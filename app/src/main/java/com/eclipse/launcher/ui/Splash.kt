package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import kotlinx.coroutines.launch
import kotlin.math.sin

private val GOLD = Color(0xFFE0B04A)
private val RIM = Color(0xFFFFE2A0)
private val WARM = Color(0xFFFFE6AA)

/** A few stars, as fractions of the screen (x, y, size in dp, brightness, twinkle phase). Fixed, so it looks the same each time. */
private val STARS = listOf(
    floatArrayOf(0.12f, 0.18f, 1.0f, 0.55f, 0.0f), floatArrayOf(0.82f, 0.12f, 1.0f, 0.45f, 1.3f),
    floatArrayOf(0.06f, 0.46f, 0.9f, 0.40f, 2.1f), floatArrayOf(0.40f, 0.08f, 0.8f, 0.30f, 0.7f),
    floatArrayOf(0.91f, 0.58f, 1.2f, 0.55f, 2.8f), floatArrayOf(0.75f, 0.30f, 0.8f, 0.30f, 3.5f),
    floatArrayOf(0.30f, 0.35f, 0.8f, 0.22f, 4.2f), floatArrayOf(0.22f, 0.62f, 0.9f, 0.30f, 5.0f),
    floatArrayOf(0.64f, 0.05f, 0.7f, 0.25f, 1.9f), floatArrayOf(0.95f, 0.30f, 0.8f, 0.35f, 0.4f),
    floatArrayOf(0.03f, 0.15f, 0.8f, 0.30f, 3.1f), floatArrayOf(0.55f, 0.20f, 0.7f, 0.20f, 2.5f)
)

/**
 * The opening animation (Settings → General → Opening animation): an eclipse rising over a pure black screen (on an
 * OLED screen the black is pixels switched off), its rim lighting up, the name drawing together, then the flare at the
 * top. About 2.6 s, then it fades out. Any button, Back or a tap skips it.
 */
@Composable
fun EclipseSplash(vm: EclipseVM) {
    if (!vm.flag("splash", true)) { LaunchedEffect(Unit) { vm.splashDone = true }; return }
    val clock = remember { Animatable(0f) }      // the timeline, in ms
    val fade = remember { Animatable(1f) }       // the whole splash, fading out at the end
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    fun finish() {
        if (leaving) return
        leaving = true
        scope.launch { fade.animateTo(0f, tween(420)); vm.splashDone = true }
    }
    LaunchedEffect(Unit) {
        runCatching { fr.requestFocus() }
        clock.animateTo(2600f, tween(2600, easing = LinearEasing))
        finish()
    }
    BackHandler { finish() }

    // dots and stars keep moving while it waits
    val loop = rememberInfiniteTransition(label = "splashLoop")
    val beat by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "beat")

    val ms = clock.value
    fun seg(a: Float, b: Float) = ((ms - a) / (b - a)).coerceIn(0f, 1f)
    val starsIn = seg(0f, 900f)
    val rise = FastOutSlowInEasing.transform(seg(150f, 1500f))
    val rim = FastOutSlowInEasing.transform(seg(450f, 1400f))
    // the flare: a quick flash, then it settles a little
    val flare = if (ms < 1650f) FastOutSlowInEasing.transform(seg(1300f, 1650f)) else 1f - 0.3f * seg(1650f, 2200f)
    val word = FastOutSlowInEasing.transform(seg(1000f, 1800f))
    val tag = seg(1550f, 2150f)
    val dots = seg(1900f, 2250f)

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { finish() } }
            .focusRequester(fr).focusable()
            .onKeyEvent { if (it.type == KeyEventType.KeyDown) finish(); true }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            // stars, faint, twinkling slowly
            for (s in STARS) {
                val tw = 0.65f + 0.35f * sin((beat * 2f * Math.PI.toFloat()) + s[4])
                drawCircle(Color.White.copy(alpha = s[3] * tw * starsIn), s[2].dp.toPx(), Offset(s[0] * w, s[1] * h))
            }
            // the eclipse: a disc wider than the screen, rising until only its top shows
            val r = w * 0.388f
            val cx = w / 2f
            val cy = h * 0.698f + r + (1f - rise) * h * 0.42f
            val c = Offset(cx, cy)
            // glow just around the rim (everything further out stays pure black)
            val glowR = r * 1.2f
            drawCircle(
                Brush.radialGradient(
                    0f to Color.Transparent, (r * 0.97f / glowR) to Color.Transparent,
                    (r / glowR) to GOLD.copy(alpha = 0.42f * rim), (r * 1.06f / glowR) to GOLD.copy(alpha = 0.10f * rim), 1f to Color.Transparent,
                    center = c, radius = glowR
                ),
                glowR, c
            )
            // bloom: wide faint strokes to a thin bright one
            listOf(26.dp to 0.05f, 14.dp to 0.12f, 7.dp to 0.28f, 3.dp to 0.6f).forEach { (sw, a) ->
                drawCircle(GOLD.copy(alpha = a * rim), r, c, style = Stroke(sw.toPx()))
            }
            // the moon: true black
            drawCircle(Color.Black, r - 1.dp.toPx(), c)
            drawCircle(RIM.copy(alpha = rim), r, c, style = Stroke(1.8.dp.toPx()))
            // the flare at the top of the rim, with its streak
            if (flare > 0.01f) {
                val top = Offset(cx, cy - r)
                val gr = 38.dp.toPx() * (0.5f + 0.5f * flare)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * flare), WARM.copy(alpha = 0.45f * flare), Color.Transparent), center = top, radius = gr),
                    gr, top
                )
                drawCircle(Color.White.copy(alpha = flare), 5.5.dp.toPx() * (0.6f + 0.4f * flare), top)
                val half = 100.dp.toPx() * flare
                val th = 1.5.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.95f * flare), Color.Transparent), startX = cx - half, endX = cx + half),
                    topLeft = Offset(cx - half, top.y - th / 2f), size = Size(half * 2f, th)
                )
            }
        }

        // the name and the line under it, a quarter of the way down
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.fillMaxHeight(0.25f))
            Text(
                buildAnnotatedString {
                    append("ECLIPS")
                    withStyle(SpanStyle(color = GOLD)) { append("E") }
                },
                color = Color(0xFFF2EEE6), fontSize = 30.sp, fontWeight = FontWeight.Light,
                letterSpacing = (20f + 10f * (1f - word)).sp,
                modifier = Modifier.graphicsLayer { alpha = word }
            )
            Text(
                "YOUR GAMES · ONE PLACE", color = Color(0xFF9A958B), fontSize = 8.5.sp, letterSpacing = 3.2.sp,
                modifier = Modifier.padding(top = 14.dp).graphicsLayer { alpha = tag }
            )
        }

        // loading dots, one lit after another
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { alpha = dots },
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            for (i in 0 until 3) {
                val lit = ((beat * 3f).toInt() % 3) == i
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (lit) GOLD else GOLD.copy(alpha = 0.3f)))
            }
        }
    }
}
