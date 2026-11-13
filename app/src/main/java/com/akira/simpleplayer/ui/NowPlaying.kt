package com.akira.simpleplayer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akira.simpleplayer.AppExecutors
import com.akira.simpleplayer.core.Core
import com.akira.simpleplayer.core.Lib
import com.akira.simpleplayer.playback.Playback
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.exp
import kotlin.math.max

/** Position of the song playing, updated a few times a second while it plays. */
@Composable
private fun rememberPosition(state: Playback.State): Long {
    var pos by remember { mutableLongStateOf(Playback.positionMs()) }
    LaunchedEffect(state.track, state.playing, state.buffering) {
        pos = Playback.positionMs()
        while (isActive && state.playing) {
            delay(200)
            pos = Playback.positionMs()
        }
    }
    return pos
}

@Composable
private fun screenHeightPx(): Float =
    with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

/* ---------------- mini player ---------------- */

@Composable
fun MiniPlayer(m: AppModel, sheet: NowSheet) {
    val p = LocalPalette.current
    val state by Playback.state.collectAsState()
    if (state.track < 0 || !Lib.valid(state.track)) return
    val pos = rememberPosition(state)
    val heightPx = screenHeightPx()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            // Mica-like: the list behind is blurred and tinted with the surface colour
            // (Android 12+; older phones get the translucent tint only)
            .hazeEffect(
                state = m.haze,
                style = HazeStyle(
                    backgroundColor = p.bg,
                    tint = HazeTint(p.raised.copy(alpha = if (p.light) 0.62f else 0.58f)),
                    blurRadius = 28.dp,
                    noiseFactor = 0.06f,
                    fallbackTint = HazeTint(p.raised.copy(alpha = 0.92f))
                )
            )
            .border(1.dp, p.text.copy(alpha = if (p.light) 0.10f else 0.12f), RoundedCornerShape(16.dp))
            // 1.x gesture handling: drag up = the sheet follows, no drag = tap to open.
            // (clickable + detectVerticalDragGestures stacked would let clickable eat the
            // down event so the swipe is never seen.)
            .pointerInput(sheet, heightPx) {
                val touchSlop = viewConfiguration.touchSlop
                val travel = heightPx * 0.47f          // slow drag to fully open
                val flingVelocity = 1800.dp.toPx()     // a flick opens at once
                val openDistance = travel * 0.5f
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var total = 0f
                    var dragging = false
                    var fling = false
                    var tapTaken = false
                    val tracker = VelocityTracker()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // a button inside the mini player already handled this tap
                            tapTaken = change.isConsumed
                            break
                        }
                        total += change.position.y - change.previousPosition.y
                        if (!dragging && abs(total) > touchSlop) {
                            dragging = true
                            tracker.resetTracking()
                        }
                        if (dragging) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (tracker.calculateVelocity().y <= -flingVelocity) {
                                fling = true
                                change.consume()
                                break
                            }
                            if (total < 0f) sheet.drag(-total / travel)
                            change.consume()
                        }
                    }
                    when {
                        fling -> sheet.settle(open = true)
                        dragging -> {
                            val v = tracker.calculateVelocity().y
                            sheet.settle(open = -total >= openDistance || v <= -900.dp.toPx())
                        }
                        // a tap that did not land on a button
                        !tapTaken -> m.nowPlayingOpen = true
                    }
                }
            }
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(state.track, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(Lib.title(state.track), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = p.strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Lib.artist(state.track), fontSize = 12.sp, color = p.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { m.prev() }) {
                Icon(Icons.Filled.SkipPrevious, tr("上一首", "Previous"), tint = p.text)
            }
            IconButton(onClick = { Playback.toggle() }) {
                if (state.buffering && state.playing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        tr("播放／暫停", "Play / pause"), tint = p.strong, modifier = Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { m.next() }) {
                Icon(Icons.Filled.SkipNext, tr("下一首", "Next"), tint = p.text)
            }
        }
        val frac = if (state.durationMs > 0) (pos.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(p.track)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(p.accent))
        }
    }
}

/* ---------------- full player ---------------- */

