package com.eclipse.launcher.media

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Really closes apps for the app switcher. Android lets an ordinary app stop only processes that are already idle in
 * the background (killBackgroundProcesses), which most games and emulators aren't, so the helper does what you'd do by
 * hand: opens the app's App info page, presses Force stop and OK. It starts the moment you close a card, underneath
 * the switcher (which stays on top and hides it), so by the time you leave the switcher it's usually done.
 * Needs the helper to read the Settings window (canRetrieveWindowContent + flagRetrieveInteractiveWindows: the
 * switcher's own window has the focus, so Settings is found among the windows, not as the active one).
 */
object ForceStop {
    private val main = Handler(Looper.getMainLooper())
    private const val STEP_MS = 90L
    private const val GIVE_UP_MS = 3500L

    private val queue = ArrayDeque<String>()
    private val idle = ArrayList<() -> Unit>()
    private var svc: AccessibilityService? = null

    @Volatile var busy = false
        private set
    /** Set once App info has been opened: something needs to be put back in front afterwards. */
    var touchedSettings = false
        private set

    /** Adds [pkg] to the apps to stop; starts right away if nothing else is being stopped. */
    fun enqueue(s: AccessibilityService, pkg: String) {
        svc = s
        if (pkg !in queue) queue.addLast(pkg)
        if (!busy) { busy = true; mute(s, true); next() }
    }

    /** Runs [cb] once every queued app has been stopped (now, if none are). */
    fun whenIdle(cb: () -> Unit) {
        if (!busy) { cb(); touchedSettings = false } else idle.add(cb)
    }

    private fun next() {
        val s = svc
        val pkg = queue.removeFirstOrNull()
        if (pkg == null || s == null) {
            busy = false
            // the touch sounds come back once the last OK has made its (silent) click
            s?.let { main.postDelayed({ if (!busy) mute(it, false) }, 400) }
            val cbs = idle.toList(); idle.clear()
            cbs.forEach { runCatching { it() } }
            touchedSettings = false
            return
        }
        stopOne(s, pkg) {
            runCatching { (s.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
            next()
        }
    }

    // whether system sounds were already muted before we muted them (then they stay muted)
    private var wasMuted = false
    private var muted = false

    /** Mutes the system sounds while Force stop and OK are pressed (each press plays the touch click). */
    private fun mute(ctx: Context, on: Boolean) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            if (on && !muted) {
                wasMuted = am.isStreamMute(android.media.AudioManager.STREAM_SYSTEM)
                if (!wasMuted) am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_MUTE, 0)
                muted = true
            } else if (!on && muted) {
                if (!wasMuted) am.adjustStreamVolume(android.media.AudioManager.STREAM_SYSTEM, android.media.AudioManager.ADJUST_UNMUTE, 0)
                muted = false
            }
        }
    }

    /** No sliding pages: the switcher covers the screen, and nothing should move under it. */
    fun quietOptions(ctx: Context) = runCatching {
        ActivityOptions.makeCustomAnimation(ctx, 0, 0).setLaunchDisplayId(Screens.mainId).toBundle()
    }.getOrNull()

    private fun stopOne(svc: AccessibilityService, pkg: String, done: () -> Unit) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (runCatching { svc.startActivity(intent, quietOptions(svc)) }.isFailure) { done(); return }
        touchedSettings = true
        val settingsPkg = intent.resolveActivity(svc.packageManager)?.packageName ?: "com.android.settings"
        val stopLabel = settingsString(svc, settingsPkg, "force_stop") ?: "Force stop"
        val okLabels = listOfNotNull(settingsString(svc, settingsPkg, "dlg_ok"), svc.getString(android.R.string.ok), "OK")
        val start = System.currentTimeMillis()
        var pressedStop = false
        fun tick() {
            if (System.currentTimeMillis() - start > GIVE_UP_MS) { done(); return }
            val root = settingsRoot(svc, settingsPkg)
            if (root == null) { main.postDelayed({ tick() }, STEP_MS); return }
            if (!pressedStop) {
                val found = root.findAccessibilityNodeInfosByText(stopLabel)
                val btn = found.firstOrNull { it.text?.toString().equals(stopLabel, true) } ?: found.firstOrNull()
                if (btn == null) { main.postDelayed({ tick() }, STEP_MS); return }
                val target = clickable(btn)
                // greyed out: the app isn't running any more
                if (target == null || !target.isEnabled) { done(); return }
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                pressedStop = true
                main.postDelayed({ tick() }, STEP_MS)
                return
            }
            // the "Force stop?" question: press its OK
            val ok = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
                ?: okLabels.firstNotNullOfOrNull { l -> root.findAccessibilityNodeInfosByText(l).firstOrNull { it.text?.toString().equals(l, true) } }
            if (ok == null) { main.postDelayed({ tick() }, STEP_MS); return }
            clickable(ok)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            main.postDelayed({ done() }, 120)
        }
        main.postDelayed({ tick() }, 150)
    }

    /** The Settings window's content: among all windows (the switcher has the focus), else the active one. */
    private fun settingsRoot(svc: AccessibilityService, settingsPkg: String): AccessibilityNodeInfo? {
        val fromWindows = runCatching {
            svc.windows.mapNotNull { it.root }.firstOrNull { r ->
                val p = r.packageName?.toString() ?: ""
                p == settingsPkg || p.contains("settings")
            }
        }.getOrNull()
        return fromWindows ?: svc.rootInActiveWindow?.takeIf { (it.packageName?.toString() ?: "").let { p -> p == settingsPkg || p.contains("settings") } }
    }

    /** The node itself if it can be pressed, else the nearest parent that can. */
    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var depth = 0
        while (c != null && depth < 5) {
            if (c.isClickable) return c
            c = c.parent; depth++
        }
        return null
    }

    /** A string from the Settings app in the device's language ("Force stop", "OK"). */
    private fun settingsString(ctx: Context, pkg: String, name: String): String? = runCatching {
        val r = ctx.packageManager.getResourcesForApplication(pkg)
        val id = r.getIdentifier(name, "string", pkg)
        if (id == 0) null else r.getString(id)
    }.getOrNull()
}
