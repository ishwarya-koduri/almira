package tech.bhrigu.almira.shared.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.bhrigu.almira.shared.theme.AlmiraTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The mark's own colours — the same hexes as `brand/almira-mark.svg`, not the
 * app palette. The animation is the logo, so it is drawn in the logo's colours
 * whether the app is light or dark.
 */
private object Mark {
    val Ground = Color(0xFF123F3A)
    val Cream = Color(0xFFFAF6F0)
    val Gold = Color(0xFFE0BC7E)

    /** Inside the almira: what the doors were hiding. */
    val Depth = Color(0xFF06201D)
    /** A door's face once light from inside reaches it. */
    val DoorLit = Color(0xFF1C5750)
    val Light = Color(0xFFFFE6B0)
    val Refusal = Color(0xFFE5775A)
}

/**
 * The Almira mark, drawn rather than rasterised, so it is sharp at any size and
 * the sign-in header is the same drawing the unlock animation opens.
 */
@Composable
fun AlmiraMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(Mark.Ground, cornerRadius = CornerRadius(size.minDimension * 0.24f))
        inUnits {
            drawFrame()
            drawDoor(left = true, open = 0f, hollow = 0f, rim = Mark.Gold)
            drawDoor(left = false, open = 0f, hollow = 0f, rim = Mark.Gold)
        }
    }
}

/**
 * The key goes into the lock while the code is checked, and the lock answers.
 *
 * Right: the key turns, clicks, dissolves into sparks, the doors swing open on
 * a warm light and the view moves in through them — and fades out onto
 * whatever was signed in to underneath. Wrong: the key will not turn, the
 * almira rattles, and the key is taken back out. Neither is a delay the person
 * waits through for its own sake: the key goes in while the server is thinking,
 * and the outcome is under two and a half seconds.
 *
 * Covers the whole screen and swallows touches while it plays, so nothing
 * underneath can be tapped mid-animation.
 */
