package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Quick picks for each colour: "" = from the theme / Eclipse's own. */
private val HIGHLIGHT_PICKS = listOf("", "#D4A537", "#00C3E3", "#FF3FA4", "#E60012", "#FF8A00", "#34C759", "#8E5CFF", "#FFFFFF")
private val TEXT_PICKS = listOf("", "#F2EDE2", "#D8D8DD", "#FFE7B0", "#CFE8FF", "#D9F5E3", "#F7D6E0")
private val GLASS_PICKS = listOf("", "#2C2924", "#15171C", "#1D2A3A", "#2B1F33", "#F2EEE6", "#EEF5FD")
private val BACKDROP_PICKS = listOf("", "#0B0B0D", "#14110C", "#0A1220", "#160C1E", "#0C1A12", "#200A0A")
private val LABEL_PICKS = listOf("", "#A9ABB4", "#9A958A", "#8FA3B8", "#6F6A60", "#46586A", "#B9B4AA")

/** The quick picks for one colour (Settings → Appearance → Colours): "" first = the theme's. */
fun colourPicks(kind: String): List<String> = when (kind) {
    "text" -> TEXT_PICKS; "glass" -> GLASS_PICKS; "backdrop" -> BACKDROP_PICKS; "labels" -> LABEL_PICKS; else -> HIGHLIGHT_PICKS
}

/** A colour's name in Settings ("Glass colour" …). */
fun colourTitle(kind: String): String = when (kind) {
    "glass" -> "Glass colour"; "backdrop" -> "Background"; "accent" -> "Accent"; "text" -> "Text colour"; "labels" -> "Labels"; else -> "Highlight colour"
}

