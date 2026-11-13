package com.eclipse.launcher.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Screen
import kotlin.math.roundToInt
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.RommDownloads
import com.eclipse.launcher.data.AppEntry
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.RaProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Content of the second display: wallpaper / Now Playing + dock, then (swipe) media controls and the screen mirror. */
@Composable
fun SecondaryContent(vm: EclipseVM, displayId: Int) {
    // the bottom screen follows Eclipse's look like the top one: theme colours, glass, light mode, font and text size
    EclipseTheme { run {
        // the media page is there only while something is playing or paused
        val nowMedia by vm.media.info.collectAsState()
        // pages: home, then media controls (while something plays), then the screen mirror
        val mediaPage = vm.flag("media") && nowMedia?.active == true
        // (Recently Played: a grid of your recent games, Settings → Dual screen → Recently played page)
        val recentPage = vm.flag("recent_page", false) && vm.recent.isNotEmpty()
        // Transfers (RomM downloads / uploads, CHD): only while one is running; last, so it never shifts the other pages
        val txPage = vm.flag("tx_page", true) && transfersRunning(vm)
        // the mirror page, like Transfers, is only there while the mirror is on (the Mirror button on Now Playing starts it)
        val mirrorOn = vm.flag("mirror_page", true)
        val mirroring by com.eclipse.launcher.media.Mirror.running.collectAsState()
        val mirrorStarting by com.eclipse.launcher.media.Mirror.starting.collectAsState()
        // (a start that never got an answer from Android's dialog doesn't keep the page forever)
        LaunchedEffect(mirrorStarting) {
            if (mirrorStarting) { kotlinx.coroutines.delay(45_000); if (!com.eclipse.launcher.media.Mirror.running.value) com.eclipse.launcher.media.Mirror.starting.value = false }
        }
        val pageIds = listOfNotNull("home", if (recentPage) "recent" else null, if (mediaPage) "media" else null, if (mirrorOn && (mirroring || mirrorStarting)) "mirror" else null, if (txPage) "transfers" else null)
        val pages = pageIds.size
        val pager = rememberPagerState(pageCount = { pages })
        LaunchedEffect(pages) { if (pager.currentPage > pages - 1) pager.scrollToPage(pages - 1) }
        // Mirror pressed: its page is added and scrolled to FIRST, then (once the page has settled) capture is asked
        // for, so the live picture starts on a page that's standing still (the old order: starting capture while the
        // page was being added and scrolled could stall both screens)
        val ctxMain = LocalContext.current
        var wantMirror by remember { mutableStateOf(false) }
        LaunchedEffect(wantMirror, pages) {
            if (!wantMirror) return@LaunchedEffect
            val i = pageIds.indexOf("mirror")
            if (i < 0) return@LaunchedEffect
            pager.animateScrollToPage(i)
            kotlinx.coroutines.delay(450)
            if (!com.eclipse.launcher.media.Mirror.running.value) com.eclipse.launcher.media.Mirror.request(ctxMain)
            wantMirror = false
        }
        // a RomM download starting brings the Transfers page up
        val downloadingNow = RommDownloads.items.value.any { it.state != RommDownloads.State.FAILED }
        var wasDownloading by remember { mutableStateOf(downloadingNow) }
        LaunchedEffect(downloadingNow, pages) {
            if (downloadingNow && !wasDownloading && vm.flag("tx_follow", true) && !mirroring) {
                pageIds.indexOf("transfers").takeIf { it >= 0 }?.let { pager.animateScrollToPage(it); wasDownloading = true }
            } else wasDownloading = downloadingNow
        }
        var mirrorPicking by remember { mutableStateOf(false) }
        val pagerScope = rememberCoroutineScope()
        var browser by remember { mutableStateOf<String?>(null) }
        // a web page from Now Playing (hold the Companion button): the browser window on this screen, like the
        // Companion's; the old built-in one only if Android won't open the window
        val openWeb: (String) -> Unit = { url ->
            val site = com.eclipse.launcher.data.Guides.Site("Web", url, url, "WEB", 0xFF444444)
            if (!GuideBrowserActivity.open(ctxMain, site, vm.session?.game?.id ?: "web", displayId, true) { browser = url }) browser = url
        }
        var walkGame by remember { mutableStateOf<Game?>(null) }
        // the Companion (ui/Companion.kt) open for this game
        // (kept in the view model, so a game's page on the top screen can open it here too)
        val companion = vm.companionBottom
        var drawer by remember { mutableStateOf(false) }
        val dockOn = vm.flag("dock2")
        // opening an app from this screen: ask top or bottom every time, unless "Remember my choice" was ticked
        var askPkg by remember { mutableStateOf<String?>(null) }
        val launch: (String) -> Unit = { pkg ->
            if (vm.appScreen[pkg] == null) askPkg = pkg else vm.openFromSecond(pkg, displayId)
        }


        // ---- dimming: after Settings → Screens → "Dim after" without a touch, a dark veil (as dark as "Dim level");
        // the first tap only wakes it. Any touch, a game starting or stopping, or the Companion opening resets the time.
        var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var dimmed by remember { mutableStateOf(false) }
        val mirroringNow by com.eclipse.launcher.media.Mirror.running.collectAsState()
        LaunchedEffect(vm.session?.game?.id, companion) { lastTouch = System.currentTimeMillis(); dimmed = false }
        LaunchedEffect(lastTouch, vm.dim2After, mirroringNow) {
            dimmed = false
            // never while the mirror is showing the top screen (you're watching it)
            if (vm.dim2After <= 0 || mirroringNow) return@LaunchedEffect
            delay(vm.dim2After * 1000L)
            dimmed = true
        }
        val veil by androidx.compose.animation.core.animateFloatAsState(if (dimmed) vm.dim2Level / 100f else 0f, androidx.compose.animation.core.tween(if (dimmed) 1200 else 200), label = "dim2")

        Box(
            Modifier.fillMaxSize().background(Color.Black)
                // every touch counts (seen first, then passed on untouched)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            lastTouch = System.currentTimeMillis()
                        }
                    }
                }
        ) {
          // frosted look: the page and dock blur behind the drawer and the screen bubble.
          // Not drawn at all while the walkthrough browser covers the screen: every redraw of the web page used to
          // redraw this (blurred wallpaper and all) underneath it, which is what made the browser so slow.
          if (browser == null && companion == null) Box(Modifier.fillMaxSize().then(behindBlur(drawer || askPkg != null))) {
            HorizontalPager(pager, userScrollEnabled = !mirrorPicking) { p ->
                when (pageIds.getOrNull(p)) {
                    "media" -> MediaPage(vm)
                    "recent" -> RecentPage(vm)
                    "mirror" -> MirrorPage(vm) { mirrorPicking = it }
                    "transfers" -> TransfersPage(vm)
                    else -> NowPlayingPage(
                        vm, dockOn,
                        openMirror = if (mirrorOn) ({
                            if (!mirroring) com.eclipse.launcher.media.Mirror.starting.value = true // shows the page
                            wantMirror = true
                            Unit
                        }) else null,
                        editWalk = { walkGame = it },
                        openCompanion = { vm.companionBottom = it }
                    ) { openWeb(it) }
                }
            }
            if (dockOn && pager.currentPage == 0) {
                BottomDock(vm, launch, { drawer = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
                // the grab handle under the dock: swipe up (or tap) for the app drawer
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.5f).height(20.dp)
                        .pointerInput(Unit) { detectTapGestures { drawer = true } }
                        .pointerInput(Unit) {
                            var acc = 0f
                            detectVerticalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { _, dy ->
                                acc += dy
                                if (acc < -30f) { acc = 0f; drawer = true }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x66FFFFFFL))) }
            }
            // something is playing: a chip on the main page that jumps to the media controls
            val playing by vm.media.info.collectAsState()
            val scope = rememberCoroutineScope()
            val track = playing
            if (mediaPage && pager.currentPage == 0 && track != null && track.active) {
                FocusBox(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp).height(36.dp),
                    shape = RoundedCornerShape(18.dp),
                    onClick = { scope.launch { pager.animateScrollToPage(pageIds.indexOf("media").coerceAtLeast(0)) } }
                ) {
                    Box(Modifier.matchParentSize().background(shade(0.4f)))
                    Text(
                        (if (track.playing) "\u266A  " else "\u2016  ") + track.title.ifBlank { "Media" } +
                            (if (track.artist.isNotBlank()) " \u00b7 ${track.artist}" else "") + "  \u203A",
                        color = C.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp).widthIn(max = 320.dp)
                    )
                }
            }
            if (pages > 1 && pageIds.getOrNull(pager.currentPage) != "mirror") {
                Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (dockOn && pager.currentPage == 0) 2.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(pages) { i ->
                        Box(Modifier.size(if (pager.currentPage == i) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (pager.currentPage == i) C.accent else C.x(0x59FFFFFFL)))
                    }
                }
            }
          } // end of the blurred layer
            AnimatedVisibility(
                visible = drawer,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                DrawerOverlay(vm, launch) { drawer = false }
            }
            askPkg?.let { pkg ->
                val app = vm.apps.firstOrNull { it.pkg == pkg }
                if (app != null) {
                    ScreenBubble(
                        vm, app,
                        onPick = { where, rememberIt ->
                            if (rememberIt) vm.setAppScreen(pkg, where)
                            askPkg = null
                            vm.openOnScreen(pkg, where, displayId)
                        },
                        onCancel = { askPkg = null }
                    )
                }
            }
            walkGame?.let { g -> WalkSheet(vm, g, { openWeb(it) }) { walkGame = null } }
            companion?.let { g -> CompanionHub(vm, g) { vm.companionBottom = null } }
            browser?.let { url -> BrowserOverlay(url) { browser = null } }
            // the veil: while it's down, a tap only wakes the screen
            if (veil > 0.001f) Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil))
                    .then(if (dimmed) Modifier.pointerInput(Unit) { detectTapGestures { lastTouch = System.currentTimeMillis() } } else Modifier)
            )
        }
    } }
}

