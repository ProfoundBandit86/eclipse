package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind

/*
 * Settings → Appearance → Colours: the colours and glass, as a list on the left (each opens in place with quick picks,
 * a colour wheel for anything else, and the theme's own), and a live miniature of Eclipse on the right. L1 / R1 switch
 * the miniature between Home with the right dock, Settings, and a game's menu. B goes back to Appearance.
 */

private class ColourOpt(val kind: String, val title: String, val about: String)

private val COLOUR_OPTS = listOf(
    ColourOpt("glass", "Glass colour", "Glass colour tints every glass panel: the docks, Settings and menus."),
    ColourOpt("strength", "Glass strength", "How solid the glass panels are, from see-through to solid."),
    ColourOpt("accent", "Accent", "Selected items and coloured writing: current choices, section titles, the Play key and switches."),
    ColourOpt("focus", "Highlight", "The outline around whatever the controller is on."),
    ColourOpt("text", "Text", "The main writing everywhere."),
    ColourOpt("labels", "Labels", "The softer writing: dates, hints and descriptions."),
    ColourOpt("backdrop", "Background", "The colour of the shading over wallpapers and game art, slide view's fades included."),
    ColourOpt("dim", "Background dim", "How much the wallpaper or game art behind the panels is darkened (lightened in light mode)."),
)

@Composable
fun ColoursScreen(vm: EclipseVM) = Glass {
    val close = { vm.appearancePicker = null }
    BackHandler { close() }
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    var open by remember { mutableStateOf<String?>(null) }
    var about by remember { mutableStateOf(COLOUR_OPTS[0]) }
    var page by remember { mutableIntStateOf(0) }
    // L1 / R1: what the preview shows
    DisposableEffect(Unit) {
        val prevR = GamepadFocus.rightDock
        val l: () -> Unit = { page = (page + 2) % 3 }
        val r: () -> Unit = { page = (page + 1) % 3 }
        GamepadFocus.leftDock = l; GamepadFocus.rightDock = r
        onDispose {
            if (GamepadFocus.leftDock === l) GamepadFocus.leftDock = null
            if (GamepadFocus.rightDock === r) GamepadFocus.rightDock = prevR
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: the options ----
            GlassPanel(Modifier.width(300.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().menuScroll().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("APPEARANCE ›", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text("Colours", color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    COLOUR_OPTS.forEachIndexed { i, o ->
                        ColourRow(vm, o, open == o.kind, if (i == 0) first else null, onFocus = { about = o }) {
                            open = if (open == o.kind) null else o.kind
                        }
                        AnimatedVisibility(open == o.kind) { ColourMenu(vm, o.kind) }
                    }
                    Spacer(Modifier.height(4.dp))
                    FocusBox(Modifier.fillMaxWidth().height(30.dp), shape = RoundedCornerShape(9.dp), onClick = { vm.resetColours() }, focusKey = "col:reset") {
                        Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("↺  ALL BACK TO THE THEME'S", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.4.sp)
                        }
                    }
                }
            }
            // ---- right: the preview ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PREVIEW", color = C.accent, fontSize = 8.5.sp, letterSpacing = 2.sp)
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text("LIVE", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.6.sp)
                    }
                    Box(
                        Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(753f / 424f).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.x(0x2EFFFFFFL), RoundedCornerShape(12.dp))
                    ) { ColourPreview(vm, page) }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Home + dock", "Settings", "Game menu").forEachIndexed { i, label ->
                            val on = page == i
                            Text(
                                label.uppercase(), color = if (on) C.accent else C.sub, fontSize = 8.sp, letterSpacing = 1.3.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (on) C.accent.copy(alpha = 0.16f) else C.x(0x12FFFFFFL))
                                    .then(if (on) Modifier.border(1.dp, C.accent.copy(alpha = 0.4f), RoundedCornerShape(50)) else Modifier)
                                    .pointerInput(i) { detectTapGestures { page = i } }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Text(about.about + " The preview changes as soon as you pick.", color = C.sub, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 9.dp))
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ColourHint("A", "Choose"); ColourHint("L1 R1", "Preview"); ColourHint("B", "Done")
        }
    }
}

