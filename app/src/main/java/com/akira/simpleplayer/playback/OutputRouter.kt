package com.akira.simpleplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import com.akira.simpleplayer.ui.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Chooses how each song leaves the phone.
 *
 * Android normally mixes every app into one output with a fixed format (usually 48 kHz),
 * so a 44.1 kHz or 96 kHz song is resampled on the way out. Android 14 (API 34) lets an
 * app ask for the output mixer to follow its own format ("preferred mixer attributes"),
 * on the devices whose driver offers it:
 *
 *  - USB DAC, bit-perfect: the samples reach the DAC untouched, like WASAPI exclusive on
 *    Windows. Other apps' media and notification sounds are muted meanwhile; the volume
 *    is the DAC's own.
 *  - any device that lists the song's sample rate: the mixer runs at that rate (no
 *    resampling, but still shared with other apps).
 *
 * Before Android 14, or where the device offers nothing, the system mixer is used as
 * before; Hi-Res still sends songs at their own rate and resolution into it.
 */
class OutputRouter(
    context: Context,
    private val hiRes: () -> Boolean,
    private val bitPerfect: () -> Boolean,
    private val resample: () -> Boolean
) {

    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var applied: AudioDeviceInfo? = null

    /** Bit depth of the song's source (set when its decoder starts; 0 = lossy / unknown). */
    @Volatile var sourceBits = 0

    /** Last song format, to pick the mixer again when the output device changes. */
    private var last: Triple<Int, Int, Int>? = null

    /**
     * Called before the AudioTrack for a new format is created. Returns the rate the app
     * should convert the song to itself (0 = send it at its own rate).
     */
    fun prepare(rate: Int, channels: Int, pcmEncoding: Int): Int {
        last = Triple(rate, channels, pcmEncoding)
        // what reaches the AudioTrack: 16-bit stays 16-bit, everything else goes out as float
        val outEncoding = if (pcmEncoding == C.ENCODING_PCM_16BIT) AudioFormat.ENCODING_PCM_16BIT
        else AudioFormat.ENCODING_PCM_FLOAT
        val bits = when (pcmEncoding) {
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            // float from the decoder: the source's own depth
            C.ENCODING_PCM_FLOAT -> sourceBits.takeIf { it > 16 } ?: 24
            else -> 16
        }
        val device = currentDevice()
        val route = routeName(device)
        val song = if (sourceBits == 0 && pcmEncoding == C.ENCODING_PCM_16BIT) fmtRate(rate)
        else "${fmtRate(rate)} / ${bits}-bit"

        if (Build.VERSION.SDK_INT >= 34 && device != null && (hiRes() || bitPerfect())) {
            val picked = runCatching { pick(device, rate, channels, outEncoding) }.getOrNull()
            if (picked != null) {
                val ok = runCatching { am.setPreferredMixerAttributes(attrs, device, picked) }.getOrDefault(false)
                if (ok) {
                    applied = device
                    val exclusive = picked.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                    publish(
                        if (exclusive) tr("$route · 獨佔 bit-perfect · $song", "$route · exclusive bit-perfect · $song")
                        else tr("$route · 輸出跟隨歌曲 $song", "$route · output follows the song, $song"),
                        exclusive
                    )
                    return 0
                }
            }
            clear()
        } else if (Build.VERSION.SDK_INT >= 34) {
            clear()
        }

        // shared system mixer: the rate it runs at for media on the current output
        @Suppress("DEPRECATION")
        val mixRate = runCatching {
            android.media.AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
        if (resample() && mixRate > 0 && mixRate != rate) {
            // like the desktop app's shared mode: convert with our own resampler, so the
            // system has nothing left to convert (and songs of different rates follow each
            // other without reopening the output)
            publish(
                tr("$route · $song → 高品質重新取樣 ${fmtRate(mixRate)}（系統混音不再轉換）",
                    "$route · $song → resampled to ${fmtRate(mixRate)} in the app (no conversion by the system)"),
                false
            )
            return mixRate
        }
        val mixer = if (mixRate > 0) tr("系統混音 ${fmtRate(mixRate)}", "system mixer ${fmtRate(mixRate)}")
        else tr("系統混音", "system mixer")
        publish(
            when {
                mixRate == rate -> tr("$route · $song，與系統混音同為 ${fmtRate(rate)}，不需轉換",
                    "$route · $song, same rate as the system mixer, no conversion")
                hiRes() -> tr("$route · 原始格式送出 $song → $mixer", "$route · sent as $song → $mixer")
                else -> tr("$route · 16-bit → $mixer", "$route · 16-bit → $mixer")
            },
            false
        )
        return 0
    }

    @RequiresApi(34)
    private fun pick(device: AudioDeviceInfo, rate: Int, channels: Int, encoding: Int): AudioMixerAttributes? {
        val list = am.getSupportedMixerAttributes(device)
        if (list.isEmpty()) return null
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        fun sameShape(m: AudioMixerAttributes) =
            m.format.sampleRate == rate && m.format.channelMask == mask
        if (bitPerfect() && isUsb(device)) {
            // bit-perfect needs exactly the format the app writes
            list.firstOrNull {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    sameShape(it) && it.format.encoding == encoding
            }?.let { return it }
        }
        if (!hiRes()) return null
        // shared mixer at the song's rate: the widest sample format on offer
        val rank = mapOf(
            AudioFormat.ENCODING_PCM_FLOAT to 4, AudioFormat.ENCODING_PCM_32BIT to 3,
            AudioFormat.ENCODING_PCM_24BIT_PACKED to 2, AudioFormat.ENCODING_PCM_16BIT to 1
        )
        return list.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT && sameShape(it) }
            .maxByOrNull { rank[it.format.encoding] ?: 0 }
    }

    /** Puts the system's own mixer back (other apps get their sound back from bit-perfect). */
    fun clear() {
        if (Build.VERSION.SDK_INT < 34) return
        val d = applied ?: return
        runCatching { am.clearPreferredMixerAttributes(attrs, d) }
        applied = null
    }

    /** The output device changed: the next AudioTrack picks again with the last format. */
    fun lastFormat() = last

    private fun currentDevice(): AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { am.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull()
    } else {
        // older: the most specific connected output
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        order.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
    }

    companion object {
        private val mutable = MutableStateFlow(Status("", false))
        /** What the current song is going out through, for the UI. */
        val status: StateFlow<Status> = mutable

        data class Status(val text: String, val exclusive: Boolean)

        private fun publish(text: String, exclusive: Boolean) {
            mutable.value = Status(text, exclusive)
        }

        fun isUsb(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun routeName(d: AudioDeviceInfo?): String = when (d?.type) {
            null -> tr("輸出", "Output")
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB DAC"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> tr("藍牙", "Bluetooth")
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> tr("藍牙 LE", "Bluetooth LE")
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> tr("有線耳機", "Wired headphones")
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> tr("喇叭", "Speaker")
            else -> d.productName?.toString()?.takeIf { it.isNotBlank() } ?: tr("輸出", "Output")
        }

        fun fmtRate(rate: Int) = if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0)
    }
}
