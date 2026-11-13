package com.eclipse.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.AppEntry
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.Screen

private val CATS = listOf("All", "Games", "Media", "Social", "Tools", "Other")

@Composable
fun AppsScreen(vm: EclipseVM) {
    // the drawer normally lives on Home (under the platforms); this is the same drawer on its own
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    // R bumper: the categories drawer
    DisposableEffect(Unit) {
        val hook: () -> Unit = {
            vm.appsDrawer = !vm.appsDrawer
            if (!vm.appsDrawer) runCatching { first.requestFocus() }
        }
        GamepadFocus.rightDock = hook
        onDispose { if (GamepadFocus.rightDock === hook) GamepadFocus.rightDock = null }
    }
    // the same backdrop and bottom-half glass as On Deck (your wallpaper)
    Box(Modifier.fillMaxSize()) { SlideBackdrop(vm, null); AppDrawer(vm, first, active = vm.screen == Screen.APPS) }
}

/**
 * The app drawer, over the dimmed wallpaper: your favourites first, then every app grouped by its first letter (a gold
 * letter at the start of each row), an A–Z strip down the right, and a bar along the bottom about the selected app.
 * L2 / R2 jump to the previous / next letter (while [active]); R (or Right at the end) opens the categories drawer.
 * [first] goes on the first tile.
 */
