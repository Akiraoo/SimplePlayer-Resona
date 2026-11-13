package com.akira.simpleplayer

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.akira.simpleplayer.core.CoreBoot
import com.akira.simpleplayer.playback.PlaybackService
import com.akira.simpleplayer.ui.*
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {

    private val model: AppModel by viewModels()
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        CoreBoot.ensure(this)
        super.onCreate(savedInstanceState)
        setContent {
            val m = model
            SpTheme(m.light, m.accent) {
                val p = LocalPalette.current
                enableEdgeToEdge(
                    statusBarStyle = if (p.light) SystemBarStyle.light(0, 0) else SystemBarStyle.dark(0),
                    navigationBarStyle = if (p.light) SystemBarStyle.light(0, 0) else SystemBarStyle.dark(0)
                )
                androidx.compose.runtime.key(m.english) { App(m) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        // connecting a controller starts the playback service (it restores the last song)
        controller = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()
    }

    override fun onStop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onStop()
    }
}

/** How deep a page sits (push = slide in from the right, pop = slide back). */
private fun depth(p: Page) = when (p) {
    Page.Home -> 0
    is Page.Songs, Page.Settings, Page.Equalizer -> 1
    Page.Custom -> 2
}

@Composable
private fun App(m: AppModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val sheet = remember { NowSheet(scope, { m.nowPlayingOpen = it }, { m.nowPlayingOpen }) }
    LaunchedEffect(m.nowPlayingOpen) { sheet.animateTo(m.nowPlayingOpen) }

    // Horizontal offset of the song list while it is swiped back. The playlist grid is
    // drawn underneath during the swipe, so finishing it needs no second animation.
    val backSwipe = remember { Animatable(0f) }
    var instantBack by remember { mutableStateOf(false) }
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    LaunchedEffect(m.page) {
        if (m.page !is Page.Songs) {
            backSwipe.snapTo(0f)
            instantBack = false
        }
    }

    BackHandler(enabled = m.nowPlayingOpen || m.selecting || m.page != Page.Home) { m.back() }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        // everything under the mini player is the source of its frosted backdrop
        Box(Modifier.fillMaxSize().hazeSource(m.haze).background(p.bg).statusBarsPadding()) {
            if (m.page is Page.Songs && backSwipe.value > 0f) {
                val f = (backSwipe.value / screenWidthPx).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        // same parallax as the tap-back transition: starts 1/3 to the left
                        translationX = -(screenWidthPx / 3f) * (1f - f)
                    }
                ) {
                    HomeScreen(m)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f * (1f - f))))
                }
            }
            AnimatedContent(
                targetState = m.page,
                transitionSpec = {
                    if (instantBack) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        val spec = tween<IntOffset>(PAGE_MS, easing = SheetEasing)
                        val fade = tween<Float>(PAGE_MS)
                        if (depth(targetState) >= depth(initialState)) {
                            (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                                (slideOutHorizontally(spec) { -it / 3 } + fadeOut(fade))
                        } else {
                            (slideInHorizontally(spec) { -it / 3 } + fadeIn(fade)) togetherWith
                                (slideOutHorizontally(spec) { it } + fadeOut(fade))
                        }
                    }
                },
                label = "page"
            ) { page ->
                when (page) {
                    Page.Home -> HomeScreen(m)
                    is Page.Songs -> SongsScreen(m, page.title, backSwipe) {
                        instantBack = true
                        m.selecting = false
                        m.picked.clear()
                        m.page = Page.Home
                    }
                    Page.Settings -> SettingsScreen(m)
                    Page.Equalizer -> EqualizerScreen(onClose = { m.back() })
                    Page.Custom -> CustomListsScreen(m)
                }
            }
        }
        // lifted clear of the gesture bar so swiping home does not catch the mini player
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp)) {
            if (m.selecting) SelectionBar(m)
            else if (m.page != Page.Equalizer) MiniPlayer(m, sheet)
        }
        NowPlayingHost(m, sheet)
        UpdateDialog(m)
    }
}
