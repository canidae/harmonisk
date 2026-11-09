@file:OptIn(kotlin.contracts.ExperimentalContracts::class, kotlin.ExperimentalUnsignedTypes::class, kotlinx.coroutines.FlowPreview::class)

package net.exent.harmonisk.ui.melody

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
import net.exent.harmonisk.BuildConfig
import net.exent.harmonisk.midi.MelodyTrack
import kotlin.math.abs

private const val TAG = "SheetMusicView"

private const val CURSOR_INSET_DP = 18f

private const val ALPHATAB_TICKS_PER_QUARTER = 960.0

private val BEAT_CURSOR_COLOR = android.graphics.Color.argb(255, 64, 64, 255)

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

    fun scrubTouchListener(context: android.content.Context): View.OnTouchListener {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var lastX = 0f
        var lastDownTime = 0L
        var totalDx = 0f
        var dragging = false
        return View.OnTouchListener { _, event ->
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

            beatCursorView = findBeatCursor(view)

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

    LaunchedEffect(midiReady) {
        if (!midiReady) return@LaunchedEffect
        snapshotFlow { playing && currentTime >= 0.0 }
            .distinctUntilChanged()
            .collect { play ->
                val api = apiRef.value ?: return@collect
                if (play) api.play() else api.pause()
            }
    }

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
            if (onScreenX < 0) {
                scroll.scrollTo(0, scroll.scrollY)
            } else if (onScreenX > pageRightX) {
                val maxScroll = ((scroll.getChildAt(0)?.width ?: 0) - viewportWidth).coerceAtLeast(0)
                scroll.scrollTo((params.leftMargin - leadX).coerceIn(0, maxScroll), scroll.scrollY)
            }
        } else if (beatPositions.isNotEmpty()) {
            val timeMs = scrollTime.coerceIn(0.0, durationSec) * 1000.0
            val targetX = (xForTime(timeMs, beatPositions) * density).toInt() - cursorInsetPx.toInt()
            val firstBeatX = (beatPositions.first().xCss * density).toInt()
            val startX = if (targetX < firstBeatX + cursorInsetPx.toInt()) 0 else targetX.coerceAtLeast(0)
            scroll.scrollTo(startX, scroll.scrollY)
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

private fun findHorizontalScrollView(root: View): HorizontalScrollView? {
    if (root is HorizontalScrollView) return root
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            findHorizontalScrollView(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

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

private class BeatPosition(val timeMs: Double, val xCss: Double, val isRest: Boolean)

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