@Composable
fun AppDrawer(vm: EclipseVM, first: FocusRequester, modifier: Modifier = Modifier, active: Boolean = true) = Glass {
    val cat = vm.appCat
    val list = remember(vm.apps, cat, vm.renames, vm.cats) {
        vm.apps.filter { cat == "All" || vm.catOf(it) == cat }.sortedBy { vm.nameOf(it).lowercase() }
    }
    val favs = if (cat == "All") vm.favApps.mapNotNull { p -> vm.apps.firstOrNull { it.pkg == p } } else emptyList()
    val groups = remember(list) {
        list.groupBy { a -> vm.nameOf(a).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#" }
            .toSortedMap(compareBy { if (it == "#") "{" else it })
    }
    val letters = groups.keys.toList()
    // where each letter's first app is (the very first one shares [first])
    val letterFocus = remember(letters, favs.isEmpty(), first) {
        letters.mapIndexed { i, l -> l to (if (i == 0 && favs.isEmpty()) first else FocusRequester()) }.toMap()
    }
    var current by remember { mutableStateOf<com.eclipse.launcher.data.AppEntry?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    RightStick.Register(listState)
    val scope = rememberCoroutineScope()
    val fm = LocalFocusManager.current
    val rowOffset = if (favs.isNotEmpty()) 1 else 0
    val jumpTo: (String) -> Unit = { l ->
        val idx = letters.indexOf(l)
        if (idx >= 0) scope.launch {
            listState.animateScrollToItem(idx + rowOffset)
            withFrameNanos { }
            runCatching { letterFocus[l]?.requestFocus() }
        }
    }
    val curLetter = current?.let { a -> vm.nameOf(a).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#" }
    // L2 / R2: previous / next letter
    DisposableEffect(active, letters) {
        if (!active) return@DisposableEffect onDispose { }
        val hook: (Int) -> Unit = { d ->
            if (letters.isNotEmpty()) {
                val at = letters.indexOf(curLetter).let { if (it < 0) (if (d > 0) -1 else letters.size) else it }
                jumpTo(letters[(at + d).coerceIn(0, letters.lastIndex)])
            }
        }
        GamepadFocus.shoulder = hook
        onDispose { if (GamepadFocus.shoulder === hook) GamepadFocus.shoulder = null }
    }

    // the list takes [modifier] (Home pushes it below its header); the drawer on the right always runs the full height
    Box(Modifier.fillMaxSize()) {
        // the theme's wallpaper behind frosted glass, like the other screens (plain tinted panel in low power mode)
        GlassScreenBackdrop(vm)
        GlassFill()
        Column(
            modifier.fillMaxSize()
                .onKeyEvent { e ->
                    // all the way right: the categories drawer slides out
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !vm.appsDrawer) {
                        if (!fm.moveFocus(FocusDirection.Right)) vm.appsDrawer = true
                        true
                    } else false
                }
        ) {
            // header: title and count, then the categories (tap one; with a controller, R opens the categories drawer)
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 22.dp, end = 36.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(if (cat == "All") "Apps" else cat, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.ink)
                Text(
                    "${list.size} ${if (list.size == 1) "app" else "apps"}", fontSize = 13.sp, color = C.x(0xFFA9ABB4L),
                    modifier = Modifier.padding(start = 14.dp, bottom = 5.dp)
                )
                // (categories: R opens the drawer on the right)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cols = ((maxWidth - 28.dp - 36.dp - 44.dp) / 104.dp).toInt().coerceIn(3, 10)
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(start = 28.dp, end = 36.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (favs.isNotEmpty()) {
                        item(key = "row:fav") {
                            LetterRow("\u2605", favs, cols, first, vm, fav = true) { current = it }
                        }
                    }
                    letters.forEachIndexed { gi, l ->
                        item(key = "row:$l") {
                            LetterRow(l, groups[l].orEmpty(), cols, letterFocus[l], vm) { current = it }
                        }
                    }
                    if (list.isEmpty()) item { Text("No apps here.", color = C.sub, modifier = Modifier.padding(top = 16.dp)) }
                }
                // A–Z down the right edge: the letters you have are brighter, the one you're on is gold; tap to jump
                Column(
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 10.dp, top = 6.dp, bottom = 10.dp).width(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ("#" + ('A'..'Z').joinToString("")).forEach { ch ->
                        val l = ch.toString()
                        val on = l == curLetter
                        val has = l in letters
                        Text(
                            l, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                            color = if (on) C.onAccent else if (has) C.x(0xB3FFFFFFL) else C.x(0x40FFFFFFL),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(16.dp).clip(RoundedCornerShape(8.dp)).background(if (on) C.accent else Color.Transparent)
                                .pointerInput(l, has) { detectTapGestures { if (has) jumpTo(l) } }
                        )
                    }
                }
            }
            // the selected app, and what the buttons do
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(glassTint(ThemeColors.light.value)).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val a = current
                if (a != null) {
                    Text(vm.nameOf(a), color = C.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
                    Text(
                        vm.catOf(a) + if (a.pkg in vm.favApps) "  \u00b7  Favorite" else "",
                        color = C.x(0xFFA9ABB4L), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 14.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // the same button badges as slide view
                ButtonHint("A", "Open"); Spacer(Modifier.width(14.dp))
                ButtonHint("Select", "Options"); Spacer(Modifier.width(14.dp))
                ButtonHint("L2 R2", "Jump letter"); Spacer(Modifier.width(14.dp))
                ButtonHint("R", "More")
            }
        }
        // touch: a small tab on the right edge opens the categories drawer
        if (!vm.appsDrawer) {
            Box(
                Modifier.align(Alignment.CenterEnd).size(18.dp, 72.dp).pointerInput(Unit) { detectTapGestures { vm.appsDrawer = true } },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(Modifier.padding(end = 5.dp).size(5.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L)))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.appsDrawer,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
        ) {
            OptionsDrawer(
                vm, "Apps",
                listOf(DrawerList("Category", CATS.map { it to it }, cat) { vm.appCat = it })) {
                vm.appsDrawer = false
                runCatching { first.requestFocus() }
            }
        }
    }
}

/** One row of the drawer: a gold letter (or ★ for favourites) on the left, its apps in lines of [cols] beside it. */
@Composable
private fun LetterRow(
    label: String, apps: List<com.eclipse.launcher.data.AppEntry>, cols: Int, first: FocusRequester?,
    vm: EclipseVM, fav: Boolean = false, onFocused: (com.eclipse.launcher.data.AppEntry) -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = C.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp).padding(top = 14.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            apps.chunked(cols).forEachIndexed { li, line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    line.forEachIndexed { i, app ->
                        Box(Modifier.weight(1f)) { AppTile(vm, app, if (li == 0 && i == 0) first else null, fav, onFocused) }
                    }
                    repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    Box(Modifier.padding(start = 44.dp).fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
}

@Composable
private fun AppTile(
    vm: EclipseVM, app: com.eclipse.launcher.data.AppEntry, fr: FocusRequester?, fav: Boolean = false,
    onFocused: (com.eclipse.launcher.data.AppEntry) -> Unit = {}
) {
    FocusBox(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        onClick = { vm.openApp(app.pkg) },
        onLongClick = { vm.appMenu = app.pkg }, // long-press or Select
        onFocusChange = { f -> if (f) onFocused(app) },
        focusRequester = fr,
        focusKey = (if (fav) "fav:" else "app:") + app.pkg
    ) { focused ->
        Column(
            Modifier.fillMaxWidth().background(if (focused) C.accent.copy(alpha = 0.16f) else Color.Transparent).padding(top = 9.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppIcon(app, Modifier.size(52.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                vm.nameOf(app), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp, color = if (focused) C.ink else C.x(0xFFD8D8DDL),
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center
            )
        }
    }
}

/** Menu for an app in the drawer (long-press or Select), in the same style as the other menus. */
@Composable
fun AppMenu(vm: EclipseVM, pkg: String) {
    val app = vm.apps.firstOrNull { it.pkg == pkg }
    val close = { vm.appMenu = null }
    var mode by remember { mutableStateOf("main") }
    var name by remember { mutableStateOf(app?.let { vm.nameOf(it) } ?: "") }
    val first = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    FocusOnShow(if (mode == "rename") field else first, mode)
    if (app == null) {
        MenuSheet("App", pkg, "This app is no longer installed.", onDismiss = close) {
            MenuRow("Close", gold = true, fr = first) { close() }
        }
        return
    }
    MenuSheet(
        if (mode == "cat") "Category" else "App", vm.nameOf(app),
        if (mode == "main") vm.catOf(app) else null,
        onDismiss = { if (mode == "main") close() else mode = "main" },
        image = { AppIcon(app, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) }
    ) {
        when (mode) {
            "rename" -> {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.rename(app.pkg, name); close() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                )
                MenuRow("Save", gold = true) { vm.rename(app.pkg, name); close() }
                MenuRow("Reset to original name") { vm.rename(app.pkg, ""); close() }
                MenuRow("Back", value = "B") { mode = "main" }
            }
            "cat" -> {
                CATS.drop(1).forEachIndexed { i, c ->
                    MenuRow(c, selected = vm.catOf(app) == c, fr = if (i == 0) first else null) { vm.setCat(app.pkg, c); close() }
                }
                MenuRow("Back", value = "B") { mode = "main" }
            }
            else -> {
                MenuRow(if (app.pkg in vm.favApps) "Remove from favorites" else "Add to favorites", fr = first) { vm.toggleFav(app.pkg); close() }
                MenuRow(if (app.pkg in vm.dock) "Unpin app" else "Pin app", note = "Shows in the left dock") { vm.toggleDock(app.pkg); close() }
                MenuRow(if (app.pkg in vm.dock2) "Remove from second-screen dock" else "Add to second-screen dock") { vm.toggleDock2(app.pkg); close() }
                MenuRow("Rename") { mode = "rename" }
                MenuRow("Change category") { mode = "cat" }
                MenuRow("Uninstall", danger = true) { close(); vm.uninstallApp(app.pkg, app.label) }
            }
        }
    }
}

/**
 * Games for one system (or all). Pick the system from the home screen's platform grid; here, L2 / R2 switch to the
 * previous / next system. Views: Carousel (same as Home), Grid, List.
 */
@Composable
fun PlatformsScreen(vm: EclipseVM) {
    var sel by remember { mutableStateOf(vm.platformSel) }
    val customViews = rememberCustomViews()
    // the chosen view, unless it's been turned off in Settings → Views (then the first one that's on)
    val gameViews = vm.viewsFor("games", customViews)
    val allViews = gameViews.map { it.first }
    val mode = vm.shownView(vm.viewFor(sel), allViews)
    val plats = remember(vm.platCounts, vm.platOrder, vm.platSort, vm.sysNames) { vm.platCounts.let { c -> vm.orderPlats(Platforms.all.filter { vm.showsPlat(it.id, c) && it.id != Platforms.ALL }) } }
    // Source (options drawer): your RomM games for this system that aren't downloaded, mixed in with a cloud badge.
    // They also show when the platforms' source is Local + RomM.
    val romOnly = vm.platSource == "rommonly" && vm.rommOn()
    val withRomm = (vm.flag("romm_lists", false) || vm.platSource == "romm" || romOnly) && vm.rommOn()
    val sort = vm.gameSort
    val favSys = sel == Platforms.FAVORITES
    val list = remember(vm.games, sel, withRomm, romOnly, vm.rommCatalog, vm.rommLink, vm.favGames, sort, vm.lastPlayed, vm.playtime, vm.hiddenGames, vm.showHidden()) {
        // Sort (options drawer): A-Z with favourites first, recently played, most played, or favourites only
        // (the Favourites "system": your favourite games from every system; hidden games never show)
        val all = vm.games.filter { (sel == null || it.platform == sel || (favSys && it.id in vm.favGames)) && (it.id !in vm.hiddenGames || vm.showHidden()) && (!romOnly || it.id in vm.rommLink) } +
            (if (withRomm && !favSys) vm.rommEntriesFor(sel) else emptyList())
        when (sort) {
            "recent" -> all.sortedWith(compareByDescending<Game> { vm.lastPlayed[it.id]?.toLongOrNull() ?: 0L }.thenBy { it.name.lowercase() })
            "played" -> all.sortedWith(compareByDescending<Game> { vm.playedSeconds(it) }.thenBy { it.name.lowercase() })
            "fav" -> all.filter { it.id in vm.favGames }.sortedBy { it.name.lowercase() }
            else -> all.sortedWith(compareBy({ it.id !in vm.favGames }, { it.name.lowercase() }))
        }
    }
    val focusGame = list.firstOrNull { it.id == vm.focused?.id } ?: list.firstOrNull()
    SideEffect { vm.browseList = list }
    // the letter list (A-Z and favourites sorts): where each letter starts, after the favourites at the top
    // (other sorts too: a letter jumps to the first game starting with it, wherever it is in the list)
    val letters = remember(list, sort) {
        letterIndex(list, if (sort == "az") list.count { it.id in vm.favGames }.takeIf { it < list.size } ?: 0 else 0)
    }
    val scope = rememberCoroutineScope()
    var flowJump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val first = remember { FocusRequester() }
    val letterFr = remember { FocusRequester() }
    // back from the letter strip to the highlighted cover (once the list has scrolled to it)
    val backToCovers: () -> Unit = {
        scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    // focus the list when the system or view changes, except while the options drawer is open (changing the view
    // from the drawer must leave focus in the drawer)
    LaunchedEffect(sel to mode) {
        withFrameNanos { }
        if (!vm.gamesDrawer) runCatching { first.requestFocus() }
    }
    PrimaryFocus(first)
    val sizeKey = "sys:" + (sel ?: "all") // Icon size is kept per system

    // R bumper: the options drawer
    DisposableEffect(Unit) {
        val hook: () -> Unit = {
            vm.gamesDrawer = !vm.gamesDrawer
            if (!vm.gamesDrawer) runCatching { first.requestFocus() }
        }
        GamepadFocus.rightDock = hook
        onDispose { if (GamepadFocus.rightDock === hook) GamepadFocus.rightDock = null }
    }

    // L2 / R2: previous / next system
    DisposableEffect(plats) {
        GamepadFocus.shoulder = { d ->
            val options: List<String?> = plats.map { it.id }
            if (options.isNotEmpty()) {
                val i = options.indexOf(sel).coerceAtLeast(0)
                sel = options[(i + d + options.size) % options.size]
                vm.platformSel = sel
                sel?.let { vm.lastPlatform = it }
            }
        }
        onDispose { GamepadFocus.shoulder = null }
    }

    // "coverflow" = Cover flow (centred, details underneath), "detailflow" = Detail cover flow (details on the left)
    val flow = (mode == "coverflow" || mode == "detailflow") && list.isNotEmpty()
    val sysBg = sel?.let { vm.platformBackdrop(it) }
    // carousel, cover flow and a theme's system background all draw on dark, so the header text is white
    val carousel = ((mode == "carousel" || flow) && list.isNotEmpty()) || sysBg != null
    Box(Modifier.fillMaxSize()) {
        // slide view and cover flow share the same look: the highlighted game's backdrop fading into a dark bottom
        // cover flow keeps its own blurred look; every other view has On Deck's backdrop and bottom-half glass
        if (flow) StageBackdrop(vm, focusGame, side = mode == "detailflow")
        else if (sysBg != null && (mode == "list" || list.isEmpty())) {
            // the theme's background for this system, dimmed so the games stand out
            coil.compose.AsyncImage(sysBg, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
        }
        else if (mode == "carousel" && list.isNotEmpty()) SlideCornerBackdrop(vm, focusGame)
        else SlideBackdrop(vm, focusGame, null)
        val fm = LocalFocusManager.current
        Column(
            // (slide view starts at the very top, exactly like Home's)
            Modifier.fillMaxSize().padding(top = if (mode == "carousel" && list.isNotEmpty()) 0.dp else 16.dp)
                // all the way right: the options drawer slides out
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && !vm.gamesDrawer) {
                        if (!fm.moveFocus(FocusDirection.Right)) vm.gamesDrawer = true
                        true
                    } else false
                }
        ) {
            val listView = mode == "list" && list.isNotEmpty()
            if (listView) {
                // list view: no header; the list starts at the top and the game's own name / logo sits on the right
            } else if (mode == "carousel" && list.isNotEmpty()) {
                // slide view: the hero is laid over the whole screen below, exactly as on Home
            } else if (flow) {
                // cover flow: just where you are up top; the game's name and details sit under the covers
                val selP = sel?.let { Platforms.byId(it) }
                SlideTopBar(
                    vm, selP?.let { p -> p.short.ifBlank { p.name } } ?: sel ?: "All games", plats.size > 1, modifier = Modifier.padding(start = 28.dp, end = 24.dp),
                    // the system's logo top left (its name when it has none)
                    logo = selP?.let { p -> { PlatformTitle(vm, p, 22.sp, 34.dp) } }
                )
            } else if (mode == "grid" && list.isNotEmpty()) {
                // grid view, like the systems grid: the highlighted game's logo (or name) with its year and the game count
                // beside it in small capitals; the sort chips and the clock on the right
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp).offset(y = (-2).dp), verticalAlignment = Alignment.Top) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        focusGame?.let { g ->
                            Box(Modifier.weight(1f, fill = false).heightIn(max = 30.dp)) { GameHeading(vm, g, logoHeight = 28.dp) }
                            Text(
                                listOfNotNull(vm.gameDetail(g, "y").ifBlank { null }, "${list.size} ${if (list.size == 1) "GAME" else "GAMES"}").joinToString("  \u00b7  "),
                                color = C.x(0xFFB9B4AAL), fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1,
                                modifier = Modifier.padding(start = 16.dp, bottom = 3.dp)
                            )
                        }
                    }
                    Box(Modifier.padding(top = 6.dp)) { GridSortChips(vm) }
                    Clock(vm, Modifier.padding(start = 14.dp))
                }
            } else Row(Modifier.padding(start = 28.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sel?.let { Platforms.byId(it)?.name } ?: "All games",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (carousel) Color.White else C.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val count = "${list.size} ${if (list.size == 1) "game" else "games"}"
                    Text(
                        (if (plats.size > 1) "$count  \u00b7  L2 / R2: change system" else count) + "  \u00b7  R: options",
                        fontSize = 12.sp, color = if (carousel) C.x(0xCCFFFFFFL) else C.sub
                    )
                }
            }
            if (!listView && !(mode == "carousel" && list.isNotEmpty())) Spacer(Modifier.height(10.dp))
            if (list.isEmpty()) {
                Text("No games here yet. Add a ROM folder in Settings \u2192 Library.", color = C.sub, modifier = Modifier.padding(horizontal = 28.dp))
            } else when (mode) {
                "carousel" -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    // the logo / title and play line at the top left: same place and size as Home's slide view
                    SlideHero(
                        vm, focusGame, sel?.let { Platforms.byId(it)?.name } ?: "All games",
                        Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 20.dp), clock = false
                    )
                    // the covers may be taller than the space under the details: let them reach up, bottom-aligned
                    if (!vm.gamesDrawer) SlideHintBar(Modifier.align(Alignment.BottomStart))
                    GameCarousel(
                        vm, list, focusGame, first, emptyText = "",
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 34.dp),
                        scale = vm.iconSize(sizeKey, "carousel") * SLIDE_POSTER * SLIDE_GAME, showTitle = false, slide = true,
                        onDown = if (letters.size >= 2 && !vm.gamesDrawer) ({ runCatching { letterFr.requestFocus() } }) else null
                    )
                    // letters along the bottom: Down from the covers, Left / Right jump, Up / A goes back
                    if (!vm.gamesDrawer) LetterList(
                        letters, focusGame?.let { letterOf(it) }, vertical = false,
                        onJump = { i -> list.getOrNull(i)?.let { vm.focused = it } },
                        onLeave = backToCovers,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 5.dp),
                        focusRequester = letterFr
                    )
                }
                "coverflow", "detailflow" -> CoverFlow(
                    vm, list, first, Modifier.weight(1f).fillMaxWidth(), background = false, floor = HomeDark, scale = vm.iconSize(sizeKey, mode),
                    look = if (mode == "detailflow") "stage" else "detail",
                    jumpTo = flowJump,
                    // Down: the letter strip (it sits inside the cover flow's own box, so focus search never found it)
                    onDown = if (letters.size >= 2) ({ runCatching { letterFr.requestFocus() } }) else null,
                    bottomBar = {
                        LetterList(
                            letters, focusGame?.let { letterOf(it) }, vertical = false,
                            onJump = { i -> flowJump = i to ((flowJump?.second ?: 0) + 1) },
                            onLeave = backToCovers,
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 10.dp),
                            focusRequester = letterFr
                        )
                    }
                )
                // list: names on the left, the highlighted game's art, buttons and description on the right
                "list" -> Box(Modifier.weight(1f).fillMaxWidth().offset(y = (-16).dp)) {
                    val ls = stickListState()
                    // one glass panel: the list on the left, a line, the highlighted game on the right; the letter
                    // strip sits on the line, over the screenshot
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 34.dp)) {
                        val listW = (maxWidth * 0.4f).coerceIn(260.dp, 420.dp)
                        val panelShape = RoundedCornerShape(18.dp)
                        Box(Modifier.fillMaxSize().clip(panelShape).background(cardGlass()).border(1.dp, C.x(0x1FFFFFFFL), panelShape)) {
                            Row(Modifier.fillMaxSize()) {
                                Column(Modifier.width(listW).fillMaxHeight().padding(start = 14.dp, end = 22.dp, top = 14.dp)) {
                                    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text((sel?.let { Platforms.byId(it)?.name } ?: "All games").uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp, maxLines = 1)
                                        Box(Modifier.padding(horizontal = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
                                        val pos = list.indexOfFirst { it.id == focusGame?.id } + 1
                                        Text("${if (pos > 0) "$pos / " else ""}${list.size}", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp)
                                    }
                                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(bottom = 12.dp), state = ls) {
                                        itemsIndexed(list, key = { _, g -> g.id }) { i, g -> ListRow(vm, g, g.id == focusGame?.id, if (i == 0) first else null) }
                                    }
                                }
                                Box(Modifier.width(1.dp).fillMaxHeight().background(C.x(0x1FFFFFFFL)))
                                focusGame?.let { ListDetails(vm, it, Modifier.weight(1f).fillMaxHeight()) }
                            }
                        }
                        // the letter strip on the line, a little above the middle (over the screenshot)
                        LetterList(
                            letters, focusGame?.let { letterOf(it) }, vertical = true,
                            onJump = { i -> vm.focused = list[i]; scope.launch { ls.scrollToItem(i) } },
                            onLeave = { vm.focused?.let { GamepadFocus.restoreKey = "g:${it.id}" } },
                            modifier = Modifier.align(androidx.compose.ui.BiasAlignment(-1f, -0.25f)).offset(x = listW - 14.dp),
                            onRight = { vm.gamesDrawer = true }
                        )
                    }
                    if (!vm.gamesDrawer) SlideHints(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp))
                }
                else -> {
                    // a view you made (Eclipse/views/*.json), else the grid (also when its file has gone or is broken)
                    val custom = if (mode.startsWith("view:")) customViews.firstOrNull { "view:" + it.id == mode } else null
                    if (custom != null) CustomGamesView(vm, custom, list, focusGame, first, Modifier.weight(1f).fillMaxWidth())
                    else GameGridView(vm, list, focusGame, first, vm.gridCols(sizeKey, 6), Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        // touch: a small tab on the right edge opens the options drawer
        if (!vm.gamesDrawer) {
            Box(
                Modifier.align(Alignment.CenterEnd).size(18.dp, 72.dp).pointerInput(Unit) { detectTapGestures { vm.gamesDrawer = true } },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(Modifier.padding(end = 5.dp).size(5.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L)))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.gamesDrawer,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
        ) {
            val sources = listOf("romm" to "All", "local" to "Local", "rommonly" to "RomM")
            // Sort, View, Style (per system), Size, Source: each a drop-down
            val before = listOf(
                DrawerList("Sort", GRID_SORTS, vm.gameSort) { vm.chooseGameSort(it) },
                // per system, with a default for every system that hasn't got its own ("Make default")
                DrawerList(
                    "View",
                    gameViews.filter { vm.viewOn(it.first) },
                    mode,
                    makeDefault = { vm.makeViewDefault(sel, mode) },
                    useDefault = if ((sel ?: "all") in vm.sysViews) ({ vm.useDefaultView(sel) }) else null,
                    isDefault = (sel ?: "all") !in vm.sysViews
                ) { vm.choosePlatformView(sel, it) }
            ) + (sel?.let { sys ->
                // remembered per system; On Deck shows this system's games the same way
                val own = vm.hasOwnArtStyle(sys)
                listOf(DrawerList(
                    "Style", ART_STYLES, vm.artStyleFor(sys),
                    makeDefault = { vm.makeArtStyleDefault(sys, vm.artStyleFor(sys)) },
                    useDefault = if (own) ({ vm.useDefaultArtStyle(sys) }) else null,
                    isDefault = !own
                ) { vm.setArtStyle(sys, it) })
            } ?: emptyList())
            OptionsDrawer(
                vm, sel?.let { Platforms.byId(it)?.name } ?: "All games",
                before + if (!vm.rommOn()) emptyList() else listOf(DrawerList("Source", sources, if (romOnly) "rommonly" else if (withRomm) "romm" else "local") { v ->
                    vm.setFlag("romm_lists", v != "local")
                    when (v) {
                        "local" -> vm.choosePlatSource("local")
                        "rommonly" -> vm.choosePlatSource("rommonly")
                        else -> if (vm.platSource == "rommonly") vm.choosePlatSource("romm")
                    }
                }),
                size = { if (mode != "list") IconSizeSection(vm, sizeKey, mode, gridFallback = 6) },
                sizeAt = before.size,
                hiddenToggle = true
            ) {
                vm.gamesDrawer = false
                runCatching { first.requestFocus() }
            }
        }
        // slide view: the clock in the same spot as Home's (the drawer slides in around it)
        if (mode == "carousel" && list.isNotEmpty()) CornerClock(vm, Modifier.align(Alignment.TopEnd))
    }
}





