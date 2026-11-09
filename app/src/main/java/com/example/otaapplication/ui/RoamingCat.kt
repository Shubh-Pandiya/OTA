package com.example.otaapplication.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** What the Home cat is doing. */
enum class CatPose { WALK, STAND, SIT, SLEEP }

/** Everything the cat's drawing reads; only [pose], [facingRight] and [bubble] cause recomposition. */
@Stable
class CatState {
    internal val random = Random(System.nanoTime())
    var x by mutableFloatStateOf(Float.NaN)
    var facingRight by mutableStateOf(true)
    var pose by mutableStateOf(CatPose.SIT)
    /** Walk cycle, in radians. */
    var stride by mutableFloatStateOf(0f)
    /** Seconds of "life", drives the tail, breathing and the sleep Zs. */
    var clock by mutableFloatStateOf(0f)
    /** 0 = eyes open, 1 = shut. */
    var blink by mutableFloatStateOf(0f)
    /** Height of the current jump, in px. */
    var hop by mutableFloatStateOf(0f)
    var bubble by mutableStateOf<String?>(null)
    internal var woken = false
}

// Drawing units: the cat lives in a 64 x 44 box standing on y = 44; above it is room to jump.
private const val UNITS_W = 64f
private const val UNITS_CAT_H = 44f
private const val UNITS_JUMP = 24f
private val BODY = Color(0xFFF2A65A)
private val BODY_FAR = Color(0xFFE39550)
private val STRIPE = Color(0xFFD27B32)
private val CREAM = Color(0xFFFFEBD3)
private val EYE = Color(0xFF3A2A1E)
private val PINK = Color(0xFFF08A8A)
private val WHISKER = Color(0x993A2A1E)

/**
 * A small ginger cat that wanders along the bottom of its area: it walks, stands, sits and now
 * and then naps. Tapping it makes it jump and meow. Only the cat itself takes touches, so the
 * screen under it keeps working.
 *
 * [active] should be false while the screen is in the background; the cat then stops animating
 * entirely. With [reduceMotion] it just sits still (and still answers a tap with a meow).
 */
@Composable
fun RoamingCat(
    active: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    floor: Dp = 0.dp,
    catWidth: Dp = 60.dp,
    state: CatState = remember { CatState() }
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val catHeight = catWidth * (UNITS_CAT_H / UNITS_W)
    val boxHeight = catWidth * ((UNITS_CAT_H + UNITS_JUMP) / UNITS_W)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - catWidth).toPx() }.coerceAtLeast(0f)
        val speed = with(density) { 44.dp.toPx() }
        val jumpPx = with(density) { (catWidth * (UNITS_JUMP / UNITS_W)).toPx() * 0.9f }
        if (state.x.isNaN() || state.x > maxX) state.x = maxX * 0.72f

        LaunchedEffect(active, reduceMotion, maxX) {
            if (!active || reduceMotion) {
                if (state.pose == CatPose.WALK) state.pose = CatPose.SIT
                return@LaunchedEffect
            }
            while (isActive) {
                val r = state.random
                when (r.nextInt(100)) {
                    in 0..49 -> {
                        // Pick a spot at least a quarter of the way across, so walks look purposeful.
                        var target = r.nextFloat() * maxX
                        if (abs(target - state.x) < maxX / 4) target = if (state.x > maxX / 2) state.x - maxX / 3 else state.x + maxX / 3
                        walkTo(state, target.coerceIn(0f, maxX), speed)
                    }
                    in 50..67 -> rest(state, CatPose.STAND, 1.5f + r.nextFloat() * 2.5f)
                    in 68..89 -> rest(state, CatPose.SIT, 4f + r.nextFloat() * 5f)
                    else -> rest(state, CatPose.SLEEP, 9f + r.nextFloat() * 8f)
                }
            }
        }

        var reaction by remember { mutableStateOf<Job?>(null) }
        fun poke() {
            if (reaction?.isActive == true) return
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            reaction = scope.launch {
                val wasAsleep = state.pose == CatPose.SLEEP
                state.woken = true
                state.bubble = if (wasAsleep) "Mrrp?" else listOf("Meow!", "Meow!", "Mew!", "Prrr…").random(state.random)
                if (!reduceMotion) {
                    if (wasAsleep) state.pose = CatPose.SIT
                    jump(state, jumpPx)
                }
                delay(1100)
                state.bubble = null
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = floor)
                .offset { IntOffset(state.x.roundToInt(), 0) }
                .size(catWidth, boxHeight + 30.dp)
        ) {
            Canvas(Modifier.align(Alignment.BottomCenter).size(catWidth, boxHeight)) { drawCat(state) }
            // The touch target is only the cat's body, not the empty space above it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(catWidth, catHeight)
                    .semantics { contentDescription = "Cat"; onClick { poke(); true } }
                    .pointerInput(Unit) { detectTapGestures { poke() } }
            )
            AnimatedVisibility(
                visible = state.bubble != null,
                enter = fadeIn(tween(120)) + scaleIn(tween(180), initialScale = 0.6f),
                exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.8f),
                modifier = Modifier.align(Alignment.TopCenter).offset { IntOffset(0, -state.hop.roundToInt() / 2) }
            ) {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 3.dp, tonalElevation = 0.dp) {
                    Text(
                        state.bubble.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8A4B12),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

private suspend fun walkTo(state: CatState, target: Float, speed: Float) {
    state.pose = CatPose.WALK
    state.facingRight = target >= state.x
    var last = androidx.compose.runtime.withFrameNanos { it }
    while (abs(target - state.x) > 0.5f) {
        val now = androidx.compose.runtime.withFrameNanos { it }
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.05f)
        last = now
        val left = target - state.x
        state.x += sign(left) * min(abs(left), speed * dt)
        state.stride += dt * 8.5f
        state.clock += dt
    }
    state.stride = 0f
}

