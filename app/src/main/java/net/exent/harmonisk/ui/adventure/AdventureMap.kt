package net.exent.harmonisk.ui.adventure

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import net.exent.harmonisk.ui.theme.AdventureGold
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private val GROUND_TOP = Color(0xFF4C7A3F)
private val GROUND_BOTTOM = Color(0xFF244A31)
private val PATH_DARK = Color(0xFF6B4A2F)
private val PATH = Color(0xFFB98A57)
private val PATH_LIGHT = Color(0xFFD8AE72)
private val RIVER_DARK = Color(0xFF2C6B9A)
private val RIVER = Color(0xFF3D8EC9)
private val RIVER_LIGHT = Color(0xFF8ED0F0)
private val MOUNTAIN = Color(0xFF6D665C)
private val MOUNTAIN_DARK = Color(0xFF514C45)
private val SNOW = Color(0xFFF5F1E6)
private val TREE_DARK = Color(0xFF245A32)
private val TREE = Color(0xFF3F8A46)
private val TREE_LIGHT = Color(0xFF65A85A)
private val STONE = Color(0xFF77736B)
private val STONE_LIGHT = Color(0xFF9A958B)
private val WOOD = Color(0xFF8A5B36)
private val WOOD_LIGHT = Color(0xFFB07A4A)
private val CASTLE_STONE = Color(0xFFB5AFA1)
private val CASTLE_DARK = Color(0xFF817B70)

private val FIELD_STICKERS = Color(0xFF3FC7C0)
private val FIELD_SONG = AdventureGold
private val FIELD_GO_AGAIN = Color(0xFF9B7BFF)
private val FIELD_WHACK = Color(0xFFE05D5D)
private val FIELD_START = Color(0xFF8DE8C4)

private const val MAP_TOP_UNITS = 1.5f
private const val MAP_BOTTOM_UNITS = 0.5f
private const val CASTLE_Y = 0.06f

data class AdventureAnimal(val emoji: String, val position: Offset)

data class AdventureMapSpec(
    val points: List<Offset>,
    val mountains: List<Offset>,
    val trees: List<Offset>,
    val rocks: List<Offset>,
    val animals: List<AdventureAnimal>,
    val lake: Offset,
    val village: List<Offset>,
    val river: List<Offset>,
    val bridge: Offset,
    val bridgeSegment: Int,
    val contentAspect: Float
)

private val LAND_ANIMALS = listOf("🐗", "🦌", "🦊", "🐰", "🦔", "🐑", "🐐", "🐿️")

private class Obstacle(val samples: List<Offset>, val marginX: Float, val marginY: Float)

private fun ellipseScore(spot: Offset, sample: Offset, marginX: Float, marginY: Float): Float {
    val dx = (spot.x - sample.x) / marginX
    val dy = (spot.y - sample.y) / marginY
    return dx * dx + dy * dy
}

private fun List<Obstacle>.allows(spot: Offset): Boolean = none { obstacle ->
    obstacle.samples.any { ellipseScore(spot, it, obstacle.marginX, obstacle.marginY) < 1f }
}

private fun openSpot(
    random: Random,
    near: Offset,
    spreadX: Float,
    spreadY: Float,
    obstacles: List<Obstacle>,
    xRange: ClosedFloatingPointRange<Float> = 0.08f..0.92f,
    yRange: ClosedFloatingPointRange<Float> = 0.02f..0.98f
): Offset {
    var best = near
    var bestScore = -1f
    repeat(160) {
        val spot = Offset(
            (near.x + (random.nextFloat() - 0.5f) * 2f * spreadX).coerceIn(xRange.start, xRange.endInclusive),
            (near.y + (random.nextFloat() - 0.5f) * 2f * spreadY).coerceIn(yRange.start, yRange.endInclusive)
        )
        val score = obstacles.minOf { obstacle ->
            obstacle.samples.minOf { ellipseScore(spot, it, obstacle.marginX, obstacle.marginY) }
        }
        if (score > bestScore) {
            bestScore = score
            best = spot
        }
        if (bestScore > 1.5f) return best
    }
    return best
}

private fun scatter(
    random: Random,
    count: Int,
    obstacles: List<Obstacle>,
    xRange: ClosedFloatingPointRange<Float> = 0.04f..0.96f,
    yRange: ClosedFloatingPointRange<Float> = 0.02f..0.98f
): List<Offset> {
    fun pick(range: ClosedFloatingPointRange<Float>) =
        range.start + random.nextFloat() * (range.endInclusive - range.start)
    return List(count * 6) { Offset(pick(xRange), pick(yRange)) }
        .filter { obstacles.allows(it) }
        .take(count)
}

