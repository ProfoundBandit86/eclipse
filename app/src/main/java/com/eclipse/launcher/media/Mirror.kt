package com.eclipse.launcher.media

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screen mirror: a live copy of the top screen on the bottom screen (the whole thing, or a zoomed-in part of it).
 *
 * Android's screen capture (MediaProjection) makes an invisible copy of the top display (a virtual display) and draws
 * every frame of it straight into a surface on the bottom screen's Mirror page. The GPU does the copying, so it costs
 * about a frame of lag. Android asks for permission each time it starts, and requires a foreground service (with its
 * notification) while it runs. Apps that protect their content (DRM video, some secure screens) come out black.
 */
object Mirror {
    val running = MutableStateFlow(false)
    /** Size of the top screen in pixels: the size of each mirrored frame. */
    val size = MutableStateFlow(1920 to 1080)

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private val main = Handler(Looper.getMainLooper())

    /** Asks Android for permission (its consent dialog shows on the top screen), then starts mirroring. */
    fun request(ctx: Context) {
        val i = Intent(ctx, MirrorPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(Screens.mainId).toBundle()
        try { ctx.startActivity(i, opts) } catch (e: Exception) { ctx.startActivity(i) }
    }

    fun stop(ctx: Context) {
        val p = projection
        if (p != null) p.stop() else release(ctx)
    }

    /** The bottom screen's view is ready to show frames (or has gone away: [detach]). */
    fun attach(s: Surface) { surface = s; display?.surface = s }
    fun detach(s: Surface) { if (surface === s) { surface = null; display?.surface = null } }

    internal fun start(ctx: Context, resultCode: Int, data: Intent) {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(resultCode, data) } catch (e: Exception) { null }
        if (p == null) { release(ctx); return }
        projection = p
        // (Android 14 requires the callback before the virtual display is made)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { release(ctx) } }
        }, main)
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Screens.mainId)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        size.value = m.widthPixels to m.heightPixels
        display = try {
            p.createVirtualDisplay(
                "EclipseMirror", m.widthPixels, m.heightPixels, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        } catch (e: Exception) { null }
        if (display == null) { p.stop(); return }
        running.value = true
    }

    private fun release(ctx: Context) {
        display?.release()
        display = null
        projection = null
        running.value = false
        ctx.stopService(Intent(ctx, MirrorService::class.java))
    }
}

/** Keeps screen capture allowed while the mirror runs (Android requires one, with a notification). */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Mirror.stop(this); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen mirror", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(com.eclipse.launcher.R.drawable.ic_launcher_monochrome)
            .setContentTitle("Mirroring the top screen")
            .setContentText("Shown on the bottom screen. Tap Stop to end it.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data != null && !Mirror.running.value) Mirror.start(this, code, data)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.eclipse.launcher.MIRROR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "mirror"
        private const val NOTIF_ID = 7
    }
}

/** An invisible page that shows Android's "start capturing?" dialog, hands the answer to [MirrorService] and closes. */
class MirrorPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), 42)
        } catch (e: Exception) {
            Toast.makeText(this, "Screen capture isn't available on this device", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        finish()
    }
}
