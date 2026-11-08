package com.eclipse.launcher.media

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Accessibility service whose only job is taking screenshots of the main (top) screen on request,
 * e.g. from the Screenshot button on the bottom screen's Now Playing page. It reads no screen content
 * and listens to no events. The user turns it on once in Android's Accessibility settings.
 *
 * Android 11+: captures just the main display and saves a PNG to Pictures/Eclipse, named after the game.
 * Android 9-10: falls back to the regular system screenshot (saved by the system to Pictures/Screenshots).
 */
class ScreenshotService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: ScreenshotService? = null

        /** Turns key catching on or off after the switcher button is chosen or cleared. */
        fun refreshKeyFilter() {
            val s = instance ?: return
            runCatching {
                s.serviceInfo = s.serviceInfo.apply {
                    flags = if (AppSwitcher.keyCode(s) != 0 || AppSwitcher.learning != null)
                        flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    else flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
                }
            }
        }

        /**
         * A context to start apps from. Android lets an accessibility service start activities even while Eclipse is in
         * the background (a game on the top screen), where its own launches from the bottom screen can be blocked
         * without an error. Null when the helper is off.
         */
        fun starter(): android.content.Context? = instance

        /** Observable from Compose: true while the user has the service turned on. */
        val ready = mutableStateOf(false)

        // ---- dock-app watch: which app we opened on the bottom screen, so we can bring the dock back when it closes
        @Volatile private var watchPkg: String? = null
        @Volatile private var watchDisplay = -1
        @Volatile private var watchSeen = false
        @Volatile private var onWatchedClosed: (() -> Unit)? = null

        /** Package of every app that comes to the front on the main (top) screen. Used to notice a game closing. */
        @Volatile var onMainForeground: ((String) -> Unit)? = null

        /** An app opened from the bottom-screen dock is being watched (it hasn't closed yet). */
        val watching: Boolean get() = watchPkg != null

        /** True once the watched app has actually appeared on its screen. */
        val watchedAppSeen: Boolean get() = watchSeen

        /** Returns false (arming nothing) when the service is off or Android is older than 11. */
        fun startWatch(pkg: String, displayId: Int, onClosed: () -> Unit): Boolean {
            if (instance == null || Build.VERSION.SDK_INT < 30) return false
            watchPkg = pkg
            watchDisplay = displayId
            watchSeen = false
            onWatchedClosed = onClosed
            return true
        }

        fun stopWatch() {
            watchPkg = null
            watchDisplay = -1
            watchSeen = false
            onWatchedClosed = null
        }

        sealed class Result {
            data class Saved(val uri: Uri) : Result()
            object SystemShot : Result()   // the OS saved it in its own screenshot folder
            object Failed : Result()
        }

        /** Returns false (and does nothing) when the service isn't turned on. [onDone] runs on the main thread. */
        fun capture(ctx: Context, gameName: String?, onDone: (Result) -> Unit): Boolean {
            val s = instance ?: return false
            val main = Handler(Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 30) {
                s.takeScreenshot(Screens.mainId, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        // nothing in here may take the launcher down: any problem just reports "Screenshot failed"
                        try {
                            val hw = result.hardwareBuffer
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(hw, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Throwable) { null } finally { runCatching { hw.close() } }
                            if (bmp == null) { onDone(Result.Failed); return }
                            main.post { s.flash() } // after the capture, so the flash isn't in the picture
                            Thread {
                                val uri = try { save(ctx.applicationContext, bmp, gameName) } catch (e: Throwable) { null }
                                runCatching { bmp.recycle() }
                                main.post { onDone(if (uri != null) Result.Saved(uri) else Result.Failed) }
                            }.start()
                        } catch (e: Throwable) {
                            onDone(Result.Failed)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        // e.g. called again too quickly, or a secure (DRM) window on screen: try the system shot
                        onDone(if (s.systemShot()) Result.SystemShot else Result.Failed)
                    }
                })
                return true
            }
            onDone(if (s.systemShot()) Result.SystemShot else Result.Failed)
            return true
        }

        /** Each game gets its own folder: Pictures/Eclipse/<game>/<game>_<date>.png (no game running: Pictures/Eclipse/Eclipse). */
        private fun save(ctx: Context, bmp: Bitmap, game: String?): Uri? {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            // a name that's safe as a folder and a file name on any storage (keeps spaces, brackets and dashes)
            val base = (game ?: "Eclipse").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).ifEmpty { "Eclipse" }
            val folder = base
            val name = "${base.replace(' ', '_')}_$stamp.png"
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val r = ctx.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Eclipse/" + folder)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                    r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    r.update(uri, values, null, null)
                    uri
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Eclipse"), folder)
                    dir.mkdirs()
                    val f = File(dir, name)
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Uri.fromFile(f)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * A camera-style flash over the top screen: a white layer that fades out in about a third of a second. It's an
     * accessibility overlay, so it shows over the game without any extra permission, and ignores touches.
     */
    private fun flash() {
        val wm = try { getSystemService(WINDOW_SERVICE) as android.view.WindowManager } catch (e: Throwable) { return }
        val v = android.view.View(this).apply { setBackgroundColor(android.graphics.Color.WHITE); alpha = 0f }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        try { wm.addView(v, lp) } catch (e: Throwable) { return }
        val remove = Runnable { try { if (v.isAttachedToWindow) wm.removeView(v) } catch (e: Throwable) { } }
        try {
            v.animate().alpha(0.85f).setDuration(60).withEndAction {
                try { v.animate().alpha(0f).setDuration(320).withEndAction(remove).start() } catch (e: Throwable) { remove.run() }
            }.start()
        } catch (e: Throwable) { remove.run() }
        // and in any case, gone within a second
        Handler(Looper.getMainLooper()).postDelayed(remove, 1000)
    }

    private fun systemShot(): Boolean =
        Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    override fun onServiceConnected() {
        super.onServiceConnected()
        // catch key presses only once a switcher button is chosen (Settings → General → App switcher)
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = if (AppSwitcher.keyCode(this@ScreenshotService) != 0 || AppSwitcher.learning != null)
                    flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                else flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            }
        }
        instance = this
        ready.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        ready.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        ready.value = false
        super.onDestroy()
    }

    /**
     * Only the package name of the app that comes to the front is used (no screen content is read):
     * once the dock-opened app has appeared on the bottom screen, anything else taking that screen means it closed.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val onMain = Build.VERSION.SDK_INT < 30 || event.displayId == Screens.mainId
        if (onMain) event.packageName?.toString()?.let { p ->
            onMainForeground?.invoke(p)
            // the app switcher's list of recent apps (names only)
            if (!AppSwitcher.isOpen && !ForceStop.busy) runCatching { AppSwitcher.sawForeground(this, p) }
        }
        if (ForceStop.busy) return // the switcher's App info pages flashing by
        val watched = watchPkg ?: return
        if (Build.VERSION.SDK_INT >= 30 && event.displayId != watchDisplay) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == watched) {
            watchSeen = true
            return
        }
        if (!watchSeen) return
        // pop-ups that sit on top of the app don't mean it closed
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("permissioncontroller")) return
        val cb = onWatchedClosed
        stopWatch()
        cb?.invoke()
    }
    override fun onInterrupt() {}

    /** The app switcher's button, and the D-pad while it's open (every other press goes on to the app as usual). */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        if (event == null) return false
        return try { AppSwitcher.onKey(this, event) } catch (e: Throwable) { false }
    }
}