private fun crossings(pathSamples: List<Offset>, river: List<Offset>): Int {
    val water = sampleCurve(river)
    var previous = 0
    var total = 0
    pathSamples.forEach { point ->
        val surface = water.minByOrNull { abs(it.x - point.x) }?.y ?: return@forEach
        val side = if (point.y > surface) 1 else -1
        if (previous != 0 && side != previous) total++
        previous = side
    }
    return total
}

fun generateMapSpec(random: Random, columns: Int = 4): AdventureMapSpec {
    val columnCount = columns.coerceAtLeast(3)
    val rows = (ADVENTURE_GOAL + columnCount - 1) / columnCount
    val contentUnits = rows + MAP_TOP_UNITS + MAP_BOTTOM_UNITS
    val cellW = 1f / columnCount
    val cellH = 1f / contentUnits
    val contentAspect = contentUnits / columnCount

    fun rowY(row: Int) = (contentUnits - MAP_BOTTOM_UNITS - (row + 0.5f)) / contentUnits
    val fields = (0 until ADVENTURE_GOAL).map { index ->
        val row = index / columnCount
        val positionInRow = index % columnCount
        val column = if (row % 2 == 0) positionInRow else columnCount - 1 - positionInRow
        val jitterX = if (index == 0) 0f else (random.nextFloat() - 0.5f) * 0.12f * cellW
        val jitterY = if (index == 0) 0f else (random.nextFloat() - 0.5f) * 0.10f * cellH
        Offset(
            ((column + 0.5f) * cellW + jitterX).coerceIn(0.05f, 0.95f),
            (rowY(row) + jitterY).coerceIn(0.02f, 0.98f)
        )
    }
    val points = fields + Offset(0.5f, CASTLE_Y)
    val pathSamples = sampleCurve(points)

    val gap = (rows / 2).coerceIn(1, (rows - 1).coerceAtLeast(1))
    val gapY = (contentUnits - MAP_BOTTOM_UNITS - gap) / contentUnits
    val riverAmplitude = 0.14f * cellH
    fun river(amplitude: Float) = List(9) { index ->
        val x = -0.06f + index * 0.14f
        Offset(x, gapY + amplitude * sin(5f * (x - 0.5f)))
    }
    val wavyRiver = river(riverAmplitude)
    val riverPoints = if (crossings(pathSamples, wavyRiver) == 1) wavyRiver else river(0f)
    val bridgeSegment = (gap * columnCount - 1).coerceIn(0, points.lastIndex - 1)
    val bridge = cubicPoint(points, bridgeSegment, 0.5f)

    fun pathObstacle() = Obstacle(pathSamples, 0.46f * cellW, 0.46f * cellH)
    fun riverObstacle() = Obstacle(sampleCurve(riverPoints), 0.34f * cellW, 0.34f * cellH)
    fun fieldObstacle() = Obstacle(points, 0.32f * cellW, 0.32f * cellH)
    val lakeObstacleMarginX = 0.8f * cellW
    val lakeObstacleMarginY = 0.8f * cellH

    val lake = openSpot(
        random,
        near = Offset(0.5f, gapY),
        spreadX = 0.3f,
        spreadY = 0.06f,
        obstacles = listOf(
            pathObstacle(),
            riverObstacle(),
            Obstacle(points, 0.4f * cellW, 0.4f * cellH)
        ),
        xRange = 0.18f..0.82f,
        yRange = 0.08f..0.92f
    )
    val village = mutableListOf<Offset>()
    val villageObstacles = mutableListOf(
        pathObstacle(),
        riverObstacle(),
        Obstacle(listOf(lake), lakeObstacleMarginX, lakeObstacleMarginY),
        fieldObstacle()
    )
    repeat(3) {
        village += openSpot(
            random,
            near = points[3] + Offset(
                (random.nextFloat() - 0.5f) * 0.8f * cellW,
                (random.nextFloat() - 0.5f) * 0.8f * cellH
            ),
            spreadX = 0.3f * cellW,
            spreadY = 0.3f * cellH,
            obstacles = villageObstacles
        ).also { spot ->
            villageObstacles += Obstacle(listOf(spot), 0.28f * cellW, 0.28f * cellH)
        }
    }
    val decorationObstacles = mutableListOf(
        pathObstacle(),
        riverObstacle(),
        Obstacle(listOf(lake), lakeObstacleMarginX, lakeObstacleMarginY),
        Obstacle(village, 0.24f * cellW, 0.24f * cellH)
    )
    val trees = scatter(
        random,
        (columnCount * rows * 1.1f).roundToInt(),
        decorationObstacles
    )
    val rocks = scatter(
        random,
        (columnCount * rows * 0.55f).roundToInt(),
        decorationObstacles
    )
    val mountains = scatter(
        random,
        6,
        decorationObstacles + Obstacle(listOf(points.last()), 0.7f * cellW, 0.7f * cellH),
        xRange = 0.08f..0.92f,
        yRange = 0.015f..0.2f
    )
    val animalObstacles = mutableListOf(
        Obstacle(mountains, 0.45f * cellW, 0.45f * cellH),
        pathObstacle(),
        riverObstacle(),
        Obstacle(listOf(lake), lakeObstacleMarginX, lakeObstacleMarginY),
        Obstacle(village, 0.24f * cellW, 0.24f * cellH),
        fieldObstacle(),
        Obstacle(trees, 0.2f * cellW, 0.2f * cellH)
    )
    val animals = mutableListOf<AdventureAnimal>()
    LAND_ANIMALS.shuffled(random).take(3 + random.nextInt(4)).forEach { emoji ->
        repeat(1 + random.nextInt(4)) {
            val spot = scatter(random, 1, animalObstacles).firstOrNull() ?: return@repeat
            animals += AdventureAnimal(emoji, spot)
            animalObstacles += Obstacle(listOf(spot), 0.2f * cellW, 0.2f * cellH)
        }
    }
    return AdventureMapSpec(
        points = points,
        mountains = mountains,
        trees = trees,
        rocks = rocks,
        animals = animals,
        lake = lake,
        village = village,
        river = riverPoints,
        bridge = bridge,
        bridgeSegment = bridgeSegment,
        contentAspect = contentAspect
    )
}

