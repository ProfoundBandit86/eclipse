package com.eclipse.launcher.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.exp
import kotlin.math.abs
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.AppEntry
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.PlatKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.HomeSection
import com.eclipse.launcher.data.PlatformDef
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Dock(vm: EclipseVM, focusRequester: FocusRequester? = null, width: Dp = 200.dp, labels: Boolean = true) {
    val lastPinnedFr = remember { FocusRequester() } // the pinned app nearest the Settings button: Up from Settings lands there
    // (the bottom padding keeps Settings clear of the dock's rounded corner while it slides)
    Column(Modifier.fillMaxHeight().width(width).padding(top = 14.dp, bottom = 18.dp), horizontalAlignment = Alignment.Start) {
        // Games = the platform grid under Home's On Deck row (a system's own game list counts too)
        val onGrid = (vm.screen == Screen.HOME && vm.homeSection == HomeSection.PLATFORMS) || vm.screen == Screen.PLATFORMS
        val onApps = (vm.screen == Screen.HOME && vm.homeSection == HomeSection.APPS) || vm.screen == Screen.APPS
        val onHome = vm.screen == Screen.HOME && !onGrid && !onApps
        val dockArt = { n: String -> vm.themeImage(vm.theme?.dock?.get(n)) }
        // On Home, moving Up / Down through Home, Games and Apps slides the screen to that row, as if you'd scrolled there
        // (only for Up / Down inside the dock: just entering it, which lands on Home, leaves the screen where it was)
        val preview = { s: HomeSection -> { vm.settingsPeek = false; if (vm.screen == Screen.HOME && vm.dockNav) vm.homeSection = s } }
        val notSettings = { vm.settingsPeek = false }

        // ---- pages, in your order (Settings → General → Left dock → Pages), then your pinned apps; this part scrolls
        // if the screen is short. Pressing Left into the dock lands on the page you're on (the first one shown if that
        // page is hidden from the dock), and that page is always marked.
        // (Home / Recently Played isn't offered until a game has been played)
        val shown = vm.scopes().filter { it !in vm.scopeHidden && (it != "recent" || vm.homeGames().isNotEmpty()) }
        val current = when {
            vm.screen == Screen.SETTINGS -> "settings"
            onGrid -> "systems"
            onApps -> "apps"
            onHome -> "recent"
            else -> null
        }
        val landOn = current?.takeIf { it == "settings" || it in shown } ?: shown.firstOrNull() ?: "settings"
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            shown.forEach { id ->
                val fr = if (id == landOn) focusRequester else null
                when (id) {
                    "search" -> {
                        DockSearch(labels, onFocus = notSettings, fr = fr) { vm.searchOpen = true }
                        Spacer(Modifier.height(7.dp))
                    }
                    "recent" -> DockButton(Icons.Filled.Home, "Home", labels, onHome, fr, dockArt("home"), onFocus = preview(HomeSection.RECENTS)) {
                        vm.homeSection = HomeSection.RECENTS
                        vm.go(Screen.HOME)
                    }
                    "systems" -> DockButton(Icons.Filled.SportsEsports, "Games", labels, onGrid, fr, dockArt("games"), onFocus = preview(HomeSection.PLATFORMS)) {
                        vm.homeSection = HomeSection.PLATFORMS
                        vm.go(Screen.HOME)
                    }
                    // the app drawer is the third row of Home, under the platforms
                    "apps" -> DockButton(Icons.Filled.Apps, "Apps", labels, onApps, fr, dockArt("apps"), onFocus = preview(HomeSection.APPS)) {
                        vm.homeSection = HomeSection.APPS
                        vm.go(Screen.HOME)
                    }
                    else -> {}
                }
            }
            // ---- pinned systems, then pinned apps
            val pinned = vm.dock.mapNotNull { pkg -> vm.apps.firstOrNull { it.pkg == pkg } }.take(4)
            val pinnedSys = vm.dockSystems.mapNotNull { id -> Platforms.byId(id) }
            Row(Modifier.fillMaxWidth().padding(start = if (labels) 22.dp else 0.dp, end = if (labels) 14.dp else 0.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center) {
                if (labels) Text("PINNED", color = C.sub.copy(alpha = 0.8f), fontSize = 8.sp, letterSpacing = 2.sp, modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.then(if (labels) Modifier.weight(1f) else Modifier.width(22.dp)).height(1.dp).background(C.x(0x1AFFFFFFL)))
            }
            pinnedSys.forEachIndexed { si, p ->
                val here = vm.screen == Screen.PLATFORMS && (vm.platformSel ?: Platforms.ALL) == p.id
                Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.CenterStart) {
                    if (here) Box(Modifier.align(Alignment.CenterStart).size(3.dp, 18.dp).clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)).background(C.accent))
                    FocusBox(
                        Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp), shape = RoundedCornerShape(10.dp),
                        onClick = { vm.openPlatform(p.id) },
                        onLongClick = { vm.platformMenu = p.id }, // long-press or Select: the system's menu (Unpin is there)
                        onFocusChange = { f -> if (f) notSettings() },
                        focusRequester = if (pinned.isEmpty() && si == pinnedSys.lastIndex) lastPinnedFr else null,
                        focusKey = "docksys:${p.id}"
                    ) {
                        Row(
                            Modifier.fillMaxSize().background(if (here) C.accent.copy(alpha = 0.16f) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center
                        ) {
                            Box(
                                Modifier.padding(start = if (labels) 7.dp else 0.dp).size(26.dp).clip(RoundedCornerShape(7.dp)).background(platformTint(p.id)),
                                contentAlignment = Alignment.Center
                            ) {
                                val pic = vm.platformCardIcon(p.id)
                                if (pic != null) AsyncImage(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Text(p.short.take(3), color = C.ink, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            if (labels) Text(
                                p.name, color = if (here) C.accent else C.x(0xFFCFCAC0L), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 8.dp)
                            )
                        }
                    }
                }
            }
            pinned.forEachIndexed { pi, app ->
                FocusBox(
                    Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp), shape = RoundedCornerShape(10.dp),
                    onClick = { vm.openApp(app.pkg) },
                    onLongClick = { vm.dockMenu = app.pkg }, // long-press or Select
                    onFocusChange = { f -> if (f) notSettings() },
                    focusRequester = if (pi == pinned.lastIndex) lastPinnedFr else null,
                    focusKey = "dock:${app.pkg}"
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center) {
                        Box(Modifier.padding(start = if (labels) 7.dp else 0.dp).size(26.dp), contentAlignment = Alignment.Center) { AppIcon(app, Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))) }
                        if (labels) Text(
                            vm.nameOf(app), color = C.x(0xFFCFCAC0L), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 8.dp)
                        )
                    }
                }
            }
        }

        // ---- Settings, at the bottom (on Home, moving down onto it slides it up into view)
        DockButton(
            Icons.Filled.Settings, "Settings", labels, vm.screen == Screen.SETTINGS, if (landOn == "settings") focusRequester else null, dockArt("settings"),
            onFocus = { if (vm.screen == Screen.HOME && vm.dockNav) vm.settingsPeek = true },
            upTo = if (vm.dock.any { pkg -> vm.apps.any { it.pkg == pkg } } || vm.dockSystems.any { Platforms.byId(it) != null }) lastPinnedFr else null,
            stayDown = true
        ) { vm.go(Screen.SETTINGS) }
    }
}

/** Search, at the top of the dock: a glass bar when it's open, a round glass button on the rail. */
@Composable
private fun DockSearch(labels: Boolean, onFocus: () -> Unit, fr: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp), contentAlignment = if (labels) Alignment.CenterStart else Alignment.Center) {
        FocusBox(
            Modifier.height(38.dp).then(if (labels) Modifier.fillMaxWidth() else Modifier.width(38.dp)), shape = shape,
            onClick = onClick, onFocusChange = { f -> if (f) onFocus() }, focusKey = "dock:search", focusRequester = fr
        ) {
            Row(
                Modifier.fillMaxSize().background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x1FFFFFFFL), shape).padding(horizontal = if (labels) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (labels) Arrangement.Start else Arrangement.Center
            ) {
                Icon(Icons.Filled.Search, "Search", tint = C.sub, modifier = Modifier.size(17.dp))
                if (labels) Text(
                    "Search games & apps", color = C.sub, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** Menu for a dock app (long-press or Select). */
@Composable
fun DockMenu(vm: EclipseVM, pkg: String) {
    val name = vm.apps.firstOrNull { it.pkg == pkg }?.let { vm.nameOf(it) } ?: pkg
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    MenuSheet("Pinned app", name, onDismiss = { vm.dockMenu = null }) {
        MenuRow("Cancel", value = "B", fr = first) { vm.dockMenu = null }
        MenuRow("Unpin app", danger = true) { vm.toggleDock(pkg); vm.dockMenu = null }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DockButton(
    icon: ImageVector, label: String, showLabel: Boolean, active: Boolean, focusRequester: FocusRequester?,
    themed: String? = null, onFocus: (() -> Unit)? = null, badge: String? = null, upTo: FocusRequester? = null,
    stayDown: Boolean = false, plain: Boolean = false, onClick: () -> Unit
) {
    // line icons on the glass, with their names when it's open; the current place gets a soft glass pill and a glowing
    // gold bar at the edge
    Box(Modifier.fillMaxWidth().height(42.dp).focusProperties { if (upTo != null) up = upTo; if (stayDown) down = FocusRequester.Cancel }, contentAlignment = Alignment.CenterStart) {
        if (active) {
            Box(
                // (no shadow: a coloured shadow on a sliding, clipped dock flickered, worst on Settings at the bottom)
                Modifier.align(Alignment.CenterStart).size(3.dp, 18.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)).background(C.accent)
            )
        }
        FocusBox(
            Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(38.dp),
            shape = RoundedCornerShape(10.dp), onClick = onClick, focusRequester = focusRequester,
            onFocusChange = { f -> if (f) onFocus?.invoke() }
        ) {
            Row(
                Modifier.fillMaxSize().background(if (active && !plain) C.accent.copy(alpha = 0.16f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (showLabel) Arrangement.Start else Arrangement.Center
            ) {
              Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (themed != null) {
                    // the theme's own icon for this button
                    AsyncImage(
                        themed, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(9.dp), alpha = if (active) 1f else 0.7f
                    )
                } else {
                    Icon(icon, contentDescription = label, tint = if (active) C.accent else C.x(0xA6FFFFFFL), modifier = Modifier.size(20.dp))
                }
                // the count, in the corner of the icon on the rail
                if (badge != null && !showLabel) DockBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 2.dp))
              }
              if (showLabel) {
                  DockLabel(label, active, Modifier.weight(1f))
                  if (badge != null) DockBadge(badge, Modifier.padding(end = 10.dp))
              }
            }
        }
    }
}

@Composable
private fun DockBadge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.height(17.dp).widthIn(min = 17.dp).clip(RoundedCornerShape(9.dp)).background(C.accent).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        // the invisible text sets the width; the number is drawn by its ink, exactly centred
        Text(text, color = Color.Transparent, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        CenteredGlyph(text, C.onAccent, 10.sp, Modifier.matchParentSize(), fill = 0.56f)
    }
}

/** A dock button's name, next to its icon (only while the dock is open wide). */
@Composable
private fun DockLabel(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), color = if (active) C.accent else C.x(0xB3FFFFFFL), fontSize = 10.sp, letterSpacing = 1.6.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        // clipped, not "…": while the dock widens the name would otherwise flick between cut-off forms every frame
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = modifier.padding(start = 2.dp, end = 8.dp)
    )
}

@Composable
fun AppIcon(app: AppEntry, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(bitmap = app.icon, contentDescription = app.label, modifier = modifier)
}

