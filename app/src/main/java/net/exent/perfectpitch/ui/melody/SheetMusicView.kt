@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package net.exent.perfectpitch.ui.melody

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import alphaTab.AlphaTabApiBase
import alphaTab.AlphaTabView
import alphaTab.PlayerMode
import alphaTab.LayoutMode
import alphaTab.ScrollMode
import alphaTab.StaveProfile
import alphaTab.synth.IExternalMediaHandler
import alphaTab.synth.IExternalMediaSynthOutput
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import net.exent.perfectpitch.BuildConfig

private const val TAG = "SheetMusicView"

/**
 * Sheet music staff rendered by alphaTab. The playback cursor is driven by the
 * game's own clock via alphaTab's external media sync — alphaTab never
 * produces audio itself (that stays with the app's own MelodyPlayer).
 */
@Composable
fun SheetMusicView(
    alphaTex: String,
    currentTimeSeconds: Double,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val apiRef = remember { mutableStateOf<AlphaTabApiBase<AlphaTabView>?>(null) }
    var midiReady by remember { mutableStateOf(false) }
    var loadedTex by remember { mutableStateOf<String?>(null) }
    val currentTime by rememberUpdatedState(currentTimeSeconds)
    val playing by rememberUpdatedState(isPlaying)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val view = AlphaTabView(context, null)
            view.setBackgroundColor(android.graphics.Color.WHITE)
            view.barCursorFillColor = android.graphics.Color.TRANSPARENT
            view.beatCursorFillColor = android.graphics.Color.argb(255, 64, 64, 255)
            val api = view.api
            api.settings.apply {
                player.enablePlayer = true
                player.playerMode = PlayerMode.EnabledExternalMedia
                player.enableCursor = true
                player.enableAnimatedBeatCursor = true
                player.enableElementHighlighting = false
                player.enableUserInteraction = false
                player.scrollMode = ScrollMode.Continuous
                display.layoutMode = LayoutMode.Horizontal
                display.staveProfile = StaveProfile.Score
            }
            api.updateSettings()

            // The game is the master clock; alphaTab -> media callbacks are ignored
            (api.player?.output as? IExternalMediaSynthOutput)?.handler = object : IExternalMediaHandler {
                override var masterVolume: Double = 0.0
                override var playbackRate: Double = 1.0
                override val backingTrackDuration: Double get() = 0.0
                override fun seekTo(time: Double) {}
                override fun play() {}
                override fun pause() {}
            }

            api.midiLoaded.on { midiReady = true }
            api.error.on { e -> if (BuildConfig.DEBUG) Log.w(TAG, "alphaTab error", e) }

            // The initial score load typically happens before the view is laid out,
            // in which case alphaTab skips rendering (width=0) and never retries.
            // Trigger a manual render once both score and layout are available.
            var firstRenderDone = false
            api.postRenderFinished.on { firstRenderDone = true }
            api.scoreLoaded.on {
                if (!firstRenderDone && view.measuredWidth > 0) api.render()
            }
            view.doOnLayout {
                if (!firstRenderDone && api.score != null && view.measuredWidth > 0) {
                    api.render()
                }
            }

            api.tex(alphaTex)
            loadedTex = alphaTex
            apiRef.value = api
            view
        },
        update = {
            if (loadedTex != alphaTex) {
                midiReady = false
                apiRef.value?.tex(alphaTex)
                loadedTex = alphaTex
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            apiRef.value?.destroy()
            apiRef.value = null
        }
    }

    // alphaTab only glides its animated beat cursor while its player reports
    // state Playing. Keep it paused until the game clock reaches the first note
    // (time 0) so the marker holds on the first note through the lead-in, then
    // let it glide note-to-note in sync with the game. Also pause whenever the
    // game clock freezes (wait mode) so the marker doesn't drift ahead.
    LaunchedEffect(midiReady) {
        if (!midiReady) return@LaunchedEffect
        snapshotFlow { playing && currentTime >= 0.0 }
            .distinctUntilChanged()
            .collect { play ->
                val api = apiRef.value ?: return@collect
                if (play) api.play() else api.pause()
            }
    }

    // Push the game clock to alphaTab; when game time freezes (e.g. wait mode)
    // no updates are emitted and the cursor freezes with it
    LaunchedEffect(midiReady) {
        if (!midiReady) return@LaunchedEffect
        snapshotFlow { currentTime }
            .sample(50)
            .collectLatest { t ->
                (apiRef.value?.player?.output as? IExternalMediaSynthOutput)
                    ?.updatePosition(t.coerceAtLeast(0.0) * 1000.0)
            }
    }
}