/**
 * The dock along the bottom: two app slots, the app-drawer button (always in the centre), two app slots.
 * Slot width follows the screen width so the icons are evenly spaced; empty slots keep the centre in place.
 * Hold an icon and drag it to another slot to move it; hold without moving to remove it. Its look (glass, solid,
 * floating icons or a full-width bar) and icon size come from Settings → Dual screen.
 */
@Composable
private fun BottomDock(vm: EclipseVM, launch: (String) -> Unit, openDrawer: () -> Unit, modifier: Modifier) {
    var removePkg by remember { mutableStateOf<String?>(null) }
    // each app in the slot you put it in (empty slots stay empty); the app drawer button sits in the middle
    val bySlot: List<AppEntry?> = vm.dock2.map { pkg -> if (pkg.isEmpty()) null else vm.apps.firstOrNull { it.pkg == pkg } }
    val pinned = bySlot.filterNotNull()
    val slots: List<AppEntry?> = listOf(bySlot.getOrNull(0), bySlot.getOrNull(1), null, bySlot.getOrNull(2), bySlot.getOrNull(3))
    val style = vm.dock2Style
    val bar = style == "bar"
    // the icon being dragged, and how far it has moved (px)
    var dragPkg by remember { mutableStateOf<String?>(null) }
    var dragX by remember { mutableFloatStateOf(0f) }
    // an icon that was just held: the tap that ends the hold mustn't open it too
    var held by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(
        modifier.fillMaxWidth()
            // swipe up on the dock: the app drawer
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { ch, dy ->
                    acc += dy
                    if (acc < -40f) { acc = 0f; ch.consume(); openDrawer() }
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        val scale = when (vm.dock2Size) { "small" -> 0.82f; "large" -> 1.18f; else -> 1f }
        val slot = (((if (bar) maxWidth else maxWidth - 64.dp) / 5) * (if (bar) 1f else scale)).coerceIn(56.dp, 124.dp)
        val icon = ((if (bar) maxWidth / 5 else slot) * 0.7f).coerceAtMost(58.dp * scale)
        // each slot's width, read here: inside the Row below, the screen's width can't be reached
        val cellW = if (bar) maxWidth / 5 else slot
        val corner = RoundedCornerShape(icon * 0.28f)
        val slotPx = with(androidx.compose.ui.platform.LocalDensity.current) { slot.toPx() }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rem = removePkg?.let { p -> pinned.firstOrNull { it.pkg == p } }
            if (rem != null) {
                Row(
                    Modifier.dockPanel(RoundedCornerShape(14.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(vm.nameOf(rem), color = C.ink, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                    Pill("Remove from dock", primary = true) { vm.toggleDock2(rem.pkg); removePkg = null }
                    Pill("Cancel") { removePkg = null }
                }
            }
            // all in the theme's glass (its colour and strength, light mode too)
            val edge = C.x(0x24FFFFFFL)
            val tint = glassTint(ThemeColors.light.value)
            val panel = when (style) {
                "solid" -> Modifier.dockPanel(RoundedCornerShape(20.dp))
                "clear" -> Modifier
                "bar" -> Modifier.fillMaxWidth().background(tint).drawBehind { drawLine(edge, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
                // frosted glass with a hairline edge, like the top screen's docks
                else -> Modifier.panel2(RoundedCornerShape(20.dp))
            }
            Row(
                panel.padding(horizontal = if (bar) 0.dp else 10.dp, vertical = 9.dp),
                horizontalArrangement = if (bar) Arrangement.SpaceEvenly else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                slots.forEachIndexed { i, app ->
                    val dragging = app != null && app.pkg == dragPkg
                    Box(
                        Modifier.width(cellW).zIndex(if (dragging) 1f else 0f),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            i == 2 -> FocusBox(Modifier.size(icon), shape = corner, onClick = openDrawer) {
                                Box(
                                    Modifier.fillMaxSize().background(C.accent.copy(alpha = 0.12f)).border(1.5.dp, C.accent, corner),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Apps, contentDescription = "All apps", tint = C.accent, modifier = Modifier.size(icon * 0.55f))
                                }
                            }
                            app != null -> Box(
                                Modifier.size(icon)
                                    .graphicsLayer {
                                        if (dragging) { translationX = dragX; scaleX = 1.15f; scaleY = 1.15f; shadowElevation = 12.dp.toPx() }
                                    }
                                    .then(if (style == "clear") Modifier.shadow(10.dp, corner) else Modifier)
                                    .clip(corner)
                                    // tap: open it
                                    .pointerInput(app.pkg) { detectTapGestures { if (held == app.pkg) held = null else launch(app.pkg) } }
                                    // hold and drag: move it to another slot; hold and let go without moving: remove it
                                    .pointerInput(app.pkg) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragPkg = app.pkg; dragX = 0f; held = app.pkg },
                                            onDragEnd = {
                                                val moved = Math.round(dragX / slotPx)
                                                if (kotlin.math.abs(dragX) < slotPx * 0.35f) removePkg = app.pkg
                                                else {
                                                    held = null
                                                    var to = (i + moved).coerceIn(0, 4)
                                                    if (to == 2) to = if (moved > 0) 3 else 1
                                                    vm.moveDock2(app.pkg, if (to < 2) to else to - 1)
                                                }
                                                dragPkg = null; dragX = 0f
                                            },
                                            onDragCancel = { dragPkg = null; dragX = 0f }
                                        ) { ch, d -> ch.consume(); dragX += d.x }
                                    }
                            ) { AppIcon(app, Modifier.fillMaxSize()) }
                            else -> Spacer(Modifier.size(icon))
                        }
                    }
                }
            }
        }
    }
}

