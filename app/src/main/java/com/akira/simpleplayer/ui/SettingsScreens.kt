package com.akira.simpleplayer.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.Lib
import com.akira.simpleplayer.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back"), tint = p.text) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.strong)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accent,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp))
            .background(p.raised).border(1.dp, p.line, RoundedCornerShape(12.dp)).padding(14.dp),
        content = content
    )
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = p.strong)
            if (sub != null) Text(sub, fontSize = 12.sp, color = p.muted)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(m: AppModel) {
    val p = LocalPalette.current
    var address by remember(m.server) { mutableStateOf(m.server) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("設定", "Settings")) { m.back() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 140.dp)) {
            Section(tr("伺服器", "Server")) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    label = { Text(tr("Simple Player Web Server 位址", "Simple Player Web Server address")) },
                    placeholder = { Text("192.168.0.10:55555") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val changed = address.trim() != m.server
                            m.setServerAddress(address)
                            if (changed) m.startSync(false)
                        },
                        enabled = address.isNotBlank() && address.trim() != m.server
                    ) { Text(tr("儲存", "Save")) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("曲庫：${Lib.size} 首、${m.playlists.size} 個歌單", "Library: ${Lib.size} songs, ${m.playlists.size} playlists"),
                    fontSize = 13.sp, color = p.muted
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { m.startSync(false) }, enabled = m.server.isNotBlank() && !m.sync.running) {
                        Text(tr("重新同步", "Resync"))
                    }
                    OutlinedButton(onClick = { m.startSync(true) }, enabled = m.server.isNotBlank() && !m.sync.running) {
                        Text(tr("掃描並同步", "Scan & resync"))
                    }
                }
                Text(
                    tr("「掃描並同步」會先讓伺服器重新掃描音樂資料夾（加了新歌時用）。",
                        "“Scan & resync” first makes the server rescan its music folder (after adding songs)."),
                    fontSize = 12.sp, color = p.faint, modifier = Modifier.padding(top = 6.dp)
                )
            }
            SyncBanner(m)

            Section(tr("外觀", "Appearance")) {
                SwitchRow(tr("淺色主題", "Light theme"), null, m.light, m::changeLight)
                Spacer(Modifier.height(10.dp))
                Text(tr("主題色", "Theme colour"), fontSize = 15.sp, color = p.strong)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ACCENTS.forEach { hex ->
                        val c = parseColor(hex)
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(c)
                                .border(if (m.accent.equals(hex, true)) 3.dp else 0.dp, p.strong, CircleShape)
                                .clickable { m.changeAccent(hex) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("語言", "Language"), fontSize = 15.sp, color = p.strong)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("繁體中文", !m.english) { m.changeEnglish(false) }
                    Chip("English", m.english) { m.changeEnglish(true) }
                }
            }

            Section(tr("輸出", "Output")) {
                val status by com.akira.simpleplayer.playback.OutputRouter.status.collectAsState()
                SwitchRow(
                    tr("Hi-Res 輸出", "Hi-Res output"),
                    tr("依歌曲原本的採樣率送出，24-bit 歌曲以浮點傳遞，不先降成 16-bit。",
                        "Songs go out at their own sample rate; 24-bit ones as float instead of being cut to 16-bit."),
                    m.hiRes, m::changeHiRes
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    tr("高品質重新取樣", "High-quality resampling"),
                    tr("系統混音的取樣率跟歌曲不同時（例如藍牙 96 kHz、歌曲 44.1 kHz），改由 app 用電腦版同一套重新取樣器轉換，不交給 Android；不同取樣率的歌也能無縫接續。所有 Android 版本可用。",
                        "When the system mixer runs at another rate than the song (e.g. Bluetooth at 96 kHz, song at 44.1 kHz), the app converts it with the desktop app's resampler instead of Android's; songs of different rates then follow each other without a gap. All Android versions."),
                    m.resample, m::changeResample
                )
                Spacer(Modifier.height(6.dp))
                val canExclusive = android.os.Build.VERSION.SDK_INT >= 34
                SwitchRow(
                    tr("USB DAC 獨佔（bit-perfect）", "USB DAC exclusive (bit-perfect)"),
                    if (canExclusive) tr("播放時其他 app 的媒體和通知聲會被靜音，音量改由 DAC 控制。開著等化器就不算 bit-perfect。",
                        "Other apps' media and notification sounds are muted while playing; the DAC controls the volume. Not bit-perfect while the equalizer is on.")
                    else tr("需要 Android 14 以上（這台是 Android ${android.os.Build.VERSION.RELEASE}）。",
                        "Needs Android 14 or newer (this phone runs Android ${android.os.Build.VERSION.RELEASE})."),
                    m.bitPerfect && canExclusive
                ) { if (canExclusive) m.changeBitPerfect(it) }
                if (status.text.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("目前：", "Now: ") + status.text, fontSize = 12.sp,
                        color = if (status.exclusive) p.accent else p.muted)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    tr("""各輸出的效果與系統版本限制：
• USB DAC：Android 14 以上可依每首歌切換 DAC 的採樣率與位深，開獨佔時為 bit-perfect；Android 13 以下經系統混音（多半 48 kHz）。
• 藍牙：所有版本都會把原始採樣率與 24-bit 送進系統，最後的音質由藍牙編碼決定（LDAC 最高 96 kHz / 24-bit，可在「開發人員選項」設定 LDAC 取樣率與 990 kbps）。Android 14 以上若手機回報支援，混音器會跟著歌曲的採樣率。
• 喇叭／有線耳機：多數手機固定以 48 kHz 混音；Android 14 以上且手機音訊驅動回報支援時，才會切換成歌曲的採樣率（少數機型）。
• 高品質重新取樣：所有版本可用，只在系統混音的取樣率跟歌曲不同時才會作用；獨佔或輸出已跟隨歌曲時不會轉換。
• 輸出跟隨歌曲或獨佔時，不同格式的歌之間切換會重新開啟輸出，有短暫間隙。""",
                    """What each output gets, and what it needs:
• USB DAC: on Android 14+ the DAC follows each song's sample rate and bit depth, bit-perfect when exclusive is on; Android 13 and older go through the system mixer (usually 48 kHz).
• Bluetooth: on every version the song's own rate and 24-bit reach the system; the codec decides the final quality (LDAC up to 96 kHz / 24-bit — set its sample rate and 990 kbps in Developer options). On Android 14+ the mixer follows the song's rate if the phone offers it.
• Speaker / wired: most phones mix at a fixed 48 kHz; only on Android 14+ with a driver that offers it does the output follow the song (a few models).
• High-quality resampling: all versions; only used when the system mixer runs at another rate than the song, never in exclusive / follow-the-song output.
• With exclusive or follow-the-song output, switching between songs of different formats reopens the output: expect a short gap."""),
                    fontSize = 12.sp, color = p.faint, lineHeight = 17.sp
                )
            }

            Section(tr("功能", "Features")) {
                Row(Modifier.fillMaxWidth().clickable { m.open(Page.Equalizer) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.GraphicEq, null, tint = p.muted)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("等化器", "Equalizer"), fontSize = 15.sp, color = p.strong, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, null, tint = p.faint)
                }
                Row(Modifier.fillMaxWidth().clickable { m.open(Page.Custom) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Share, null, tint = p.muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("自訂分享清單", "Custom share lists"), fontSize = 15.sp, color = p.strong)
                        Text(tr("在歌曲列表長按選歌，建立 30 天有效的分享連結",
                            "Long-press songs in a list to pick them; links last 30 days"),
                            fontSize = 12.sp, color = p.muted)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = p.faint)
                }
            }

            Section(tr("更新", "Updates")) {
                if (m.isTestBuild) {
                    Text(tr("測試版不檢查更新。", "Test builds do not check for updates."), fontSize = 13.sp, color = p.muted)
                } else {
                    SwitchRow(tr("啟動時檢查更新", "Check for updates at start"), null, m.autoUpdate, m::changeAutoUpdate)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { m.checkUpdate(true) }) { Text(tr("立即檢查", "Check now")) }
                    if (m.updateMessage.isNotBlank()) {
                        Text(m.updateMessage, fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            Section(tr("關於", "About")) {
                val context = LocalContext.current
                Text("Resona " + Updater.installedVersion(context), fontSize = 15.sp, color = p.strong)
                Text(tr("Simple Player 的串流端", "Streaming client for Simple Player"), fontSize = 12.sp, color = p.muted)
                Text(tr("核心", "Core") + " " + Core.version() + " (Rust)", fontSize = 12.sp, color = p.muted)
                Text("Apache License 2.0", fontSize = 12.sp, color = p.muted)
            }
        }
    }
}