/** Kept in the composition while the sheet is open or moving; removed once fully closed. */
@Composable
fun NowPlayingHost(m: AppModel, sheet: NowSheet) {
    val state by Playback.state.collectAsState()
    val visible by remember(sheet) {
        derivedStateOf { m.nowPlayingOpen || sheet.progress.value > 0.001f }
    }
    if (visible && state.track >= 0 && Lib.valid(state.track)) NowPlayingScreen(m, sheet, state)
}

@Composable
private fun NowPlayingScreen(m: AppModel, sheet: NowSheet, state: Playback.State) {
    val p = LocalPalette.current
    val track = state.track
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            // swipe down anywhere except the lyrics (which scroll) to close
            .sheetSwipeDown(sheet, screenHeightPx())
            .graphicsLayer {
                val pr = sheet.progress.value
                translationY = (1f - pr) * size.height
                alpha = pr
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(p.bg)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { m.nowPlayingOpen = false }) {
                    Icon(Icons.Filled.KeyboardArrowDown, tr("收起", "Close"), tint = p.text)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(p.line))
                }
                IconButton(onClick = { m.nowPlayingOpen = false; m.open(Page.Equalizer) }) {
                    Icon(Icons.Filled.GraphicEq, tr("等化器", "Equalizer"), tint = p.muted)
                }
            }

            // cover or lyrics (tap to switch)
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).coverEntrance(sheet),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics) {
                    Lyrics(track, state, onTapEmpty = { showLyrics = false })
                } else {
                    SwipeCover(m, track, onTap = { showLyrics = true })
                }
            }

            Spacer(Modifier.height(14.dp))
            Column(Modifier.padding(horizontal = 24.dp).staged(sheet, 60f)) {
                Text(Lib.title(track), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = p.strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(Lib.artist(track), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.accent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Lib.album(track), fontSize = 13.sp, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(formatLine(track, state.sampleRate, state.channels, state.bitrate),
                    fontSize = 11.sp, color = p.faint)
                val output by com.akira.simpleplayer.playback.OutputRouter.status.collectAsState()
                if (output.text.isNotBlank()) {
                    Text(output.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (output.exclusive) p.accent else p.faint)
                }
            }

            AudioVisualizer(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 24.dp).staged(sheet, 140f))

            SeekBar(state, Modifier.padding(horizontal = 18.dp).staged(sheet, 210f))

            state.error?.let {
                Text(tr("播放錯誤：", "Playback error: ") + it, color = p.accent, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).staged(sheet, 280f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { m.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, tr("隨機", "Shuffle"), tint = if (m.shuffle) p.accent else p.faint)
                }
                IconButton(onClick = { m.prev() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, tr("上一首", "Previous"), tint = p.strong, modifier = Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(70.dp).clip(CircleShape).background(p.accent).clickable { Playback.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.buffering && state.playing) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = p.accentText, strokeWidth = 3.dp)
                    } else {
                        Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            tr("播放／暫停", "Play / pause"), tint = p.accentText, modifier = Modifier.size(40.dp))
                    }
                }
                IconButton(onClick = { m.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, tr("下一首", "Next"), tint = p.strong, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { m.cycleRepeat() }) {
                    Icon(if (m.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        tr("循環", "Repeat"), tint = if (m.repeat > 0) p.accent else p.faint)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).staged(sheet, 350f),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Filled.Lyrics, null, tint = if (showLyrics) p.accent else p.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("歌詞", "Lyrics"), color = if (showLyrics) p.accent else p.muted)
                }
                TextButton(onClick = { showQueue = true }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = p.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("佇列", "Queue") + if (m.upnext.isNotEmpty()) " ${m.upnext.size}" else "", color = p.muted)
                }
            }
        }
    }

    if (showQueue) QueueSheet(m, onClose = { showQueue = false })
}

/**
 * The big cover. Song changes slide it out and the new one in (left for next, right for
 * previous). It can also be dragged sideways: past 30 % of its width or with a flick it
 * changes the song, otherwise it springs back.
 */