@Composable
fun Clock(vm: EclipseVM, modifier: Modifier = Modifier, color: Color? = null) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val fmt = if (vm.flag("h24", false)) "HH:mm" else "h:mm a"
    // the right dock opening or closing: the clock fades away, then back in once the dock has slid into place
    val open = vm.drawerOpen()
    val alpha = remember { androidx.compose.animation.core.Animatable(if (open) 0f else 1f) }
    var seen by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open == seen && alpha.value == 1f) return@LaunchedEffect
        seen = open
        if (alpha.value > 0f) alpha.animateTo(0f, androidx.compose.animation.core.tween(140))
        delay(170)
        alpha.animateTo(1f, androidx.compose.animation.core.tween(260))
    }
    Text(
        SimpleDateFormat(fmt, Locale.getDefault()).format(Date(now)),
        modifier = modifier.graphicsLayer { this.alpha = alpha.value }, color = color ?: C.ink, fontWeight = FontWeight.SemiBold, fontSize = 22.sp
    )
}

/**
 * The clock in the top-right corner of a screen. With Settings → General → "Clock panel" on: the time and Wi-Fi on a
 * black panel tucked into the corner, very rounded on its inner corner; otherwise the plain clock, 28 dp in.
 */
@Composable
fun CornerClock(vm: EclipseVM, modifier: Modifier = Modifier) {
    // a right-hand drawer open: it shows the clock, battery and Wi-Fi itself (its top bar)
    if (!vm.flag("clock_panel", true)) { if (!vm.drawerOpen()) Clock(vm, modifier.padding(28.dp)); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var net by remember { mutableStateOf(QuickSettings.network(ctx)) }
    var level by remember { mutableIntStateOf(QuickSettings.wifiLevel(ctx)) }
    var battery by remember { mutableStateOf(QuickSettings.battery(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            net = QuickSettings.network(ctx); level = QuickSettings.wifiLevel(ctx); battery = QuickSettings.battery(ctx)
        }
    }
    // a right-hand drawer open: it shows Wi-Fi and the time itself (its header), so the panel fades out and back in
    // (the position goes on the AnimatedVisibility: on the Row inside it, align() did nothing and the panel sat top left)
    androidx.compose.animation.AnimatedVisibility(
        visible = !vm.drawerOpen(),
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 170)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140))
    ) {
    Row(
        Modifier.clip(RoundedCornerShape(bottomStart = 26.dp)).background(Color(0xDB000000))
            .padding(start = 18.dp, end = 20.dp, top = 9.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // like Android's own status bar: Wi-Fi with its strength, then the battery's percentage and an upright battery
        StatusIcons(net, level, battery.first, battery.second)
        Box(Modifier.padding(horizontal = 11.dp).size(1.dp, 18.dp).background(Color(0x2EFFFFFF)))
        Clock(vm, color = Color.White)
    }
    }
}

/**
 * Wi-Fi (filled, its bars showing the signal), the battery's percentage and an upright battery that fills from the
 * bottom (red when low and not charging), drawn the way Android's own status bar does them.
 */
@Composable
fun StatusIcons(net: String, wifiLevel: Int, pct: Int, charging: Boolean, color: Color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // the filled fan Android uses: the whole fan faint, lit from the point up to the signal's strength
        val level = if (net == "Wi-Fi") wifiLevel.coerceIn(0, 4) else 4
        val offline = net == "Offline"
        androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
            val r = size.width * 0.62f
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.84f)
            val tl = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r)
            val full = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)
            drawArc(color.copy(alpha = 0.32f), 225f, 90f, true, tl, full)
            if (!offline && level > 0) {
                val lr = r * (0.25f + 0.75f * level / 4f)
                drawArc(color, 225f, 90f, true, androidx.compose.ui.geometry.Offset(c.x - lr, c.y - lr), androidx.compose.ui.geometry.Size(lr * 2f, lr * 2f))
            }
            if (offline) drawLine(color, androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.2f), androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.9f), 1.6.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        }
        if (pct >= 0) {
            Text("$pct%", color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 10.dp))
            val low = pct <= 15 && !charging
            val fillCol = if (low) Color(0xFFFF5A4E) else color
            androidx.compose.foundation.Canvas(Modifier.padding(start = 3.dp).size(10.dp, 17.dp)) {
                val nubW = size.width * 0.42f; val nubH = 1.6.dp.toPx()
                val r = androidx.compose.ui.geometry.CornerRadius(1.6.dp.toPx())
                // the tip, then the body: faint all over, solid up to the charge
                drawRoundRect(color.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset((size.width - nubW) / 2f, 0f), androidx.compose.ui.geometry.Size(nubW, nubH + 1f), androidx.compose.ui.geometry.CornerRadius(0.8.dp.toPx()))
                val top = nubH
                val bodyH = size.height - top
                drawRoundRect(color.copy(alpha = 0.32f), androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bodyH), r)
                val fillH = bodyH * (pct.coerceIn(0, 100) / 100f)
                if (fillH > 0f) clipRect(top = size.height - fillH) {
                    drawRoundRect(fillCol, androidx.compose.ui.geometry.Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, bodyH), r)
                }
                if (charging) {
                    // a small bolt in the middle
                    val p = androidx.compose.ui.graphics.Path().apply {
                        val cx = size.width / 2f; val cy = top + bodyH / 2f; val u = size.width * 0.28f
                        moveTo(cx + u * 0.3f, cy - u * 1.6f); lineTo(cx - u, cy + u * 0.2f); lineTo(cx, cy + u * 0.2f)
                        lineTo(cx - u * 0.3f, cy + u * 1.6f); lineTo(cx + u, cy - u * 0.2f); lineTo(cx, cy - u * 0.2f); close()
                    }
                    drawPath(p, Color(0xFF141216))
                }
            }
        }
    }
}

/** The home screens' background: near-black, or warm off-white in light mode. */
val HomeDark: Color @Composable get() = ThemeColors.backdrop.value.takeIf { it != Color.Unspecified }
    ?: if (ThemeColors.light.value) Color(0xFFEEEAE1) else Color(0xFF0B0B0D)

/** Laid over every wallpaper / backdrop: a slight darkening, or in light mode a pale veil so dark writing reads on it. */
val WallpaperDim: Color @Composable get() = ThemeColors.dim.value.let { d ->
    val b = ThemeColors.backdrop.value.takeIf { it != Color.Unspecified }
    if (d >= 0f) (b ?: if (ThemeColors.light.value) Color(0xFFEEEAE1) else Color.Black).copy(alpha = d)
    else b?.copy(alpha = 0.25f) ?: if (ThemeColors.light.value) Color(0x99EEEAE1) else Color(0x40000000)
}

/** Wallpaper for the home screen: the highlighted game's backdrop fills only the top, fading into a dark bottom. */
@Composable
fun HomeBackdrop(vm: EclipseVM) {
    // Recently Played in cover flow / detail cover flow: the same backdrop as the games screen's (the systems row's
    // cover flow and slide view draw their own picture, for the highlighted system)
    val onRecent = vm.homeSection == HomeSection.RECENTS && vm.games.isNotEmpty()
    if (onRecent && vm.homeView == "coverflow" && vm.viewOn("coverflow")) StageBackdrop(vm, vm.homeFocus(), side = false)
    else if (onRecent && vm.homeView == "detailflow" && vm.viewOn("detailflow")) StageBackdrop(vm, vm.homeFocus(), side = true)
    // on Recently Played: the hero above the gold line, glass below it (behind the covers)
    else if (vm.homeSection == HomeSection.RECENTS && vm.games.isNotEmpty()) SlideCornerBackdrop(vm, vm.homeFocus())
    else SlideBackdrop(vm, vm.homeFocus(), null)
}


/**
 * Home: two rows stacked vertically.
 *  - On Deck (the carousel). Press Down: it slides up out of view and the platform grid slides up into its place.
 *  - Platforms grid. Press Up on the top row to slide back.
 */