/* ---------------- custom share lists ---------------- */

/**
 * Custom share playlists: the server keeps the picked songs for 30 days and returns an id
 * shared as <web>/c/<id>. Lists made on this phone are remembered (with the delete token)
 * until they expire. Same storage as app 1.x.
 */
data class CustomPlaylist(val id: String, val name: String, val count: Int, val expiresAt: Long, val deleteToken: String) {
    val daysLeft get() = ((expiresAt - System.currentTimeMillis() + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(1)
}

object CustomStore {
    const val MAX_TRACKS = 500
    private fun prefs(c: Context) = c.getSharedPreferences("custom_playlists", Context.MODE_PRIVATE)

    fun load(c: Context): List<CustomPlaylist> {
        val raw = prefs(c).getString("lists", null) ?: return emptyList()
        val now = System.currentTimeMillis()
        val all = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                CustomPlaylist(o.optString("id"), o.optString("name"), o.optInt("count"),
                    o.optLong("expiresAt"), o.optString("deleteToken")).takeIf { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val live = all.filter { it.expiresAt > now }
        if (live.size != all.size) save(c, live)
        return live
    }

    private fun save(c: Context, lists: List<CustomPlaylist>) {
        val a = JSONArray()
        lists.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("count", it.count)
                .put("expiresAt", it.expiresAt).put("deleteToken", it.deleteToken))
        }
        prefs(c).edit().putString("lists", a.toString()).apply()
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    suspend fun create(c: Context, base: String, name: String, ids: List<String>): CustomPlaylist =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("name", name).put("tracks", JSONArray(ids)).toString().toByteArray()
            val conn = (URL("$base/api/custom-playlists").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 8000; readTimeout = 15000; doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val o = runCatching { JSONObject(text) }.getOrNull()
                if (code !in 200..299 || o == null) error(o?.optString("error")?.takeIf { it.isNotBlank() } ?: "HTTP $code")
                val entry = CustomPlaylist(o.getString("id"), o.optString("name", name),
                    o.optJSONArray("tracks")?.length() ?: ids.size, o.optLong("expiresAt"), o.optString("deleteToken"))
                save(c, listOf(entry) + load(c).filter { it.id != entry.id })
                entry
            } finally { conn.disconnect() }
        }

    suspend fun delete(c: Context, base: String, list: CustomPlaylist) = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("$base/api/custom-playlists/${enc(list.id)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"; connectTimeout = 8000; readTimeout = 10000
                setRequestProperty("X-Delete-Token", list.deleteToken)
            }
            try { conn.responseCode } finally { conn.disconnect() }
        }
        save(c, load(c).filter { it.id != list.id })
    }
}