@Composable
fun VaultUnlock(
    phase: UnlockPhase,
    /** The server's sentence for a refusal; shown under the headline. */
    message: String?,
    onFinished: () -> Unit,
) {
    val currentPhase by rememberUpdatedState(phase)
    val currentMessage by rememberUpdatedState(message)
    val finished by rememberUpdatedState(onFinished)
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current

    val reveal = remember { Animatable(0f) }
    val approach = remember { Animatable(0f) }
    val withdraw = remember { Animatable(0f) }
    val turn = remember { Animatable(1f) } // the key's width: 1 flat on, ~0 edge on
    val tilt = remember { Animatable(0f) }
    val keyFade = remember { Animatable(1f) }
    val click = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    val refusal = remember { Animatable(0f) }
    val open = remember { Animatable(0f) }
    val motes = remember { Animatable(0f) }
    val push = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    val rattle = remember { Animatable(0f) }

    var caption by remember { mutableStateOf(Caption("Turning the key…", null)) }

    val breathing = rememberInfiniteTransition(label = "keyBreath")
    val breath by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )

    LaunchedEffect(Unit) {
        // The keyboard would cover half the almira, and there is nothing to type.
        keyboard?.hide()
        coroutineScope {
            launch { reveal.animateTo(1f, tween(260, easing = LinearOutSlowInEasing)) }
            delay(110)
            approach.animateTo(1f, tween(820, easing = LinearEasing))
        }
        // Seated. The key waits in the lock for as long as the server takes.
        val outcome = snapshotFlow { currentPhase }.first { it != UnlockPhase.Checking && it != UnlockPhase.Idle }

        when (outcome) {
            UnlockPhase.Opened -> coroutineScope {
                launch { turn.animateTo(0.12f, tween(420, easing = FastOutSlowInEasing)) }
                delay(330)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                caption = Caption("Unlocked", "Welcome to your almira.")
                launch { click.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
                launch {
                    flash.animateTo(1f, tween(90))
                    flash.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
                }
                launch { keyFade.animateTo(0f, tween(260, delayMillis = 60)) }
                launch { motes.animateTo(1f, tween(2200, easing = LinearEasing)) }
                delay(110)
                // A little past fully open and back, like a heavy door on good hinges.
                open.animateTo(1f, tween(1000, easing = CubicBezierEasing(0.22f, 0.9f, 0.3f, 1.06f)))
                delay(180)
                launch { push.animateTo(1f, tween(760, easing = FastOutLinearInEasing)) }
                delay(430)
                reveal.animateTo(0f, tween(360, easing = FastOutLinearInEasing))
            }

            UnlockPhase.Refused -> coroutineScope {
                caption = Caption("That key doesn't fit", currentMessage ?: "Check the code and try again.")
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                launch {
                    // It starts to turn, catches, and will not go.
                    turn.animateTo(1f, keyframes {
                        durationMillis = 720
                        0.66f at 110 using FastOutSlowInEasing
                        0.9f at 190
                        0.6f at 300 using FastOutSlowInEasing
                        0.86f at 380
                        0.72f at 460
                        1f at 720 using FastOutSlowInEasing
                    })
                }
                launch {
                    tilt.animateTo(0f, keyframes {
                        durationMillis = 620
                        -6f at 110
                        5f at 220
                        -4f at 330
                        2f at 440
                    })
                }
                launch {
                    refusal.animateTo(1f, tween(120))
                    delay(260)
                    refusal.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
                }
                launch {
                    shake.animateTo(0f, keyframes {
                        durationMillis = 560
                        -10f at 70
                        9f at 150
                        -7f at 230
                        5f at 310
                        -3f at 390
                        1.5f at 470
                    })
                }
                launch {
                    rattle.animateTo(0f, keyframes {
                        durationMillis = 560
                        5f at 90
                        -4f at 180
                        3f at 270
                        -2f at 360
                        1f at 450
                    })
                }
                launch {
                    delay(300)
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
                delay(760)
                withdraw.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                delay(900)
                reveal.animateTo(0f, tween(300, easing = FastOutLinearInEasing))
            }

            else -> {
                caption = Caption("Couldn't check the key", currentMessage)
                withdraw.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
                delay(1100)
                reveal.animateTo(0f, tween(300, easing = FastOutLinearInEasing))
            }
        }
        finished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = reveal.value }
            // Nothing underneath is reachable while the lock is deciding.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val markSize = min(maxWidth.value * 0.66f, 280f).dp
        val captionHeight = 96.dp
        val gap = 28.dp
        // The mark sits above centre by half of what is below it.
        val markLift = (gap + captionHeight) / 2

        Canvas(Modifier.fillMaxSize()) {
            drawBackdrop(
                centre = Offset(size.width / 2, size.height / 2 - markLift.toPx()),
                markPx = markSize.toPx(),
                open = open.value.coerceAtLeast(WARM),
                push = push.value,
                refusal = refusal.value,
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(markSize)
                    .graphicsLayer {
                        translationX = shake.value.dp.toPx()
                        val zoom = 1f + 5.5f * push.value * push.value
                        scaleX = zoom
                        scaleY = zoom
                        // Into the doors, not into the keyhole.
                        transformOrigin = TransformOrigin(0.5f, 0.52f)
                    },
            ) {
                val hollow = if (withdraw.value > 0f) 1f - withdraw.value else keyholeHollow(approach.value)
                val rim = lerp(lerp(Mark.Gold, Mark.Light, flash.value), Mark.Refusal, refusal.value)

                Canvas(Modifier.fillMaxSize()) {
                    inUnits {
                        drawInterior(open.value.coerceAtLeast(WARM), motes.value)
                        drawFrame()
                    }
                }
                Door(left = true, open = open.value, rattle = rattle.value + WARM, hollow = hollow, rim = rim)
                Door(left = false, open = open.value, rattle = rattle.value + WARM, hollow = hollow, rim = rim)
                Canvas(Modifier.fillMaxSize()) {
                    inUnits {
                        val waiting = approach.value >= 1f && click.value == 0f && refusal.value == 0f &&
                            withdraw.value == 0f
                        drawKeyholeHalo(
                            waiting = if (waiting) 0.55f + 0.45f * breath else 0f,
                            flash = flash.value,
                            refusal = refusal.value,
                        )
                        drawKey(
                            approach = approach.value,
                            withdraw = withdraw.value,
                            turn = turn.value,
                            tilt = tilt.value,
                            alpha = keyFade.value,
                        )
                        drawClick(click.value)
                        drawMotes(motes.value.coerceAtLeast(WARM))
                    }
                }
            }
            Spacer(Modifier.height(gap))
            AnimatedContent(
                targetState = caption,
                transitionSpec = { fadeIn(tween(320, delayMillis = 80)) togetherWith fadeOut(tween(160)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(captionHeight)
                    .padding(horizontal = AlmiraTheme.spacing.x8)
                    .graphicsLayer { alpha = 1f - push.value }
                    .semantics { liveRegion = LiveRegionMode.Polite },
                label = "unlockCaption",
            ) { shown ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AlmiraTheme.spacing.x2),
                ) {
                    Text(
                        shown.title,
                        style = AlmiraTheme.typography.h3,
                        color = Mark.Cream,
                        textAlign = TextAlign.Center,
                    )
                    shown.detail?.let {
                        Text(
                            it,
                            style = AlmiraTheme.typography.small,
                            color = Mark.Cream.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private data class Caption(val title: String, val detail: String?)

/**
 * One door, in its own layer so it can turn in three dimensions on its hinge —
 * the frame's left or right stile, a fifth of the way in from that edge.
 */
@Composable
private fun Door(left: Boolean, open: Float, rattle: Float, hollow: Float, rim: Color) {
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val swing = DOOR_SWING * open + rattle
                rotationY = if (left) -swing else swing
                transformOrigin = TransformOrigin(if (left) 0.2f else 0.8f, 0.5f)
                cameraDistance = 7f * density
            },
    ) {
        inUnits { drawDoor(left, open, hollow, rim) }
    }
}

/**
 * Drawn from the first frame at an invisible strength. The light, the rays and
 * the turned doors each need a shader the renderer compiles the first time it
 * sees one, and compiling them at the click froze the doors for half a second;
 * compiling them while the key waits costs nothing anyone can see.
 */
private const val WARM = 0.002f

/** Degrees. Short of 90, so the door is still a door and not its own back. */
private const val DOOR_SWING = 76f

// ---------------------------------------------------------------------------
// Drawing. Everything below is in the mark's own 100 × 100 units — the SVG's
// viewBox — so the numbers can be checked against brand/almira-mark.svg.
// ---------------------------------------------------------------------------

private inline fun DrawScope.inUnits(block: DrawScope.() -> Unit) {
    val k = size.minDimension / 100f
    withTransform({ scale(k, k, pivot = Offset.Zero) }) { block() }
}

private val Arch = Path().apply {
    moveTo(20f, 84f)
    lineTo(20f, 44f)
    cubicTo(20f, 26f, 33f, 12.5f, 50f, 12.5f)
    cubicTo(67f, 12.5f, 80f, 26f, 80f, 44f)
    lineTo(80f, 84f)
}

/** Inside the frame's inner edge: what shows once the doors move. */
private val Opening = Path().apply {
    moveTo(23.75f, 84f)
    lineTo(23.75f, 44f)
    cubicTo(23.75f, 28.2f, 35.2f, 16.25f, 50f, 16.25f)
    cubicTo(64.8f, 16.25f, 76.25f, 28.2f, 76.25f, 44f)
    lineTo(76.25f, 84f)
    close()
}

/** Each door's face, a hair inside the frame so a closed door never covers it. */
private fun doorPanel(left: Boolean) = Path().apply {
    val m = if (left) 1f else -1f
    fun x(v: Float) = 50f - m * (50f - v)
    moveTo(x(24.1f), 84f)
    lineTo(x(24.1f), 44f)
    cubicTo(x(24.1f), 28.4f, x(35.4f), 16.6f, 50f, 16.6f)
    // Past the seam by a little, so the two faces overlap instead of leaving
    // an anti-aliased hairline between them.
    lineTo(50f + m * 0.4f, 16.6f)
    lineTo(50f + m * 0.4f, 84f)
    close()
}

private val LeftPanel = doorPanel(left = true)
private val RightPanel = doorPanel(left = false)

private val Keyhole = Path().apply {
    addOval(Rect(Offset(50f, 45f), 7.6f))
    moveTo(46.2f, 50.3f)
    lineTo(44.1f, 60f)
    lineTo(55.9f, 60f)
    lineTo(53.8f, 50.3f)
    close()
}

private val KeyholeInside = Path().apply {
    addOval(Rect(Offset(50f, 45f), 5.3f))
    moveTo(47.9f, 49.6f)
    lineTo(46.4f, 57.9f)
    lineTo(53.6f, 57.9f)
    lineTo(52.1f, 49.6f)
    close()
}

/** The frame: arch, plinth, and the ends of the gold band that sit on the stiles. */
private fun DrawScope.drawFrame() {
    drawPath(Arch, Mark.Cream, style = Stroke(7.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(Mark.Cream, Offset(15f, 90f), Offset(85f, 90f), 6f, StrokeCap.Round)
    drawLine(Mark.Gold, Offset(16.25f, 70f), Offset(24.5f, 70f), 7.5f, StrokeCap.Butt)
    drawLine(Mark.Gold, Offset(75.5f, 70f), Offset(83.75f, 70f), 7.5f, StrokeCap.Butt)
}

private fun DrawScope.drawDoor(left: Boolean, open: Float, hollow: Float, rim: Color) {
    val panel = if (left) LeftPanel else RightPanel
    drawPath(panel, lerp(Mark.Ground, Mark.DoorLit, open))
    if (open > 0f) {
        // Darker towards the hinge, where the light from inside does not reach.
        drawPath(
            panel,
            Brush.horizontalGradient(
                if (left) listOf(Color.Black.copy(alpha = 0.35f * open), Color.Transparent)
                else listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f * open)),
                startX = if (left) 24f else 50f,
                endX = if (left) 50f else 76f,
            ),
        )
        // The leading edge catches it.
        val edge = if (left) 49.7f else 50.3f
        drawLine(Mark.Light.copy(alpha = 0.7f * open), Offset(edge, 17f), Offset(edge, 84f), 0.7f)
    }
    // The seam, the band and the keyhole are shared by both doors; each draws
    // its own half, overlapping the other's by a hair.
    clipRect(left = if (left) 0f else 49.6f, right = if (left) 50.4f else 100f) {
        drawLine(Mark.Cream, Offset(50f, 14f), Offset(50f, 38f), 7.5f, StrokeCap.Round)
        drawLine(Mark.Gold, Offset(if (left) 23.75f else 50f, 70f), Offset(if (left) 50f else 76.25f, 70f), 7.5f)
        drawPath(Keyhole, rim)
        if (hollow > 0f) drawPath(KeyholeInside, lerp(rim, Mark.Depth, hollow))
    }
}

/** The keyhole opens to receive the key as it arrives, not before. */
private fun keyholeHollow(approach: Float) = ((approach - 0.45f) / 0.3f).coerceIn(0f, 1f)

private fun DrawScope.drawInterior(open: Float, motes: Float) {
    if (open <= 0f) return
    clipPath(Opening) {
        drawPath(Opening, lerp(Mark.Ground, Mark.Depth, open.coerceAtMost(1f)))
        // A warm light from the back of the almira.
        drawCircle(
            Brush.radialGradient(
                listOf(
                    Mark.Light.copy(alpha = 0.95f * open),
                    Mark.Gold.copy(alpha = 0.55f * open),
                    Color.Transparent,
                ),
                center = Offset(50f, 50f),
                radius = 40f,
            ),
            radius = 60f,
            center = Offset(50f, 50f),
        )
        // Two shelves, just visible in it.
        val shelf = Mark.Ground.copy(alpha = 0.45f * open)
        drawLine(shelf, Offset(23f, 38f), Offset(77f, 38f), 1.6f)
        drawLine(shelf, Offset(23f, 58f), Offset(77f, 58f), 1.6f)
        // Light moving on the back wall.
        val drift = sin(motes * PI.toFloat() * 2f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.35f * open), Color.Transparent),
                center = Offset(46f + 6f * drift, 46f),
                radius = 16f,
            ),
            radius = 16f,
            center = Offset(46f + 6f * drift, 46f),
        )
    }
}

/**
 * The key, from above and near the camera down into the lock; `turn` is how
 * much of its face is still towards us, which is what turning looks like head-on.
 */
private fun DrawScope.drawKey(approach: Float, withdraw: Float, turn: Float, tilt: Float, alpha: Float) {
    if (approach <= 0f || alpha <= 0f) return
    val flight = FastOutSlowInEasing.transform((approach / 0.72f).coerceAtMost(1f))
    val slide = FastOutSlowInEasing.transform(((approach - 0.72f) / 0.28f).coerceIn(0f, 1f))
    val out = FastOutSlowInEasing.transform(withdraw)

    val cx = lerp(78f, 50f, flight) - 12f * out
    val cy = lerp(-10f, 28f, flight) + 14f * slide - 22f * out
    val scale = KEY_SIZE * (lerp(1.8f, 1f, flight) + 0.45f * out)
    val angle = lerp(34f, 0f, flight) + tilt - 24f * out
    val a = alpha * min(1f, approach * 5f) * (1f - out)
    if (a <= 0f) return

    // Anything below the keyhole's centre is inside the lock.
    clipRect(top = -200f, bottom = 45f) {
        withTransform({
            translate(cx, cy)
            rotate(angle, pivot = Offset.Zero)
            scale(turn.coerceAtLeast(0.08f) * scale, scale, pivot = Offset.Zero)
        }) {
            // Nearer the camera, the shadow falls further away and softer.
            val lift = 1.2f + 5f * (1f - flight)
            withTransform({ translate(lift * 0.6f, lift) }) {
                drawPath(KeyShape, Color.Black.copy(alpha = 0.28f * a * (1f - 0.5f * (1f - flight))))
            }
            drawPath(KeyShape, Mark.Depth.copy(alpha = a), style = Stroke(1.4f, join = StrokeJoin.Round))
            // The face darkens as it turns away from us.
            val shade = 1f - turn.coerceIn(0f, 1f)
            drawPath(
                KeyShape,
                Brush.linearGradient(
                    listOf(
                        lerp(Color(0xFFFBEACB), Color(0xFFB98F4E), shade * 0.6f),
                        lerp(Mark.Gold, Color(0xFF9C7640), shade * 0.5f),
                        Color(0xFFA9803F),
                    ),
                    start = Offset(-7f, -24f),
                    end = Offset(7f, 14f),
                ),
                alpha = a,
            )
            // A glint down the shaft.
            drawLine(
                Color.White.copy(alpha = 0.55f * a * (1f - shade)),
                Offset(-0.5f, -8f),
                Offset(-0.5f, 10f),
                0.6f,
                StrokeCap.Round,
            )
            drawCircle(
                Color.White.copy(alpha = 0.5f * a * (1f - shade)),
                radius = 1.1f,
                center = Offset(-3.2f, -19.5f),
            )
        }
    }
}

private const val KEY_SIZE = 1.18f

/** Bow, collar, shaft and bit, tip at y = 14; the bow's hole cut through. */
private val KeyShape: Path = run {
    fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(Rect(l, t, r, b)) }
    fun oval(c: Offset, radius: Float) = Path().apply { addOval(Rect(c, radius)) }
    var body = oval(Offset(0f, -16f), 6.6f)
    listOf(
        Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(-2.8f, -10.6f, 2.8f, -8.2f, CornerRadius(0.8f))) },
        rect(-1.3f, -9f, 1.3f, 13f),
        oval(Offset(0f, 13f), 1.3f),
        rect(1.2f, 4.6f, 4.9f, 7f),
        rect(1.2f, 8.6f, 3.9f, 10.8f),
        rect(-2.4f, -2.2f, 2.4f, -1f),
    ).forEach { body = Path.combine(PathOperation.Union, body, it) }
    body = Path.combine(PathOperation.Difference, body, oval(Offset(0f, -16f), 3.4f))
    Path.combine(PathOperation.Union, body, oval(Offset(0f, -16f), 1.1f))
}