@Composable
fun HomeScreen(vm: EclipseVM) {
    val tiles = vm.homeGames()
    // the view each row shows: the one picked, unless it's off in Settings → Views; your view types made for the row too
    val customViews = rememberCustomViews()
    val deckViews = vm.viewsFor("recent", customViews)
    val deckMode = vm.shownView(vm.homeView, deckViews.map { it.first })
    val deckSpec = customViews.firstOrNull { "view:" + it.id == deckMode }
    val platMode = vm.shownView(vm.platView, vm.viewsFor("systems", customViews).map { it.first })
    val focus = vm.homeFocus()
    SideEffect { if (vm.screen == Screen.HOME) vm.browseList = tiles }
    val hi = remember { FocusRequester() }
    val platFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val counts = vm.platCounts
    val plats = remember(counts, vm.platOrder, vm.platSort, vm.sysNames) { vm.orderPlats(Platforms.all.filter { vm.showsPlat(it.id, counts) }) }
    val appsFocus = remember { FocusRequester() }
    // nothing played yet: no Recently Played row at all; Home starts on the systems (it appears after the first game)
    val noRecent = tiles.isEmpty() && plats.isNotEmpty()
    LaunchedEffect(noRecent, vm.homeSection) { if (noRecent && vm.homeSection == HomeSection.RECENTS) vm.homeSection = HomeSection.PLATFORMS }
    val onApps = vm.homeSection == HomeSection.APPS
    val onPlatforms = (vm.homeSection == HomeSection.PLATFORMS || (noRecent && vm.homeSection == HomeSection.RECENTS)) && plats.isNotEmpty()
    val appsRow = if (plats.isNotEmpty()) 2 else 1
    // which row is on screen: 0 On Deck, 1 platforms, then the app drawer
    val row = when {
        onApps -> appsRow
        onPlatforms -> 1
        else -> 0
    }
    // going down from the platforms (or On Deck without platforms): the app drawer
    val toBelowPlatforms: () -> Unit = { vm.homeSection = HomeSection.APPS; runCatching { appsFocus.requestFocus() } }
    val target = when {
        onApps -> appsFocus
        onPlatforms -> platFocus
        else -> hi
    }

    PrimaryFocus(target)

    // Recently Played: whenever it comes into view the first (most recent) game is the highlighted one, and B goes
    // back to it from further along
    val recentsShown = row == 0 && vm.screen == Screen.HOME && vm.games.isNotEmpty()
    val homeScope = rememberCoroutineScope()
    fun toFirstRecent() {
        val firstGame = vm.homeGames().firstOrNull() ?: return
        vm.focused = firstGame
        homeScope.launch {
            withFrameNanos { }; withFrameNanos { }
            if (!vm.dockFocused && !vm.drawerOpen() && !vm.setupOpen) runCatching { hi.requestFocus() }
        }
    }
    LaunchedEffect(recentsShown) { if (recentsShown) toFirstRecent() }
    BackHandler(enabled = recentsShown && !vm.dockFocused && !vm.drawerOpen() && tiles.isNotEmpty() && focus?.id != tiles.first().id) { toFirstRecent() }

    // R bumper opens (or closes) the options drawer of the row on screen: On Deck or the platforms
    val onDeckRow = !onApps && !onPlatforms && vm.games.isNotEmpty()
    DisposableEffect(onPlatforms, onDeckRow, onApps) {
        val hook: (() -> Unit)? = when {
            onApps -> ({
                vm.appsDrawer = !vm.appsDrawer
                if (!vm.appsDrawer) runCatching { appsFocus.requestFocus() }
            })
            onPlatforms -> ({
                vm.platDrawer = !vm.platDrawer
                if (!vm.platDrawer) runCatching { platFocus.requestFocus() }
            })
            onDeckRow -> ({
                vm.deckDrawer = !vm.deckDrawer
                if (!vm.deckDrawer) runCatching { hi.requestFocus() }
            })
            else -> null
        }
        GamepadFocus.rightDock = hook
        onDispose { if (GamepadFocus.rightDock === hook) GamepadFocus.rightDock = null }
    }

    // Initial focus. Re-run once the library has loaded (otherwise nothing gets focus and the D-pad seems dead).
    // Coming back to Home (B from a system, say) the old screen's focus vanishes and Android hands it to the dock,
    // the first thing it finds. That isn't the player asking for the dock, so the first time round Home takes focus
    // anyway (unless LB actually opened the dock).
    var firstFocus by remember { mutableStateOf(true) }
    LaunchedEffect(vm.screen, tiles.size, vm.loaded, plats.size, vm.homeSection) {
        withFrameNanos { }
        // moving through the left dock slides Home to that row as a preview; focus stays in the dock
        // (nor while a right-hand drawer is open: changing its options rebuilds the row behind it)
        val inDock = vm.dockFocused && !(firstFocus && !vm.dockPeek)
        // (and never while the setup wizard covers Home: that would pull the controller out of it)
        if (!inDock && !vm.drawerOpen() && !vm.setupOpen && vm.screen == Screen.HOME) runCatching { target.requestFocus() }
        firstFocus = false
    }

    // a theme can give each system its own background, shown while that system is highlighted
    val sysBg = if (onPlatforms) vm.lastPlatform?.let { vm.platformBackdrop(it) } else null
    val dim by animateFloatAsState(if (row > 0) (if (sysBg != null) 0.45f else 0.94f) else 0f, label = "homeDim")
    Box(Modifier.fillMaxSize()) {
        // (no blank frame between two systems' backgrounds)
        SteadyImage(sysBg, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(HomeDark.copy(alpha = dim)))

        if (!vm.loaded) {
            // library still loading from storage
        } else if (vm.games.isEmpty() && onApps) {
            AppDrawer(vm, appsFocus, Modifier.padding(top = 52.dp), active = vm.homeSection == HomeSection.APPS && vm.screen == Screen.HOME)
        } else if (vm.games.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = C.x(0xCCFFFFFFL), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No games yet", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.ink)
                Text("Pick your ROM folder in Settings \u2192 Library.", color = C.x(0xCCFFFFFFL))
                Spacer(Modifier.height(20.dp))
                Pill("Open Library settings", primary = true, focusRequester = hi) {
                    vm.settingsTab = SettingsTabs.LIBRARY
                    vm.go(Screen.SETTINGS)
                }
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = maxHeight
                val slide by animateDpAsState(-h * row, label = "homeSlide")

                // ---- row 1: On Deck (left out until something has been played) ----
                if (!noRecent) Box(
                    Modifier
                        .fillMaxSize()
                        .offset(y = slide)
                        .onFocusChanged { if (it.hasFocus) vm.homeSection = HomeSection.RECENTS }
                        // touch: pull down on On Deck to open search
                        .pointerInput(Unit) {
                            var pulled = 0f
                            detectVerticalDragGestures(onDragEnd = { pulled = 0f }) { _, dy ->
                                pulled += dy
                                if (pulled > 120f) { pulled = 0f; vm.searchOpen = true }
                            }
                        }
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                // up: pull open search (a fresh press only, not a held D-pad)
                                if (!GamepadFocus.held) vm.searchOpen = true
                                true
                            } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                                // down: the platforms (or straight to the apps when there are no systems yet)
                                if (GamepadFocus.held) {
                                } else if (plats.isNotEmpty()) {
                                    vm.homeSection = HomeSection.PLATFORMS
                                    runCatching { platFocus.requestFocus() }
                                } else toBelowPlatforms()
                                true
                            } else false
                        }
                        .focusGroup()
                ) {
                    // the row's own art style (its options drawer → Style), else each game's system's
                    CompositionLocalProvider(LocalArtStyle provides vm.deckStyle.ifEmpty { null }) {
                    if (deckSpec != null && tiles.isNotEmpty()) {
                        // one of your view types, made for Recently Played
                        SlideTopBar(vm, "Recently Played", clock = false, modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 14.dp, end = 150.dp))
                        CustomGamesView(vm, deckSpec, tiles, focus, hi, Modifier.fillMaxSize().padding(top = 52.dp))
                    } else if ((deckMode == "coverflow" || deckMode == "detailflow") && tiles.isNotEmpty()) {
                        SlideTopBar(vm, "Recently Played", clock = false, modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 14.dp, end = 150.dp))
                        // cover flow over the wallpaper (Settings → Appearance)
                        CoverFlow(
                            vm, tiles, hi,
                            // same size and place as the games screen's cover flow (which sits under its header)
                            Modifier.fillMaxSize().padding(top = 52.dp),
                            background = false, floor = HomeDark,
                            onSelect = { vm.onDeckRemove = it }, scale = vm.iconSize("deck", deckMode),
                            look = if (deckMode == "detailflow") "stage" else "detail"
                        )
                    } else {
                      // slide view: the highlighted game's logo, details and buttons up top (see SlideView.kt)
                      SlideHero(
                          vm, focus, "Recently Played", Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 20.dp), clock = false,
                          emptyTitle = "Nothing played yet",
                          emptyText = "Games you launch will show up here. Press down to pick a system."
                      )
                      if (!vm.deckDrawer) SlideHintBar(Modifier.align(Alignment.BottomStart))
                      GameCarousel(
                        vm, tiles, focus, hi,
                        emptyText = "Games you launch will show up here. Press down to pick a system.",
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 34.dp),
                        onDeck = true,
                        scale = vm.iconSize("deck", "carousel") * SLIDE_POSTER * SLIDE_GAME,
                        showTitle = false,
                        slide = true, wrapAround = false
                    ) {
                        // (only when nothing has been played yet, so there is something to hold the controller)
                        if (tiles.isEmpty()) item(key = "library") {
                            LibraryTile(if (tiles.isEmpty()) hi else null, vm.iconSize("deck", "carousel") * SLIDE_POSTER * SLIDE_GAME) {
                                if (plats.isEmpty()) vm.openPlatform(null) else {
                                    vm.homeSection = HomeSection.PLATFORMS
                                    runCatching { platFocus.requestFocus() }
                                }
                            }
                        }
                    }
                    }
                    }
                    // R bumper / the edge tab: On Deck's options drawer
                    if (!vm.deckDrawer) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).size(18.dp, 72.dp)
                                .pointerInput(Unit) { detectTapGestures { vm.deckDrawer = true } },
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Box(Modifier.padding(end = 5.dp).size(5.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L)))
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = vm.deckDrawer,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
                    ) {
                        OptionsDrawer(
                            vm, "Recently Played",
                            listOf(
                                // what the row shows: recently played, recently added, or both on one timeline
                                DrawerList("Show", listOf("both" to "Played and added", "played" to "Recently played", "added" to "Recently added"), vm.deckShow) { vm.chooseDeckShow(it) },
                                DrawerList("View", deckViews.filter { vm.viewOn(it.first) }, deckMode) { vm.chooseHomeView(it) },
                                // how many recently played games On Deck keeps
                                DrawerList("Games", listOf(5, 10, 15, 20, 30).map { "$it" to "$it games" }, vm.deckCount.toString()) { vm.chooseDeckCount(it.toInt()) },
                                // how the row's games are drawn: one style for all, or each system's own
                                DrawerList("Style", listOf("" to "Each system's") + ART_STYLES + ("mixed" to "Mixed"), vm.deckStyle) { vm.chooseDeckStyle(it) }
                            ),
                            size = { IconSizeSection(vm, "deck", deckMode) }
                        ) {
                            vm.deckDrawer = false
                            runCatching { hi.requestFocus() }
                        }
                    }
                }

                // ---- row 2: platforms ----
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset(y = slide + h)
                        .onFocusChanged { if (it.hasFocus) vm.homeSection = HomeSection.PLATFORMS }
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (e.key) {
                                // top row: slide back to On Deck
                                Key.DirectionUp -> {
                                    if (!focusManager.moveFocus(FocusDirection.Up) && !GamepadFocus.held && !noRecent) {
                                        vm.homeSection = HomeSection.RECENTS
                                        runCatching { hi.requestFocus() }
                                    }
                                    true
                                }
                                // all the way right: the options drawer slides out
                                Key.DirectionRight -> {
                                    if (!focusManager.moveFocus(FocusDirection.Right)) vm.platDrawer = true
                                    true
                                }
                                // bottom row: slide up the media row (or the app drawer)
                                Key.DirectionDown -> {
                                    // (slide view: the neighbouring smaller card counts as "below" for the focus search, so go straight down)
                                    if ((platMode == "carousel" || !focusManager.moveFocus(FocusDirection.Down)) && !GamepadFocus.held) toBelowPlatforms()
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusGroup()
                ) {
                    if (plats.isNotEmpty()) {
                        when (platMode) {
                            "carousel" -> PlatformCarousel(vm, plats, counts, platFocus, active = onPlatforms)
                            "coverflow" -> PlatformCoverFlow(vm, plats, counts, platFocus)
                            else -> customViews.firstOrNull { "view:" + it.id == platMode }
                                ?.let { CustomPlatformsView(vm, it, plats, counts, platFocus) }
                                ?: PlatformGrid(vm, plats, counts, platFocus)
                        }
                    }
                    // touch: a small tab on the right edge opens the options drawer
                    if (!vm.platDrawer) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).size(18.dp, 72.dp)
                                .pointerInput(Unit) { detectTapGestures { vm.platDrawer = true } },
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Box(Modifier.padding(end = 5.dp).size(5.dp, 48.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L)))
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = vm.platDrawer,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }) + androidx.compose.animation.fadeOut()
                    ) {
                        PlatformDrawer(vm) {
                            vm.platDrawer = false
                            runCatching { platFocus.requestFocus() }
                        }
                    }
                }

                // ---- row 3: the app drawer (Down from the platforms, or the Apps button) ----
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset(y = slide + h * appsRow)
                        .onFocusChanged { if (it.hasFocus) vm.homeSection = HomeSection.APPS }
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) {
                                // top of the drawer: slide back to the platforms (not while held)
                                if (!focusManager.moveFocus(FocusDirection.Up) && !GamepadFocus.held) {
                                    if (plats.isNotEmpty()) {
                                        vm.homeSection = HomeSection.PLATFORMS
                                        runCatching { platFocus.requestFocus() }
                                    } else {
                                        vm.homeSection = HomeSection.RECENTS
                                        runCatching { hi.requestFocus() }
                                    }
                                }
                                true
                            } else false
                        }
                        .focusGroup()
                ) {
                    AppDrawer(vm, appsFocus, Modifier.padding(top = 52.dp), active = vm.homeSection == HomeSection.APPS && vm.screen == Screen.HOME)
                }

            }
        }

        CornerClock(vm, Modifier.align(Alignment.TopEnd))

    }
}

/**
 * The game carousel used on Home and on the Platforms screen: title and details above a row of box art.
 * The highlighted game swells up and always sits in the first slot; games before it slide off to the left.
 * [trailing] adds extra items after the games (Home uses it for the Library tile).
 */
