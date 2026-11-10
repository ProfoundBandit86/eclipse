package com.eclipse.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.*  // the icons are extension properties: they need importing
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.LaunchBox
import com.eclipse.launcher.data.Platforms
import com.eclipse.launcher.data.PlatformDef
import com.eclipse.launcher.media.ScreenshotService

/** Settings tabs: name, icon and the one-line description under the page heading. */
private data class SettingsTab(val name: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val desc: String)
private val TABS = listOf(
    SettingsTab("Theme", androidx.compose.material.icons.Icons.Filled.Palette, "Theme, colours, icons, font and wallpapers."),
    SettingsTab("Views", androidx.compose.material.icons.Icons.Filled.ViewCarousel, "Which ways of showing a games list are offered, and new ones to install."),
    SettingsTab("Library", androidx.compose.material.icons.Icons.Filled.VideoLibrary, "Your game folders, the systems found in them and their emulators."),
    SettingsTab("Tools", androidx.compose.material.icons.Icons.Filled.Handyman, "Compress games (CHD / RVZ / ZIP), make playlists, send games to RomM, shrink artwork."),
    SettingsTab("Scrape", androidx.compose.material.icons.Icons.Filled.ImageSearch, "Artwork, descriptions and details, and where they come from."),
    SettingsTab("General", androidx.compose.material.icons.Icons.Filled.Tune, "Clock, dock, home screen and crash reports."),
    SettingsTab("Connections", androidx.compose.material.icons.Icons.Filled.Cloud, "RomM, artwork scrapers and RetroAchievements."),
    SettingsTab("Dual screen", androidx.compose.material.icons.Icons.Filled.Devices, "What the second screen shows on dual-screen handhelds."),
    SettingsTab("Credits", androidx.compose.material.icons.Icons.Filled.Info, "Who made what Eclipse uses, and under which licence.")
)

/** The tabs' numbers (their place in [TABS]), for opening Settings on one: `vm.settingsTab = SettingsTabs.LIBRARY`. */
object SettingsTabs {
    const val THEME = 0
    const val VIEWS = 1
    const val LIBRARY = 2
    const val TOOLS = 3
    const val SCRAPE = 4
    const val GENERAL = 5
    const val CONNECTIONS = 6
    const val DUAL_SCREEN = 7
    const val CREDITS = 8
}

