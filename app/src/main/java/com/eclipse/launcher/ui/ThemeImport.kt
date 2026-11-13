package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.ThemeConvert
import com.eclipse.launcher.data.Themes

/*
 * The theme import screen, for themes made for other frontends (ES-DE, Cocoon …): every folder in the zip with
 * pictures named after systems, on the left, each set to Icons, Logos, Backdrops or Skip (Eclipse's guess to start
 * with); the highlighted folder's pictures on the right. Import converts the theme with those choices; B cancels.
 */

private val ROLES = listOf(
    ThemeConvert.ICONS to "Icons",
    ThemeConvert.LOGOS to "Logos",
    ThemeConvert.BACKDROPS to "Backdrops",
    ThemeConvert.SKIP to "Skip",
)

private fun roleAbout(role: String): String = when (role) {
    ThemeConvert.ICONS -> "Icons: shown as each system's tile on Home and in the system lists."
    ThemeConvert.LOGOS -> "Logos: shown where Eclipse writes a system's name (Settings → Look → Logos), and drawn onto a tile for systems without an icon."
    ThemeConvert.BACKDROPS -> "Backdrops: shown behind a system's games, full screen."
    else -> "Skip: this folder isn't used."
}

@Composable
fun ThemeImportScreen(vm: EclipseVM, p: Themes.Pending) = Glass {
    BackHandler { vm.cancelThemeImport() }
    val roles = remember(p) { mutableStateMapOf<String, String>().apply { p.folders.forEach { put(it.rel, it.guess) } } }
    var open by remember(p) { mutableStateOf<String?>(null) }
    var shown by remember(p) { mutableStateOf(p.folders.first()) }
    val first = remember { FocusRequester() }
    FocusOnShow(first)

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: the folders and what each one is ----
            GlassPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Text("IMPORT THEME", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text(p.name, color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "This theme was made for another frontend. Pick what each folder holds; Eclipse has made a guess.",
                        color = C.sub, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Column(Modifier.weight(1f).fillMaxWidth().menuScroll(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        p.folders.forEachIndexed { i, f ->
                            val role = roles[f.rel] ?: f.guess
                            FolderRow(f, role, open == f.rel, if (i == 0) first else null, onFocus = { shown = f }) {
                                open = if (open == f.rel) null else f.rel
                            }
                            AnimatedVisibility(open == f.rel) {
                                RoleMenu(role) { r -> roles[f.rel] = r; open = null }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Cancel", Modifier.weight(1f)) { vm.cancelThemeImport() }
                        Pill("Import", Modifier.weight(1f), primary = true) { vm.finishThemeImport(roles.toMap()) }
                    }
                }
            }
            // ---- right: the highlighted folder's pictures ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shown.rel.uppercase(), color = C.accent, fontSize = 8.5.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text("${shown.files.size} SYSTEMS", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.4.sp)
                    }
                    Text(roleAbout(roles[shown.rel] ?: shown.guess), color = C.sub, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    val wide = (roles[shown.rel] ?: shown.guess) != ThemeConvert.ICONS
                    LazyVerticalGrid(
                        GridCells.Fixed(if (wide) 3 else 5), Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown.files.entries.toList().sortedBy { it.key }.take(60), key = { it.key }) { (id, file) ->
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f).clip(RoundedCornerShape(8.dp))
                                        .background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(8.dp))
                                ) { AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                Text(
                                    Platforms.byId(id)?.short ?: id, color = C.sub, fontSize = 8.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ImportHint("A", "Choose"); ImportHint("B", "Cancel")
        }
    }
}

@Composable
private fun ImportHint(key: String, label: String) = ButtonHint(key, label)

/** One folder: its path, how many systems, a few of its pictures, and its role in gold. */
@Composable
private fun FolderRow(f: ThemeConvert.FolderChoice, role: String, expanded: Boolean, fr: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = if (expanded) RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp) else RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    val skip = role == ThemeConvert.SKIP
    FocusBox(Modifier.fillMaxWidth(), shape = shape, onClick = onClick, focusRequester = fr, focusKey = "imp:${f.rel}", onFocusChange = { fo -> if (fo) onFocus() }) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (expanded) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (expanded) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // three of its pictures
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                f.files.values.take(3).forEach { img ->
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(C.x(0x1AFFFFFFL))) {
                        AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(f.rel, color = if (skip) C.sub else C.ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.files.size} systems", color = C.sub, fontSize = 8.5.sp)
            }
            Text(
                (ROLES.firstOrNull { it.first == role }?.second ?: "Skip").uppercase(),
                color = if (skip) C.sub else C.accent, fontSize = 8.5.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 6.dp)
            )
            Text(if (expanded) "⌃" else "⌄", color = C.sub, fontSize = 10.sp, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

/** The four roles, as radio choices under an open folder row. */
@Composable
private fun RoleMenu(current: String, onPick: (String) -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)
    Column(
        Modifier.fillMaxWidth().offset(y = (-5).dp).clip(shape)
            .background(if (ThemeColors.light.value) Color(0xBFFFFFFF) else C.x(0x80100F0EL))
            .border(1.dp, C.accent.copy(alpha = 0.3f), shape).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ROLES.forEach { (id, label) ->
            val sel = id == current
            FocusBox(Modifier.fillMaxWidth().height(28.dp), shape = RoundedCornerShape(7.dp), onClick = { onPick(id) }, focusKey = "role:$id") {
                Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (sel) C.accent else C.sub, CircleShape), contentAlignment = Alignment.Center) {
                        if (sel) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent))
                    }
                    Text(label.uppercase(), color = if (sel) C.ink else C.x(0xFFB9B4AAL), fontSize = 8.5.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 9.dp))
                }
            }
        }
    }
}