private fun DrawScope.drawKeyholeHalo(waiting: Float, flash: Float, refusal: Float) {
    val glow = 0.4f * waiting + flash
    if (glow > 0f) {
        drawCircle(
            Brush.radialGradient(
                listOf(Mark.Light.copy(alpha = 0.55f * glow.coerceAtMost(1f)), Color.Transparent),
                center = Offset(50f, 47f),
                radius = 18f + 8f * flash,
            ),
            radius = 26f,
            center = Offset(50f, 47f),
        )
    }
    if (refusal > 0f) {
        drawCircle(
            Brush.radialGradient(
                listOf(Mark.Refusal.copy(alpha = 0.6f * refusal), Color.Transparent),
                center = Offset(50f, 47f),
                radius = 20f,
            ),
            radius = 26f,
            center = Offset(50f, 47f),
        )
    }
}

/** The click: a ring of light off the keyhole and a burst of short rays. */
private fun DrawScope.drawClick(click: Float) {
    if (click <= 0f || click >= 1f) return
    val fade = 1f - click
    val centre = Offset(50f, 45f)
    drawCircle(
        Mark.Light.copy(alpha = 0.85f * fade),
        radius = 8f + 34f * click,
        center = centre,
        style = Stroke(2.2f * fade),
    )
    drawCircle(
        Color.White.copy(alpha = 0.5f * fade * fade),
        radius = 8f + 20f * click,
        center = centre,
        style = Stroke(1f * fade),
    )
    for (i in 0 until 12) {
        val theta = (i * 30f + 15f) * PI.toFloat() / 180f
        val from = 10f + 18f * click
        val to = from + 6f * fade + 1f
        val dir = Offset(cos(theta), sin(theta))
        drawLine(
            Mark.Light.copy(alpha = fade),
            centre + dir * from,
            centre + dir * to,
            1.1f * fade + 0.2f,
            StrokeCap.Round,
        )
    }
}

