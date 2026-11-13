package com.eclipse.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.eclipse.launcher.data.AdBlock
import com.eclipse.launcher.data.CompanionData
import com.eclipse.launcher.data.Guides
import com.eclipse.launcher.data.Http
import com.eclipse.launcher.media.SecondScreenHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The Companion's browser as a screen of its own: an ordinary app window, like Chrome's, instead of a web page drawn
 * inside the bottom screen's overlay. In the overlay (a Presentation, a kind of pop-up window that never takes focus)
 * fast swipes did nothing however the page was handled; in a normal window the browser engine scrolls, flings and
 * zooms the way it does everywhere else.
 *
 * Opened on the screen the Companion is on. On the bottom screen, Eclipse's overlay steps aside while it's open and
 * comes back, with the Companion as it was, when it closes. Keeps the Companion's buttons: Back, Map (keep the page's
 * picture as a map), Save (keep the guide's text for the reader), Close. Ads are blocked as before.
 */
class GuideBrowserActivity : ComponentActivity() {

    companion object {
        private const val URL = "url"
        private const val FALLBACK = "fallback"
        private const val SITE = "site"
        private const val GAME = "game"
        private const val BOTTOM = "bottom"
        private const val TOKEN = "token"

        /** The newest window asked for, and whether it has opened (Android can refuse a launch without saying so). */
        @Volatile private var asked = 0L
        @Volatile private var opened = 0L

        /**
         * Opens [site] for the game [gameId] on [displayId]. [bottom]: that's the bottom screen (the overlay there
         * steps aside). False when Android refused at once; [onFail] runs (on the main thread) if the window still
         * hasn't opened a few seconds later, and the overlay comes back.
         */
        fun open(ctx: Context, site: Guides.Site, gameId: String, displayId: Int?, bottom: Boolean, onFail: () -> Unit = {}): Boolean {
            val token = System.nanoTime()
            val i = Intent(ctx, GuideBrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(URL, site.url).putExtra(FALLBACK, site.fallback).putExtra(SITE, site.name)
                .putExtra(GAME, gameId).putExtra(BOTTOM, bottom).putExtra(TOKEN, token)
            val opts = displayId?.let { android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
            // while a game has the top screen Eclipse counts as in the background, where Android may block its own
            // launches; its accessibility helper is allowed to start windows then (as for apps opened on this screen)
            val starter = com.eclipse.launcher.media.ScreenshotService.starter() ?: ctx
            return runCatching {
                SecondScreenHost.lastLaunchMs = System.currentTimeMillis()
                asked = token
                starter.startActivity(i, opts)
                // the overlay steps aside (it sits above windows on its screen), and comes back if nothing opened
                if (bottom) SecondScreenHost.hideForGuide?.invoke()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (asked == token && opened != token) {
                        if (bottom) SecondScreenHost.guideClosed?.invoke()
                        onFail()
                    }
                }, 3000)
                true
            }.getOrElse {
                if (bottom) SecondScreenHost.guideClosed?.invoke()
                false
            }
        }
    }

    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private var bottom = false
    private var fellBack = false

    private val accent: Int get() = runCatching { ThemeColors.accent.value.toArgb() }.getOrDefault(0xFFD4A537.toInt())
    /** Light mode (Look & Feel): the bar goes warm off-white with dark writing, like the rest of Eclipse. */
    private val light: Boolean get() = runCatching { ThemeColors.light.value }.getOrDefault(false)
    /** The bar: your / the theme's glass colour (solid), else near-black, or off-white in light mode. */
    private val barColor: Int get() = runCatching {
        ThemeColors.glass.value.takeIf { it.isSpecified }?.copy(alpha = 1f)?.toArgb()
    }.getOrNull() ?: if (light) Color.parseColor("#F7F4EE") else Color.parseColor("#0E0D0B")
    /** Writing on the bar: your / the theme's text colour, else whatever reads on the bar. */
    private val inkColor: Int get() = runCatching {
        ThemeColors.text.value.takeIf { it.isSpecified }?.toArgb()
    }.getOrNull() ?: if (androidx.core.graphics.ColorUtils.calculateLuminance(barColor) > 0.5) Color.parseColor("#1B1A17") else Color.parseColor("#F2EEE6")
    private val barIsLight: Boolean get() = androidx.core.graphics.ColorUtils.calculateLuminance(barColor) > 0.5
    /** Writing on a button filled with the accent. */
    private val onAccent: Int get() = if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.45) Color.parseColor("#1A1505") else Color.WHITE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A rounded button for the bar: plain, or filled with the theme's colour. */
    private fun pill(text: String, filled: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(if (filled) onAccent else inkColor)
        if (filled) paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (filled) accent else if (barIsLight) Color.parseColor("#12000000") else Color.parseColor("#1AFFFFFF"))
            setStroke(dp(1), if (filled) accent else if (barIsLight) Color.parseColor("#1F000000") else Color.parseColor("#26FFFFFF"))
        }
        background = RippleDrawable(ColorStateList.valueOf(if (barIsLight) Color.parseColor("#22000000") else Color.parseColor("#33FFFFFF")), shape, null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bottom = intent.getBooleanExtra(BOTTOM, false)
        opened = intent.getLongExtra(TOKEN, 0L)
        val url = intent.getStringExtra(URL) ?: run { finish(); return }
        val fallback = intent.getStringExtra(FALLBACK)
        val siteName = intent.getStringExtra(SITE) ?: "Guide"
        val gameId = intent.getStringExtra(GAME) ?: ""
        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = true
            // pinch to zoom, like Chrome (without the old on-screen zoom buttons)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    if (AdBlock.blocked(request?.url?.host)) WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    else null
                override fun onPageFinished(view: WebView?, u: String?) {
                    titleView.text = view?.title?.takeIf { it.isNotBlank() } ?: siteName
                }
                // the game's own page was a guess and isn't there: the site's search instead
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val fb = fallback ?: return
                    if (!fellBack && request?.isForMainFrame == true && request.url?.toString() == url && (errorResponse?.statusCode ?: 0) >= 400) {
                        fellBack = true
                        view?.post { view.loadUrl(fb) }
                    }
                }
            }
        }

        titleView = TextView(this).apply {
            text = siteName
            textSize = 13f
            setTextColor(inkColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(barColor)
            addView(pill("‹ Back") { goBack() })
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill("⤓ Map") { saveMap(gameId) })
            addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
            addView(pill("⤓ Save", filled = true) { saveGuide(gameId, siteName, url) })
            addView(View(context), LinearLayout.LayoutParams(dp(6), 1))
            addView(pill("✕") { finish() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // (keeps clear of the screen's bars)
        root.fitsSystemWindows = true
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(url)
    }

    private fun goBack() { if (web.canGoBack()) web.goBack() else finish() }

    @Deprecated("Back on older Android versions")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) { goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** The guide's own text (as the Companion's Save did): a <pre> guide, else the page's main content. */
    private fun saveGuide(gameId: String, siteName: String, url: String) {
        val js = """(function(){
            var pre=document.querySelector('#faqtext pre, .faqtext pre, #faqwrap pre, pre');
            var main=document.querySelector('#faqtext, .faqtext, #mw-content-text, article, main, #content');
            var e=pre||main||document.body;
            return JSON.stringify([pre?'txt':'html', document.title||'', e.innerText||'']);
        })()"""
        web.evaluateJavascript(js) { res ->
            val arr = runCatching { JSONArray(JSONArray("[$res]").getString(0)) }.getOrNull()
            val text = arr?.optString(2).orEmpty()
            if (arr == null || text.isBlank()) { toast("Nothing to save on this page"); return@evaluateJavascript }
            val title = arr.optString(1).ifBlank { siteName }
            val html = arr.optString(0) != "txt"
            val here = web.url ?: url
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { Guides.save(filesDir, gameId, title, siteName, here, text, html) }
                CompanionData.changed()
                toast("Saved · it's under Guides → On this device")
            }
        }
    }

    /** The page itself when it's a picture, else the biggest picture on it, kept as a map. */
    private fun saveMap(gameId: String) {
        val js = """(function(){
            if((document.contentType||'').indexOf('image/')===0) return JSON.stringify([location.href, document.title||'']);
            var best=null, area=0, imgs=document.images;
            for(var i=0;i<imgs.length;i++){var im=imgs[i], a=im.naturalWidth*im.naturalHeight;
                if(im.naturalWidth>=300&&im.naturalHeight>=200&&a>area){area=a;best=im;}}
            return JSON.stringify([best?best.currentSrc||best.src:'', (best&&best.alt)||document.title||'']);
        })()"""
        web.evaluateJavascript(js) { res ->
            val arr = runCatching { JSONArray(JSONArray("[$res]").getString(0)) }.getOrNull()
            val src = arr?.optString(0).orEmpty()
            if (!src.startsWith("http")) { toast("No map picture on this page"); return@evaluateJavascript }
            val name = arr?.optString(1).orEmpty().ifBlank { src.substringAfterLast('/').substringBeforeLast('.') }.take(60)
            toast("Saving the map…")
            lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) { CompanionData.newMapFile(filesDir, gameId) }
                val ok = Http.download(src, f) && withContext(Dispatchers.IO) {
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        .also { android.graphics.BitmapFactory.decodeFile(f.path, it) }.outWidth > 0
                }
                if (ok) withContext(Dispatchers.IO) { CompanionData.addMap(filesDir, gameId, f, name) } else f.delete()
                if (ok) CompanionData.changed()
                toast(if (ok) "Saved · it's under Maps" else "That picture couldn't be saved")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onResume() { super.onResume(); if (::web.isInitialized) web.onResume() }
    override fun onPause() { if (::web.isInitialized) web.onPause(); super.onPause() }

    override fun onDestroy() {
        if (::web.isInitialized) runCatching { web.stopLoading(); web.destroy() }
        // the bottom screen's overlay comes back, the Companion as it was (the window is never rebuilt for a
        // rotation or the like: the manifest says it handles those itself, so this is always the browser closing)
        if (bottom) SecondScreenHost.guideClosed?.invoke()
        super.onDestroy()
    }
}