/** System file pickers that live in the activity (set up in MainActivity). */
object Pickers {
    var theme: () -> Unit = {}
    /** picks an image for your top ("top") or bottom ("bottom") wallpaper */
    var wallpaper: (String) -> Unit = {}
    var font: () -> Unit = {}
    /** picks a short sound file for the move click */
    var sound: () -> Unit = {}
    /** picks a zip for an icon pack ("icons") or a backdrop pack ("backdrops") */
    var pack: (String) -> Unit = {}
    /** picks a view type to install (a .json, or a .zip of them) */
    var view: () -> Unit = {}
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(vm: EclipseVM, pickFolder: () -> Unit, interactive: Boolean = true) = Glass {
    val tabFocus = remember { FocusRequester() }
    if (interactive) FocusOnShow(tabFocus) // (a preview sliding in under the dock doesn't take the controller)
    val tab = vm.settingsTab.coerceIn(0, TABS.lastIndex)
    val panel = RoundedCornerShape(18.dp)
    var tabsFocused by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    // the backdrop shows around and through two floating panels of the right dock's glass
    Box(Modifier.fillMaxSize()) {
        GlassScreenBackdrop(vm)
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- left: the tabs. Once you move right into a page, they fold down to just their icons ----
            val sideW by animateDpAsState(if (tabsFocused || !interactive) 236.dp else 58.dp, label = "settingsSide")
            val wide = sideW > 150.dp
            GlassPanel(Modifier.width(sideW).fillMaxHeight(), RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 8.dp).onFocusChanged { tabsFocused = it.hasFocus }
                // coming back Left from a page: the tab you're on, never the top of the list
                .focusProperties { enter = { tabFocus } }.stayInside()) {
                // search: the first row, its icon lined up with the tabs' icons (folded or open, nothing moves)
                Box(Modifier.fillMaxWidth().height(37.dp).padding(vertical = 2.dp)) {
                    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Search, null, tint = if (query.isNotBlank()) C.accent else C.sub, modifier = Modifier.size(20.dp))
                        if (wide) TextBox(query, Modifier.padding(start = 14.dp).weight(1f), placeholder = "Search settings", focusKey = "set:search", fontSize = 11.sp) { query = it }
                    }
                    // folded: the icon alone takes the controller; the rail opens and the box takes over
                    if (!wide) FocusBox(Modifier.matchParentSize(), shape = RoundedCornerShape(12.dp), onClick = {}, onFocusChange = { f -> if (f) GamepadFocus.restoreKey = "set:search" }) { }
                }
                TABS.forEachIndexed { i, t ->
                    val active = tab == i
                    FocusBox(
                        Modifier.fillMaxWidth().height(37.dp).padding(vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        onClick = { vm.settingsTab = i },
                        // just highlighting a tab opens its page
                        onFocusChange = { f -> if (f && vm.settingsTab != i) vm.settingsTab = i },
                        focusRequester = if (active) tabFocus else null,
                        focusKey = "tab:$i"
                    ) {
                        Row(
                            Modifier.fillMaxSize().background(if (active) C.x(0x17FFFFFFL) else Color.Transparent).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Icon(
                                t.icon, contentDescription = null,
                                tint = if (active) C.accent else C.sub, modifier = Modifier.size(20.dp)
                            )
                            if (wide) Text(
                                t.name, color = if (active) C.ink else C.sub, fontSize = 15.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, softWrap = false,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // starts Eclipse again from scratch: folders, themes, packs, add-ons and art are all read again
                FocusBox(
                    Modifier.fillMaxWidth().height(37.dp).padding(vertical = 2.dp),
                    shape = RoundedCornerShape(12.dp), onClick = { vm.reloadEclipse() }, focusKey = "set:reload"
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Reload Eclipse", tint = C.sub, modifier = Modifier.size(20.dp))
                        if (wide) Text(
                            "Reload Eclipse", color = C.sub, fontSize = 15.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    }
                }
                if (wide) {
                    Text(
                        vm.appVersion().let { if (it.isBlank()) "" else "Version $it" },
                        color = C.x(0x66FFFFFFL), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                    )
                }
            }
            }

            // ---- right: the page ----
            GlassPanel(Modifier.weight(1f).fillMaxHeight(), panel) {
            // back at the top of a page (or close to it): scroll all the way up, so its headings show again
            val pageScroll = rememberScrollState()
            RightStick.Register(pageScroll)
            val pageScope = rememberCoroutineScope()
            LaunchedEffect(tab) { pageScroll.scrollTo(0) }
            val topZone = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
            CompositionLocalProvider(LocalScrollUp provides { edge ->
                pageScope.launch {
                    withFrameNanos { } // after the row's own bring-into-view
                    if (edge || pageScroll.value < topZone) pageScroll.animateScrollTo(0)
                }
            }) {
            Column(Modifier.fillMaxSize().stayInside()) {
                if (query.isNotBlank()) {
                    SettingsSearchResults(query, Modifier.weight(1f)) { i -> vm.settingsTab = i; query = "" }
                } else {
                val crashOpen = tab == SettingsTabs.GENERAL && vm.crashPage
                LaunchedEffect(tab) { if (tab != SettingsTabs.GENERAL) vm.crashPage = false }
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (crashOpen) Text(TABS[tab].name.uppercase() + "  \u203A  ", color = C.sub, fontSize = 12.sp, letterSpacing = 1.6.sp)
                    Text(if (crashOpen) "Crash reports" else TABS[tab].name, color = C.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.x(0x14FFFFFFL)))
                if (crashOpen) CrashReportsPage(vm, Modifier.weight(1f).fillMaxWidth()) else {
                // the highlighted row stays in the middle of the page while there is page left to scroll
                @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenterBringIntoView) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll).padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompositionLocalProvider(LocalBigRows provides true) {
                    when (tab) {
                        SettingsTabs.THEME -> AppearanceTab(vm)
                        SettingsTabs.VIEWS -> ViewsTab(vm)
                        SettingsTabs.LIBRARY -> Library(vm, pickFolder)
                        SettingsTabs.TOOLS -> ToolsTab(vm)
                        SettingsTabs.SCRAPE -> ScrapeTab(vm)
                        SettingsTabs.GENERAL -> General(vm)
                        SettingsTabs.CONNECTIONS -> Connections(vm, showTitle = false)
                        SettingsTabs.DUAL_SCREEN -> DualScreen(vm)
                        else -> Credits(vm) // SettingsTabs.CREDITS
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

/** Every setting's name (and words people might search for), with the tab it's on. */
private val SETTINGS_INDEX: List<Triple<String, Int, String>> = listOf(
    Triple("Crash reports", 5, "crash error log report bug"), Triple("Background colour", 0, "overlay shading backdrop color"),
    Triple("Theme", 0, "import themes"), Triple("Card logos", 0, "system platform card colour logo glass"), Triple("Corners", 0, "rounded corners radius art cards"), Triple("Colours", 0, "accent highlight glass text labels dim color"),
    Triple("Font", 0, "typeface import"), Triple("Text size", 0, "bigger smaller scale"), Triple("Icons", 0, "icon pack platform"),
    Triple("Backdrops", 0, "backdrop pack background"), Triple("Reload images", 0, "refresh eclipse folder"), Triple("Android wallpaper", 0, "live wallpaper system"),
    Triple("Wallpaper (top / bottom screen)", 0, "background picture"),
    Triple("Scan for new games", 2, "rescan library roms"), Triple("Views", 1, "view types slide cover flow grid list install custom turn off"), Triple("Tools", 3, "chd convert m3u playlists multi disc romm upload send bulk"), Triple("ROM folders", 2, "add folder path"),
    Triple("Systems and emulators", 2, "platform emulator core edit"), Triple("Scraping", 4, "artwork box art descriptions scrape"),
    Triple("Installed apps", 2, "emulators android games scan"), Triple("RetroArch cores", 2, "cores"),
    Triple("24-hour clock", 5, "time"), Triple("Auto-hide dock", 5, "left dock"), Triple("Compact dock", 5, "icons only"), Triple("Pages", 5, "left dock order hide move search recent systems apps scopes"),
    Triple("Clock panel", 5, "time wifi corner"),
    Triple("Default launcher", 5, "home app"), Triple("Run setup again", 5, "wizard first run"),
    Triple("Use second screen", 7, "dual bottom display"), Triple("Media controls page", 7, "music playback"), Triple("Screen mirror page", 7, "mirror"), Triple("Transfers page", 7, "romm download upload chd progress"), Triple("Show finished transfers", 7, "romm history done"),
    Triple("Dock on second screen", 7, "bottom dock"), Triple("Where apps open", 7, "which screen"), Triple("Now Playing buttons", 7, "screenshot walkthrough"),
    Triple("Show Now Playing", 7, "bottom screen game running"),
    Triple("LaunchBox", 4, "artwork database scraper"), Triple("Save artwork as WebP", 4, "webp lossless png space compress images"), Triple("Fill missing disc, cartridge and 3D box art", 4, "generate draw media physical cart 3d box missing"),
    Triple("SteamGridDB", 4, "api key art scraper"), Triple("IGDB", 4, "twitch client scraper"), Triple("RetroAchievements", 6, "achievements cheevos"),
    Triple("RomM server", 6, "server sync saves romm"), Triple("RomM library share", 6, "upload smb folder"), Triple("Transfers", 6, "uploads downloads history romm"),
    Triple("Compress games", 3, "iso bin cue compress disc rvz gamecube wii dolphin zip rom cartridge chd"), Triple("Shrink artwork", 3, "webp lossless png space compress images"), Triple("Playlists for multi-disc games", 3, "m3u discs"),
    Triple("Credits", 8, "licences about version"),
    Triple("Permissions", 5, "access allow"), Triple("All-files access", 5, "storage permission"), Triple("Notification access", 5, "permission"),
    Triple("Eclipse helper", 5, "accessibility screenshot permission"), Triple("No battery limits", 5, "battery optimisation permission")
)

/** Settings whose name (or search words) match [query]; picking one opens its tab. */
@Composable
private fun SettingsSearchResults(query: String, modifier: Modifier, open: (Int) -> Unit) {
    val q = query.trim().lowercase()
    val hits = SETTINGS_INDEX.filter { e -> e.first.lowercase().contains(q) || e.third.contains(q) || TABS[e.second].name.lowercase().contains(q) }
    Column(modifier.fillMaxWidth().menuScroll().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DropSection("${hits.size} result${if (hits.size == 1) "" else "s"} for \u201c${query.trim()}\u201d")
        if (hits.isEmpty()) Note("Nothing matches. Try another word.")
        hits.forEach { (name, tab, _) ->
            SetRow(focusKey = "find:$name", onClick = { open(tab) }) {
                RowText(name, null, Modifier.weight(1f))
                Text(TABS[tab].name.uppercase(), color = C.accent, fontSize = 9.sp, letterSpacing = 1.3.sp)
                Text("\u203A", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun General(vm: EclipseVM) {
    TwoColumns({
        DropSection("Clock")
        ToggleRow("24-hour clock", null, vm.flag("h24", false)) { vm.setFlag("h24", it) }
        ToggleRow("Clock panel", "The time and Wi-Fi on a black panel in the top-right corner, so they stand out over artwork.", vm.flag("clock_panel", true)) { vm.setFlag("clock_panel", it) }
        DropSection("Left dock")
        ToggleRow("Auto-hide dock", "Slides away when you move into a screen; press left at the edge to bring it back.", vm.flag("dock_autohide")) { vm.setFlag("dock_autohide", it) }
        ToggleRow("Compact dock", "Icons only, no names.", vm.flag("dock_compact", false)) { vm.setFlag("dock_compact", it) }
        ScopesDrop(vm)
    }, {
        DropSection("Home")
        StatusRow("Default launcher", if (vm.isDefaultHome()) "Eclipse" else "Not Eclipse", if (vm.isDefaultHome()) Dot.OK else Dot.OFF) { vm.openHomeSettings() }
        ActionRow("Run setup again", "Permissions, ROM folder and connections.") { vm.runSetupAgain() }
        ToggleRow("Opening animation", "The eclipse rising when Eclipse starts. Any button skips it.", vm.flag("splash", true)) { vm.setFlag("splash", it) }
        ActionRow("Show the tutorial again", "The short tour of Home, the dock and the settings panel.") { vm.restartTutorial() }
        DropSection("Updates")
        ToggleRow("Check for updates at start", "Looks for a newer Eclipse on GitHub when Eclipse opens, and asks before installing it.", vm.flag("update_check", true)) { vm.setFlag("update_check", it) }
        ActionRow("Check for updates", vm.updateStatus ?: "You have ${vm.appVersion()}") { vm.checkForUpdate(manual = true) }
        DropSection("Crash reports")
        val ctxNow = androidx.compose.ui.platform.LocalContext.current
        val crashes = remember { CrashReports.list(ctxNow) }
        StatusRow("Crash reports", if (crashes.isEmpty()) "None" else "${crashes.size} kept", if (crashes.isEmpty()) Dot.OK else Dot.ERROR) { vm.crashPage = true }
        DropSection("App switcher")
        AppSwitcherRows(vm)
        DropSection("Access")
        PermissionsDrop(vm)
    })
}

/**
 * Settings → General → Left dock → Pages: the main screens in the left dock, top to bottom. Each row: its switch
 * (shown / hidden), and ▲ / ▼ to move it. Settings always stays at the bottom of the dock.
 */
@Composable
private fun ScopesDrop(vm: EclipseVM) {
    var open by remember { mutableStateOf(false) }
    val list = vm.scopes()
    DropRow("Pages", "${list.count { it !in vm.scopeHidden }} of ${list.size} shown", "gen:scopes", open) { open = !open }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            list.forEachIndexed { i, id ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.weight(1f)) { ToggleRow(vm.scopeName(id), null, id !in vm.scopeHidden) { vm.showScope(id, it) } }
                    if (i > 0) Pill("\u25B2", Modifier.width(38.dp)) { vm.moveScope(id, -1) } else Spacer(Modifier.width(38.dp))
                    if (i < list.lastIndex) Pill("\u25BC", Modifier.width(38.dp)) { vm.moveScope(id, 1) } else Spacer(Modifier.width(38.dp))
                }
            }
            Note("Hiding a page only takes it out of the dock: Home's rows are still there.")
        }
    }
}

@Composable
private fun Permissions(vm: EclipseVM, showTitle: Boolean = true) {
    @Suppress("UNUSED_VARIABLE") val tick = vm.permTick // re-check every time you come back from Android settings
    val files = vm.hasAllFiles()
    val notif = vm.notifAccessOn()
    val helper = vm.helperOn()
    val battery = vm.batteryUnrestricted()
    val home = vm.isDefaultHome()
    val done = listOf(files, notif, helper, battery, home).count { it }

    if (showTitle) Text("Permissions", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.ink)
    TwoColumns({
        BigNumber("$done", "of 5 turned on")
        ActionRow(if (done == 5) "Everything is set up" else "Set up everything", if (done == 5) null else "Opens the next one that's off. Turn it on, come back, tap again.") { vm.openNextPermission() }
        DropSection("Permissions")
        PermRow("All-files access", "Find your ROMs; real file paths for emulators.", files) { vm.requestAllFiles() }
        PermRow("Notification access", "Now Playing on the second screen.", notif) { vm.requestNotifAccess() }
        PermRow("Eclipse helper", "Screenshots, and the second-screen dock coming back.", helper) { vm.requestScreenshotAccess() }
        PermRow("No battery limits", "Keeps Eclipse open during long sessions.", battery) { vm.requestBatteryUnrestricted() }
        PermRow("Default home app", "Makes Eclipse your home screen.", home) { vm.openHomeSettings() }
    }, {
        DropSection("Switch greyed out?")
        Note(
            "Android 13+ blocks notification access and accessibility for apps installed outside an app store. " +
                "Open Eclipse's app info, tap the \u22ee menu in the top corner, choose \"Allow restricted settings\", then try again."
        )
        ActionRow("Open Eclipse app info", null) { vm.openAppInfo() }
    })
}

/**
 * Settings → General → App switcher: which button opens it (press it to choose), or off. Needs the Eclipse helper.
 * Hold that button anywhere: the app on screen zooms out among your recent apps; Left / Right choose, Up shows one
 * bigger and Up again closes it, B stays, and letting go switches.
 */
@Composable
private fun AppSwitcherRows(vm: EclipseVM) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableIntStateOf(com.eclipse.launcher.media.AppSwitcher.keyCode(ctx)) }
    var waiting by remember { mutableStateOf(false) }
    val helper = ScreenshotService.ready.value
    DisposableEffect(Unit) { onDispose { if (waiting) { com.eclipse.launcher.media.AppSwitcher.learning = null; ScreenshotService.refreshKeyFilter() } } }
    if (!helper) {
        StatusRow("Eclipse helper", "Turn on", Dot.OFF) { vm.requestScreenshotAccess() }
        Note("The app switcher runs in the Eclipse helper: turn it on first.")
        return
    }
    DropRow("Button", if (waiting) "Press it now\u2026" else com.eclipse.launcher.media.AppSwitcher.keyName(code), "sw:key", opens = true) {
        waiting = true
        com.eclipse.launcher.media.AppSwitcher.learning = { k ->
            // A and the D-pad run the switcher itself: not those. Back (or B) is fine: a quick press still goes back.
            val taken = k in setOf(
                android.view.KeyEvent.KEYCODE_BUTTON_A, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            )
            if (!taken) { com.eclipse.launcher.media.AppSwitcher.setKeyCode(ctx, k); code = k }
            waiting = false
            ScreenshotService.refreshKeyFilter()
        }
        ScreenshotService.refreshKeyFilter()
    }
    if (code != 0 && !waiting) DropRow("Turn the switcher off", "Off", "sw:off", opens = true) {
        com.eclipse.launcher.media.AppSwitcher.setKeyCode(ctx, 0); code = 0
        ScreenshotService.refreshKeyFilter()
    }
    Note(
        if (waiting) "Press the button you want for the app switcher (any but A and the D-pad). Holding it opens the switcher; a quick press of Back, Home or Recents still does its usual job."
        else "Hold the button anywhere: Left / Right choose an app (past the end goes Home), Up lifts it and Up again closes it, Down puts it back, B stays where you are. A (or the button again) switches."
    )
}

/** Settings → General → Permissions: one row with how many are on; A opens the list of them under it. */
@Composable
private fun PermissionsDrop(vm: EclipseVM) {
    @Suppress("UNUSED_VARIABLE") val tick = vm.permTick // re-check every time you come back from Android settings
    val files = vm.hasAllFiles(); val notif = vm.notifAccessOn(); val helper = vm.helperOn()
    val battery = vm.batteryUnrestricted(); val home = vm.isDefaultHome()
    val done = listOf(files, notif, helper, battery, home).count { it }
    var open by remember { mutableStateOf(false) }
    DropRow("Permissions", "$done of 5 on", "gen:perms", open) { open = !open }
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (done < 5) ActionRow("Set up everything", "Opens the next one that's off. Turn it on, come back, tap again.") { vm.openNextPermission() }
            PermRow("All-files access", "Find your ROMs; real file paths for emulators.", files) { vm.requestAllFiles() }
            PermRow("Notification access", "Now Playing on the second screen.", notif) { vm.requestNotifAccess() }
            PermRow("Eclipse helper", "Screenshots, and the second-screen dock coming back.", helper) { vm.requestScreenshotAccess() }
            PermRow("No battery limits", "Keeps Eclipse open during long sessions.", battery) { vm.requestBatteryUnrestricted() }
            PermRow("Default home app", "Makes Eclipse your home screen.", home) { vm.openHomeSettings() }
            Note("Switch greyed out? Android 13+ blocks some access for apps from outside an app store: open Eclipse's app info, \u22ee, Allow restricted settings.")
            ActionRow("Open Eclipse app info", null) { vm.openAppInfo() }
        }
    }
}

/** A permission with its status and a button straight to the Android page that turns it on. */
@Composable
private fun PermRow(title: String, why: String, on: Boolean, open: () -> Unit) {
    SetRow(focusKey = "perm:$title", onClick = open) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Dot.OK.color else Dot.OFF.color))
        RowText(title, why, Modifier.weight(1f).padding(start = 9.dp))
        Text(if (on) "ON" else "TURN ON", color = if (on) C.sub else C.accent, fontSize = 9.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold)
        Text("\u203A", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DualScreen(vm: EclipseVM) {
    // the main switch, across both columns; everything else greys out (and can't be reached) while it's off
    ToggleRow("Use second screen", "Now Playing, media and the dock on the bottom display.", vm.flag("dual")) { vm.setFlag("dual", it) }
    val on = vm.flag("dual")
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).alpha(if (on) 1f else 0.38f)
            .focusProperties { enter = { if (on) FocusRequester.Default else FocusRequester.Cancel } }
            .focusGroup()
    ) {
    TwoColumns({
        DropSection("Bottom screen")
        ToggleRow("Media controls page", "Swipe to playback controls.", vm.flag("media")) { vm.setFlag("media", it) }
        ToggleRow("Recently played page", "A grid of your recent games; tap one to play.", vm.flag("recent_page", false)) { vm.setFlag("recent_page", it) }
        ToggleRow("Screen mirror page", "The top screen, or a zoomed-in part of it. Only there while the mirror is on: start it with Mirror on Now Playing.", vm.flag("mirror_page", true)) { vm.setFlag("mirror_page", it) }
        ToggleRow("Transfers page", "RomM downloads, uploads and CHD conversion; only there while one is running.", vm.flag("tx_page", true)) { vm.setFlag("tx_page", it) }
        if (vm.flag("tx_page", true)) ToggleRow("Go to Transfers when a download starts", "The bottom screen switches to it when a RomM download begins.", vm.flag("tx_follow", true)) { vm.setFlag("tx_follow", it) }
        if (vm.flag("tx_page", true)) ToggleRow("Show finished transfers", "The last few results under the live ones.", vm.flag("tx_done", true)) { vm.setFlag("tx_done", it) }
        ToggleRow("Dock on second screen", "Long-press an app in its drawer to pin it.", vm.flag("dock2")) { vm.setFlag("dock2", it) }
        if (vm.flag("dock2")) {
            // the dock's look and icon size
            var dockOpen by remember { mutableStateOf<String?>(null) }
            val styles = listOf("glass" to "Glass", "solid" to "Solid", "clear" to "Floating icons", "bar" to "Full-width bar")
            DropRow("Dock style", styles.firstOrNull { it.first == vm.dock2Style }?.second ?: "Glass", "ds:dockstyle", dockOpen == "style") { dockOpen = if (dockOpen == "style") null else "style" }
            AnimatedVisibility(dockOpen == "style") {
                DropMenu { styles.forEach { (id, label) -> DropChoice(label, id == vm.dock2Style, null, "ds:dockstyle:$id") { vm.chooseDock2Style(id); dockOpen = null } } }
            }
            val sizes = listOf("small" to "Small", "medium" to "Medium", "large" to "Large")
            DropRow("Dock icons", sizes.firstOrNull { it.first == vm.dock2Size }?.second ?: "Medium", "ds:docksize", dockOpen == "size") { dockOpen = if (dockOpen == "size") null else "size" }
            AnimatedVisibility(dockOpen == "size") {
                DropMenu { sizes.forEach { (id, label) -> DropChoice(label, id == vm.dock2Size, null, "ds:docksize:$id") { vm.chooseDock2Size(id); dockOpen = null } } }
            }
        }
        DropSection("Apps")
        DropRow(
            "Where apps open", if (vm.appScreen.isEmpty()) "Ask" else "${vm.appScreen.size} remembered", "ds:where", opens = true
        ) { vm.resetAppScreens() }
    }, {
        DropSection("Now Playing")
        ToggleRow("Show Now Playing", "Cover, play time and achievements while a game runs.", vm.flag("np_show", true)) { vm.setFlag("np_show", it) }
        ToggleRow("Screenshot button", "Saves to Pictures/Eclipse.", vm.flag("npb_shot")) { vm.setFlag("npb_shot", it) }
        ToggleRow("Walkthrough button", "Hold it to change the search.", vm.flag("npb_walk")) { vm.setFlag("npb_walk", it) }
    })
    // while it's off: taps on the greyed part do nothing
    if (!on) Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
    }
    // which display is which (always reachable: it matters even with the second screen off)
    DropSection("Screens")
    var scrOpen by remember { mutableStateOf<String?>(null) }
    val dctx = androidx.compose.ui.platform.LocalContext.current
    val others = remember(scrOpen) { com.eclipse.launcher.media.Screens.candidates(dctx, android.view.Display.DEFAULT_DISPLAY) }
    TwoColumns({
        // Auto swaps only for a 4:3 handheld wearing a widescreen clip-on screen (its built-in screen is the bottom one)
        val roles = listOf("auto" to "Auto", "standard" to "Android's main display", "swapped" to "The other display")
        DropRow("Main screen", roles.firstOrNull { it.first == vm.screenRoles }?.second ?: "Auto", "ds:roles", scrOpen == "roles") {
            scrOpen = if (scrOpen == "roles") null else "roles"
        }
        AnimatedVisibility(scrOpen == "roles") {
            DropMenu { roles.forEach { (id, label) -> DropChoice(label, id == vm.screenRoles, null, "ds:roles:$id") { vm.chooseScreenRoles(id); scrOpen = null } } }
        }
        // more than one possible second screen (a TV on DisplayPort, say): pick the real one
        if (others.size > 1) {
            val cur = others.firstOrNull { it.name == vm.secondName }
            DropRow("Second screen", cur?.let { com.eclipse.launcher.media.Screens.label(it) } ?: "Automatic", "ds:which", scrOpen == "which") {
                scrOpen = if (scrOpen == "which") null else "which"
            }
            AnimatedVisibility(scrOpen == "which") {
                DropMenu {
                    DropChoice("Automatic", cur == null, null, "ds:which:auto") { vm.chooseSecondDisplay(""); scrOpen = null }
                    others.forEachIndexed { i, d ->
                        DropChoice(com.eclipse.launcher.media.Screens.label(d), d.name == vm.secondName, null, "ds:which:$i") { vm.chooseSecondDisplay(d.name ?: ""); scrOpen = null }
                    }
                }
            }
        }
    }, {
        ToggleRow(
            "Controller on the bottom screen",
            "Your device's switch-screen key or a tap moves it there; B gives it back.",
            vm.bottomFocusOn()
        ) { vm.setBottomFocus(it) }
    })
    // what the bottom screen needs from Android (always reachable)
    DropSection("Needs")
    TwoColumns({
        val helper = ScreenshotService.ready.value
        StatusRow("Eclipse helper", if (helper) "On" else "Turn on", if (helper) Dot.OK else Dot.OFF) { vm.requestScreenshotAccess() }
    }, {
        val notif = vm.notifAccessOn()
        StatusRow("Notification access", if (notif) "On" else "Turn on", if (notif) Dot.OK else Dot.OFF) { vm.requestNotifAccess() }
    })
}

@Composable
/** Everything that talks to an online service: one card per service, opened by clicking it. */
private fun Connections(vm: EclipseVM, showTitle: Boolean = true, withRomm: Boolean = true) {
    LaunchedEffect(Unit) { vm.checkConnections() }
    // one box open per column
    var openL by remember { mutableStateOf<String?>(null) }
    var openR by remember { mutableStateOf<String?>(null) }
    val toggleL = { id: String -> openL = if (openL == id) null else id }
    val toggleR = { id: String -> openR = if (openR == id) null else id }

    if (showTitle) Text("Connections", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = C.ink)

    // ---- RomM: one box across both columns: its status on top, the server and library share side by side, and
    // what's going up and down under them
    if (withRomm) RommPanel(vm, openR, toggleR)

    // ---- RetroAchievements: your progress, in game menus and on the bottom screen
    DropSection("RetroAchievements")
    TwoColumns({
    val (raDot, raText) = statusOf(vm, "ra", "Not set up")
    ServiceCard("RetroAchievements", raText, raDot, openL == "ra", { toggleL("ra") }) {
        FieldGroup {
            FieldRow("Username", vm.key("ra_user")) { vm.setKey("ra_user", it.trim()); vm.checkConnection("ra") }
            FieldRow("Web API key", vm.key("ra_key"), secret = true) { vm.setKey("ra_key", it.trim()); vm.checkConnection("ra") }
        }
        ToggleRow("Show achievements in the game menu", null, vm.flag("ra_on")) { vm.setFlag("ra_on", it) }
        InfoRow("Where to find the key", "retroachievements.org → your profile → Settings → Keys → Web API Key.")
    }
    }, {
        Note("Achievements show on a game's page and, while it runs, on the bottom screen's Now Playing.")
    })
}

/** "Just now", "12 min ago", "3 h ago", "yesterday", else the date. */
private fun whenText(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} h ago"
        d < 2 * 86_400_000 -> "yesterday"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }
}

/**
 * Settings → Connections → RomM, spanning both columns: a header (logo, status, game count, when it last synced, Sync now
 * and Open RomM), then Server | Library share · uploads side by side, then Now | History.
 */
@Composable
private fun RommPanel(vm: EclipseVM, openR: String?, toggleR: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val ready = vm.isConfigured("romm")
    val (dot, status) = if (vm.rommBusy) Dot.BUSY to (vm.rommStatus ?: "Working\u2026") else statusOf(vm, "romm", "Not set up")
    val line = C.x(0x12FFFFFFL)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.x(0x09FFFFFFL)).border(1.dp, C.accent.copy(alpha = 0.35f), shape)
    ) {
        // ---- header: logo, name, status line, then the two buttons
        Row(
            Modifier.fillMaxWidth().background(C.accent.copy(alpha = 0.07f)).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.eclipse.launcher.R.drawable.romm_logo), null, Modifier.size(30.dp)
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("RomM", color = C.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot.color))
                    val games = vm.rommCatalog.size
                    val bits = listOfNotNull(
                        status,
                        if (ready && !vm.rommBusy && games > 0) "%,d games".format(games) else null,
                        if (ready && !vm.rommBusy && vm.rommSyncedAt > 0) "synced " + whenText(vm.rommSyncedAt) else null
                    )
                    Text(bits.joinToString("  \u00b7  "), color = C.sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (ready) {
                Pill(if (vm.rommBusy) "Syncing\u2026" else "Sync now", primary = true) { vm.rommSync() }
                Spacer(Modifier.width(6.dp))
                Pill("Open RomM") {
                    val u = vm.key("romm_url").trim()
                    vm.openUrl(if (u.startsWith("http")) u else "http://$u")
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        // ---- Server | Library share · uploads
        Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            TwoColumns({
                ColumnLabel("Server")
                FieldGroup {
                    FieldRow("Address", vm.key("romm_url"), hint = "192.168.1.20:8080") { vm.setKey("romm_url", it.trim()); vm.checkConnection("romm") }
                    FieldRow("Username", vm.key("romm_user")) { vm.setKey("romm_user", it.trim()); vm.checkConnection("romm") }
                    FieldRow("Password", vm.key("romm_pass"), secret = true) { vm.setKey("romm_pass", it); vm.checkConnection("romm") }
                }
                if (vm.rommInsecure()) Text(
                    "This address uses http, so your RomM password is sent unencrypted. Use an https address if your server has one.",
                    color = androidx.compose.ui.graphics.Color(0xFFE59A6A), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp)
                )
                if (ready) {
                    var launchOpen by remember { mutableStateOf(false) }
                    val launches = listOf("ask" to "Ask each time", "play" to "Install and play", "download" to "Install")
                    DropRow("Opening a game", launches.firstOrNull { it.first == vm.rommLaunch }?.second ?: "Ask each time", "romm:launch", launchOpen) { launchOpen = !launchOpen }
                    AnimatedVisibility(launchOpen) {
                        DropMenu {
                            launches.forEach { (id, label) ->
                                DropChoice(label, id == vm.rommLaunch, null, "romm:launch:$id") { vm.chooseRommLaunch(id); launchOpen = false }
                            }
                        }
                    }
                }
            }, {
                ColumnLabel("Library share \u00b7 uploads")
                RommShareRow(vm)
                if (ready) {
                    ToggleRow("Convert installs to CHD", "A disc game (.cue / .iso) from RomM is converted to a CHD (RVZ for GameCube / Wii) before it shows up or starts. Multi-disc games start after disc 1. Originals are deleted if Settings \u2192 Tools says so.", vm.flag("romm_chd", false)) { vm.setFlag("romm_chd", it) }
                    ToggleRow("Sync saves", "Newer saves come down from RomM before a game starts; changed saves go up after it closes.", vm.flag("romm_saves", false)) { vm.setFlag("romm_saves", it) }
                    if (vm.flag("romm_saves", false)) {
                        FieldRow("Save folders", vm.key("romm_savedirs").ifBlank { "/storage/emulated/0/RetroArch/saves" }) { vm.setKey("romm_savedirs", it) }
                    }
                    ToggleRow("RomM artwork & descriptions", "Fills in missing box art, backdrops and descriptions from RomM when it syncs.", vm.flag("romm_meta")) { vm.setFlag("romm_meta", it) }
                }
            })
        }
        // ---- Now | History, once RomM is set up
        if (ready) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
            Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                TwoColumns({ ColumnLabel("Now"); RommNow(vm) }, { ColumnLabel("History"); RommHistory(vm, openR == "hist") { toggleR("hist") } })
            }
        }
    }
}

