package com.eclipse.launcher.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.Screen

/** Dock open with names next to the icons (+ its hairline edge). */
private val DOCK_W = 201.dp
/** Dock as a slim icon rail (when it stays on screen and isn't being used) + its hairline edge. */
private val DOCK_RAIL = 69.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Root(vm: EclipseVM, pickFolder: () -> Unit, pickImage: () -> Unit) {
    val ctx = LocalContext.current
    RightStick.Driver() // right joystick scrolls the open menu's list
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    // B / Back: close the peeked dock -> artwork screen -> game menu -> return Home. On Home it does nothing (we are the launcher).
    var backToDock by remember { mutableStateOf(false) }
    BackHandler {
        when {
            vm.dockPeek -> vm.dockPeek = false
            vm.viewMenu -> vm.viewMenu = false
            vm.onDeckRemove != null -> vm.onDeckRemove = null
            vm.searchOpen -> vm.searchOpen = false
            vm.platDrawer -> vm.platDrawer = false
            vm.deckDrawer -> vm.deckDrawer = false
            vm.appsDrawer -> vm.appsDrawer = false
            vm.gamesDrawer -> vm.gamesDrawer = false
            vm.storeDelete != null -> vm.storeDelete = null
            vm.gameDelete != null -> vm.gameDelete = null
            vm.platArtScreen != null -> vm.platArtScreen = null
            vm.artGame != null -> vm.artGame = null
            vm.menuGame != null -> vm.menuGame = null
            // B in Settings: back Home, with the controller on Home in the left dock
            vm.screen == Screen.SETTINGS -> {
                vm.homeSection = com.eclipse.launcher.data.HomeSection.RECENTS
                vm.screen = Screen.HOME
                vm.dockPeek = true
                backToDock = true
            }
            vm.screen != Screen.HOME -> vm.screen = Screen.HOME
            // on Home, B from the platforms or the app drawer slides back up to On Deck
            vm.homeSection != com.eclipse.launcher.data.HomeSection.RECENTS -> vm.homeSection = com.eclipse.launcher.data.HomeSection.RECENTS
        }
    }

    val autoHide = vm.flag("dock_autohide")
    val overlayOpen = vm.menuGame != null || vm.artGame != null || vm.platArtScreen != null || vm.dockMenu != null ||
        vm.setupOpen || vm.platReorder || vm.appearancePicker != null || vm.appMenu != null || vm.platformMenu != null || vm.emuMenu != null || vm.sysEdit != null || vm.viewMenu || vm.searchOpen || vm.onDeckRemove != null || vm.hiddenScreen || vm.storeDelete != null || vm.gameDelete != null || vm.colorPicker != null || vm.themeImport != null || vm.smbBrowse != null || vm.packImport != null || vm.rommAsk != null || vm.rommDiscAsk != null || vm.toolsOpen || !vm.splashDone
    // when the last menu closes, put the controller back where it was
    // leaving a page: its right panel opens on its first row again next time
    LaunchedEffect(vm.screen, vm.homeSection, vm.platformSel) { vm.drawerMemory.clear() }
    LaunchedEffect(overlayOpen) {
        if (!overlayOpen) {
            withFrameNanos { }
            GamepadFocus.restore()
        }
    }
    val focusManager = LocalFocusManager.current
    val dockFocus = remember { FocusRequester() }
    var dockHasFocus by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        GamepadFocus.rescue = rescue@{
            GamepadFocus.modal?.let { m -> if (runCatching { m.requestFocus() }.isSuccess) return@rescue }
            val p = GamepadFocus.primary
            val ok = p != null && runCatching { p.requestFocus() }.isSuccess
            if (!ok) runCatching { dockFocus.requestFocus() }
        }
        onDispose { GamepadFocus.rescue = null }
    }
    // L bumper: open the left dock from anywhere (closing any right-hand drawer), or close it again
    val overlayNow by rememberUpdatedState(overlayOpen)
    val dockFocused by rememberUpdatedState(dockHasFocus)
    val dockScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val hook: () -> Unit = {
            if (!overlayNow) {
                if (dockFocused) {
                    vm.dockPeek = false
                    GamepadFocus.rescue?.invoke()
                } else {
                    vm.platDrawer = false; vm.gamesDrawer = false; vm.deckDrawer = false; vm.appsDrawer = false
                    // show it straight away (as a tap on its edge would), then put the controller on its top button
                    // once it's on screen; asking for focus in the same moment can miss while the dock is still hidden
                    vm.dockPeek = true
                    dockScope.launch {
                        repeat(20) {
                            withFrameNanos { }
                            if (runCatching { dockFocus.requestFocus() }.isSuccess && dockFocused) return@launch
                        }
                    }
                }
            }
        }
        GamepadFocus.dockOpener = hook
        onDispose { if (GamepadFocus.dockOpener === hook) GamepadFocus.dockOpener = null }
    }
    // Shown while a dock button has focus (controller) or after a tap/swipe on the edge handle (touch).
    // Right after a screen change the dock can catch focus for a frame or two before the new screen takes it.
    // Don't let that flash the dock open.
    var switching by remember { mutableStateOf(false) }
    // (the same when a menu opens or closes: focus can pass through the dock on its way in or out)
    // (and on the very frame a menu closes, before the effect below has run: [settled] is still the old state then,
    // so the dock can't start sliding out for the one frame where focus passes through it)
    var settled by remember { mutableStateOf(vm.screen to overlayOpen) }
    var dockByIntent by remember { mutableStateOf(false) }
    LaunchedEffect(vm.screen, overlayOpen) {
        switching = true
        repeat(6) { withFrameNanos { } }
        switching = false
        settled = vm.screen to overlayOpen
        // the dock only opens for Left or LB: if focus fell into it on the way (a menu closing, a screen changing),
        // send it back to the screen
        if (backToDock) {
            backToDock = false
            repeat(3) { withFrameNanos { }; if (runCatching { dockFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
        } else if (dockHasFocus && !vm.dockPeek) {
            GamepadFocus.lastKey?.let { GamepadFocus.restoreKey = it }
            withFrameNanos { }; withFrameNanos { }
            if (dockHasFocus) GamepadFocus.primary?.let { runCatching { it.requestFocus() } }
        }
    }
    val changing = switching || settled != (vm.screen to overlayOpen)
    // the dock opens only when the controller was sent there (Left, LB, B from Settings): focus that merely falls
    // into it (a menu closing, a game removed from under it) doesn't open it, and is sent straight back
    val dockWanted = dockHasFocus && (dockByIntent || vm.dockPeek) && !overlayOpen && !(changing && !vm.dockPeek)
    LaunchedEffect(dockHasFocus, dockByIntent) {
        if (dockHasFocus && !dockByIntent && !vm.dockPeek) {
            withFrameNanos { }
            GamepadFocus.lastKey?.let { GamepadFocus.restoreKey = it }
            withFrameNanos { }; withFrameNanos { }
            if (dockHasFocus && !dockByIntent && !vm.dockPeek) GamepadFocus.primary?.let { runCatching { it.requestFocus() } }
        }
    }
    val showDock = !autoHide || dockWanted || vm.dockPeek
    // Compact dock: icons only, it never widens to show the names
    val compact = vm.flag("dock_compact", false)
    val openW = if (compact) DOCK_RAIL else DOCK_W
    val dockX by animateDpAsState(if (showDock) 0.dp else -openW, label = "dockX")
    // With auto-hide, the dock never covers anything: as it slides out, the whole screen slides right with it.
    val push = if (autoHide) openW + dockX else 0.dp
    // Always-on dock: a slim icon rail that widens over the screen (with names) while you're in it.
    // Auto-hide: it slides out already wide.
    val dockOpen = autoHide || dockWanted || vm.dockPeek
    val dockWidth by animateDpAsState(if (dockOpen) openW - 1.dp else DOCK_RAIL - 1.dp, label = "dockW")

    // everything behind an open menu is blurred and dimmed (frosted-glass look)
    // The game menu covers the whole screen once its card has flipped over, so it needs no blur behind it, and
    // animating a full-screen blur during the flip makes it stutter.
    val blurBehind = behindBlur(overlayOpen && vm.menuGame == null)

    Box(Modifier.fillMaxSize().background(if (vm.sysWall) Color.Transparent else C.bg)) {
      Box(Modifier.fillMaxSize().then(blurBehind)) {
        // Settings slides up from below: when it opens, and as a preview while the dock's Settings button has focus on Home.
        // The screen underneath slides up and away with it, like moving down through Home's rows.
        val toSettings = vm.screen == Screen.SETTINGS || (vm.settingsPeek && vm.screen == Screen.HOME && dockHasFocus)
        val sp by androidx.compose.animation.core.animateFloatAsState(
            if (toSettings) 1f else 0f,
            androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "settingsSlide"
        )
        val base = if (vm.screen == Screen.SETTINGS) vm.prevScreen else vm.screen
        val baseShown = sp < 0.999f
        if (base == Screen.HOME && baseShown) {
            Box(Modifier.fillMaxSize().offset(x = push).graphicsLayer { translationY = -sp * size.height * 0.35f }) { HomeBackdrop(vm) }
        }
        if (sp > 0.001f) {
            // Settings floats over a heavily blurred copy of the home backdrop
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = sp }) {
                Box(Modifier.fillMaxSize().softBlur(48.dp)) { HomeBackdrop(vm) }
                Box(Modifier.fillMaxSize().background(Color(0x73000000)))
            }
        }

        // ---- screens ----
        Box(
            Modifier
                .fillMaxSize()
                .offset(x = push)
                .padding(start = if (autoHide) 0.dp else DOCK_RAIL)
                .onKeyEvent { e ->
                    // Left at the edge of a screen brings the hidden dock back.
                    if (autoHide && !overlayOpen && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                        if (!focusManager.moveFocus(FocusDirection.Left)) runCatching { dockFocus.requestFocus() }
                        true
                    } else false
                }
        ) {
            if (baseShown) Box(Modifier.fillMaxSize().graphicsLayer { translationY = -sp * size.height }) {
                when (base) {
                    Screen.HOME -> HomeScreen(vm)
                    Screen.APPS -> AppsScreen(vm)
                    Screen.PLATFORMS -> PlatformsScreen(vm)
                    Screen.SETTINGS -> HomeScreen(vm)
                }
            }
            if (sp > 0.001f) {
                val open = vm.screen == Screen.SETTINGS
                Box(
                    Modifier.fillMaxSize().graphicsLayer { translationY = (1f - sp) * size.height }
                        // while it's only a preview, the controller can't wander into it
                        .then(if (open) Modifier else Modifier.focusProperties { canFocus = false; enter = { FocusRequester.Cancel } })
                        .focusGroup()
                ) { SettingsScreen(vm, pickFolder, interactive = open) }
            }
        }

        // ---- touch: edge handle to open, tap outside to close ----
        if (autoHide && !showDock) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(18.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) { detectTapGestures { vm.dockPeek = true } }
                    .pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag > 8f) vm.dockPeek = true } },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier.padding(start = 5.dp).size(5.dp, 56.dp)
                        .clip(RoundedCornerShape(3.dp)).background(C.x(0x99A0A0A0L))
                )
            }
        }
        if (autoHide && vm.dockPeek) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { vm.dockPeek = false } })
        }

        // ---- the dock itself: frosted glass over whatever is behind it ----
        // rounded on its open side, like the right dock (22 dp)
        val dockShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
        Box(Modifier.fillMaxHeight().width(dockWidth + 1.dp).offset(x = dockX).clip(dockShape)) {
            // exactly the right dock's recipe (OptionsDrawer), low power included: GlassFill already turns itself into
            // a plain solid panel then, so both docks are always the same colour
            GlassFill(Alignment.TopStart, shiftX = -dockX)
            Box(Modifier.fillMaxSize().background(glassTint(ThemeColors.light.value)))
        }
        // the glass's bright edge, following the curve
        Box(
            Modifier.fillMaxHeight().width(dockWidth + 1.dp).offset(x = dockX)
                .border(1.dp, if (ThemeColors.light.value) C.line else Color(0x29FFFFFF), dockShape) // the right dock's edge
        )
        Row(
            Modifier
                .fillMaxHeight()
                .offset(x = dockX)
                .onFocusChanged {
                    if (!it.hasFocus && dockHasFocus) { vm.dockPeek = false; vm.settingsPeek = false } // the controller left the dock: let it hide
                    if (it.hasFocus && !dockHasFocus) dockByIntent = vm.dockPeek || backToDock ||
                        android.os.SystemClock.uptimeMillis() - GamepadFocus.lastLeftMs < 500
                    dockHasFocus = it.hasFocus; vm.dockFocused = it.hasFocus
                }
                // Inside the dock, Left (and Up / Down past the ends) go nowhere. Swallow them: otherwise they count as
                // "unhandled" presses, the controller rescue sends focus back to the screen, and the dock hides.
                .onKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionLeft -> true
                        // Right on the Settings button while its preview is up: open it
                        Key.DirectionRight -> if (vm.settingsPeek && vm.screen == Screen.HOME) {
                            if (e.type == KeyEventType.KeyDown) vm.go(Screen.SETTINGS)
                            true
                        } else false
                        Key.DirectionUp, Key.DirectionDown -> {
                            if (e.type == KeyEventType.KeyDown) {
                                vm.dockNav = true // lets Home slide to the row of the button we land on
                                focusManager.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                                vm.dockNav = false
                            }
                            true
                        }
                        else -> false
                    }
                }
                // however focus arrives (Left from a screen, LB, or the hidden dock popping out), land on the page you're on
                .focusProperties { enter = { dockFocus } }
                .focusGroup()
        ) {
            Glass { Dock(vm, dockFocus, dockWidth, labels = dockWidth > 140.dp) } // the right dock's colours too
        }
      } // end of the blurred layer

        // ---- first-run setup, over everything but the menus it opens itself ----
        if (vm.setupOpen) SetupWizard(vm, pickFolder)

        // ---- menus over everything (controller focus is trapped inside each) ----
        vm.menuGame?.let { GameMenu(vm, it) }
        vm.artGame?.let { ArtScreen(vm, it, pickImage) }
        vm.platArtScreen?.let { PlatformArtScreen(vm, it, pickImage) }
        vm.dockMenu?.let { DockMenu(vm, it) }
        vm.appMenu?.let { AppMenu(vm, it) }
        vm.platformMenu?.let { PlatformMenu(vm, it, pickImage) }
        vm.emuMenu?.let { EmuPicker(vm, it) }
        // (stands aside while the share browser it opened is up, so that one gets the controller)
        vm.sysEdit?.let { if (vm.smbBrowse == null) SystemEditor(vm, it, pickFolder) }
        if (vm.searchOpen) SearchOverlay(vm)
        vm.onDeckRemove?.let { OnDeckRemove(vm, it) }
        vm.rommAsk?.let { RommLaunchSheet(vm, it) }
        vm.rommDiscAsk?.let { (g, play) -> RommDiscSheet(vm, g, play) }
        if (vm.hiddenScreen) HiddenGamesSheet(vm)
        if (vm.toolsOpen) ToolsScreen(vm)
        if (vm.platReorder) PlatformReorder(vm)
        vm.appearancePicker?.let { if (it == "colours") ColoursScreen(vm) else AppearancePicker(vm, it) }
        vm.colorPicker?.let { ColorWheelDialog(vm, it) }
        vm.themeImport?.let { ThemeImportScreen(vm, it) }
        vm.packImport?.let { PackImportScreen(vm, it) }
        vm.storeDelete?.let { DeleteDownload(vm, it) }
        vm.gameDelete?.let { UninstallGame(vm, it) }
        if (vm.smbBrowse != null) SmbBrowser(vm)
        // the tour of Home, once: after setup and the opening animation, when Home is up with the library loaded
        LaunchedEffect(vm.splashDone, vm.setupOpen, vm.screen, vm.loaded) {
            if (!vm.tutorialOpen && !vm.flag("tutorial_done", false) && vm.splashDone && !vm.setupOpen && vm.screen == Screen.HOME && vm.loaded) {
                kotlinx.coroutines.delay(700) // let Home settle first
                if (!vm.setupOpen && vm.screen == Screen.HOME) { vm.tutorialStep = 0; vm.tutorialOpen = true }
            }
        }
        if (vm.tutorialOpen) Tutorial(vm)
        // the opening animation, over everything (once per start)
        if (!vm.splashDone) EclipseSplash(vm)
    }
}