@Composable
fun AdventureMap(
    players: List<AdventurePlayer>,
    currentPlayerId: String?,
    mapSeed: Long,
    fieldRewards: List<AdventureReward?>,
    moveFrom: Int = 0,
    moving: Boolean = false,
    focusPlayerId: String? = null,
    onTokenPosition: (String, Offset) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val columns = (maxWidth.value / 92f).roundToInt().coerceIn(4, 8)
        val spec = remember(mapSeed, columns) { generateMapSpec(Random(mapSeed), columns) }
        val contentWidth = maxWidth
        val contentHeight = maxOf(contentWidth * spec.contentAspect, maxHeight)
        val contentWidthPx = with(density) { contentWidth.toPx() }
        val contentHeightPx = with(density) { contentHeight.toPx() }
        val pointsDp = remember(spec, contentWidth, contentHeight) {
            spec.points.map { Offset(contentWidth.value * it.x, contentHeight.value * it.y) }
        }
        val initialField = players.firstOrNull { it.profileId == currentPlayerId }?.position ?: 0
        val initialPoint = spec.points.getOrNull(initialField.coerceIn(0, spec.points.lastIndex))
        val initialScroll = (
            (initialPoint?.y ?: 1f) * contentHeightPx - viewportHeightPx / 2f
            ).coerceAtLeast(0f).roundToInt()
        val scroll = rememberScrollState(initial = initialScroll)
        val pointsPx = remember(spec, contentWidthPx, contentHeightPx) {
            spec.points.map { Offset(it.x * contentWidthPx, it.y * contentHeightPx) }
        }
        val riverPx = remember(spec, contentWidthPx, contentHeightPx) {
            spec.river.map { Offset(it.x * contentWidthPx, it.y * contentHeightPx) }
        }
        val bridgePx = remember(spec, contentWidthPx, contentHeightPx) {
            Offset(spec.bridge.x * contentWidthPx, spec.bridge.y * contentHeightPx)
        }
        val path = remember(pointsPx) { smoothPath(pointsPx) }
        val riverPath = remember(riverPx) { smoothPath(riverPx) }
        val segmentMeasures = remember(pointsPx) {
            (0 until pointsPx.lastIndex).map { index ->
                PathMeasure().apply { setPath(segmentPath(pointsPx, index), false) }
            }
        }
        val bridgeAngle = remember(pointsPx, spec.bridgeSegment) {
            val from = cubicPoint(pointsPx, spec.bridgeSegment, 0.45f)
            val to = cubicPoint(pointsPx, spec.bridgeSegment, 0.55f)
            Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
        }
        val fieldSize = (contentWidth / columns.toFloat() * 0.5f).coerceIn(30.dp, 44.dp)
        val tokenSize = fieldSize * 0.78f
        val flowTransition = rememberInfiniteTransition(label = "river_flow")
        val flowState = flowTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "river_flow_value"
        )
        val ringPulse by rememberInfiniteTransition(label = "faerie_ring").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "faerie_ring_value"
        )
        val measurer = rememberTextMeasurer()
        val animalLayouts = remember(spec) {
            spec.animals.map { measurer.measure(it.emoji, TextStyle(fontSize = 20.sp)) }
        }
        val currentField = players.firstOrNull { it.profileId == currentPlayerId }?.position ?: 0
        LaunchedEffect(currentPlayerId, currentField, focusPlayerId, columns, contentHeightPx) {
            withFrameNanos { }
            val field = focusPlayerId?.let { id -> players.firstOrNull { it.profileId == id }?.position }
                ?: currentField
            val point = spec.points.getOrNull(field.coerceIn(0, spec.points.lastIndex)) ?: return@LaunchedEffect
            val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
            val target = (point.y * contentHeightPx - viewportHeightPx / 2f).coerceIn(0f, maxScroll)
            scroll.animateScrollTo(target.roundToInt())
        }

        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .clipToBounds()
        ) {
            Box(Modifier.fillMaxWidth().height(contentHeight)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        Brush.verticalGradient(
                            listOf(GROUND_TOP, GROUND_BOTTOM),
                            startY = 0f,
                            endY = size.height
                        )
                    )
                    drawGroundPatches()
                    spec.mountains.forEach { drawMountain(it, size.width, size.height) }
                    drawRiver(riverPath, flowState.value)
                    drawLake(Offset(spec.lake.x * size.width, spec.lake.y * size.height))
                    spec.village.forEach { drawHouse(Offset(it.x * size.width, it.y * size.height)) }
                    spec.trees.forEach { drawTree(Offset(it.x * size.width, it.y * size.height), 1f) }
                    spec.rocks.forEach { drawRock(Offset(it.x * size.width, it.y * size.height)) }
                    drawPath(path)
                    drawBridge(bridgePx, bridgeAngle)
                    drawCastle(Offset(pointsPx.last().x, pointsPx.last().y - 22.dp.toPx()))
                    spec.animals.forEachIndexed { index, animal ->
                        val layout = animalLayouts[index]
                        val center = Offset(animal.position.x * size.width, animal.position.y * size.height)
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
                        )
                    }
                    if (players.any { it.position >= ADVENTURE_GOAL && it.profileId == currentPlayerId }) {
                        drawCircle(
                            color = StorybookGold,
                            radius = 30.dp.toPx(),
                            center = pointsPx.last(),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                    val focused = focusPlayerId?.let { id -> players.firstOrNull { it.profileId == id } }
                    val focusPoint = focused?.let { pointsPx.getOrNull(it.position.coerceIn(0, pointsPx.lastIndex)) }
                    if (focusPoint != null) {
                        val ringCenter = focusPoint - Offset(0f, tokenSize.toPx() * 0.5f)
                        drawCircle(
                            color = StorybookGold.copy(alpha = 0.55f - 0.35f * ringPulse),
                            radius = tokenSize.toPx() * (0.72f + 0.22f * ringPulse),
                            center = ringCenter,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        drawCircle(
                            color = StorybookGold.copy(alpha = 0.18f * (1f - ringPulse)),
                            radius = tokenSize.toPx() * (0.9f + 0.4f * ringPulse),
                            center = ringCenter,
                            style = Stroke(width = 6.dp.toPx())
                        )
                    }
                }

                pointsDp.forEachIndexed { index, point ->
                    if (index <= ADVENTURE_FIELD_COUNT) {
                        val field = index
                        val reached = players.any { it.position >= field }
                        val active = !moving && players.any { it.position == field && it.profileId == currentPlayerId }
                        FieldBadge(
                            index = field,
                            reward = fieldRewards.getOrNull(field),
                            reached = reached,
                            active = active,
                            size = fieldSize,
                            modifier = Modifier.offset(
                                x = point.x.dp - fieldSize / 2,
                                y = point.y.dp - fieldSize / 2
                            )
                        )
                    }
                }

                players.forEach { player ->
                    val atField = players.count { it.position == player.position }
                    val indexInField = players.filter { it.position == player.position }.indexOf(player)
                    val startAt = if (player.profileId == currentPlayerId && moving && moveFrom < player.position) {
                        moveFrom.toFloat()
                    } else {
                        player.position.toFloat()
                    }
                    val steps = if (player.profileId == currentPlayerId && moving && player.position > moveFrom) {
                        player.position - moveFrom
                    } else {
                        1
                    }
                    AnimatedPlayerToken(
                        player = player,
                        current = player.profileId == currentPlayerId,
                        startAt = startAt,
                        walkMs = AdventureTiming.walkMs(steps),
                        points = pointsPx,
                        segmentMeasures = segmentMeasures,
                        tokenSize = tokenSize,
                        offsetX = ((indexInField - (atField - 1) / 2f) * 14f).dp,
                        onPositioned = { position ->
                            onTokenPosition(player.profileId, position)
                        }
                    )
                }
            }
        }
    }
}