/** A column's small grey capitals heading inside the RomM box. */
@Composable
private fun ColumnLabel(text: String) {
    Text(text.uppercase(), color = C.sub, fontSize = 10.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 2.dp, bottom = 1.dp))
}

/**
 * The library share in one row: where uploads go. A opens it: the computer's sign-in (blank: RomM's own) and the
 * folder picker.
 */
@Composable
private fun RommShareRow(vm: EclipseVM) {
    var open by remember { mutableStateOf(false) }
    val where = vm.key("smb_share").takeIf { it.isNotBlank() }?.let { sh -> listOf(sh, vm.key("smb_path")).filter { it.isNotBlank() }.joinToString(" / ") }
    LaunchedEffect(Unit) { if (vm.smbReady() && vm.smbCheck == null) vm.checkSmb() }
    DropRow("Share", where ?: "Set up", "smb:row", open) { open = !open }
    AnimatedVisibility(open) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FieldGroup {
                val rh = vm.rommHost()
                FieldRow("Computer", vm.key("smb_host"), hint = if (rh.isNotEmpty()) "$rh (RomM's)" else "Not set") { vm.setKey("smb_host", it.trim()); vm.checkSmb() }
                FieldRow("Username", vm.key("smb_user")) { vm.setKey("smb_user", it.trim()); vm.checkSmb() }
                FieldRow("Password", vm.key("smb_pass"), secret = true) { vm.setKey("smb_pass", it); vm.checkSmb() }
            }
            vm.smbCheck?.let { Note(it) }
            ActionRow("Choose library folder", "The shared folder RomM mounts as /romm/library. Upload to RomM on a game's page copies the game there, every disc of it.") { vm.openSmbBrowser() }
        }
    }
}

/** What's going up or down right now: compact rows with a bar; A cancels a download (or clears a failed one). */
@Composable
private fun RommNow(vm: EclipseVM) {
    val downs = com.eclipse.launcher.RommDownloads.items.value
    val ups = vm.uploads.toMap()
    if (downs.isEmpty() && ups.isEmpty()) { JobLine("\u2013", C.x(0x26FFFFFFL), "Nothing going", null); return }
    ups.forEach { (id, p) ->
        val g = vm.games.firstOrNull { it.id == id }
        JobLine("\u2191", Color(0xFFD32F2F), g?.name ?: "A game", "${(p * 100).toInt()}%", p)
    }
    downs.forEach { d ->
        val failed = d.state == com.eclipse.launcher.RommDownloads.State.FAILED
        val label = when (d.state) {
            com.eclipse.launcher.RommDownloads.State.DOWNLOADING -> if (d.progress >= 0f) "${(d.progress * 100).toInt()}%" else "Starting"
            com.eclipse.launcher.RommDownloads.State.QUEUED -> "Waiting"
            com.eclipse.launcher.RommDownloads.State.FAILED -> "Failed"
        }
        JobLine(
            if (failed) "!" else "\u2193", if (failed) Color(0xFFFF453A) else Color(0xFF2E7D5B), d.rom.name, label,
            if (d.state == com.eclipse.launcher.RommDownloads.State.DOWNLOADING) d.progress else -1f,
            sub = if (failed) "Failed; A clears it." else "Downloading from RomM; A cancels it."
        ) {
            if (failed) com.eclipse.launcher.RommDownloads.dismiss(d.rom.id) else com.eclipse.launcher.RommDownloads.cancel(d.rom.id)
        }
    }
}

/** The last few finished transfers; A on More shows them all. */
@Composable
private fun RommHistory(vm: EclipseVM, all: Boolean, onToggle: () -> Unit) {
    if (vm.transfers.isEmpty()) { JobLine("\u2013", C.x(0x26FFFFFFL), "None yet", null); return }
    vm.transfers.take(if (all) 40 else 3).forEach { t ->
        val sys = Platforms.byId(t.platform)?.short ?: t.platform
        JobLine(
            if (!t.ok) "\u2715" else if (t.up) "\u2191" else "\u2713",
            if (!t.ok) Color(0xFFFF453A) else if (t.up) Color(0xFFD32F2F) else Color(0xFF2E7D5B),
            t.name + " \u00b7 " + (if (!t.ok) "failed" else if (t.up) "uploaded" else "installed"),
            shortAgo(t.time),
            sub = "$sys \u00b7 ${whenText(t.time)}" + (t.error?.let { " \u00b7 $it" } ?: "")
        )
    }
    if (vm.transfers.size > 3) ActionRow(if (all) "Show less" else "Show all (${vm.transfers.size})", null, onToggle)
    if (all) ActionRow("Clear history", null) { vm.clearTransfers() }
}

/**
 * One transfer: a coloured square with its symbol, the name, a short label on the right, and a thin bar under it while
 * it's going. With [onClick] (or a [sub] for Select's bubble) it's a row you can highlight.
 */
@Composable
private fun JobLine(symbol: String, color: Color, name: String, label: String?, progress: Float = -1f, sub: String? = null, onClick: (() -> Unit)? = null) {
    val body: @Composable RowScope.() -> Unit = {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(color), contentAlignment = Alignment.Center) {
                    Text(symbol, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (sub != null) RowText(name, sub, Modifier.weight(1f).padding(start = 9.dp))
                else Text(name, color = C.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 9.dp))
                if (label != null) Text(label, color = C.sub, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
            if (progress >= 0f) Box(Modifier.padding(start = 29.dp, top = 5.dp)) { Bar(progress) }
        }
    }
    if (onClick != null || sub != null) SetRow(focusKey = "job:$name", onClick = onClick ?: {}, content = body)
    else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, content = body)
}

/** "now", "12m", "3h", "2d". */
private fun shortAgo(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when { d < 60_000 -> "now"; d < 3_600_000 -> "${d / 60_000}m"; d < 86_400_000 -> "${d / 3_600_000}h"; else -> "${d / 86_400_000}d" }
}

/**
 * Settings → RomM → RomM library share → Library folder: the computer's shares, then the folders inside them.
 * A to open one, "Up" to go back a level, "Use this folder" to pick the one you're in. Folders that look like RomM's
 * library (with roms/ inside) are marked.
 */
@Composable
fun SmbBrowser(vm: EclipseVM) {
    val b = vm.smbBrowse ?: return
    val first = remember { FocusRequester() }
    // focus back on the first row whenever the place changes
    FocusOnShow(first, b.share + "/" + b.path + "/" + b.loading)
    val atShares = b.share.isEmpty()
    val place = if (atShares) vm.smbHost() else listOf(b.share, b.path).filter { it.isNotBlank() }.joinToString(" / ")
    val lib = b.listing?.isLibrary == true
    val sys = b.forSystem?.let { Platforms.byId(it)?.name ?: it }
    MenuSheet(
        if (sys != null) "RomM folder for $sys" else "RomM library folder", place,
        when {
            b.loading -> "Looking…"
            b.error != null -> b.error
            sys != null && atShares -> "The folders this computer shares. Open RomM's library, then roms, then $sys's folder."
            sys != null -> "Open $sys's folder, then Use this folder. Its games are uploaded there."
            atShares -> "The folders this computer shares. Open the one that holds RomM's library."
            lib -> "This looks like RomM's library."
            else -> "Open folders until you're in RomM's library (the one with a roms folder inside)."
        },
        onDismiss = { vm.smbBrowse = null }
    ) {
        var firstUsed = false
        fun fr(): FocusRequester? = if (firstUsed) null else { firstUsed = true; first }
        if (!atShares && !b.loading && b.error == null) {
            MenuRow("Use this folder", value = if (sys != null) b.path.substringAfterLast('/').ifEmpty { null } else if (lib) "RomM library" else null, gold = true, fr = fr()) { vm.smbUseFolder() }
        }
        if (!atShares || b.error != null) MenuRow("Up", value = "←", fr = fr()) { vm.smbBrowseUp() }
        if (b.error != null) MenuRow("Try again", fr = fr()) { vm.smbBrowseTo(b.share, b.path) }
        val folders = b.listing?.folders.orEmpty()
        if (!b.loading && b.error == null && folders.isEmpty()) Note(if (atShares) "This computer doesn't share any folders." else "No folders in here.")
        if (folders.isNotEmpty() && !b.loading) {
            Column(Modifier.fillMaxWidth().heightIn(max = 190.dp).menuScroll().stayInside(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                folders.forEach { f ->
                    MenuRow(f.name, value = if (f.library) "RomM?" else "›", fr = fr()) {
                        if (atShares) vm.smbBrowseTo(f.name, "") else vm.smbBrowseTo(b.share, listOf(b.path, f.name).filter { it.isNotBlank() }.joinToString("/"))
                    }
                }
            }
        }
        MenuRow("Cancel", value = "B", fr = fr()) { vm.smbBrowse = null }
    }
}

private enum class Dot(val color: Color) {
    OK(Color(0xFF34C759)),     // connected / ready
    ERROR(Color(0xFFFF453A)),  // something's wrong
    OFF(Color(0xFF8E8E93)),    // not set up
    BUSY(Color(0xFFFFB020))    // checking / downloading
}

/** Dot + line for a service that signs in (sgdb, igdb, ra, romm). */
private fun statusOf(vm: EclipseVM, id: String, notSet: String): Pair<Dot, String> {
    if (!vm.isConfigured(id)) return Dot.OFF to notSet
    val r = vm.connCheck[id] ?: return Dot.BUSY to "Checking…"
    return (if (r.first) Dot.OK else Dot.ERROR) to r.second
}

/**
 * One service as a box that opens and closes: its name, a status line, a coloured dot and an arrow; A (or a tap) on
 * the header opens it to show its settings underneath, in the bigger row text ([LocalBigRows]). With no [onToggle]
 * it's always open (the setup wizard).
 */
@Composable
private fun ServiceCard(title: String, status: String, dot: Dot, expanded: Boolean, onToggle: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val open = expanded || onToggle == null
    val shape = RoundedCornerShape(12.dp)
    val light = ThemeColors.light.value
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (light) Color.White.copy(alpha = 0.7f) else C.x(if (open) 0x0FFFFFFFL else 0x0BFFFFFFL))
            .border(1.dp, if (open && onToggle != null) C.accent.copy(alpha = 0.35f) else C.x(0x14FFFFFFL), shape)
    ) {
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                Text(status, color = if (dot == Dot.ERROR) dot.color else C.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                Box(Modifier.padding(start = 8.dp).size(8.dp).clip(CircleShape).background(dot.color))
                if (onToggle != null) Text(if (open) "\u25BE" else "\u25B8", color = C.accent, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (onToggle != null) FocusBox(Modifier.fillMaxWidth(), shape = shape, onClick = onToggle, focusKey = "card:$title") { header() }
        else header()
        AnimatedVisibility(open) {
            CompositionLocalProvider(LocalBigRows provides true) {
                Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
            }
        }
    }
}

@Composable
private fun Library(vm: EclipseVM, pickFolder: () -> Unit) {
    val counts = remember(vm.games) { vm.games.groupingBy { it.platform }.eachCount() }
    val romCount = vm.games.count { it.platform != "android" }
    TwoColumns({
        BigNumber(romCount.toString(), (if (romCount == 1) "game found" else "games found") + if (vm.scanning) " \u00b7 scanning\u2026" else "")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Scan new", Modifier.weight(1f)) { vm.rescan(full = false) }
            Pill("Rescan all", Modifier.weight(1f)) { vm.rescan(full = true) }
        }
        if (!vm.hasAllFiles()) StatusRow("All-files access", "Turn on", Dot.ERROR) { vm.requestAllFiles() }
        LibBox("Folders") {
            val folders = vm.romFolders()
            if (folders.isEmpty()) Note("No folder yet. Add the one that holds your system folders (snes, gba, psx\u2026).")
            folders.forEach { f -> FolderLine(f, if (f == vm.romPath && folders.size > 1) "main" else null) { vm.removeFolder(f) } }
            ActionRow("Add folder", null) { vm.folderTarget = null; pickFolder() }
        }
        // the systems: one drop-down row that opens to the list
        LibBox("Systems") {
            val list = Platforms.all.filter {
                it.id != "android" && it.id != Platforms.ALL && it.id != Platforms.FAVORITES &&
                    ((counts[it.id] ?: 0) > 0 || vm.foldersFor(it.id).isNotEmpty())
            }
            var sysOpen by remember { mutableStateOf(false) }
            if (list.isEmpty()) Note("Systems show up here once games are found.")
            else {
                DropRow("Systems", "${list.size} found", "lib:systems", sysOpen) { sysOpen = !sysOpen }
                AnimatedVisibility(sysOpen) {
                    DropMenu { list.forEach { p -> PlatformLine(vm, p, counts[p.id] ?: 0) } }
                }
            }
        }
        DropSection("Hidden games")
        ActionRow("Hidden games", if (vm.hiddenGames.isEmpty()) "None. Hide a game from its page (Hide game)." else "${vm.hiddenGames.size} hidden \u00b7 open to unhide") { vm.hiddenScreen = true }
        DropSection("Built in")
    }, {
        DropSection("Installed apps")
        InfoRow("Emulators found", vm.detectedEmulators().joinToString(", ").ifBlank { "None found" })
        val maybe = vm.possibleEmulators()
        if (maybe.isNotEmpty()) InfoRow("Not recognised", maybe.joinToString(", "))
        InfoRow("Android games", vm.androidGameCount().toString())
        ActionRow("Scan installed apps", "Finds emulators and Android games.") { vm.scanInstalledApps(manual = true) }
        ToggleRow("Scan automatically", "Whenever you come back to Eclipse.", vm.flag("auto_scan", true)) { vm.setFlag("auto_scan", it) }
        if (Platforms.retroArch.any { vm.installed(it) }) {
            val f = vm.raCores
            SlimRow(
                "RetroArch cores",
                when {
                    f == null -> "Checking\u2026"
                    f.cores.isEmpty() -> "None seen"
                    else -> "${f.cores.size} found"
                },
                C.accent
            ) { vm.scanRaCores(force = true) }
            if (f != null && f.cores.isEmpty()) Note(
                "Android keeps RetroArch's core folder private. Once you've played something in RetroArch, " +
                    "or moved its Cores directory to shared storage, Eclipse can see them."
            )
        }
    })
}

