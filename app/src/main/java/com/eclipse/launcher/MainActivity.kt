package com.eclipse.launcher

import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.eclipse.launcher.data.Scanner
import com.eclipse.launcher.media.Screens
import com.eclipse.launcher.media.ScreenshotService
import com.eclipse.launcher.media.SecondScreenHost
import com.eclipse.launcher.media.SecondaryPresentation
import com.eclipse.launcher.ui.EclipseTheme
import com.eclipse.launcher.ui.CrashScreen
import com.eclipse.launcher.ui.GamepadFocus
import com.eclipse.launcher.ui.Root
import com.eclipse.launcher.ui.SecondaryContent
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: EclipseVM get() = (application as EclipseApp).vm

    // Bottom screen. A Presentation window sits above normal apps on that display, so the device's own
    // second-screen launcher can't cover it (that's what happened with a regular activity on the AYN Thor).
    private var presentation: SecondaryPresentation? = null
    private var hiddenForApp = false
    /** The second screen's display is off, so its window is hidden until it's back on. */
    private var offHidden = false
    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { if (!placeMain()) syncSecondScreen() }
        override fun onDisplayRemoved(displayId: Int) { if (!placeMain()) syncSecondScreen() }
        // a screen turned off or on (AYANEO's "main screen only" mode, the bottom screen's own off switch)
        override fun onDisplayChanged(displayId: Int) = screenStateChanged(displayId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.eclipse.launcher.media.UiSound.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Crashed last time? Show the report (copy / share) before starting the launcher again.
        val crashFile = java.io.File(filesDir, EclipseApp.CRASH_FILE)
        if (crashFile.exists()) {
            val report = try { crashFile.readText() } catch (e: Exception) { "Couldn't read the crash report: ${e.message}" }
            setContent {
                EclipseTheme {
                    CrashScreen(report) {
                        crashFile.delete()
                        recreate()
                    }
                }
            }
            return
        }

        // make the view model now, outside any snapshot: built later inside one (the "dual" watcher below reads it
        // first), its set-up writes crash with "Cannot modify a state object in a read-only snapshot"
        vm.hashCode()
        setContent {
            // Android's wallpaper (live ones too) behind Eclipse: the window lets it through
            androidx.compose.runtime.LaunchedEffect(vm.sysWall) {
                if (vm.sysWall) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                }
            }
            val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                val path = uri?.let { Scanner.treeToPath(it) }
                if (uri != null && path != null) {
                    // keep access to the folder link too: some emulators (Flycast) only open games through it
                    val kept = try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        true
                    } catch (e: Exception) { false }
                    vm.onFolderPicked(path, if (kept) uri.toString() else null)
                } else if (uri != null) { vm.folderTarget = null; vm.message = "Couldn't read that folder" }
                else vm.folderTarget = null // cancelled
            }
            val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                val plat = vm.pendingPlatform
                vm.pendingPlatform = null
                val platKind = vm.pendingPlatKind
                vm.pendingPlatKind = null
                val g = vm.artGame
                when {
                    uri == null -> Unit
                    plat != null && platKind != null -> vm.importPlatformKind(plat, platKind, uri)
                    plat != null -> vm.importPlatformArt(plat, uri)
                    g != null -> vm.importArt(g, vm.pendingKind, uri)
                }
            }
            val themeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importTheme(uri)
            }
            val wallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                val which = vm.pendingWallpaper
                vm.pendingWallpaper = null
                if (uri != null && which != null) vm.importWallpaper(which, uri)
            }
            com.eclipse.launcher.ui.Pickers.wallpaper = { which ->
                vm.pendingWallpaper = which
                wallLauncher.launch("image/*")
            }
            val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importFont(uri)
            }
            val soundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.importSound(uri)
            }
            com.eclipse.launcher.ui.Pickers.sound = { soundLauncher.launch(arrayOf("audio/*")) }
            com.eclipse.launcher.ui.Pickers.font = {
                fontLauncher.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream"))
            }
            val packLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val kind = vm.pendingPackKind
                vm.pendingPackKind = null
                if (uri != null && kind != null) vm.importPack(kind, uri)
            }
            com.eclipse.launcher.ui.Pickers.pack = { kind ->
                vm.pendingPackKind = kind
                packLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            val viewLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.installView(uri)
            }
            com.eclipse.launcher.ui.Pickers.view = {
                viewLauncher.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "application/octet-stream", "text/plain", "*/*"))
            }
            com.eclipse.launcher.ui.Pickers.theme = {
                themeLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            EclipseTheme {
                Root(
                    vm,
                    pickFolder = { folderLauncher.launch(null) },
                    pickImage = { imageLauncher.launch("image/*") }
                )
            }
        }

        // the main screen on the display it belongs on (screens swapped: the other one); then the second screen
        if (placeMain()) return
        // show / hide the second screen when the "dual" setting changes
        lifecycleScope.launch {
            snapshotFlow { vm.flag("dual") }.collect { syncSecondScreen() }
        }
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, null)
        SecondScreenHost.owner = this
        SecondScreenHost.hideForApp = { pkg, displayId -> hideForApp(pkg, displayId) }
        SecondScreenHost.close = { handler.post { closeSecondScreen() } }
        SecondScreenHost.restart = { handler.post { restartEclipse() } }
        SecondScreenHost.show = { handler.post { syncSecondScreen() } }
        SecondScreenHost.resync = {
            handler.post {
                gaveUpOn = null
                if (!placeMain()) {
                    // which display may have changed: start the second screen again on the right one
                    val want = secondDisplay()?.displayId
                    if (presentation != null && presentation?.display?.displayId != want) closeSecondScreen()
                    syncSecondScreen()
                    presentation?.applyFocusMode()
                }
            }
        }
        PermissionAsk.notifications = {
            val prefs = getSharedPreferences("asks", MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif", false) &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                prefs.edit().putBoolean("notif", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
            }
        }
    }

    /** Takes Eclipse off the bottom screen. It comes back by itself the next time Eclipse is in front. */
    private fun closeSecondScreen() {
        ScreenshotService.stopWatch()
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        presentation?.dismiss()
        presentation = null
        hiddenForApp = false
    }

    /**
     * Eclipse went to the background. If that's because it started a game or app (or one is running), the bottom
     * screen stays to show Now Playing. Otherwise you left Eclipse (another launcher, Recents, screen off), so the
     * bottom screen is handed back.
     */
    override fun onStop() {
        super.onStop()
        if (showingCrash || isChangingConfigurations) return
        val justLaunched = System.currentTimeMillis() - SecondScreenHost.lastLaunchMs < 5_000
        if (!justLaunched && !hiddenForApp && vm.session == null) closeSecondScreen()
    }

    /** True while the crash report is on screen (nothing else was started). */
    private val showingCrash: Boolean get() = java.io.File(filesDir, EclipseApp.CRASH_FILE).exists()

    /** The Android Home button while Eclipse is already open: close any menu and go back to On Deck. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (showingCrash) return
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            vm.dockMenu = null
            vm.appMenu = null
            vm.platformMenu = null
            vm.platArtScreen = null
            vm.emuMenu = null
            vm.sysEdit = null
            vm.viewMenu = false
            vm.searchOpen = false
            vm.onDeckRemove = null
            vm.deckDrawer = false
            vm.appsDrawer = false
            vm.platReorder = false
            vm.appearancePicker = null
            vm.colorPicker = null
            vm.storeDelete = null
            vm.gameDelete = null
            vm.smbBrowse = null
            vm.rommAsk = null
            vm.cancelPackImport()
            vm.homeSection = com.eclipse.launcher.data.HomeSection.RECENTS
            vm.go(com.eclipse.launcher.data.Screen.HOME)
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingCrash) return
        if (placeMain()) return
        vm.onResume()
        // Eclipse coming back on the top screen doesn't mean the app on the bottom screen closed: while the helper
        // is still watching that app, leave the bottom screen to it (it comes back when the app closes)
        if (!(hiddenForApp && ScreenshotService.watching)) restoreAfterApp()
        syncSecondScreen()
    }

    override fun onPause() {
        if (!showingCrash) vm.onPause()
        super.onPause()
    }

    private fun myDisplayId(): Int = if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: Display.DEFAULT_DISPLAY else Display.DEFAULT_DISPLAY

    /**
     * The other screen (on the AYN Thor: the bottom one). Real panels only, built-in before external (a TV), the one
     * picked in Settings → Dual screen first: see [Screens].
     */
    private fun secondDisplay(): Display? = Screens.companion(this, myDisplayId(), vm.secondName)

    /**
     * Puts the main screen where it belongs (Settings → Dual screen → Main screen): on Android's default display, or
     * on the other one when the screens are swapped (a handheld whose built-in screen is the bottom one, like those
     * wearing Retroid's clip-on screen). Returns true when this window is closing to reopen on the other display.
     * If Android puts it straight back, Eclipse stops trying (until the setting changes) and says so.
     */
    private fun placeMain(): Boolean {
        if (showingCrash) return false
        val mine = myDisplayId()
        Screens.mainId = mine
        if (Build.VERSION.SDK_INT < 30) return false
        val want = Screens.wantedMain(this, vm.screenRoles, vm.secondName)
        if (want == mine || want == gaveUpOn) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - movedAt < 6_000) {
            // reopened on the other display a moment ago and Android put it back here: don't loop
            gaveUpOn = want
            vm.message = "Android didn't let Eclipse open on the other screen. Settings \u2192 Dual screen \u2192 Main screen."
            return false
        }
        movedAt = now
        presentation?.dismiss()
        presentation = null
        val i = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(want).toBundle()
        finishAndRemoveTask()
        return runCatching { applicationContext.startActivity(i, opts); true }.getOrElse { gaveUpOn = want; false }
    }

    /** The second screen's display turned off: stop drawing it. Back on: show it again (unless an app is using it). */
    private fun screenStateChanged(id: Int) {
        val p = presentation ?: return
        if (p.display.displayId != id) return
        val off = p.display.state == Display.STATE_OFF
        if (off && p.isShowing) { runCatching { p.hide() }; offHidden = true }
        else if (!off && offHidden) {
            offHidden = false
            if (!hiddenForApp) runCatching { p.show() }
        }
    }

    /**
     * B on the bottom screen with nothing to go back from: the controller goes back to the main screen (bringing
     * Eclipse's window there to the front moves Android's key focus with it). Only while Eclipse is what's on the
     * main screen: during a game it would cover the game, so the device's own "switch screen" key does that instead.
     */
    private fun focusMain() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("focus_only", true)
        runCatching { startActivity(i, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(myDisplayId()).toBundle()) }
    }

    private fun syncSecondScreen() {
        val want = vm.flag("dual")
        val d = secondDisplay()
        if (!want || d == null) {
            presentation?.dismiss()
            presentation = null
            hiddenForApp = false
            return
        }
        val p = presentation
        if (p != null && p.display.displayId == d.displayId && (p.isShowing || hiddenForApp || offHidden)) return
        p?.dismiss()
        hiddenForApp = false
        offHidden = false
        presentation = try {
            SecondaryPresentation(
                this, d,
                focusable = { vm.flag("bottom_focus", Screens.bottomFocusDefault()) },
                onLeave = { handler.post { focusMain() } }
            ) { SecondaryContent(vm, d.displayId) }.also { it.show() }
        } catch (e: Exception) {
            vm.message = "Couldn't open the second screen: ${e.message}"
            null
        }
    }

    /** An app was opened from the bottom dock: step aside so it's visible, and come back when it closes. */
    private fun hideForApp(pkg: String, displayId: Int) {
        val p = presentation ?: return
        if (p.isShowing) p.hide()
        hiddenForApp = true
        // With Eclipse's accessibility helper on, we see the app close and return right away.
        // Without it, the dock comes back next time you return to Eclipse on the main screen.
        val watching = ScreenshotService.startWatch(pkg, displayId) { handler.post { restoreAfterApp() } }
        if (watching) {
            watchdog?.let { handler.removeCallbacks(it) }
            val w = Runnable {
                watchdog = null
                if (hiddenForApp && !ScreenshotService.watchedAppSeen) restoreAfterApp() // app never appeared
            }
            watchdog = w
            // (emulators can take a while to start: give it time before deciding it never opened)
            handler.postDelayed(w, 20_000)
        }
    }

    private fun restoreAfterApp() {
        ScreenshotService.stopWatch()
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
        if (!hiddenForApp) return
        hiddenForApp = false
        try { presentation?.show() } catch (e: Exception) { presentation = null }
    }

    /**
     * Settings → Reload Eclipse. The bottom screen goes first (a Presentation torn down by a dying process is what left
     * the strange colour), Android is asked to open Eclipse again in a moment (an alarm, so nothing is started from inside
     * the process that's ending: that's what froze and then force closed), then the process ends at once. As the home
     * app, Android also brings Eclipse back by itself.
     */
    private fun restartEclipse() {
        runCatching { presentation?.dismiss() }
        presentation = null
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) runCatching {
            val opts = if (Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            val pi = android.app.PendingIntent.getActivity(
                this, 7731, launch,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT, opts
            )
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager)
                .set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 700, pi)
        }
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onDestroy() {
        // only while this is the Eclipse screen the hooks belong to: one reopened on the other display (placeMain)
        // has set its own by the time this old one finishes closing, and they must stay
        val current = SecondScreenHost.owner === this
        // Eclipse closed for real (not just turned, restarted by Android or moved to the other display): the app
        // switcher's card pictures go
        if (current && isFinishing && !isChangingConfigurations) com.eclipse.launcher.media.AppSwitcher.clearShots(this)
        if (current) {
            SecondScreenHost.owner = null
            SecondScreenHost.hideForApp = null
            SecondScreenHost.close = null
            SecondScreenHost.restart = null
            SecondScreenHost.show = null
            SecondScreenHost.resync = null
        }
        presentation?.dismiss()
        presentation = null
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    /**
     * Gamepad: A = select / go forward, B = back, Select = context menu for whatever is highlighted
     * (game -> game menu, app -> app menu, dock app -> remove from dock).
     * There are no separate dialog windows anywhere, so every press arrives here.
     */
    /** Right joystick: remembered for [com.eclipse.launcher.ui.RightStick], which scrolls the open menu. (The left stick is left to Android, which turns it into D-pad presses.) */
    override fun dispatchGenericMotionEvent(e: android.view.MotionEvent): Boolean {
        if (e.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK) && e.action == android.view.MotionEvent.ACTION_MOVE) {
            val rz = e.getAxisValue(android.view.MotionEvent.AXIS_RZ)
            val ry = e.getAxisValue(android.view.MotionEvent.AXIS_RY)
            com.eclipse.launcher.ui.RightStick.y = if (kotlin.math.abs(rz) >= kotlin.math.abs(ry)) rz else ry
        }
        return super.dispatchGenericMotionEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // The setup wizard covers everything: if focus has slipped out of it onto the screen underneath (something
        // there asked for focus), a controller press first puts it back in the wizard instead of acting behind it.
        val modal = GamepadFocus.modal
        val menuOverWizard = vm.colorPicker != null || vm.appearancePicker != null || vm.searchOpen ||
            vm.menuGame != null || vm.artGame != null || vm.platArtScreen != null || vm.dockMenu != null || vm.platReorder ||
            vm.appMenu != null || vm.platformMenu != null || vm.emuMenu != null || vm.sysEdit != null || vm.viewMenu || vm.onDeckRemove != null ||
            vm.storeDelete != null || vm.gameDelete != null || vm.updateShowing || vm.smbBrowse != null || vm.packImport != null || vm.rommAsk != null || vm.themeImport != null
        if (modal != null && !menuOverWizard && !GamepadFocus.modalHasFocus && e.keyCode in MODAL_KEYS) {
            if (e.action == KeyEvent.ACTION_DOWN) runCatching { modal.requestFocus() }
            return true
        }
        // and the screens underneath don't get the shoulder / X / Y / Start / Select shortcuts while it's up
        if (modal != null && !menuOverWizard && e.keyCode in BEHIND_ONLY_KEYS) return true
        if (e.keyCode in DPAD_KEYS && e.action == KeyEvent.ACTION_DOWN) GamepadFocus.held = e.repeatCount > 0
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN) GamepadFocus.lastLeftMs = android.os.SystemClock.uptimeMillis()
        // the tour of Home keeps the controller: the shoulder and face buttons don't reach the screens behind it
        if (vm.tutorialOpen && e.keyCode in setOf(
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
            )) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(remap(e, KeyEvent.KEYCODE_DPAD_CENTER))
            KeyEvent.KEYCODE_BUTTON_B -> return super.dispatchKeyEvent(remap(e, KeyEvent.KEYCODE_BACK))
            // L bumper: the left dock. R bumper: the screen's right-hand drawer (wherever there is one).
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    // a game's page: the previous / next game in the list it was opened from
                    // a game's page: its tabs (L2 / R2 go to the previous / next game)
                    if (vm.menuGame != null && vm.artGame == null && vm.emuMenu == null) {
                        val d = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                        vm.menuTabStep(d)
                    }
                    // Tools (Settings → Library): its tabs
                    else if (vm.toolsOpen) GamepadFocus.pageTab?.invoke(if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1)
                    else if (!vm.setupOpen) {
                        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) (GamepadFocus.leftDock ?: GamepadFocus.dockOpener)?.invoke() else GamepadFocus.rightDock?.invoke()
                    }
                }
                return true
            }
            // L2 / R2: previous / next system on a system's games screen
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    val d = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L2) -1 else 1
                    if (vm.menuGame != null && vm.artGame == null && vm.emuMenu == null) vm.menuStep(d)
                    else if (!vm.setupOpen) GamepadFocus.shoulder?.invoke(d)
                }
                return true
            }
            // X / Y: the slide view's Details and Favourite (when a screen has set them)
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> {
                val hook = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_X) GamepadFocus.buttonX else GamepadFocus.buttonY
                if (hook != null) {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) hook()
                    return true
                }
            }
            // Start: the highlighted item's menu / info page
            KeyEvent.KEYCODE_BUTTON_START -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) GamepadFocus.context?.invoke()
                return true
            }
            // Select: the same, unless the item has its own Select action (On Deck: remove it)
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) (GamepadFocus.select ?: GamepadFocus.context)?.invoke()
                return true
            }
        }
        val handled = super.dispatchKeyEvent(e)
        // A D-pad press that nothing handled usually means nothing has focus yet: put focus somewhere useful.
        if (!handled && e.action == KeyEvent.ACTION_DOWN && e.keyCode in DPAD_KEYS) {
            GamepadFocus.rescue?.invoke()
            return true
        }
        return handled
    }

    private companion object {
        /** When the main screen last reopened on another display, and the display Android wouldn't let it open on. */
        var movedAt = 0L
        var gaveUpOn: Int? = null
        val MODAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        )
        val BEHIND_ONLY_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        )
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER
        )
    }

    private fun remap(e: KeyEvent, code: Int) = KeyEvent(
        e.downTime, e.eventTime, e.action, code, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source
    )
}