@Composable
private fun SwipeCover(m: AppModel, track: Int, onTap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var shown by remember { mutableIntStateOf(track) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    // a swipe already slid the old cover out: only the new one has to come in
    var swipedOut by remember { mutableStateOf(false) }
    val outSpec = tween<Float>(170, easing = FastOutLinearInEasing)
    val inSpec = tween<Float>(300, easing = SheetEasing)

    LaunchedEffect(track) {
        if (track == shown) return@LaunchedEffect
        val d = m.takeCoverDir()
        if (!swipedOut) offset.animateTo(-d * widthPx, outSpec)
        swipedOut = false
        shown = track
        offset.snapTo(d * widthPx)
        offset.animateTo(0f, inSpec)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                val springBack = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragCancel = { scope.launch { offset.animateTo(0f, springBack) } },
                    onDragEnd = {
                        val vx = tracker.calculateVelocity().x
                        val fling = 800.dp.toPx()
                        val go = when {
                            offset.value < -widthPx * 0.3f || vx < -fling -> 1
                            offset.value > widthPx * 0.3f || vx > fling -> -1
                            else -> 0
                        }
                        scope.launch {
                            if (go == 0) {
                                offset.animateTo(0f, springBack)
                                return@launch
                            }
                            offset.animateTo(-go * widthPx, outSpec)
                            swipedOut = true
                            val before = shown
                            if (go == 1) m.next() else m.prevTrack()
                            // nothing to change to (end / start of the list): bring it back
                            delay(700)
                            if (shown == before && swipedOut) {
                                swipedOut = false
                                offset.animateTo(0f, inSpec)
                            }
                        }
                    },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(offset.value + dx) }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth, maxHeight)
        Cover(
            shown, side, corner = 14.dp, full = true,
            modifier = Modifier
                .graphicsLayer {
                    translationX = offset.value
                    val f = (abs(offset.value) / widthPx).coerceIn(0f, 1f)
                    alpha = 1f - 0.6f * f
                    val s = 1f - 0.06f * f
                    scaleX = s
                    scaleY = s
                }
                .clickable(onClick = onTap)
        )
    }
}

@Composable
private fun SeekBar(state: Playback.State, modifier: Modifier) {
    val p = LocalPalette.current
    val pos = rememberPosition(state)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val dur = state.durationMs.coerceAtLeast(1)
    Column(modifier) {
        Slider(
            value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { Playback.seekTo((it * dur).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(
                thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.track
            )
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Text(fmtTime(dragging?.let { (it * dur).toLong() } ?: pos), fontSize = 11.sp, color = p.muted)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(state.durationMs), fontSize = 11.sp, color = p.muted)
        }
    }
}

/* ---------------- spectrum ---------------- */

private const val SPECTRUM_ATTACK_TAU = 0.045f
private const val SPECTRUM_RELEASE_TAU = 0.18f

/** Bars from the core's analyser in the theme colour; fast attack, slow release, rests when silent. */
@Composable
fun AudioVisualizer(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val levels = remember { FloatArray(Core.SPECTRUM_BARS) }
    val target = remember { FloatArray(Core.SPECTRUM_BARS) }
    var frame by remember { mutableLongStateOf(0L) }
    val color = p.accent

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            var settled = false
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
                lastNanos = now
                Core.spectrumRead(target)
                val attack = 1f - exp(-dt / SPECTRUM_ATTACK_TAU)
                val release = 1f - exp(-dt / SPECTRUM_RELEASE_TAU)
                var sum = 0f
                for (i in levels.indices) {
                    val cur = levels[i]
                    val goal = target[i]
                    var next = cur + (goal - cur) * (if (goal > cur) attack else release)
                    if (goal == 0f && next < 0.002f) next = 0f
                    levels[i] = next
                    sum += next
                }
                settled = sum == 0f
                frame = now
            }
            if (settled) {
                lastNanos = 0L
                delay(80L)
            }
        }
    }

    Spacer(
        modifier.drawWithCache {
            val n = Core.SPECTRUM_BARS
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val radius = CornerRadius(w / 2, w / 2)
            onDrawBehind {
                if (frame == Long.MIN_VALUE) return@onDrawBehind
                for (i in 0 until n) {
                    val v = levels[i].coerceIn(0f, 1f)
                    val h = (v * size.height).coerceAtLeast(1.5f)
                    drawRoundRect(
                        color = color.copy(alpha = 0.35f + 0.5f * v),
                        topLeft = Offset(i * (w + gap), size.height - h),
                        size = Size(w, h),
                        cornerRadius = radius
                    )
                }
            }
        }
    )
}

