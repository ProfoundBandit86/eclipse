package com.eclipse.launcher.media

import com.eclipse.launcher.ui.softBlur

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.eclipse.launcher.MainActivity
import com.eclipse.launcher.ui.ThemeColors
import java.io.File
import kotlin.math.abs
import kotlin.math.sign

/**
 * The app switcher: hold the button you picked (Settings → General → App switcher) anywhere, and the app on the
 * top screen shrinks into a big card in the middle with your other recent apps peeking in at the sides.
 * Left / Right (or L1 / R1) choose; past either end goes Home. Up pushes the chosen card up, Up again closes that app
 * (the card on its right moves in), Down puts it back. Down on a card in the row shows the swap: the card sinks part
 * way off the bottom and the app on the bottom screen zooms out into a card above it; Down again (or A) swaps the two
 * apps' screens, still running (see [Privileged]); Up or B goes back to the row. B stays where you were; A (or the
 * button again) switches.
 *
 * Runs inside the Eclipse helper (the accessibility service): it catches the button while open, and the overlay
 * takes focus to read the D-pad (a joystick hat on the Thor). It keeps the list of recent apps from the apps it
 * sees come to the front (only their names). Android doesn't share other apps' recent-app pictures, so each card
 * shows the picture taken the last time that app was in front, or the app's icon.
 */
object AppSwitcher {
    private const val PREFS = "eclipse"
    private const val KEY = "switcher_key"
    private const val MRU = "switcher_mru"
    private const val MAX_APPS = 12

    /** The chosen button's key code, 0 = switcher off. */
    /** The button that opens the switcher: Back until you choose another (or turn it off: 0). */
    fun keyCode(ctx: Context): Int {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return android.view.KeyEvent.KEYCODE_BACK
        return v.toIntOrNull() ?: 0 // ("" was how "off" used to be kept)
    }
    /** Holding Home swaps the apps on the two screens (Settings → App switcher); on by default. */
    fun homeSwap(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(HOME_SWAP, true)
    fun setHomeSwap(ctx: Context, on: Boolean) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(HOME_SWAP, on).apply() }
    private const val HOME_SWAP = "home_swap"
    /** Whether the helper needs to see key presses at all (the switcher's button, choosing one, or Home held). */
    fun wantsKeys(ctx: Context): Boolean = keyCode(ctx) != 0 || learning != null || homeSwap(ctx)

