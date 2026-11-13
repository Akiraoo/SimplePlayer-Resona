package com.akira.simpleplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

/* ------------------------------------------------------------------------
 * Motion of app 1.x, kept as it was (same timings, curves and thresholds).
 *
 * Now-playing sheet (from the web player's setupMiniSwipe / setupNowSwipe):
 *   - progress        : sheet position (0 = below the screen, 1 = full screen)
 *   - contentProgress : staggered entrance of the content (0 = hidden, 1 = shown)
 *
 *   Swiping the mini player up only moves the sheet: content stays hidden, so only the
 *   slightly shrunk cover shows. Letting go (or tapping) slides the sheet the rest of the
 *   way while the content fades and rises in, one element after another. Swiping the
 *   full screen down anywhere outside the lyrics follows the finger 1:1.
 * ------------------------------------------------------------------------ */

const val SHEET_MS = 380            // sheet slides in / out
const val CONTENT_MS = 500f         // whole staggered entrance
const val STAGE_MS = 150f           // one element of it
const val PAGE_MS = 280             // page push / pop

val SheetEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
val StageEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f) // CSS "ease"

@Stable
class NowSheet(private val scope: CoroutineScope, private val setOpen: (Boolean) -> Unit, private val isOpen: () -> Boolean) {
    val progress = Animatable(0f)
    val contentProgress = Animatable(0f)

    /** Follows the finger; only the sheet moves. */
    fun drag(p: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(p.coerceIn(0f, 1f)) }
    }

    fun settle(open: Boolean) {
        if (isOpen() != open) setOpen(open) // the host's LaunchedEffect animates
        else scope.launch { animateTo(open) }
    }

    suspend fun animateTo(open: Boolean) {
        if (open) {
            if (contentProgress.value > 0.5f) {
                // content already visible (dragged down half way and put back): finish the slide
                contentProgress.snapTo(1f)
                progress.animateTo(1f, tween(SHEET_MS, easing = SheetEasing))
            } else {
                contentProgress.snapTo(0f)
                coroutineScope {
                    launch { progress.animateTo(1f, tween(SHEET_MS, easing = SheetEasing)) }
                    launch { contentProgress.animateTo(1f, tween(CONTENT_MS.toInt(), easing = LinearEasing)) }
                }
            }
        } else {
            // content stays as it is while the sheet slides away, then resets
            progress.animateTo(0f, tween(SHEET_MS, easing = SheetEasing))
            contentProgress.snapTo(0f)
        }
    }
}

/**
 * Swipe the full-screen player down to close. Lyrics list, slider and buttons take their
 * own gestures first, so scrolling the lyrics is unaffected. Put it outside the
 * graphicsLayer that moves the sheet, so finger positions are not shifted.
 */
fun Modifier.sheetSwipeDown(sheet: NowSheet, screenHeightPx: Float): Modifier =
    pointerInput(sheet, screenHeightPx) {
        val tracker = VelocityTracker()
        val threshold = max(110.dp.toPx(), screenHeightPx * .22f)
        var total = 0f
        detectVerticalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                // carry on from where an interrupted animation was
                total = (1f - sheet.progress.value) * screenHeightPx
            },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().y // down is positive
                sheet.settle(open = !(total >= threshold || velocity >= 750.dp.toPx()))
            },
            onDragCancel = { sheet.settle(open = true) },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                total = (total + dragAmount).coerceAtLeast(0f)
                sheet.drag(1f - total / screenHeightPx)
            }
        )
    }

/**
 * Staggered entrance: fade + rise 12 dp, driven by contentProgress.
 * Delays: cover 0 / title 60 / visualizer 140 / progress 210 / controls 280 / lyrics 350.
 */
fun Modifier.staged(sheet: NowSheet, delayMs: Float): Modifier = graphicsLayer {
    val t = sheet.contentProgress.value * CONTENT_MS
    val local = StageEasing.transform(((t - delayMs) / STAGE_MS).coerceIn(0f, 1f))
    alpha = local
    translationY = (1f - local) * 12.dp.toPx()
}

/** Cover entrance: scale 0.94 → 1, opacity 0.7 → 1. */
fun Modifier.coverEntrance(sheet: NowSheet): Modifier = graphicsLayer {
    val t = sheet.contentProgress.value * CONTENT_MS
    val local = SheetEasing.transform((t / 380f).coerceIn(0f, 1f))
    val s = .94f + .06f * local
    scaleX = s
    scaleY = s
    alpha = .7f + .3f * local
}