/** Holds [pose] for about [seconds], ticking gently (25 fps is plenty for a tail and a blink). */
private suspend fun rest(state: CatState, pose: CatPose, seconds: Float) {
    state.pose = pose
    state.woken = false
    var left = seconds
    var nextBlink = 1.5f + state.random.nextFloat() * 2.5f
    while (left > 0 && !state.woken) {
        delay(40)
        left -= 0.04f
        state.clock += 0.04f
        nextBlink -= 0.04f
        state.blink = when {
            nextBlink > 0.16f -> 0f
            nextBlink > 0f -> 1f
            else -> { nextBlink = 2f + state.random.nextFloat() * 3f; 0f }
        }
    }
    state.blink = 0f
    // A woken cat sits up for a moment before going on.
    if (state.woken) {
        state.woken = false
        if (state.pose == CatPose.SLEEP) state.pose = CatPose.SIT
        delay(1200)
    }
}

private suspend fun jump(state: CatState, height: Float) {
    val start = androidx.compose.runtime.withFrameNanos { it }
    while (true) {
        val t = (androidx.compose.runtime.withFrameNanos { it } - start) / 1e9f / 0.5f
        if (t >= 1f) break
        state.hop = (4 * t * (1 - t)) * height
    }
    state.hop = 0f
}

private fun DrawScope.drawCat(s: CatState) {
    val u = size.width / UNITS_W
    val ground = UNITS_CAT_H + UNITS_JUMP
    val hopUnits = s.hop / u
    // Shadow stays on the ground and shrinks as the cat jumps.
    val shadowW = 40f * (1f - min(hopUnits / UNITS_JUMP, 1f) * 0.45f)
    drawOval(
        Color.Black.copy(alpha = 0.10f),
        topLeft = Offset((32f - shadowW / 2) * u, (ground - 3f) * u),
        size = Size(shadowW * u, 5f * u)
    )
    translate(top = (UNITS_JUMP - hopUnits) * u) {
        scale(if (s.facingRight) 1f else -1f, 1f, pivot = Offset(size.width / 2, 0f)) {
            scale(u, u, pivot = Offset.Zero) {
                when (s.pose) {
                    CatPose.WALK, CatPose.STAND -> standing(s, walking = s.pose == CatPose.WALK)
                    CatPose.SIT -> sitting(s)
                    CatPose.SLEEP -> sleeping(s)
                }
            }
        }
    }
}