/**
 * Settings → Tools: one row per tool, with what's waiting; each opens the Tools screen on its tab (L1 / R1 move between
 * them there).
 */
@Composable
private fun ToolsTab(vm: EclipseVM) {
    LaunchedEffect(Unit) {
        if (vm.chdItems == null) vm.scanForChd()
        if (vm.m3uSets == null) vm.scanM3u()
    }
    fun open(tab: Int) { vm.toolsTab = tab; vm.toolsOpen = true }
    TwoColumns({
        DropSection("Compress games")
        val chd = vm.chdItems?.count { !it.done }
        ActionRow(
            "Compress games",
            when {
                vm.chdJob != null -> "Converting ${vm.chdJob!!.index + 1} of ${vm.chdJob!!.total}\u2026"
                chd == null -> "Looking for games to compress\u2026"
                chd == 0 -> "Everything is already compressed."
                else -> "$chd game${if (chd == 1) "" else "s"} not compressed yet. Smaller files, same games: discs to CHD (GameCube / Wii to RVZ), cartridges to ZIP."
            }
        ) { open(0) }
        DropSection("Multi-disc games")
        val m3u = vm.m3uSets?.count { !it.upToDate }
        ActionRow(
            "Make playlists",
            when {
                m3u == null -> "Looking for multi-disc games\u2026"
                m3u == 0 -> "Every multi-disc game has its playlist."
                else -> "$m3u game${if (m3u == 1) "" else "s"} without an up-to-date .m3u. Each shows once, discs swap in the emulator."
            }
        ) { open(1) }
        // new discs dropped into the folders you pick become CHDs by themselves
        DropSection("Automatic")
        LaunchedEffect(Unit) { vm.loadChdFolders() }
        ToggleRow("Convert new discs automatically", "New .cue / .iso discs in the folders you watch become CHDs by themselves.", vm.chdAuto) { vm.chooseChdAuto(it) }
        if (vm.chdAuto) {
            var watchOpen by remember { mutableStateOf(false) }
            val folders = vm.chdFolders.orEmpty()
            val watched = folders.filter { it.second in vm.chdAutoDirs }.map { it.first }
            DropRow("Folders to watch", if (watched.isEmpty()) "None" else watched.take(2).joinToString(", ") + if (watched.size > 2) " +${watched.size - 2}" else "", "chd:watch", watchOpen) { watchOpen = !watchOpen }
            AnimatedVisibility(watchOpen) {
                DropMenu {
                    if (folders.isEmpty()) DropChoice("No disc-system folders yet", false, null, "chd:watch:none", radio = false) { }
                    folders.forEach { (sysName, path) ->
                        val on = path in vm.chdAutoDirs
                        DropChoice(sysName, on, path.substringAfterLast('/'), "chd:watch:$path", radio = false, checked = on) { vm.chooseChdAutoDir(path, !on) }
                    }
                }
            }
        }
        ToggleRow("Delete the originals", "Once each CHD has been checked.", vm.chdDelete) { vm.chooseChdDelete(it) }
    }, {
        DropSection("RomM")
        ActionRow(
            "Send games to RomM",
            when {
                vm.upQueue != null -> "Sending ${vm.upQueue!!.index + 1} of ${vm.upQueue!!.ids.size}\u2026"
                !vm.isConfigured("romm") -> "Connect RomM first (Connections)."
                !vm.smbReady() -> "Set up RomM's library share first (Connections)."
                else -> "Games on this device that RomM hasn't got, copied into its library."
            }
        ) { open(2) }
        // Eclipse's own PNG artwork re-saved as lossless WebP, each checked pixel by pixel; kept only when smaller
        DropSection("Artwork")
        // missing disc / cartridge and 3D box pictures, drawn from the box art (data/ArtMaker.kt)
        val fj = vm.fillJob
        if (fj != null) {
            BigNumber("${minOf(fj.index + 1, fj.total)}", "of ${fj.total} games \u00b7 drawing")
            Bar(if (fj.total == 0) 0f else fj.index / fj.total.toFloat())
            Note("${fj.made} picture${if (fj.made == 1) "" else "s"} drawn so far" + if (fj.name.isNotEmpty()) " \u00b7 now: ${fj.name}" else "")
            ActionRow("Stop drawing", "Pictures already drawn stay.") { vm.cancelFillArt() }
        } else {
            val n = remember(vm.games, vm.art) { vm.fillCandidates() }
            ActionRow(
                "Fill missing disc, cartridge and 3D box art",
                if (n == 0) "Every game with box art has its disc / cartridge and 3D box."
                else "$n game${if (n == 1) "" else "s"} with box art but no disc / cartridge or 3D box. Drawn from the cover, in your library's style; real art from a source replaces them later."
            ) { vm.startFillArt() }
        }
        LaunchedEffect(Unit) { if (vm.webpJob == null) vm.scanWebp() }
        val wj = vm.webpJob
        val ws = vm.webpScan
        val supported = com.eclipse.launcher.data.WebpShrink.supported
        if (wj != null) {
            // running: a big count, a bar, and what's happened so far
            BigNumber("${minOf(wj.index + 1, wj.total)}", "of ${wj.total} pictures \u00b7 shrinking")
            Bar(if (wj.total == 0) 0f else wj.index / wj.total.toFloat())
            Note("${wj.shrunk} shrunk \u00b7 ${wj.kept} kept as they were" + (if (wj.failed > 0) " \u00b7 ${wj.failed} couldn't be read" else "") + " \u00b7 saved ${vm.fmtBytes(wj.saved)}")
            if (wj.name.isNotEmpty()) Note("Now: ${wj.name}")
            ActionRow("Stop", "Pictures already done stay done.") { vm.cancelWebp() }
        } else {
            InfoRow("Artwork folder", when {
                ws == null -> "Looking\u2026"
                else -> "${ws.allFiles} pictures \u00b7 ${vm.fmtBytes(ws.allBytes)}" + if (ws.webp > 0) " \u00b7 ${ws.webp} WebP" else ""
            })
            ActionRow(
                "Shrink artwork",
                when {
                    !supported -> "Needs Android 10 or newer."
                    ws == null -> "Looking at your artwork\u2026"
                    ws.files.isEmpty() -> "Nothing left to shrink: only PNGs can get smaller this way, and every one has been tried."
                    else -> "${ws.files.size} PNG picture${if (ws.files.size == 1) "" else "s"} (${vm.fmtBytes(ws.bytes)}) to try. Re-saved as lossless WebP, pixel for pixel the same; kept only if smaller."
                }
            ) { if (supported && ws != null && ws.files.isNotEmpty()) vm.startWebp() else vm.scanWebp() }
            vm.webpLast?.let { Note(it) }
            if (vm.webpFilesTotal > 0) Note("Saved so far: ${vm.fmtBytes(vm.webpSavedTotal)} on ${vm.webpFilesTotal} pictures.")
        }
        Note("Tick games with A, all or none with X, start with Y. Big jobs carry on in the background if you leave.")
    })
}

/**
 * Settings → Views: a switch for every view type (the built-in ones and yours), and installing new ones. A view that's
 * off leaves every system's View list; a system that was using it shows the first view that's still on.
 */
@Composable
private fun ViewsTab(vm: EclipseVM) {
    val custom = rememberCustomViews()
    val all = vm.builtinViews.map { it.first } + custom.map { "view:" + it.id }
    val about = mapOf(
        "carousel" to "Big art up top, a row of covers. Games, Recently Played, Systems.",
        "coverflow" to "Covers turning in 3D, the logo and details underneath. Games, Recently Played, Systems.",
        "detailflow" to "The game's details and buttons on the left, covers flowing on the right. Games, Recently Played.",
        "grid" to "Every cover at once. Games, Systems.",
        "list" to "Names on the left, the highlighted game on the right. Games."
    )
    TwoColumns({
        DropSection("Built in")
        vm.builtinViews.forEach { (id, name) -> ToggleRow(name, about[id], vm.viewOn(id)) { vm.setViewOn(id, it, all) } }
        Note("A view that's off leaves the View list everywhere (press R on Recently Played, Systems or a system's games). A screen that was using it shows the first one still on.")
    }, {
        DropSection("Installed")
        if (custom.isEmpty()) Note("None yet. Install one below, or make your own: a view type is one small .json file (see VIEWS.md).")
        custom.forEach { spec ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.weight(1f)) {
                    val where = com.eclipse.launcher.data.Views.SCREENS.filter { spec.on(it.first) }.joinToString(" \u00b7 ") { it.second }
                    val file = spec.file.let { if (it.name.equals("view.json", true)) (it.parentFile?.name ?: it.name) + "/view.json" else it.name }
                    ToggleRow(spec.name, "$where \u00b7 $file", vm.viewOn("view:" + spec.id)) {
                        vm.setViewOn("view:" + spec.id, it, all)
                    }
                }
                RoundBtn("\u2212", "view:rm:${spec.id}") { vm.removeView(spec) }
            }
        }
        com.eclipse.launcher.data.Views.errors.forEach { (file, err) -> Note("$file couldn't be read: $err") }
        ActionRow("Install a view type", "A view.json, or a .zip with one or more. Each is checked before it's added.") { Pickers.view() }
        Note("Installed views live in Eclipse/views. Edit a file there and Eclipse picks the change up within two seconds.")
    })
}

/** A titled box in the Library tab. */
@Composable
private fun LibBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DropSection(title)
        content()
    }
}

/** A folder path with a small "−" to take it off the list. */
@Composable
private fun FolderLine(path: String, tag: String?, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().setRowLook(RoundedCornerShape(9.dp)).heightIn(min = 34.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(path.removePrefix("/storage/emulated/0"), color = C.ink, fontSize = if (LocalBigRows.current) 14.sp else 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (tag != null) Text(tag.uppercase(), color = C.accent, fontSize = if (LocalBigRows.current) 11.sp else 8.5.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(horizontal = 8.dp))
        if (onRemove != null) RoundBtn("\u2212", "folder:$path", onRemove)
    }
}

/** A small round button (one symbol). */
@Composable
private fun RoundBtn(symbol: String, key: String, onClick: () -> Unit) {
    FocusBox(Modifier.size(28.dp), shape = CircleShape, onClick = onClick, focusKey = key) {
        Box(Modifier.fillMaxSize().background(C.x(0x14FFFFFFL)).border(1.dp, C.x(0x14FFFFFFL), CircleShape)) {
            CenteredGlyph(symbol, C.ink, 13.sp, Modifier.fillMaxSize())
        }
    }
}

/** One system: name, game count, then rescan + scrape and edit. */
@Composable
private fun PlatformLine(vm: EclipseVM, p: PlatformDef, count: Int) {
    Row(
        Modifier.fillMaxWidth().setRowLook(RoundedCornerShape(9.dp)).heightIn(min = 38.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (LocalBigRows.current) Text(p.name, color = C.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Text(p.name.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.4.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val busy = vm.scanningSys == p.id
        Text(if (busy) "SCANNING\u2026" else "$count", color = C.accent, fontSize = if (LocalBigRows.current) 12.sp else 9.5.sp, letterSpacing = 1.2.sp, modifier = Modifier.padding(horizontal = 8.dp))
        RoundBtn("\u21BB", "sysscan:${p.id}") { vm.rescanSystem(p.id) }
        Spacer(Modifier.width(4.dp))
        RoundBtn("\u270E", "sysedit:${p.id}") { vm.sysEdit = p.id }
    }
}

/** Edit system: which emulator runs it, and the folders its games come from. */
@Composable
fun SystemEditor(vm: EclipseVM, platformId: String, pickFolder: () -> Unit) {
    val p = Platforms.byId(platformId) ?: run { vm.sysEdit = null; return }
    val installed = vm.installedFor(platformId)
    val curKey = vm.emuPick[platformId]
    val cur = installed.firstOrNull { it.spec.key == curKey }
    val auto = installed.firstOrNull { it.detected != false }
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    var open by remember { mutableStateOf(false) }
    // picking an emulator closes the list: the row that had the controller is gone, so hand it back to the
    // Emulator button (or focus falls out of the card onto the Library tab behind it)
    var used by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) used = true
        else if (used) { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    val count = vm.games.count { it.platform == platformId }
    ModalCard(onDismiss = { vm.sysEdit = null }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = C.ink, modifier = Modifier.weight(1f, fill = false))
            Text("  $count ${if (count == 1) "game" else "games"}", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Column(
            Modifier.heightIn(max = 420.dp).menuScroll(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // the system's name, as shown everywhere in Eclipse (empty = its usual name)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Name", color = C.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                TextBox(p.name, Modifier.weight(1f), placeholder = Platforms.originalName(platformId) ?: p.name, focusKey = "sys:name:$platformId") { v ->
                    vm.renameSystem(platformId, v)
                }
            }
            if (vm.sysNames[platformId] != null) Text(
                "Usually “${Platforms.originalName(platformId)}”. Clear the name to go back to it.",
                color = C.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)
            )
            // emulator: a button that drops down the list
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Emulator", color = C.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                val label = cur?.label ?: auto?.let { "Auto (${it.label})" } ?: "Auto (none installed)"
                Pill("$label  ${if (open) "▴" else "▾"}", Modifier.weight(1f), primary = true, focusRequester = first) { open = !open }
            }
            AnimatedVisibility(visible = open) {
                Column(
                    Modifier.fillMaxWidth().keepInView(open).clip(RoundedCornerShape(14.dp)).background(C.card).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EmuOption("Auto" + (auto?.let { " (${it.label})" } ?: ""), null, curKey == null) { vm.setEmu(platformId, null); open = false }
                    // installed first: what RetroArch is known to have, then cores it might not have
                    installed.sortedBy { if (it.detected == false) 1 else 0 }.forEach { c ->
                        EmuOption(c.label, if (c.detected == false) "not seen in RetroArch" else null, c.spec.key == curKey) {
                            vm.setEmu(platformId, c.spec.key); open = false
                        }
                    }
                    // the rest are supported but not installed: shown greyed out, can't be picked
                    p.emus.filter { s -> installed.none { it.spec.key == s.key } }.forEach { s ->
                        val name = if (s.core != null) "RetroArch · ${s.name}" else s.name
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = C.sub.copy(alpha = 0.5f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("not installed", color = C.sub.copy(alpha = 0.5f), fontSize = 12.sp)
                        }
                    }
                }
            }

            // folders
            LibBox("Folders") {
                val found = vm.detectedFoldersFor(platformId)
                val added = vm.foldersFor(platformId)
                if (found.isEmpty() && added.isEmpty()) Text("None yet", color = C.sub, fontSize = 13.sp)
                found.forEach { FolderLine(it, "found", null) }
                added.forEach { f -> FolderLine(f, null) { vm.removeSysFolder(platformId, f) } }
            }
            Pill("+  Add folder for ${p.name}", Modifier.fillMaxWidth()) { vm.folderTarget = platformId; pickFolder() }
            Text(
                "Everything in a folder added here counts as ${p.name}, whatever it's called.",
                color = C.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp)
            )

            // Vita: Eclipse makes the .psvita shortcuts from the games installed in Vita3K
            if (platformId == "psvita") {
                LibBox("Vita3K") {
                    DropRow("Vita3K's folder", vm.vita3kDir.ifBlank { "Found by itself" }, "sys:vita3k", opens = true) { vm.folderTarget = "@vita3k"; pickFolder() }
                    if (vm.vita3kDir.isNotBlank()) DropRow("Back to automatic", "Look in the usual places", "sys:vita3k:auto", opens = true) { vm.chooseVita3kDir(null) }
                    Note(vm.vitaNote ?: "Games installed in Vita3K show up here by themselves on the next scan.")
                }
            }

            // RomM: which of the library's platform folders this system's games are uploaded to (picked on the share)
            if (vm.isConfigured("romm")) {
                val pick = vm.rommFolderPick[platformId]
                val auto = vm.rommFolders[platformId] ?: platformId
                LibBox("Linked RomM folder") {
                    DropRow("Uploads go to", pick ?: "Auto · $auto", "sys:rommfolder", opens = true) { vm.openSmbBrowserFor(platformId) }
                    if (pick != null) DropRow("Back to automatic", auto, "sys:rommfolder:auto", opens = true) { vm.setRommFolder(platformId, null) }
                    Note("Opens RomM's library share: go into ${p.name}'s folder and choose Use this folder. Automatic uses the folder RomM itself has for ${p.name}.")
                }
            }
            Pill("Done", Modifier.fillMaxWidth()) { vm.sysEdit = null }
        }
    }
}