/** One game in the list view's panel: its box art, a heart for favourites, the name in small capitals, play time. */
@Composable
private fun ListRow(vm: EclipseVM, g: Game, current: Boolean, fr: FocusRequester?) {
    val shape = RoundedCornerShape(9.dp)
    FocusBox(
        Modifier.fillMaxWidth().height(34.dp), shape = shape,
        onClick = { vm.play(g) }, onLongClick = { vm.menuGame = g },
        onFocusChange = { f -> if (f) vm.focused = g }, focusRequester = fr, focusKey = "g:${g.id}"
    ) {
        Row(
            Modifier.fillMaxSize()
                .background(if (current) C.accent.copy(alpha = 0.12f) else C.x(0x0FFFFFFFL))
                .border(1.dp, if (current) C.accent.copy(alpha = 0.55f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (vm.isFavGame(g)) Text("\u2665", color = C.accent, fontSize = 9.sp, modifier = Modifier.padding(end = 5.dp))
            Text(
                g.name.uppercase(), color = if (current) Color.White else C.x(0xFFCFCAC0L), fontSize = 10.sp, letterSpacing = 1.3.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (vm.isRommEntry(g)) Icon(Icons.Filled.CloudDownload, contentDescription = "On RomM", tint = C.sub, modifier = Modifier.padding(start = 6.dp).size(12.dp))
            val t = vm.playedSeconds(g)
            Text(if (t > 0) fmtDuration(t).uppercase() else "\u2014", color = if (current) C.accent else C.sub, fontSize = 8.5.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** The list view's right side: logo (or name), play line, tags, description, box art, and Play / Details / Favourite. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ListDetails(vm: EclipseVM, g: Game, modifier: Modifier) {
    LaunchedEffect(g.id) { if (vm.descOf(g) == null) vm.ensureDesc(g) }
    LaunchedEffect(g.id) { runCatching { vm.ensureInfo(g) } }
    val shot = vm.artOf(g, ArtKind.BACKDROP) ?: vm.artOf(g, ArtKind.HERO)
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            // ---- top: the screenshot / backdrop behind the logo (top left) and the box art with its disc or cartridge
            Box(Modifier.fillMaxWidth().weight(0.5f).clip(RoundedCornerShape(topEnd = 18.dp))) {
                val base = HomeDark
                if (shot != null) SteadyImage(shot, Modifier.fillMaxSize())
                // shaded toward the logo's corner and the line under it, so the writing and the art stand out
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to base.copy(alpha = 0.55f), 0.45f to base.copy(alpha = 0.1f), 1f to base.copy(alpha = 0.35f)
                )))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to base.copy(alpha = 0.35f), 0.4f to base.copy(alpha = 0f), 1f to base.copy(alpha = 0.6f)
                )))
                // the logo (or the name) in the top-left corner
                Box(Modifier.align(Alignment.TopStart).padding(start = 44.dp, top = 18.dp, end = 16.dp).widthIn(max = 300.dp)) {
                    GameHeading(vm, g, Modifier.heightIn(max = 64.dp), logoHeight = 60.dp)
                }
                // the box art on the right, the disc / cartridge tucked over its lower corner
                // (sizes from the mock-up: the box ~60% of this area's height, top right; the disc or cartridge
                // over its lower right corner, sticking out past it)
                val media = vm.artOf(g, ArtKind.MEDIA)
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val artH = maxHeight * 0.62f
                    val top = maxHeight * 0.08f
                    Cover(
                        g.name, vm.artOf(g, ArtKind.BOX),
                        Modifier.align(Alignment.TopEnd).padding(top = top, end = 52.dp).height(artH).aspectRatio(3f / 4f)
                            .shadow(14.dp, RoundedCornerShape(vm.artCorner)).clip(RoundedCornerShape(vm.artCorner))
                            .border(1.dp, C.x(0x33FFFFFFL), RoundedCornerShape(vm.artCorner))
                    )
                    if (media != null) coil.compose.AsyncImage(
                        media, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = top + artH * 0.55f, end = 26.dp).size(artH * 0.55f)
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.x(0x24FFFFFFL)))
            // ---- under the line, no box and no labels: one gold line (rating · year · genre · system · publisher ·
            // players) with the play line at its right, then the description, big and bright (the right stick scrolls it)
            Column(
                Modifier.fillMaxWidth().weight(0.5f).padding(start = 20.dp, end = 18.dp, top = 12.dp, bottom = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val rating = vm.gameDetail(g, "r").takeIf { it.isNotBlank() }?.let { "\u2605 $it" } ?: ""
                    val players = vm.gameDetail(g, "p").takeIf { it.isNotBlank() }?.let { if (it.any(Char::isLetter)) it else "$it players" } ?: ""
                    val meta = listOf(
                        rating, vm.gameDetail(g, "y"), vm.gameDetail(g, "g"), Platforms.byId(g.platform)?.short ?: "",
                        vm.gameDetail(g, "pub").ifBlank { vm.gameDetail(g, "d") }, players
                    ).filter { it.isNotBlank() }.joinToString("  \u00b7  ")
                    Text(
                        meta.uppercase(), color = C.accent, fontSize = 10.sp, letterSpacing = 1.3.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    SlidePlayLine(vm, g)
                }
                val sc = rememberScrollState()
                LaunchedEffect(g.id) { sc.scrollTo(0) }
                RightStick.Register(sc)
                Text(
                    vm.descOf(g) ?: "No description yet.",
                    color = C.x(0xFFEDE9E0L), fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 10.dp).weight(1f).verticalScroll(sc)
                )
            }
        }
    }
}




/** A game's name as a heading: its logo art when there is one (and it loads), else the name in big type. */
@Composable
fun GameHeading(vm: EclipseVM, g: Game, modifier: Modifier = Modifier, center: Boolean = false, logoHeight: androidx.compose.ui.unit.Dp = 84.dp) {
    val logo = vm.artOf(g, ArtKind.LOGO)
    var failed by remember(g.id, logo) { mutableStateOf(false) }
    if (logo != null && !failed) {
        coil.compose.AsyncImage(
            logo, contentDescription = g.name,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            alignment = if (center) Alignment.Center else Alignment.CenterStart,
            onError = { failed = true },
            modifier = modifier.height(logoHeight).widthIn(max = if (logoHeight >= 50.dp) 460.dp else 380.dp)
        )
    } else {
        Text(
            g.name, color = C.ink, fontSize = if (center) 22.sp else if (logoHeight < 60.dp) 24.sp else 28.sp, fontWeight = FontWeight.Bold,
            maxLines = if (center) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
            textAlign = if (center) TextAlign.Center else TextAlign.Start
        )
    }
}
