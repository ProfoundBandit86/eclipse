package com.eclipse.launcher.media

import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
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
import com.eclipse.launcher.data.MediaInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** One notification, as the right-hand drawer shows it. */
data class Notif(
    val key: String, val pkg: String, val app: String, val title: String, val text: String, val time: Long,
    val clearable: Boolean, val intent: android.app.PendingIntent?
)

/**
 * The notification listener. Being enabled is what lets us read other apps' media sessions (Now Playing); it also
 * keeps the list of current notifications for the right-hand drawer, and can dismiss them.
 */
class MediaListener : NotificationListenerService() {
    companion object {
        /** Called when Android connects this listener (e.g. right after you grant notification access). */
        @Volatile
        var onConnected: (() -> Unit)? = null
        /** Current notifications, newest first (not ongoing ones like downloads or music, and not Eclipse's own). */
        val items = MutableStateFlow<List<Notif>>(emptyList())
        @Volatile private var instance: MediaListener? = null

        fun dismiss(n: Notif) { runCatching { instance?.cancelNotification(n.key) }; items.value = items.value.filter { it.key != n.key } }
        fun dismissAll() { runCatching { instance?.cancelAllNotifications() }; items.value = items.value.filter { !it.clearable } }
        fun open(ctx: Context, n: Notif) {
            val ok = runCatching { n.intent?.send() }.isSuccess && n.intent != null
            if (!ok) ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let { runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            if (n.clearable) dismiss(n)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        onConnected?.invoke()
        reload()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) = reload()
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) = reload()

    private fun reload() {
        val list = runCatching { activeNotifications }.getOrNull() ?: return
        val pm = packageManager
        items.value = list.asSequence()
            .filter { it.packageName != packageName && !it.isOngoing && (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 }
            .mapNotNull { sbn ->
                val ex = sbn.notification.extras
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                Notif(sbn.key, sbn.packageName, app, title, text, sbn.postTime, sbn.isClearable, sbn.notification.contentIntent)
            }
            .sortedByDescending { it.time }
            .toList()
    }
}

class MediaRepo(private val ctx: Context) {
    val info = MutableStateFlow<MediaInfo?>(null)
    val hasAccess = MutableStateFlow(false)

    private var controller: MediaController? = null
    private val component = ComponentName(ctx, MediaListener::class.java)
    private val msm get() = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }

    fun start() {
        MediaListener.onConnected = { android.os.Handler(android.os.Looper.getMainLooper()).post { start() } }
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            msm.addOnActiveSessionsChangedListener(sessionsListener, component)
            hasAccess.value = true
            refresh()
        } catch (e: SecurityException) {
            hasAccess.value = false
            info.value = null
        }
    }

    fun refresh() {
        val list = try { msm.getActiveSessions(component) } catch (e: SecurityException) {
            hasAccess.value = false
            return
        }
        controller?.unregisterCallback(callback)
        controller = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        controller?.registerCallback(callback)
        push()
    }

    private fun push() {
        val c = controller
        if (c == null) {
            info.value = null
            return
        }
        val m = c.metadata
        info.value = MediaInfo(
            title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty(),
            art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            active = c.playbackState?.state in setOf(
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS
            )
        )
    }

    fun toggle() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun prev() = controller?.transportControls?.skipToPrevious()
}

/**
 * Compose inside a Presentation (second display) needs its own lifecycle / saved-state / view-model owners,
 * because a Presentation is a Dialog and has none of the Activity's.
 */
