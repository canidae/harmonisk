@file:OptIn(kotlin.contracts.ExperimentalContracts::class)

package net.exent.perfectpitch.ui.melody

import android.graphics.drawable.ColorDrawable
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
import net.exent.perfectpitch.midi.MelodyTrack
import kotlin.math.abs

private const val TAG = "SheetMusicView"

/** Leading space kept between the sheet's left edge and the cursor position. */
private const val CURSOR_INSET_DP = 18f

/** alphaTab's MIDI tick resolution (ticks per quarter note), used by its tick lookup. */
private const val ALPHATAB_TICKS_PER_QUARTER = 960.0

/** Fill color of alphaTab's beat cursor (the vertical line), used to locate its view. */
private val BEAT_CURSOR_COLOR = android.graphics.Color.argb(255, 64, 64, 255)

/**
 * Sheet music staff rendered by alphaTab. The playback cursor is driven by the
 * game's own clock via alphaTab's external media sync — alphaTab never
 * produces audio itself (that stays with the app's own MelodyPlayer).
 *
 * During playback the sheet pages so alphaTab's beat cursor stays in view: the
 * cursor moves freely across the score (alphaTab animates it with its own
 * timing, including a leading overshoot), and the sheet advances when the cursor
 * reaches the right edge. Near the end of the music the sheet stops paging and
 * lets the cursor finish moving right. This avoids chasing the cursor every
 * frame, which made it flicker.
 *
 * While the user pans the shared time line ([songTimeOffsetSeconds] != 0) the
 * sheet scrolls smoothly from [scrollTimeSeconds] so it tracks the note-bar view,
 * and the cursor is pinned to the sheet's left edge so it stays in view. Dragging
 * the sheet pans it directly (and pins the cursor the same way) and reports the
 * time under the finger via [onScrolledToTime] so the note-bar view can seek in turn.
 */