/** Bottom bar while songs are being picked for a share list. */
@Composable
fun SelectionBar(m: AppModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(14.dp)).background(p.raised)
            .border(1.dp, p.accentLine, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("已選 ${m.picked.size} 首", "${m.picked.size} picked"), color = p.strong,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = { m.selecting = false; m.picked.clear() }) { Text(tr("取消", "Cancel")) }
        Button(onClick = { naming = true }, enabled = !busy && m.picked.size in 1..CustomStore.MAX_TRACKS) {
            Text(tr("建立分享清單", "Create share list"))
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(tr("分享清單名稱", "Share list name")) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    naming = false
                    busy = true
                    val ids = m.picked.map { Lib.id(it) }
                    scope.launch {
                        runCatching { CustomStore.create(context, m.server, name.trim(), ids) }
                            .onSuccess {
                                m.selecting = false
                                m.picked.clear()
                                val link = Core.webUrl(3, it.id)
                                shareText(context, link, it.name)
                            }
                            .onFailure { toast(context, tr("建立失敗：", "Failed: ") + it.message) }
                        busy = false
                    }
                }) { Text(tr("建立並分享", "Create & share")) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(tr("取消", "Cancel")) } }
        )
    }
}

@Composable
fun CustomListsScreen(m: AppModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf(CustomStore.load(context)) }
    Column(Modifier.fillMaxSize()) {
        TopBar(tr("自訂分享清單", "Custom share lists")) { m.back() }
        if (lists.isEmpty()) {
            Text(
                tr("還沒有分享清單。打開任一歌曲列表，長按歌曲開始選歌。",
                    "No share lists yet. Open a song list and long-press a song to start picking."),
                color = p.muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
            items(lists, key = { it.id }) { l ->
                val link = Core.webUrl(3, l.id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.strong,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("${l.count} 首 · 剩 ${l.daysLeft} 天", "${l.count} songs · ${l.daysLeft} days left"),
                            fontSize = 12.sp, color = p.muted)
                    }
                    IconButton(onClick = { copyText(context, link) }) { Icon(Icons.Filled.ContentCopy, tr("複製", "Copy"), tint = p.muted) }
                    IconButton(onClick = { shareText(context, link, l.name) }) { Icon(Icons.Filled.Share, tr("分享", "Share"), tint = p.muted) }
                    IconButton(onClick = {
                        scope.launch {
                            CustomStore.delete(context, m.server, l)
                            lists = CustomStore.load(context)
                        }
                    }) { Icon(Icons.Filled.DeleteOutline, tr("刪除", "Delete"), tint = p.muted) }
                }
            }
        }
    }
}

/** Shown when a newer release is out. */
@Composable
fun UpdateDialog(m: AppModel) {
    val r = m.update ?: return
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = { if (m.updateProgress == null) m.update = null },
        title = { Text(tr("有新版本 ${r.version}", "Version ${r.version} is out")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                m.updateProgress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                }
                Text(r.notes.ifBlank { tr("（沒有說明）", "(no notes)") }, fontSize = 13.sp, color = p.text)
            }
        },
        confirmButton = {
            TextButton(enabled = m.updateProgress == null, onClick = { m.downloadUpdate() }) {
                Text(tr("下載並安裝", "Download & install"))
            }
        },
        dismissButton = {
            TextButton(enabled = m.updateProgress == null, onClick = { m.update = null }) { Text(tr("稍後", "Later")) }
        }
    )
}