@Composable
private fun ColourHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.height(17.dp).widthIn(min = 17.dp).clip(RoundedCornerShape(9.dp)).background(C.x(0x2BFFFFFFL)).padding(horizontal = if (key.length > 1) 5.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) { Text(key, color = C.ink, fontSize = 7.5.sp, fontWeight = FontWeight.Bold) }
        Text(label.uppercase(), color = C.x(0xFFB9B4AAL), fontSize = 8.5.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A colour's current value: what you picked, else the theme's (or Eclipse's own). */
@Composable
private fun currentColour(vm: EclipseVM, kind: String): Color = colorOf(vm.colourHex(kind)) ?: vm.colourAuto(kind)

/** One option: name, a swatch and the value (or a percentage), and an arrow. */
@Composable
private fun ColourRow(vm: EclipseVM, o: ColourOpt, expanded: Boolean, fr: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = if (expanded) RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp) else RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    val (swatch, value) = when (o.kind) {
        "strength" -> null to (if (vm.glassStrength < 0) "Theme's · ${(ThemeColors.glassAlpha.value * 100).toInt()}%" else "${vm.glassStrength}%")
        "dim" -> null to (if (vm.bgDim < 0) "Theme's" else "${vm.bgDim}%")
        else -> currentColour(vm, o.kind) to vm.colourHex(o.kind).ifBlank { "Theme's" }
    }
    FocusBox(
        Modifier.fillMaxWidth().heightIn(min = 30.dp), shape = shape, onClick = onClick, focusRequester = fr,
        focusKey = "col:${o.kind}", onFocusChange = { f -> if (f) onFocus() }
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 30.dp)
                .background(if (expanded) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (expanded) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(o.title.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.sp, letterSpacing = 1.5.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (swatch != null) Box(Modifier.padding(end = 6.dp).size(12.dp).clip(CircleShape).background(swatch).border(1.dp, C.x(0x59FFFFFFL), CircleShape))
            Text(value.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
        }
    }
}

/** An open option: quick swatches + Custom… + Theme's for a colour; a slider for strength and dim. */
@Composable
private fun ColourMenu(vm: EclipseVM, kind: String) {
    val shape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)
    Column(
        Modifier.fillMaxWidth().offset(y = (-5).dp).clip(shape)
            .background(if (ThemeColors.light.value) Color(0xBFFFFFFF) else C.x(0x80100F0EL))
            .border(1.dp, C.accent.copy(alpha = 0.3f), shape).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (kind) {
            "strength" -> {
                PercentSlider(if (vm.glassStrength < 0) (ThemeColors.glassAlpha.value * 100).toInt() else vm.glassStrength, "CLEAR", "SOLID") { vm.chooseGlassStrength(it) }
                if (vm.glassStrength >= 0) MenuChip("↺  THEME'S", "col:strength:theme") { vm.chooseGlassStrength(-1) }
            }
            "dim" -> {
                PercentSlider(if (vm.bgDim < 0) 30 else vm.bgDim, "NONE", "MOST") { vm.chooseBgDim(it) }
                if (vm.bgDim >= 0) MenuChip("↺  THEME'S", "col:dim:theme") { vm.chooseBgDim(-1) }
            }
            else -> {
                val cur = vm.colourHex(kind)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    colourPicks(kind).filter { it.isNotEmpty() }.take(7).forEach { hex ->
                        val col = colorOf(hex) ?: Color.Gray
                        val sel = cur.equals(hex, true)
                        FocusBox(Modifier.size(24.dp), shape = CircleShape, onClick = { vm.chooseColour(kind, hex) }, focusKey = "col:$kind:$hex") {
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape).background(col)
                                    .border(if (sel) 2.5.dp else 1.dp, if (sel) C.focus else C.x(0x40FFFFFFL), CircleShape)
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuChip("＋  CUSTOM…", "col:$kind:custom") { vm.colorPicker = kind }
                    MenuChip("↺  THEME'S", "col:$kind:theme") { vm.chooseColour(kind, "") }
                }
            }
        }
    }
}

@Composable
private fun MenuChip(label: String, key: String, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(7.dp), onClick = onClick, focusKey = key) {
        Text(
            label, color = C.x(0xFFCFCAC0L), fontSize = 8.sp, letterSpacing = 1.2.sp, maxLines = 1,
            modifier = Modifier.background(C.x(0x12FFFFFFL)).padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}

/** 0..100 in steps of 5: Left / Right with a controller, tap or drag by touch. */
@Composable
fun PercentSlider(value: Int, left: String, right: String, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    fun at(x: Float) = (Math.round((x / widthPx).coerceIn(0f, 1f) * 20) * 5)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.2.sp)
        BoxWithConstraints(
            Modifier.weight(1f).padding(horizontal = 10.dp).height(24.dp)
                .onFocusChanged { focused = it.isFocused }.focusable()
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onChange((value - 5).coerceIn(0, 100)); true }
                        Key.DirectionRight -> { onChange((value + 5).coerceIn(0, 100)); true }
                        else -> false
                    }
                }
                .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> onChange(at(ch.position.x)); ch.consume() } },
            contentAlignment = Alignment.CenterStart
        ) {
            widthPx = constraints.maxWidth.coerceAtLeast(1)
            val w = maxWidth
            val f = value / 100f
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x24FFFFFFL))
                .then(if (focused) Modifier.border(1.5.dp, C.focus, RoundedCornerShape(3.dp)) else Modifier))
            Box(Modifier.width(w * f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
            Box(Modifier.offset(x = w * f - 7.dp).size(14.dp).clip(CircleShape).background(Color.White).border(2.5.dp, C.accent, CircleShape))
        }
        Text(right, color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.2.sp)
    }
}

// ---------------------------------------------------------------- the miniature