/** App drawer for the second screen: a sheet with search, the dock's pinned apps, then every app by letter. Long-press pins / unpins. */
@Composable
private fun DrawerOverlay(vm: EclipseVM, launch: (String) -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember(vm.apps, vm.renames) { vm.apps.sortedBy { vm.nameOf(it).lowercase() } }
    val shown = if (query.isBlank()) all else all.filter { vm.nameOf(it).contains(query.trim(), ignoreCase = true) }
    val groups = remember(shown) {
        shown.groupBy { a -> vm.nameOf(a).firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' }?.toString() ?: "#" }
            .toSortedMap(compareBy { if (it == "#") "{" else it })
    }
    // typing: the bottom screen's window takes focus so the keyboard can type into the search box
    val searchFr = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var typing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(false) } }
    LaunchedEffect(typing) {
        if (typing) {
            com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(true)
            delay(80)
            runCatching { searchFr.requestFocus() }
            keyboard?.show()
        }
    }
    // drag the sheet's top down to close it (it follows your finger; let go past a third of the way and it shuts)
    var dragY by remember { mutableFloatStateOf(0f) }
    val sheetTop = Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = { if (dragY > size.height * 1.2f || dragY > 140f) close() else dragY = 0f },
            onDragCancel = { dragY = 0f }
        ) { ch, dy -> ch.consume(); dragY = (dragY + dy).coerceAtLeast(0f) }
    }
    // tap above the sheet to close it
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }) {
        val sheet = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 6.dp, top = 30.dp)
                .offset { IntOffset(0, dragY.roundToInt()) }
                .dockPanel(sheet)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Box(Modifier.fillMaxWidth().then(sheetTop).padding(top = 9.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x66FFFFFFL)))
            }
            Row(Modifier.fillMaxWidth().then(sheetTop).padding(start = 18.dp, end = 14.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Apps", color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${all.size} APPS", color = C.sub, fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 10.dp, top = 3.dp).weight(1f))
                FocusBox(Modifier.size(34.dp), shape = CircleShape, onClick = close) {
                    Box(Modifier.fillMaxSize().background(C.x(0x1AFFFFFFL)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = C.ink, modifier = Modifier.size(18.dp))
                    }
                }
            }
            // search
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 4.dp).fillMaxWidth().height(38.dp)
                    .pointerInput(Unit) { detectTapGestures { typing = true } }
                    .clip(RoundedCornerShape(9.dp)).background(C.x(0x59000000L)).border(1.dp, C.x(0x1AFFFFFFL), RoundedCornerShape(9.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(androidx.compose.material.icons.Icons.Filled.Search, null, tint = C.sub, modifier = Modifier.size(18.dp))
                Box(Modifier.padding(start = 8.dp).weight(1f)) {
                    if (query.isEmpty()) Text("Search apps", color = C.sub, fontSize = 13.sp)
                    androidx.compose.foundation.text.BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = C.ink, fontSize = 13.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFr)
                    )
                    // a tap on the box turns typing on first (the window can't take the keyboard until then)
                    if (!typing) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { typing = true } })
                }
                if (query.isNotEmpty()) Text("Clear", color = C.accent, fontSize = 12.sp, modifier = Modifier.pointerInput(Unit) { detectTapGestures { query = "" } })
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cols = ((maxWidth - 36.dp - 28.dp) / 76.dp).toInt().coerceIn(3, 8)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                    groups.forEach { (letter, apps) ->
                        item(key = "l:$letter") {
                            Column(Modifier.padding(horizontal = 18.dp)) {
                                // the letter as a gold section label with a hairline, like the top screen's sections
                                Box(Modifier.padding(top = 10.dp, bottom = 2.dp)) { DropSection(letter) }
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        apps.chunked(cols).forEach { line ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                line.forEach { app -> Box(Modifier.weight(1f)) { BottomDrawerTile(vm, app, launch, close) } }
                                                repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (shown.isEmpty()) item { Text("No apps match \u201c$query\u201d.", color = C.sub, modifier = Modifier.padding(18.dp)) }
                    // last: close Eclipse on this screen (e.g. when you've switched to another launcher); it comes back with Eclipse
                    item(key = "close") {
                        val ctx = LocalContext.current
                        Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp)) {
                            MenuRow("Close Eclipse on this screen", value = "\u2715", danger = true) {
                                android.widget.Toast.makeText(ctx, "Bottom screen closed. Open Eclipse to bring it back.", android.widget.Toast.LENGTH_SHORT).show()
                                com.eclipse.launcher.media.SecondScreenHost.close?.invoke()
                            }
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
                        .background(Brush.verticalGradient(listOf(panelFade(0f), panelFade(0.94f)))),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text("TAP TO OPEN  \u00b7  HOLD TO DOCK  \u00b7  SWIPE DOWN TO CLOSE", color = C.sub, fontSize = 8.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(bottom = 9.dp))
                }
            }
        }
    }
}