@Composable
private fun EmuOption(label: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    FocusBox(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().background(if (selected) C.x(0x24FFFFFFL) else Color.Transparent).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (selected) "✓" else "", color = C.accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
            Text(label, color = C.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = C.sub, fontSize = 12.sp)
        }
    }
}

/** Pick which installed emulator runs a system (the game page's Emulator row), in the right dock's look. */
@Composable
fun EmuPicker(vm: EclipseVM, platformId: String) {
    val p = Platforms.byId(platformId)
    val installed = vm.installedFor(platformId)
    val curKey = vm.emuPick[platformId]
    val first = remember { FocusRequester() }
    FocusOnShow(first)
    val auto = installed.firstOrNull { it.detected != false }
    MenuSheet("Emulator", p?.name ?: platformId, "Which emulator opens this system's games.", onDismiss = { vm.emuMenu = null }) {
        Column(Modifier.heightIn(max = 250.dp).menuScroll(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MenuRow("Auto", value = auto?.label ?: "none found", selected = curKey == null, fr = first) { vm.setEmu(platformId, null); vm.emuMenu = null }
            installed.forEachIndexed { i, c ->
                // cores Eclipse couldn't find get their own heading, under everything that will work
                if (c.detected == false && (i == 0 || installed[i - 1].detected != false)) {
                    DropSection("Not seen in RetroArch")
                    Note("RetroArch can hang for a long time if the core is missing.")
                }
                MenuRow(c.label, value = if (c.detected == true) "in RetroArch" else null, selected = c.spec.key == curKey) {
                    vm.setEmu(platformId, c.spec.key); vm.emuMenu = null
                }
            }
            if (p != null) {
                val missing = p.emus.filter { s -> installed.none { it.spec.key == s.key } }
                if (missing.isNotEmpty()) {
                    val raMissing = missing.count { it.core != null }
                    val names = missing.filter { it.core == null }.map { it.name }.distinct() +
                        if (raMissing > 0) listOf("RetroArch ($raMissing cores)") else emptyList()
                    DropSection("Also supported, not installed")
                    Note(names.joinToString(", "))
                }
            }
        }
    }
}

/** Settings → Scrape: what is fetched for your games, and the sources it comes from. */
@Composable
private fun ScrapeTab(vm: EclipseVM) {
    var openL by remember { mutableStateOf<String?>(null) }
    var openR by remember { mutableStateOf<String?>(null) }
    val toggleL = { id: String -> openL = if (openL == id) null else id }
    val toggleR = { id: String -> openR = if (openR == id) null else id }
    LaunchedEffect(Unit) { vm.checkConnections() }
    ScrapeSection(vm)
    // ---- scrapers: where artwork and descriptions come from
    DropSection("Sources")
    TwoColumns({

    // LaunchBox
    val lb = vm.lbStatus
    val (lbDot, lbText) = when {
        lb != null -> Dot.BUSY to lb
        !LaunchBox.loaded -> Dot.OFF to "Not downloaded"
        !vm.flag("p_lb") -> Dot.OFF to "Turned off"
        else -> Dot.OK to "Ready · ${LaunchBox.gameCount} games"
    }
    // "checked today" / "checked 12 days ago": when we last asked LaunchBox for a newer file
    val lbChecked = LaunchBox.checkedAt.takeIf { it > 0 }?.let { at ->
        when (val d = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()) { 0 -> "Checked today"; 1 -> "Checked yesterday"; else -> "Checked $d days ago" }
    }
    ServiceCard("LaunchBox", lbText, lbDot, openL == "lb", { toggleL("lb") }) {
        if (LaunchBox.loaded) ToggleRow("Use LaunchBox", null, vm.flag("p_lb")) { vm.setFlag("p_lb", it) }
        if (lb != null) {
            Bar(vm.lbProgress)
            ActionRow("Cancel", null) { vm.cancelLaunchBox() }
        } else {
            ActionRow(
                if (LaunchBox.loaded) "Check for newer LaunchBox data" else "Download LaunchBox data",
                if (LaunchBox.loaded && vm.lbKeptBytes() > 0)
                    listOfNotNull(lbChecked, "Kept on the device (${vm.fmtBytes(vm.lbKeptBytes())}): new games are looked up without downloading. Checks for a newer copy once a month on Wi-Fi.").joinToString(". ")
                else "Downloaded once and kept on the device (use Wi-Fi). New games are then looked up without downloading again."
            ) { vm.updateLaunchBox() }
        }
    }

    // (the free sources, libretro thumbnails, ArcadeDB and GameTDB, need nothing set up: they're always on, not listed)

    // SteamGridDB
    val (sgDot, sgText) = statusOf(vm, "sgdb", "Not set up")
    ServiceCard("SteamGridDB", sgText, sgDot, openL == "sgdb", { toggleL("sgdb") }) {
        FieldRow("API key", vm.key("sgdb"), secret = true) { vm.setKey("sgdb", it.trim()); vm.checkConnection("sgdb") }
        if (vm.isConfigured("sgdb")) ToggleRow("Use SteamGridDB", null, vm.flag("p_sgdb")) { vm.setFlag("p_sgdb", it) }
        InfoRow("Where to get a key", "steamgriddb.com → sign in → Preferences → API.")
    }

    }, {
    // IGDB
    val (igDot, igText) = statusOf(vm, "igdb", "Not set up")
    ServiceCard("IGDB", igText, igDot, openR == "igdb", { toggleR("igdb") }) {
        FieldGroup {
            FieldRow("Client ID", vm.key("igdb_id")) { vm.setKey("igdb_id", it.trim()); vm.checkConnection("igdb") }
            FieldRow("Client secret", vm.key("igdb_secret"), secret = true) { vm.setKey("igdb_secret", it.trim()); vm.checkConnection("igdb") }
        }
        if (vm.isConfigured("igdb")) ToggleRow("Use IGDB", null, vm.flag("p_igdb")) { vm.setFlag("p_igdb", it) }
        InfoRow("Where to get them", "dev.twitch.tv → Your Console → Register Your Application (any name, redirect http://localhost) → copy the Client ID and make a secret.")
    }
    })

}

@Composable
private fun ScrapeSection(vm: EclipseVM) {
    DropSection("Scraping")
    val s = vm.scrape
    if (s != null) {
        BigNumber("${s.done}", "of ${s.total} \u00b7 scraping")
        Bar(if (s.total == 0) 0f else s.done / s.total.toFloat())
        Note(s.current)
        Pill("Cancel", Modifier.fillMaxWidth()) { vm.cancelScrape() }
    } else {
        val pool = vm.games.filter { it.platform != "android" }
        val withArt = pool.count { vm.artOf(it, com.eclipse.launcher.data.ArtKind.BOX) != null }
        BigNumber("$withArt", "of ${pool.size} have artwork")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("Scrape missing", Modifier.weight(1f)) { vm.scrapeAll(false) }
            Pill("Re-scrape all", Modifier.weight(1f)) { vm.scrapeAll(true) }
        }
    }
    ToggleRow("Scrape new games automatically", "As soon as they're added to a system folder.", vm.flag("scrape_new", false)) { vm.setFlag("scrape_new", it) }
    // off by default: new PNG artwork is re-saved as lossless WebP (same pixels, smaller), JPEGs are left as they are
    ToggleRow(
        "Fill missing disc, cartridge and 3D box art",
        "Drawn from the box art when no source has one, in the style of your library. A real one replaces it when found. For games already scraped: Settings \u2192 Tools.",
        vm.flag("fill_art", false)
    ) { vm.setFlag("fill_art", it) }
    ToggleRow("Save artwork as WebP", "New PNG pictures are re-saved as lossless WebP when that's smaller. Pixel for pixel the same. Needs Android 10+.", vm.flag("webp_new", false)) { vm.setFlag("webp_new", it) }
    // what to scrape: one drop-down row; its list ticks each kind on or off
    val kinds = SCRAPE_KINDS
    var kindsOpen by remember { mutableStateOf(false) }
    val on = kinds.count { it.first in vm.scrapeKinds }
    DropRow("What to scrape", if (on == kinds.size) "Everything" else "$on of ${kinds.size}", "lib:scrapekinds", kindsOpen) { kindsOpen = !kindsOpen }
    AnimatedVisibility(kindsOpen) {
        DropMenu {
            kinds.forEach { (k, label) ->
                DropChoice(label, k in vm.scrapeKinds, null, "scrapekind:$k", checked = k in vm.scrapeKinds) { vm.chooseScrapeKind(k, k !in vm.scrapeKinds) }
            }
        }
    }
    Note("Your own uploaded art is always kept.")
}

/** What scraping can fetch (store key → name): Settings → Scrape → What to scrape, and the setup wizard's Artwork page. */
val SCRAPE_KINDS = listOf(
    "box" to "Box art", "poster" to "Poster", "bg" to "Backdrop", "logo" to "Logo", "desc" to "Description",
    "media" to "Disc / cartridge", "box3d" to "3D box", "hero" to "Hero banner", "video" to "Trailer (video link)"
)

/** A tick box: gold with a check when on, an empty outline when off. */
@Composable
private fun KindCheck(on: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(16.dp).clip(shape).background(if (on) C.accent else Color.Transparent).border(1.4.dp, if (on) C.accent else C.sub, shape),
        contentAlignment = Alignment.Center
    ) { if (on) Text("\u2713", color = C.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun ToggleRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SetRow(onClick = { onChange(!checked) }) {
        RowText(title, sub, Modifier.weight(1f))
        MiniSwitch(checked)
    }
}

/** The right dock's row: a slim rounded box, faint fill and edge. */
@Composable
fun Modifier.setRowLook(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(9.dp)): Modifier =
    this.clip(shape).background(if (ThemeColors.light.value) Color.White else C.x(0x0FFFFFFFL)).border(1.dp, C.x(0x12FFFFFFL), shape)

/**
 * A focusable settings row in the right dock's look. A row with a description keeps it out of sight: Select (or a long
 * press) shows it in a bubble beside the row ([HelpBubble]).
 */
@Composable
fun SetRow(focusKey: String? = null, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    val help = remember { mutableStateOf<Pair<String, String>?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    Box {
        FocusBox(
            Modifier.fillMaxWidth(), shape = shape, onClick = onClick, focusKey = focusKey, focusRequester = fr,
            onLongClick = { if (help.value != null) showHelp = true },
            onSelect = { if (help.value != null) showHelp = true }
        ) { focused ->
            CompositionLocalProvider(LocalRowFocused provides focused, LocalRowHelp provides help) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (LocalBigRows.current) 40.dp else 36.dp).setRowLook(shape).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, content = content
                )
            }
        }
        val h = help.value
        if (showHelp && h != null) HelpBubble(h.first, h.second) { showHelp = false; runCatching { fr.requestFocus() } }
    }
}

/** Where a row's [RowText] leaves its description, for [SetRow]'s bubble. */
private val LocalRowHelp = androidx.compose.runtime.compositionLocalOf<MutableState<Pair<String, String>?>?> { null }

/** A row's description in a speech bubble under it (over it near the bottom of the screen); any button closes it. */
@Composable
private fun HelpBubble(title: String, text: String, onClose: () -> Unit) {
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val place = remember(gap) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize
            ): androidx.compose.ui.unit.IntOffset {
                val x = (anchorBounds.left + 16).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val below = anchorBounds.bottom + gap
                val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
                return androidx.compose.ui.unit.IntOffset(x, y)
            }
        }
    }
    val me = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { me.requestFocus() } }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = place, onDismissRequest = onClose,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.widthIn(max = 340.dp).clip(shape)
                .background(if (ThemeColors.light.value) Color.White else Color(0xF21C1A18)).border(1.dp, C.accent.copy(alpha = 0.55f), shape)
                .focusRequester(me).focusable()
                // any button closes it (on the press, so the Select that opened it doesn't)
                .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) onClose(); true }
                .pointerInput(Unit) { detectTapGestures { onClose() } }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text(title, color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = C.ink, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** True inside the highlighted settings row: its description shows only then (Settings pages stay uncluttered). */
val LocalRowFocused = androidx.compose.runtime.compositionLocalOf { true }

/**
 * True on Settings pages: rows use bigger, plain-case text, as large as the settings tab list, instead of the small
 * capitals the right docks and menus use.
 */
val LocalBigRows = androidx.compose.runtime.staticCompositionLocalOf { false }

/** A row's name in small capitals, with an optional grey line under it (bigger plain text in a [ServiceCard]). */
@Composable
fun RowText(title: String, sub: String?, modifier: Modifier = Modifier) {
    val big = LocalBigRows.current
    Column(modifier) {
        if (big) Text(title, color = C.ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Text(title.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // the description isn't shown in the row: Select brings it up in a bubble (SetRow)
        val help = LocalRowHelp.current
        if (help != null) SideEffect { help.value = if (sub.isNullOrBlank()) null else title to sub }
    }
}

/** A small switch: gold when on. */
@Composable
fun MiniSwitch(on: Boolean) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 15.dp else 2.dp, label = "miniSwitch")
    Box(
        Modifier.padding(start = 10.dp).size(30.dp, 17.dp).clip(RoundedCornerShape(50))
            .background(if (on) C.accent else C.x(0x26FFFFFFL))
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(13.dp).clip(CircleShape).background(if (on) C.onAccent else C.x(0xFFB9B4AAL)))
    }
}

/** A setting's status: name, a coloured dot and a word; tap to sort it out. */
@Composable
private fun StatusRow(title: String, status: String, dot: Dot, onClick: () -> Unit) {
    SetRow(focusKey = "st:$title", onClick = onClick) {
        RowText(title, null, Modifier.weight(1f))
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot.color))
        Text(status.uppercase(), color = if (dot == Dot.OK) C.sub else C.accent, fontSize = if (LocalBigRows.current) 12.sp else 9.sp, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 6.dp))
        Text("\u203A", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

/** A grey explanation under a section. */
@Composable
fun Note(text: String) {
    val big = LocalBigRows.current
    Text(text, color = C.sub, fontSize = if (big) 12.sp else 10.sp, lineHeight = if (big) 16.sp else 13.5.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

/** A big gold number with its words beside it. */
@Composable
private fun BigNumber(n: String, words: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(n, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.accent)
        Text(words.uppercase(), fontSize = if (LocalBigRows.current) 12.sp else 9.5.sp, letterSpacing = 1.5.sp, color = C.x(0xFFCFCAC0L), modifier = Modifier.padding(start = 8.dp, bottom = 7.dp))
    }
}

/** A thin gold progress bar. */
@Composable
private fun Bar(progress: Float) {
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x1FFFFFFFL))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(C.accent))
    }
}

/** A settings page in two columns (the right dock's width each). */
@Composable
private fun TwoColumns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // each column is its own group: Up / Down at a column's end stops there instead of crossing to the other one
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp), content = left)
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp), content = right)
    }
}