/* ---------------- lyrics ---------------- */

private data class LyricLine(val time: Double?, val text: String)

@Composable
private fun Lyrics(track: Int, state: Playback.State, onTapEmpty: () -> Unit) {
    val p = LocalPalette.current
    var lines by remember(track) { mutableStateOf<List<LyricLine>?>(null) }
    LaunchedEffect(track) {
        lines = withContext(AppExecutors.io) {
            Core.lyrics(track).map {
                val tab = it.indexOf('\t')
                LyricLine(it.substring(0, tab).toDoubleOrNull(), it.substring(tab + 1))
            }
        }
    }
    val list = lines
    if (list == null) {
        CircularProgressIndicator(Modifier.size(28.dp))
        return
    }
    if (list.isEmpty()) {
        Text(tr("這首歌沒有歌詞", "No lyrics for this song"), color = p.muted,
            modifier = Modifier.clickable(onClick = onTapEmpty).padding(20.dp))
        return
    }
    val synced = list.any { it.time != null }
    val pos = rememberPosition(state)
    val active = if (!synced) -1 else list.indexOfLast { (it.time ?: Double.MAX_VALUE) * 1000 <= pos + 150 }
    val listState = rememberLazyListState()
    // follow every line; only the user's own dragging pauses it (for 3 s after letting go).
    // (isScrollInProgress would also be true for the automatic scrolling itself.)
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var userDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    userDragging = false
                    userScrollAt = System.currentTimeMillis()
                }
            }
        }
    }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(active) {
        if (active < 0 || userDragging || System.currentTimeMillis() - userScrollAt < 3000) return@LaunchedEffect
        // keep the active line in the middle of the view
        runCatching {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == active }
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            if (item != null && !first) {
                val delta = item.offset + item.size / 2f - center
                if (abs(delta) > 1f) listState.animateScrollBy(delta, tween(320, easing = SheetEasing))
            } else {
                listState.scrollToItem(active, -(center - 60).toInt().coerceAtLeast(0))
            }
        }
        first = false
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 60.dp)) {
        itemsIndexed(list) { i, line ->
            val current = i == active
            Text(
                line.text.ifBlank { "♪" },
                fontSize = if (current) 19.sp else 16.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !synced -> p.text
                    current -> p.strong
                    else -> p.faint
                },
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = line.time != null) { line.time?.let { Playback.seekTo((it * 1000).toLong()) } }
                    .padding(vertical = 7.dp)
            )
        }
    }
}

/* ---------------- the user's queue ---------------- */

@Composable
private fun QueueSheet(m: AppModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    ModalBottomSheet(onDismissRequest = onClose, containerColor = p.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("佇列", "Queue"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.strong)
                Text(tr("佇列裡的歌優先播放，播完自動移除；跳過的會留著。",
                    "Queued songs play first and leave once played; skipped ones stay."),
                    fontSize = 12.sp, color = p.muted)
            }
            if (m.upnext.isNotEmpty()) {
                TextButton(onClick = { Playback.queueClear() }) { Text(tr("清空", "Clear")) }
            }
        }
        if (m.upnext.isEmpty()) {
            Text(tr("佇列是空的。在歌曲的「⋮」選單選「加入佇列」。",
                "The queue is empty. Use “Add to queue” in a song's ⋮ menu."),
                color = p.muted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(m.upnext.toList()) { i, t ->
                val playing = i == m.upnextCurrent
                Row(
                    Modifier.fillMaxWidth().clickable { Playback.playUpnext(i) }
                        .padding(start = 18.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(t, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Lib.title(t), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (playing) p.accent else p.strong,
                            fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium)
                        Text(Lib.artist(t), fontSize = 12.sp, color = p.muted, maxLines = 1)
                    }
                    if (!playing) {
                        IconButton(onClick = { Playback.queueRemove(i) }) {
                            Icon(Icons.Filled.Close, tr("移除", "Remove"), tint = p.faint)
                        }
                    } else {
                        Icon(Icons.Filled.GraphicEq, null, tint = p.accent, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
