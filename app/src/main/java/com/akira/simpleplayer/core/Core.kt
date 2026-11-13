package com.akira.simpleplayer.core

import java.nio.ByteBuffer

/**
 * The Rust core (`core/` → libspcore.so): library, resync, search, play order, equalizer,
 * spectrum. Every function maps to `Java_com_akira_simpleplayer_core_Core_<name>` in
 * core/src/jni_api.rs — keep the two lists in step.
 *
 * Songs are referred to by their index in the library ("track"); the index changes only
 * when a resync replaces the library ([libGen] goes up).
 */
object Core {
    init {
        System.loadLibrary("spcore")
    }

    /* start-up, settings */
    @JvmStatic external fun init(
        filesDir: String,
        legacyServer: String,
        legacyLight: Boolean,
        legacyEq: String,
        legacyTrack: String,
        legacyPosition: Long,
        legacyDuration: Long,
        chinese: Boolean
    )
    @JvmStatic external fun version(): String
    @JvmStatic external fun configJson(): String
    @JvmStatic external fun setConfig(key: String, value: String)

    /* resync */
    @JvmStatic external fun syncStart(scanFirst: Boolean)
    @JvmStatic external fun syncCancel()
    @JvmStatic external fun syncState(): String

    /* library */
    @JvmStatic external fun libGen(): Long
    /** 7 fields per track (id, title, artist, album, format, folder, cover file), U+0001 separated. */
    @JvmStatic external fun libStrings(): String
    /** 3 numbers per track: duration ms, size bytes, has cover. */
    @JvmStatic external fun libNumbers(): LongArray
    @JvmStatic external fun playlistNames(): String
    /** 2 per playlist: song count, track whose cover represents it (-1 none). */
    @JvmStatic external fun playlistInfo(): IntArray
    /** source: "all" or "pl:<name>"; sort 0..6. */
    @JvmStatic external fun view(source: String, query: String, sort: Int, asc: Boolean): IntArray
    @JvmStatic external fun streamUrl(track: Int): String
    /** kind: 0 song share page, 1 download, 2 server playlist, 3 custom share list. */
    @JvmStatic external fun webUrl(kind: Int, value: String): String
    /** "<seconds or empty>\t<text>" per line; may use the network. */
    @JvmStatic external fun lyrics(track: Int): Array<String>

    /* play order */
    @JvmStatic external fun playList(list: IntArray, row: Int): Int
    @JvmStatic external fun playUpnext(index: Int): Int
    @JvmStatic external fun current(): Int
    @JvmStatic external fun planNext(): Int
    @JvmStatic external fun commitPlan(): Int
    @JvmStatic external fun advance(auto: Boolean): Int
    /** track to play, -1 restart the current one, -2 nothing */
    @JvmStatic external fun prev(positionMs: Long): Int
    @JvmStatic external fun queueAdd(track: Int)
    @JvmStatic external fun queueRemove(index: Int): Boolean
    @JvmStatic external fun queueClear()
    @JvmStatic external fun upnext(): IntArray
    @JvmStatic external fun upnextCurrent(): Int
    @JvmStatic external fun saveSession(positionMs: Long, durationMs: Long)
    @JvmStatic external fun resumePosition(): Long
    @JvmStatic external fun resumeDuration(): Long

    /* equalizer settings */
    @JvmStatic external fun eqJson(): String
    @JvmStatic external fun eqSetEnabled(on: Boolean)
    @JvmStatic external fun eqSelect(name: String)
    @JvmStatic external fun eqPreview(preset: String)
    @JvmStatic external fun eqSave(preset: String, replacing: String): String
    @JvmStatic external fun eqDelete(name: String)
    @JvmStatic external fun eqImport(content: String, fallbackName: String): Int
    @JvmStatic external fun eqError(): String
    @JvmStatic external fun eqExport(): String
    @JvmStatic external fun eqResponse(preset: String, freqs: FloatArray): FloatArray
    @JvmStatic external fun eqAutoPreamp(preset: String): Float

    /* audio path (direct buffers only); encoding: PCM_* below */
    @JvmStatic external fun eqProcess(
        input: ByteBuffer, inPos: Int, output: ByteBuffer, outPos: Int,
        samples: Int, channels: Int, encoding: Int, rate: Int
    ): Boolean
    @JvmStatic external fun eqReset()
    @JvmStatic external fun spectrumFeed(
        input: ByteBuffer, pos: Int, samples: Int, channels: Int, encoding: Int, rate: Int
    )
    @JvmStatic external fun spectrumRead(dest: FloatArray)

    /* shared-mode resampling (song rate → output mixer rate, equalizer at the output rate) */
    @JvmStatic external fun pipeNew(srcRate: Int, dstRate: Int, channels: Int): Long
    @JvmStatic external fun pipeFree(handle: Long)
    @JvmStatic external fun pipeReset(handle: Long)
    @JvmStatic external fun pipeMaxBytes(handle: Long, samples: Int): Int
    @JvmStatic external fun pipeProcess(
        handle: Long, input: ByteBuffer, inPos: Int, samples: Int, encoding: Int, srcRate: Int,
        output: ByteBuffer, outCap: Int
    ): Int
    @JvmStatic external fun pipeFlush(handle: Long, output: ByteBuffer, outCap: Int): Int
    @JvmStatic external fun spectrumStop()

    const val SPECTRUM_BARS = 48
    const val SEP = '\u0001'

    const val PCM_16 = 0
    const val PCM_FLOAT = 1
    const val PCM_24 = 2
    const val PCM_32 = 3
}
