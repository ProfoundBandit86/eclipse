package com.eclipse.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.*

/**
 * A game's page (Start / Select on a game): on the left the box art (A on it opens the artwork picker), play stats and
 * the system / RomM badges; on the right its logo, the actions (Play, emulator, favourite, achievements, On Deck) as the right dock's rows, and its details in
 * boxes like Settings. Y (or Edit) lets you type into them; Save keeps it (and can send it to RomM). B goes back to
 * the game it came from. It flips out of the cover and back into it.
 */
@Composable
fun GameMenu(vm: EclipseVM, g: Game) = Glass {
    var ra by remember(g.id) { mutableStateOf<RaProgress?>(null) }
    var raOpen by remember(g.id) { mutableStateOf(false) }
    // Advanced: the extra actions, and the panel on the right turns over from About to the file's details
    var advanced by remember(g.id) { mutableStateOf(false) }
    // multi-disc games: the discs, and the open drop-downs (which disc starts, which screen)
    val discs = remember(g.id, g.path) { vm.discsOf(g) }
    var discOpen by remember(g.id) { mutableStateOf(false) }
    var screenOpen by remember(g.id) { mutableStateOf(false) }
    val discFr = remember { FocusRequester() }
    val discPickFr = remember { FocusRequester() }
    val screenFr = remember { FocusRequester() }
    // opening a drop-down puts the controller on its chosen line; closing it, back on its row
    // (the line that had focus disappears, and focus would otherwise fall through to the screen underneath)
    var discUsed by remember(g.id) { mutableStateOf(false) }
    var screenUsed by remember(g.id) { mutableStateOf(false) }
    LaunchedEffect(discOpen) {
        if (discOpen) discUsed = true
        if (!discUsed) return@LaunchedEffect // (not on opening the page: Play keeps the controller)
        withFrameNanos { }; runCatching { if (discOpen) discPickFr.requestFocus() else discFr.requestFocus() }
    }
    val screenPickFr = remember { FocusRequester() }
    LaunchedEffect(screenOpen) {
        if (screenOpen) { screenUsed = true; withFrameNanos { }; runCatching { screenPickFr.requestFocus() } }
        else if (screenUsed) { withFrameNanos { }; runCatching { screenFr.requestFocus() } }
    }
    var editing by remember(g.id) { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    // the tabs: Overview, Details, Achievements, Artwork, Options (L1 / R1)
    // (kept in the view model, so L1 / R1 change it straight from the key handler: no hook to go missing)
    var tab by vm::menuTab
    // the description, read in a pop-up over the page
    var descOpen by remember(g.id) { mutableStateOf(false) }
    val tabFr = remember { FocusRequester() }
    SideEffect { vm.menuEditing = editing }
    DisposableEffect(g.id) { onDispose { vm.menuEditing = false } }
    // a new tab: the controller on its first thing
    var tabUsed by remember(g.id) { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab != 0) tabUsed = true
        if (!tabUsed) return@LaunchedEffect
        withFrameNanos { }; withFrameNanos { }
        runCatching { if (tab == 0) playFocus.requestFocus() else tabFr.requestFocus() }
    }
    // RetroAchievements: the bar under About; selecting it turns the panel over (top to bottom) to the badges
    val raBarFr = remember { FocusRequester() }
    val raFirstFr = remember { FocusRequester() }
    val raDeg by androidx.compose.animation.core.animateFloatAsState(if (raOpen) 180f else 0f, tween(450), label = "raFlip")
    var raUsed by remember(g.id) { mutableStateOf(false) }
    LaunchedEffect(raOpen) {
        if (raOpen) raUsed = true
        if (!raUsed) return@LaunchedEffect
        // once the side it's going to is showing, the controller goes there (the badges, or back on the bar)
        snapshotFlow { raDeg }.first { if (raOpen) it > 90f else it <= 90f }
        withFrameNanos { }
        runCatching { if (raOpen) raFirstFr.requestFocus() else if (!advanced && !editing) raBarFr.requestFocus() }
    }
    // a CHD made from this page shows what it saved until the page closes or Advanced is pressed
    DisposableEffect(g.id) { onDispose { vm.chdSavedFor.remove(g.id) } }
    // the details as typed (start from what's known; reset when you cancel)
    fun known() = listOf(vm.gameDetail(g, "t"), vm.gameDetail(g, "y"), vm.gameDetail(g, "g"), vm.gameDetail(g, "d"), vm.gameDetail(g, "p"), vm.descOf(g) ?: "", vm.gameDetail(g, "pub"), vm.gameDetail(g, "r"))
    val fields = remember(g.id) { mutableStateListOf(*known().toTypedArray()) }
    val rid = vm.rommIdFor(g) ?: g.id.removePrefix("rm:").takeIf { g.id.startsWith("rm:") }
    var toRomm by remember(g.id) { mutableStateOf(rid != null) }

    LaunchedEffect(g.id) {
        ra = vm.loadRa(g)
        if (vm.descOf(g) == null) { vm.ensureDesc(g); if (!editing) fields[5] = vm.descOf(g) ?: "" }
        // year, genre, developer, publisher, players and rating from the databases, once
        if (vm.gameInfo[g.id] == null) {
            vm.ensureInfo(g)
            if (!editing) known().forEachIndexed { i, v -> if (i != 0 && i != 5) fields[i] = v }
        }
    }
    // focus Play on open, and again when coming back from the artwork screen
    var matching by remember(g.id) { mutableStateOf(false) }
    // (the RomM "download / play / stream?" popup and the CHD / share pop-ups take the controller from the page)
    val menuActive = vm.artGame == null && vm.emuMenu == null && !matching && vm.rommAsk == null
    // (back from the emulator list or Match by hand on another tab: the controller goes back to that tab, not to Play,
    // which isn't on screen there, and the page would be left with nothing focused)
    LaunchedEffect(menuActive, g.id) {
        if (menuActive) { withFrameNanos { }; withFrameNanos { }; runCatching { if (tab == 0) playFocus.requestFocus() else tabFr.requestFocus() } }
    }

    // Card flip: the cover lifts out of its spot, grows to fill the screen and turns over; the page is on its back.
    // B plays it in reverse, back into the cover's spot.
    val src = remember(g.id) { vm.artBounds[g.id] }
    // (LB / RB to the next game: no flip, the page just changes)
    val flip = remember(g.id) { Animatable(if (vm.menuStepped) 1f else 0f) }
    var closing by remember(g.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(g.id) { vm.menuStepped = false; flip.animateTo(1f, tween(560, easing = CubicBezierEasing(0.3f, 1.12f, 0.5f, 1f))) }
    /** Closes the page; the controller goes back to this game (never the left dock). */
    fun close() {
        GamepadFocus.lastKey = "g:${g.id}"
        if (vm.menuGame?.id == g.id) vm.menuGame = null
    }
    fun save() {
        vm.saveGameDetails(g, fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], toRomm && rid != null, fields[6], fields[7])
        editing = false
        GamepadFocus.restoreKey = "page:edit"
    }
    fun cancelEdit() { known().forEachIndexed { i, v -> fields[i] = v }; editing = false; GamepadFocus.restoreKey = "page:edit" }
    // editing: the controller goes straight to the Title box (the boxes only appear once editing starts)
    fun startEdit() { editing = true; tab = 1; GamepadFocus.restoreKey = "detail:Title" }
    BackHandler(enabled = editing) { cancelEdit() }
    BackHandler(enabled = !editing && !raOpen && !descOpen && vm.artGame == null && !closing) {
        closing = true
        scope.launch {
            flip.animateTo(0f, tween(420, easing = CubicBezierEasing(0.5f, 0f, 0.6f, 1f)))
            close()
        }
    }
    // B on the achievements turns the panel back to About
    BackHandler(enabled = raOpen && !editing && vm.artGame == null) { raOpen = false }
    LaunchedEffect(advanced, editing) { if (advanced || editing) raOpen = false }
    // Y: edit (or save while editing); X: save. Whatever the screen behind set is put back afterwards.
    DisposableEffect(g.id) {
        val prevX = GamepadFocus.buttonX; val prevY = GamepadFocus.buttonY
        val y: () -> Unit = { if (editing) save() else startEdit() }
        val x: () -> Unit = { if (editing) save() }
        GamepadFocus.buttonY = y; GamepadFocus.buttonX = x
        onDispose {
            if (GamepadFocus.buttonY === y) GamepadFocus.buttonY = prevY
            if (GamepadFocus.buttonX === x) GamepadFocus.buttonX = prevX
        }
    }
    val dens = LocalDensity.current.density
    val origin = Offset.Zero // menus are drawn over the whole window, from its top-left corner
    val rom = vm.rommIdFor(g)?.toIntOrNull()?.let { vm.rommById[it] }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val area = IntSize(constraints.maxWidth, constraints.maxHeight)
    val pageH = maxHeight
    val pageW = maxWidth
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = flip.value.coerceIn(0f, 1f) }.background(Color(0x8C000000)))
    Box(
        Modifier.fillMaxSize()
            .flipLayer({ flip.value }, src, origin, area, back = true, dens)
            .background(HomeDark).pointerInput(Unit) { detectTapGestures { } }
            // (the trap lets go while the artwork screen or the emulator list is open over it, or they can't take the controller)
            .then(if (menuActive) Modifier.focusTrap() else Modifier)
    ) {
        // ---- the screenshot / backdrop fills the page; from the middle down a band of the theme's glass ----
        val shot = vm.artOf(g, ArtKind.BACKDROP) ?: vm.artOf(g, ArtKind.HERO) ?: vm.wallpaperTop()
        val light = ThemeColors.light.value
        SteadyImage(shot, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0x8C0B0B0D), 0.5f to Color(0x1A0B0B0D), 1f to Color(0x330B0B0D))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f).clipToBounds()) {
            // the same picture, blurred, lined up with the one behind (frosted glass)
            if (!ThemeColors.lowPower.value && android.os.Build.VERSION.SDK_INT >= 31) SteadyImage(
                shot, Modifier.wrapContentSize(Alignment.BottomCenter, unbounded = true).requiredSize(pageW, pageH).blur(24.dp)
            )
            Box(Modifier.fillMaxSize().background(glassTint(light)))
            // a little light along the top edge, then the edge itself
            Box(Modifier.fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x29FFFFFF)))
        }
        val coverW = (pageW * 0.235f).coerceIn(150.dp, 260.dp)
        val rightX = 26.dp + coverW + 28.dp
        val btnW = 200.dp
        val topY = 66.dp // under the tabs
        // ---- the tabs along the top: L1 / R1 (or a tap) move between them ----
        val tabNames = listOf("Overview", "Details", "Achievements", "Artwork", "Options")
        Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 16.dp).height(34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ButtonBadge("L1")
            tabNames.forEachIndexed { i, name ->
                val on = tab == i
                val shape = RoundedCornerShape(50)
                Row(
                    Modifier.height(30.dp).clip(shape)
                        .background(if (on) androidx.compose.ui.graphics.lerp(cardGlass(), C.accent, 0.25f) else cardGlass())
                        .border(1.dp, if (on) C.accent.copy(alpha = 0.7f) else C.x(0x1FFFFFFFL), shape)
                        .pointerInput(i) { detectTapGestures { tab = i } }
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name.uppercase(), color = if (on) Color.White else C.x(0xFFE6E3DAL), fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
                    if (i == 2) ra?.let { r -> Text("${r.earned}/${r.total}", color = C.accent, fontSize = 8.5.sp, modifier = Modifier.padding(start = 7.dp), maxLines = 1) }
                }
            }
            ButtonBadge("R1")
            Spacer(Modifier.weight(1f))
            Clock(vm)
        }
        // ---- left on every tab: the big cover (it's the artwork button), the system's logo and where it is, stats and size ----
        Column(Modifier.padding(start = 26.dp, top = topY).width(coverW)) {
            val coverShape = RoundedCornerShape(14.dp)
            FocusBox(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f).shadow(16.dp, coverShape), shape = coverShape, focusKey = "page:art",
                onClick = { vm.artKindStart = ArtKind.BOX; vm.artGame = g }
            ) { focused ->
                Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.fillMaxSize().clip(coverShape))
                if (focused) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) { Text("CHANGE ARTWORK", color = Color.White, fontSize = 8.sp, letterSpacing = 1.6.sp) }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // the system's logo, black / white / colour like the platform cards (its name when it has none)
                val pLogo = com.eclipse.launcher.data.SystemArt.logo(g.platform, cardLook(vm))
                var logoFailed by remember(pLogo) { mutableStateOf(false) }
                if (pLogo != null && !logoFailed) AsyncImage(
                    pLogo, Platforms.byId(g.platform)?.name, Modifier.height(24.dp).widthIn(max = 130.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, onError = { logoFailed = true }
                ) else Text((Platforms.byId(g.platform)?.short ?: g.platform).uppercase(), color = C.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                // where it is: the same size whatever Settings → Theme → Text size says (it has to fit beside the logo)
                FixedTextSize {
                    val onRomm = rom != null || vm.isRommEntry(g)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(C.x(0x10FFFFFFL)).border(1.dp, C.x(0x2EFFFFFFL), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onRomm) Icon(Icons.Filled.Cloud, null, tint = C.x(0xFFCFCAC0L), modifier = Modifier.size(10.dp))
                        Text(
                            if (vm.isRommEntry(g)) "ROMM" else if (onRomm) "LOCAL \u00b7 ROMM" else "LOCAL", color = C.x(0xFFCFCAC0L), fontSize = 8.5.sp, letterSpacing = 1.3.sp,
                            maxLines = 1, softWrap = false, modifier = Modifier.padding(start = if (onRomm) 4.dp else 0.dp)
                        )
                    }
                }
            }
            PlayStats(vm, g, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        // ---- the tab itself, right of the cover ----
        Box(Modifier.fillMaxSize().padding(start = rightX, end = 26.dp, top = topY, bottom = 20.dp + 24.dp)) {
            when (tab) {
                0 -> {
                    // Overview: logo, the details line, Play / Disc / Favourite under it, the description beside them from
                    // just under the middle line, RetroAchievements at the bottom
                    Column(Modifier.fillMaxSize()) {
                        GameHeading(vm, g, logoHeight = 70.dp)
                        val metaLine = listOf(fields[1], fields[2], fields[3].ifBlank { fields[6] }, fields[6].takeIf { it != fields[3] && fields[3].isNotBlank() } ?: "", fields[7].takeIf { it.isNotBlank() }?.let { "\u2605 " + it.substringBefore(" /") } ?: "", fields[4].takeIf { it.isNotBlank() }?.let { "$it players" } ?: "")
                            .filter { it.isNotBlank() }.joinToString("  \u00b7  ")
                        if (metaLine.isNotBlank()) Text(
                            metaLine.uppercase(), color = C.x(0xFFF0EDE6L), fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    val p = ra
                    // from the page's middle line down (in this box's own terms): the buttons, the description beside them
                    val midTop = (pageH * 0.5f - topY + 10.dp).coerceAtLeast(0.dp)
                    Column(Modifier.padding(top = midTop, bottom = if (p != null) 58.dp else 0.dp).fillMaxHeight().width(btnW).stayInside(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Play takes the room the disc choice had (that's under Options now)
                        // a RomM game not on this device: Install only (it doesn't start by itself)
                        if (vm.isRommEntry(g)) PagePill("↓  Install", gold = true, fr = playFocus) { close(); vm.rommOpen(g, "download") }
                        else PagePill("▶  Play", gold = true, fr = playFocus) { close(); vm.play(g) }
                        if (!g.id.startsWith("rm:")) PagePill(if (vm.isFavGame(g)) "♥  Favourite" else "♡  Favourite", on = vm.isFavGame(g)) { vm.toggleFavGame(g) }
                    }
                    Box(Modifier.fillMaxSize().padding(start = btnW + 14.dp, top = midTop, bottom = if (p != null) 58.dp else 0.dp)) {
                        if (fields[5].isNotBlank()) DescriptionPreview(fields[5], Modifier.fillMaxSize()) { descOpen = true }
                        else Text("No description yet. Details \u2192 Rescrape or Match can find one.", color = C.sub, fontSize = 11.sp)
                    }
                    if (p != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()) { RaBar(p, raBarFr) { tab = 2 } }
                }
                1 -> TabPanel {
                    // Details: every field, Edit / Rescrape / Match
                    Column(Modifier.fillMaxSize().menuScroll().stayInside()) {
                        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("DETAILS", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                            Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                            if (editing) {
                                PageChip("\u2713  Save", gold = true, fr = tabFr) { save() }
                                Spacer(Modifier.width(6.dp))
                                PageChip("\u2715  Cancel") { cancelEdit() }
                            } else {
                                PageChip("\u270E  Edit", key = "page:edit", fr = tabFr) { startEdit() }
                                Spacer(Modifier.width(6.dp))
                                PageChip("\u21BB  Rescrape") { vm.rescrapeGame(g) }
                                Spacer(Modifier.width(6.dp))
                                PageChip("\u2315  Match") { matching = true }
                            }
                        }
                        FieldGroup {
                            // (the boxes are fields 0-4 and 6-7; field 5 is the description)
                            listOf("Title" to 0, "Released" to 1, "Genre" to 2, "Developer" to 3, "Publisher" to 6, "Players" to 4, "Rating" to 7).forEach { (label, i) ->
                                DetailRow(label, fields[i], editing) { v -> fields[i] = v }
                            }
                            val file = if (g.path.startsWith("romm:")) "" else g.path.substringAfterLast('/')
                            if (file.isNotEmpty()) DetailRow("File", file, false) { }
                            if (discs.size > 1) discs.forEachIndexed { i, d -> DetailRow("Disc ${i + 1}", d.name, false) { } }
                        }
                        if (editing) {
                            Spacer(Modifier.height(6.dp))
                            TextBox(fields[5], Modifier.fillMaxWidth(), placeholder = "Description", singleLine = false, fontSize = 11.sp) { v -> fields[5] = v }
                        }
                        if (editing && rid != null) Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("ALSO SAVE TO ROMM", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                            FocusBox(Modifier.size(34.dp, 20.dp), shape = RoundedCornerShape(10.dp), onClick = { toRomm = !toRomm }) {
                                Box(Modifier.fillMaxSize().background(if (toRomm) C.accent else C.x(0x2BFFFFFFL)), contentAlignment = if (toRomm) Alignment.CenterEnd else Alignment.CenterStart) {
                                    Box(Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(if (toRomm) C.onAccent else C.ink))
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                2 -> TabPanel {
                    val p = ra
                    if (p == null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (vm.isConfigured("ra")) "RetroAchievements doesn't know this game (or it has no achievements)."
                            else "Sign in to RetroAchievements in Settings \u2192 Connections to see this game's achievements.",
                            color = C.sub, fontSize = 12.sp
                        )
                        FocusBox(Modifier.size(1.dp), onClick = { }, focusRequester = tabFr) { }
                    } else Column(Modifier.fillMaxSize().menuScroll().stayInside()) { RaCard(p, true, firstFr = tabFr) { } }
                }
                3 -> TabPanel {
                    // Artwork: each kind of picture; A opens the picker on it
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val kinds = ArtKind.values().toList()
                        items(kinds.size) { i ->
                            val k = kinds[i]
                            val url = vm.artOf(g, k)
                            val shape = RoundedCornerShape(10.dp)
                            FocusBox(Modifier.fillMaxWidth(), shape = shape, onClick = { vm.artKindStart = k; vm.artGame = g }, focusRequester = if (i == 0) tabFr else null) {
                                Column(Modifier.fillMaxWidth().background(C.x(0x0FFFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), shape).padding(8.dp)) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(7.dp)).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                                        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                        else Text("NONE", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp)
                                    }
                                    Text(k.label, color = C.ink, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                                    Text(if (url == null) "Choose one \u203A" else "Change \u203A", color = C.accent, fontSize = 9.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 2.dp))
                                }
                            }
                        }
                    }
                }
                else -> TabPanel {
                    // Options: how it plays, RomM, and the files
                    Column(Modifier.fillMaxSize().menuScroll().stayInside(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        TabSection("Playing")
                        // the controller lands on the first option, whichever it is (Android apps have no Emulator row)
                        var firstFr: FocusRequester? = tabFr
                        fun first(): FocusRequester? = firstFr.also { firstFr = null }
                        val opts = ArrayList<@Composable () -> Unit>()
                        if (g.platform != "android") { val f = first(); opts += { OptRow("Emulator", vm.emuFor(g)?.label ?: "Choose", fr = f) { vm.emuMenu = g.platform } } }
                        if (g.platform != "android" && !vm.isRommEntry(g) && remember { vm.bottomDisplayId() } != null)
                            opts += { OptRow("Starts on", if (vm.gameScreen[g.id] == "bottom") "Bottom screen \u25BE" else "Top screen \u25BE", fr = screenFr) { screenOpen = true } }
                        val rDiscs = remember(g.id, vm.rommCatalog) { vm.discsOfRomm(g) }
                        if (discs.size > 1) opts += {
                            val cur = vm.discPick[g.id]?.let { p -> discs.indexOfFirst { it.absolutePath == p } }?.takeIf { it >= 0 } ?: 0
                            OptRow("Disc", "${cur + 1} of ${discs.size} \u25BE", fr = discFr) { discOpen = true }
                        } else if (rDiscs.size > 1) opts += {
                            val cur = rDiscs.indexOfFirst { it.id == vm.rommDiscPick[rDiscs.first().id] }.takeIf { it >= 0 } ?: 0
                            OptRow("Disc", "${cur + 1} of ${rDiscs.size} \u25BE", fr = discFr) { discOpen = true }
                        }
                        if (g.id in vm.recent) { val f = first(); opts += { OptRow("Remove from Recently Played", "\u203A", fr = f) { vm.removeFromOnDeck(g) } } }
                        if (!g.id.startsWith("rm:")) { val f = first(); opts += { OptRow("Hide game", "\u203A", fr = f) { close(); vm.setHidden(g, true) } } }
                        OptGrid(opts)
                        if (vm.isConfigured("romm")) {
                            TabSection("RomM")
                            val rOpts = ArrayList<@Composable () -> Unit>()
                            if (!g.id.startsWith("rm:") && vm.rommIdFor(g) != null) rOpts += { OptRow("Art & info to RomM", "Send \u203A") { vm.sendToRomm(g) } }
                            vm.uploads[g.id]?.let { up -> rOpts += { OptRow("Uploading", "${(up * 100).toInt()}%") { } } }
                                ?: run { if (rom == null && vm.rommIdFor(g) == null && vm.canUploadToRomm(g)) rOpts += { OptRow("Upload to RomM", "\u203A") { vm.uploadToRomm(g) } } }
                            rOpts += { OptToggle("Sync saves", vm.flag("romm_saves", false)) { vm.setFlag("romm_saves", it) } }
                            OptGrid(rOpts)
                        }
                        TabSection("Files")
                        val fOpts = ArrayList<@Composable () -> Unit>()
                        fOpts += {
                            val j = vm.chdJobFor(g)
                            val saved = vm.chdSavedFor[g.id]
                            when {
                                j != null -> OptRow("Convert to CHD", "${j.phase} ${(j.progress * 100).toInt()}%") { }
                                saved != null -> OptRow("Convert to CHD", "Saved ${vm.fmtBytes(saved)}") { }
                                vm.canConvertToChd(g) -> OptRow("Convert to CHD", "\u203A") { vm.convertGameToChd(g) }
                                else -> OptRow("Convert to CHD", "Not a disc game", muted = true) { }
                            }
                        }
                        run { val f = first(); fOpts += { OptRow("Rescrape", "\u203A", fr = f) { vm.rescrapeGame(g) } } }
                        fOpts += { OptRow("Match by hand", "\u203A") { matching = true } }
                        if (rom != null && vm.installedRom(rom.id)?.id == g.id) fOpts += { OptRow("Delete install", "\u203A", danger = true) { close(); vm.storeDelete = rom.id } }
                        OptGrid(fOpts)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        // the drop-downs of the action pills, as small menus over the page
        if (discOpen) {
            val rDiscs = vm.discsOfRomm(g)
            MenuSheet("Disc", g.name, "Which disc Play starts", onDismiss = { discOpen = false }) {
                if (discs.size > 1) {
                    val cur = vm.discPick[g.id]?.let { p -> discs.indexOfFirst { it.absolutePath == p } }?.takeIf { it >= 0 } ?: 0
                    discs.forEachIndexed { i, d ->
                        MenuRow("Disc ${i + 1}", value = d.extension.uppercase(), selected = i == cur, fr = if (i == cur) discPickFr else null) {
                            vm.chooseDisc(g, if (i == 0) null else d.absolutePath); discOpen = false
                        }
                    }
                } else if (rDiscs.size > 1) {
                    val head = rDiscs.first().id
                    val cur = rDiscs.indexOfFirst { it.id == vm.rommDiscPick[head] }.takeIf { it >= 0 } ?: 0
                    rDiscs.forEachIndexed { i, d ->
                        MenuRow("Disc ${i + 1}", value = vm.fmtBytes(d.sizeBytes), selected = i == cur, fr = if (i == cur) discPickFr else null) {
                            vm.rommDiscPick = vm.rommDiscPick + (head to d.id); discOpen = false
                        }
                    }
                }
            }
        }
        if (descOpen) DescriptionPopup(g.name, fields[5]) { descOpen = false; GamepadFocus.restoreKey = "page:desc" }
        if (screenOpen) {
            val bottom = vm.gameScreen[g.id] == "bottom"
            MenuSheet("Screen", g.name, "Which screen the game starts on", onDismiss = { screenOpen = false }) {
                listOf("top" to "Top screen", "bottom" to "Bottom screen").forEach { (k, label) ->
                    val on = (k == "bottom") == bottom
                    MenuRow(label, selected = on, fr = if (on) screenPickFr else null) { vm.chooseGameScreen(g, k); screenOpen = false }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PageHint("A", "Choose")
            if (editing) { PageHint("X", "Save"); PageHint("B", "Cancel") } else { PageHint("Y", "Edit"); PageHint("B", "Back") }
        }
    }
    // the front of the card: the cover, stretched to the screen and scaled back down, so at the start it sits exactly on the tile
    Box(Modifier.fillMaxSize().flipLayer({ flip.value }, src, origin, area, back = false, dens).background(placeholderColor(g.name))) {
        vm.artOf(g, ArtKind.BOX)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
    }
    if (matching) MatchSheet(vm, g) { matching = false }
}

/** One of the game page's action pills: its name (and current choice in gold), Play in solid gold. */
@Composable
private fun PagePill(label: String, value: String? = null, gold: Boolean = false, on: Boolean = false, danger: Boolean = false, fr: FocusRequester? = null, height: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (height > 40.dp) 14.dp else 10.dp)
    val rose = Color(0xFFF08AA0)
    FocusBox(Modifier.fillMaxWidth().height(height), shape = shape, onClick = onClick, focusRequester = fr) {
        Row(
            Modifier.fillMaxSize()
                .background(when { gold -> C.accent; on -> C.accent.copy(alpha = 0.18f); danger -> rose.copy(alpha = 0.14f); else -> androidx.compose.ui.graphics.lerp(cardGlass(), Color.White, 0.06f) })
                .border(1.dp, when { gold -> C.accent; on -> C.accent.copy(alpha = 0.5f); danger -> rose.copy(alpha = 0.35f); else -> C.x(0x1AFFFFFFL) }, shape)
                .padding(horizontal = if (gold) 20.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label.uppercase(), color = if (gold) C.onAccent else if (danger) rose else C.x(0xFFE6E3DAL), fontSize = if (height > 40.dp) 15.sp else 10.sp, letterSpacing = 1.5.sp,
                fontWeight = if (gold) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f)
            )
            if (value != null) Text(value.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** The game page's description: as many lines as fit and "A Read more"; A (or a tap) opens it in a pop-up. */
@Composable
private fun DescriptionPreview(text: String, modifier: Modifier, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    FocusBox(modifier, shape = shape, onClick = onOpen, focusKey = "page:desc") {
        Column(
            Modifier.fillMaxSize().background(cardGlass()).border(1.dp, C.x(0x1AFFFFFFL), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text, color = C.x(0xFFE6E3DAL), fontSize = 12.sp, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ButtonBadge("A"); Text("READ MORE", color = C.accent, fontSize = 8.5.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** The whole description over the page: Up / Down (or the right stick) scroll it, B, A or a tap outside close it. */
@Composable
private fun DescriptionPopup(title: String, text: String, onClose: () -> Unit) {
    val sc = rememberScrollState()
    RightStick.Register(sc)
    val scope = rememberCoroutineScope()
    val me = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { me.requestFocus() } }
    BackHandler { onClose() }
    Box(
        Modifier.fillMaxSize().background(Color(0x8C000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth(0.62f).fillMaxHeight(0.78f).clip(shape)
                .background(cardGlass()).border(1.dp, C.accent.copy(alpha = 0.45f), shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .focusRequester(me)
                .onKeyEvent { e ->
                    val k = e.key
                    val dirs = setOf(androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.DirectionDown, androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight)
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onKeyEvent k in dirs
                    when (k) {
                        androidx.compose.ui.input.key.Key.DirectionDown -> { scope.launch { sc.animateScrollBy(110f) }; true }
                        androidx.compose.ui.input.key.Key.DirectionUp -> { scope.launch { sc.animateScrollBy(-110f) }; true }
                        androidx.compose.ui.input.key.Key.ButtonA, androidx.compose.ui.input.key.Key.DirectionCenter, androidx.compose.ui.input.key.Key.Enter -> { onClose(); true }
                        in dirs -> true
                        else -> false
                    }
                }
                .focusable()
                .padding(horizontal = 22.dp, vertical = 18.dp)
        ) {
            Text("ABOUT", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
            Text(title, color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Text(text, color = C.x(0xFFE6E3DAL), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(sc))
                if (sc.maxValue > 0) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x1AFFFFFFL))) {
                    val frac = sc.value / sc.maxValue.toFloat()
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Box(Modifier.offset(y = (maxHeight * 0.62f) * frac).fillMaxWidth().fillMaxHeight(0.38f).background(C.accent))
                    }
                }
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ButtonBadge("B"); Text("CLOSE", color = C.sub, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 6.dp, end = 14.dp))
                if (sc.maxValue > 0) Text("\u25B2\u25BC  SCROLL", color = C.sub, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
        }
    }
}

/** RetroAchievements folded up: its name, how much of it you've done, and the bar. */
@Composable
private fun RaBar(p: RaProgress, fr: FocusRequester, onClick: () -> Unit) {
    val frac = if (p.total == 0) 0f else p.earned / p.total.toFloat()
    val shape = RoundedCornerShape(9.dp)
    FocusBox(Modifier.fillMaxWidth(), shape = shape, onClick = onClick, focusRequester = fr, focusKey = "page:ra") {
        Column(
            Modifier.fillMaxWidth().background(if (ThemeColors.light.value) Color.White else cardGlass())
                .border(1.dp, C.x(0x12FFFFFFL), shape).padding(horizontal = 11.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RETROACHIEVEMENTS", color = C.x(0xFFCFCAC0L), fontSize = 8.5.sp, letterSpacing = 1.4.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("${(frac * 100).toInt()}%", color = C.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("\u203A", color = C.sub, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(C.x(0x33FFFFFFL))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color(0xFFFFC83D)))
            }
        }
    }
}

/** Under the cover: when you last played it and for how long (stacked, to fit the cover's width). */
@Composable
private fun PlayStats(vm: EclipseVM, g: Game, modifier: Modifier = Modifier) {
    val t = vm.playedSeconds(g)
    val last = vm.lastPlayedLabel(g)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (t <= 0 && last == null) Text("NOT PLAYED YET", color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp, modifier = Modifier.fillMaxWidth())
        @Composable fun stat(label: String, value: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            Text(value.uppercase(), color = C.x(0xFFE6E3DAL), fontSize = 10.sp, letterSpacing = 0.8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (last != null) stat("LAST PLAYED", last)
        if (t > 0) stat("PLAY TIME", fmtDuration(t))
        // how much room it takes here, or how much a RomM game downloads
        val size = remember(g.id, g.path) { vm.gameSizeBytes(g) }
        if (size > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.isRommEntry(g)) "DOWNLOAD SIZE" else "INSTALL SIZE", color = C.sub, fontSize = 8.sp, letterSpacing = 1.6.sp, modifier = Modifier.weight(1f))
            Text(vm.fmtBytes(size), color = C.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun PageChip(label: String, gold: Boolean = false, key: String? = null, fr: FocusRequester? = null, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(7.dp), onClick = onClick, focusKey = key, focusRequester = fr) {
        Text(
            label.uppercase(), color = if (gold) C.onAccent else C.x(0xFFCFCAC0L), fontSize = 8.sp, letterSpacing = 1.2.sp, maxLines = 1,
            modifier = Modifier.background(if (gold) C.accent else C.x(0x14FFFFFFL)).padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

/** A tab's glass box, right of the cover. */
@Composable
private fun TabPanel(content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.fillMaxSize().clip(shape).background(cardGlass()).border(1.dp, C.x(0x1AFFFFFFL), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

/** A gold heading with a hairline, inside a tab. */
@Composable
private fun TabSection(title: String) {
    Row(Modifier.padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
    }
}

/** Options in two columns. */
@Composable
private fun OptGrid(items: List<@Composable () -> Unit>) {
    items.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            pair.forEach { Box(Modifier.weight(1f)) { it() } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** An option: its name, the current choice in gold on the right. */
@Composable
private fun OptRow(label: String, value: String, danger: Boolean = false, muted: Boolean = false, fr: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val rose = Color(0xFFF08AA0)
    FocusBox(Modifier.fillMaxWidth().height(40.dp), shape = shape, onClick = onClick, focusRequester = fr) {
        Row(
            Modifier.fillMaxSize().background(if (danger) rose.copy(alpha = 0.08f) else C.x(0x0FFFFFFFL))
                .border(1.dp, if (danger) rose.copy(alpha = 0.35f) else C.x(0x14FFFFFFL), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = if (danger) rose else C.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (muted) value else value.uppercase(), color = if (muted) C.sub else if (danger) rose else C.accent,
                fontSize = if (muted) 11.sp else 10.sp, letterSpacing = if (muted) 0.sp else 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 220.dp)
            )
        }
    }
}

/** An on / off option. */
@Composable
private fun OptToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    FocusBox(Modifier.fillMaxWidth().height(40.dp), shape = shape, onClick = { onChange(!on) }) {
        Row(
            Modifier.fillMaxSize().background(C.x(0x0FFFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = C.ink, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            MiniSwitch(on)
        }
    }
}

/** One detail: its name, then a box (read-only, or one you type into while editing). */
@Composable
private fun DetailRow(label: String, value: String, editing: Boolean, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.width(76.dp))
        if (editing) TextBox(value, Modifier.weight(1f), placeholder = "\u2014", focusKey = "detail:$label", fontSize = 11.5.sp, onSave = onChange)
        else Text(
            value.ifBlank { "\u2014" }, color = if (value.isBlank()) C.sub else C.ink, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(C.x(0x4D000000L)).border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PageHint(key: String, label: String) = ButtonHint(key, label)

/**
 * One side of the flipping card. At [t] 0 it sits on [src] (the cover on screen) facing you with its front;
 * at 1 it fills the screen with the back (the menu) facing you. Without a known cover it starts as a cover-sized card in the middle.
 */
private fun Modifier.flipLayer(t: () -> Float, src: Rect?, origin: Offset, area: IntSize, back: Boolean, density: Float) = graphicsLayer {
    val w = area.width.toFloat()
    val h = area.height.toFloat()
    if (w <= 0f || h <= 0f) { alpha = 0f; return@graphicsLayer }
    val e = t()
    val s = src ?: Rect(Offset(origin.x + w / 2 - 100 * density, origin.y + h / 2 - 133 * density), Size(200 * density, 266 * density))
    val sx = s.width / w
    val sy = s.height / h
    scaleX = sx + (1f - sx) * e
    scaleY = sy + (1f - sy) * e
    translationX = (s.center.x - origin.x - w / 2) * (1f - e)
    translationY = (s.center.y - origin.y - h / 2) * (1f - e)
    val rot = 180f * e // 0: the cover faces you, 180: the menu does
    rotationY = if (back) rot - 180f else rot
    cameraDistance = 16f * density
    alpha = if (back == (rot >= 90f)) 1f else 0f
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RaCard(p: RaProgress, open: Boolean, firstFr: FocusRequester? = null, toggle: () -> Unit) {
    // A grid of every achievement badge (dimmed until earned). Whichever badge the controller is on (or you tap)
    // shows its name, description and points in the bubble above the grid.
    val frac = if (p.total == 0) 0f else p.earned / p.total.toFloat()
    val pts = p.achievements.filter { it.earned }.sumOf { it.points }
    val allPts = p.achievements.sumOf { it.points }
    var picked by remember(p) { mutableStateOf(p.achievements.firstOrNull { it.earned } ?: p.achievements.firstOrNull()) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.x(0x1FFFFFFFL)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RetroAchievements", fontWeight = FontWeight.Bold, color = C.ink, modifier = Modifier.weight(1f))
            Text("\u2605 ${p.earned} / ${p.total}  \u00b7  $pts / $allPts pts", color = C.x(0xCCFFFFFFL), fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)).background(C.x(0x33FFFFFFL))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color(0xFFFFC83D)))
        }
        // the "hover" bubble
        picked?.let { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (a.earned) Color(0x33FFC83D) else C.x(0x1AFFFFFFL)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(RetroAch.badgeUrl(a.badge), null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), alpha = if (a.earned) 1f else 0.45f)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, fontWeight = FontWeight.Bold, color = C.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.description, color = C.x(0xCCFFFFFFL), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (a.earned) "\u2713 " else "") + "${a.points} pts", color = if (a.earned) Color(0xFFFFC83D) else C.x(0x99FFFFFFL),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        // the grid: earned first, then the rest
        val shown = p.achievements.sortedBy { !it.earned }.let { if (open) it else it.take(24) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEachIndexed { i, a ->
                FocusBox(
                    Modifier.size(46.dp),
                    shape = RoundedCornerShape(8.dp),
                    focusRequester = if (i == 0) firstFr else null,
                    onClick = { picked = a },
                    onFocusChange = { f -> if (f) picked = a }
                ) {
                    AsyncImage(
                        RetroAch.badgeUrl(a.badge), a.title, Modifier.fillMaxSize(),
                        alpha = if (a.earned) 1f else 0.35f, contentScale = ContentScale.Crop
                    )
                }
            }
            if (!open && p.achievements.size > shown.size) {
                FocusBox(Modifier.size(46.dp), shape = RoundedCornerShape(8.dp), onClick = toggle) {
                    Box(Modifier.fillMaxSize().background(C.x(0x26FFFFFFL)), contentAlignment = Alignment.Center) {
                        Text("+${p.achievements.size - shown.size}", color = C.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Match a game by hand: type the title the sources know it by; Eclipse searches for that and scrapes again. */
@Composable
private fun MatchSheet(vm: EclipseVM, g: Game, close: () -> Unit) {
    var title by remember(g.id) { mutableStateOf(vm.matchNames[g.id] ?: g.name) }
    // the controller starts on the title box
    LaunchedEffect(Unit) { withFrameNanos { }; GamepadFocus.restoreKey = "match:title" }
    // the matches the sources find for the title (searched again whenever the title is changed)
    var hits by remember(g.id) { mutableStateOf<List<MatchHit>?>(null) }
    LaunchedEffect(g.id, title) {
        hits = null
        hits = runCatching { vm.matchCandidates(g, title) }.getOrDefault(emptyList())
    }
    val current = vm.matchNames[g.id]
    MenuSheet(
        "Match by hand", g.name,
        "Pick the right game from the list, or change the title to search again.",
        onDismiss = close
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SEARCH", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.width(52.dp))
            TextBox(title, Modifier.weight(1f), placeholder = "The game's proper title", focusKey = "match:title", fontSize = 11.5.sp) { v -> title = v }
        }
        val list = hits
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text("MATCHES", color = C.accent, fontSize = 8.sp, letterSpacing = 1.6.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(1.dp).background(C.accent.copy(alpha = 0.25f)))
            Spacer(Modifier.width(8.dp))
            Text(
                when { list == null -> "Searching…"; list.isEmpty() -> "None found"; else -> "${list.size}" },
                color = C.sub, fontSize = 8.5.sp
            )
        }
        if (!list.isNullOrEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                list.forEach { h ->
                    MenuRow(
                        h.name, note = listOf(h.note, h.source).filter { it.isNotBlank() }.joinToString("  ·  "),
                        selected = current != null && current.equals(h.name, ignoreCase = true)
                    ) { vm.manualMatch(g, h.name); close() }
                }
            }
        }
        MenuRow("Use the title as typed", note = "Search & scrape with “$title”") { vm.manualMatch(g, title); close() }
        MenuRow("Pick artwork for this title", note = "Opens the artwork picker") { vm.manualMatch(g, title); close(); vm.artKindStart = ArtKind.BOX; vm.artGame = g }
        if (vm.matchNames[g.id] != null) MenuRow("Undo the match", note = "Back to automatic") { vm.manualMatch(g, ""); close() }
        MenuRow("Cancel", value = "B") { close() }
    }
}

@Composable
fun ArtScreen(vm: EclipseVM, g: Game, pickImage: () -> Unit) = Glass {
    var kind by remember { mutableStateOf(vm.artKindStart) }
    var src by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ArtOption>?>(null) }
    val provs = vm.artProviders()
    BackHandler { vm.artGame = null }

    LaunchedEffect(g.id, kind, src, provs.size) {
        results = null
        results = if (provs.isEmpty()) emptyList() else vm.searchArt(g, kind, src)
    }

    // the controller starts on the kind you came in for
    val firstTab = remember { FocusRequester() }
    FocusOnShow(firstTab)
    val ratio = when (kind) { ArtKind.BOX, ArtKind.BOX3D -> 3f / 4f; ArtKind.BACKDROP -> 16f / 9f; ArtKind.LOGO -> 3f / 1f; ArtKind.MEDIA -> 1f; ArtKind.HERO -> 3.1f; ArtKind.POSTER -> 2f / 3f }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.focusTrap()) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: what you're changing, the current picture, upload / default ----
            GlassPanel(Modifier.width(250.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().menuScroll().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CHANGE ARTWORK", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text(g.name, color = C.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ArtSection("Current")
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(if (kind == ArtKind.BOX) 1.2f else ratio.coerceAtLeast(1.4f)).clip(RoundedCornerShape(9.dp))
                            .background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val current = vm.artOf(g, kind)
                        if (current != null) AsyncImage(current, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
                        else Text("NONE", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Upload", Modifier.weight(1f), primary = true) { vm.pendingKind = kind; pickImage() }
                        Pill("Default", Modifier.weight(1f)) { vm.setArt(g, kind, null) }
                    }
                    ArtSection("Kind")
                    ArtKind.values().forEach { k ->
                        ArtChoiceRow(k.label, k == kind, "art:k:${k.name}", if (k == kind) firstTab else null) { kind = k; src = null }
                    }
                }
            }
            // ---- right: what the sources found ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                    val list = results
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kind.label.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text(
                            when { list == null -> "SEARCHING\u2026"; else -> "${list.size} FOUND" },
                            color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            provs.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("No artwork sources are on.", color = C.ink, fontWeight = FontWeight.Bold)
                                Pill("Open Scrape settings", primary = true) { vm.artGame = null; vm.settingsTab = SettingsTabs.SCRAPE; vm.go(Screen.SETTINGS) }
                            }
                            list == null -> Text("Searching ${provs.joinToString { it.second }}\u2026", color = C.sub, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                            list.isEmpty() -> Text("Nothing found for this game.", color = C.sub, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                            else -> LazyVerticalGrid(
                                GridCells.Adaptive(when (kind) { ArtKind.BOX, ArtKind.POSTER -> 96.dp; ArtKind.MEDIA -> 110.dp; else -> 170.dp }),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 40.dp), state = stickGridState()
                            ) {
                                items(list) { o ->
                                    FocusBox(Modifier.fillMaxWidth().aspectRatio(ratio), shape = RoundedCornerShape(8.dp), onClick = { vm.pickArt(g, kind, o.url) }) {
                                        AsyncImage(o.thumb, null, Modifier.fillMaxSize().background(C.x(0x14FFFFFFL)), contentScale = if (kind == ArtKind.MEDIA || kind == ArtKind.LOGO) ContentScale.Fit else ContentScale.Crop)
                                        Text(
                                            o.source.uppercase(), color = C.ink, fontSize = 7.5.sp, letterSpacing = 1.sp,
                                            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xA6000000)).padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) { ArtHint("A", "Choose"); ArtHint("B", "Back") }
    }
}

@Composable
private fun ArtSection(title: String) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
    }
}

/** A choice in the right dock's style: a radio dot, the name in small capitals, gold when chosen. */
@Composable
private fun ArtChoiceRow(label: String, on: Boolean, key: String, fr: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    FocusBox(Modifier.fillMaxWidth().height(32.dp), shape = shape, onClick = onClick, focusRequester = fr, focusKey = key) {
        Row(
            Modifier.fillMaxSize()
                .background(if (on) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (on) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (on) C.accent else C.sub, CircleShape), contentAlignment = Alignment.Center) {
                if (on) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent))
            }
            Text(label.uppercase(), color = if (on) C.ink else C.x(0xFFB9B4AAL), fontSize = 9.sp, letterSpacing = 1.3.sp, maxLines = 1, modifier = Modifier.padding(start = 9.dp))
        }
    }
}

@Composable
private fun ArtHint(key: String, label: String) = ButtonHint(key, label)

/**
 * A system's artwork picker (Platform menu → Choose card image / Platform artwork), laid out like the games' ArtScreen:
 * the current picture, Upload / Default and the kind (card image, icon, logo, backdrop) on the left, what the sources
 * found on the right. Favourites and All games only have a card image.
 */
@Composable
fun PlatformArtScreen(vm: EclipseVM, id: String, pickImage: () -> Unit) = Glass {
    val p = Platforms.byId(id) ?: run { vm.platArtScreen = null; return@Glass }
    val kinds = if (id == Platforms.FAVORITES || id == Platforms.ALL) listOf(PlatKind.CARD) else PlatKind.values().toList()
    var kind by remember { mutableStateOf(vm.platKindStart.takeIf { it in kinds } ?: kinds.first()) }
    var src by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ArtOption>?>(null) }
    val provs = vm.platProviders(kind)
    BackHandler { vm.platArtScreen = null }

    LaunchedEffect(id, kind, src, provs.size) {
        results = null
        results = if (provs.isEmpty()) emptyList() else vm.searchPlatformArt(id, kind, src)
    }

    // the controller starts on the kind you came in for
    val firstTab = remember { FocusRequester() }
    FocusOnShow(firstTab)
    val ratio = when (kind) { PlatKind.CARD, PlatKind.ICON -> 1f; PlatKind.LOGO -> 2f; PlatKind.BACKDROP -> 16f / 9f }
    // card images and backdrops fill their box; icons and logos sit inside it
    val fill = kind == PlatKind.CARD || kind == PlatKind.BACKDROP
    val noPacks = "No installed icon pack has ${p.name}. Add packs in Settings \u2192 Look & Feel."

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }.focusTrap()) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: what you're changing, the current picture, upload / default ----
            GlassPanel(Modifier.width(250.dp).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().menuScroll().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("CHANGE ARTWORK", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                    Text(p.name, color = C.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ArtSection("Current")
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(if (kind == PlatKind.BACKDROP || kind == PlatKind.LOGO) ratio.coerceAtLeast(1.4f) else 1.2f).clip(RoundedCornerShape(9.dp))
                            .background(platformTint(id)).border(1.dp, C.x(0x14FFFFFFL), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val current = when (kind) {
                            PlatKind.CARD -> vm.platformCardIcon(id)
                            PlatKind.BACKDROP -> vm.platArtOf(id, kind) ?: vm.systemBackground(id)
                            else -> vm.platArtOf(id, kind)
                        }
                        if (current != null) AsyncImage(current, null, Modifier.fillMaxSize().padding(if (fill) 0.dp else 8.dp), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
                        else Text("NONE", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("Upload", Modifier.weight(1f), primary = true) { vm.pendingPlatform = id; vm.pendingPlatKind = kind; pickImage() }
                        Pill("Default", Modifier.weight(1f)) { vm.clearPlatformArt(id, kind) }
                    }
                    if (kinds.size > 1) {
                        ArtSection("Kind")
                        kinds.forEach { k ->
                            ArtChoiceRow(k.label, k == kind, "platart:k:${k.name}", if (k == kind) firstTab else null) { kind = k; src = null }
                        }
                    }
                    if (provs.size > 1) {
                        ArtSection("Source")
                        ArtChoiceRow("All sources", src == null, "platart:s:all", if (kinds.size == 1) firstTab else null) { src = null }
                        provs.forEach { (pid, label) -> ArtChoiceRow(label, src == pid, "platart:s:$pid") { src = pid } }
                    }
                }
            }
            // ---- right: what the sources found ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp)) {
                    val list = results
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kind.label.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp)
                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                        Text(if (list == null) "SEARCHING\u2026" else "${list.size} FOUND", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            provs.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when (kind) {
                                        PlatKind.CARD -> "No icon packs are installed."
                                        PlatKind.LOGO -> "Logos come from IGDB: add its keys in Settings \u2192 Scrape."
                                        else -> "No sources are on for this."
                                    },
                                    color = C.ink, fontWeight = FontWeight.Bold, fontSize = 12.sp
                                )
                                if (kind == PlatKind.CARD) {
                                    Text("Upload a picture, or add packs in Look & Feel.", color = C.sub, fontSize = 11.sp)
                                    Pill("Open Look & Feel", primary = true) { vm.platArtScreen = null; vm.settingsTab = SettingsTabs.THEME; vm.go(Screen.SETTINGS) }
                                } else Pill("Open Scrape settings", primary = true) { vm.platArtScreen = null; vm.settingsTab = SettingsTabs.SCRAPE; vm.go(Screen.SETTINGS) }
                            }
                            list == null -> Text("Searching ${provs.joinToString { it.second }}\u2026", color = C.sub, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                            list.isEmpty() -> Text(
                                when (kind) {
                                    PlatKind.CARD -> noPacks
                                    PlatKind.BACKDROP -> "None of your games here have a backdrop yet."
                                    else -> "Nothing found for ${p.name}."
                                },
                                color = C.sub, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center)
                            )
                            else -> LazyVerticalGrid(
                                GridCells.Adaptive(when (kind) { PlatKind.CARD, PlatKind.ICON -> 96.dp; PlatKind.LOGO -> 150.dp; PlatKind.BACKDROP -> 170.dp }),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 40.dp), state = stickGridState()
                            ) {
                                items(list) { o ->
                                    FocusBox(Modifier.fillMaxWidth().aspectRatio(ratio), shape = RoundedCornerShape(8.dp), onClick = { vm.pickPlatformArt(id, kind, o.url) }) {
                                        Box(Modifier.fillMaxSize().background(platformTint(id))) {
                                            AsyncImage(
                                                o.thumb, null,
                                                Modifier.fillMaxSize().padding(if (fill) 0.dp else 8.dp),
                                                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit
                                            )
                                        }
                                        Text(
                                            o.source.uppercase(), color = C.ink, fontSize = 7.5.sp, letterSpacing = 1.sp, maxLines = 1,
                                            modifier = Modifier.align(Alignment.BottomStart).background(Color(0xA6000000)).padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                        // the one in use
                                        if (vm.platArtOf(id, kind) == o.url) Text(
                                            "\u2713", color = C.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(C.accent).padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically
        ) { ArtHint("A", "Choose"); ArtHint("B", "Back") }
    }
}

/** "Delete the downloaded copy?" for a game downloaded from RomM (the game page's Download row). */
@Composable
fun DeleteDownload(vm: EclipseVM, romId: Int) {
    val r = vm.rommCatalog.firstOrNull { it.id == romId }
    val keep = remember { FocusRequester() }
    FocusOnShow(keep) // Keep first: a stray A press deletes nothing
    MenuSheet(
        "RomM download", r?.name ?: "This game",
        "Removes the copy on this device to free up space. It stays on your RomM server, and your play time and artwork are kept.",
        onDismiss = { vm.storeDelete = null }
    ) {
        MenuRow("Keep it", value = "B", fr = keep) { vm.storeDelete = null }
        MenuRow("Delete from this device", danger = true) { vm.storeDelete = null; vm.rommDeleteDownload(romId) }
    }
}
