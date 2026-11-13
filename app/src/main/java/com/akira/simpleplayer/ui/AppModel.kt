package com.akira.simpleplayer.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.akira.simpleplayer.AppExecutors
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.CoreBoot
import com.akira.simpleplayer.core.Lib
import com.akira.simpleplayer.playback.Playback
import com.akira.simpleplayer.sync.SyncService
import com.akira.simpleplayer.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Pages under the mini player (the now-playing screen is drawn over them). */
sealed class Page {
    data object Home : Page()
    data class Songs(val source: String, val title: String) : Page()
    data object Settings : Page()
    data object Equalizer : Page()
    data object Custom : Page()
}

data class SyncState(
    val running: Boolean = false,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String = ""
)

class AppModel(app: Application) : AndroidViewModel(app) {

    init {
        CoreBoot.ensure(app)
    }

    /* ---------- settings (the core keeps them) ---------- */

    var server by mutableStateOf("")
    var light by mutableStateOf(false)
    var accent by mutableStateOf(ACCENTS[0])
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(0)
    var english by mutableStateOf(false)
    var autoUpdate by mutableStateOf(true)
    var sortCol by mutableIntStateOf(0)
    var sortAsc by mutableStateOf(true)
    var hiRes by mutableStateOf(true)
    var bitPerfect by mutableStateOf(false)
    var resample by mutableStateOf(true)

    private fun loadConfig() {
        val o = runCatching { JSONObject(Core.configJson()) }.getOrDefault(JSONObject())
        server = o.optString("server")
        light = o.optBoolean("light")
        accent = o.optString("accent").ifBlank { ACCENTS[0] }
        shuffle = o.optBoolean("shuffle")
        repeat = o.optInt("repeat")
        english = o.optString("lang") == "en"
        Lang.english = english
        autoUpdate = o.optBoolean("autoUpdate", true)
        sortCol = o.optInt("sortCol")
        sortAsc = o.optBoolean("sortAsc", true)
        hiRes = o.optBoolean("hires", true)
        bitPerfect = o.optBoolean("bitperfect", false)
        resample = o.optBoolean("resample", true)
    }

    private fun set(key: String, value: Any) = Core.setConfig(key, value.toString())

    fun setServerAddress(v: String) {
        set("server", v)
        loadConfig()
    }

    fun changeLight(v: Boolean) { light = v; set("light", v) }
    fun changeAccent(v: String) { accent = v; set("accent", v) }
    fun changeEnglish(v: Boolean) {
        english = v
        Lang.english = v
        set("lang", if (v) "en" else "zh-TW")
    }
    fun changeAutoUpdate(v: Boolean) { autoUpdate = v; set("autoUpdate", v) }

    fun changeHiRes(v: Boolean) {
        hiRes = v
        set("hires", v)
        Playback.outputSettingsChanged()
    }

    fun changeResample(v: Boolean) {
        resample = v
        set("resample", v)
        Playback.outputSettingsChanged()
    }

    fun changeBitPerfect(v: Boolean) {
        bitPerfect = v
        set("bitperfect", v)
        Playback.outputSettingsChanged()
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        set("shuffle", shuffle)
        Playback.orderChanged()
    }

    /** off → all → one → off */
    fun cycleRepeat() {
        repeat = (repeat + 1) % 3
        set("repeat", repeat)
        Playback.orderChanged()
    }

    fun setSort(col: Int, asc: Boolean) {
        sortCol = col
        sortAsc = asc
        set("sortCol", col)
        set("sortAsc", asc)
        refreshRows()
    }

    /* ---------- library ---------- */

    /** Bumped when the library was replaced (UI reads Lib again). */
    var libGen by mutableLongStateOf(0)
    var playlists by mutableStateOf<List<Lib.Playlist>>(emptyList())
    val songCount get() = Lib.size

    var page by mutableStateOf<Page>(Page.Home)

    /** Backdrop blur shared by the page content (source) and the mini player (effect). */
    val haze = dev.chrisbanes.haze.HazeState()

    /** Scroll position of the playlist grid (shared by the page and the swipe-back underlay). */
    val homeGrid = androidx.compose.foundation.lazy.grid.LazyGridState()
    private val scroll = HashMap<String, Pair<Int, Int>>()
    fun scrollPos(source: String) = scroll[source] ?: (0 to 0)
    fun saveScrollPos(source: String, index: Int, offset: Int) { scroll[source] = index to offset }
    var nowPlayingOpen by mutableStateOf(false)
    var source by mutableStateOf("all")
    var query by mutableStateOf("")
    var rows by mutableStateOf(IntArray(0))
    private var rowsJob: Job? = null

    fun open(p: Page) {
        if (p is Page.Songs) {
            if (p.source != source) query = ""
            source = p.source
            selecting = false
            picked.clear()
            set("lastView", p.source)
            refreshRows()
        }
        page = p
    }

    fun back(): Boolean {
        when {
            nowPlayingOpen -> nowPlayingOpen = false
            selecting -> { selecting = false; picked.clear() }
            page is Page.Custom -> page = Page.Settings
            page != Page.Home -> page = Page.Home
            else -> return false
        }
        return true
    }

    fun search(q: String) {
        query = q
        refreshRows()
    }