@Composable
fun GameCarousel(
    vm: EclipseVM,
    tiles: List<Game>,
    focus: Game?,
    hi: FocusRequester,
    emptyText: String,
    modifier: Modifier = Modifier,
    onDeck: Boolean = false,
    /** poster size (right-hand drawer → Icon size) */
    scale: Float = vm.posterScale,
    /** false when the screen shows the game's name (or logo) itself, elsewhere */
    showTitle: Boolean = true,
    /** slide view: the highlighted cover sits a little higher than the rest, which come down to the button hints */
    slide: Boolean = false,
    /** slide view: Right on the last game goes back to the first (not on Recently Played) */
    wrapAround: Boolean = true,
    /** slide view: Down from the covers (the letter strip under them) */
    onDown: (() -> Unit)? = null,
    trailing: (LazyListScope.() -> Unit)? = null
) {
    val idx = tiles.indexOfFirst { it.id == focus?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = idx)
    // Keep the highlighted game in the first slot. Rather than one fixed scroll animation (which overshoots when going
    // right, because the tile we just left shrinks while we're scrolling past it), we follow the tile every frame and
    // close a share of the remaining gap, so we land exactly on it even while the tiles are still resizing.
    LaunchedEffect(focus?.id) {
        if (tiles.isNotEmpty()) state.pinToFirst(idx)
    }
    // slide view: the list wraps round (Left on the first game goes to the last, Right on the last to the first).
    // The highlighted game always stays in the first slot on the left.
    val wrapScope = rememberCoroutineScope()
    fun wrapTo(t: Game) {
        wrapScope.launch {
            state.scrollToItem(tiles.indexOf(t).coerceAtLeast(0))
            vm.focused = t
            withFrameNanos { }; withFrameNanos { }
            runCatching { hi.requestFocus() }
        }
    }
    val wrap = Modifier.onPreviewKeyEvent { e ->
        if (!slide || tiles.isEmpty()) return@onPreviewKeyEvent false
        if (e.key == Key.DirectionDown && onDown != null) { if (e.type == KeyEventType.KeyDown && !GamepadFocus.held) onDown(); return@onPreviewKeyEvent true }
        if (!wrapAround || tiles.size < 2) return@onPreviewKeyEvent false
        if (e.key != Key.DirectionLeft && e.key != Key.DirectionRight) return@onPreviewKeyEvent false
        val first = focus?.id == tiles.first().id
        val last = focus?.id == tiles.last().id
        when {
            // (only to the right: Left on the first game still reaches the left dock)
            e.key == Key.DirectionRight && last -> { if (e.type == KeyEventType.KeyDown) wrapTo(tiles.first()); true }
            else -> false
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // trailing space so even the last game can scroll all the way to the first slot
        val endPad = (maxWidth - 190.dp * scale - 40.dp).coerceAtLeast(30.dp)
        Column {
          if (showTitle) {
            Text(
                focus?.name ?: "Nothing played yet", color = C.ink, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 36.dp, end = 36.dp)
            )
            if (focus != null) {
                val plat = Platforms.byId(focus.platform)?.name ?: focus.platform
                val t = vm.playedSeconds(focus)
                Text(
                    plat + if (t > 0) "  \u00b7  Played ${fmtDuration(t)}" else "",
                    color = Color(0xCCFFFFFF), modifier = Modifier.padding(start = 36.dp, top = 2.dp)
                )
            } else {
                Text(emptyText, color = Color(0xCCFFFFFF), modifier = Modifier.padding(start = 36.dp, top = 2.dp))
            }
          }
            LazyRow(
                modifier = wrap,
                state = state,
                contentPadding = PaddingValues(start = 30.dp, end = endPad, top = if (slide) 0.dp else 16.dp, bottom = if (slide) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                itemsIndexed(tiles, key = { _, g -> g.id }) { _, g ->
                    val big = g.id == focus?.id
                    HomeTile(vm, g, big, if (big) hi else null, onDeck, scale, lift = if (slide) 8.dp else 0.dp, even = slide)
                }
                trailing?.invoke(this)
            }
        }
    }
}

/**
 * Keeps item [idx] in the first slot. Rather than one fixed scroll animation (which overshoots when going right,
 * because the tile we just left shrinks while we're scrolling past it), it follows the tile every frame and closes a
 * share of the remaining gap, so it lands exactly on it even while the tiles are still resizing.
 */
suspend fun LazyListState.pinToFirst(idx: Int) {
    if (layoutInfo.visibleItemsInfo.none { it.index == idx }) scrollToItem(idx)
    val st = this
    scroll {
        var prev = withFrameNanos { it }
        val giveUp = prev + 1_500_000_000L
        var still = 0
        while (still < 4) {
            val now = withFrameNanos { it }
            val dt = (now - prev) / 1_000_000_000f
            prev = now
            val gap = st.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }?.offset?.toFloat() ?: break
            if (abs(gap) < 0.5f || now > giveUp) {
                if (gap != 0f) scrollBy(gap)
                still++
            } else {
                still = 0
                scrollBy(gap * (1f - exp(-dt / 0.075f)))
            }
        }
    }
}