@Composable
fun ActionRow(title: String, sub: String?, onClick: () -> Unit) {
    SetRow(onClick = onClick) {
        RowText(title, sub, Modifier.weight(1f))
        Text("\u203A", color = C.accent, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    val big = LocalBigRows.current
    val labelSize = if (big) 12.sp else 9.sp
    if (value.length <= 30) {
        // short: name on the left, value on the right
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = C.sub, fontSize = labelSize, letterSpacing = 1.4.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(value, color = C.ink, fontSize = if (big) 13.sp else 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
            Text(title.uppercase(), color = C.sub, fontSize = labelSize, letterSpacing = 1.4.sp, maxLines = 1)
            Text(value, color = C.x(0xFFB9B4AAL), fontSize = if (big) 12.sp else 10.sp, lineHeight = if (big) 16.sp else 13.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}


/** True inside a [FieldGroup]: the rows drop their own card and the group draws one card with lines between them. */
private val LocalInFieldGroup = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Text settings that belong together (server, username, password): one card, slim rows, a hairline between them.
 */
@Composable
fun FieldGroup(content: @Composable () -> Unit) {
    val line = C.x(0x12FFFFFFL)
    // where the lines go, found while laying out the rows (rows that are hidden take no space and get no line)
    var cuts by remember { mutableStateOf(emptyList<Int>()) }
    CompositionLocalProvider(LocalInFieldGroup provides true) {
        androidx.compose.ui.layout.Layout(
            content,
            Modifier.fillMaxWidth().setRowLook(RoundedCornerShape(9.dp))
                .padding(horizontal = 12.dp, vertical = 1.dp)
                .drawBehind { cuts.forEach { y -> drawLine(line, androidx.compose.ui.geometry.Offset(0f, y.toFloat()), androidx.compose.ui.geometry.Offset(size.width, y.toFloat()), 1.dp.toPx()) } }
        ) { measurables, c ->
            val placeables = measurables.map { it.measure(c.copy(minHeight = 0)) }
            val h = placeables.sumOf { it.height }
            layout(c.maxWidth, h.coerceIn(c.minHeight, c.maxHeight)) {
                var y = 0
                val found = ArrayList<Int>()
                placeables.forEach { pl ->
                    if (pl.height > 0) { if (y > 0) found += y; pl.place(0, y); y += pl.height }
                }
                if (found != cuts) cuts = found
            }
        }
    }
}

/**
 * Text setting as a slim row: its name on the left, the value in a dark box on the right (the box lights up when
 * the row is selected). Press A (or tap) to edit it in a small editor with the keyboard open. On its own it has its
 * own card; in a [FieldGroup] it shares the group's.
 */
@Composable
fun FieldRow(label: String, value: String, secret: Boolean = false, hint: String = "Not set", onChange: (String) -> Unit) {
    val grouped = LocalInFieldGroup.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (grouped) Modifier else Modifier.setRowLook(RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 1.dp))
            .heightIn(min = if (LocalBigRows.current) 40.dp else 36.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val big = LocalBigRows.current
        Text(label.uppercase(), color = if (big) C.sub else C.x(0xFFCFCAC0L), fontSize = if (big) 12.sp else 9.sp, letterSpacing = 1.3.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(if (big) 100.dp else 96.dp))
        Spacer(Modifier.width(8.dp))
        TextBox(value, Modifier.weight(1f), secret = secret, placeholder = hint, focusKey = "field:$label", fontSize = if (big) 14.sp else 11.sp, onSave = onChange)
    }
}

/**
 * A text box you type straight into (no pop-up editor): shows the value; A or a tap puts the cursor in it with the
 * keyboard up; Done, or moving away, keeps what you typed; B puts the old value back.
 */
@Composable
fun TextBox(
    value: String, modifier: Modifier = Modifier, secret: Boolean = false, placeholder: String = "",
    focusKey: String? = null, fontSize: androidx.compose.ui.unit.TextUnit = 13.sp, singleLine: Boolean = true,
    onSave: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    val boxFr = remember { FocusRequester() }
    val fieldFr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(8.dp)
    // The box stays on screen the whole time (the typing field sits inside it), so leaving the field hands the
    // controller straight back to this box: before, the field vanished first and focus fell back to the tab list.
    fun finish(save: Boolean) {
        if (!editing) return
        editing = false
        if (save) { if (text.trim() != value) onSave(text.trim()) } else text = value
        keyboard?.hide()
        runCatching { boxFr.requestFocus() }
    }
    if (editing) {
        BackHandler { finish(false) }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { fieldFr.requestFocus() }; keyboard?.show() }
    }
    FocusBox(
        modifier.heightIn(min = 28.dp), shape = shape, ring = false,
        onClick = { if (!editing) { text = value; editing = true } }, focusRequester = boxFr, focusKey = focusKey
    ) { focused ->
        if (editing) {
            var had by remember { mutableStateOf(false) }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                textStyle = androidx.compose.ui.text.TextStyle(color = C.ink, fontSize = fontSize),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.focus),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { finish(true) }),
                modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp).focusRequester(fieldFr)
                    // tapped or moved away from: keep what was typed
                    .onFocusChanged { f -> if (f.isFocused) had = true else if (had) finish(true) }
                    .clip(shape).background(C.x(0x6B000000L)).border(2.dp, C.focus, shape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 28.dp).background(C.x(0x52000000L))
                    .border(if (focused) 2.dp else 1.dp, if (focused) C.focus else C.x(0x1AFFFFFFL), shape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        value.isEmpty() -> placeholder
                        secret -> "\u2022".repeat(minOf(value.length, 12))
                        else -> value
                    },
                    color = if (value.isEmpty()) C.sub else C.ink, fontSize = fontSize,
                    maxLines = if (singleLine) 1 else 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text("\u270E", color = if (focused) C.focus else C.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}




/**
 * Text size: a slider from 50% to 130%, with a small "A" and a big "A" at its ends and the size above the knob.
 * Drag or tap it; with a controller, Left / Right step it by 5%. Dragging previews the number and applies on release,
 * so the page doesn't resize under your finger.
 */
@Composable
private fun TextSizeSlider(vm: EclipseVM) {
    val min = 50; val max = 130
    var drag by remember { mutableStateOf<Int?>(null) }
    val pct = drag ?: vm.textSize
    var focused by remember { mutableStateOf(false) }
    val frac = (pct - min) / (max - min).toFloat()
    fun snap(x: Float, w: Int): Int = (min + ((x / w).coerceIn(0f, 1f) * (max - min) / 5f).roundToInt() * 5)
    // drawn at a fixed size (not scaled by the setting itself)
    val d = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1f)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.x(0x0DFFFFFFL)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("A", color = C.sub, fontSize = 13.sp, fontFamily = vm.previewFont(vm.fontChoice))
            BoxWithConstraints(
                Modifier.weight(1f).padding(horizontal = 14.dp).height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (focused) Modifier.border(2.dp, C.focus, RoundedCornerShape(10.dp)) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { vm.chooseTextSize(vm.textSize - 5); true }
                            Key.DirectionRight -> { vm.chooseTextSize(vm.textSize + 5); true }
                            else -> false
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures { vm.chooseTextSize(snap(it.x, size.width)) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { drag = snap(it.x, size.width) },
                            onDragEnd = { drag?.let { vm.chooseTextSize(it) }; drag = null },
                            onDragCancel = { drag = null }
                        ) { change, _ -> drag = snap(change.position.x, size.width); change.consume() }
                    }
                    .padding(horizontal = 11.dp)
            ) {
                val track = maxWidth
                // the track: filled in gold up to the knob, with a tick at 100%
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.x(0x26FFFFFFL)))
                Box(Modifier.align(Alignment.CenterStart).width(track * frac).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
                Box(Modifier.align(Alignment.CenterStart).offset(x = track * ((100 - min) / (max - min).toFloat()) - 1.dp).size(2.dp, 14.dp).background(C.sub.copy(alpha = 0.6f)))
                // the knob, with the size above it
                Column(
                    Modifier.align(Alignment.CenterStart).offset(x = track * frac - 22.dp).width(44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("$pct%", color = C.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Box(Modifier.padding(top = 3.dp).size(22.dp).clip(CircleShape).background(Color.White).border(3.dp, C.accent, CircleShape))
                    Spacer(Modifier.height(14.dp))
                }
            }
            Text("A", color = C.sub, fontSize = 26.sp, fontFamily = vm.previewFont(vm.fontChoice))
        }
    }
}


/**
 * Settings → Appearance: two columns of drop-down rows (the right dock's style). Look and Text on the left, Images and
 * Wallpaper on the right. Simple choices open under their row; colours open the colour wheel, and the theme, icons
 * and backdrops open their pickers.
 */
@Composable
private fun AppearanceTab(vm: EclipseVM) {
    // (On Deck / platform view styles and poster sizes live in each screen's right-hand drawer: R)
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(id: String) { open = if (open == id) null else id }
    val fonts = listOf(
        "" to (if (vm.theme?.font != null) "Theme's" else "Default"),
        "system" to "System", "serif" to "Serif", "mono" to "Mono", "condensed" to "Condensed", "casual" to "Casual"
    ) + vm.importedFonts.map { f -> (if (f == "custom.ttf") "custom" else "file:$f") to vm.fontLabel(f) }
    val usingFile = vm.fontChoice.removePrefix("file:").takeIf { vm.fontChoice.startsWith("file:") } ?: if (vm.fontChoice == "custom") "custom.ttf" else null

    // ---- Look: across both columns, in pairs (Theme | Colours, Card logos | Corners, Icons | Backdrops, Glass | Reload)
    DropSection("Look")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            DropRow("Theme", vm.theme?.name ?: "Eclipse", "ap:theme", opens = true) { vm.appearancePicker = "theme" }
            // system cards are made of the glass colour; their logos are black or white to suit it, or in colour
            val cardModes = listOf("" to "Black / white", "color" to "Colour")
            DropRow("Card logos", cardModes.firstOrNull { it.first == vm.cardMode }?.second ?: "Black / white", "ap:cards", open == "cards") { toggle("cards") }
            AnimatedVisibility(open == "cards") {
                DropMenu {
                    cardModes.forEach { (id, label) ->
                        DropChoice(label, id == vm.cardMode, if (id == "") "whichever reads on the glass colour" else null, "ap:cards:$id") { vm.chooseCardMode(id); open = null }
                    }
                }
            }
            DropRow("Icons", packLabel(vm, vm.iconChoice, vm.iconPacks), "ap:icons", opens = true) { vm.appearancePicker = "icons" }
            // Glass effect: frosted panels over a blurred picture (off: plain tinted panels, lighter on the battery)
            ToggleRow("Glass effect", "Frosted panels and blur. Off is lighter on the battery.", !vm.lowPowerOn()) { vm.chooseLowPower(!it) }
        }
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            // glass, accent, highlight, text, labels, glass strength and dim: their own screen with a live preview
            val custom = listOf(vm.colorGlass, vm.colorAccent, vm.colorFocus, vm.colorText, vm.colorLabels).any { it.isNotBlank() } || vm.glassStrength >= 0 || vm.bgDim >= 0
            DropRow("Colours", if (custom) "Your own" else "Theme's", "ap:colours", opens = true, swatch = C.accent) { vm.appearancePicker = "colours" }
            DropRow("Corners", "Art ${vm.cornerArt}% \u00b7 Cards ${vm.cornerCard}% \u00b7 Icons ${vm.cornerIcon}%", "ap:corners", open == "corners") { toggle("corners") }
            AnimatedVisibility(open == "corners") {
                DropMenu {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("GAME ART", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp)
                        PercentSlider(vm.cornerArt, "SQUARE", "ROUND") { vm.chooseCornerArt(it) }
                        Text("SYSTEM CARDS", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp)
                        PercentSlider(vm.cornerCard, "SQUARE", "ROUND") { vm.chooseCornerCard(it) }
                        Text("SYSTEM ICONS", color = C.sub, fontSize = 8.sp, letterSpacing = 1.4.sp)
                        PercentSlider(vm.cornerIcon, "SQUARE", "ROUND") { vm.chooseCornerIcon(it) }
                    }
                }
            }
            DropRow("Backdrops", packLabel(vm, vm.backdropChoice, vm.backdropPacks), "ap:backdrops", opens = true) { vm.appearancePicker = "backdrops" }
            // every theme's and pack's pictures are plain files in the Eclipse folder: tweak them, then reload
            DropRow("Reload images", if (vm.homeInUse) "Eclipse folder" else "Eclipse storage", "ap:reload", opens = true) { vm.reloadImages() }

        }
    }
    // ---- then Wallpaper on the left, Text and Sounds on the right
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            DropSection("Wallpaper")
            DropRow("Android wallpaper", if (vm.sysWall) "On" else "Off", "ap:syswall", open == "syswall") { toggle("syswall") }
            AnimatedVisibility(open == "syswall") {
                DropMenu {
                    DropChoice("Use Eclipse's wallpaper", !vm.sysWall, null, "ap:syswall:off") { vm.chooseSysWall(false); open = null }
                    DropChoice("Use Android's wallpaper", vm.sysWall, "live ones too", "ap:syswall:on") { vm.chooseSysWall(true) }
                    DropChoice("Choose Android wallpaper\u2026", false, null, "ap:syswall:pick", radio = false) { vm.chooseSysWall(true); open = null; vm.openWallpaperChooser() }
                }
            }
            if (!vm.sysWall) {
            val topLabel = if (vm.wallTop.isEmpty()) (if (vm.theme?.wallpaper != null) "Theme's" else "Eclipse") else "Your picture"
            DropRow("Top screen", topLabel, "ap:wtop", open == "wtop") { toggle("wtop") }
            AnimatedVisibility(open == "wtop") {
                DropMenu {
                    DropChoice("Choose a picture\u2026", false, null, "ap:wtop:pick", radio = false) { open = null; Pickers.wallpaper("top") }
                    if (vm.wallTop.isNotEmpty()) DropChoice("Remove my picture", false, "back to the theme's", "ap:wtop:clear", radio = false) { vm.clearWallpaper("top"); open = null }
                }
            }
            val bottomLabel = if (vm.wallBottom.isNotEmpty()) "Your picture" else if (vm.wallTop.isEmpty() && vm.theme?.wallpaper == null) "Eclipse" else "Same as top"
            DropRow("Bottom screen", bottomLabel, "ap:wbottom", open == "wbottom") { toggle("wbottom") }
            AnimatedVisibility(open == "wbottom") {
                DropMenu {
                    DropChoice("Choose a picture\u2026", false, null, "ap:wbottom:pick", radio = false) { open = null; Pickers.wallpaper("bottom") }
                    if (vm.wallBottom.isNotEmpty()) DropChoice("Remove my picture", false, "same as the top again", "ap:wbottom:clear", radio = false) { vm.clearWallpaper("bottom"); open = null }
                }
            }
            val wallModes = listOf("" to "As the theme says", "fallback" to "If no game backdrop", "always" to "Always")
            DropRow("Show it", wallModes.firstOrNull { it.first == vm.wallMode }?.second ?: "As the theme says", "ap:wm", open == "wm") { toggle("wm") }
            AnimatedVisibility(open == "wm") {
                DropMenu {
                    wallModes.forEach { (id, label) -> DropChoice(label, id == vm.wallMode, null, "ap:wm:$id") { vm.chooseWallMode(id); open = null } }
                }
            }
            }
        }
        Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            DropSection("Text")
            DropRow("Font", fonts.firstOrNull { it.first == vm.fontChoice }?.second ?: "Default", "ap:font", open == "font") { toggle("font") }
            AnimatedVisibility(open == "font") {
                DropMenu {
                    fonts.forEach { (id, label) ->
                        DropChoice(label, id == vm.fontChoice, null, "ap:font:$id", previewFont = vm.previewFont(id)) { vm.chooseFont(id) }
                    }
                    DropChoice("Import font\u2026", false, ".ttf or .otf", "ap:fontimport", radio = false) { Pickers.font() }
                    if (usingFile != null && usingFile in vm.importedFonts) {
                        DropChoice("Remove \u201C${vm.fontLabel(usingFile)}\u201D", false, "back to the default", "ap:fontremove", radio = false) { vm.deleteFont(usingFile) }
                    }
                }
            }
            DropRow("Text size", "${vm.textSize}%", "ap:textsize", open == "size") { toggle("size") }
            AnimatedVisibility(open == "size") {
                DropMenu { TextSizeSlider(vm) }
            }
            DropSection("Sounds")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PercentSlider(vm.uiVolume, "OFF", "LOUD") { vm.chooseUiVolume(it); com.eclipse.launcher.media.UiSound.tick() }
                }
                Text("${vm.uiVolume}%", color = C.ink, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
            }
            val builtIn = com.eclipse.launcher.media.UiSound.BUILT_IN
            val soundLabel = builtIn.firstOrNull { it.first == vm.uiSoundPick }?.second ?: vm.uiSoundPick.removePrefix("file:").substringBeforeLast('.')
            DropRow("Sound", soundLabel, "ap:sound", open == "sound") { toggle("sound") }
            AnimatedVisibility(open == "sound") {
                DropMenu {
                    builtIn.forEach { (id, label) ->
                        DropChoice(label, vm.uiSoundPick == id, null, "ap:sound:$id") { vm.chooseUiSoundPick(id); com.eclipse.launcher.media.UiSound.tick() }
                    }
                    vm.importedSounds.forEach { f ->
                        DropChoice(f.substringBeforeLast('.'), vm.uiSoundPick == "file:$f", "your file", "ap:sound:f:$f") { vm.chooseUiSoundPick("file:$f"); com.eclipse.launcher.media.UiSound.tick() }
                    }
                    DropChoice("Choose a sound file\u2026", false, null, "ap:sound:pick", radio = false) { open = null; Pickers.sound() }
                    if (vm.uiSoundPick.startsWith("file:")) DropChoice("Remove my sound", false, null, "ap:sound:del", radio = false) { vm.deleteSound(vm.uiSoundPick.removePrefix("file:")); open = null }
                }
            }
        }
    }
}