fun hexOf(c: Color): String = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
fun colorOf(hex: String): Color? = try { if (hex.isBlank()) null else Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { null }

/**
 * The colour wheel popup (Settings → Appearance → Change): hue around the wheel, paler toward the middle, brightness on a
 * slider, a code you can type, quick picks and a live preview. D-pad moves the marker on the wheel; L2 / R2 (or Left /
 * Right on the slider) change the brightness. [kind] is "focus" (highlight) or "text".
 */
@Composable
fun ColorWheelDialog(vm: EclipseVM, kind: String) = Glass {
    val isText = kind == "text" || kind == "labels"
    val current = vm.colourHex(kind)
    val auto = vm.colourAuto(kind)
    val start = colorOf(current) ?: auto
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.rgb((start.red * 255).toInt(), (start.green * 255).toInt(), (start.blue * 255).toInt()), it) } }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    var useAuto by remember { mutableStateOf(current.isBlank()) }
    val picked = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val shown = if (useAuto) auto else picked
    fun setFrom(c: Color) {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()), a)
        hue = a[0]; sat = a[1]; value = a[2]; useAuto = false
    }
    // closing (B, Cancel or Use): the controller goes back to the Custom… chip that opened the wheel. Without this
    // nothing had focus once the wheel went, so the next B or D-pad press did nothing (or acted behind the screen).
    val back = { GamepadFocus.lastKey = "col:$kind:custom"; GamepadFocus.restoreKey = "col:$kind:custom" }
    val close = { vm.colorPicker = null; back() }
    val use = {
        val hex = if (useAuto) "" else hexOf(picked)
        vm.chooseColour(kind, hex)
        vm.colorPicker = null
        back()
    }
    BackHandler { close() }
    // L2 / R2: brightness
    DisposableEffect(Unit) {
        val prev = GamepadFocus.shoulder
        val hook: (Int) -> Unit = { d -> value = (value + d * 0.05f).coerceIn(0.05f, 1f); useAuto = false }
        GamepadFocus.shoulder = hook
        onDispose { if (GamepadFocus.shoulder === hook) GamepadFocus.shoulder = prev }
    }
    val wheelFocus = remember { FocusRequester() }
    FocusOnShow(wheelFocus)
    var wheelFocused by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Color(0x9E040406)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(540.dp).dockPanel(RoundedCornerShape(22.dp))
                .pointerInput(Unit) { detectTapGestures { } }.focusTrap()
                .padding(horizontal = 24.dp, vertical = 22.dp)
        ) {
            Text("COLOURS", color = C.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(colourTitle(kind), color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // the wheel: touch or drag to pick; with a controller, the D-pad moves the marker
                Box(
                    Modifier.size(200.dp)
                        .focusRequester(wheelFocus)
                        .onFocusChanged { wheelFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            val step = 0.06f
                            val r = sat; val a = Math.toRadians(hue.toDouble())
                            var x = (cos(a) * r).toFloat(); var y = (sin(a) * r).toFloat()
                            when (e.key) {
                                Key.DirectionLeft -> x -= step
                                Key.DirectionRight -> x += step
                                Key.DirectionUp -> y -= step
                                Key.DirectionDown -> y += step
                                else -> return@onKeyEvent false
                            }
                            // stepping off the edge of the wheel lets the D-pad leave it (to the slider / buttons)
                            if (hypot(x, y) > 1.04f) return@onKeyEvent false
                            val d = hypot(x, y).coerceAtMost(1f)
                            sat = d
                            if (d > 0.001f) hue = ((Math.toDegrees(atan2(y, x).toDouble()) + 360) % 360).toFloat()
                            useAuto = false
                            true
                        }
                        .pointerInput(Unit) {
                            fun pick(p: Offset) {
                                val cx = size.width / 2f; val cy = size.height / 2f
                                val dx = p.x - cx; val dy = p.y - cy
                                sat = (hypot(dx, dy) / (size.width / 2f)).coerceIn(0f, 1f)
                                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                                useAuto = false
                            }
                            detectTapGestures { pick(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val cx = size.width / 2f; val cy = size.height / 2f
                                val dx = change.position.x - cx; val dy = change.position.y - cy
                                sat = (hypot(dx, dy) / (size.width / 2f)).coerceIn(0f, 1f)
                                hue = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360) % 360).toFloat()
                                useAuto = false
                                change.consume()
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        val hues = (0..12).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 30f % 360f, 1f, 1f))) }
                        drawCircle(Brush.sweepGradient(hues, center), r)
                        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
                        // darken with the brightness setting, so the wheel shows what you'll get
                        drawCircle(Color.Black.copy(alpha = 1f - value), r)
                        if (wheelFocused) drawCircle(C_FOCUS_RING, r + 3.dp.toPx(), style = Stroke(3.dp.toPx()))
                        // the marker
                        val a = Math.toRadians(hue.toDouble())
                        val m = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
                        drawCircle(Color.Black, 13.dp.toPx(), m)
                        drawCircle(Color.White, 11.dp.toPx(), m)
                        drawCircle(picked, 8.dp.toPx(), m)
                    }
                }
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("BRIGHTNESS", color = C.sub, fontSize = 12.sp, letterSpacing = 0.5.sp)
                    BrightnessSlider(value, Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f)))) { value = it; useAuto = false }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(shown).border(1.dp, C.x(0x33FFFFFFL), RoundedCornerShape(10.dp)))
                        // type a code straight into the box (#F2EDE2)
                        TextBox(if (useAuto) "" else hexOf(picked), Modifier.padding(start = 10.dp).width(130.dp), placeholder = "From the theme", fontSize = 15.sp) { t ->
                            val h = t.trim().let { if (it.startsWith("#")) it else "#$it" }
                            colorOf(h)?.let { setFrom(it) }
                        }
                    }
                    Text("QUICK PICKS", color = C.sub, fontSize = 12.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        colourPicks(kind).take(7).forEach { hex ->
                            val col = colorOf(hex) ?: auto
                            val sel = if (hex.isEmpty()) useAuto else (!useAuto && hexOf(picked).equals(hex, true))
                            FocusBox(Modifier.size(28.dp), shape = CircleShape, onClick = { if (hex.isEmpty()) useAuto = true else setFrom(col) }) {
                                Box(Modifier.fillMaxSize().background(col).border(if (sel) 3.dp else 1.dp, if (sel) Color.White else C.x(0x40FFFFFFL), CircleShape), contentAlignment = Alignment.Center) {
                                    if (hex.isEmpty()) Text("A", color = if (col.luminance() > 0.5f) C.x(0xFF111111L) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            // live preview
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.x(0xFF0D0D11L))
                    .border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val ink = if (isText) shown else C.ink
                Column(Modifier.weight(1f)) {
                    Text("Super Metroid", color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Super Nintendo · 2 h 14 m played", color = ink.copy(alpha = 0.65f), fontSize = 11.5.sp)
                }
                if (!isText) {
                    Box(Modifier.size(84.dp, 34.dp).clip(EclipseButtonShape).background(C.x(0x1FFFFFFFL)).border(3.dp, shown, EclipseButtonShape), contentAlignment = Alignment.Center) {
                        Text("Selected", color = C.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("D-PAD: MOVE  ·  L2 / R2: BRIGHTNESS", color = C.x(0xFFB4AFA5L), fontSize = 10.5.sp, modifier = Modifier.weight(1f))
                Pill("Cancel") { close() }
                Spacer(Modifier.width(10.dp))
                Pill("Use colour", primary = true) { use() }
            }
        }
    }
}

private val C_FOCUS_RING: Color get() = ThemeColors.focus.value

/** Brightness: a bar from black to the picked colour. Drag or tap it; Left / Right step it with a controller. */
@Composable
private fun BrightnessSlider(value: Float, full: Color, onChange: (Float) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth().height(28.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange((value - 0.05f).coerceIn(0.05f, 1f)); true }
                    Key.DirectionRight -> { onChange((value + 0.05f).coerceIn(0.05f, 1f)); true }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0.05f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0.05f, 1f)); change.consume() }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(Brush.horizontalGradient(listOf(Color.Black, full)))
                .then(if (focused) Modifier.border(2.dp, ThemeColors.focus.value, RoundedCornerShape(7.dp)) else Modifier)
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier.offset(x = (maxWidth - 22.dp) * value).size(22.dp).clip(CircleShape)
                    .background(Color.White).border(3.dp, C.accent, CircleShape)
            )
        }
    }
}
