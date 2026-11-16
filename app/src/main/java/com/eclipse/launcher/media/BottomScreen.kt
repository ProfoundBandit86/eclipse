package com.eclipse.launcher.media

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * Eclipse's bottom screen, whichever window it lives in:
 *
 * - [ActivitySurface] (the default, Settings → Screens → "Apps cover the bottom screen"): an ordinary app screen,
 *   [BottomScreenActivity], in its own task on the bottom display. Android stacks app screens, so anything opened or
 *   moved there (by Eclipse, a game's own second screen, another app, a swap) simply sits on top of it, and when that
 *   closes or moves away Eclipse's screen is underneath again. Nothing has to hide or come back.
 * - [PresentationSurface]: the old overlay (an Android Presentation). Overlays are drawn above every app on their
 *   display, so it has to hide for apps and guess when to come back.
 */
interface BottomSurface {
    val isShowing: Boolean
    val displayId: Int
    /** True when apps opened on this screen cover it by themselves (nothing to hide or bring back). */
    val stacksUnderApps: Boolean
    fun show()
    fun hide()
    fun dismiss()
    fun applyFocusMode()
    /** Typing in a text box on this screen: let the window take focus (and the keyboard) while it's on. */
    fun setTyping(on: Boolean)
    /** Something was asked for on this screen (the Companion): bring it in front of any app there. */
    fun bringToFront() {}
}

/** The bottom screen now open, for the UI (typing, which display). */
object BottomScreen {
    @Volatile var current: BottomSurface? = null
}

/** The old overlay as a [BottomSurface]. */
class PresentationSurface(val p: SecondaryPresentation) : BottomSurface {
    override val isShowing: Boolean get() = p.isShowing
    override val displayId: Int get() = p.display.displayId
    override val stacksUnderApps = false
    override fun show() = p.show()
    override fun hide() = p.hide()
    override fun dismiss() = p.dismiss()
    override fun applyFocusMode() = p.applyFocusMode()
    override fun setTyping(on: Boolean) = p.setTyping(on)
}

/**
 * The bottom screen as an app screen. Its content, view model and controller rules come from MainActivity through
 * [content], [focusable] and [onLeave] (the activity itself is started by Android, so they're handed over here).
 */
class ActivitySurface(
    private val ctx: Context,
    override val displayId: Int,
    focusable: () -> Boolean,
    onLeave: () -> Unit,
    content: @Composable () -> Unit
) : BottomSurface {
    init {
        BottomScreenActivity.content = content
        BottomScreenActivity.focusable = focusable
        BottomScreenActivity.onLeave = onLeave
        // the screen is still open from before (Eclipse's main screen was recreated): show the new content in it
        BottomScreenActivity.live?.let { a -> a.runOnUiThread { runCatching { a.setContent { content() } } } }
    }

    private val live: BottomScreenActivity? get() = BottomScreenActivity.live?.takeIf { !it.isFinishing && !it.isDestroyed }
    // when it was asked to open and hasn't yet (Android takes a moment): counts as showing, so a second check right
    // after (Eclipse starting checks on create and again on resume) doesn't throw it away and start it over
    private var startedAt = 0L
    private val starting: Boolean get() = live == null && android.os.SystemClock.uptimeMillis() - startedAt < 3_000
    override val isShowing: Boolean get() = live != null || starting
    override val stacksUnderApps = true

    /** Starts the screen on its display, unless it's already there or on its way (never brought in front of an app). */
    override fun show() {
        if (live != null || starting) return
        startedAt = android.os.SystemClock.uptimeMillis()
        val i = Intent(ctx, BottomScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { ctx.startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) }.onFailure { startedAt = 0L }
    }

    /** Nothing to do: an app on this screen covers it by itself. */
    override fun hide() {}

    override fun dismiss() {
        startedAt = 0L
        live?.let { runCatching { it.finishAndRemoveTask() } }
        // (one still on its way finds no content and closes itself, unless a new surface has set it again)
        BottomScreenActivity.content = null
    }

    override fun applyFocusMode() { live?.applyFocusMode() }
    override fun setTyping(on: Boolean) { live?.setTyping(on) }

    override fun bringToFront() {
        if (live == null) { show(); return }
        val i = Intent(ctx, BottomScreenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { ctx.startActivity(i, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()) }
    }
}

/**
 * Eclipse's bottom screen as an app screen (see [ActivitySurface]). Touch only, like the overlay: its window never
 * takes the controller from the game on the top screen (unless Settings → Screens says the controller may come here),
 * and takes the keyboard only while a text box is being typed in. Back never closes it.
 */
class BottomScreenActivity : ComponentActivity() {
    companion object {
        @Volatile var live: BottomScreenActivity? = null
        @Volatile var content: (@Composable () -> Unit)? = null
        @Volatile var focusable: () -> Boolean = { false }
        @Volatile var onLeave: () -> Unit = {}
    }

    fun applyFocusMode() {
        runCatching {
            if (focusable()) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            } else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    fun setTyping(on: Boolean) {
        if (isFinishing || isDestroyed) return
        runCatching {
            if (on) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            else applyFocusMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // touch only from the very first frame, so opening it never takes the controller from the top screen
        applyFocusMode()
        super.onCreate(savedInstanceState)
        val c = content
        if (c == null) { finishAndRemoveTask(); return } // Eclipse isn't running its main screen: nothing to show
        live = this
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        // laid out inside the bottom screen's bars, over black, like the overlay
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        setContent { c() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            (window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content))?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        applyFocusMode() // (back to touch only, if a text box was being typed in when it went away)
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    /** Back never closes this screen: B goes back inside it, or (with the controller here) hands it back to the top. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && focusable()) {
                    if (onBackPressedDispatcher.hasEnabledCallbacks()) onBackPressedDispatcher.onBackPressed() else onLeave()
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(KeyEvent(
                event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source
            ))
        }
        return super.dispatchKeyEvent(event)
    }
}