@Composable
private fun BottomDrawerTile(vm: EclipseVM, app: AppEntry, launch: (String) -> Unit, close: () -> Unit) {
    FocusBox(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        onClick = { close(); launch(app.pkg) },
        onLongClick = { vm.toggleDock2(app.pkg) }
    ) { focused ->
        Column(
            Modifier.fillMaxWidth().background(if (focused) C.x(0x12FFFFFFL) else Color.Transparent).padding(vertical = 7.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                AppIcon(app, Modifier.size(46.dp))
                if (app.pkg in vm.dock2) {
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(10.dp).clip(CircleShape).background(C.accent).border(1.5.dp, C.x(0xFF101014L), CircleShape))
                }
            }
            Text(vm.nameOf(app), color = if (focused) Color.White else C.x(0xFFD8D8DDL), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

/**
 * "Open on which screen?", shown when an app is opened from this screen: the menus' look (a glass card of slim
 * rows), Top screen and Bottom screen one under the other, and a Remember switch that skips it for that app next time.
 */
@Composable
private fun ScreenBubble(vm: EclipseVM, app: AppEntry, onPick: (String, Boolean) -> Unit, onCancel: () -> Unit) {
    var rememberIt by remember(app.pkg) { mutableStateOf(false) }
    MenuSheet(
        "Open app", vm.nameOf(app), "Which screen should it open on?", onDismiss = onCancel,
        image = { AppIcon(app, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))) }
    ) {
        MenuRow("Top screen", value = "\u25B2") { onPick("top", rememberIt) }
        MenuRow("Bottom screen", value = "\u25BC") { onPick("bottom", rememberIt) }
        MenuRow("Remember my choice", value = if (rememberIt) "On" else "Off", selected = rememberIt) { rememberIt = !rememberIt }
        if (rememberIt) Text(
            "Reset it in Settings \u2192 Screens \u2192 Where apps open.",
            color = C.sub, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(horizontal = 2.dp)
        )
        MenuRow("Cancel") { onCancel() }
    }
}

private val textShadow = TextStyle(shadow = Shadow(Color(0x99000000), Offset(0f, 2f), 8f))

/** "00:13", or "1:02:13" once past an hour. */
private fun sessionClock(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60)

/**
 * Page 0 of the second screen. Idle: the highlighted game's backdrop. Playing: the Now Playing card from the draft:
 * box art with a system tag, NOW PLAYING, title, system, total playtime and session timer, RetroAchievements
 * progress, and frosted buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingPage(
    vm: EclipseVM, dockOn: Boolean, openMirror: (() -> Unit)? = null,
    editWalk: (Game) -> Unit = {}, openCompanion: (Game) -> Unit = {}, openBrowser: (String) -> Unit
) {
    // Settings → Dual screen → Show Now Playing: off keeps the clock and wallpaper up while a game runs
    val s = vm.session?.takeIf { vm.flag("np_show", true) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    LaunchedEffect(vm.shotStatus) {
        if (vm.shotStatus != null) { delay(3500); vm.shotStatus = null }
    }
    var ra by remember(s?.game?.id) { mutableStateOf<RaProgress?>(null) }
    LaunchedEffect(s?.game?.id) { ra = s?.game?.let { vm.loadRa(it) } }
    val bottomPad = if (dockOn) 104.dp else 24.dp

    Box(Modifier.fillMaxSize().background(HomeDark)) {
        if (s == null) {
            // ---- nothing running: the game highlighted on the top screen (its hero or backdrop), else the wallpaper
            val g = rememberSettled(vm.focused?.takeIf { vm.screen != Screen.SETTINGS && !vm.searchOpen })
            val art = g?.let { vm.artOf(it, ArtKind.HERO) ?: vm.artOf(it, ArtKind.BACKDROP) } ?: vm.wallpaperBottom()
            SteadyImage(art, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(shade(0.35f), shade(0.15f), shade(0.75f)))))
            // stay on a game for half a second: its details slide in
            var showInfo by remember { mutableStateOf(false) }
            LaunchedEffect(g?.id) { showInfo = false; if (g != null) { delay(500); showInfo = true } }
            AnimatedVisibility(!showInfo || g == null, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(start = 22.dp, top = 14.dp)) {
                    val h24 = vm.flag("h24", false)
                    Text(
                        java.text.SimpleDateFormat(if (h24) "HH:mm" else "h:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
                        color = C.ink, fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp, style = textShadow
                    )
                    Text(
                        java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(java.util.Date(now)).uppercase(),
                        color = C.x(0xFFE6E3DAL), fontSize = 9.sp, letterSpacing = 1.8.sp, style = textShadow
                    )
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)) { StatusPill(now) }
            AnimatedVisibility(
                visible = showInfo && g != null,
                enter = slideInHorizontally(tween(380)) { -it / 3 } + fadeIn(tween(380)),
                exit = slideOutHorizontally(tween(260)) { -it / 3 } + fadeOut(tween(260))
            ) {
                if (g != null) GameInfoPanel(vm, g, Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 58.dp, bottom = bottomPad + 6.dp))
            }
        } else {
            // ---- a game is running: its art, blurred, under the right dock's rows ----
            val game = s.game
            val elapsed = ((now - s.startMs) / 1000).coerceAtLeast(0)
            val plat = Platforms.byId(game.platform)
            val bg = vm.artOf(game, ArtKind.HERO) ?: vm.artOf(game, ArtKind.BACKDROP) ?: vm.wallpaperBottom()
            if (bg != null) AsyncImage(bg, null, Modifier.fillMaxSize().softBlur(18.dp), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(shade(0.6f)))
            Box(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)) { StatusPill(now) }
            BoxWithConstraints(Modifier.fillMaxSize().padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = bottomPad)) {
                val coverW = (maxWidth * 0.29f).coerceIn(96.dp, 180.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(C.accent))
                        Text("NOW PLAYING", color = C.accent, fontSize = 9.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 7.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.width(coverW).aspectRatio(3f / 4f).shadow(18.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                                .border(1.dp, C.x(0x1FFFFFFFL), RoundedCornerShape(10.dp))
                        ) { Cover(game.name, vm.artOf(game, ArtKind.BOX), Modifier.fillMaxSize()) }
                        Column(
                            Modifier.weight(1f).panel2(RoundedCornerShape(18.dp)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(game.name, color = C.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                            NpRow("System", plat?.short ?: if (game.platform == "android") "Android" else game.platform)
                            val emu = if (game.platform == "android") null else vm.emuFor(game)?.label
                            if (emu != null) NpRow("Emulator", emu)
                            NpRow("This session", sessionClock(elapsed))
                            NpRow("Total", fmtDuration(vm.playedSeconds(game) + elapsed))
                            ra?.let { p ->
                                val frac = if (p.total == 0) 0f else p.earned / p.total.toFloat()
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(C.x(0x0FFFFFFFL))
                                        .border(1.dp, C.x(0x12FFFFFFL), RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("ACHIEVEMENTS", color = C.x(0xFFCFCAC0L), fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                                        Text("${p.earned} / ${p.total}", color = C.accent, fontSize = 9.sp, letterSpacing = 1.2.sp)
                                    }
                                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.x(0x26FFFFFFL))) {
                                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(C.accent))
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.flag("npb_shot")) NpButton("Screenshot", Icons.Filled.PhotoCamera, Modifier.weight(1f)) { vm.takeScreenshot() }
                        // the screen mirror: starts it if needed, and swipes over to its page
                        if (openMirror != null) {
                            val mirroring by com.eclipse.launcher.media.Mirror.running.collectAsState()
                            NpButton(if (mirroring) "Mirror on" else "Mirror", Icons.Filled.ScreenShare, Modifier.weight(1f)) { openMirror() }
                        }
                        if (vm.flag("npb_walk")) {
                            // tap: the Companion (guides, manual); hold: open a web address of your own in the browser
                            NpButton("Companion", Icons.Filled.MenuBook, Modifier.weight(1f), onLongClick = { editWalk(game) }) {
                                openCompanion(game)
                            }
                        }
                    }
                    vm.shotStatus?.let { Text(it, color = C.ink, fontSize = 12.sp) }
                }
            }
        }
    }
}

/**
 * The highlighted game's details on the bottom screen: box art, its system as a gold section label, the logo (or
 * name), year / genre line and play time, and the description scrolling slowly by itself.
 */
@Composable
private fun GameInfoPanel(vm: EclipseVM, g: com.eclipse.launcher.data.Game, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.panel2(shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.fillMaxHeight(0.86f).aspectRatio(3f / 4f, matchHeightConstraintsFirst = true).shadow(14.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                .border(1.dp, C.x(0x1FFFFFFFL), RoundedCornerShape(10.dp))
        ) { Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            DropSection(Platforms.byId(g.platform)?.name ?: if (g.platform == "android") "Android" else g.platform)
            GameHeading(vm, g, Modifier.padding(top = 8.dp), logoHeight = 30.dp)
            GameMetaRow(vm, g, Modifier.padding(top = 6.dp))
            val text = vm.descOf(g)?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val scroll = rememberScrollState()
                // read it slowly from top to bottom, wait, start again
                LaunchedEffect(g.id, scroll.maxValue) {
                    scroll.scrollTo(0)
                    if (scroll.maxValue <= 0) return@LaunchedEffect
                    while (true) {
                        delay(2500)
                        scroll.animateScrollTo(scroll.maxValue, tween((scroll.maxValue * 28).coerceIn(3000, 60000), easing = androidx.compose.animation.core.LinearEasing))
                        delay(3000)
                        scroll.animateScrollTo(0, tween(600))
                    }
                }
                Box(Modifier.padding(top = 10.dp).weight(1f).fillMaxWidth()) {
                    Text(
                        text, color = C.x(0xFFE4E0D8L), fontSize = 11.5.sp, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = false)
                    )
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(panelFade(0f), panelFade(0.9f)))))
                }
            }
        }
    }
}

