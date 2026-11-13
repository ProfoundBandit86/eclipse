package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.Packs
import com.eclipse.launcher.data.PlatformDef
import com.eclipse.launcher.data.Platforms
import java.io.File

/*
 * Settings → Look & Feel → Theme / Icons / Backdrops. The right dock's look: what's installed on the left (A uses
 * one), and on the right a miniature of the home screen as it will look with the highlighted one installed.
 */

private data class LookEntry(val id: String, val name: String, val sub: String, val deletable: Boolean)

@Composable
fun AppearancePicker(vm: EclipseVM, kind: String) = Glass {
    val close = { vm.appearancePicker = null }
    BackHandler { close() }
    // pick up anything added to the Eclipse folder since the last look
    LaunchedEffect(kind) { vm.refreshThemeLists() }

    val entries: List<LookEntry> = when (kind) {
        "theme" -> listOf(LookEntry("", "Eclipse", "Eclipse's own black and gold", false)) +
            vm.themes.map { LookEntry(it.id, it.name, it.author.ifBlank { if (it.builtIn) "Comes with Eclipse" else "" }, !it.builtIn) }
        else -> {
            val packs = when (kind) { "icons" -> vm.iconPacks; "logos" -> vm.logoPacks; else -> vm.backdropPacks }
            fun ofTheme(t: com.eclipse.launcher.data.Themes.Theme) = when (kind) { "icons" -> t.platforms; "logos" -> t.logos; else -> t.backgrounds }
            val fromThemes = vm.themes.filter { ofTheme(it).isNotEmpty() }
            (when (kind) {
                "icons" -> emptyList()
                "logos" -> listOf(LookEntry("", "The theme's own", "The current theme's logos, else the ones that come with Eclipse", false))
                else -> listOf(LookEntry("", "The theme's own", "Whatever the current theme has", false))
            }) +
                packs.map { LookEntry("pack:${it.id}", it.name, if (it.id == com.eclipse.launcher.data.StockPack.COLORGRADE) "Comes with Eclipse · by grvceless · ${it.images.size} systems" else "Pack · ${it.images.size} systems", it.id != com.eclipse.launcher.data.StockPack.COLORGRADE) } +
                fromThemes.map { t -> LookEntry("theme:${t.id}", t.name, "From the theme · ${ofTheme(t).size} systems", false) }
        }
    }
    val current = when (kind) { "theme" -> vm.theme?.id ?: ""; "icons" -> vm.iconChoice.ifEmpty { vm.defaultIconPack()?.let { "pack:${it.id}" } ?: "" }; "logos" -> vm.logoChoice; else -> vm.backdropChoice }
    var shown by remember(kind) { mutableStateOf(current) }
    fun use(id: String) {
        when (kind) {
            "theme" -> vm.useTheme(vm.themes.firstOrNull { it.id == id })
            "icons" -> vm.chooseIcons(id)
            "logos" -> vm.chooseLogos(id)
            else -> vm.chooseBackdrops(id)
        }
    }
    val title = when (kind) { "theme" -> "Themes"; "icons" -> "Icons"; "logos" -> "Logos"; else -> "Backdrops" }

    // the controller goes to the list, and keeps trying for a few frames (the rows may not be laid out on the first)
    val first = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    PrimaryFocus(first)
    LaunchedEffect(Unit) {
        repeat(20) {
            androidx.compose.runtime.withFrameNanos { }
            if (listFocused) return@LaunchedEffect
            runCatching { first.requestFocus() }
        }
    }
    val firstIndex = entries.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: 0

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: what's installed ----
            GlassPanel(Modifier.width(270.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                    Text("LOOK & FEEL ›", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text(title, color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                    Column(
                        Modifier.weight(1f).fillMaxWidth().onFocusChanged { listFocused = it.hasFocus }.menuScroll().stayInside()
                            .padding(bottom = 40.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        DropSection("Installed")
                        entries.forEachIndexed { i, e ->
                            LookRow(e, e.id == current, if (i == firstIndex) first else null, "pick:$kind:${e.id}", onFocus = { shown = e.id }) { use(e.id) }
                        }
                        // Eclipse's own logos (the ones used when no pack or theme has a system's logo): black / white
                        // to suit the card's glass colour, or in colour (was Look & Feel → Card logos)
                        if (kind == "logos") {
                            DropSection("Eclipse's logos")
                            listOf(
                                LookEntry("", "Black / white", "Whichever reads on the glass colour", false),
                                LookEntry("color", "Colour", "The systems' own colours", false)
                            ).forEach { m ->
                                LookRow(m, vm.cardMode == m.id, null, "pick:logos:style:${m.id}", onFocus = { }) { vm.chooseCardMode(m.id) }
                            }
                        }
                        DropSection("Add")
                        LookAction(
                            "Import…",
                            when (kind) { "theme" -> "a theme .zip"; else -> "a .zip of pictures" },
                            "pick:$kind:import"
                        ) {
                            when (kind) {
                                "theme" -> Pickers.theme()
                                "icons" -> Pickers.pack(Packs.ICONS)
                                "logos" -> Pickers.pack(Packs.LOGOS)
                                else -> Pickers.pack(Packs.BACKDROPS)
                            }
                        }
                        if (kind == "theme") LookAction("Export theme", "to Eclipse/export", "pick:theme:export") { vm.themeExporter = true }
                        Note(
                            when (kind) {
                                "theme" -> "Eclipse themes, or any zipped theme. I'll try, but no promises."
                                "icons" -> "Any zip with pictures named after systems (snes.png, psx.jpg…). If it has several folders of them, you choose which one."
                                "logos" -> "Any zip with system logos named after systems (snes.png, psx.svg…), like an ES-DE theme's logos folder. Logos show where Eclipse writes a system's name."
                                else -> "Any zip with wide pictures named after systems. If it has several folders of them, you choose which one."
                            }
                        )
                    }
                }
            }
            // ---- right: how it looks ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                val e = entries.firstOrNull { it.id == shown } ?: entries.firstOrNull()
                Column(Modifier.fillMaxSize().menuScroll().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PREVIEW", color = C.accent, fontSize = 8.5.sp, letterSpacing = 2.sp)
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text(if (e?.id == current) "IN USE" else "AS INSTALLED", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.6.sp)
                    }
                    Box(
                        Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(753f / 424f).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, C.x(0x2EFFFFFFL), RoundedCornerShape(12.dp))
                    ) { if (e != null) LookPreview(vm, kind, e.id) }
                    if (e != null) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, color = C.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val about = lookAbout(vm, kind, e)
                                if (about.isNotBlank()) Text(about, color = C.sub, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            if (e.deletable) Pill("Delete", Modifier.padding(start = 8.dp).width(96.dp)) {
                                if (kind == "theme") vm.themes.firstOrNull { it.id == e.id }?.let { vm.deleteTheme(it) }
                                else (when (kind) { "icons" -> vm.iconPacks; "logos" -> vm.logoPacks; else -> vm.backdropPacks }).firstOrNull { "pack:${it.id}" == e.id }?.let { vm.deletePack(it) }
                                shown = current
                            }
                            Pill(if (e.id == current) "In use" else "Use", Modifier.padding(start = 8.dp).width(110.dp), primary = e.id != current) { use(e.id) }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            LookHint("A", "Use"); LookHint("B", "Done")
        }
    }
}

/** A line under the preview: author, description, what it includes. */
private fun lookAbout(vm: EclipseVM, kind: String, e: LookEntry): String {
    if (kind != "theme") return e.sub
    val t = vm.themes.firstOrNull { it.id == e.id } ?: return "Black and gold, with no pictures of its own."
    val bits = listOfNotNull(
        t.platforms.size.takeIf { it > 0 }?.let { "$it icons" },
        t.backgrounds.size.takeIf { it > 0 }?.let { "$it backdrops" },
        t.dock.size.takeIf { it > 0 }?.let { "dock icons" },
        t.font?.let { "a font" },
        if (t.wallpaper != null || t.bottom != null) "wallpapers" else null
    )
    return listOfNotNull(
        t.author.takeIf { it.isNotBlank() }?.let { "by $it" },
        t.description.takeIf { it.isNotBlank() },
        bits.takeIf { it.isNotEmpty() }?.let { "Includes " + it.joinToString(", ") }
    ).joinToString(" · ")
}

@Composable
private fun LookHint(key: String, label: String) = ButtonHint(key, label)

/** One installed theme or pack: a radio dot, its name and a line under it; gold edge when it's the one in use. */
@Composable
private fun LookRow(e: LookEntry, active: Boolean, fr: FocusRequester?, key: String, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    FocusBox(
        Modifier.fillMaxWidth().heightIn(min = 38.dp), shape = shape, onClick = onClick, focusRequester = fr, focusKey = key,
        onFocusChange = { f -> if (f) onFocus() }
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 38.dp)
                .background(if (active) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (active) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (active) C.accent else C.sub, CircleShape), contentAlignment = Alignment.Center) {
                if (active) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent))
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(e.name.uppercase(), color = if (active) C.ink else C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.sub.isNotBlank()) Text(e.sub, color = C.sub, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun LookAction(label: String, note: String, key: String, onClick: () -> Unit) {
    SetRow(focusKey = key, onClick = onClick) {
        Text("+", color = C.accent, fontSize = 13.sp, modifier = Modifier.width(16.dp))
        Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        Text(note, color = C.sub, fontSize = 9.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- the miniature

/** What one choice brings: wallpaper, icons, backdrops, dock icons and the two colours. */
private class LookParts(
    val wallpaper: Any?, val icons: Map<String, File>?, val backdrops: Map<String, File>?,
    val dock: Map<String, File>, val accent: Color, val focus: Color,
    /** the highlighted system's logo, by system id ("" key = none chosen: Eclipse's own) */
    val logo: ((String) -> Any?)? = null,
)

@Composable
private fun lookParts(vm: EclipseVM, kind: String, id: String): LookParts {
    val curWall = vm.wallpaperTop()
    return when (kind) {
        "theme" -> {
            val t = vm.themes.firstOrNull { it.id == id }
            LookParts(
                if (vm.wallTop.isNotEmpty()) vm.wallTop else vm.themeImage(t?.wallpaper) ?: if (t == null) vm.defaultWallTop else null,
                t?.platforms, t?.backgrounds, t?.dock.orEmpty(),
                t?.accent?.let { Color(it) } ?: ThemeColors.DefaultAccent, t?.focus?.let { Color(it) } ?: ThemeColors.DefaultFocus
            )
        }
        "logos" -> {
            val logos = when {
                id.startsWith("pack:") -> vm.logoPacks.firstOrNull { "pack:${it.id}" == id }?.images
                id.startsWith("theme:") -> vm.themes.firstOrNull { "theme:${it.id}" == id }?.logos
                else -> vm.theme?.logos?.takeIf { it.isNotEmpty() }
            }
            LookParts(curWall, vm.iconSource(), vm.backdropSource(), vm.theme?.dock.orEmpty(), C.accent, C.focus,
                logo = { sys -> logos?.get(sys) ?: com.eclipse.launcher.data.SystemArt.logo(sys, "dark") })
        }
        "icons" -> {
            val icons = when {
                id.startsWith("pack:") -> vm.iconPacks.firstOrNull { "pack:${it.id}" == id }?.images
                id.startsWith("theme:") -> vm.themes.firstOrNull { "theme:${it.id}" == id }?.platforms
                else -> vm.defaultIconPack()?.images
            }
            LookParts(curWall, icons, vm.backdropSource(), vm.theme?.dock.orEmpty(), C.accent, C.focus)
        }
        else -> {
            val bgs = when {
                id.startsWith("pack:") -> vm.backdropPacks.firstOrNull { "pack:${it.id}" == id }?.images
                id.startsWith("theme:") -> vm.themes.firstOrNull { "theme:${it.id}" == id }?.backgrounds
                else -> vm.theme?.backgrounds
            }
            LookParts(curWall, vm.iconSource(), bgs, vm.theme?.dock.orEmpty(), C.accent, C.focus)
        }
    }
}

/**
 * Home's systems row drawn at the Thor's real size (753 × 424 dp) and scaled down: the wallpaper (or the highlighted
 * system's backdrop), the dock, and five system tiles with the first one highlighted.
 */
@Composable
private fun LookPreview(vm: EclipseVM, kind: String, id: String) {
    val parts = lookParts(vm, kind, id)
    val plats: List<PlatformDef> = remember(vm.platCounts) {
        val counts = vm.platCounts
        val mine = vm.orderPlats(Platforms.all.filter { vm.showsPlat(it.id, counts) && it.id != Platforms.ALL && it.id != Platforms.FAVORITES })
        (mine + listOf("snes", "psx", "gba", "n64", "megadrive", "nes", "ps2").mapNotNull { Platforms.byId(it) }).distinctBy { it.id }.take(5)
    }
    val counts = vm.platCounts
    val sel = plats.firstOrNull()
    // the backdrops picker leads with a system that has a backdrop, so there's something to see
    val hero = if (kind == "backdrops") plats.firstOrNull { parts.backdrops?.containsKey(it.id) == true } ?: sel else sel
    val bg: Any? = hero?.let { parts.backdrops?.get(it.id) } ?: parts.wallpaper
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val s = maxWidth / 753.dp
        Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(753.dp, 424.dp)
                .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
                .background(HomeDark)
        ) {
            if (bg != null) AsyncImage(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1A1A20), Color(0xFF0B0B0D)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x99000000)))))
            // the dock
            Column(
                Modifier.width(58.dp).fillMaxHeight().background(Color(0x8C0A0A0C)).padding(top = 70.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                listOf(
                    "home" to Icons.Filled.Home, "games" to Icons.Filled.SportsEsports, "romm" to Icons.Filled.Cloud,
                    "apps" to Icons.Filled.Apps, "settings" to Icons.Filled.Settings
                ).forEach { (k, icon) -> DockMini(parts.dock[k], icon, k == "games", parts.accent) }
            }
            // the highlighted system
            Column(Modifier.padding(start = 86.dp, top = 30.dp)) {
                // the logos picker shows the system's logo here, as Home's title does
                val heroLogo = hero?.let { h -> parts.logo?.invoke(h.id) }
                if (heroLogo != null) AsyncImage(heroLogo, hero.name, Modifier.height(48.dp).widthIn(max = 360.dp), contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
                else Text((hero?.name ?: "Super Nintendo"), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val n = hero?.let { counts[it.id] } ?: 0
                Text(if (n > 0) "$n GAMES" else "SYSTEMS", color = Color(0xFFD6D2C9), fontSize = 10.sp, letterSpacing = 1.8.sp, modifier = Modifier.padding(top = 4.dp))
            }
            CornerClock(vm, Modifier.align(Alignment.TopEnd))
            // the tiles
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 86.dp, bottom = 46.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom
            ) {
                plats.forEach { p ->
                    val big = p.id == hero?.id
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.size(if (big) 150.dp else 112.dp).clip(shape).background(Color(0xFF26262C))
                            .then(if (big) Modifier.border(3.dp, parts.focus, shape) else Modifier.border(1.dp, Color(0x24FFFFFF), shape)),
                        contentAlignment = Alignment.Center
                    ) {
                        val img = parts.icons?.get(p.id)
                        if (img != null) AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(p.short, color = Color(0xFFE6E3DA), fontSize = if (big) 20.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 86.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(17.dp).clip(CircleShape).background(parts.accent), contentAlignment = Alignment.Center) {
                    Text("A", color = Color(0xFF111111), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                Text("OPEN", color = Color(0xFFD6D2C9), fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

@Composable
private fun DockMini(img: File?, icon: ImageVector, on: Boolean, accent: Color) {
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(if (on) accent.copy(alpha = 0.22f) else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        if (img != null) AsyncImage(img, null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
        else Icon(icon, null, tint = if (on) accent else Color(0xFFCFCAC0), modifier = Modifier.size(22.dp))
    }
}

/**
 * Export theme (Look & Feel → Theme → Export theme): a name, an author, and which pictures to take along. The zip
 * goes to Eclipse/export; colours, glass, wallpapers and the theme's dock icons always go in.
 */
@Composable
fun ThemeExportSheet(vm: EclipseVM) = Glass {
    val close = { vm.themeExporter = false }
    BackHandler { close() }
    var name by remember { mutableStateOf(vm.theme?.name?.let { "$it (mine)" } ?: "My theme") }
    var author by remember { mutableStateOf(vm.theme?.author.orEmpty()) }
    var icons by remember { mutableStateOf(true) }
    var backdrops by remember { mutableStateOf(true) }
    var logos by remember { mutableStateOf(true) }
    var font by remember { mutableStateOf(true) }
    val first = remember { FocusRequester() }
    MenuSheet("Export theme", "Your look as a theme", "Colours, glass and wallpapers always go in. Saved to Eclipse/export.", onDismiss = close) {
        // two columns so it fits the top screen: who made it on the left, what goes in on the right
        Row(
            Modifier.heightIn(max = 300.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("THEME NAME", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp)
                TextBox(name, Modifier.fillMaxWidth(), placeholder = "My theme", focusKey = "export:name") { name = it }
                Text("AUTHOR", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 6.dp))
                TextBox(author, Modifier.fillMaxWidth(), placeholder = "Your name", focusKey = "export:author") { author = it }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("INCLUDE", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp)
                MenuRow("Icons", value = if (icons) "Yes" else "No", selected = icons, fr = first) { icons = !icons }
                MenuRow("Backdrops", value = if (backdrops) "Yes" else "No", selected = backdrops) { backdrops = !backdrops }
                MenuRow("Logos", value = if (logos) "Yes" else "No", selected = logos) { logos = !logos }
                MenuRow("Font", value = if (font) "Yes" else "No", selected = font, note = "if it's a file") { font = !font }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { MenuRow("Cancel") { close() } }
            Box(Modifier.weight(1f)) { MenuRow("Export", gold = true, enabled = name.isNotBlank()) { vm.exportTheme(name, author, icons, backdrops, logos, font) } }
        }
    }
    FocusOnShow(first)
}