/**
 * A miniature of Eclipse drawn with your current colours: [page] 0 = Home with the right dock, 1 = Settings, 2 = a
 * game's menu. Laid out at the Thor's real size (753 × 424 dp) and scaled down to fit. Nothing in it can be selected.
 */
@Composable
private fun ColourPreview(vm: EclipseVM, page: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val s = maxWidth / 753.dp
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(753.dp, 424.dp)
                .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
        ) {
            GlassScreenBackdrop(vm)
            val games = remember(vm.games) { vm.games.take(4) }
            when (page) {
                0 -> PreviewHome(vm, games)
                1 -> PreviewSettings()
                else -> PreviewMenu(vm, games.firstOrNull())
            }
        }
    }
}

@Composable
private fun PreviewHome(vm: EclipseVM, games: List<com.eclipse.launcher.data.Game>) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 28.dp, top = 22.dp)) {
            Text(games.firstOrNull()?.name ?: "Super Metroid", color = C.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("LAST PLAYED", color = C.sub, fontSize = 9.sp, letterSpacing = 1.6.sp)
                Text("YESTERDAY", color = C.ink, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, end = 14.dp))
                Text("PLAY TIME", color = C.sub, fontSize = 9.sp, letterSpacing = 1.6.sp)
                Text("2 H 14 M", color = C.ink, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 34.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            (0 until 3).forEach { i ->
                val g = games.getOrNull(i)
                val big = i == 0
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.size(if (big) 190.dp else 120.dp, if (big) 253.dp else 160.dp).clip(shape)
                        .background(C.x(0xFF2A2A30L))
                        .then(if (big) Modifier.border(3.dp, C.focus, shape) else Modifier)
                ) { g?.let { vm.artOf(it, ArtKind.BOX) }?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            }
        }
        CornerClock(vm, Modifier.align(Alignment.TopEnd))
        // the right dock
        GlassPanel(Modifier.align(Alignment.CenterEnd).width(270.dp).fillMaxHeight(), RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.padding(top = 29.dp, bottom = 12.dp)) {
                    Text("MONDAY", color = C.x(0xFFE6E3DAL), fontSize = 9.sp, letterSpacing = 1.6.sp)
                    Text("26 OCTOBER", color = C.sub, fontSize = 7.5.sp, letterSpacing = 1.6.sp)
                }
                Text("ALL GAMES", color = C.accent, fontSize = 8.sp, letterSpacing = 2.sp)
                listOf("Sort" to "A–Z", "View" to "Slide view", "Style" to "Box art", "Size" to "100%", "Source" to "Local only").forEachIndexed { i, (k, v) ->
                    PreviewRow(k, v, focused = i == 1)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(17.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) { Text("A", color = C.onAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
            Text("PLAY", color = C.sub, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun PreviewRow(label: String, value: String, focused: Boolean = false) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(shape)
            .background(if (ThemeColors.light.value) Color.White else C.x(0x0FFFFFFFL))
            .border(if (focused) 2.5.dp else 1.dp, if (focused) C.focus else C.x(0x12FFFFFFL), shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 8.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
        Text(value.uppercase(), color = C.accent, fontSize = 8.sp, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun PreviewSettings() {
    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassPanel(Modifier.width(58.dp).fillMaxHeight(), RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(7) { i ->
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (i == 0) C.accent.copy(alpha = 0.22f) else C.x(0x0FFFFFFFL))
                        .then(if (i == 0) Modifier.border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(9.dp)) else Modifier))
                }
            }
        }
        GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Look & Feel", color = C.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Mode, theme, colours, font and images.", color = C.sub, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("LOOK", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                PreviewRow("Mode", "Dark", focused = true)
                PreviewRow("Theme", "Eclipse")
                PreviewRow("Colours", "Custom")
                Text("TEXT", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
                PreviewRow("Font", "Default")
                PreviewRow("Text size", "100%")
            }
        }
    }
}

@Composable
private fun PreviewMenu(vm: EclipseVM, g: com.eclipse.launcher.data.Game?) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.size(460.dp, 250.dp), RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxSize().padding(20.dp)) {
                Box(Modifier.size(150.dp, 200.dp).clip(RoundedCornerShape(10.dp)).background(C.x(0xFF2A2A30L))) {
                    g?.let { vm.artOf(it, ArtKind.BOX) }?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.padding(start = 18.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(g?.name ?: "Super Metroid", color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text("Played 2 h 14 m · last played yesterday", color = C.sub, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(EclipseButtonShape).background(C.accent), contentAlignment = Alignment.Center) {
                        Text("PLAY", color = C.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).clip(EclipseButtonShape).background(C.x(0x1FFFFFFFL)).border(2.5.dp, C.focus, EclipseButtonShape),
                        contentAlignment = Alignment.Center
                    ) { Text("DETAILS", color = C.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }
                    Box(Modifier.fillMaxWidth().height(36.dp).clip(EclipseButtonShape).background(C.x(0x1FFFFFFFL)), contentAlignment = Alignment.Center) {
                        Text("FAVOURITE", color = C.ink, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}