/*
 * Importing an icon or backdrop pack from a zip with several folders of pictures named after systems (a theme made
 * for another frontend, say). Left: every such folder, A to pick it, and whether it goes in as icons, backdrops or logos.
 * Right: the highlighted folder's pictures. Import makes a pack of the picked folder; B cancels.
 */
@Composable
fun PackImportScreen(vm: EclipseVM, p: com.eclipse.launcher.data.Packs.Pending) = Glass {
    BackHandler { vm.cancelPackImport() }
    // start on the folder that suits what you're importing (the theme import's guess), else the fullest
    val start = remember(p) {
        val want = when (p.kind) { com.eclipse.launcher.data.Packs.ICONS -> ThemeConvert.ICONS; com.eclipse.launcher.data.Packs.LOGOS -> ThemeConvert.LOGOS; else -> ThemeConvert.BACKDROPS }
        p.folders.firstOrNull { it.guess == want } ?: p.folders.first()
    }
    var picked by remember(p) { mutableStateOf(start) }
    var shown by remember(p) { mutableStateOf(start) }
    var kind by remember(p) { mutableStateOf(p.kind) }
    val first = remember { FocusRequester() }
    FocusOnShow(first)

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPanel(Modifier.width(330.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Text(when (kind) { com.eclipse.launcher.data.Packs.ICONS -> "IMPORT ICONS"; com.eclipse.launcher.data.Packs.LOGOS -> "IMPORT LOGOS"; else -> "IMPORT BACKDROPS" }, color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text(p.name, color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "This zip has ${p.folders.size} folders of pictures named after systems. Pick the one to import.",
                        color = C.sub, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Column(Modifier.weight(1f).fillMaxWidth().menuScroll().stayInside(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        DropSection("Folders")
                        p.folders.forEach { f ->
                            PackFolderRow(f, f == picked, if (f == start) first else null, onFocus = { shown = f }) { picked = f; shown = f }
                        }
                        DropSection("Import as")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Pill("Icons", Modifier.weight(1f), primary = kind == com.eclipse.launcher.data.Packs.ICONS) { kind = com.eclipse.launcher.data.Packs.ICONS }
                            Pill("Backdrops", Modifier.weight(1f), primary = kind == com.eclipse.launcher.data.Packs.BACKDROPS) { kind = com.eclipse.launcher.data.Packs.BACKDROPS }
                            Pill("Logos", Modifier.weight(1f), primary = kind == com.eclipse.launcher.data.Packs.LOGOS) { kind = com.eclipse.launcher.data.Packs.LOGOS }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Cancel", Modifier.weight(1f)) { vm.cancelPackImport() }
                        Pill("Import", Modifier.weight(1f), primary = true) { vm.finishPackImport(picked, kind) }
                    }
                }
            }
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shown.rel.uppercase(), color = C.accent, fontSize = 8.5.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text("${shown.files.size} SYSTEMS" + if (shown.others.isNotEmpty()) " + ${shown.others.size} FOR LATER" else "", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.4.sp)
                    }
                    val wide = kind == com.eclipse.launcher.data.Packs.BACKDROPS
                    val logo = kind == com.eclipse.launcher.data.Packs.LOGOS
                    LazyVerticalGrid(
                        GridCells.Fixed(if (wide || logo) 3 else 5), Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown.files.entries.toList().sortedBy { it.key }, key = { it.key }) { (id, file) ->
                            Column {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else if (logo) 2.4f else 1f).clip(RoundedCornerShape(8.dp))
                                        .background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(8.dp))
                                ) { AsyncImage(file, null, Modifier.fillMaxSize(), contentScale = if (wide) ContentScale.Crop else ContentScale.Fit) }
                                Text(
                                    Platforms.byId(id)?.short ?: id, color = C.sub, fontSize = 8.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ImportHint("A", "Pick"); ImportHint("B", "Cancel")
        }
    }
}

/** One folder of the zip: a radio dot, three of its pictures, its path and how many systems. */
@Composable
private fun PackFolderRow(f: ThemeConvert.FolderChoice, picked: Boolean, fr: FocusRequester?, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    FocusBox(Modifier.fillMaxWidth(), shape = shape, onClick = onClick, focusRequester = fr, focusKey = "pimp:${f.rel}", onFocusChange = { fo -> if (fo) onFocus() }) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (picked) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (picked) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (picked) C.accent else C.sub, CircleShape), contentAlignment = Alignment.Center) {
                if (picked) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent))
            }
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                f.files.values.take(3).forEach { img ->
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(C.x(0x1AFFFFFFL))) {
                        AsyncImage(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(f.rel, color = C.ink, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.files.size} systems", color = C.sub, fontSize = 8.5.sp)
            }
        }
    }
}