    fun refreshRows() {
        val src = source
        val q = query
        val col = sortCol
        val asc = sortAsc
        rowsJob?.cancel()
        rowsJob = viewModelScope.launch {
            val r = withContext(AppExecutors.cpu) { Core.view(src, q, col, asc) }
            rows = r
        }
    }

    fun playRow(row: Int) {
        if (row in rows.indices) Playback.playList(rows, row)
    }

    fun playAll(shuffled: Boolean) {
        if (rows.isEmpty()) return
        if (shuffled != shuffle) toggleShuffle()
        val start = if (shuffled) (rows.indices).random() else 0
        Playback.playList(rows, start)
    }

    private fun libraryReplaced() {
        Lib.reload()
        libGen = Lib.gen
        playlists = Lib.playlists
        refreshRows()
        refreshQueue()
    }

    /* ---------- next / previous with a direction for the cover animation ---------- */

    /** +1 = the cover leaves to the left (next), -1 = to the right (previous). */
    var coverDir = 1
        private set
    private var coverDirAt = 0L

    /** Direction of the latest song change (asked for in the last 1.5 s; otherwise forward). */
    fun takeCoverDir(): Int {
        val d = if (System.currentTimeMillis() - coverDirAt < 1500) coverDir else 1
        coverDir = 1
        coverDirAt = 0
        return d
    }

    private fun aim(d: Int) { coverDir = d; coverDirAt = System.currentTimeMillis() }

    fun next() { aim(1); Playback.next() }
    fun prev() { aim(-1); Playback.prev() }
    fun prevTrack() { aim(-1); Playback.prevTrack() }

    /* ---------- the user's queue ---------- */

    var upnext by mutableStateOf(IntArray(0))
    var upnextCurrent by mutableIntStateOf(-1)

    fun refreshQueue() {
        upnext = Core.upnext()
        upnextCurrent = Core.upnextCurrent()
    }

    /* ---------- selection (custom share lists) ---------- */

    var selecting by mutableStateOf(false)
    val picked = mutableStateListOf<Int>()

    fun togglePick(track: Int) {
        if (track in picked) picked.remove(track) else picked.add(track)
        if (picked.isEmpty()) selecting = false
    }

    /* ---------- resync ---------- */

    var sync by mutableStateOf(SyncState())
    private var syncJob: Job? = null

    fun startSync(scanFirst: Boolean) {
        if (server.isBlank()) return
        // run inside a foreground service, so leaving the app does not stop it half way
        Core.syncStart(scanFirst)
        SyncService.start(getApplication())
        watchSync()
    }

    fun cancelSync() {
        SyncService.cancel(getApplication())
        sync = sync.copy(running = false)
    }

    private fun watchSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            while (true) {
                val o = runCatching { JSONObject(Core.syncState()) }.getOrDefault(JSONObject())
                sync = SyncState(
                    o.optBoolean("running"), o.optString("stage"),
                    o.optInt("done"), o.optInt("total"), o.optString("error")
                )
                // compare with what this screen shows: the sync service may already have
                // reloaded Lib for the player
                if (o.optLong("gen") != libGen) {
                    if (Lib.refreshIfChanged()) Playback.libraryChanged()
                    libraryReplaced()
                }
                if (!sync.running) break
                delay(300)
            }
        }
    }

    /* ---------- updates ---------- */

    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateMessage by mutableStateOf("")
    val isTestBuild = app.packageName.endsWith(".remake")

    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            updateMessage = if (manual) tr("檢查中…", "Checking…") else ""
            runCatching { Updater.latest() }
                .onSuccess { r ->
                    val mine = Updater.installedVersion(getApplication())
                    if (r != null && Updater.isNewer(r.version, mine)) {
                        update = r
                        updateMessage = ""
                    } else if (manual) {
                        updateMessage = tr("已是最新版本（$mine）", "Up to date ($mine)")
                    }
                }
                .onFailure { if (manual) updateMessage = tr("檢查失敗：", "Check failed: ") + it.message }
        }
    }

    fun downloadUpdate() {
        val r = update ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            updateProgress = 0f
            runCatching { Updater.download(ctx, r) { p -> updateProgress = p } }
                .onSuccess { apk ->
                    updateProgress = null
                    if (!Updater.canInstall(ctx)) Updater.openInstallPermission(ctx)
                    else Updater.install(ctx, apk)
                }
                .onFailure {
                    updateProgress = null
                    updateMessage = tr("下載失敗：", "Download failed: ") + it.message
                }
        }
    }

    /* ---------- start ---------- */

    init {
        loadConfig()
        Lib.refreshIfChanged()
        libGen = Lib.gen
        playlists = Lib.playlists
        val last = runCatching { JSONObject(Core.configJson()).optString("lastView") }.getOrDefault("all")
        source = if (last.startsWith("pl:") && playlists.none { "pl:" + it.name == last }) "all" else last.ifBlank { "all" }
        refreshRows()
        refreshQueue()
        viewModelScope.launch {
            Playback.state.collect { refreshQueue() }
        }
        // a resync started before the app left the screen may still be running
        if (runCatching { JSONObject(Core.syncState()).optBoolean("running") }.getOrDefault(false)) watchSync()
        // the library is synced by hand (as before); only an empty one is fetched right away
        else if (server.isNotBlank() && Lib.size == 0) startSync(false)
        if (autoUpdate && !isTestBuild) checkUpdate(false)
    }
}