/** A Now Playing detail: name in small capitals, value in gold, on a slim row. */
@Composable
private fun NpRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(9.dp)).background(C.x(0x0FFFFFFFL))
            .border(1.dp, C.x(0x12FFFFFFL), RoundedCornerShape(9.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.sp, letterSpacing = 1.4.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(value.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A Now Playing button: a slim row with a gold icon, like the top screen's. */
@Composable
private fun NpButton(text: String, icon: ImageVector, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    FocusBox(modifier.height(36.dp), shape = shape, onClick = onClick, onLongClick = onLongClick) {
        Row(
            Modifier.fillMaxSize().background(C.x(0x0FFFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), shape).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = C.accent, modifier = Modifier.size(14.dp))
            Text(text.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Wi-Fi and battery, top right of the bottom screen. */
@Composable
private fun StatusPill(now: Long) {
    val ctx = LocalContext.current
    // read again every half minute (the clock ticks every second; no need to ask Android that often)
    val tick = now / 30_000
    val battery = remember(tick) {
        ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { i ->
            val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val charging = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1).let {
                it == android.os.BatteryManager.BATTERY_STATUS_CHARGING || it == android.os.BatteryManager.BATTERY_STATUS_FULL
            }
            if (lvl >= 0 && scale > 0) (lvl * 100 / scale) to charging else null
        }
    }
    val wifi = remember(tick) {
        try {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (e: Exception) { false }
    }
    Row(
        Modifier.panel2(RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(if (wifi) "Wi-Fi" else "Offline", color = C.x(0xFFDDDDDDL), fontSize = 12.sp)
        battery?.let { (pct, charging) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp, 11.dp).border(1.5.dp, C.x(0xFFDDDDDDL), RoundedCornerShape(3.dp)).padding(2.dp)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).clip(RoundedCornerShape(1.dp)).background(if (pct <= 15 && !charging) Color(0xFFFF6B6B) else C.accent))
                }
                Text((if (charging) "\u26A1" else "") + "$pct%", color = C.x(0xFFDDDDDDL), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun MediaPage(vm: EclipseVM) {
    val info by vm.media.info.collectAsState()
    val m = info ?: return // (the page only exists while something is playing or paused)
    Box(Modifier.fillMaxSize().background(C.x(0xFF16161AL))) {
        // the cover art fills the page, darkened so the text and controls read on top of it
        val bmp = m.art
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A2440), C.x(0xFF121216L)))))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(shade(0.55f), shade(0.7f), shade(0.9f)))))

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
        ) {
            Text(
                m.title.ifBlank { "Unknown" }, color = C.ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            if (m.artist.isNotBlank()) {
                Text(m.artist, color = C.x(0xCCFFFFFFL), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaBtn(Icons.Filled.SkipPrevious) { vm.media.prev() }
                MediaBtn(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, big = true) { vm.media.toggle() }
                MediaBtn(Icons.Filled.SkipNext) { vm.media.next() }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun MediaBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, big: Boolean = false, onClick: () -> Unit) {
    // see-through "glass" buttons over the cover art
    FocusBox(Modifier.size(if (big) 72.dp else 52.dp), shape = CircleShape, onClick = onClick) {
        Box(
            Modifier.fillMaxSize()
                .background(if (big) C.x(0x40FFFFFFL) else C.x(0x26FFFFFFL))
                .border(1.dp, C.x(0x40FFFFFFL), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = C.ink, modifier = Modifier.size(if (big) 38.dp else 26.dp))
        }
    }
}

/** Opens a game's walkthrough (GameFAQs, or the address you typed) in Eclipse's own viewer. */
private fun openWalk(vm: EclipseVM, g: Game, openViewer: (String) -> Unit) = openViewer(vm.walkUrl(g, vm.walkText(g)))

/** Hold the Walkthrough button: change the GameFAQs search, or type a web address. Blank puts GameFAQs back. */
@Composable
private fun WalkSheet(vm: EclipseVM, g: Game, openViewer: (String) -> Unit, close: () -> Unit) {
    var text by remember(g.id) { mutableStateOf(vm.walkText(g)) }
    // typing: the bottom screen's window takes focus so the keyboard can type into the box
    val fieldFr = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var typing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(false) } }
    LaunchedEffect(typing) {
        if (typing) {
            com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(true)
            delay(80)
            runCatching { fieldFr.requestFocus() }
            keyboard?.show()
        }
    }
    fun go() {
        keyboard?.hide()
        vm.setWalkText(g, text)
        close()
        openWalk(vm, g, openViewer)
    }
    MenuSheet("Companion", g.name, "Search GameFAQs, or type a web address. Leave it blank for GameFAQs.", onDismiss = close) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 38.dp).clip(RoundedCornerShape(9.dp)).background(C.x(0x59000000L))
                .border(1.dp, C.x(0x1AFFFFFFL), RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            androidx.compose.foundation.text.BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(color = C.ink, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFr)
            )
            // a tap on the box turns typing on first (the window can't take the keyboard until then)
            if (!typing) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { typing = true } })
        }
        MenuRow("Open", gold = true) { go() }
        // blank = GameFAQs for the game again
        MenuRow("Back to GameFAQs", note = "Clears the change") { text = ""; vm.setWalkText(g, ""); close(); openWalk(vm, g, openViewer) }
        MenuRow("Cancel") { close() }
    }
}