@Composable
fun SheetMusicView(
    melodyTrack: MelodyTrack,
    alphaTex: String,
    currentTimeSeconds: Double,
    isPlaying: Boolean,
    seekTimeSeconds: Double,
    scrollTimeSeconds: Double,
    songTimeOffsetSeconds: Double,
    durationSeconds: Double,
    onScrolledToTime: (Double) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val apiRef = remember { mutableStateOf<AlphaTabApiBase<AlphaTabView>?>(null) }
    var midiReady by remember { mutableStateOf(false) }
    var loadedTex by remember { mutableStateOf<String?>(null) }
    var beatPositions by remember { mutableStateOf(emptyList<BeatPosition>()) }
    var beatCursorView by remember { mutableStateOf<View?>(null) }
    var scrollView by remember { mutableStateOf<HorizontalScrollView?>(null) }
    var isSheetDragging by remember { mutableStateOf(false) }
    val currentTime by rememberUpdatedState(currentTimeSeconds)
    val playing by rememberUpdatedState(isPlaying)
    val durationSec by rememberUpdatedState(durationSeconds)
    val seekTime by rememberUpdatedState(seekTimeSeconds)
    val scrollTime by rememberUpdatedState(scrollTimeSeconds)
    val songOffset by rememberUpdatedState(songTimeOffsetSeconds)
    val onScrolledTo by rememberUpdatedState(onScrolledToTime)
    val density = LocalDensity.current.density
    val cursorInsetPx = CURSOR_INSET_DP * density

    // Drag the sheet music directly: pan the score under the finger and report the
    // resulting left-edge time so the note-bar view seeks in lockstep.
    fun scrubTouchListener(context: android.content.Context): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var lastX = 0f
        var lastDownTime = 0L
        var totalDx = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
            // ACTION_DOWN is not always delivered to this view (a parent consumes
            // it), so lastX would stay 0 and the first MOVE would report a huge dx,
            // jumping the sheet. Anchor each gesture to its first delivered event
            // instead of relying on ACTION_DOWN.
            if (event.downTime != lastDownTime) {
                lastDownTime = event.downTime
                lastX = event.x
                totalDx = 0f
                dragging = false
                return@OnTouchListener false
            }
            when (event.actionMasked) {
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
                    // Pin the marker to the left edge while the sheet is under the
                    // finger, so it never flies out of the view while panning.
                    val firstBeatX = (positions.first().xCss * density).toInt()
                    val cursor = beatCursorView
                    val cursorParams = cursor?.layoutParams as? android.widget.RelativeLayout.LayoutParams
                    if (cursor != null && cursorParams != null) {
                        cursor.clearAnimation()
                        cursorParams.leftMargin = maxOf(scroll.scrollX + cursorInsetPx.toInt(), firstBeatX)
                        cursor.visibility = View.VISIBLE
                        cursor.requestLayout()
                    }
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
            view.beatCursorFillColor = BEAT_CURSOR_COLOR
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

            // Locate alphaTab's beat cursor view (created during setup) so the sheet
            // can scroll to the cursor's actual position instead of predicting it.
            beatCursorView = findBeatCursor(view)

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
                beatPositions = buildBeatPositions(api, melodyTrack)
                if (beatCursorView == null) beatCursorView = findBeatCursor(view)
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

    // Push the cursor to alphaTab during playback (pan offset is 0 and the sheet
    // is not being dragged). While the user pans the sheet or the shared time
    // line, the cursor is positioned directly instead (see below), so alphaTab's
    // own tick-based placement is skipped to keep the marker pinned in view.
    LaunchedEffect(midiReady) {
        if (!midiReady) return@LaunchedEffect
        snapshotFlow { seekTime }
            .sample(50)
            .collectLatest { t ->
                if (isSheetDragging || abs(songOffset) >= 0.001) return@collectLatest
                (apiRef.value?.player?.output as? IExternalMediaSynthOutput)
                    ?.updatePosition(t.coerceAtLeast(0.0) * 1000.0)
            }
    }

    // Keep the playback cursor in view. alphaTab animates the cursor with its own
    // timing (a per-note glide plus a leading overshoot), so continuously pinning
    // the cursor to the inset by scrolling every frame makes it flicker — the scroll
    // chases the overshoot with a one-frame lag. Instead, let the cursor move freely
    // and page the sheet only when it approaches the right edge (or passes the left
    // edge). Near the end of the music there is no room left to page back to the
    // left, so the sheet stops and lets the cursor finish moving right.
    // While the user is panning the shared time line (songTimeOffsetSeconds != 0) the
    // sheet scrolls smoothly from [scrollTimeSeconds] so it tracks the note-bar view.
    // Skipped while the sheet is being dragged.
    LaunchedEffect(midiReady, scrollTime, beatPositions, isSheetDragging, beatCursorView) {
        if (!midiReady) return@LaunchedEffect
        if (isSheetDragging) return@LaunchedEffect
        val scroll = scrollView ?: return@LaunchedEffect
        val params = beatCursorView?.layoutParams as? android.widget.RelativeLayout.LayoutParams
        if (abs(songOffset) < 0.001 && params != null) {
            val viewportWidth = scroll.width
            if (viewportWidth <= 0) return@LaunchedEffect
            val onScreenX = params.leftMargin - scroll.scrollX
            val leadX = cursorInsetPx.toInt()
            val pageRightX = viewportWidth - (CURSOR_INSET_DP * 4f * density).toInt()
            // Near the end of the music there is no room left to page back to the left;
            // stop scrolling and let the cursor finish moving to the right instead.
            val lastMusicX = (beatPositions.lastOrNull { !it.isRest }?.xCss ?: 0.0) * density
            val pageable = params.leftMargin + viewportWidth / 2f < lastMusicX
            if (onScreenX < 0) {
                // The cursor reset to the start (the song finished): scroll all the
                // way back so the staff clef is visible again.
                scroll.scrollTo(0, scroll.scrollY)
            } else if (onScreenX > pageRightX && pageable) {
                scroll.scrollTo((params.leftMargin - leadX).coerceAtLeast(0), scroll.scrollY)
            }
        } else if (beatPositions.isNotEmpty()) {
            val timeMs = scrollTime.coerceIn(0.0, durationSec) * 1000.0
            val targetX = (xForTime(timeMs, beatPositions) * density).toInt() - cursorInsetPx.toInt()
            // At the very start of the score the staff clef sits before the first
            // beat; once the target is within that zone, scroll fully to the left so
            // the clef is visible instead of clipping it (e.g. when a finished song
            // scrolls back to the beginning).
            val firstBeatX = (beatPositions.first().xCss * density).toInt()
            val startX = if (targetX < firstBeatX + cursorInsetPx.toInt()) 0 else targetX.coerceAtLeast(0)
            scroll.scrollTo(startX, scroll.scrollY)
            // While panning, keep the marker pinned to the left edge (the panned-to
            // time sits at the sheet's left edge) so it never leaves the view.
            val cursor = beatCursorView ?: return@LaunchedEffect
            val cursorParams = cursor.layoutParams as? android.widget.RelativeLayout.LayoutParams
                ?: return@LaunchedEffect
            cursor.clearAnimation()
            cursorParams.leftMargin = maxOf(startX + cursorInsetPx.toInt(), firstBeatX)
            cursor.visibility = View.VISIBLE
            cursor.requestLayout()
        }
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

/** Depth-first search for alphaTab's beat cursor view, identified by its fill color. */
private fun findBeatCursor(root: View): View? {
    val background = root.background
    if (background is ColorDrawable && background.color == BEAT_CURSOR_COLOR) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findBeatCursor(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/** A rendered beat: its playback start (ms), horizontal position (CSS px) and rest state. */
private class BeatPosition(val timeMs: Double, val xCss: Double, val isRest: Boolean)

/**
 * Collects every rendered beat's playback time and horizontal position from
 * alphaTab's layout so time <-> x lookups follow the real score layout instead
 * of assuming a linear time axis (which drifts whenever note durations and
 * rests vary).
 *
 * alphaTab's tick lookup reports beat starts in its own MIDI ticks (960 per
 * quarter note), not milliseconds. Map those ticks through the melody track's
 * own tick -> seconds conversion so the sheet's time axis matches the game
 * clock and the note-bar canvas.
 */
private fun buildBeatPositions(api: AlphaTabApiBase<AlphaTabView>, track: MelodyTrack): List<BeatPosition> {
    val lookup = api.boundsLookup ?: return emptyList()
    val tickCache = api.tickCache ?: return emptyList()
    val score = api.score ?: return emptyList()
    val positions = mutableListOf<BeatPosition>()
    for (scoreTrack in score.tracks) {
        for (staff in scoreTrack.staves) {
            for (bar in staff.bars) {
                for (voice in bar.voices) {
                    for (beat in voice.beats) {
                        val bounds = lookup.findBeat(beat) ?: continue
                        val alphaTabTick = tickCache.getBeatStart(beat)
                        val trackTick = (alphaTabTick * track.ticksPerQuarterNote.coerceAtLeast(1) / ALPHATAB_TICKS_PER_QUARTER).toLong()
                        positions.add(BeatPosition(track.tickToSeconds(trackTick) * 1000.0, bounds.onNotesX, beat.isRest))
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
