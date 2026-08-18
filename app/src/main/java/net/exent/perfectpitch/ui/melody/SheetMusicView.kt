@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package net.exent.perfectpitch.ui.melody

import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs

private const val TAG = "SheetMusicView"

/** Leading space kept between the sheet's left edge and the cursor position. */
private const val CURSOR_INSET_DP = 18f

/**
 * Sheet music staff rendered by alphaTab. The playback cursor is driven by the
 * game's own clock via alphaTab's external media sync — alphaTab never
 * produces audio itself (that stays with the app's own MelodyPlayer).
 *
 * The view is kept in sync with the note-bar view through a single shared time line:
 * - [scrollTimeSeconds] is placed at the sheet's left edge, so panning the note-bar
 *   view scrolls the sheet and keeps the cursor in view.
 * - [seekTimeSeconds] is the cursor target (anchored to the leftmost note when the
 *   user has panned), moved via the external media output.
 * - Dragging the sheet pans it directly and reports the time at its left edge via
 *   [onScrolledToTime] so the note-bar view can seek in turn.
 */
@Composable
fun SheetMusicView(
    alphaTex: String,
    currentTimeSeconds: Double,
    isPlaying: Boolean,
    seekTimeSeconds: Double,
    scrollTimeSeconds: Double,
    durationSeconds: Double,
    onScrolledToTime: (Double) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val apiRef = remember { mutableStateOf<AlphaTabApiBase<AlphaTabView>?>(null) }
    var midiReady by remember { mutableStateOf(false) }
    var loadedTex by remember { mutableStateOf<String?>(null) }
    var beatPositions by remember { mutableStateOf(emptyList<BeatPosition>()) }
    var scrollView by remember { mutableStateOf<HorizontalScrollView?>(null) }
    var isSheetDragging by remember { mutableStateOf(false) }
    val currentTime by rememberUpdatedState(currentTimeSeconds)
    val playing by rememberUpdatedState(isPlaying)
    val durationSec by rememberUpdatedState(durationSeconds)
    val seekTime by rememberUpdatedState(seekTimeSeconds)
    val scrollTime by rememberUpdatedState(scrollTimeSeconds)
    val onScrolledTo by rememberUpdatedState(onScrolledToTime)
    val density = LocalDensity.current.density
    val cursorInsetPx = CURSOR_INSET_DP * density

    // Drag the sheet music directly: pan the score under the finger and report the
    // resulting left-edge time so the note-bar view seeks in lockstep.
    fun scrubTouchListener(context: android.content.Context): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var lastX = 0f
        var totalDx = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    totalDx = 0f
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (playing) return@OnTouchListener true
                    val dx = event.x - lastX
                    lastX = event.x
                    totalDx += dx
                    if (!dragging && abs(totalDx) > touchSlop) dragging = true
                    if (!dragging) return@OnTouchListener false
                    isSheetDragging = true
                    val scroll = scrollView ?: return@OnTouchListener true
                    scroll.scrollTo(scroll.scrollX - dx.toInt(), scroll.scrollY)
                    val positions = beatPositions
                    if (positions.isEmpty()) return@OnTouchListener true
                    val leftEdgeTime = timeForX(((scroll.scrollX + cursorInsetPx) / density).toDouble(), positions) / 1000.0
                    onScrolledTo(leftEdgeTime)
                    true
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    dragging = false
                    isSheetDragging = false
                    false
                }
                else -> false
            }
        }
    }

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
                player.scrollMode = ScrollMode.Off
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

            val foundScroll = findHorizontalScrollView(view)
            scrollView = foundScroll
            foundScroll?.setOnTouchListener(scrubTouchListener(context))

            // The initial score load typically happens before the view is laid out,
            // in which case alphaTab skips rendering (width=0) and never retries.
            // Trigger a manual render once both score and layout are available.
            var firstRenderDone = false
            api.postRenderFinished.on {
                firstRenderDone = true
                beatPositions = buildBeatPositions(api)
            }
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

    // Push the cursor to alphaTab; when game time freezes (e.g. wait mode) no
    // updates are emitted and the cursor freezes with it. seekTimeSeconds equals
    // the game clock during playback (pan offset is 0) and anchors to the
    // leftmost note while the user is panning.
    LaunchedEffect(midiReady) {
        if (!midiReady) return@LaunchedEffect
        snapshotFlow { seekTime }
            .sample(50)
            .collectLatest { t ->
                (apiRef.value?.player?.output as? IExternalMediaSynthOutput)
                    ?.updatePosition(t.coerceAtLeast(0.0) * 1000.0)
            }
    }

    // Scroll the sheet so the beat at scrollTime sits at the cursor inset, using
    // alphaTab's actual layout positions (notes and rests don't map linearly to
    // time). This follows the note-bar view during panning and keeps the cursor
    // in view during playback. Skipped while the sheet itself is being dragged
    // so the score tracks the finger 1:1.
    LaunchedEffect(midiReady, scrollTime, beatPositions, isSheetDragging) {
        if (!midiReady) return@LaunchedEffect
        if (isSheetDragging) return@LaunchedEffect
        val scroll = scrollView ?: return@LaunchedEffect
        if (beatPositions.isEmpty()) return@LaunchedEffect
        val timeMs = scrollTime.coerceIn(0.0, durationSec) * 1000.0
        val targetX = (xForTime(timeMs, beatPositions) * density).toInt() - cursorInsetPx.toInt()
        scroll.scrollTo(targetX.coerceAtLeast(0), scroll.scrollY)
    }
}