class SecondaryPresentation(
    outer: Context,
    display: Display,
    /** May this screen take the controller (Settings → Dual screen → Controller on the bottom screen)? */
    private val focusable: () -> Boolean = { false },
    /** B on this screen with nothing to go back from: hand the controller back to the main screen. */
    private val onLeave: () -> Unit = {},
    private val content: @Composable () -> Unit
) : Presentation(outer, display), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    androidx.activity.OnBackPressedDispatcherOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vmStore
    // (BackHandler in the bottom screen's UI needs one of these, or it crashes the moment it's shown)
    private val backDispatcher = androidx.activity.OnBackPressedDispatcher(null)
    override val onBackPressedDispatcher: androidx.activity.OnBackPressedDispatcher get() = backDispatcher

    /**
     * The window's normal flags. Not focusable (the AYN Thor's way): the controller never comes here, taps don't take it
     * from the game. Focusable (devices with a "switch screen" key): the controller works here when the device sends it,
     * and B gives it back. Either way it stays behind the keyboard if the device shows one on this screen.
     */
    fun applyFocusMode() {
        val w = window ?: return
        runCatching {
            if (focusable()) {
                w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                w.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            } else w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    /** Typing in a text box on this screen: let the window take focus (and the keyboard) while it's on. */
    fun setTyping(on: Boolean) {
        val w = window ?: return
        // the screen may already be hidden or gone (an app opened on it, Eclipse closing): changing a detached
        // window's flags throws, and there's nothing to change then anyway
        if (!isShowing || !w.decorView.isAttachedToWindow) return
        runCatching {
            if (on) w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            else applyFocusMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A Presentation is a Dialog: by default a tap gives it focus and the next Back dismisses it for good.
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        // Touch-only surface by default (the controller stays on the main screen); see [applyFocusMode]
        applyFocusMode()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        val decor = window!!.decorView
        decor.setViewTreeLifecycleOwner(this)
        decor.setViewTreeViewModelStoreOwner(this)
        decor.setViewTreeSavedStateRegistryOwner(this)
        decor.setViewTreeOnBackPressedDispatcherOwner(this)
        current = this
        // Keep everything clear of the bottom screen's navigation bar (and any status bar): the window gets the bars'
        // sizes and the UI is laid out inside them, over black, instead of underneath them.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window!!, false)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        val view = ComposeView(context).apply {
            setContent {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides this@SecondaryPresentation
                ) { content() }
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        // back to the normal flags: if a search box was being typed in when this screen last went away, the window
        // could be left able to take key presses from the game on the top screen
        applyFocusMode()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        if (current === this) current = null
        registry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        super.onStop()
    }

    /**
     * Back must never close the second screen. With the controller here: A presses the highlighted thing (like on the
     * main screen), B goes back inside this screen (a drawer, a sheet) or, with nothing to go back from, hands the
     * controller back to the main screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && focusable()) {
                    if (backDispatcher.hasEnabledCallbacks()) backDispatcher.onBackPressed() else onLeave()
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

    @Deprecated("Dialog still routes Back through this on some versions")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // deliberately empty
    }

    companion object {
        /** The bottom screen now showing (for [setTyping]). */
        @Volatile var current: SecondaryPresentation? = null
    }
}

/** Lets the second-screen UI ask MainActivity to step aside for an app opened on that screen. */
object SecondScreenHost {
    var hideForApp: ((pkg: String, displayId: Int) -> Unit)? = null

    /** Closes the bottom-screen overlay until Eclipse is opened again (the button on the bottom screen). */
    var close: (() -> Unit)? = null

    /** Opens the bottom screen again if it was closed (a game you left came back to the front). */
    var show: (() -> Unit)? = null

    /** The app switcher is about to bring an app to the front (set by the view model: resumes that game's session). */
    var onSwitch: ((pkg: String) -> Unit)? = null

    /** Settings → Reload Eclipse: the activity closes the bottom screen, schedules a fresh start and ends the process. */
    var restart: (() -> Unit)? = null

    /** Settings → Dual screen changed (which display, swapped screens, controller on the bottom screen): apply it. */
    var resync: (() -> Unit)? = null

    /** When Eclipse last started a game or app; tells "we launched something" apart from "you left Eclipse". */
    @Volatile
    var lastLaunchMs = 0L
}