@Composable
private fun BrowserOverlay(url: String, close: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().background(C.bg).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Close", primary = true, onClick = close)
            Spacer(Modifier.width(12.dp))
            Text("Companion", fontWeight = FontWeight.Bold, color = C.ink)
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    // guide sites keep their state in web storage; without it their scripts fail and retry
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = true // no auto-playing video ads
                    smoothScrolling()
                    webViewClient = object : WebViewClient() {
                        // ads and trackers (data/AdBlock.kt) get an empty answer instead of loading
                        override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? =
                            if (com.eclipse.launcher.data.AdBlock.blocked(request?.url?.host))
                                android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            else null
                    }
                    loadUrl(url)
                }
            },
            // closing the browser frees the page (it used to keep running until the screen was rebuilt)
            onRelease = { w -> runCatching { w.stopLoading(); w.destroy() } }
        )
    }
}


/**
 * The bottom screen's Recently Played page: your recent games as a grid of covers; a tap starts one (on the top screen,
 * or where its page says). Settings → Dual screen → Recently played page turns it off.
 */
@Composable
private fun RecentPage(vm: EclipseVM) {
    val games = remember(vm.recent, vm.games) { vm.recent.filter { it !in vm.hiddenGames }.mapNotNull { id -> vm.games.firstOrNull { it.id == id } } }
    Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 18.dp)) {
        Text("RECENTLY PLAYED", color = C.accent, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(88.dp),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            items(games.size, key = { games[it].id }) { i ->
                val g = games[i]
                Column {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(vm.artCorner))
                            .pointerInput(g.id) { detectTapGestures { vm.play(g) } }
                    ) {
                        Cover(g.name, vm.artOf(g, com.eclipse.launcher.data.ArtKind.BOX), Modifier.fillMaxSize())
                    }
                    Text(g.name, color = C.x(0xE6FFFFFFL), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 1.dp))
                }
            }
        }
    }
}

/** The bottom screen's shading over wallpapers and art: the theme's background colour (near-black; off-white in light mode). */
@Composable
private fun shade(alpha: Float): Color = HomeDark.copy(alpha = alpha)

/** The colour a glass panel fades into at its bottom edge: the theme's glass colour, else the look's own. */
@Composable
private fun panelFade(alpha: Float): Color =
    (ThemeColors.glass.value.takeIf { it.isSpecified } ?: if (ThemeColors.light.value) Color(0xFFF2EEE6) else Color(0xFF121214)).copy(alpha = alpha)

/** A bottom screen panel in the theme's glass (colour, strength, light mode) with a hairline edge. */
@Composable
private fun Modifier.panel2(shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.clip(shape).background(glassTint(ThemeColors.light.value)).border(1.dp, C.x(0x24FFFFFFL), shape)
