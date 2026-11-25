package com.akira.simpleplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.akira.simpleplayer.MainActivity
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.CoreBoot
import com.akira.simpleplayer.core.Lib
import java.io.File

/**
 * Background playback, notification, lock screen, Bluetooth buttons.
 *
 * ExoPlayer holds two items at most: the song playing (always index 0) and the next one
 * the core planned, preloaded so songs follow each other without a gap. Next / previous
 * from anywhere (app, notification, headset) go through the core, so the order rules are
 * the desktop app's.
 *
 * Audio path: decoder → EqualizerProcessor → SpectrumProcessor → AudioTrack.
 */
class PlaybackService : MediaSessionService() {

    private lateinit var exo: ExoPlayer
    private lateinit var router: OutputRouter
    @Volatile private var hiRes = true
    @Volatile private var bitPerfect = false
    @Volatile private var resample = true

    private fun loadOutputSettings() {
        val o = runCatching { org.json.JSONObject(Core.configJson()) }.getOrNull()
        hiRes = o?.optBoolean("hires", true) ?: true
        bitPerfect = o?.optBoolean("bitperfect", false) ?: false
        resample = o?.optBoolean("resample", true) ?: true
    }

    /** USB DAC / headphones / Bluetooth plugged in or out: pick the output format again. */
    private val deviceWatch = object : AudioDeviceCallback() {
        private var first = true
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            // the callback reports every device once when registered
            if (first) { first = false; return }
            if (added.any { it.isSink }) handler.postDelayed({ reopenOutput() }, 400)
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isSink }) handler.postDelayed({ reopenOutput() }, 400)
        }
    }

    /**
     * Rebuilds the audio output at the same spot, so a new device or new output settings
     * take effect now rather than at the next song.
     */
    fun reopenOutput() {
        if (exo.mediaItemCount == 0 || exo.playbackState == Player.STATE_IDLE) {
            router.clear()
            return
        }
        val pos = exo.currentPosition
        val play = exo.playWhenReady
        exo.stop()
        exo.prepare()
        exo.seekTo(0, pos)
        exo.playWhenReady = play
    }

    fun outputSettingsChanged() {
        loadOutputSettings()
        reopenOutput()
    }
    private lateinit var session: MediaSession
    private val handler = Handler(Looper.getMainLooper())
    private var queueGen = 0

    private val saver = object : Runnable {
        override fun run() {
            saveSession()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CoreBoot.ensure(this)

        loadOutputSettings()
        router = OutputRouter(this, { hiRes }, { bitPerfect }, { resample })
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = CoreSink(
                DefaultAudioSink.Builder(context)
                    // float output keeps 24-bit songs at full resolution; CoreSink asks the
                    // decoder for 16-bit instead when Hi-Res is off
                    .setEnableFloatOutput(true)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build(),
                { hiRes },
                router
            )

            // Decode only real high-resolution sources (24/32-bit, float) to float; 16-bit
            // and lossy songs stay 16-bit, so they can go out bit-perfect as PCM 16.
            // (ExoPlayer would otherwise ask every decoder for float once float output is on.)
            override fun buildAudioRenderers(
                context: android.content.Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                out.add(object : MediaCodecAudioRenderer(
                    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, audioSink
                ) {
                    override fun getMediaFormat(
                        format: Format, codecMimeType: String, codecMaxInputSize: Int, codecOperatingRate: Float
                    ): MediaFormat {
                        val mf = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate)
                        val bits = when (format.pcmEncoding) {
                            C.ENCODING_PCM_24BIT -> 24
                            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
                            C.ENCODING_PCM_16BIT -> 16
                            else -> 0 // lossy or unknown
                        }
                        router.sourceBits = bits
                        if (!hiRes || bits <= 16) {
                            mf.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                        }
                        return mf
                    }
                })
            }
        }

        exo = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, SessionPlayer(exo))
            .setSessionActivity(open)
            .build()

        getSystemService(AudioManager::class.java).registerAudioDeviceCallback(deviceWatch, handler)
        restore()
        Playback.attach(this)
        publish()
    }

    /** What the session (notification, lock screen, headset) sees. */
    private inner class SessionPlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addAll(
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                ).build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        // explicitly the service's: inside ForwardingPlayer a bare next() is Player.next(),
        // which would just jump to the preloaded item behind the core's back
        override fun seekToNext() = this@PlaybackService.next()
        override fun seekToNextMediaItem() = this@PlaybackService.next()
        override fun seekToPrevious() = this@PlaybackService.prev()
        override fun seekToPreviousMediaItem() = this@PlaybackService.prev()
        override fun play() = this@PlaybackService.play()
    }

    private fun item(t: Int): MediaItem {
        val cover = Lib.cover(t)
        return MediaItem.Builder()
            .setMediaId(Lib.id(t))
            .setUri(Core.streamUrl(t))
            .setTag(t)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(Lib.title(t))
                    .setArtist(Lib.artist(t))
                    .setAlbumTitle(Lib.album(t))
                    .setArtworkUri(if (cover.isEmpty()) null else Uri.fromFile(File(cover)))
                    .build()
            )
            .build()
    }

    /** The song that was playing when the app was last closed, paused where it was left. */
    private fun restore() {
        val t = Core.current()
        if (t < 0 || !Lib.valid(t)) return
        exo.setMediaItem(item(t), Core.resumePosition())
        planNext()
    }

    /** Replaces whatever follows the current item with the core's next song. */
    private fun planNext() {
        if (exo.mediaItemCount == 0) return
        val cur = exo.currentMediaItemIndex
        if (exo.mediaItemCount > cur + 1) exo.removeMediaItems(cur + 1, exo.mediaItemCount)
        val n = Core.planNext()
        if (n >= 0 && Lib.valid(n)) exo.addMediaItem(item(n))
    }

    private fun start(t: Int, playNow: Boolean = true) {
        if (t < 0 || !Lib.valid(t)) {
            publish()
            return
        }
        exo.setMediaItem(item(t), 0L)
        planNext()
        exo.prepare()
        exo.playWhenReady = playNow
        queueGen++
        publish(error = null)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
            // the preloaded song started by itself
            Core.commitPlan()
            val cur = exo.currentMediaItemIndex
            if (cur > 0) exo.removeMediaItems(0, cur)
            planNext()
            queueGen++
            publish()
            saveSession()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // nothing was planned: end of the list, or a reshuffle for repeat all
                val t = Core.advance(true)
                if (t >= 0) start(t) else {
                    exo.playWhenReady = false
                    exo.seekTo(0, 0L)
                    queueGen++
                }
            }
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(saver)
            if (isPlaying) handler.postDelayed(saver, 5000) else saveSession()
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onAudioSessionIdChanged(audioSessionId: Int) = publish()

        override fun onPlayerError(error: PlaybackException) {
            publish(error = error.errorCodeName.removePrefix("ERROR_CODE_") +
                (error.cause?.message?.let { " · $it" } ?: ""))
        }
    }

    private fun publish(error: String? = Playback.state.value.error) {
        val f = exo.audioFormat
        Playback.mutable.value = Playback.State(
            track = Core.current(),
            playing = exo.playWhenReady,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            durationMs = exo.duration.takeIf { it > 0 } ?: Core.resumeDuration(),
            sampleRate = f?.sampleRate?.takeIf { it > 0 } ?: 0,
            channels = f?.channelCount?.takeIf { it > 0 } ?: 0,
            bitrate = f?.bitrate?.takeIf { it > 0 } ?: 0,
            error = error,
            queueGen = queueGen
        )
    }

    private fun saveSession() {
        if (exo.mediaItemCount == 0) return
        Core.saveSession(exo.currentPosition, exo.duration.coerceAtLeast(0))
    }

    /* ---------- commands (main thread) ---------- */

    fun positionMs(): Long = if (exo.mediaItemCount == 0) 0 else exo.currentPosition

    fun playList(list: IntArray, row: Int) = start(Core.playList(list, row))
    fun playUpnext(index: Int) = start(Core.playUpnext(index))

    fun play() {
        if (exo.mediaItemCount == 0) {
            val t = Core.current()
            if (t >= 0) start(t)
            return
        }
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0, 0L)
        exo.play()
    }

    fun pause() = exo.pause()

    fun toggle() = if (exo.playWhenReady && exo.playbackState != Player.STATE_ENDED) pause() else play()

    fun next() {
        val t = Core.advance(false)
        if (t >= 0) start(t) else publish()
    }

    fun prevTrack() {
        val r = Core.prev(0)
        if (r >= 0) start(r) else publish()
    }

    fun prev() {
        when (val r = Core.prev(positionMs())) {
            -2 -> Unit
            -1 -> exo.seekTo(0, 0L)
            else -> start(r)
        }
    }

    fun seekTo(ms: Long) {
        if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        exo.seekTo(0, ms.coerceAtLeast(0))
    }

    fun orderChanged() {
        planNext()
        queueGen++
        publish()
    }

    fun libraryChanged() {
        // indices moved; the current item keeps playing, what follows is planned again
        planNext()
        queueGen++
        publish()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saver)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceWatch)
        router.clear() // give other apps their sound back
        saveSession()
        Playback.detach(this)
        session.release()
        exo.release()
        Core.spectrumStop()
        super.onDestroy()
    }
}
