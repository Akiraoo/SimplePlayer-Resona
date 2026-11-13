package com.akira.simpleplayer.playback

import com.akira.simpleplayer.core.Core
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI talks to. Commands go to the playback service (same process, main thread);
 * when the service is not up yet they wait until it is.
 *
 * The play order itself lives in the Rust core: the service only ever holds the current
 * song and the one after it.
 */
object Playback {
    data class State(
        val track: Int = -1,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        val durationMs: Long = 0,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bitrate: Int = 0,
        val error: String? = null,
        /** Bumped whenever the play order or the user's queue changes. */
        val queueGen: Int = 0
    )

    internal val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable

    private var service: PlaybackService? = null
    private val pending = ArrayDeque<(PlaybackService) -> Unit>()

    internal fun attach(s: PlaybackService) {
        service = s
        while (pending.isNotEmpty()) pending.removeFirst()(s)
    }

    internal fun detach(s: PlaybackService) {
        if (service === s) service = null
    }

    private fun run(block: (PlaybackService) -> Unit) {
        val s = service
        if (s != null) block(s) else pending.addLast(block)
    }

    fun positionMs(): Long = service?.positionMs() ?: Core.resumePosition()

    fun playList(list: IntArray, row: Int) = run { it.playList(list, row) }
    fun playUpnext(index: Int) = run { it.playUpnext(index) }
    fun toggle() = run { it.toggle() }
    fun play() = run { it.play() }
    fun pause() = run { it.pause() }
    fun next() = run { it.next() }
    fun prev() = run { it.prev() }
    /** Always the previous song (never "start this one over"): for swiping the cover. */
    fun prevTrack() = run { it.prevTrack() }
    fun seekTo(ms: Long) = run { it.seekTo(ms) }

    fun queueAdd(track: Int) {
        Core.queueAdd(track)
        run { it.orderChanged() }
    }

    fun queueRemove(index: Int) {
        if (Core.queueRemove(index)) run { it.orderChanged() }
    }

    fun queueClear() {
        Core.queueClear()
        run { it.orderChanged() }
    }

    /** Shuffle / repeat changed in the core settings. */
    fun orderChanged() = run { it.orderChanged() }

    /** Hi-Res / bit-perfect settings changed. */
    fun outputSettingsChanged() = run { it.outputSettingsChanged() }

    /** A resync replaced the library. */
    fun libraryChanged() = run { it.libraryChanged() }
}