private fun controlPoints(points: List<Offset>, index: Int): Pair<Offset, Offset> {
    val previous = points[(index - 1).coerceAtLeast(0)]
    val from = points[index]
    val to = points[index + 1]
    val next = points[(index + 2).coerceAtMost(points.lastIndex)]
    return (from + (to - previous) / 6f) to (to - (next - from) / 6f)
}

private fun cubicPoint(from: Offset, control1: Offset, control2: Offset, to: Offset, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(
        a * from.x + b * control1.x + c * control2.x + d * to.x,
        a * from.y + b * control1.y + c * control2.y + d * to.y
    )
}

private fun cubicPoint(points: List<Offset>, index: Int, t: Float): Offset {
    val (control1, control2) = controlPoints(points, index)
    return cubicPoint(points[index], control1, control2, points[index + 1], t)
}

private fun sampleCurve(points: List<Offset>, steps: Int = 24): List<Offset> = buildList {
    for (index in 0 until points.lastIndex) {
        for (step in 0 until steps) add(cubicPoint(points, index, step / steps.toFloat()))
    }
    add(points.last())
}

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (index in 0 until points.lastIndex) {
        val (control1, control2) = controlPoints(points, index)
        cubicTo(control1.x, control1.y, control2.x, control2.y, points[index + 1].x, points[index + 1].y)
    }
}