private class Mote(
    val x: Float,
    val y: Float,
    val drift: Float,
    val rise: Float,
    val start: Float,
    val size: Float,
    val wobble: Float,
)

private val Motes: List<Mote> = Random(1947).let { r ->
    List(34) {
        Mote(
            x = 30f + r.nextFloat() * 40f,
            y = 48f + r.nextFloat() * 34f,
            drift = -9f + r.nextFloat() * 18f,
            rise = 34f + r.nextFloat() * 50f,
            start = r.nextFloat() * 0.45f,
            size = 0.35f + r.nextFloat() * 0.9f,
            wobble = r.nextFloat() * 6.28f,
        )
    }
}

/** Gold dust, lifted out of the almira by the light. */
private fun DrawScope.drawMotes(progress: Float) {
    if (progress <= 0f) return
    Motes.forEach { m ->
        val p = ((progress - m.start) / (1f - m.start)).coerceIn(0f, 1f)
        if (p >= 1f || (p <= 0f && progress > WARM)) return@forEach
        val lifted = LinearOutSlowInEasing.transform(p)
        val c = Offset(
            m.x + m.drift * lifted + 1.6f * sin(m.wobble + p * 9f),
            m.y - m.rise * lifted,
        )
        val a = if (p <= 0f) WARM else sin(p * PI.toFloat())
        drawCircle(
            Brush.radialGradient(listOf(Mark.Light.copy(alpha = 0.7f * a), Color.Transparent), c, m.size * 3.2f),
            radius = m.size * 3.2f,
            center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.9f * a), radius = m.size * 0.55f, center = c)
    }
}