/** Box-art-sized button at the end of the carousel that jumps to the platform grid. */
@Composable
private fun LibraryTile(focusRequester: FocusRequester?, scale: Float = 1f, onClick: () -> Unit) {
    FocusBox(
        Modifier.size(120.dp * scale, 160.dp * scale),
        onClick = onClick,
        focusRequester = focusRequester
    ) {
        Column(
            Modifier.fillMaxSize().background(C.x(0x33FFFFFFL)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(8.dp))
            Text("Library", color = C.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

/**
 * Platform cards, six per row (Icon size in the right-hand drawer changes that): the header shows the highlighted
 * system's logo with its game count and year; each card has its game count in a chip; the others sit a little dimmer
 * than the highlighted one; the button hints are along the bottom.
 */
@Composable
private fun PlatformGrid(vm: EclipseVM, plats: List<PlatformDef>, counts: Map<String, Int>, fr: FocusRequester) {
    val target = plats.firstOrNull { it.id == vm.lastPlatform }?.id ?: plats.first().id
    Box(Modifier.fillMaxSize()) {
    // the header stays put and the cards scroll underneath it (like the games grid), so they never reach the top edge
    Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
        // the highlighted system's name and game count (the cards themselves have no label)
        val cur = plats.firstOrNull { it.id == vm.lastPlatform } ?: plats.first()
        Column(Modifier.padding(start = 36.dp, end = 28.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                PlatformTitle(vm, cur, 24.sp, 28.dp)
                val n = counts[cur.id] ?: 0
                Text(
                    "$n ${if (n == 1) "GAME" else "GAMES"}" + (Platforms.year(cur.id)?.let { "  \u00b7  $it" } ?: ""),
                    color = C.x(0xFFB9B4AAL), fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 16.dp, bottom = 3.dp)
                )
            }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .clipToBounds()
            .menuScroll()
            // the padding sits inside the clip, so a highlighted (enlarged) card isn't cut off at the sides
            .padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Icon size (right-hand drawer): bigger cards = fewer per row
        val cols = vm.gridCols("platforms", 6)
        plats.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f)) {
                        PlatformCard(vm, p, counts[p.id] ?: 0, if (p.id == target) fr else null, showCount = true)
                    }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    }
    // the button hints on the same dark bar as the games' grid and slide view
    if (!vm.platDrawer) SlideHintBar(Modifier.align(Alignment.BottomStart), listOf("R" to "Drawer", "Select" to "Options", "A" to "Open"))
    }
}

/** A muted colour that is always the same for a given system. */
fun platformTint(id: String): Color =
    if (ThemeColors.light.value) Color.hsv(((id.hashCode() and 0x7fffffff) % 360).toFloat(), 0.22f, 0.86f)
    else Color.hsv(((id.hashCode() and 0x7fffffff) % 360).toFloat(), 0.38f, 0.42f)

/** System cards are portrait, like game covers. */
private const val CARD_ASPECT = 0.75f

/** Each system icon's width / height once it has loaded (Style → Icon draws the icon at its own shape). */
private object IconRatios { val map = androidx.compose.runtime.mutableStateMapOf<String, Float>() }

/** An icon's shape (width / height), or the card's until it has loaded. */
private fun iconRatio(icon: String?): Float = icon?.let { IconRatios.map[it] }?.takeIf { it > 0.05f } ?: CARD_ASPECT

/**
 * Home's systems row drawn by one of your view types (made for "systems"): each card is a normal system card. The
 * slots show the highlighted system: title = its name, meta = how many games, logo = its logo, backdrop = its background.
 */
@Composable
private fun CustomPlatformsView(vm: EclipseVM, spec: com.eclipse.launcher.data.Views.Spec, plats: List<PlatformDef>, counts: Map<String, Int>, fr: FocusRequester) {
    CustomItemsView(
        spec, plats, { it.id }, plats.indexOfFirst { it.id == vm.lastPlatform }.coerceAtLeast(0), fr, { vm.lastPlatform = it.id },
        { p ->
            val n = counts[p.id] ?: 0
            SlotInfo(
                p.name, "$n ${if (n == 1) "game" else "games"}",
                com.eclipse.launcher.data.SystemArt.logo(p.id, "dark") ?: vm.platLogo[p.id],
                vm.platformBackdrop(p.id) ?: vm.wallpaperTop()
            )
        },
        Modifier.fillMaxSize()
    ) { p, m, focus -> PlatformCard(vm, p, counts[p.id] ?: 0, focus, m) }
}

@Composable
private fun PlatformCard(
    vm: EclipseVM, p: PlatformDef, count: Int, fr: FocusRequester?, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(CARD_ASPECT),
    /** the game count in a chip over the icon's corner (the grid) */
    showCount: Boolean = false
) {
    // Style → Icon: the highlight follows the icon's own shape and corners, centred where the card would be
    val iconMode = vm.platStyle == "icon"
    Box(modifier, contentAlignment = Alignment.Center) {
    FocusBox(
        if (iconMode) Modifier.aspectRatio(iconRatio(vm.platformCardIcon(p.id))) else Modifier.fillMaxSize(),
        shape = RoundedCornerShape(if (iconMode) vm.iconCorner else vm.cardCorner),
        onClick = { vm.openPlatform(p.id) },
        onLongClick = { vm.platformMenu = p.id }, // long-press or Select
        onFocusChange = { f -> if (f) vm.lastPlatform = p.id },
        focusRequester = fr,
        focusKey = "plat:${p.id}"
    ) { focused ->
        // the ones you're not on sit a little dimmer
        Box(Modifier.fillMaxSize().alpha(if (focused || !showCount) 1f else 0.84f)) {
            SystemCardFace(vm, p, Modifier.fillMaxSize())
            if (showCount) Text(
                "$count", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).clip(RoundedCornerShape(50))
                    .background(Color(0x9E000000)).border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
    }
}

/** The glass colour the cards are made of: yours or the theme's (Appearance → Colours), else Eclipse's warm charcoal. */
@Composable
fun cardGlass(): Color = ThemeColors.glass.value.takeIf { it.isSpecified }?.copy(alpha = 1f) ?: Color(0xFF221F1B)

/**
 * The card's look: "light" (a pale glass colour: black logo), "dark" (white logo), or "color" (colour logo). It
 * follows Eclipse's glass colour, never Android's light / dark setting.
 */
@Composable
fun cardLook(vm: EclipseVM): String =
    if (vm.cardMode == "color") "color" else if (cardGlass().luminance() > 0.5f) "light" else "dark"

/**
 * A system's card: the icon on top, 10% of the card's width in from the top and sides, and the logo under it at the
 * same width. The card's colour and the logo (black / white / colour) follow the card look. The icon changes with
 * the icon you pick; the logo never does. Too short for a logo (the reorder grid): the icon alone.
 */
@Composable
private fun SystemCardFace(vm: EclipseVM, p: PlatformDef, modifier: Modifier) {
    val look = cardLook(vm)
    val glass = cardGlass()
    val light = glass.luminance() > 0.5f
    // glass, like the docks: the glass colour see-through over what's behind, a sheen toward the top and a light edge
    val a = if (ThemeColors.lowPower.value) 0.92f else ThemeColors.glassAlpha.value.coerceIn(0.35f, 0.85f)
    val bg = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(
        androidx.compose.ui.graphics.lerp(glass, Color.White, 0.10f).copy(alpha = a * 0.85f), glass.copy(alpha = a)
    ))
    val ink = if (light) Color(0xFF111111) else Color(0xFFF2EFE8)
    val icon = vm.platformCardIcon(p.id)
    val logo = com.eclipse.launcher.data.SystemArt.logo(p.id, look)
    // Style → Icon: the picture alone, at its own shape (not stretched to the card's), with Corners → Icons rounding
    if (vm.platStyle == "icon") {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(iconRatio(icon)).clip(RoundedCornerShape(vm.iconCorner))) {
                if (icon != null) AsyncImage(
                    model = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onSuccess = { s ->
                        val d = s.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) IconRatios.map[icon] = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                    }
                ) else CardIcon(p, null, Modifier.fillMaxSize())
            }
        }
        return
    }
    val edge = if (light) Color(0x1F000000) else Color(0x29FFFFFF)
    // (clipped to the card's corners: the icon now reaches the card's edges without fading, so it mustn't poke past them)
    BoxWithConstraints(modifier.clip(RoundedCornerShape(vm.cardCorner)).background(bg).border(1.dp, edge, RoundedCornerShape(vm.cardCorner))) {
        val w = maxWidth
        val pad = w * 0.1f
        val portrait = maxHeight >= w * 1.15f
        // the icon sharp, no fade into the glass (Corners → Icons rounds it, for icons that are pictures with a frame)
        val iconShape = RoundedCornerShape(w * 0.30f * (vm.cornerIcon / 100f))
        if (!portrait) {
            CardIcon(p, icon, Modifier.fillMaxSize().clip(iconShape))
        } else Column(Modifier.fillMaxSize().padding(bottom = pad), horizontalAlignment = Alignment.CenterHorizontally) {
            CardIcon(p, icon, Modifier.fillMaxWidth().aspectRatio(1f).clip(iconShape))
            // the logo (or the name) centred in what is left
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = pad, vertical = pad * 0.3f), contentAlignment = Alignment.Center) {
                var failed by remember(logo) { mutableStateOf(false) }
                if (logo != null && !failed) AsyncImage(
                    model = logo, contentDescription = p.name, contentScale = ContentScale.Fit, onError = { failed = true },
                    modifier = Modifier.fillMaxSize()
                ) else Text(
                    p.name, color = ink, fontSize = (w.value * 0.085f).sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CardIcon(p: PlatformDef, icon: String?, modifier: Modifier) {
    if (icon != null) AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    else Box(modifier.background(platformTint(p.id)), contentAlignment = Alignment.Center) {
        Text(p.short, color = C.x(0x99FFFFFFL), fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

/** A system's name: its logo when it has one (picked or fetched), else the name in text. */
@Composable
fun PlatformTitle(vm: EclipseVM, p: PlatformDef, size: androidx.compose.ui.unit.TextUnit, logoHeight: Dp, center: Boolean = false) {
    // Eclipse's own logo for the system (white, these sit on dark pictures), else one fetched or picked for it
    val logo = com.eclipse.launcher.data.SystemArt.logo(p.id, "dark") ?: vm.platLogo[p.id]
    var failed by remember(logo) { mutableStateOf(false) }
    if (logo != null && !failed) AsyncImage(
        model = logo, contentDescription = p.name, contentScale = ContentScale.Fit,
        alignment = if (center) Alignment.Center else Alignment.CenterStart, onError = { failed = true },
        modifier = Modifier.height(logoHeight).widthIn(max = 360.dp)
    ) else Text(p.name, color = C.ink, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Menu for a platform card (long-press or Select). */
@Composable
fun PlatformMenu(vm: EclipseVM, id: String, pickImage: () -> Unit) {
    val name = Platforms.byId(id)?.name ?: id
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    val n = vm.games.count { it.platform == id }
    MenuSheet(
        "System", name, "$n ${if (n == 1) "game" else "games"}", onDismiss = { vm.platformMenu = null },
        image = {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(C.x(0x14FFFFFFL))) {
                vm.platformCardIcon(id)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
    ) {
        MenuRow("Open", gold = true, fr = first) { vm.platformMenu = null; vm.openPlatform(id) }
        // card image, icon, logo and backdrop (Favourites / All games: card image only, the picker limits them)
        val special = id == Platforms.FAVORITES || id == Platforms.ALL
        MenuRow("Platform artwork") { vm.platformMenu = null; vm.platKindStart = if (special) PlatKind.CARD else PlatKind.ICON; vm.platArtScreen = id }
        // Scan: look for this system's games again (All games: every system). Scrape: artwork for the ones missing it.
        if (id != Platforms.FAVORITES) MenuRow("Scan") {
            vm.platformMenu = null
            if (id == Platforms.ALL) vm.rescan() else vm.rescanSystem(id, scrapeAfter = false)
        }
        MenuRow("Scrape") {
            vm.platformMenu = null
            vm.scrapeGames(when (id) {
                Platforms.ALL -> vm.games
                Platforms.FAVORITES -> vm.games.filter { vm.isFavGame(it) }
                else -> vm.games.filter { it.platform == id }
            }, false)
        }
        val inDock = id in vm.dockSystems
        MenuRow(if (inDock) "Unpin from the left dock" else "Pin to the left dock") { vm.toggleDockSystem(id); vm.platformMenu = null }
        if (!special) MenuRow("Edit system") { vm.platformMenu = null; vm.sysEdit = id }
        MenuRow("Cancel", value = "B") { vm.platformMenu = null }
    }
}

/** The highlighted game swells up larger than the others (animated). */
@Composable
private fun HomeTile(vm: EclipseVM, g: Game, big: Boolean, focusRequester: FocusRequester?, onDeck: Boolean = false, s: Float = vm.posterScale, lift: Dp = 0.dp, even: Boolean = false) {
    // (slide view: the others are nearly as big as the highlighted one)
    val small = if (even) 0.86f else 0.632f
    val w by animateDpAsState(if (big) 190.dp * s else 190.dp * small * s, label = "tileW")
    val h by animateDpAsState(if (big) 253.dp * s else 253.dp * small * s, label = "tileH")
    val up by animateDpAsState(if (big) lift else 0.dp, label = "tileLift")
    // On Deck: Select asks whether to take the game off On Deck (Start still opens its page)
    GameTile(vm, g, Modifier.padding(bottom = up).size(w, h), focusRequester, onSelect = if (onDeck) ({ vm.onDeckRemove = g }) else null)
}

@Composable
fun GameTile(vm: EclipseVM, g: Game, modifier: Modifier, focusRequester: FocusRequester? = null, onSelect: (() -> Unit)? = null) {
    // a disc / cartridge picture gets its highlight around its own outline, not around the tile
    val media = ownOutline(vm, g, styleOf(vm, g, LocalArtStyle.current))
    FocusBox(
        modifier,
        shape = RoundedCornerShape(vm.artCorner),
        ring = !media,
        onClick = { vm.play(g) },
        onLongClick = { vm.menuGame = g }, // long-press, Start or Select
        onFocusChange = { f -> if (f) vm.focused = g },
        focusRequester = focusRequester,
        focusKey = "g:${g.id}",
        onSelect = onSelect
    ) { focused ->
        GameArt(vm, g, Modifier.fillMaxSize(), focused)
        GameBadge(vm, g, Modifier.align(Alignment.TopEnd))
    }
}

/**
 * A game's tile picture in its system's art style (right-hand drawer → Art style): box art, its disc / cartridge,
 * its logo over a darkened backdrop, or a screenshot. Falls back to box art when the game has no picture of that kind.
 */
@Composable
fun GameArt(vm: EclipseVM, g: Game, modifier0: Modifier = Modifier, focused: Boolean = false) {
    // remember where this cover sits, so the game menu can flip open out of it
    val modifier = modifier0.onGloballyPositioned { vm.artBounds[g.id] = it.boundsInRoot() }
    DisposableEffect(g.id) { onDispose { vm.artBounds.remove(g.id) } }
    val style = styleOf(vm, g, LocalArtStyle.current)
    if (style == "logo") {
        val logo = vm.artOf(g, ArtKind.LOGO)
        if (logo != null) {
            Box(modifier.background(C.x(0xFF111114L)), contentAlignment = Alignment.Center) {
                vm.artOf(g, ArtKind.BACKDROP)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                AsyncImage(logo, g.name, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            }
            return
        }
    }
    val pick = tilePicture(vm, g, style)
    val m = pick.model
    if (pick.standsAlone && m != null) {
        // box art keeps its real shape (a wide SNES box, a tall DVD case), discs and 3D boxes their own outline
        Box(modifier, contentAlignment = Alignment.Center) {
            MediaPicture(m, g.name, focused, Modifier.fillMaxSize().padding(pick.pad))
        }
    } else Cover(g.name, pick.model, modifier)
}

/** What a tile shows: the picture, whether it stands on its own (fitted, highlight around its outline) and its inset. */
class TilePick(val model: String?, val standsAlone: Boolean, val pad: androidx.compose.ui.unit.Dp)

fun tilePicture(vm: EclipseVM, g: Game, style: String): TilePick {
    val box = vm.artOf(g, ArtKind.BOX)
    val boxPick = TilePick(box, box != null, 3.dp) // 3 dp: room for the outline highlight
    return when (style) {
        "media" -> vm.artOf(g, ArtKind.MEDIA)?.let { TilePick(it, true, 8.dp) } ?: boxPick
        "box3d" -> vm.artOf(g, ArtKind.BOX3D)?.let { TilePick(it, true, 4.dp) } ?: boxPick
        // posters and screenshots fill the tile; without one, the box art at its own shape
        "poster" -> vm.artOf(g, ArtKind.POSTER)?.let { TilePick(it, false, 0.dp) } ?: boxPick
        "shot" -> vm.artOf(g, ArtKind.BACKDROP)?.let { TilePick(it, false, 0.dp) } ?: boxPick
        "logo" -> if (vm.artOf(g, ArtKind.LOGO) != null) TilePick(null, false, 0.dp) else boxPick
        else -> boxPick
    }
}

/**
 * A disc or cartridge picture with no box around it. When [focused], the highlight colour traces the picture's own
 * outline: the picture is drawn in the highlight colour, nudged a few pixels in every direction, underneath itself.
 */
@Composable
private fun MediaPicture(model: Any, name: String, focused: Boolean, modifier: Modifier) {
    val painter = coil.compose.rememberAsyncImagePainter(model)
    val ring = C.focus
    val glow by animateFloatAsState(if (focused) 1f else 0f, label = "mediaRing")
    Box(
        modifier.drawWithContent {
            val src = painter.intrinsicSize
            if (glow > 0f && src.isSpecified && src.width > 0f && src.height > 0f) {
                // where ContentScale.Fit puts the picture
                val k = minOf(size.width / src.width, size.height / src.height)
                val w = src.width * k; val h = src.height * k
                val left = (size.width - w) / 2f; val top = (size.height - h) / 2f
                val r = 3.dp.toPx()
                val tint = androidx.compose.ui.graphics.ColorFilter.tint(ring.copy(alpha = glow))
                for (i in 0 until 16) {
                    val a = i * Math.PI / 8
                    translate(left + (kotlin.math.cos(a) * r).toFloat(), top + (kotlin.math.sin(a) * r).toFloat()) {
                        with(painter) { draw(androidx.compose.ui.geometry.Size(w, h), colorFilter = tint) }
                    }
                }
            }
            drawContent()
        }
    ) {
        androidx.compose.foundation.Image(painter, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** The art styles a system's games can use. */
/** An art style for every tile inside (Recently Played's own Style); null: each game's system's style. */
val LocalArtStyle = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * The style a game is drawn in: the row's own (Recently Played's Style) or its system's. "mixed": each game one of
 * the styles it has art for (disc / cartridge, box art, poster), picked from the game's id so it stays put.
 */
/** Does this game's tile picture stand on its own (so the highlight follows its outline, not the tile)? */
fun ownOutline(vm: EclipseVM, g: Game, style: String): Boolean = tilePicture(vm, g, style).standsAlone

fun styleOf(vm: EclipseVM, g: Game, row: String?): String {
    val s = row ?: vm.artStyleFor(g.platform)
    if (s != "mixed") return s
    val opts = listOfNotNull(
        "box",
        "media".takeIf { vm.artOf(g, ArtKind.MEDIA) != null }, "box3d".takeIf { vm.artOf(g, ArtKind.BOX3D) != null },
        "poster".takeIf { vm.artOf(g, ArtKind.POSTER) != null }
    )
    return opts[Math.floorMod(g.id.hashCode(), opts.size)]
}

val ART_STYLES = listOf("box" to "Box art", "box3d" to "3D box", "poster" to "Poster", "media" to "Physical", "logo" to "Logo", "shot" to "Screenshot")

/**
 * A game's corner badge: a RomM game shows its cloud (or its download's percentage), and a game going up to RomM
 * shows its upload's percentage.
 */
@Composable
fun GameBadge(vm: EclipseVM, g: Game, modifier: Modifier = Modifier) {
    if (vm.isRommEntry(g)) { RommBadge(vm, g, modifier); return }
    // a multi-disc game started on another disc than the first: which one
    val disc = vm.discLabel(g)
    val up = vm.uploads[g.id] ?: run {
        if (disc != null) Box(
            modifier.padding(6.dp).height(22.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xCC000000)).border(1.dp, C.accent.copy(alpha = 0.6f), RoundedCornerShape(11.dp)).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) { Text(disc.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }
        return
    }
    // upload progress: a red circle, big enough to read from the couch
    Box(
        modifier.padding(6.dp).size(BADGE_SIZE).clip(CircleShape).background(BADGE_RED),
        contentAlignment = Alignment.Center
    ) { Text("\u2191${(up * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

/** The download / upload badges: a red circle. */
private val BADGE_SIZE = 40.dp
private val BADGE_RED = Color(0xFFD32F2F)

/** Little cloud on a RomM game that isn't downloaded yet (or its download progress). */
@Composable
fun RommBadge(vm: EclipseVM, g: Game, modifier: Modifier = Modifier) = RommBadgeFor(g.id.removePrefix("rm:").toIntOrNull(), modifier)

/** The same badge for a RomM game by its RomM id (the search results use it). */
@Composable
fun RommBadgeFor(romId: Int?, modifier: Modifier = Modifier) {
    val dl = romId?.let { id -> com.eclipse.launcher.RommDownloads.items.value.firstOrNull { it.rom.id == id } }
    // the RomM logo (res/drawable-nodpi/romm_logo.png) fills the circle; a download's % replaces it
    Box(
        modifier.padding(6.dp).size(BADGE_SIZE).clip(CircleShape).background(Color(0xE6101014)),
        contentAlignment = Alignment.Center
    ) {
        if (dl != null && dl.progress >= 0f) Text("\u2193${(dl.progress * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        else if (dl != null && dl.state == com.eclipse.launcher.RommDownloads.State.QUEUED) Text("\u2193\u2026", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        else androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.eclipse.launcher.R.drawable.romm_logo), contentDescription = "On RomM",
            modifier = Modifier.fillMaxSize()
        )
    }
}


/**
 * Platforms in slide view, laid out exactly like Recently Played: the system's picture (its theme background, else the
 * backdrop of its most recently played game) in the top-right corner, its logo, game count and year top left, the
 * "Systems" label over the row of cards, the highlighted card first on the left, the list wrapping round, and the
 * button hints along the bottom.
 */
@Composable
private fun PlatformCarousel(vm: EclipseVM, plats: List<PlatformDef>, counts: Map<String, Int>, fr: FocusRequester, active: Boolean) {
    val idx = plats.indexOfFirst { it.id == vm.lastPlatform }.coerceAtLeast(0)
    val cur = plats[idx]
    val state = rememberLazyListState(initialFirstVisibleItemIndex = idx)
    LaunchedEffect(cur.id) { state.pinToFirst(idx) }
    val ps = vm.iconSize("platforms", "carousel") * SLIDE_POSTER * SLIDE_GAME
    // the picture: the theme's background for the system, else the backdrop of its most recently played game
    val pick = remember(cur.id, vm.games, vm.lastPlayed, vm.favGames) {
        vm.games.filter { cur.id == Platforms.ALL || it.platform == cur.id || (cur.id == Platforms.FAVORITES && it.id in vm.favGames) }
            .maxByOrNull { vm.lastPlayed[it.id]?.toLongOrNull() ?: 0L }
    }
    // the list wraps round: Left on the first system goes to the last, Right on the last to the first
    val scope = rememberCoroutineScope()
    fun wrapTo(i: Int) {
        scope.launch {
            state.scrollToItem(i)
            vm.lastPlatform = plats[i].id
            withFrameNanos { }; withFrameNanos { }
            runCatching { fr.requestFocus() }
        }
    }
    val wrap = Modifier.onPreviewKeyEvent { e ->
        if (plats.size < 2 || (e.key != Key.DirectionLeft && e.key != Key.DirectionRight)) return@onPreviewKeyEvent false
        when {
            e.key == Key.DirectionRight && idx == plats.lastIndex -> { if (e.type == KeyEventType.KeyDown) wrapTo(0); true }
            else -> false
        }
    }
    Box(Modifier.fillMaxSize()) {
        SlideCornerBackdrop(vm, pick, image = vm.platformBackdrop(cur.id), glass = active)
        // top left: the system's logo (or name), its game count and year, and the game you played last on it
        Column(Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 20.dp).widthIn(max = 380.dp)) {
            PlatformTitle(vm, cur, 26.sp, 52.dp)
            val n = counts[cur.id] ?: 0
            Text(
                listOfNotNull("$n ${if (n == 1) "game" else "games"}", Platforms.year(cur.id)?.toString()).joinToString("  ·  ").uppercase(),
                color = C.x(0xFFB9B4AAL), fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp)
            )
            if (pick != null && vm.lastPlayed[pick.id] != null) Text(
                "Last played: ${pick.name}", color = C.x(0xFFE6E3DAL), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        SlideHintBar(Modifier.align(Alignment.BottomStart), listOf("R" to "Options", "Select" to "Menu", "A" to "Open", "B" to "Back"))
        BoxWithConstraints(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 34.dp)) {
            val endPad = (maxWidth - 253.dp * ps * CARD_ASPECT - 40.dp).coerceAtLeast(30.dp)
            Column {
                LazyRow(
                    modifier = wrap,
                    state = state,
                    contentPadding = PaddingValues(start = 30.dp, end = endPad, top = 0.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    itemsIndexed(plats, key = { _, p -> p.id }) { i, p ->
                        val big = i == idx
                        // the others are nearly as big as the highlighted one (like the games' slide view)
                        val h by animateDpAsState(if (big) 253.dp * ps else 253.dp * ps * 0.86f, label = "platSize")
                        val up by animateDpAsState(if (big) 8.dp else 0.dp, label = "platLift")
                        PlatformCard(vm, p, counts[p.id] ?: 0, if (big) fr else null, Modifier.padding(bottom = up).size(width = h * CARD_ASPECT, height = h))
                    }
                }
            }
        }
    }
}

/**
 * Platforms in cover flow: the same look as the games' and Recently Played's Cover flow. The highlighted system's
 * picture behind (its theme background, else its last played game's backdrop), "Systems" top left, the cards turning
 * in 3D, and its logo, game count, the game played last and where you are underneath.
 */
@Composable
private fun PlatformCoverFlow(vm: EclipseVM, plats: List<PlatformDef>, counts: Map<String, Int>, fr: FocusRequester) {
    val cur = plats.firstOrNull { it.id == vm.lastPlatform } ?: plats.first()
    // its most recently played game (for the picture and the "last played" line)
    val pick = remember(cur.id, vm.games, vm.lastPlayed, vm.favGames) {
        vm.games.filter { cur.id == Platforms.ALL || it.platform == cur.id || (cur.id == Platforms.FAVORITES && it.id in vm.favGames) }
            .maxByOrNull { vm.lastPlayed[it.id]?.toLongOrNull() ?: 0L }
    }
    Box(Modifier.fillMaxSize()) {
        StageBackdrop(vm, pick, side = false, image = vm.platformBackdrop(cur.id))
        SlideTopBar(vm, "Systems", clock = false, modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 14.dp, end = 150.dp))
        Flow3D(
            items = plats,
            itemKey = { it.id },
            startIndex = plats.indexOfFirst { it.id == vm.lastPlatform }.coerceAtLeast(0),
            focusRequester = fr,
            // same place, spacing and turn as the games' Cover flow
            modifier = Modifier.fillMaxSize().padding(top = 52.dp),
            aspect = CARD_ASPECT,
            corner = vm.cardCorner,
            floor = HomeDark,
            background = false,
            scale = vm.iconSize("platforms", "coverflow"),
            tilt = 60f,
            stepK = 0.36f,
            captionBottom = 40.dp,
            onIndex = { vm.lastPlatform = it.id },
            onActivate = { vm.openPlatform(it.id) },
            onContext = { vm.platformMenu = it.id },
            face = { p, m -> PlatformFace(vm, p, m) },
            caption = { p, i, total ->
                Box(Modifier.heightIn(max = 56.dp)) { PlatformTitle(vm, p, 24.sp, 50.dp, center = true) }
                val n = counts[p.id] ?: 0
                Text(
                    listOfNotNull("$n ${if (n == 1) "game" else "games"}", Platforms.year(p.id)?.toString()).joinToString("  \u00b7  ").uppercase(),
                    color = C.x(0xFFB9B4AAL), fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 7.dp)
                )
                val last = pick?.takeIf { p.id == cur.id && vm.lastPlayed[it.id] != null }
                if (last != null) Text(
                    "Last played: ${last.name}", color = C.x(0xFFE6E3DAL), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
                FlowScrubber(i, total, Modifier.padding(top = 8.dp))
            }
        )
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ButtonHint("A", "Open"); ButtonHint("Select", "Menu"); ButtonHint("R", "View")
        }
    }
}

/** A platform's card on its own (cover flow, and the reorder grid). */
@Composable
private fun PlatformFace(vm: EclipseVM, p: PlatformDef, modifier: Modifier) = SystemCardFace(vm, p, modifier)


/** One folding list in an options drawer: its name, the choices (id to label), which one is current, what picking does. */
/**
 * A drop-down of the right panel. With [makeDefault]: "Make default" (this choice becomes the default for every system
 * without its own) and, with [useDefault], "Use default here"; [isDefault] = the current choice is the default one.
 */
class DrawerList(
    val title: String, val options: List<Pair<String, String>>, val current: String,
    val makeDefault: (() -> Unit)? = null, val useDefault: (() -> Unit)? = null, val isDefault: Boolean = false,
    val onPick: (String) -> Unit
)

/** Options for the platforms, sliding out from the right: which systems to show, and how to lay them out. */
@Composable
private fun PlatformDrawer(vm: EclipseVM, close: () -> Unit) {
    val sources = listOf("romm" to "All", "local" to "Local", "rommonly" to "RomM")
    val views = vm.viewsFor("systems", rememberCustomViews())
    val mode = vm.shownView(vm.platView, views.map { it.first })
    OptionsDrawer(
        vm, "Platforms",
        listOf(
            DrawerList("View", views.filter { vm.viewOn(it.first) }, mode) { vm.choosePlatView(it) },
            // Card: the system's icon with its logo under it. Icon: the picture alone, filling the card
            DrawerList("Style", listOf("card" to "Card", "icon" to "Icon"), vm.platStyle) { vm.choosePlatStyle(it) }
        ) + (if (!vm.rommOn()) emptyList() else listOf(
            // only with RomM connected: All (yours + RomM), yours, or RomM's
            DrawerList("Source", sources, if (vm.platSource == "rommonly" || vm.platSource == "romm") vm.platSource else "local") { vm.choosePlatSource(it) }
        )) + listOf(
            // Custom: closes the drawer and opens the systems to put in your own order
            DrawerList(
                "Order", listOf("az" to "Alphabetical", "played" to "Most played", "year" to "Year", "custom" to "Custom"), vm.platSort
            ) { v -> vm.choosePlatSort(v); if (v == "custom") { close(); vm.platReorder = true } }
        ),
        size = { IconSizeSection(vm, "platforms", mode, gridFallback = 6) }, sizeAt = 2,
        extra = {
            if (vm.platSort == "custom") DrawerSection("Rearrange", "Custom order", false) { close(); vm.platReorder = true }
        },
        close = close
    )
}

/**
 * A drawer of options sliding out from the right edge (used by the platforms and by each system's games). Top: a gear
 * (Android's settings), Wi-Fi, battery and the clock. Under that a Page | System switch: Page is this screen's options,
 * Sort (Sort, Order, Source) and View (Style, View, Size, the rest), each a folding list; System is Wi-Fi, Bluetooth,
 * what's playing and notifications. Left or B closes it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OptionsDrawer(
    vm: EclipseVM, title: String, lists: List<DrawerList>,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    /** the size drop-down (it has its own slider), placed after the first [sizeAt] lists */
    size: (@Composable ColumnScope.() -> Unit)? = null, sizeAt: Int = lists.size,
    close: () -> Unit
) = Glass {
    val fm = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    BackHandler { close() }
    val shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
    val light = ThemeColors.light.value
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    // Page (this screen's options) or System (Wi-Fi, Bluetooth, notifications); the drawer opens on Page
    var system by remember { mutableStateOf(false) }
    val switchFr = remember { FocusRequester() }
    val wifiFr = remember { FocusRequester() }
    var open by remember { mutableStateOf<String?>(null) }
    val headers = remember(lists.size) { List(lists.size) { FocusRequester() } }
    // Sort group: how the list is ordered and where it comes from. View group: everything else (and Size)
    val sortTitles = setOf("Sort", "Order", "Source")
    val sortLists = lists.withIndex().filter { it.value.title in sortTitles }.sortedBy { if (it.value.title == "Source") 1 else 0 }
    val viewLists = lists.withIndex().filter { it.value.title !in sortTitles }
    // the drawer opens with the controller on its first option (the switch when it has none)
    val firstOpt = if (sortLists.isNotEmpty()) headers[sortLists.first().index] else if (viewLists.isNotEmpty()) headers[viewLists.first().index] else switchFr
    // ...or back on the row you were on, if you've had this panel open since you came to this page
    val memKey = "${vm.screen}/$title"
    val remembered = vm.drawerMemory[memKey]?.let { t -> lists.indexOfFirst { it.title == t }.takeIf { it >= 0 } }
        ?.let { headers[it] }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { (remembered ?: firstOpt).requestFocus() } }
    val memo: (String) -> (Boolean) -> Unit = { t -> { f -> if (f) vm.drawerMemory[memKey] = t } }
    // light mode: off-white panel, white cards, near-black writing and darker greys (DockLightPal)
    CompositionLocalProvider(LocalPal provides if (light) DockLightPal else LocalPal.current) {
    Box(Modifier.fillMaxHeight().width(270.dp).clip(shape)) {
    GlassFill(Alignment.TopEnd)
    // a tint over the glass (about 70% solid in dark mode), so the options read cleanly over busy game art
    Box(Modifier.fillMaxSize().background(glassTint(light)))
    Box(
        Modifier.fillMaxHeight().width(270.dp)
            .clip(shape).border(1.dp, if (light) C.line else Color(0x29FFFFFF), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            // Up / Down never leave the drawer, even when the next row is outside it (only those two directions:
            // Left still closes it, and handing focus back to the screen on close isn't blocked)
            .focusProperties { exit = { d -> if (d == FocusDirection.Up || d == FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default } }
            .focusGroup()
            .onKeyEvent { e ->
                when (e.key) {
                    Key.DirectionLeft -> {
                        if (e.type == KeyEventType.KeyDown) {
                            val t = open
                            if (t != null && !system) {
                                // on a setting's list page: back to the buttons
                                val i = lists.indexOfFirst { it.title == t }
                                runCatching { switchFr.requestFocus() }; open = null
                                if (i >= 0) scope.launch { refocus(headers[i]) }
                            } else close()
                        }
                        true
                    }
                    // keep Up / Down inside the drawer (the rows around it would otherwise take them)
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (e.type == KeyEventType.KeyDown) fm.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    Key.DirectionRight -> true
                    else -> false
                }
            }
    ) {
        // no focus trap here: it would also block handing focus back to the screen on close, and the drawer's
        // focused item vanishing would then drop focus onto the left dock (opening it). Keys are handled above.
        Column(
            Modifier.fillMaxSize().menuScroll().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // the switch sits at the very top; its left half is named after this screen ("SNES", "Recently Played"…)
            DockSwitch(system, { system = it; open = null }, switchFr, page = title)
            // a setting's button was pressed: the whole panel is that setting's list (B / Left / ‹ comes back)
            val page = if (system) null else open?.let { t -> lists.withIndex().firstOrNull { it.value.title == t } }
            if (page != null) {
                val (pi, pl) = page
                // focus waits on the switch (there in both views) while the rows change, then lands on the button
                // again: a focused row vanishing would otherwise drop focus out of the panel
                val back: () -> Unit = { runCatching { switchFr.requestFocus() }; open = null; scope.launch { refocus(headers[pi]) } }
                DrawerListPage(pl, back) { action -> action(); back() }
            } else {
            // what's happening right now: transfers, conversions, and notifications that just came in (they leave by themselves)
            TransientArea(vm, tick)
            val openPage: (String) -> Unit = { t -> runCatching { switchFr.requestFocus() }; open = t }
            if (!system) {
                if (sortLists.isNotEmpty()) {
                    DockHeading("Sort")
                    sortLists.forEach { (i, l) -> DrawerListButton(l, headers[i], openPage, memo(l.title)) }
                }
                if (viewLists.isNotEmpty() || size != null || extra != null) {
                    DockHeading("View")
                    viewLists.forEachIndexed { n, (i, l) ->
                        DrawerListButton(l, headers[i], openPage, memo(l.title))
                        // the size drop-down sits where the screen asked for it (after its first [sizeAt] lists)
                        if (size != null && i == sizeAt - 1) size.invoke(this)
                    }
                    if (size != null && (sizeAt <= 0 || viewLists.none { it.index == sizeAt - 1 })) size.invoke(this)
                    extra?.invoke(this)
                }
            } else {
                DockHeading("Quick")
                QuickTiles(tick, wifiFr)
                NotificationsSection(vm)
            }
            }
        }
    }
    }
    }
}

/** One setting of the drawer (Sort, Source, Style …) as a button: its name, the current choice in gold, and ›. */
@Composable
private fun DrawerListButton(l: DrawerList, header: FocusRequester, openPage: (String) -> Unit, onFocus: ((Boolean) -> Unit)?) {
    val shown = (l.options.firstOrNull { it.first == l.current }?.second ?: "") + if (l.makeDefault != null && l.isDefault) " (default)" else ""
    DrawerSection(l.title, shown, false, header, onFocus = onFocus, arrow = "\u203A") { openPage(l.title) }
}

/**
 * A setting's choices filling the whole panel: "‹ TITLE" (B / Left / pressing it goes back), each choice as a full
 * row with the current one marked and highlighted, then Make default / Use default here. [pick] runs a choice and
 * returns to the buttons.
 */
@Composable
private fun DrawerListPage(l: DrawerList, back: () -> Unit, pick: (() -> Unit) -> Unit) {
    val cur = remember { FocusRequester() }
    val backFr = remember { FocusRequester() }
    BackHandler { back() }
    LaunchedEffect(l.title) {
        repeat(3) { withFrameNanos { } }
        if (runCatching { cur.requestFocus() }.isFailure) runCatching { backFr.requestFocus() }
    }
    FocusBox(Modifier.fillMaxWidth().height(32.dp), shape = RoundedCornerShape(8.dp), onClick = back, focusRequester = backFr) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\u2039", color = DockGold, fontSize = 16.sp)
            Text(l.title.uppercase(), color = DockGold, fontSize = 10.sp, letterSpacing = 2.4.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(1f))
            Text("B \u00b7 BACK", color = C.sub, fontSize = 8.sp, letterSpacing = 1.sp)
        }
    }
    val hasCurrent = l.options.any { it.first == l.current }
    l.options.forEachIndexed { i, (id, label) ->
        val sel = l.current == id
        DrawerChoice(label, sel, fr = if (sel || (!hasCurrent && i == 0)) cur else null, tall = true) { pick { l.onPick(id) } }
    }
    val make = l.makeDefault?.takeIf { !l.isDefault }
    val use = l.useDefault
    if (make != null || use != null) Box(Modifier.padding(vertical = 3.dp, horizontal = 2.dp).fillMaxWidth().height(1.dp).background(C.x(0x14FFFFFFL)))
    if (make != null) DrawerChoice("Make default", false, tall = true, radio = false) { pick(make) }
    if (use != null) DrawerChoice("Use default here", false, tall = true, radio = false) { pick(use) }
}

/** The choices of an open drop-down: a darker box hanging under its header. */
@Composable
private fun DrawerMenu(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)
    Column(
        Modifier.fillMaxWidth().offset(y = (-7).dp).clip(shape).background(if (LocalPal.current.light) Color(0xBFFFFFFF) else C.x(0x80100F0EL))
            .border(1.dp, C.accent.copy(alpha = 0.3f), shape).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** A drop-down's header: its name on the left, the current choice in gold on the right, and an arrow. */
@Composable
private fun DrawerSection(title: String, value: String, expanded: Boolean, fr: FocusRequester? = null, onFocus: ((Boolean) -> Unit)? = null, arrow: String? = null, onClick: () -> Unit) {
    val shape = if (expanded) RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp) else RoundedCornerShape(9.dp)
    FocusBox(Modifier.fillMaxWidth().height(38.dp), shape = shape, onClick = onClick, focusRequester = fr, onFocusChange = { f -> onFocus?.invoke(f) }) {
        Row(
            Modifier.fillMaxSize()
                .background(if (expanded) C.accent.copy(alpha = if (LocalPal.current.light) 0.14f else 0.10f) else if (LocalPal.current.light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (expanded) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title.uppercase(), color = C.x(0xB3FFFFFFL), fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(value.uppercase(), color = DockGold, fontSize = 10.sp, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            Text(arrow ?: if (expanded) "\u2303" else "\u2304", color = C.sub, fontSize = if (arrow != null) 14.sp else 10.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** One choice in an open drop-down: a radio dot and its name. */
@Composable
private fun DrawerChoice(label: String, selected: Boolean, fr: FocusRequester? = null, tall: Boolean = false, radio: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(if (tall) 9.dp else 7.dp)
    FocusBox(Modifier.fillMaxWidth().height(if (tall) 38.dp else 32.dp), shape = shape, onClick = onClick, focusRequester = fr) {
        Row(
            Modifier.fillMaxSize()
                .then(if (tall) Modifier.background(if (LocalPal.current.light) Color.White else C.x(0x0AFFFFFFL)).border(1.dp, C.x(0x10FFFFFFL), shape) else Modifier)
                .padding(horizontal = if (tall) 12.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (radio) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (selected) C.accent else C.sub, CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (selected) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent)) }
                Spacer(Modifier.width(9.dp))
            }
            Text(label.uppercase(), color = if (selected) C.ink else C.x(0xB3FFFFFFL), fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}


/**
 * A RomM game that isn't on this device, opened while Settings → RomM → "Opening a RomM game" is Ask:
 * install and play, or just install, with Remember (that becomes the setting).
 */
@Composable
fun RommLaunchSheet(vm: EclipseVM, g: Game) {
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    var rememberIt by remember(g.id) { mutableStateOf(false) }
    val close = { vm.rommAsk = null }
    val rom = vm.romFor(g)
    fun pick(how: String) { if (rememberIt) vm.chooseRommLaunch(how); close(); vm.rommOpen(g, how) }
    MenuSheet(
        "On RomM", g.name, (Platforms.byId(g.platform)?.name ?: g.platform) + " \u00b7 not on this device yet", onDismiss = close,
        image = { Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.x(0x1FFFFFFFL), RoundedCornerShape(8.dp))) }
    ) {
        MenuRow("Install and play", gold = true, fr = first) { pick("play") }
        MenuRow("Install", value = "\u2193") { pick("download") }
        MenuRow("Remember my choice", value = if (rememberIt) "On" else "Off", selected = rememberIt) { rememberIt = !rememberIt }
        if (rememberIt) Text("Change it in Settings \u2192 Connections \u2192 RomM.", color = C.sub, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** A multi-disc game on RomM, about to download: all its discs, or just one? */
@Composable
fun RommDiscSheet(vm: EclipseVM, g: Game, play: Boolean) {
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    val close = { vm.rommDiscAsk = null }
    val discs = vm.discsOfRomm(g)
    MenuSheet(
        "On RomM", g.name, "${discs.size} discs \u00b7 install which?", onDismiss = close,
        image = { Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.x(0x1FFFFFFFL), RoundedCornerShape(8.dp))) }
    ) {
        MenuRow("All ${discs.size} discs", value = vm.fmtBytes(discs.sumOf { it.sizeBytes }), gold = true, fr = first) { close(); vm.rommDownloadDiscs(g, discs, play) }
        discs.forEachIndexed { i, d ->
            MenuRow("Disc ${i + 1} only", value = vm.fmtBytes(d.sizeBytes)) { close(); vm.rommDownloadDiscs(g, listOf(d), play) }
        }
        MenuRow("Cancel", value = "B") { close() }
    }
}

/** "Remove … from Recently Played?" (Select on an On Deck game), in the right dock's look. */
@Composable
fun OnDeckRemove(vm: EclipseVM, g: Game) {
    val cancel = remember { FocusRequester() }
    FocusOnShow(cancel) // Cancel first: a stray A press doesn't remove anything
    val close = { vm.onDeckRemove = null }
    val t = vm.playedSeconds(g)
    val sys = Platforms.byId(g.platform)?.name ?: if (g.platform == "android") "Android" else g.platform
    MenuSheet(
        "Recently Played", g.name, sys + (if (t > 0) " \u00b7 played ${fmtDuration(t)}" else " \u00b7 not played yet"), onDismiss = close,
        image = {
            Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.size(54.dp, 72.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, C.x(0x1FFFFFFFL), RoundedCornerShape(8.dp)))
        }
    ) {
        Text("Remove it from Recently Played? It stays in your library, and comes back when you play it.", color = C.sub, fontSize = 10.sp, lineHeight = 13.5.sp, modifier = Modifier.padding(bottom = 2.dp))
        MenuRow("Keep it", value = "B", fr = cancel) { close() }
        MenuRow("Remove from Recently Played", danger = true) {
            // the controller goes to the game next to it (the removed one's spot is gone)
            val list = vm.homeGames()
            val i = list.indexOfFirst { it.id == g.id }
            (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.let { n -> GamepadFocus.lastKey = "g:${n.id}"; vm.focused = n }
            close(); vm.removeFromOnDeck(g)
        }
    }
}



/**
 * "Icon size" in a right-hand drawer: a slider per view (slide view, cover flow, grid) for this screen, plus
 * "Make default" (these sizes everywhere) and "Use default" (forget this screen's own sizes).
 */
@Composable
fun IconSizeSection(vm: EclipseVM, screen: String, view: String, gridFallback: Int = 4) {
    // only the view on screen: a size slider for slide view / cover flow, icons per row for the grid
    val grid = view == "grid"
    val key = if (grid) vm.gridKey(screen) else view
    var open by remember { mutableStateOf(false) }
    val custom = vm.iconSizeCustom(screen, key)
    val header = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val cols = vm.gridCols(screen, gridFallback)
    val value = if (grid) "$cols per row" else "${(vm.iconSize(screen, view) * 100).toInt()}%"
    DrawerSection("Size", value + if (custom) "" else " (default)", open, header) { open = !open }
    androidx.compose.animation.AnimatedVisibility(visible = open) {
        DrawerMenu {
            if (grid) {
                ColsStepper(cols) { vm.setGridCols(screen, it) }
            } else {
                MiniSlider(when (view) { "carousel" -> "Slide view"; "detailflow" -> "Detail cover flow"; else -> "Cover flow" }, vm.iconSize(screen, view)) { vm.setIconSize(screen, view, it) }
            }
            if (custom) DrawerChoice("Make default", false) {
                // "Use default here" disappears; keep focus on the header rather than losing it
                runCatching { header.requestFocus() }
                vm.makeIconSizeDefault(screen, key, if (grid) cols.toFloat() else vm.iconSize(screen, view))
                scope.launch { refocus(header) }
            }
            if (custom) DrawerChoice("Use default here", false) {
                runCatching { header.requestFocus() }
                vm.resetIconSize(screen, key)
                scope.launch { refocus(header) }
            }
        }
    }
}

/** Icons per row: \u2212 and + buttons around the number (2 \u2026 10). */
@Composable
private fun ColsStepper(n: Int, onChange: (Int) -> Unit) {
    // one stop for the controller (like the size slider): Left / Right change it. The drawer's rows only move up and
    // down, so two separate − / + buttons side by side couldn't both be reached. They stay as tap targets.
    fun step(d: Int) { val v = (n + d).coerceIn(2, 10); if (v != n) onChange(v) }
    FocusBox(
        Modifier.fillMaxWidth().onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
        shape = RoundedCornerShape(12.dp),
        onClick = { }
    ) {
        Row(
            Modifier.fillMaxWidth().background(C.x(0x0FFFFFFFL)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StepBtn("\u2212", n > 2) { step(-1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", color = C.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("per row  \u00b7  \u25C0 \u25B6", color = C.sub, fontSize = 11.sp)
            }
            StepBtn("+", n < 10) { step(1) }
        }
    }
}

/** − or + on the per-row stepper: tap only (the controller uses Left / Right on the whole stepper). */
@Composable
private fun StepBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(C.x(0x1FFFFFFFL))
            .pointerInput(enabled) { detectTapGestures { if (enabled) onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Color.White else C.x(0x4DFFFFFFL), fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** A small slider for a drawer: Left / Right (or tap / drag) to change, A to go back to 100%. */
@Composable
private fun MiniSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val min = 0.6f
    val max = 1.5f
    val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
    var widthPx by remember { mutableStateOf(1f) }
    FocusBox(
        Modifier.fillMaxWidth().height(54.dp)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onChange(value - 0.05f); true }
                    Key.DirectionRight -> { onChange(value + 0.05f); true }
                    else -> false
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) { detectTapGestures { o -> onChange(min + (max - min) * (o.x / widthPx).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onChange(min + (max - min) * (change.position.x / widthPx).coerceIn(0f, 1f))
                }
            },
        shape = RoundedCornerShape(12.dp),
        onClick = { onChange(1f) }
    ) {
        Column(
            Modifier.fillMaxSize().background(C.x(0x0FFFFFFFL)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row {
                Text(label, color = C.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${(value * 100).toInt()}%", color = C.sub, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Box(contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x33FFFFFFL)))
                Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
            }
        }
    }
}



/**
 * Puts focus on [req] again over the next couple of frames: a drawer option can rebuild the screen behind it
 * (a new view style, a new list), and whatever that does with focus must not win over the drawer.
 */
private suspend fun refocus(req: FocusRequester) {
    repeat(3) {
        withFrameNanos { }
        runCatching { req.requestFocus() }
    }
}


/**
 * Custom order for the platforms: the systems as a grid of their icons (All games and Favourites stay first).
 * A picks one up, the D-pad moves it, A puts it down; by touch, hold one and drag it. Saved as you go. B / Done closes.
 */
@Composable
fun PlatformReorder(vm: EclipseVM) {
    val counts = vm.platCounts
    var list by remember {
        mutableStateOf(vm.orderPlats(Platforms.all.filter { vm.showsPlat(it.id, counts) && it.id != Platforms.ALL && it.id != Platforms.FAVORITES }))
    }
    var held by remember { mutableStateOf<String?>(null) }
    val reqs = remember { HashMap<String, FocusRequester>() }
    val done = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val close = { vm.platReorder = false }
    BackHandler { if (held != null) held = null else close() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { (list.firstOrNull()?.let { reqs[it.id] } ?: done).requestFocus() }
    }
    fun moveTo(id: String, j0: Int) {
        val i = list.indexOfFirst { it.id == id }
        val j = j0.coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list = list.toMutableList().apply { add(j, removeAt(i)) }
        vm.savePlatOrder(list.map { it.id })
        if (vm.platSort != "custom") vm.choosePlatSort("custom")
        scope.launch { withFrameNanos { }; runCatching { reqs[id]?.requestFocus() } }
    }
    Glass {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
            GlassScreenBackdrop(vm)
            GlassPanel(Modifier.fillMaxSize().padding(10.dp)) {
                Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PLATFORMS \u203A", color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.8.sp)
                            Text("Custom order", color = C.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (held == null) "A PICK UP  \u00b7  HOLD AND DRAG  \u00b7  B DONE" else "D-PAD MOVE  \u00b7  A PUT DOWN",
                            color = C.sub, fontSize = 8.5.sp, letterSpacing = 1.4.sp
                        )
                        Pill("Done", Modifier.padding(start = 12.dp).width(110.dp), primary = true, focusRequester = done) { close() }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val cols = 6
                        val gap = 10.dp
                        val cell = (maxWidth - gap * (cols - 1)) / cols
                        val cellPx = with(LocalDensity.current) { (cell + gap).toPx() }
                        var dragId by remember { mutableStateOf<String?>(null) }
                        var drag by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        Column(Modifier.fillMaxSize().menuScroll(), verticalArrangement = Arrangement.spacedBy(gap)) {
                            list.chunked(cols).forEachIndexed { r, line ->
                                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    line.forEachIndexed { c, p ->
                                        val i = r * cols + c
                                        val req = reqs.getOrPut(p.id) { FocusRequester() }
                                        val isHeld = held == p.id
                                        val dragging = dragId == p.id
                                        FocusBox(
                                            Modifier.size(cell)
                                                .zIndex(if (dragging || isHeld) 1f else 0f)
                                                .graphicsLayer {
                                                    if (dragging) { translationX = drag.x; translationY = drag.y }
                                                    val sc = if (dragging || isHeld) 1.08f else 1f; scaleX = sc; scaleY = sc
                                                }
                                                .onPreviewKeyEvent { e ->
                                                    if (!isHeld || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                                    when (e.key) {
                                                        Key.DirectionLeft -> { moveTo(p.id, i - 1); true }
                                                        Key.DirectionRight -> { moveTo(p.id, i + 1); true }
                                                        Key.DirectionUp -> { moveTo(p.id, i - cols); true }
                                                        Key.DirectionDown -> { moveTo(p.id, i + cols); true }
                                                        else -> false
                                                    }
                                                }
                                                .pointerInput(p.id, i) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { dragId = p.id; drag = androidx.compose.ui.geometry.Offset.Zero },
                                                        onDragEnd = {
                                                            val dc = Math.round(drag.x / cellPx); val dr = Math.round(drag.y / cellPx)
                                                            val id = p.id
                                                            dragId = null; drag = androidx.compose.ui.geometry.Offset.Zero
                                                            moveTo(id, i + dc + dr * cols)
                                                        },
                                                        onDragCancel = { dragId = null; drag = androidx.compose.ui.geometry.Offset.Zero }
                                                    ) { ch, d -> ch.consume(); drag += d }
                                                },
                                            shape = RoundedCornerShape(14.dp),
                                            focusRequester = req,
                                            focusKey = "reorder:${p.id}",
                                            onClick = { held = if (isHeld) null else p.id }
                                        ) {
                                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))) {
                                                PlatformFace(vm, p, Modifier.fillMaxSize())
                                                // where it sits now
                                                Text(
                                                    "${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(6.dp))
                                                        .background(if (isHeld) C.accent else Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                                if (isHeld) Box(Modifier.matchParentSize().border(3.dp, C.accent, RoundedCornerShape(14.dp)))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


