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
 * (the card on its right moves in), Down puts it back. B stays where you were; A (or the button again) switches.
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
        when (code) {
            // past either end of the row: Home
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> when {
                s.flying -> {}
                s.sel > 0 -> { s.sel--; s.expanded = false }
                repeat == 0 -> finish(svc, switch = false, goHome = true)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> when {
                s.flying -> {}
                s.sel < s.apps.lastIndex -> { s.sel++; s.expanded = false }
                repeat == 0 -> finish(svc, switch = false, goHome = true)
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (repeat == 0 && !s.flying) { if (!s.expanded) s.expanded = true else dismiss(svc) }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (repeat == 0) s.expanded = false
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> finish(svc, switch = false)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> finish(svc, switch = true)
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
        // (Eclipse itself never gets a card)
        val pkgs = mru.filter { it != svc.packageName && pm.getLaunchIntentForPackage(it) != null }.take(MAX_APPS)
        if (pkgs.isEmpty()) return
        val st = UiState()
        st.current = mru.firstOrNull()
        pkgs.forEachIndexed { i, p ->
            val label = if (p == svc.packageName) "Eclipse" else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            val icon = runCatching { iconBitmap(pm.getApplicationIcon(p))?.asImageBitmap() }.getOrNull()
            if (p == st.current) seenAt[p] = now
            st.apps.add(Entry(p, label, icon, seenAt[p] ?: 0L))
        }
        state = st
        // the pictures saved last time (the one on screen gets a fresh one below, if Android allows it this soon)
        Thread {
            for (e in st.apps) {
                val f = shotFile(svc, e.pkg)
                if (!f.isFile) continue
                val b = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: continue
                main.post { if (st.shots[e.pkg] == null) st.shots[e.pkg] = b.asImageBitmap() }
            }
        }.start()
        // a fresh picture of the app on screen now (before the overlay covers it)
        capture(svc, st.apps.first().pkg, st)
        addOverlay(svc, st)
    }

    private fun capture(svc: AccessibilityService, pkg: String, st: UiState?) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            svc.takeScreenshot(Screens.mainId, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hw = result.hardwareBuffer
                    val full = try { Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Throwable) { null } finally { runCatching { hw.close() } }
                    if (full == null) return
                    val w = 720
                    val small = runCatching { Bitmap.createScaledBitmap(full, w, (full.height * w / full.width.toFloat()).toInt().coerceAtLeast(1), true) }.getOrNull() ?: full
                    if (small !== full) runCatching { full.recycle() }
                    st?.shots?.set(pkg, small.asImageBitmap())
                    Thread { runCatching { shotFile(svc, pkg).apply { parentFile?.mkdirs() }.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) } } }.start()
                }
                override fun onFailure(errorCode: Int) {}
            })
        }
    }

    private fun shotFile(ctx: Context, pkg: String) = File(File(ctx.cacheDir, "switcher"), "$pkg.jpg")

    private fun iconBitmap(d: android.graphics.drawable.Drawable): Bitmap? = runCatching {
        val size = 144
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        d.setBounds(0, 0, size, size); d.draw(c); b
    }.getOrNull()

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
            setContent { SwitcherUi(st, onGone = { removeChosen() }) { i -> st.sel = i; finish(svc, switch = true) } }
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
    private fun SwitcherUi(st: UiState, onGone: () -> Unit, onTap: (Int) -> Unit) {
        val gold = ThemeColors.accent.value
        val ring = ThemeColors.focus.value
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(320)) }
        // the chosen card's lift: 0 in the row, 1 pushed up (Up), 1.7 gone off the top (Up again)
        val lift = remember { Animatable(0f) }
        LaunchedEffect(st.expanded, st.flying) {
            when {
                st.flying -> { lift.animateTo(1.7f, tween(210)); lift.snapTo(0f); onGone() }
                st.expanded -> lift.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 380f))
                else -> lift.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 420f))
            }
        }
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
            val topAlpha = appear.value * (1f - lift.value.coerceIn(0f, 1f) * 0.85f)
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
                    val alpha = (if (chosen) 1f - (lift.value - 1f).coerceIn(0f, 0.7f) / 0.7f else 0.85f) * (if (i == 0) 1f else appear.value) * (1f - 0.55f * leave)
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
            // the chosen app's name, and when it was last in front
            st.apps.getOrNull(st.sel)?.let { e ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp).graphicsLayer { alpha = appear.value },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        e.icon?.let { Image(it, null, Modifier.size(22.dp).clip(RoundedCornerShape(11.dp))) }
                        Text(e.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 7.dp))
                    }
                    Text(
                        if (st.closing) "CLOSING\u2026" else if (st.expanded) "UP AGAIN TO CLOSE  ·  DOWN TO KEEP" else if (e.pkg == st.current) "JUST NOW" else ago(e.time, now),
                        color = Color(0xFFC8CAD2), fontSize = 8.5.sp, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // where you are in the row
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                st.apps.indices.forEach { i ->
                    Box(Modifier.size(if (i == st.sel) 16.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == st.sel) gold else Color(0x59FFFFFF)))
                }
            }
        }
    }

    private fun ago(t: Long, now: Long): String {
        if (t <= 0L) return "EARLIER"
        val m = (now - t) / 60_000
        return when { m < 1 -> "JUST NOW"; m < 60 -> "$m MIN AGO"; m < 1440 -> "${m / 60} H AGO"; else -> "${m / 1440} D AGO" }
    }
}