private fun DrawScope.leg(x: Float, top: Float, footX: Float, footY: Float, color: Color) {
    drawLine(color, Offset(x, top), Offset(footX, footY), strokeWidth = 4.4f, cap = StrokeCap.Round)
}

private fun DrawScope.standing(s: CatState, walking: Boolean) {
    val bob = if (walking) sin(s.stride * 2) * 0.7f else 0f
    val legTop = 31f + bob
    fun foot(x: Float, phase: Float): Offset {
        if (!walking) return Offset(x, 42.5f)
        val a = s.stride + phase
        return Offset(x + sin(a) * 3.4f, 42.5f - max(0f, -cos(a)) * 2.6f)
    }
    val pi = PI.toFloat()
    // Far legs first, a shade darker, then the body, then the near legs.
    foot(21f, pi).let { leg(21f, legTop, it.x, it.y, BODY_FAR) }
    foot(41f, 0f).let { leg(41f, legTop, it.x, it.y, BODY_FAR) }
    tail(Offset(13f, 25f + bob), s.clock * if (walking) 3.2f else 1.8f, up = true)
    drawOval(BODY, Offset(11f, 18.5f + bob), Size(37f, 17.5f))
    drawOval(CREAM, Offset(22f, 28.5f + bob), Size(20f, 7f))
    for (x in floatArrayOf(22f, 28f, 34f)) {
        drawLine(STRIPE, Offset(x, 19.8f + bob), Offset(x - 1.6f, 24.5f + bob), strokeWidth = 2.2f, cap = StrokeCap.Round)
    }
    foot(25f, 0f).let { leg(25f, legTop, it.x, it.y, BODY) }
    foot(45f, pi).let { leg(45f, legTop, it.x, it.y, BODY) }
    head(Offset(50f, 16.5f + bob), s.blink, asleep = false)
}

