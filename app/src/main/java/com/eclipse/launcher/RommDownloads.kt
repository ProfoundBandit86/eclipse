package com.eclipse.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateOf
import com.eclipse.launcher.data.RomM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * RomM downloads, one after another in the background, with a progress notification.
 * Keeps going while you play something else: a small foreground service holds the app open until the queue is empty.
 */
object RommDownloads {
    enum class State { QUEUED, DOWNLOADING, FAILED }

    data class Item(
        val rom: RomM.Rom,
        val dest: File,
        val playWhenDone: Boolean,
        val state: State = State.QUEUED,
        val progress: Float = -1f // 0..1, or -1 while the size is unknown
    )

    /** The queue, observable from Compose (first item = the one downloading). */
    val items = mutableStateOf<List<Item>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var worker: Job? = null
    @Volatile private var cancelId: Int? = null

    /** Called on the main thread when a file has arrived: (rom, file to launch, play it now?). */
    var onDone: ((RomM.Rom, File, Boolean) -> Unit)? = null
    var onFailed: ((RomM.Rom) -> Unit)? = null

    fun stateOf(romId: Int): Item? = items.value.firstOrNull { it.rom.id == romId }

    fun enqueue(ctx: Context, conn: RomM.Conn, rom: RomM.Rom, dest: File, playWhenDone: Boolean) {
        val cur = items.value
        val existing = cur.firstOrNull { it.rom.id == rom.id }
        items.value = when {
            existing == null -> cur + Item(rom, dest, playWhenDone)
            existing.state == State.FAILED -> cur.map { if (it.rom.id == rom.id) Item(rom, dest, playWhenDone) else it } // retry
            else -> cur.map { if (it.rom.id == rom.id) it.copy(playWhenDone = it.playWhenDone || playWhenDone) else it }
        }
        RommDownloadService.start(ctx)
        if (worker?.isActive != true) worker = scope.launch { run(ctx.applicationContext, conn) }
    }

    fun cancel(romId: Int) {
        val item = stateOf(romId) ?: return
        if (item.state == State.DOWNLOADING) cancelId = romId
        else items.value = items.value.filter { i -> i.rom.id != romId }
    }

    /** Clears failed entries from the list. */
    fun dismiss(romId: Int) { items.value = items.value.filter { it.rom.id != romId } }

    private suspend fun run(ctx: Context, conn: RomM.Conn) {
        while (true) {
            val next = items.value.firstOrNull { it.state == State.QUEUED } ?: break
            update(next.rom.id) { it.copy(state = State.DOWNLOADING, progress = -1f) }
            RommDownloadService.refresh(ctx)
            cancelId = null
            val file = RomM.download(conn, next.rom, next.dest, cancelled = { cancelId == next.rom.id }) { p ->
                scope.launch {
                    update(next.rom.id) { it.copy(progress = p) }
                    RommDownloadService.refresh(ctx)
                }
            }
            val wasCancelled = cancelId == next.rom.id
            cancelId = null
            when {
                wasCancelled -> items.value = items.value.filter { it.rom.id != next.rom.id }
                file == null -> {
                    update(next.rom.id) { it.copy(state = State.FAILED) }
                    onFailed?.invoke(next.rom)
                }
                else -> {
                    items.value = items.value.filter { it.rom.id != next.rom.id }
                    onDone?.invoke(next.rom, file, next.playWhenDone)
                    RommDownloadService.finished(ctx, next.rom.name)
                }
            }
        }
        RommDownloadService.refresh(ctx) // nothing left: the service stops itself
    }

    private fun update(id: Int, f: (Item) -> Item) {
        items.value = items.value.map { if (it.rom.id == id) f(it) else it }
    }
}

/** Shows the download notification and keeps Eclipse running while RomM games download. */
class RommDownloadService : Service() {
    companion object {
        private const val CHANNEL = "romm_downloads"
        private const val NOTE_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_CANCEL = "com.eclipse.launcher.CANCEL_DOWNLOAD"
        @Volatile private var running = false
        private var lastNote = 0L

        fun start(ctx: Context) {
            if (running) { refresh(ctx); return }
            val i = Intent(ctx, RommDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                // background start not allowed right now: downloads still run while Eclipse is open
            }
        }

        /** Redraws the notification (at most a few times a second), or stops the service when the queue is empty. */
        fun refresh(ctx: Context) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (RommDownloads.items.value.none { it.state != RommDownloads.State.FAILED }) {
                if (running) ctx.stopService(Intent(ctx, RommDownloadService::class.java))
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastNote < 400) return
            lastNote = now
            if (running) runCatching { nm.notify(NOTE_ID, build(ctx)) }
        }

        fun finished(ctx: Context, name: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            channel(nm)
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            runCatching {
                nm.notify(
                    DONE_ID,
                    b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Downloaded $name")
                        .setContentText("Ready to play in Eclipse")
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .build()
                )
            }
        }

        private fun channel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "RomM downloads", NotificationManager.IMPORTANCE_LOW))
            }
        }

        private fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            channel(nm)
            val list = RommDownloads.items.value
            val cur = list.firstOrNull { it.state == RommDownloads.State.DOWNLOADING } ?: list.firstOrNull { it.state == RommDownloads.State.QUEUED }
            val waiting = list.count { it.state == RommDownloads.State.QUEUED && it !== cur }
            val pct = cur?.progress ?: -1f
            val cancel = PendingIntent.getService(
                ctx, 0, Intent(ctx, RommDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx)
            return b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (cur != null) "Downloading ${cur.rom.name}" else "RomM downloads")
                .setContentText(
                    (if (pct >= 0f) "${(pct * 100).toInt()}%" else "Starting…") + if (waiting > 0) "  ·  $waiting more queued" else ""
                )
                .setProgress(100, if (pct >= 0f) (pct * 100).toInt() else 0, pct < 0f)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel), "Cancel", cancel).build())
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            RommDownloads.items.value.firstOrNull { it.state == RommDownloads.State.DOWNLOADING }?.let { RommDownloads.cancel(it.rom.id) }
            return START_NOT_STICKY
        }
        val note = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTE_ID, note)
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

/** Permission prompts that need the activity (set up in MainActivity). */
object PermissionAsk {
    /** Asks once for notification permission (Android 13+), so download progress can be shown. */
    var notifications: (() -> Unit)? = null
}