    fun setKeyCode(ctx: Context, code: Int) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code.toString()).apply() }
    fun keyName(code: Int): String = if (code == 0) "Off" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')

    /** Set by Settings while it waits for you to press the button to use: the next press goes here instead. */
    @Volatile var learning: ((Int) -> Unit)? = null

    // ---- recent apps, newest first (package names), from the apps the helper sees come to the front
    private val mru = ArrayList<String>()
    private var mruLoaded = false

    private fun loadMru(ctx: Context) {
        if (mruLoaded) return
        mruLoaded = true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MRU, "")?.split('\n')?.filter { it.isNotBlank() }?.let { mru.addAll(it) }
    }

    /**
     * The helper saw [pkg] come to the front on the top screen. A moment later (once it has drawn), a small picture
     * of it is saved for its card, so every app you've used has one, not just the one you opened the switcher in.
     */
    fun sawForeground(svc: AccessibilityService, pkg: String) {
        val ctx: Context = svc
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("permissioncontroller")) return
        if (pkg != ctx.packageName && ctx.packageManager.getLaunchIntentForPackage(pkg) == null) return
        loadMru(ctx)
        seenAt[pkg] = System.currentTimeMillis()
        main.removeCallbacks(snapLater)
        snapPkg = pkg
        snapSvc = svc
        main.postDelayed(snapLater, 2500)
        if (mru.firstOrNull() == pkg) return
        mru.remove(pkg); mru.add(0, pkg)
        while (mru.size > 30) mru.removeAt(mru.lastIndex)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MRU, mru.joinToString("\n")).apply()
    }

    private fun forget(ctx: Context, pkg: String) {
        mru.remove(pkg)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MRU, mru.joinToString("\n")).apply()
    }

    // ---- what the overlay shows
    class Entry(val pkg: String, val label: String, val icon: ImageBitmap?, val time: Long)
    private class UiState {
        val apps = mutableStateListOf<Entry>()
        var sel by mutableIntStateOf(0)
        /** the chosen card is lifted (Up); Up again closes the app */
        var expanded by mutableStateOf(false)
        /** the chosen card is flying off the top (closing) */
        var flying by mutableStateOf(false)
        /** leaving, while closed apps are still being stopped underneath */
        var closing by mutableStateOf(false)
        /** the app on screen when the switcher opened */
        var current: String? = null
        /** Down once: the top app's card sinks part way down and the bottom screen's app zooms out above it */
        var preview by mutableStateOf(false)
        /** the app that would go down to the bottom screen, and the one that would come up (either may be missing) */
        var down by mutableStateOf<Entry?>(null)
        var up by mutableStateOf<Entry?>(null)
        /** Down again: the swap is happening (the cards finish their moves, then the switcher closes) */
        var sinking by mutableStateOf(false)
        /** apps running on the bottom screen right now (Down moves them up instead) */
        val bottom = mutableStateListOf<String>()
        /** the bottom screen's display, when there is one */
        var bottomId: Int? = null
        val shots = mutableStateMapOf<String, ImageBitmap>()
    }

    private var state: UiState? = null
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    // apps closed in the switcher this time (stopped for real when it closes)
    private val closed = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    private val seenAt = HashMap<String, Long>()

    val isOpen: Boolean get() = view != null

    // the picture taken a moment after an app comes to the front
    private var snapPkg: String? = null
    private var snapSvc: AccessibilityService? = null
    private val snapLater = Runnable {
        val svc = snapSvc ?: return@Runnable
        val pkg = snapPkg ?: return@Runnable
        if (isOpen || mru.firstOrNull() != pkg) return@Runnable // moved on already, or the switcher is up
        capture(svc, pkg, null)
    }

    // the let-go of the press that chose the button (see [onKey])
    private var swallowUp = 0

    // hold-to-open
    private const val HOLD_MS = 350L
    private var holdOpened = false
    private var ignoreUp = false
    private var holdSvc: AccessibilityService? = null
    private val holdOpen = Runnable {
        val svc = holdSvc ?: return@Runnable
        if (isOpen) return@Runnable
        holdOpened = true
        show(svc)
    }

    /** A quick press of the switcher's button: its usual job, for the buttons a helper can redo. */
    private fun quickPress(svc: AccessibilityService, code: Int) {
        val action = when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> AccessibilityService.GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BUTTON_MODE -> AccessibilityService.GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return
        }
        runCatching { svc.performGlobalAction(action) }
    }


    /**
     * Called by the helper for every key press on the device. Returns true when the press is the switcher's (it then
     * doesn't reach the app).
     */
    fun onKey(svc: AccessibilityService, e: KeyEvent): Boolean {
        learning?.let { cb ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { learning = null; swallowUp = e.keyCode; main.post { cb(e.keyCode) } }
            return true
        }
        // the let-go of the press that chose the button: not a "quick press" (that would go Back in Settings)
        if (e.keyCode == swallowUp) { if (e.action == KeyEvent.ACTION_UP) swallowUp = 0; return true }
        val code = keyCode(svc)
        // Home (or the controller's Home button) held: the apps on the two screens swap places; a quick press is Home
        if ((e.keyCode == KeyEvent.KEYCODE_HOME || e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) && e.keyCode != code && !isOpen &&
            homeSwap(svc) && Screens.companion(svc) != null) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                    homeLong = false; homeSvc = svc
                    main.removeCallbacks(homeHold); main.postDelayed(homeHold, HOME_HOLD_MS)
                }
                KeyEvent.ACTION_UP -> {
                    main.removeCallbacks(homeHold)
                    if (!homeLong) main.post { runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } }
                    homeLong = false
                }
            }
            return true
        }
        if (code == 0) return false
        if (e.keyCode == code) {
            // holding it opens the switcher; pressing it again while open switches (B stays where you were);
            // a quick press does the button's usual job where Android lets a helper do it (Back, Home, Recents)
            when (e.action) {
                KeyEvent.ACTION_DOWN -> when {
                    e.repeatCount == 0 && isOpen -> { ignoreUp = true; main.post { finish(svc, switch = true) } }
                    e.repeatCount == 0 -> { holdOpened = false; holdSvc = svc; main.removeCallbacks(holdOpen); main.postDelayed(holdOpen, HOLD_MS) }
                    // key repeats only come while it's held: open now if the timer hasn't yet
                    !isOpen && !holdOpened && !ignoreUp -> { main.removeCallbacks(holdOpen); holdOpen.run() }
                }
                KeyEvent.ACTION_UP -> {
                    main.removeCallbacks(holdOpen)
                    when {
                        ignoreUp -> ignoreUp = false
                        !holdOpened && !isOpen -> main.post { quickPress(svc, code) }
                    }
                    holdOpened = false
                }
            }
            return true
        }
        if (!isOpen) return false
        if (e.action == KeyEvent.ACTION_DOWN) main.post { press(svc, e.keyCode, e.repeatCount) }
        // everything else is swallowed while the switcher is up
        return true
    }

    /** One press while the switcher is up (from the helper's key filter, or the overlay's own D-pad reading). */
    private fun press(svc: AccessibilityService, code: Int, repeat: Int = 0) {
        val s = state ?: return
        if (s.closing) return
        if (s.sinking) return
        when (code) {
            // past either end of the row: Home (from the swap view, Left / Right first go back to the row)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> when {
                s.flying -> {}
                s.preview -> s.preview = false
                s.sel > 0 -> { s.sel--; s.expanded = false }
                repeat == 0 -> finish(svc, switch = false, goHome = true)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> when {
                s.flying -> {}
                s.preview -> s.preview = false
                s.sel < s.apps.lastIndex -> { s.sel++; s.expanded = false }
                repeat == 0 -> finish(svc, switch = false, goHome = true)
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (repeat == 0 && !s.flying) when {
                s.preview -> s.preview = false
                !s.expanded -> s.expanded = true
                else -> dismiss(svc)
            }
            // Down: a lifted card goes back into the row; a card in the row shows the swap (the bottom screen's app
            // above it); Down again swaps the two screens' apps
            KeyEvent.KEYCODE_DPAD_DOWN -> if (repeat == 0 && !s.flying) when {
                s.expanded -> s.expanded = false
                s.preview -> swap(svc)
                else -> showSwap(svc)
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (s.preview) s.preview = false else finish(svc, switch = false)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (s.preview) swap(svc) else finish(svc, switch = true)
            else -> {}
        }
    }

    // last D-pad hat / stick direction seen by the overlay (a press fires when it moves off centre)
    private var hatX = 0
    private var hatY = 0

    /**
     * The Thor's D-pad comes in as a joystick hat, not as key presses, so the helper's key filter never sees it.
     * The overlay takes focus and reads the hat (and the left stick) itself.
     */
    private fun onMotion(svc: AccessibilityService, ev: MotionEvent): Boolean {
        val joystick = ev.isFromSource(InputDevice.SOURCE_JOYSTICK) || ev.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!joystick || ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        fun dir(v: Float) = if (v > 0.5f) 1 else if (v < -0.5f) -1 else 0
        val hx = dir(ev.getAxisValue(MotionEvent.AXIS_HAT_X))
        val hy = dir(ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        val x = if (hx != 0) hx else dir(ev.getAxisValue(MotionEvent.AXIS_X))
        val y = if (hy != 0) hy else dir(ev.getAxisValue(MotionEvent.AXIS_Y))
        if (x != hatX) {
            hatX = x
            if (x < 0) press(svc, KeyEvent.KEYCODE_DPAD_LEFT) else if (x > 0) press(svc, KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        if (y != hatY) {
            hatY = y
            if (y < 0) press(svc, KeyEvent.KEYCODE_DPAD_UP) else if (y > 0) press(svc, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        return true
    }

    private fun show(svc: AccessibilityService) {
        if (view != null) return
        loadMru(svc)
        val pm = svc.packageManager
        val now = System.currentTimeMillis()
        // apps running on the bottom screen get a card too (right after the one on top), so Down can bring them up
        val bottomId = Screens.companion(svc)?.displayId
        val onBottom = if (bottomId != null) appsOnDisplay(svc, bottomId) else emptyList()
        // (Eclipse itself never gets a card)
        val pkgs = (mru.take(1) + onBottom + mru.drop(1)).distinct().filter { it != svc.packageName && pm.getLaunchIntentForPackage(it) != null }.take(MAX_APPS)
        if (pkgs.isEmpty()) return
        val st = UiState()
        st.current = mru.firstOrNull()
        st.bottomId = bottomId
        st.bottom.addAll(onBottom)
        pkgs.forEach { p ->
            if (p == st.current) seenAt[p] = now
            st.apps.add(entry(svc, p))
        }
        state = st
        // the pictures taken earlier: from memory, else from the copies saved in Eclipse's private cache (kept until
        // Eclipse is closed, see [clearShots]). The one on screen gets a fresh one below, if Android allows it this soon.
        for (e in st.apps) memShots[e.pkg]?.let { if (st.shots[e.pkg] == null) st.shots[e.pkg] = it }
        memShots.keys.retainAll(st.apps.map { it.pkg }.toSet())
        val need = st.apps.map { it.pkg }.filter { !memShots.containsKey(it) }
        Thread {
            for (pkg in need) {
                val f = shotFile(svc, pkg)
                if (!f.isFile) continue
                val b = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: continue
                main.post { val img = b.asImageBitmap(); if (!memShots.containsKey(pkg)) memShots[pkg] = img; if (st.shots[pkg] == null) st.shots[pkg] = img }
            }
        }.start()
        // a fresh picture of the app on screen now (before the overlay covers it), but only when that app has a card:
        // opened from Eclipse (which has none), the screen shows Eclipse, not the first card's app
        val onScreen = st.current
        if (onScreen != null && onScreen != svc.packageName && st.apps.any { it.pkg == onScreen }) capture(svc, onScreen, st)
        // and of the app on the bottom screen (the switcher doesn't cover it), for its card in the swap view
        if (bottomId != null) onBottom.firstOrNull()?.let { capture(svc, it, st, bottomId) }
        addOverlay(svc, st)
    }

    private fun entry(svc: AccessibilityService, p: String): Entry {
        val pm = svc.packageManager
        val label = if (p == svc.packageName) "Eclipse" else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        val icon = runCatching { iconBitmap(pm.getApplicationIcon(p))?.asImageBitmap() }.getOrNull()
        return Entry(p, label, icon, seenAt[p] ?: 0L)
    }

    private fun capture(svc: AccessibilityService, pkg: String, st: UiState?, display: Int = Screens.mainId) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            svc.takeScreenshot(display, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val full = try { Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Throwable) { null } finally { runCatching { hw.close() } }
                    if (full == null) return
                    val w = 720
                    val small = runCatching { Bitmap.createScaledBitmap(full, w, (full.height * w / full.width.toFloat()).toInt().coerceAtLeast(1), true) }.getOrNull() ?: full
                    if (small !== full) runCatching { full.recycle() }
                    val img = small.asImageBitmap()
                    memShots[pkg] = img
                    st?.shots?.set(pkg, img)
                    Thread { runCatching { shotFile(svc, pkg).apply { parentFile?.mkdirs() }.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) } } }.start()
                }
                override fun onFailure(errorCode: Int) {}
            })
        }
    }

    /** The cards' pictures in memory; each is also saved in Eclipse's private cache until Eclipse is closed. */
    private val memShots = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    private fun shotFile(ctx: Context, pkg: String) = File(File(ctx.cacheDir, "switcher"), "$pkg.jpg")

    /** Eclipse was closed: forget every card picture, in memory and on storage. */
    fun clearShots(ctx: Context) {
        Handler(Looper.getMainLooper()).post { memShots.clear() }
        Thread { runCatching { File(ctx.cacheDir, "switcher").deleteRecursively() } }.start()
    }

    private fun iconBitmap(d: android.graphics.drawable.Drawable): Bitmap? = runCatching {
        val size = 144
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, size, size); d.draw(c); b
    }.getOrNull()

    // Home held
    private const val HOME_HOLD_MS = 500L
    private var homeLong = false
    private var homeSvc: AccessibilityService? = null
    private val homeHold = Runnable {
        homeLong = true
        homeSvc?.let { swapScreens(it) }
    }

    /**
     * Home held: the app on the top screen goes to the bottom one and the app on the bottom screen comes up, both
     * still running (see [Privileged]). With only one of them, just that one moves. Eclipse's own screens stay put.
     */
    private fun swapScreens(svc: AccessibilityService) {
        val bottomId = Screens.companion(svc)?.displayId ?: return
        val top = appsOnDisplay(svc, Screens.mainId).firstOrNull()
        val bottom = appsOnDisplay(svc, bottomId).firstOrNull()
        if (top == null && bottom == null) { toast(svc, "No app on either screen to swap"); return }
        moveApps(svc, top, bottom, bottomId)
    }

    /** [top] goes down to the bottom screen and [bottom] comes up to the top one, both still running. */
    private fun moveApps(svc: AccessibilityService, top: String?, bottom: String?, bottomId: Int) {
        val ctx = svc.applicationContext
        Thread {
            val down = top?.let { Privileged.moveTask(ctx, it, bottomId, Screens.mainId) }
            val up = bottom?.let { Privileged.moveTask(ctx, it, Screens.mainId, bottomId) }
            main.post {
                // Eclipse's bottom page steps aside for the app now down there, or comes back when it went up alone
                if (down is Privileged.Moved.Ok && top != null) {
                    runCatching { SecondScreenHost.hideForApp?.invoke(top, bottomId); ScreenshotService.markWatchedSeen() }
                } else if (up is Privileged.Moved.Ok) runCatching { SecondScreenHost.appLeft?.invoke() }
                val fail = listOfNotNull(down, up).firstOrNull { it !is Privileged.Moved.Ok }
                when (fail) {
                    is Privileged.Moved.NoRights -> toast(ctx, "Swapping screens needs Shizuku, AYN's root service (Settings \u2192 App switcher) or a rooted device")
                    is Privileged.Moved.Failed -> toast(ctx, if (fail.why == "not running") "That app isn't running" else "Couldn't swap the screens: ${fail.why}")
                    else -> {}
                }
            }
        }.start()
    }

    /** The apps with a window on display [id] (package names only), newest first. Android 11+. */
    private fun appsOnDisplay(svc: AccessibilityService, id: Int): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val ws = runCatching { svc.windowsOnAllDisplays.get(id) }.getOrNull() ?: return emptyList()
        return ws.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { w ->
            runCatching { w.root?.let { r -> r.packageName?.toString().also { @Suppress("DEPRECATION") r.recycle() } } }.getOrNull()
        }.filter { it != svc.packageName && it != "com.android.systemui" }.distinct()
    }

    /**
     * Down on a card in the row: the swap view. The chosen card sinks part way off the bottom and the app on the
     * bottom screen zooms out into a card above it (when one is running there). If the chosen card is itself the
     * bottom screen's app, it's the one that comes up and the top screen's app is the one that sinks.
     */
    private fun showSwap(svc: AccessibilityService) {
        val s = state ?: return
        val e = s.apps.getOrNull(s.sel) ?: return
        if (s.bottomId == null) { toast(svc, "There's no second screen to move it to"); return }
        if (e.pkg == svc.packageName) return
        if (e.pkg in s.bottom) {
            s.up = e
            s.down = s.current?.takeIf { it != svc.packageName && it != e.pkg && it !in s.bottom }?.let { p -> s.apps.firstOrNull { it.pkg == p } ?: entry(svc, p) }
        } else {
            s.down = e
            s.up = s.bottom.firstOrNull { it != e.pkg }?.let { p -> s.apps.firstOrNull { it.pkg == p } ?: entry(svc, p) }
        }
        s.preview = true
    }

    /** Down again in the swap view: the cards finish their moves, then the apps swap screens (see [swapNow]). */
    private fun swap(svc: AccessibilityService) {
        val s = state ?: return
        if (s.down == null && s.up == null) { s.preview = false; return }
        moveSvc = svc
        s.sinking = true
    }
    private var moveSvc: AccessibilityService? = null

    /**
     * After the swap animation: the switcher closes and the two apps change screens, still running. On the bottom
     * screen Eclipse's own page steps aside (and comes back when the app there closes or moves up again).
     */
    private fun swapNow() {
        val svc = moveSvc ?: return
        val s = state ?: return
        val bottomId = s.bottomId ?: return
        val down = s.down?.pkg
        val up = s.up?.pkg
        if (closed.isEmpty() && !ForceStop.busy) removeOverlay(svc) else finish(svc, switch = false)
        moveApps(svc, down, up, bottomId)
    }

    private fun toast(ctx: Context, text: String) { runCatching { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show() } }

    /** Up on a lifted card: it flies off the top, then the app is closed (see [removeChosen]). */
    private fun dismiss(svc: AccessibilityService) {
        val s = state ?: return
        if (s.apps.isEmpty()) return
        if (s.apps[s.sel].pkg == svc.packageName) { s.expanded = false; return } // Eclipse itself stays
        flySvc = svc
        s.flying = true
    }
    private var flySvc: AccessibilityService? = null

    /** After the fly-away: the card goes, the app is stopped, and the card on its right takes its place. */
    private fun removeChosen() {
        val svc = flySvc ?: return
        val s = state ?: return
        s.flying = false
        if (s.apps.isEmpty()) return
        val e = s.apps[s.sel]
        s.apps.removeAt(s.sel)
        forget(svc, e.pkg)
        memShots.remove(e.pkg)
        runCatching { shotFile(svc, e.pkg).delete() }
        // stopped for real straight away, underneath the switcher (ForceStop: App info → Force stop)
        if (e.pkg !in closed) closed.add(e.pkg)
        ForceStop.enqueue(svc, e.pkg)
        s.expanded = false
        s.sel = s.sel.coerceIn(0, (s.apps.size - 1).coerceAtLeast(0))
        if (s.apps.isEmpty()) finish(svc, switch = false, goHome = true)
    }

    private fun finish(svc: AccessibilityService, switch: Boolean, goHome: Boolean = false) {
        val s = state
        if (s?.closing == true) return
        val target = if (switch && s != null) s.apps.getOrNull(s.sel)?.pkg else null
        val current = s?.current ?: mru.firstOrNull()
        val killed = closed.toList()
        closed.clear()
        if (killed.isEmpty() && !ForceStop.busy) {
            removeOverlay(svc)
            when {
                goHome -> runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
                target != null && target != current -> launch(svc, target)
            }
            return
        }
        // apps were closed: the switcher stays on top (fading its cards) until the last one is stopped, then goes
        // where you asked; App info was opened underneath, so even "stay" puts your app back in front
        s?.closing = true
        val back = target ?: current?.takeIf { !goHome }
        ForceStop.whenIdle {
            if (back != null && back !in killed) launch(svc, back)
            else runCatching { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
            // let the app come up under the switcher before it goes
            main.postDelayed({ removeOverlay(svc) }, 220)
        }
    }

    private fun launch(ctx: Context, pkg: String) {
        val intent = if (pkg == ctx.packageName) Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        else ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) ?: return
        val opts: Bundle? = ForceStop.quietOptions(ctx)
        if (pkg != ctx.packageName) {
            // counts as Eclipse launching something: the bottom screen stays when Eclipse goes to the background, and a
            // game you'd left gets its Now Playing back
            SecondScreenHost.lastLaunchMs = System.currentTimeMillis()
            runCatching { SecondScreenHost.onSwitch?.invoke(pkg) }
        }
        runCatching { ctx.startActivity(intent, opts) }
    }

    // ---- the overlay window
    private fun addOverlay(svc: AccessibilityService, st: UiState) {
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val o = OverlayOwner()
        val v = ComposeView(svc).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { SwitcherUi(st, onGone = { removeChosen() }, onSunk = { swapNow() }) { i -> st.sel = i; finish(svc, switch = true) } }
            // focusable, so the D-pad's joystick hat reaches it (see onMotion)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnGenericMotionListener { _, ev -> onMotion(svc, ev) }
            // D-pad keys made from the hat by Android, if any get this far (the key filter normally takes them first)
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN) press(svc, code, ev.repeatCount)
                true
            }
        }
        hatX = 0; hatY = 0
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(v, lp) } catch (e: Throwable) { state = null; return }
        o.resume()
        view = v; owner = o
        v.post { v.requestFocus() }
    }

    private fun removeOverlay(ctx: Context) {
        val v = view ?: return
        view = null
        state = null
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { if (v.isAttachedToWindow) wm.removeView(v) }
        owner?.destroy(); owner = null
    }

    /** Lifecycle for the Compose view in the overlay (it has no activity). */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        init { saved.performRestore(null); registry.currentState = Lifecycle.State.CREATED }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }

    // ---- the look (the mock-up): the current app zooms out into a row of cards, the chosen one in gold
    @Composable
    private fun SwitcherUi(st: UiState, onGone: () -> Unit, onSunk: () -> Unit, onTap: (Int) -> Unit) {
        val gold = ThemeColors.accent.value
        val ring = ThemeColors.focus.value
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(320)) }
        // the chosen card's lift: 0 in the row, 1 pushed up (Up), 1.7 gone off the top (Up again), -1.3 sunk off the
        // bottom (Down: moving to the other screen)
        val lift = remember { Animatable(0f) }
        LaunchedEffect(st.expanded, st.flying) {
            when {
                st.flying -> { lift.animateTo(1.7f, tween(210)); lift.snapTo(0f); onGone() }
                st.expanded -> lift.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f))
                else -> lift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
            }
        }
        // the swap view: 0 the row, 1 the top app's card sunk part way with the bottom app's card above it,
        // 2 swapping (the sunk card leaves off the bottom, the other one grows to fill the screen)
        val swapP = remember { Animatable(0f) }
        LaunchedEffect(st.preview, st.sinking) {
            when {
                st.sinking -> { swapP.animateTo(2f, tween(260)); onSunk() }
                st.preview -> swapP.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 360f))
                else -> swapP.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
            }
        }
        val p1 = swapP.value.coerceIn(0f, 1f)
        val p2 = (swapP.value - 1f).coerceIn(0f, 1f)
        val now = remember { System.currentTimeMillis() }
        // leaving while closed apps are still being stopped: the cards dim
        val leave by animateFloatAsState(if (st.closing) 1f else 0f, tween(200), label = "swLeave")
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f * appear.value))) {
            val w = maxWidth; val h = maxHeight
            // behind: Eclipse's own wallpaper, blurred and dimmed
            coil.compose.AsyncImage(
                "file:///android_asset/wallpapers/eclipse-top.jpg", null,
                Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.softBlur(26.dp), contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x400A0A0C), Color(0xE00A0A0C)))))
            // top: RECENT APPS and the clock (fade while a card is lifted over them)
            val topAlpha = appear.value * (1f - lift.value.coerceIn(0f, 1f) * 0.85f) * (1f - p2)
            Row(Modifier.padding(start = 24.dp, top = 18.dp).graphicsLayer { alpha = topAlpha }, verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT APPS", color = gold, fontSize = 9.sp, letterSpacing = 2.sp)
                Box(Modifier.padding(start = 10.dp).width(120.dp).height(1.dp).background(Color(0x1FFFFFFF)))
            }
            Text(
                java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(java.util.Date(now)),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 24.dp).graphicsLayer { alpha = topAlpha }
            )
            // the cards: the chosen one big in the middle, its neighbours peeking in at the screen's edges
            val cardW = w * 0.60f; val cardH = h * 0.60f
            val side = 0.74f                                   // neighbours' size
            val peek = w * 0.075f                              // how much of a neighbour shows
            val step1 = w / 2 + cardW * side / 2 - peek        // centre of a neighbour from the middle
            st.apps.forEachIndexed { i, e ->
                key(e.pkg) {
                    // each card glides to its place (also when one is closed and the next moves in)
                    val d by animateFloatAsState((i - st.sel).toFloat(), spring(dampingRatio = 0.85f, stiffness = 420f), label = "swCard")
                    val a = abs(d)
                    val chosen = i == st.sel
                    var scale = if (a <= 1f) 1f - (1f - side) * a else side
                    // opening: the app you were in starts full screen and shrinks into its card
                    if (i == 0) scale += (1f / 0.60f - scale) * (1f - appear.value)
                    val x = if (a <= 1f) step1 * d else (step1 + w * 0.45f * (a - 1f)) * sign(d)
                    val y = -(h * 0.03f) - (if (chosen) h * 0.50f * lift.value else 0.dp)
                    val alpha = (if (chosen) (1f - (lift.value - 1f).coerceIn(0f, 0.7f) / 0.7f) * (1f - (-lift.value - 0.6f).coerceIn(0f, 0.7f) / 0.7f) else 0.85f) * (if (i == 0) 1f else appear.value) * (1f - 0.55f * leave) * (1f - p1)
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.align(Alignment.Center).offset(x = x, y = y).size(cardW, cardH).zIndex(if (chosen) 2f else -a)
                            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                            .then(if (chosen) Modifier.shadow(30.dp, shape, ambientColor = ring, spotColor = ring) else Modifier)
                            .clip(shape)
                            .background(Brush.linearGradient(listOf(Color(0xFF1C1C22), Color(0xFF0B0B0D))))
                            .border(if (chosen) 3.dp else 2.dp, if (chosen) ring else gold.copy(alpha = 0.55f), shape)
                            .pointerInput(i) { detectTapGestures { onTap(i) } },
                        contentAlignment = Alignment.Center
                    ) {
                        val shot = st.shots[e.pkg]
                        if (shot != null) Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else e.icon?.let { Image(it, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))) }
                        // side cards sit a little darker
                        if (!chosen) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (0.35f * a).coerceAtMost(0.35f))))
                    }
                }
            }
            // the swap view's two cards: the one going down sinks part way off the bottom; the one coming up zooms
            // out of the full screen into a card above it (a chosen card starts from its place in the row)
            if (p1 > 0f) {
                val chosenPkg = st.apps.getOrNull(st.sel)?.pkg
                st.down?.let { e ->
                    val fromRow = e.pkg == chosenPkg
                    val y = -(h * 0.03f) + h * 0.65f * p1 + h * 0.5f * p2
                    val scale = 1f - 0.1f * p1
                    SwapCard(st, e, cardW, cardH, y, scale, (if (fromRow) 1f else p1) * (1f - p2), chosen = false, dim = 0.4f * p1,
                        tag = "\u25BC  TO THE BOTTOM SCREEN")
                }
                st.up?.let { e ->
                    val fromRow = e.pkg == chosenPkg
                    val start = if (fromRow) 1f else 1f / 0.60f
                    val rest = 0.85f
                    val scale = start + (rest - start) * p1 + (1f / 0.60f - rest) * p2
                    val y = -(h * (if (fromRow) 0.03f else 0f)) * (1f - p1) - h * 0.12f * p1 * (1f - p2)
                    SwapCard(st, e, cardW, cardH, y, scale, if (fromRow) 1f else p1, chosen = true, dim = 0f,
                        tag = if (p2 > 0f) null else "\u25B2  TO THE TOP SCREEN")
                }
            }
            // the chosen app's name, and when it was last in front (in the swap view: the app coming up, and what
            // Down again does), lifted above the sunk card
            (if (p1 > 0f) st.up ?: st.down else st.apps.getOrNull(st.sel))?.let { e ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp).offset(y = -(56.dp * p1)).graphicsLayer { alpha = appear.value * (1f - p2) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        e.icon?.let { Image(it, null, Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))) }
                        Text(e.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                    }
                    Text(
                        when {
                            st.closing -> "CLOSING\u2026"
                            st.sinking -> "SWAPPING\u2026"
                            st.preview -> (when {
                                st.up != null && st.down != null -> "DOWN AGAIN: SWAP SCREENS"
                                st.up != null -> "DOWN AGAIN: TO THE TOP SCREEN"
                                else -> "DOWN AGAIN: TO THE BOTTOM SCREEN"
                            }) + "  ·  UP: BACK"
                            st.expanded -> "UP AGAIN TO CLOSE  ·  DOWN TO KEEP"
                            else -> (if (e.pkg in st.bottom) "ON THE BOTTOM SCREEN" else if (e.pkg == st.current) "JUST NOW" else ago(e.time, now)) +
                                if (st.bottomId != null) "  ·  DOWN: SWAP SCREENS" else ""
                        },
                        color = Color(0xFFC8CAD2), fontSize = 8.5.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // where you are in the row
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).graphicsLayer { alpha = 1f - p1 }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                st.apps.indices.forEach { i ->
                    Box(Modifier.size(if (i == st.sel) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == st.sel) gold else Color(0x59FFFFFF)))
                }
            }
        }
    }

    /** A card in the swap view: the app's picture (or icon), with a small tag along its top saying where it's going. */
    @Composable
    private fun BoxWithConstraintsScope.SwapCard(
        st: UiState, e: Entry, cardW: androidx.compose.ui.unit.Dp, cardH: androidx.compose.ui.unit.Dp,
        y: androidx.compose.ui.unit.Dp, scale: Float, alpha: Float, chosen: Boolean, dim: Float, tag: String?,
    ) {
        val gold = ThemeColors.accent.value
        val ring = ThemeColors.focus.value
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier.align(Alignment.Center).offset(y = y).size(cardW, cardH).zIndex(if (chosen) 4f else 3f)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .then(if (chosen) Modifier.shadow(30.dp, shape, ambientColor = ring, spotColor = ring) else Modifier)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF1C1C22), Color(0xFF0B0B0D))))
                .border(if (chosen) 3.dp else 2.dp, if (chosen) ring else gold.copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center
        ) {
            val shot = st.shots[e.pkg]
            if (shot != null) Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else e.icon?.let { Image(it, null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))) }
            if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            if (tag != null) Row(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC0A0A0C)).padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                e.icon?.let { Image(it, null, Modifier.size(14.dp).clip(RoundedCornerShape(7.dp))) }
                Text(e.label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 5.dp))
                Text(tag, color = gold, fontSize = 8.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    private fun ago(t: Long, now: Long): String {
        if (t <= 0L) return "EARLIER"
        val m = (now - t) / 60_000
        return when { m < 1 -> "JUST NOW"; m < 60 -> "$m MIN AGO"; m < 1440 -> "${m / 60} H AGO"; else -> "${m / 1440} D AGO" }
    }
}