/** A gold section label with a hairline after it ("LOOK ———"). */
@Composable
fun DropSection(title: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = C.accent, fontSize = if (LocalBigRows.current) 11.sp else 9.sp, letterSpacing = 2.sp, maxLines = 1)
        Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(C.x(0x1AFFFFFFL)))
    }
}

/**
 * A drop-down row (the right dock's style): name on the left, the current choice in gold on the right, then an arrow
 * ("\u203A" when it opens something else: a picker or the colour wheel). [swatch] shows a colour before the value.
 */
@Composable
private fun DropRow(label: String, value: String, focusKey: String, expanded: Boolean = false, opens: Boolean = false, swatch: Color? = null, onClick: () -> Unit) {
    val shape = if (expanded) RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp) else RoundedCornerShape(9.dp)
    val light = ThemeColors.light.value
    val rowH = if (LocalBigRows.current) 40.dp else 36.dp
    val fr = remember { FocusRequester() }
    // the drop-down composed right after this row takes it as the place B goes back to
    if (expanded) LastDropHeader.fr = fr
    FocusBox(Modifier.fillMaxWidth().heightIn(min = rowH), shape = shape, onClick = onClick, focusKey = focusKey, focusRequester = fr) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = rowH)
                .background(if (expanded) C.accent.copy(alpha = if (light) 0.14f else 0.10f) else if (light) Color.White else C.x(0x0FFFFFFFL))
                .border(1.dp, if (expanded) C.accent.copy(alpha = 0.45f) else C.x(0x12FFFFFFL), shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val big = LocalBigRows.current
            if (big) Text(label, color = C.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else Text(label.uppercase(), color = C.x(0xFFCFCAC0L), fontSize = 9.5.sp, letterSpacing = 1.6.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (swatch != null) Box(Modifier.padding(end = 7.dp).size(12.dp).clip(CircleShape).background(swatch).border(1.dp, C.x(0x4DFFFFFFL), CircleShape))
            if (big) Text(value, color = C.accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 170.dp))
            else Text(value.uppercase(), color = C.accent, fontSize = 9.5.sp, letterSpacing = 1.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
            Text(if (opens) "\u203A" else if (expanded) "\u2303" else "\u2304", color = C.sub, fontSize = if (opens) 14.sp else 10.sp, modifier = Modifier.padding(start = 7.dp))
        }
    }
}

/** The row of the drop-down opened last (its list composes right after it). */
private object LastDropHeader { var fr: FocusRequester? = null }

/** The choices of an open [DropRow]: a darker box hanging under it. */
@Composable
private fun DropMenu(tight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)
    // B while a choice in here is highlighted: back up to the row the list hangs from (the top of the list)
    val header = remember { LastDropHeader.fr }
    var inside by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = inside && header != null) { runCatching { header?.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().onFocusChanged { inside = it.hasFocus }.then(if (tight) Modifier else Modifier.offset(y = (-7).dp)).clip(shape)
            .background(if (ThemeColors.light.value) Color(0xBFFFFFFF) else C.x(0x80100F0EL))
            .border(1.dp, C.accent.copy(alpha = 0.3f), shape).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

/** One choice in a [DropMenu]: a radio dot (or none, for actions like "Import font…"), its name and a note. */
@Composable
private fun DropChoice(
    label: String, selected: Boolean, note: String?, focusKey: String,
    radio: Boolean = true, previewFont: androidx.compose.ui.text.font.FontFamily? = null, checked: Boolean? = null, onClick: () -> Unit
) {
    FocusBox(Modifier.fillMaxWidth().heightIn(min = 30.dp), shape = RoundedCornerShape(7.dp), onClick = onClick, focusKey = focusKey) {
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) {
                Box(
                    Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(if (checked) C.accent else Color.Transparent)
                        .border(1.3.dp, if (checked) C.accent else C.sub, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center
                ) { if (checked) Icon(Icons.Filled.Check, null, tint = C.onAccent, modifier = Modifier.size(9.dp)) }
            } else if (radio) {
                Box(Modifier.size(10.dp).clip(CircleShape).border(1.3.dp, if (selected) C.accent else C.sub, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Box(Modifier.size(5.dp).clip(CircleShape).background(C.accent))
                }
            } else Text("+", color = C.accent, fontSize = 12.sp, modifier = Modifier.width(10.dp))
            Spacer(Modifier.width(9.dp))
            Text(
                if (previewFont != null) label else label.uppercase(),
                color = if (selected) C.ink else C.x(0xFFB9B4AAL),
                fontSize = if (previewFont != null) 12.sp else if (LocalBigRows.current) 12.sp else 9.sp, letterSpacing = if (previewFont != null) 0.sp else 1.2.sp,
                fontFamily = previewFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (note != null) Text(note, color = C.sub, fontSize = 9.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun packLabel(vm: EclipseVM, choice: String, packs: List<com.eclipse.launcher.data.Packs.Pack>): String = when {
    choice.startsWith("pack:") -> packs.firstOrNull { it.id == choice.removePrefix("pack:") }?.name ?: "From the theme"
    choice.startsWith("theme:") -> vm.themes.firstOrNull { it.id == choice.removePrefix("theme:") }?.name ?: "From the theme"
    else -> if (packs === vm.iconPacks) (vm.defaultIconPack()?.name ?: "None") else "From the theme"
}



/** A one-line row: name on the left, its value on the right. */
@Composable
private fun SlimRow(title: String, value: String, valueColor: Color = C.sub, focusKey: String? = null, onClick: () -> Unit) {
    SetRow(focusKey = focusKey, onClick = onClick) {
        RowText(title, null, Modifier.weight(1f))
        Text(value.uppercase(), color = valueColor, fontSize = if (LocalBigRows.current) 12.sp else 9.sp, letterSpacing = 1.2.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        Text("\u203A", color = C.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
    }
}


/** The setup wizard's pages: id, heading, one line under it, and whether it can be skipped. */
private data class SetupPage(val id: String, val title: String, val desc: String, val optional: Boolean = false)
private val SETUP_PAGES = listOf(
    SetupPage("welcome", "", ""),
    SetupPage("perms", "What Eclipse can use", "Turn on what you'd like. Each opens Android's page for it: switch it on, then come back."),
    SetupPage("games", "Where are your games?", "Pick the folder that holds them. Sub-folders named after systems (snes, gba, psx\u2026) are found by themselves."),
    SetupPage("art", "Artwork", "Box art and descriptions come from free sources already. These add more; each needs a free key.", true),
    SetupPage("romm", "RomM", "Your own game server: browse it here, download or stream its games, keep saves in sync.", true),
    SetupPage("romm2", "Library & installs", "What a RomM game does on this device, and where games you send to RomM go.", true),
    SetupPage("ra", "RetroAchievements", "Your achievements on each game's page and, while you play, on the bottom screen.", true),
    SetupPage("ready", "You're all set", "")
)

/** The pages shown: RomM's second page only once RomM is connected. */
private fun setupPages(vm: EclipseVM) = SETUP_PAGES.filter { it.id != "romm2" || vm.isConfigured("romm") }

/**
 * First-run setup, minimal: pure black (the splash's eclipse glowing low on the screen, rising a little each page), one
 * narrow column per page. Pages slide in from the side they're coming from, and their rows fade up one after another.
 */
@Composable
fun SetupWizard(vm: EclipseVM, pickFolder: () -> Unit) = Glass {
    val pages = setupPages(vm)
    val last = pages.lastIndex
    val step = vm.setupStep.coerceIn(0, last)
    // leaving the Artwork page: artwork starts downloading for the whole library, in the background
    var lastStep by remember { mutableIntStateOf(step) }
    LaunchedEffect(step) {
        if (lastStep < step && pages.getOrNull(lastStep)?.id == "art") vm.scrapeAfterSetup()
        lastStep = step
    }
    val next = remember { FocusRequester() }
    FocusOnShow(next, step)
    PrimaryFocus(next) // a stray press puts the controller back here, not on Home behind
    DisposableEffect(Unit) {
        GamepadFocus.modal = next
        onDispose { if (GamepadFocus.modal === next) { GamepadFocus.modal = null; GamepadFocus.modalHasFocus = false } }
    }
    BackHandler { if (step > 0) vm.setupStep = step - 1 }
    CompositionLocalProvider(LocalPlainButtons provides true) {
    // focusTrap: the D-pad stays inside the wizard (it can't wander onto the dock or Home hidden behind it)
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }
            .onFocusChanged { GamepadFocus.modalHasFocus = it.hasFocus }.focusTrap()
    ) {
        SetupArc(step / last.toFloat())

        // the page: slides in from the right going forward, from the left going back
        androidx.compose.animation.AnimatedContent(
            targetState = step,
            // every page comes in from the left and leaves to the right
            transitionSpec = {
                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(460)) { w -> -w / 8 } +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(460))) togetherWith
                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(300)) { w -> w / 8 } +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)))
            },
            modifier = Modifier.fillMaxSize(), label = "setupPage"
        ) { s -> SetupPageBody(vm, pages.getOrElse(s) { pages.last() }, s == 0, s == last, pickFolder) }

        // where you are: a dot per page, the current one a gold bar
        if (step in 1 until last + 1) Column(Modifier.align(Alignment.TopCenter).padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    val wdt by androidx.compose.animation.core.animateDpAsState(if (i == step) 22.dp else 4.dp, label = "dot")
                    Box(Modifier.size(wdt, 4.dp).clip(RoundedCornerShape(2.dp)).background(
                        when { i == step -> C.accent; i < step -> C.accent.copy(alpha = 0.55f); else -> Color.White.copy(alpha = 0.22f) }
                    ))
                }
            }
            if (step < last) Text(
                "${step + 1} OF ${last + 1}" + if (pages[step].optional) "  ·  OPTIONAL" else "",
                color = Color(0xFF8E897F), fontSize = 8.sp, letterSpacing = 2.4.sp, modifier = Modifier.padding(top = 9.dp)
            )
        }
        // skip, small, top right
        if (step < last) FocusBox(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp), shape = RoundedCornerShape(50), onClick = { vm.finishSetup() }, focusKey = "setup:skip") {
            Text("SKIP SETUP", color = Color(0xFF8E897F), fontSize = 8.5.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }

        // Back and Continue, low in the middle above the eclipse
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (step == 0 || step == last) 86.dp else 54.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (step in 1 until last) WizardButton("B", "Back", gold = false) { vm.setupStep = step - 1 }
            WizardButton(
                "A", when (step) { 0 -> "Get started"; last -> "Start playing"; else -> "Continue" }, gold = true, fr = next
            ) { if (step == last) vm.finishSetup() else vm.setupStep = step + 1 }
        }
    }
    }
}

/** The eclipse low on the black screen (only a sliver of its rim): [rise] 0 → 1 lifts it a little across the pages. */
@Composable
private fun SetupArc(rise: Float) {
    val r by androidx.compose.animation.core.animateFloatAsState(rise, androidx.compose.animation.core.tween(700), label = "arcRise")
    val glow = Color(0xFFE0B04A)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val rad = w * 0.6f
        val top = h * (0.955f - 0.055f * r)
        val c = androidx.compose.ui.geometry.Offset(w / 2f, top + rad)
        listOf(24.dp to 0.05f, 12.dp to 0.12f, 6.dp to 0.26f, 2.5.dp to 0.55f).forEach { (sw, a) ->
            drawCircle(glow.copy(alpha = a), rad, c, style = androidx.compose.ui.graphics.drawscope.Stroke(sw.toPx()))
        }
        drawCircle(Color.Black, rad - 1.dp.toPx(), c)
        drawCircle(Color(0xFFFFE2A0).copy(alpha = 0.85f), rad, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        val f = androidx.compose.ui.geometry.Offset(w / 2f, top)
        val gr = 22.dp.toPx()
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f), Color(0xFFFFE6AA).copy(alpha = 0.35f), Color.Transparent), center = f, radius = gr), gr, f)
        drawCircle(Color.White, 3.5.dp.toPx(), f)
    }
}

