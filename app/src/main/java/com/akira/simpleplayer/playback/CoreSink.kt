package com.akira.simpleplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.akira.simpleplayer.core.Core
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The player's audio output: equalizer and spectrum (Rust core) in front of ExoPlayer's
 * own sink.
 *
 * Why not AudioProcessors: ExoPlayer skips custom processors whenever it sends
 * high-resolution audio out as float, so the equalizer and the visualizer would stop for
 * every 24-bit song. Doing the work here, on the decoded PCM before the sink sees it,
 * works for 16-bit, 24-bit, 32-bit and float alike.
 *
 * Hi-Res: the wrapped sink is built with float output, so 24-bit / float songs keep their
 * resolution. With Hi-Res off we tell the decoder that float is not wanted, and songs are
 * decoded to 16-bit as before. [OutputRouter] picks the output format per song on
 * Android 14+.
 *
 * Shared mode (the desktop app's): when the system mixer runs at another rate than the
 * song, the core's own windowed-sinc resampler converts the song to the mixer's rate, with
 * the equalizer applied at that rate, and the sink is told it gets float at the mixer rate.
 * The system then has nothing to convert, and since every song now reaches the sink in
 * the same format, songs of different rates follow each other without reopening the
 * output.
 */
class CoreSink(
    sink: AudioSink,
    private val hiRes: () -> Boolean,
    private val router: OutputRouter
) : ForwardingAudioSink(sink) {

    private var encoding = Core.PCM_16
    private var bytesPerSample = 2
    private var channels = 2
    private var rate = 44_100
    private var supported = true

    /** The decoder buffer currently being handed over (re-sent until accepted). */
    private var pending: ByteBuffer? = null
    private var pendingOut: ByteBuffer? = null
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var copyIn: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** Core resampler handle while converting this song (0 = not converting). */
    private var pipe = 0L

    override fun getFormatSupport(format: Format): Int {
        // Hi-Res off: decoders are asked for 16-bit instead of float
        if (!hiRes() && MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            format.pcmEncoding == C.ENCODING_PCM_FLOAT
        ) return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        return super.getFormatSupport(format)
    }

    // Media3 1.11: the (Format, Int, IntArray) overload is final and forwards here
    override fun configure(config: AudioSink.AudioSinkConfig) {
        val inputFormat = config.format
        val code = when (inputFormat.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> Core.PCM_16
            C.ENCODING_PCM_FLOAT -> Core.PCM_FLOAT
            C.ENCODING_PCM_24BIT -> Core.PCM_24
            C.ENCODING_PCM_32BIT -> Core.PCM_32
            else -> -1
        }
        supported = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType && code >= 0
        if (supported) {
            encoding = code
            bytesPerSample = when (code) { Core.PCM_16 -> 2; Core.PCM_24 -> 3; else -> 4 }
            channels = inputFormat.channelCount
            rate = inputFormat.sampleRate
        }
        pending = null
        pendingOut = null
        Core.eqReset()
        Core.spectrumStop()
        freePipe()
        // pick the output mixer for this song before the AudioTrack is created
        val target = if (supported) router.prepare(rate, channels, inputFormat.pcmEncoding) else 0
        if (target > 0 && channels in 1..8) {
            pipe = Core.pipeNew(rate, target, channels)
            val out = inputFormat.buildUpon()
                .setSampleRate(target)
                .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                .build()
            // new format (rate/float); keep channel mapping and timeline info, drop the
            // buffer size override (it was sized for the source format)
            val b = AudioSink.AudioSinkConfig.Builder(out)
                .setOutputChannelMapping(config.outputChannelMapping)
            b.setTimeline(config.timeline)
            config.mediaPeriodId?.let { b.setMediaPeriodId(it) }
            super.configure(b.build())
        } else {
            super.configure(config)
        }
    }

    private fun freePipe() {
        if (pipe != 0L) {
            Core.pipeFree(pipe)
            pipe = 0L
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!supported) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        if (pending !== buffer) {
            pending = buffer
            pendingOut = process(buffer)
        }
        val out = pendingOut ?: buffer
        val done = super.handleBuffer(out, presentationTimeUs, encodedAccessUnitCount)
        if (done) {
            pending = null
            pendingOut = null
            if (out !== buffer) buffer.position(buffer.limit())
        }
        return done
    }

    /** Spectrum + equalizer (+ resampling) for one decoder buffer; null = pass it on as it is. */
    private fun process(buffer: ByteBuffer): ByteBuffer? {
        val n = buffer.remaining()
        if (n == 0) return null
        val src = if (buffer.isDirect) buffer else {
            if (copyIn.capacity() < n) copyIn = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN)
            copyIn.clear()
            copyIn.put(buffer.duplicate()).flip()
            copyIn
        }
        val samples = n / bytesPerSample
        if (pipe != 0L) {
            val need = Core.pipeMaxBytes(pipe, samples)
            if (scratch.capacity() < need) scratch = ByteBuffer.allocateDirect(need).order(ByteOrder.LITTLE_ENDIAN)
            scratch.clear()
            val frames = Core.pipeProcess(pipe, src, src.position(), samples, encoding, rate, scratch, scratch.capacity())
            scratch.position(0)
            scratch.limit((frames.coerceAtLeast(0)) * channels * 4)
            return scratch
        }
        Core.spectrumFeed(src, src.position(), samples, channels, encoding, rate)
        if (scratch.capacity() < n) scratch = ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        if (!Core.eqProcess(src, src.position(), scratch, 0, samples, channels, encoding, rate)) {
            return null // equalizer off or flat: untouched
        }
        scratch.limit(samples * bytesPerSample)
        scratch.position(0)
        return scratch
    }

    override fun flush() {
        pending = null
        pendingOut = null
        if (pipe != 0L) Core.pipeReset(pipe)
        Core.eqReset()
        Core.spectrumStop()
        super.flush()
    }

    override fun reset() {
        pending = null
        pendingOut = null
        freePipe()
        Core.spectrumStop()
        super.reset()
    }

    override fun release() {
        freePipe()
        super.release()
    }
}