private fun segmentPath(points: List<Offset>, index: Int): Path = Path().apply {
    val (control1, control2) = controlPoints(points, index)
    moveTo(points[index].x, points[index].y)
    cubicTo(control1.x, control1.y, control2.x, control2.y, points[index + 1].x, points[index + 1].y)
}

private fun DrawScope.drawGroundPatches() {
    val patches = listOf(
        Offset(size.width * 0.12f, size.height * 0.12f),
        Offset(size.width * 0.44f, size.height * 0.27f),
        Offset(size.width * 0.76f, size.height * 0.18f),
        Offset(size.width * 0.2f, size.height * 0.46f),
        Offset(size.width * 0.7f, size.height * 0.55f),
        Offset(size.width * 0.36f, size.height * 0.71f),
        Offset(size.width * 0.84f, size.height * 0.8f),
        Offset(size.width * 0.18f, size.height * 0.9f)
    )
    patches.forEachIndexed { index, center ->
        drawCircle(
            color = if (index % 2 == 0) Color(0xFF315F3A).copy(alpha = 0.35f)
            else Color(0xFF5A8A45).copy(alpha = 0.22f),
            radius = size.minDimension * (0.09f + index * 0.012f),
            center = center
        )
    }
    repeat(60) { index ->
        val x = size.width * (0.04f + (index * 0.618f % 1f) * 0.92f)
        val y = size.height * (0.02f + (index * 0.37f % 1f) * 0.96f)
        drawLine(
            color = Color(0xFF6B9B52).copy(alpha = 0.35f),
            start = Offset(x, y),
            end = Offset(x + 3.dp.toPx(), y - 5.dp.toPx()),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawMountain(normalized: Offset, width: Float, height: Float) {
    val center = Offset(normalized.x * width, normalized.y * height)
    val base = width * 0.085f
    val peak = base * 0.72f
    val path = Path().apply {
        moveTo(center.x - base, center.y)
        lineTo(center.x - base * 0.18f, center.y - peak)
        lineTo(center.x + base * 0.08f, center.y - peak * 0.78f)
        lineTo(center.x + base, center.y)
        close()
    }
    drawPath(path, MOUNTAIN)
    val snow = Path().apply {
        moveTo(center.x - base * 0.34f, center.y - peak * 0.68f)
        lineTo(center.x - base * 0.18f, center.y - peak)
        lineTo(center.x + base * 0.08f, center.y - peak * 0.78f)
        lineTo(center.x + base * 0.02f, center.y - peak * 0.68f)
        close()
    }
    drawPath(snow, SNOW)
    drawLine(MOUNTAIN_DARK, Offset(center.x - base, center.y), Offset(center.x, center.y), 2.dp.toPx())
}

private fun DrawScope.drawRiver(path: Path, flow: Float) {
    drawPath(path, RIVER_DARK, style = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, RIVER, style = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round))
    val measure = PathMeasure().apply { setPath(path, false) }
    val length = measure.length
    if (length <= 0f) return
    val spacing = 54.dp.toPx()
    val drift = flow * spacing
    var distance = 0f
    var index = 0
    while (distance < length) {
        val at = (distance + drift) % length
        val position = measure.getPosition(at)
        val tangent = measure.getTangent(at)
        val normal = Offset(-tangent.y, tangent.x)
        val side = if (index % 2 == 0) 1f else -1f
        repeat(2) { lane ->
            val center = position + normal * (side * (4f + lane * 4f) * 1.dp.toPx())
            val start = center - tangent * 6.dp.toPx()
            val end = center + tangent * 6.dp.toPx()
            val control = center + normal * (side * 3.dp.toPx())
            drawPath(
                Path().apply {
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, end.x, end.y)
                },
                color = RIVER_LIGHT.copy(alpha = 0.4f - lane * 0.18f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        distance += spacing
        index++
    }
}

private fun DrawScope.drawLake(center: Offset) {
    val radius = size.minDimension * 0.12f
    drawOval(
        color = RIVER_DARK.copy(alpha = 0.55f),
        topLeft = Offset(center.x - radius * 1.35f, center.y - radius * 0.75f),
        size = androidx.compose.ui.geometry.Size(radius * 2.7f, radius * 1.5f)
    )
    drawOval(
        color = RIVER,
        topLeft = Offset(center.x - radius * 1.2f, center.y - radius * 0.65f),
        size = androidx.compose.ui.geometry.Size(radius * 2.4f, radius * 1.3f)
    )
    drawOval(
        color = RIVER_LIGHT.copy(alpha = 0.5f),
        topLeft = Offset(center.x - radius * 0.8f, center.y - radius * 0.45f),
        size = androidx.compose.ui.geometry.Size(radius * 1.2f, radius * 0.45f)
    )
}

private fun DrawScope.drawTree(center: Offset, scale: Float) {
    val trunk = 4.dp.toPx() * scale
    drawLine(
        color = PATH_DARK,
        start = Offset(center.x, center.y + 7.dp.toPx()),
        end = Offset(center.x, center.y - 2.dp.toPx()),
        strokeWidth = trunk,
        cap = StrokeCap.Round
    )
    drawCircle(TREE_DARK, 9.dp.toPx() * scale, Offset(center.x, center.y - 6.dp.toPx()))
    drawCircle(TREE, 7.dp.toPx() * scale, Offset(center.x - 3.dp.toPx(), center.y - 9.dp.toPx()))
    drawCircle(TREE_LIGHT, 5.dp.toPx() * scale, Offset(center.x + 3.dp.toPx(), center.y - 11.dp.toPx()))
}

private fun DrawScope.drawRock(center: Offset) {
    val path = Path().apply {
        moveTo(center.x - 7.dp.toPx(), center.y + 4.dp.toPx())
        lineTo(center.x - 4.dp.toPx(), center.y - 4.dp.toPx())
        lineTo(center.x + 4.dp.toPx(), center.y - 5.dp.toPx())
        lineTo(center.x + 8.dp.toPx(), center.y + 3.dp.toPx())
        close()
    }
    drawPath(path, STONE)
    drawLine(STONE_LIGHT, Offset(center.x - 3.dp.toPx(), center.y - 2.dp.toPx()), Offset(center.x + 2.dp.toPx(), center.y - 3.dp.toPx()), 1.5.dp.toPx())
}

private fun DrawScope.drawHouse(center: Offset) {
    val w = 16.dp.toPx()
    val h = 12.dp.toPx()
    drawRect(WOOD, Offset(center.x - w / 2, center.y), androidx.compose.ui.geometry.Size(w, h))
    val roof = Path().apply {
        moveTo(center.x - w * 0.65f, center.y)
        lineTo(center.x, center.y - h * 0.9f)
        lineTo(center.x + w * 0.65f, center.y)
        close()
    }
    drawPath(roof, Color(0xFF8E3F32))
    drawRect(WOOD_LIGHT, Offset(center.x - 2.dp.toPx(), center.y + 4.dp.toPx()), androidx.compose.ui.geometry.Size(4.dp.toPx(), 6.dp.toPx()))
}

private fun DrawScope.drawCastle(center: Offset) {
    val unit = 1.dp.toPx()
    val baseW = 34 * unit
    val baseH = 26 * unit
    drawRect(CASTLE_DARK, Offset(center.x - baseW / 2, center.y), androidx.compose.ui.geometry.Size(baseW, baseH))
    drawRect(CASTLE_STONE, Offset(center.x - baseW / 2, center.y), androidx.compose.ui.geometry.Size(baseW, baseH * 0.85f))
    listOf(-13f, 13f).forEach { dx ->
        val towerX = center.x + dx * unit
        drawRect(CASTLE_DARK, Offset(towerX - 7 * unit, center.y - 12 * unit), androidx.compose.ui.geometry.Size(14 * unit, 38 * unit))
        drawRect(CASTLE_STONE, Offset(towerX - 7 * unit, center.y - 12 * unit), androidx.compose.ui.geometry.Size(14 * unit, 36 * unit))
        val roof = Path().apply {
            moveTo(towerX - 9 * unit, center.y - 12 * unit)
            lineTo(towerX, center.y - 23 * unit)
            lineTo(towerX + 9 * unit, center.y - 12 * unit)
            close()
        }
        drawPath(roof, Color(0xFF8E3F32))
    }
    drawRect(Color(0xFF4C3B2D), Offset(center.x - 5 * unit, center.y + 10 * unit), androidx.compose.ui.geometry.Size(10 * unit, 16 * unit))
    listOf(-13f, 13f).forEach { dx ->
        drawLine(Color(0xFFFFD166), Offset(center.x + dx * unit, center.y - 23 * unit), Offset(center.x + dx * unit, center.y - 32 * unit), 1.5f * unit)
        val flag = Path().apply {
            moveTo(center.x + dx * unit, center.y - 32 * unit)
            lineTo(center.x + (dx + 8) * unit, center.y - 29 * unit)
            lineTo(center.x + dx * unit, center.y - 26 * unit)
            close()
        }
        drawPath(flag, AdventureGold)
    }
}

private fun DrawScope.drawPath(path: Path) {
    drawPath(path, PATH_DARK, style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, PATH, style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
    drawPath(
        path,
        PATH_LIGHT.copy(alpha = 0.8f),
        style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f))
        )
    )
}

private fun DrawScope.drawBridge(center: Offset, angle: Float) {
    rotate(angle, pivot = center) {
        val width = 62.dp.toPx()
        val height = 18.dp.toPx()
        drawRoundRect(
            color = WOOD,
            topLeft = Offset(center.x - width / 2, center.y - height / 2),
            size = androidx.compose.ui.geometry.Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )
        repeat(6) { index ->
            val x = center.x - width / 2 + (index + 1) * width / 7
            drawLine(WOOD_LIGHT, Offset(x, center.y - height / 2), Offset(x, center.y + height / 2), 1.5.dp.toPx())
        }
        drawLine(PATH_DARK, Offset(center.x - width / 2, center.y - height / 2), Offset(center.x - width / 2, center.y + height / 2), 2.dp.toPx())
        drawLine(PATH_DARK, Offset(center.x + width / 2, center.y - height / 2), Offset(center.x + width / 2, center.y + height / 2), 2.dp.toPx())
    }
}

@Composable
private fun FieldBadge(
    index: Int,
    reward: AdventureReward?,
    reached: Boolean,
    active: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    if (index == 0) {
        CampBadge(badgeSize = size, modifier = modifier)
        return
    }
    val pulse = if (active) {
        val transition = rememberInfiniteTransition(label = "field_pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "field_pulse_value"
        )
        value
    } else {
        1f
    }
    val base = when (reward) {
        is AdventureReward.Stickers -> FIELD_STICKERS
        AdventureReward.Song -> FIELD_SONG
        AdventureReward.GoAgain -> FIELD_GO_AGAIN
        AdventureReward.Whack -> FIELD_WHACK
        null -> FIELD_START
    }
    val icon = when (reward) {
        is AdventureReward.Stickers -> reward.stickers.firstOrNull()?.emoji
        AdventureReward.Song -> "🎵"
        AdventureReward.GoAgain -> "🎲"
        AdventureReward.Whack -> "🎯"
        null -> null
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = if (active) pulse else 1f
                scaleY = if (active) pulse else 1f
            }
            .clip(CircleShape)
            .background(base.copy(alpha = if (reached) 0.95f else 0.72f))
            .border(
                width = if (active) 2.5.dp else 1.dp,
                color = if (active) StorybookGold else Color.White.copy(alpha = 0.42f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = (size.value * 0.25f).sp,
                lineHeight = (size.value * 0.27f).sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            if (icon != null) {
                Text(
                    text = icon,
                    fontSize = (size.value * 0.46f).sp,
                    lineHeight = (size.value * 0.5f).sp
                )
            }
        }
    }
}

@Composable
private fun CampBadge(badgeSize: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(badgeSize)) {
        val w = size.width
        val h = size.height
        val fabric = Color(0xFFF2D9A6)
        val shade = Color(0xFFC9A267)
        val door = Color(0xFF3A2A20)

        fun tent(cx: Float, baseY: Float, tw: Float, th: Float) {
            val left = cx - tw / 2
            val right = cx + tw / 2
            val top = baseY - th
            drawPath(Path().apply {
                moveTo(cx, top)
                lineTo(left, baseY)
                lineTo(right, baseY)
                close()
            }, fabric)
            drawPath(Path().apply {
                moveTo(cx, top)
                lineTo(left, baseY)
                lineTo(cx, baseY)
                close()
            }, shade)
            drawPath(Path().apply {
                moveTo(cx, baseY - th * 0.5f)
                lineTo(cx - tw * 0.14f, baseY)
                lineTo(cx + tw * 0.14f, baseY)
                close()
            }, door)
        }

        drawOval(
            color = Color(0xFF2E4628),
            topLeft = Offset(w * 0.06f, h * 0.7f),
            size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.24f)
        )
        tent(w * 0.34f, h * 0.78f, w * 0.42f, h * 0.4f)
        tent(w * 0.7f, h * 0.74f, w * 0.34f, h * 0.32f)

        val fx = w * 0.52f
        val fy = h * 0.88f
        drawCircle(Color(0xFF5A3B22), w * 0.13f, Offset(fx, fy))
        drawPath(Path().apply {
            moveTo(fx, fy - h * 0.2f)
            quadraticTo(fx + w * 0.1f, fy - h * 0.04f, fx, fy - h * 0.01f)
            quadraticTo(fx - w * 0.1f, fy - h * 0.04f, fx, fy - h * 0.2f)
        }, Color(0xFFFFA53D))
        drawCircle(Color(0xFFFFE08A), w * 0.035f, Offset(fx, fy - h * 0.09f))
    }
}