private fun DrawScope.sitting(s: CatState) {
    tailCurl(s.clock)
    drawOval(BODY, Offset(13f, 25f), Size(27f, 19f))
    drawLine(STRIPE, Offset(19f, 28f), Offset(17.5f, 33f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawLine(STRIPE, Offset(24f, 26.5f), Offset(23f, 31.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    drawOval(BODY, Offset(29f, 17f), Size(17f, 25f))
    drawOval(CREAM, Offset(34f, 21f), Size(9f, 14f))
    leg(36.5f, 33f, 36.5f, 42.5f, BODY_FAR)
    leg(41.5f, 33f, 41.5f, 42.5f, BODY)
    head(Offset(40f, 12.5f), s.blink, asleep = false)
}

private fun DrawScope.sleeping(s: CatState) {
    val breathe = 1f + sin(s.clock * 1.7f) * 0.035f
    scale(1f, breathe, pivot = Offset(32f, 44f)) {
        drawOval(BODY, Offset(9f, 28f), Size(42f, 16f))
        for (x in floatArrayOf(18f, 24f, 30f)) {
            drawLine(STRIPE, Offset(x, 29.5f), Offset(x - 1.2f, 33.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
    }
    // Tail wrapped round the front paws.
    val tail = Path().apply { moveTo(12f, 40f); cubicTo(18f, 46.5f, 42f, 46.5f, 50f, 41.5f) }
    drawPath(tail, BODY_FAR, style = Stroke(width = 4.6f, cap = StrokeCap.Round))
    head(Offset(48f, 34.5f), 1f, asleep = true)
    // Three Zs drifting up and fading.
    for (i in 0 until 3) {
        val t = ((s.clock * 0.45f) + i / 3f) % 1f
        val zx = 53f + t * 7f
        val zy = 26f - t * 20f
        val zs = 2.2f + t * 2.6f
        val z = Path().apply {
            moveTo(zx, zy); lineTo(zx + zs, zy); lineTo(zx, zy + zs); lineTo(zx + zs, zy + zs)
        }
        drawPath(z, Color(0xFF8A6A50).copy(alpha = (1f - t) * 0.85f), style = Stroke(width = 1.3f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.tail(base: Offset, t: Float, up: Boolean) {
    val sway = sin(t)
    val tip = Offset(base.x - 7f + sway * 4f, base.y - (if (up) 17f else 6f) + cos(t) * 1.5f)
    val path = Path().apply {
        moveTo(base.x, base.y)
        cubicTo(base.x - 7f, base.y - 1f, base.x - 9f + sway * 2f, base.y - 9f, tip.x, tip.y)
    }
    drawPath(path, BODY, style = Stroke(width = 4.4f, cap = StrokeCap.Round))
    drawCircle(STRIPE, radius = 2.2f, center = tip)
}

private fun DrawScope.tailCurl(t: Float) {
    val flick = sin(t * 1.6f) * 2.2f
    val path = Path().apply {
        moveTo(20f, 42f)
        cubicTo(10f, 44.5f, 3f, 41f, 4f + flick * 0.4f, 34f)
        cubicTo(4.5f, 31f, 6.5f + flick, 29.5f, 8.5f + flick, 30.5f)
    }
    drawPath(path, BODY, style = Stroke(width = 4.4f, cap = StrokeCap.Round))
    drawCircle(STRIPE, radius = 2.2f, center = Offset(8.5f + flick, 30.5f))
}

private fun DrawScope.head(c: Offset, blink: Float, asleep: Boolean) {
    // Ears.
    val backEar = Path().apply { moveTo(c.x - 8f, c.y - 4f); lineTo(c.x - 6f, c.y - 14f); lineTo(c.x - 0.5f, c.y - 8f); close() }
    val frontEar = Path().apply { moveTo(c.x + 0.5f, c.y - 8.5f); lineTo(c.x + 6.5f, c.y - 14.5f); lineTo(c.x + 8.8f, c.y - 5f); close() }
    drawPath(backEar, BODY_FAR)
    drawPath(frontEar, BODY)
    drawPath(Path().apply { moveTo(c.x + 2.2f, c.y - 8.6f); lineTo(c.x + 6.2f, c.y - 12.4f); lineTo(c.x + 7.4f, c.y - 6.6f); close() }, PINK)
    drawCircle(BODY, radius = 9.5f, center = c)
    drawLine(STRIPE, Offset(c.x - 1.5f, c.y - 9f), Offset(c.x - 1f, c.y - 5.5f), strokeWidth = 1.6f, cap = StrokeCap.Round)
    drawLine(STRIPE, Offset(c.x + 1.8f, c.y - 9.2f), Offset(c.x + 1.8f, c.y - 6f), strokeWidth = 1.6f, cap = StrokeCap.Round)
    drawOval(CREAM, Offset(c.x + 1.5f, c.y + 0.5f), Size(9.5f, 6.5f))
    // Eyes: open ovals, a blink squashes them, sleep draws closed arcs.
    for (ex in floatArrayOf(c.x - 0.5f, c.x + 5.5f)) {
        if (asleep || blink > 0.5f) {
            val lid = Path().apply { moveTo(ex - 1.6f, c.y - 1.5f); quadraticTo(ex, c.y + 0.3f, ex + 1.6f, c.y - 1.5f) }
            drawPath(lid, EYE, style = Stroke(width = 1.1f, cap = StrokeCap.Round))
        } else {
            drawOval(EYE, Offset(ex - 1.2f, c.y - 3.4f), Size(2.4f, 3.6f))
            drawCircle(Color.White, radius = 0.5f, center = Offset(ex + 0.3f, c.y - 2.6f))
        }
    }
    drawPath(Path().apply { moveTo(c.x + 8.3f, c.y + 1.6f); lineTo(c.x + 10.6f, c.y + 1.6f); lineTo(c.x + 9.5f, c.y + 3.1f); close() }, PINK)
    drawLine(WHISKER, Offset(c.x + 8f, c.y + 3.6f), Offset(c.x + 14.5f, c.y + 2f), strokeWidth = 0.55f)
    drawLine(WHISKER, Offset(c.x + 8f, c.y + 4.6f), Offset(c.x + 14.5f, c.y + 5.2f), strokeWidth = 0.55f)
}