/** Depth-first search for alphaTab's internal horizontal scroll view. */
private fun findHorizontalScrollView(root: View): HorizontalScrollView? {
    if (root is HorizontalScrollView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findHorizontalScrollView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/** A rendered beat: its playback start (ms) and horizontal position (CSS px). */
private class BeatPosition(val timeMs: Double, val xCss: Double)

/**
 * Collects every rendered beat's playback time and horizontal position from
 * alphaTab's layout so time <-> x lookups follow the real score layout instead
 * of assuming a linear time axis (which drifts whenever note durations and
 * rests vary).
 */
private fun buildBeatPositions(api: AlphaTabApiBase<AlphaTabView>): List<BeatPosition> {
    val lookup = api.boundsLookup ?: return emptyList()
    val tickCache = api.tickCache ?: return emptyList()
    val score = api.score ?: return emptyList()
    val positions = mutableListOf<BeatPosition>()
    for (track in score.tracks) {
        for (staff in track.staves) {
            for (bar in staff.bars) {
                for (voice in bar.voices) {
                    for (beat in voice.beats) {
                        val bounds = lookup.findBeat(beat) ?: continue
                        positions.add(BeatPosition(tickCache.getBeatStart(beat), bounds.onNotesX))
                    }
                }
            }
        }
    }
    return positions.sortedBy { it.timeMs }
}

/** Horizontal position (CSS px) of the playhead at [timeMs], interpolated between beats. */
private fun xForTime(timeMs: Double, positions: List<BeatPosition>): Double {
    if (positions.isEmpty()) return 0.0
    var i = positions.indexOfLast { it.timeMs <= timeMs }
    if (i < 0) return positions.first().xCss
    val current = positions[i]
    val next = positions.getOrNull(i + 1)
    if (next == null || next.timeMs <= current.timeMs) return current.xCss
    val fraction = ((timeMs - current.timeMs) / (next.timeMs - current.timeMs)).coerceIn(0.0, 1.0)
    return current.xCss + fraction * (next.xCss - current.xCss)
}

/** Playback time (ms) at horizontal position [xCss], interpolated between beats. */
private fun timeForX(xCss: Double, positions: List<BeatPosition>): Double {
    if (positions.isEmpty()) return 0.0
    var i = positions.indexOfLast { it.xCss <= xCss }
    if (i < 0) return positions.first().timeMs
    val current = positions[i]
    val next = positions.getOrNull(i + 1)
    if (next == null || next.xCss <= current.xCss) return current.timeMs
    val fraction = ((xCss - current.xCss) / (next.xCss - current.xCss)).coerceIn(0.0, 1.0)
    return current.timeMs + fraction * (next.timeMs - current.timeMs)
}