@Composable
private fun AnimatedPlayerToken(
    player: AdventurePlayer,
    current: Boolean,
    startAt: Float,
    walkMs: Long,
    points: List<Offset>,
    segmentMeasures: List<PathMeasure>,
    tokenSize: Dp,
    offsetX: Dp,
    onPositioned: ((Offset) -> Unit)? = null
) {
    val animated = remember(player.profileId) { Animatable(startAt) }
    LaunchedEffect(player.position) {
        animated.animateTo(
            player.position.toFloat(),
            tween(walkMs.toInt(), easing = FastOutSlowInEasing)
        )
    }
    val value = animated.value.coerceIn(0f, points.lastIndex.toFloat())
    val segment = value.toInt().coerceIn(0, segmentMeasures.lastIndex)
    val fraction = (value - segment).coerceIn(0f, 1f)
    val measure = segmentMeasures[segment]
    val position = when {
        fraction <= 0.001f -> points[segment]
        fraction >= 0.999f -> points[segment + 1]
        else -> measure.getPosition(measure.length * fraction)
    }
    val density = LocalDensity.current
    val x = with(density) { (position.x - tokenSize.toPx() / 2).toDp() } + offsetX
    val y = with(density) { (position.y - tokenSize.toPx()).toDp() }
    val color = ADVENTURE_PLAYER_COLORS[player.colorIndex % ADVENTURE_PLAYER_COLORS.size]
    val transition = rememberInfiniteTransition(label = "token_bob")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (current) -5f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "token_bob_value"
    )
    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(tokenSize)
            .zIndex(if (current) 3f else 2f)
            .graphicsLayer { translationY = bob * density.density }
            .onGloballyPositioned { coordinates ->
                onPositioned?.invoke(coordinates.boundsInRoot().center)
            }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
            .border(2.dp, if (current) StorybookGold else Color.White.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(player.emoji, fontSize = (tokenSize.value * 0.5f).sp)
    }
}
