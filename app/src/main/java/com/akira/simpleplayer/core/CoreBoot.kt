package com.akira.simpleplayer.core

import android.content.Context
import java.util.Locale

/** Loads the core once per process (the activity and the playback service both call it). */
object CoreBoot {
    @Volatile private var done = false

    @Synchronized
    fun ensure(context: Context) {
        if (done) return
        val app = context.applicationContext
        // settings of app versions 1.x; only read on the first start of 2.x
        val old = app.getSharedPreferences("simple_player", Context.MODE_PRIVATE)
        val eq = app.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
        val lang = Locale.getDefault().language
        Core.init(
            app.filesDir.absolutePath,
            old.getString("apiBase", "") ?: "",
            old.getBoolean("lightTheme", false),
            eq.getString("state", "") ?: "",
            old.getString("last_track", "") ?: "",
            old.getLong("last_position", 0L),
            old.getLong("last_duration", 0L),
            lang == "zh"
        )
        Lib.reload()
        done = true
    }
}

/**
 * The library as the UI and the notification need it, copied out of the core in two calls
 * (fast even for tens of thousands of songs). Reloaded when a resync replaces the library.
 */
object Lib {
    @Volatile var gen = 0L; private set
    @Volatile private var fields: List<String> = emptyList()
    @Volatile private var numbers: LongArray = LongArray(0)
    @Volatile var playlists: List<Playlist> = emptyList(); private set
    val size get() = numbers.size / 3

    data class Playlist(val name: String, val count: Int, val coverTrack: Int)

    @Synchronized
    fun reload() {
        val g = Core.libGen()
        val s = Core.libStrings()
        fields = if (s.isEmpty()) emptyList() else s.split(Core.SEP)
        numbers = Core.libNumbers()
        val names = Core.playlistNames().let { if (it.isEmpty()) emptyList() else it.split(Core.SEP) }
        val info = Core.playlistInfo()
        playlists = names.mapIndexed { i, n ->
            Playlist(n, info.getOrElse(i * 2) { 0 }, info.getOrElse(i * 2 + 1) { -1 })
        }
        gen = g
    }

    /** Reloads if the core has a newer library. Returns true when it did. */
    fun refreshIfChanged(): Boolean {
        if (Core.libGen() == gen) return false
        reload()
        return true
    }

    private fun f(t: Int, k: Int) = fields.getOrNull(t * 7 + k) ?: ""
    fun id(t: Int) = f(t, 0)
    fun title(t: Int) = f(t, 1)
    fun artist(t: Int) = f(t, 2)
    fun album(t: Int) = f(t, 3)
    fun format(t: Int) = f(t, 4)
    fun folder(t: Int) = f(t, 5)
    /** Cached cover file path, "" when there is none. */
    fun cover(t: Int) = f(t, 6)
    fun durationMs(t: Int) = numbers.getOrElse(t * 3) { 0L }
    fun sizeBytes(t: Int) = numbers.getOrElse(t * 3 + 1) { 0L }
    fun valid(t: Int) = t in 0 until size
}