/** A round wizard button: a key letter in a ring, then the label. Gold for the main one. */
@Composable
private fun WizardButton(key: String, label: String, gold: Boolean, fr: FocusRequester? = null, onClick: () -> Unit) {
    FocusBox(Modifier.height(30.dp), shape = RoundedCornerShape(50), onClick = onClick, focusRequester = fr, focusKey = "setup:$label") {
        Row(
            Modifier.fillMaxHeight().background(if (gold) C.accent else Color.Transparent).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ink = if (gold) C.onAccent else Color(0xFFD8D3C9)
            Box(Modifier.size(14.dp).clip(CircleShape).border(1.dp, ink.copy(alpha = 0.6f), CircleShape)) { CenteredGlyph(key, ink, 7.5.sp, Modifier.fillMaxSize()) }
            Text(label.uppercase(), color = ink, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = if (gold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Fades [content] up into place, [i] steps after the page arrives (the rows of a page come in one after another). */
@Composable
private fun Reveal(i: Int, content: @Composable () -> Unit) {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(140L + i * 60L)
        a.animateTo(1f, androidx.compose.animation.core.tween(380, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationX = -(1f - a.value) * 18.dp.toPx() }) { content() }
}

/** One page: its heading and line, then its rows, in a narrow centred column (scrolls if it doesn't fit). RomM and
 *  RetroAchievements use two columns instead: their logo, name and status on the left, the settings on the right. */
@Composable
private fun SetupPageBody(vm: EclipseVM, page: SetupPage, first: Boolean, isLast: Boolean, pickFolder: () -> Unit) {
    if (page.id == "romm" || page.id == "romm2" || page.id == "ra") { SetupTwoColumns(vm, page); return }
    val scroll = rememberScrollState()
    RightStick.Register(scroll)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().fillMaxHeight().verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = if (first) 118.dp else if (isLast) 104.dp else 58.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (first) {
                Reveal(0) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append("ECLIPS")
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = C.accent)); append("E"); pop()
                        },
                        color = Color(0xFFF2EEE6), fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 16.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
                Reveal(1) { SetupLine("A home for your games. A few quick steps, all optional, all changeable later.") }
                return@Column
            }
            Reveal(0) { Text(page.title, color = Color(0xFFF2EEE6), fontSize = 26.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            if (page.desc.isNotEmpty()) Reveal(1) { SetupLine(page.desc) }
            Spacer(Modifier.height(10.dp))
            var n = 2
            when (page.id) {
                "perms" -> {
                    @Suppress("UNUSED_VARIABLE") val tick = vm.permTick // re-checked on coming back from Android settings
                    Reveal(n++) { PermRow("All-files access", "Find your games; real file paths for emulators.", vm.hasAllFiles()) { vm.requestAllFiles() } }
                    Reveal(n++) { PermRow("Notification access", "Now Playing on the second screen.", vm.notifAccessOn()) { vm.requestNotifAccess() } }
                    Reveal(n++) { PermRow("Eclipse helper", "Screenshots and the app switcher (hold Back).", vm.helperOn()) { vm.requestScreenshotAccess() } }
                    Reveal(n++) { PermRow("No battery limits", "Keeps Eclipse open during long sessions.", vm.batteryUnrestricted()) { vm.requestBatteryUnrestricted() } }
                    Reveal(n++) { PermRow("Default home app", "Makes Eclipse your home screen.", vm.isDefaultHome()) { vm.openHomeSettings() } }
                    Reveal(n++) { Note("Switch greyed out? Android blocks some for apps from outside a store: Eclipse's app info → ⋮ → Allow restricted settings.") }
                }
                "games" -> {
                    vm.romFolders().forEach { f ->
                        Reveal(n++) { FolderLine(f, if (f == vm.romPath && vm.romFolders().size > 1) "main" else null) { vm.removeFolder(f) } }
                    }
                    Reveal(n++) { ActionRow(if (vm.romFolders().isEmpty()) "Add a folder" else "Add another folder", null) { vm.folderTarget = null; pickFolder() } }
                    if (vm.romFolders().isNotEmpty()) Reveal(n++) {
                        InfoRow("Games found", vm.games.count { it.platform != "android" }.toString() + if (vm.scanning) "  (scanning…)" else "")
                    }
                    if (!vm.hasAllFiles()) Reveal(n++) { ActionRow("All-files access", "Needed to read your games. Tap to allow.") { vm.requestAllFiles() } }
                    Reveal(n++) { ToggleRow("Add Android games too", "Installed apps that are games.", vm.flag("auto_scan", true)) { vm.setFlag("auto_scan", it) } }
                }
                "art" -> {
                    LaunchedEffect(Unit) { vm.checkConnections() }
                    var open by remember { mutableStateOf<String?>(null) }
                    val toggle = { id: String -> open = if (open == id) null else id }
                    Reveal(n++) { StatusRow("Free sources", "Always on", Dot.OK) { } }
                    Reveal(n++) {
                        val (d, t) = statusOf(vm, "sgdb", "Add a key")
                        ServiceCard("SteamGridDB", t, d, open == "sgdb", { toggle("sgdb") }) {
                            FieldRow("API key", vm.key("sgdb"), secret = true) { vm.setKey("sgdb", it.trim()); vm.checkConnection("sgdb") }
                            InfoRow("Where to get a key", "steamgriddb.com → sign in → Preferences → API.")
                        }
                    }
                    Reveal(n++) {
                        val (d, t) = statusOf(vm, "igdb", "Add a key")
                        ServiceCard("IGDB", t, d, open == "igdb", { toggle("igdb") }) {
                            FieldGroup {
                                FieldRow("Client ID", vm.key("igdb_id")) { vm.setKey("igdb_id", it.trim()); vm.checkConnection("igdb") }
                                FieldRow("Client secret", vm.key("igdb_secret"), secret = true) { vm.setKey("igdb_secret", it.trim()); vm.checkConnection("igdb") }
                            }
                            InfoRow("Where to get them", "dev.twitch.tv → Your Console → Register Your Application, then copy the Client ID and make a secret.")
                        }
                    }
                    Reveal(n++) {
                        val lb = vm.lbStatus
                        val (d, t) = when {
                            lb != null -> Dot.BUSY to lb
                            LaunchBox.loaded -> Dot.OK to "Ready"
                            else -> Dot.OFF to "Download"
                        }
                        ServiceCard("LaunchBox", t, d, open == "lb", { toggle("lb") }) {
                            if (lb != null) { Bar(vm.lbProgress); ActionRow("Cancel", null) { vm.cancelLaunchBox() } }
                            else ActionRow(if (LaunchBox.loaded) "Check for newer LaunchBox data" else "Download LaunchBox data", "Downloaded once and kept (use Wi-Fi).") { vm.updateLaunchBox() }
                        }
                    }
                    // which kinds of art to download (the same setting as Settings → Scrape → What to scrape; no trailers here)
                    Reveal(n++) { Box(Modifier.padding(top = 8.dp)) { DropSection("What to download") } }
                    SCRAPE_KINDS.filter { it.first != "video" }.chunked(2).forEach { pair ->
                        Reveal(n++) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                pair.forEach { (k, label) ->
                                    Box(Modifier.weight(1f)) {
                                        SetRow("setup:kind:$k", onClick = { vm.chooseScrapeKind(k, k !in vm.scrapeKinds) }) {
                                            KindCheck(k in vm.scrapeKinds)
                                            Text(label, color = C.ink, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                else -> {
                    val games = vm.games.count { it.platform != "android" }
                    val systems = vm.games.filter { it.platform != "android" }.map { it.platform }.distinct().size
                    Reveal(n++) {
                        SetupLine(
                            (if (games == 0) "No games found yet. Add a folder any time in Settings → Library."
                            else "$games games across $systems system${if (systems == 1) "" else "s"}." + if (vm.scanning) " Still looking…" else "") +
                                if (games > 0 && vm.artProviders().isNotEmpty()) " Artwork is being fetched in the background." else ""
                        )
                    }
                    Reveal(n++) {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                            SetupChip("Folders", vm.romFolders().isNotEmpty())
                            SetupChip("Artwork", vm.isConfigured("sgdb") || vm.isConfigured("igdb") || LaunchBox.loaded)
                            SetupChip("RomM", vm.isConfigured("romm"))
                            SetupChip("Achievements", vm.isConfigured("ra"))
                        }
                    }
                }
            }
        }
    }
}

/** RomM (two pages) and RetroAchievements: logo, name, line and status on the left; their settings on the right. */
@Composable
private fun SetupTwoColumns(vm: EclipseVM, page: SetupPage) {
    LaunchedEffect(Unit) { vm.checkConnections() }
    val scroll = rememberScrollState()
    RightStick.Register(scroll)
    Row(
        Modifier.fillMaxSize().padding(start = 60.dp, end = 60.dp, top = 60.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // ---- left: who it is
        Column(Modifier.width(230.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Reveal(0) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ra = page.id == "ra"
                    val size = if (page.id == "romm2") 72.dp else if (ra) 74.dp else 96.dp
                    // a soft glow in the logo's own colours behind it (the rest stays black)
                    Box(Modifier.size(size * 1.8f).background(Brush.radialGradient(
                        if (ra) listOf(Color(0x382870E6), Color(0x1AE0B04A), Color.Transparent) else listOf(Color(0x527850C8), Color.Transparent)
                    )))
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(if (ra) com.eclipse.launcher.R.drawable.ra_logo else com.eclipse.launcher.R.drawable.romm_logo),
                        contentDescription = page.title,
                        modifier = if (ra) Modifier.height(size) else Modifier.size(size)
                    )
                }
            }
            Reveal(1) { Text(page.title, color = Color(0xFFF2EEE6), fontSize = if (page.id == "romm2") 22.sp else 26.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Reveal(2) { Text(page.desc, color = Color(0xFF8E897F), fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            val id = if (page.id == "ra") "ra" else "romm"
            if (page.id != "romm2") Reveal(3) {
                val (dot, text) = if (id == "romm" && vm.rommBusy) Dot.BUSY to (vm.rommStatus ?: "Working…") else statusOf(vm, id, "Not set up")
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(when (dot) { Dot.OK -> Color(0xFF4CAF50); Dot.ERROR -> Color(0xFFE57373); Dot.BUSY -> C.accent; else -> Color(0x40FFFFFF) }))
                    Text(text.uppercase(), color = if (dot == Dot.OK) Color(0xFF9FD39F) else Color(0xFF8E897F), fontSize = 9.5.sp, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
        // ---- right: its settings
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var n = 1
            when (page.id) {
                "romm" -> {
                    Reveal(n++) {
                        FieldGroup {
                            FieldRow("Server address", vm.key("romm_url")) { vm.setKey("romm_url", it.trim()); vm.checkConnection("romm") }
                            FieldRow("Username", vm.key("romm_user")) { vm.setKey("romm_user", it.trim()); vm.checkConnection("romm") }
                            FieldRow("Password", vm.key("romm_pass"), secret = true) { vm.setKey("romm_pass", it); vm.checkConnection("romm") }
                        }
                    }
                    if (vm.rommInsecure()) Reveal(n++) {
                        Text(
                            "This address uses http, so your RomM password is sent unencrypted. Use an https address if your server has one.",
                            color = androidx.compose.ui.graphics.Color(0xFFE59A6A), fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 2.dp, start = 2.dp)
                        )
                    }
                    if (vm.isConfigured("romm")) Reveal(n++) {
                        ActionRow("Sync library", vm.rommStatus?.takeIf { !vm.rommBusy } ?: "Reads all your RomM games.") { vm.rommSync() }
                    }
                }
                "romm2" -> {
                    Reveal(n++) {
                        var launchOpen by remember { mutableStateOf(false) }
                        val launches = listOf("ask" to "Ask each time", "play" to "Install and play", "download" to "Install")
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            DropRow("Opening a RomM game", launches.firstOrNull { it.first == vm.rommLaunch }?.second ?: "Ask each time", "setup:romm:launch", launchOpen) { launchOpen = !launchOpen }
                            AnimatedVisibility(launchOpen) {
                                DropMenu {
                                    launches.forEach { (lid, label) ->
                                        DropChoice(label, lid == vm.rommLaunch, null, "setup:romm:launch:$lid") { vm.chooseRommLaunch(lid); launchOpen = false }
                                    }
                                }
                            }
                        }
                    }
                    Reveal(n++) { ToggleRow("Convert installs to CHD", "Disc games become CHDs (GameCube / Wii: RVZ) before they start.", vm.flag("romm_chd", false)) { vm.setFlag("romm_chd", it) } }
                    Reveal(n++) {
                        Text("ROMM'S LIBRARY SHARE · FOR SENDING GAMES", color = Color(0xFF8E897F), fontSize = 7.5.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    }
                    Reveal(n++) {
                        FieldGroup {
                            val rh = vm.rommHost()
                            FieldRow("Computer", vm.key("smb_host"), hint = if (rh.isNotEmpty()) "$rh (RomM's)" else "Not set") { vm.setKey("smb_host", it.trim()); vm.checkSmb() }
                            FieldRow("Username", vm.key("smb_user")) { vm.setKey("smb_user", it.trim()); vm.checkSmb() }
                            FieldRow("Password", vm.key("smb_pass"), secret = true) { vm.setKey("smb_pass", it); vm.checkSmb() }
                        }
                    }
                    Reveal(n++) {
                        val where = vm.key("smb_share").takeIf { it.isNotBlank() }?.let { sh -> listOf(sh, vm.key("smb_path")).filter { it.isNotBlank() }.joinToString(" / ") }
                        DropRow("Library folder", where ?: "Browse", "setup:smb:browse", opens = true) { vm.openSmbBrowser() }
                    }
                }
                else -> {
                    Reveal(n++) {
                        FieldGroup {
                            FieldRow("Username", vm.key("ra_user")) { vm.setKey("ra_user", it.trim()); vm.checkConnection("ra") }
                            FieldRow("Web API key", vm.key("ra_key"), secret = true) { vm.setKey("ra_key", it.trim()); vm.checkConnection("ra") }
                        }
                    }
                    Reveal(n++) { ToggleRow("Show achievements in the game menu", null, vm.flag("ra_on")) { vm.setFlag("ra_on", it) } }
                    Reveal(n++) { InfoRow("Where to find the key", "retroachievements.org → your profile → Settings → Keys → Web API Key.") }
                }
            }
        }
    }
}

@Composable
private fun SetupLine(text: String) =
    Text(text, color = Color(0xFF8E897F), fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp))

/** "ROMM ✓" when it's set up, faded "ACHIEVEMENTS –" when not. */
@Composable
private fun SetupChip(label: String, done: Boolean) {
    Text(
        label.uppercase() + if (done) "  ✓" else "  –", color = if (done) Color(0xFFCFCAC0) else Color(0x66CFCAC0),
        fontSize = 8.5.sp, letterSpacing = 1.4.sp, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}


/** Settings \u2192 Credits: who made what Eclipse uses, and under which licence. Rows with a link open it (A / tap). */
@Composable
private fun Credits(vm: EclipseVM) {
    TwoColumns({
    CreditHeader("Eclipse")
    CreditRow(vm, "Version", vm.appVersion().ifBlank { "Unknown" })
    CreditRow(
        vm,
        "About",
        "A launcher and emulator frontend for handhelds and TV boxes. Eclipse doesn't include any games, BIOS files " +
            "or emulators: it finds and opens the ones you already have."
    )
    CreditRow(vm, "Licence", "Free software under the GNU General Public License, version 3 or later.", "https://www.gnu.org/licenses/gpl-3.0.html")
    CreditRow(vm, "Source code", "github.com/ProfoundBandit86/eclipse", "https://github.com/ProfoundBandit86/eclipse")

    CreditHeader("Open-source libraries")
    CreditRow(vm, "Dolphin (disc code: RVZ converter)", "Dolphin Emulator Project \u00b7 GPL-2.0-or-later", "https://github.com/dolphin-emu/dolphin")
    CreditRow(vm, "Zstandard, liblzma, {fmt}, Mbed TLS", "Used by Dolphin's converter \u00b7 BSD, public domain, MIT, Apache 2.0", "https://github.com/dolphin-emu/dolphin/tree/master/Externals")
    CreditRow(vm, "jcifs-ng (network shares)", "AgNO3 & contributors \u00b7 LGPL 2.1", "https://github.com/AgNO3/jcifs-ng")
    CreditRow(vm, "Jetpack Compose, AndroidX & Material icons", "Google \u00b7 Apache License 2.0", "https://developer.android.com/jetpack/androidx")
    CreditRow(vm, "Kotlin & kotlinx.coroutines", "JetBrains \u00b7 Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines")
    CreditRow(vm, "OkHttp", "Square \u00b7 Apache License 2.0", "https://square.github.io/okhttp/")
    CreditRow(vm, "Coil (images, SVG and GIF)", "Coil contributors \u00b7 Apache License 2.0", "https://coil-kt.github.io/coil/")
    CreditRow(vm, "AndroidSVG", "Cave Rock Software \u00b7 Apache License 2.0", "https://bigbadaboom.github.io/androidsvg/")
    CreditRow(vm, "Apache License 2.0", "The full licence text for the libraries above.", "https://www.apache.org/licenses/LICENSE-2.0")
    }, {
    CreditHeader("System icons")
    CreditRow(vm, "ColorGrade", "By grvceless. Included with permission.")

    CreditHeader("Game data and artwork")
    CreditRow(vm, "Hasheous", "Recognises games from their ROM files.", "https://hasheous.org")
    CreditRow(vm, "LaunchBox Games Database", "Artwork, descriptions and arcade titles.", "https://gamesdb.launchbox-app.com")
    CreditRow(vm, "SteamGridDB", "Community artwork: box art, backdrops and logos.", "https://www.steamgriddb.com")
    CreditRow(vm, "IGDB", "Artwork and descriptions (a Twitch service).", "https://www.igdb.com")
    CreditRow(vm, "libretro thumbnails", "Box art, screenshots and logos from the libretro-thumbnails project.", "https://github.com/libretro-thumbnails/libretro-thumbnails")
    CreditRow(vm, "ArcadeDB", "Arcade artwork and game histories, by Arcade Italia.", "https://adb.arcadeitalia.net")
    CreditRow(vm, "GameTDB", "GameCube, Wii, DS and 3DS covers and disc art.", "https://www.gametdb.com")
    CreditRow(vm, "RetroAchievements", "Achievements and progress.", "https://retroachievements.org")
    CreditRow(vm, "RomM", "Your own game library server, by the RomM project.", "https://github.com/rommapp/romm")
    CreditRow(
        vm,
        "Artwork and game names",
        "Box art, logos, screenshots and descriptions belong to their publishers and creators. They're fetched from the " +
            "services above for your own library and aren't part of Eclipse."
    )

    CreditHeader("Themes")
    CreditRow(
        vm,
        "Imported themes",
        "Themes you import (ES-DE, RetroHrai, Cocoon and others) keep their authors' own licences; check each theme's terms."
    )

    CreditHeader("Trademarks")
    CreditRow(
        vm,
        "Not affiliated",
        "Nintendo, PlayStation, Sega, Xbox, Steam and every other system, company and game name are trademarks of their " +
            "owners. They're used only to identify systems and games. Eclipse isn't affiliated with or endorsed by any of them."
    )
    })
}

@Composable
private fun CreditHeader(text: String) {
    DropSection(text)
}

/** One credit. Focusable so a controller can scroll through the page; with a [url], A or a tap opens it. */
@Composable
private fun CreditRow(vm: EclipseVM, title: String, text: String, url: String? = null) {
    SetRow(onClick = { url?.let { vm.openUrl(it) } }) {
        RowText(title, text, Modifier.weight(1f))
        if (url != null) Text("\u2197", color = C.accent, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * While [active] (a section that has just opened), keeps the whole thing on screen: as it grows, the list scrolls up
 * with it, so what opened below the bottom edge comes into view instead of staying hidden.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Modifier.keepInView(active: Boolean): Modifier {
    val req = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val on by rememberUpdatedState(active)
    LaunchedEffect(active) {
        if (active) { withFrameNanos { }; runCatching { req.bringIntoView() } }
    }
    return this
        .bringIntoViewRequester(req)
        .onSizeChanged { if (on) scope.launch { runCatching { req.bringIntoView() } } }
}





/** Settings → Library → Hidden games: every game you've hidden, each with Unhide. Works with the controller. */
@Composable
fun HiddenGamesSheet(vm: EclipseVM) {
    val hidden = vm.games.filter { it.id in vm.hiddenGames }.sortedBy { it.name.lowercase() }
    val first = remember { FocusRequester() }
    FocusOnShow(first, hidden.size)
    MenuSheet("Library", "Hidden games", if (hidden.isEmpty()) "Nothing is hidden." else "${hidden.size} hidden. Unhide one to put it back in your library.", onDismiss = { vm.hiddenScreen = false }) {
        Column(Modifier.heightIn(max = 260.dp).menuScroll(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            hidden.forEachIndexed { i, g ->
                MenuRow(g.name, value = "Unhide", note = Platforms.byId(g.platform)?.name, fr = if (i == 0) first else null) { vm.setHidden(g, false) }
            }
            MenuRow("Done", value = "B", fr = if (hidden.isEmpty()) first else null) { vm.hiddenScreen = false }
        }
    }
}
