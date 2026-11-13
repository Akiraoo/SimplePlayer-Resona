package com.akira.simpleplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.Lib
import com.akira.simpleplayer.playback.Playback

/* ---------------- home: playlists ---------------- */

@Composable
fun HomeScreen(m: AppModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Resona", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.strong,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { m.open(Page.Equalizer) }) {
                Icon(Icons.Filled.GraphicEq, tr("等化器", "Equalizer"), tint = p.muted)
            }
            IconButton(onClick = { m.open(Page.Settings) }) {
                Icon(Icons.Filled.Settings, tr("設定", "Settings"), tint = p.muted)
            }
        }
        SyncBanner(m)
        if (m.server.isBlank()) {
            EmptyHint(
                tr("還沒設定伺服器", "No server yet"),
                tr("到設定填入 Simple Player Web Server 的位址（例如 192.168.0.10:55555），再同步曲庫。",
                    "Enter your Simple Player Web Server address in Settings (e.g. 192.168.0.10:55555), then sync."),
                tr("前往設定", "Open settings")
            ) { m.open(Page.Settings) }
            return@Column
        }
        if (m.songCount == 0 && !m.sync.running) {
            EmptyHint(
                tr("曲庫是空的", "The library is empty"),
                tr("按下同步，從伺服器取得歌曲清單。", "Sync to fetch the song list from the server."),
                tr("同步", "Sync")
            ) { m.startSync(false) }
            return@Column
        }
        key(m.libGen) {
            LazyVerticalGrid(
                state = m.homeGrid,
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AllSongsCard(m)
                }
                items(m.playlists, key = { it.name }) { pl ->
                    PlaylistCard(pl) { m.open(Page.Songs("pl:" + pl.name, pl.name)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, body: String, action: String, onAction: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Icon(Icons.Filled.LibraryMusic, null, tint = p.faint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.strong)
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 13.sp, color = p.muted)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SyncBanner(m: AppModel) {
    val p = LocalPalette.current
    val s = m.sync
    if (s.running) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.stage + if (s.total > 0) "  ${s.done} / ${s.total}" else "",
                    fontSize = 12.sp, color = p.muted, modifier = Modifier.weight(1f)
                )
                Text(
                    tr("取消", "Cancel"), fontSize = 12.sp, color = p.accent,
                    modifier = Modifier.clickable { m.cancelSync() }.padding(4.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            if (s.total > 0) {
                LinearProgressIndicator(
                    progress = { s.done / s.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(), trackColor = p.track
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = p.track)
            }
        }
    } else if (s.error.isNotBlank()) {
        Text(
            s.error, fontSize = 12.sp, color = p.accent,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AllSongsCard(m: AppModel) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.raised)
            .border(1.dp, p.line, RoundedCornerShape(12.dp))
            .clickable { m.open(Page.Songs("all", tr("全部歌曲", "All songs"))) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.LibraryMusic, null, tint = p.accent, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("全部歌曲", "All songs"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.strong)
            Text(
                tr("${m.songCount} 首 · ${m.playlists.size} 個歌單", "${m.songCount} songs · ${m.playlists.size} playlists"),
                fontSize = 12.sp, color = p.muted
            )
        }
        Icon(Icons.Filled.ChevronRight, null, tint = p.faint)
    }
}

@Composable
private fun PlaylistCard(pl: Lib.Playlist, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(2.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Cover(pl.coverTrack, maxWidth, corner = 10.dp)
        }
        Spacer(Modifier.height(7.dp))
        Text(pl.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.strong,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(tr("${pl.count} 首", "${pl.count} songs"), fontSize = 12.sp, color = p.muted)
    }
}

/* ---------------- songs of a list ---------------- */

private val SORTS get() = listOf(
    tr("預設順序", "Default order"), tr("標題", "Title"), tr("歌手", "Artist"),
    tr("專輯", "Album"), tr("長度", "Duration"), tr("格式", "Format"), tr("加入時間", "Date added")
)

@Composable
fun SongsScreen(
    m: AppModel,
    title: String,
    offsetX: Animatable<Float, AnimationVector1D>,
    onSwipedBack: () -> Unit
) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val state by Playback.state.collectAsState()
    val key = m.source
    // each list comes back where it was left (as in 1.x)
    val listState = rememberLazyListState(m.scrollPos(key).first, m.scrollPos(key).second)
    var sortMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableIntStateOf(-1) }

    // a new search or order starts from the top
    var firstFilter by remember { mutableStateOf(true) }
    LaunchedEffect(m.query, m.sortCol, m.sortAsc) {
        if (firstFilter) firstFilter = false else listState.scrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (idx, off) ->
                delay(400L)
                m.saveScrollPos(key, idx, off)
            }
    }
    DisposableEffect(listState) {
        onDispose { m.saveScrollPos(key, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val dragScope = rememberCoroutineScope()
    val currentOnSwipedBack by rememberUpdatedState(onSwipedBack)

    Column(
        Modifier
            .fillMaxSize()
            // swipe right anywhere: the page follows the finger over the playlist grid;
            // past 110 dp (or a quick flick) it slides away, otherwise it springs back
            .pointerInput(screenWidthPx) {
                val tracker = VelocityTracker()
                val distanceThreshold = 110.dp.toPx()
                val velocityThreshold = 800.dp.toPx()
                val springBack = spring<Float>(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragCancel = { dragScope.launch { offsetX.animateTo(0f, springBack) } },
                    onDragEnd = {
                        val vx = tracker.calculateVelocity().x
                        if (offsetX.value > distanceThreshold || vx > velocityThreshold) {
                            dragScope.launch {
                                offsetX.animateTo(screenWidthPx, tween(220, easing = SheetEasing))
                                // the grid is already in place underneath
                                currentOnSwipedBack()
                            }
                        } else {
                            dragScope.launch { offsetX.animateTo(0f, springBack) }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            offsetX.snapTo((offsetX.value + dragAmount).coerceAtLeast(0f))
                        }
                    }
                )
            }
            .graphicsLayer { translationX = offsetX.value }
            .background(p.bg)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { m.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back"), tint = p.text)
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.strong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("${m.rows.size} 首", "${m.rows.size} songs"), fontSize = 12.sp, color = p.muted)
            }
            if (m.source.startsWith("pl:")) {
                IconButton(onClick = {
                    shareText(context, Core.webUrl(2, m.source.removePrefix("pl:")), title)
                }) { Icon(Icons.Filled.Share, tr("分享歌單", "Share playlist"), tint = p.muted) }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, tr("排序", "Sort"),
                        tint = if (m.sortCol != 0) p.accent else p.muted)
                }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORTS.forEachIndexed { i, label ->
                        DropdownMenuItem(
                            text = {
                                Text(label + if (i == m.sortCol && i != 0) (if (m.sortAsc) "  ↑" else "  ↓") else "",
                                    color = if (i == m.sortCol) p.accent else p.text)
                            },
                            onClick = {
                                // the same column again flips the direction
                                if (i == m.sortCol && i != 0) m.setSort(i, !m.sortAsc) else m.setSort(i, true)
                                sortMenu = false
                            }
                        )
                    }
                }
            }
        }

        SearchField(m.query, onChange = m::search)

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { m.playAll(false) }, enabled = m.rows.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("播放", "Play"))
            }
            OutlinedButton(onClick = { m.playAll(true) }, enabled = m.rows.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                Icon(Icons.Filled.Shuffle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("隨機播放", "Shuffle"))
            }
        }

        key(m.libGen) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = if (m.selecting) 160.dp else 140.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(m.rows.size, key = { (m.rows[it].toLong() shl 32) or it.toLong() }) { row ->
                    val t = m.rows[row]
                    SongRow(
                        track = t,
                        playing = t == state.track,
                        selecting = m.selecting,
                        picked = t in m.picked,
                        menuOpen = menuFor == row,
                        onClick = {
                            if (m.selecting) m.togglePick(t) else m.playRow(row)
                        },
                        onLongClick = {
                            m.selecting = true
                            if (t !in m.picked) m.picked.add(t)
                        },
                        onMenu = { menuFor = row },
                        onMenuClose = { menuFor = -1 },
                        context = context
                    )
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.raised)
            .border(1.dp, if (focused) p.accentLine else p.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = if (focused) p.accent else p.faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(tr("搜尋標題、歌手、專輯、資料夾", "Search title, artist, album, folder"),
                    color = p.faint, fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 14.sp),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            )
        }
        if (value.isNotEmpty()) {
            Icon(Icons.Filled.Close, tr("清除", "Clear"), tint = p.muted,
                modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    track: Int,
    playing: Boolean,
    selecting: Boolean,
    picked: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
    onMenuClose: () -> Unit,
    context: Context
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    picked -> p.accentSoft
                    // the song playing gets a faint tint of the theme colour
                    playing -> p.accent.copy(alpha = if (p.light) 0.10f else 0.08f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 14.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            Icon(
                if (picked) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                tint = if (picked) p.accent else p.faint, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Cover(track, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                Lib.title(track), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) p.accent else p.strong,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatBadge(Lib.format(track))
                Spacer(Modifier.width(6.dp))
                Text(
                    listOf(Lib.artist(track), Lib.album(track)).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        Text(fmtTime(Lib.durationMs(track)), fontSize = 12.sp, color = p.faint,
            modifier = Modifier.padding(start = 8.dp))
        if (!selecting) {
            Box {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Filled.MoreVert, tr("更多", "More"), tint = p.faint)
                }
                DropdownMenu(menuOpen, onDismissRequest = onMenuClose) {
                    DropdownMenuItem(
                        text = { Text(tr("加入佇列", "Add to queue")) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                        onClick = {
                            Playback.queueAdd(track)
                            toast(context, tr("已加入佇列", "Added to queue"))
                            onMenuClose()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("分享連結", "Share link")) },
                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = {
                            shareText(context, Core.webUrl(0, Lib.id(track)), Lib.title(track))
                            onMenuClose()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("複製下載連結", "Copy download link")) },
                        leadingIcon = { Icon(Icons.Filled.Link, null) },
                        onClick = {
                            copyText(context, Core.webUrl(1, Lib.id(track)))
                            onMenuClose()
                        }
                    )
                }
            }
        }
    }
}

/* ---------------- helpers ---------------- */

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

fun shareText(context: Context, text: String, title: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, title)
    context.startActivity(Intent.createChooser(send, title))
}

fun copyText(context: Context, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Resona", text))
    toast(context, tr("已複製", "Copied"))
}