/**
 * The whole screen behind the mark: the brand ground with a vignette, the
 * light that spills out when the doors open and floods in on the way through,
 * and a warning warmth for a refusal.
 */
private fun DrawScope.drawBackdrop(centre: Offset, markPx: Float, open: Float, push: Float, refusal: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(Color(0xFF1A4E48), Mark.Ground, Color(0xFF0A2A26)),
            center = centre,
            radius = size.maxDimension * 0.75f,
        ),
    )
    if (open > 0f) {
        // Slow rays fanning out of the doorway.
        val rays = 9
        for (i in 0 until rays) {
            val theta = (-90f + (i - rays / 2) * 20f) * PI.toFloat() / 180f
            val spread = 0.09f
            val reach = size.maxDimension
            val p = Path().apply {
                moveTo(centre.x, centre.y)
                lineTo(centre.x + cos(theta - spread) * reach, centre.y + sin(theta - spread) * reach)
                lineTo(centre.x + cos(theta + spread) * reach, centre.y + sin(theta + spread) * reach)
                close()
            }
            drawPath(
                p,
                Brush.radialGradient(
                    listOf(Mark.Light.copy(alpha = 0.12f * open), Color.Transparent),
                    center = centre,
                    radius = size.minDimension * 0.9f,
                ),
            )
        }
        drawCircle(
            Brush.radialGradient(
                listOf(Mark.Gold.copy(alpha = 0.4f * open), Color.Transparent),
                center = centre,
                radius = markPx * (0.55f + 0.5f * open),
            ),
            radius = markPx * 1.1f,
            center = centre,
        )
    }
    if (push > 0f) {
        // Warm, and late: a half-strength wash over the teal reads as grey.
        drawRect(Color(0xFFFFF4DC).copy(alpha = (push * push * push).coerceAtMost(1f)))
    }
    if (refusal > 0f) {
        drawCircle(
            Brush.radialGradient(
                listOf(Mark.Refusal.copy(alpha = 0.22f * refusal), Color.Transparent),
                center = centre,
                radius = markPx * 1.2f,
            ),
            radius = markPx * 1.2f,
            center = centre,
        )
    }
}
