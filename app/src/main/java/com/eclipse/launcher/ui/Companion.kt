package com.eclipse.launcher.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.eclipse.launcher.EclipseVM
import com.eclipse.launcher.data.ArtKind
import com.eclipse.launcher.data.Game
import com.eclipse.launcher.data.Guides
import com.eclipse.launcher.data.Platforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import kotlin.math.abs

/*
 * The Companion: the bottom screen's guide hub for the game you're playing (Now Playing → Companion).
 * Touch only: tabs are tapped or swiped, everything is a finger-sized target. Tabs: Guides (sites for the game, guides
 * saved on the device, the reader) and Manual (the instruction manual: on the device, from the Internet Archive;
 * ScreenScraper shown but not ready yet).
 */

private val INK = Color(0xFFF2EEE6)
private val SUB = Color(0xFF9C978E)
private val LINE = Color(0x1AFFFFFF)
private val CARD = Color(0x0EFFFFFF)
private const val SOON_TEXT = "Manuals from ScreenScraper will be added in a future update..... Hopefully"

@Composable
private fun HubPill(text: String, gold: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (gold) C.accent else Color(0x14FFFFFF))
            .border(1.dp, if (gold) C.accent else LINE, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (gold) Color(0xFF1A1505) else if (enabled) INK else SUB, fontSize = 13.sp,
            fontWeight = if (gold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = C.accent, fontSize = 10.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 6.dp))
}

/** One row of a list: a coloured badge, a title and a note, something on the right. Long-press is optional. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubRow(
    badge: String, color: Long, title: String, note: String, greyed: Boolean = false, tag: String? = null,
    trailing: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(min = 52.dp).clip(shape).background(CARD).border(1.dp, LINE, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .graphicsLayer { alpha = if (greyed) 0.42f else 1f }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(if (greyed) Color(0xFF444444) else Color(color)), contentAlignment = Alignment.Center) {
            Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = INK, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (tag != null) Text(tag.uppercase(), color = C.accent, fontSize = 8.5.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 6.dp).border(1.dp, C.accent.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
            Text(note, color = SUB, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = if (greyed) SUB else C.accent, fontSize = 12.sp)
    }
}

/** "Coming soon" for ScreenScraper (a tap on anything of it). */
@Composable
private fun SoonPopup(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF71C1A16))
                .border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(16.dp)
        ) {
            Text("ScreenScraper · coming soon", color = INK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(SOON_TEXT, color = SUB, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            HubPill("OK", modifier = Modifier.align(Alignment.End), onClick = onDismiss)
        }
    }
}

/** The hub: header, tabs (tap or swipe), and whatever is open on top (browser, reader, manual, search). */
@Composable
fun CompanionHub(vm: EclipseVM, g: Game, close: () -> Unit) {
    val ctx = LocalContext.current
    val root = ctx.filesDir
    val scope = rememberCoroutineScope()
    val tabs = listOf("Guides", "Manual")
    val pager = rememberPagerState(pageCount = { tabs.size })
    var browser by remember { mutableStateOf<Guides.Site?>(null) }
    var reading by remember { mutableStateOf<Guides.Saved?>(null) }
    var manual by remember { mutableStateOf<File?>(null) }
    var searching by remember { mutableStateOf(false) }
    var soon by remember { mutableStateOf(false) }
    var savedTick by remember { mutableIntStateOf(0) } // bumps when a guide is saved / deleted: lists reload

    BackHandler {
        when {
            soon -> soon = false
            searching -> searching = false
            reading != null -> reading = null
            manual != null -> manual = null
            browser != null -> browser = null
            else -> close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF080807))) {
        Column(Modifier.fillMaxSize()) {
            // header
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 36.dp, height = 48.dp).clip(RoundedCornerShape(6.dp))) {
                    Cover(g.name, vm.artOf(g, ArtKind.BOX), Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(g.name, color = INK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Companion · " + (Platforms.byId(g.platform)?.name ?: g.platform), color = SUB, fontSize = 11.sp, maxLines = 1)
                }
                HubPill("⌕") { searching = true }
                Spacer(Modifier.width(6.dp))
                HubPill("✕ Close", onClick = close)
            }
            // tabs: tap one, or swipe the page
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                tabs.forEachIndexed { i, t ->
                    val on = pager.currentPage == i
                    Column(
                        Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 12.dp, vertical = 11.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(t.uppercase(), color = if (on) INK else SUB, fontSize = 11.5.sp, letterSpacing = 1.4.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        Box(Modifier.padding(top = 6.dp).size(width = 44.dp, height = 2.dp).background(if (on) C.accent else Color.Transparent))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { p ->
                when (p) {
                    0 -> GuidesTab(g, root, savedTick, open = { browser = it }, read = { reading = it }, changed = { savedTick++ })
                    else -> ManualTab(g, root, open = { manual = it }, soon = { soon = true })
                }
            }
        }
        browser?.let { site ->
            HubBrowser(site, onBack = { browser = null }) { title, text, html, url ->
                Guides.save(root, g.id, title, site.name, url, text, html)
                savedTick++
            }
        }
        reading?.let { s -> GuideReader(g, s) { reading = null; savedTick++ } }
        manual?.let { f -> ManualViewer(f) { manual = null } }
        if (searching) HubSearch(g, onCancel = { searching = false }) { q ->
            searching = false
            browser = Guides.Site("Search", q, Guides.searchUrl(g.name, q), "⌕", 0xFF444444)
        }
        if (soon) SoonPopup { soon = false }
    }
}

// ---------------------------------------------------------------- Guides tab

@Composable
private fun GuidesTab(
    g: Game, root: File, tick: Int, open: (Guides.Site) -> Unit, read: (Guides.Saved) -> Unit, changed: () -> Unit,
) {
    val ctx = LocalContext.current
    var saved by remember { mutableStateOf<List<Guides.Saved>>(emptyList()) }
    LaunchedEffect(g.id, tick) { saved = withContext(Dispatchers.IO) { Guides.saved(root, g.id) } }
    val sites = remember(g.name) { Guides.sites(g.name) }
    var deleting by remember { mutableStateOf<Guides.Saved?>(null) }
    val last = remember(saved, tick) { Guides.lastRead(ctx, g.id)?.let { n -> saved.firstOrNull { it.file.name == n } } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            if (last != null) item {
                val pct = (Guides.progress(ctx, last) * 100).toInt()
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(11.dp)).background(CARD)
                        .border(1.dp, LINE, RoundedCornerShape(11.dp)).clickable { read(last) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("CONTINUE READING", color = C.accent, fontSize = 9.5.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
                        Text(last.title, color = INK, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                        Text("${last.source} · $pct% read · on this device", color = SUB, fontSize = 11.sp)
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x1AFFFFFF))) {
                            Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().background(C.accent))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    HubPill("▶ Resume", gold = true) { read(last) }
                }
            }
            if (saved.isNotEmpty()) {
                item { SectionLabel("On this device") }
                itemsIndexed(saved, key = { _, s -> s.file.name }) { _, s ->
                    HubRow("TXT", 0xFF5A4A1A, s.title, "${s.source} · ${(Guides.progress(ctx, s) * 100).toInt()}% read · hold to delete",
                        trailing = "›", onLongClick = { deleting = s }) { read(s) }
                }
            }
            item { SectionLabel("Find guides for this game") }
            itemsIndexed(sites, key = { _, s -> s.name }) { _, s ->
                HubRow(s.badge, s.color, s.name, s.note, tag = if (s.best) "Best for this series" else null, trailing = "↗") { open(s) }
            }
            item {
                Text("Open a guide, then tap Save to keep it here: it opens in the reader, works offline and remembers where you were.",
                    color = SUB, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 16.dp))
            }
        }
        deleting?.let { s ->
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { deleting = null }, contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xF71C1A16)).padding(16.dp)) {
                    Text("Delete this guide?", color = INK, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.title, color = SUB, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                    Row(Modifier.align(Alignment.End)) {
                        HubPill("Cancel") { deleting = null }
                        Spacer(Modifier.width(8.dp))
                        HubPill("Delete", gold = true) { Guides.delete(root, g.id, s); deleting = null; changed() }
                    }
                }
            }
        }
    }
}

/** The in-Companion browser: ads blocked, and a Save button that keeps the page's guide text for the reader. */
@Composable
private fun HubBrowser(site: Guides.Site, onBack: () -> Unit, onSave: (title: String, text: String, html: Boolean, url: String) -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(site.name) }
    LaunchedEffect(status) { if (status != null) { delay(2500); status = null } }
    BackHandler { val w = web; if (w != null && w.canGoBack()) w.goBack() else onBack() }

    Column(Modifier.fillMaxSize().background(Color(0xFF080807))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubPill("‹ Back") { val w = web; if (w != null && w.canGoBack()) w.goBack() else onBack() }
            Text(title, color = INK, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            HubPill("⤓ Save", gold = true) {
                val w = web ?: return@HubPill
                // the guide's own text: GameFAQs' plain-text guides sit in a <pre>; wiki and article pages in their
                // main content; anything else the whole page
                val js = """(function(){
                    var pre=document.querySelector('#faqtext pre, .faqtext pre, #faqwrap pre, pre');
                    var main=document.querySelector('#faqtext, .faqtext, #mw-content-text, article, main, #content');
                    var e=pre||main||document.body;
                    return JSON.stringify([pre?'txt':'html', document.title||'', e.innerText||'']);
                })()"""
                w.evaluateJavascript(js) { res ->
                    val arr = runCatching { JSONArray(JSONArray("[$res]").getString(0)) }.getOrNull()
                    val text = arr?.optString(2).orEmpty()
                    if (arr == null || text.isBlank()) { status = "Nothing to save on this page"; return@evaluateJavascript }
                    onSave(arr.optString(1).ifBlank { site.name }, text, arr.optString(0) != "txt", w.url ?: site.url)
                    status = "Saved · it's under Guides → On this device"
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.offscreenPreRaster = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? =
                                if (com.eclipse.launcher.data.AdBlock.blocked(request?.url?.host))
                                    android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                else null
                            override fun onPageFinished(view: WebView?, url: String?) { title = view?.title?.takeIf { it.isNotBlank() } ?: site.name }
                        }
                        loadUrl(site.url)
                        web = this
                    }
                },
                onRelease = { w -> runCatching { w.stopLoading(); w.destroy() }; web = null }
            )
            status?.let { s ->
                Text(s, color = INK, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Color(0xF21C1A16)).border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** ⌕ in the header: what to look up; the game's name is added in front of it. */
@Composable
private fun HubSearch(g: Game, onCancel: () -> Unit, onGo: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // the bottom screen's window takes focus while typing (it can't get the keyboard otherwise)
    DisposableEffect(Unit) { onDispose { com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(false) } }
    LaunchedEffect(Unit) {
        com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(true)
        delay(80)
        runCatching { fr.requestFocus() }
        keyboard?.show()
    }
    fun go() { keyboard?.hide(); onGo(text) }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(onClick = onCancel), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 40.dp).widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xF71C1A16)).border(1.dp, LINE, RoundedCornerShape(14.dp)).clickable(enabled = false) {}.padding(14.dp)
        ) {
            Text("Search the web for ${g.name}", color = INK, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("The game's name is added for you: just type what you're stuck on.", color = SUB, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x59000000)).border(1.dp, LINE, RoundedCornerShape(10.dp)).padding(12.dp)) {
                BasicTextField(
                    text, { text = it }, singleLine = true,
                    textStyle = TextStyle(color = INK, fontSize = 15.sp), cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                )
                if (text.isEmpty()) Text("third heart piece, boss weakness…", color = SUB, fontSize = 15.sp)
            }
            Row(Modifier.align(Alignment.End).padding(top = 12.dp)) {
                HubPill("Cancel", onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                HubPill("Search", gold = true) { go() }
            }
        }
    }
}

// ---------------------------------------------------------------- the reader

@Composable
private fun GuideReader(g: Game, s: Guides.Saved, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var blocks by remember { mutableStateOf<List<Guides.Block>?>(null) }
    LaunchedEffect(s.file) {
        blocks = withContext(Dispatchers.IO) { runCatching { Guides.reflow(s.file.readText(), s.html) }.getOrDefault(emptyList()) }
        Guides.setLastRead(ctx, g.id, s)
    }
    val list = rememberLazyListState()
    var textSp by remember { mutableIntStateOf(Guides.textSize(ctx)) }
    var marks by remember { mutableStateOf(Guides.bookmarks(ctx, s)) }
    var bars by remember { mutableStateOf(true) }
    var contents by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hit by remember { mutableIntStateOf(-1) }
    BackHandler { if (contents) contents = false else if (finding) finding = false else onBack() }

    val all = blocks
    // back to where you were, then keep that up to date
    LaunchedEffect(all) {
        if (all == null || all.isEmpty()) return@LaunchedEffect
        list.scrollToItem(Guides.position(ctx, s).coerceIn(0, all.size - 1))
        snapshotFlow { list.firstVisibleItemIndex }.debounce(400).collect { Guides.setPosition(ctx, s, it, all.size) }
    }
    val first by remember { androidx.compose.runtime.derivedStateOf { list.firstVisibleItemIndex } }
    val count = all?.size ?: 0
    val frac = if (count <= 1) 0f else first.toFloat() / (count - 1)
    val section = remember(all, first) { all?.take(first + 1)?.lastOrNull { it is Guides.Block.Heading }?.text }
    val matches = remember(all, query) {
        if (all == null || query.length < 2) emptyList() else all.indices.filter { all[it].text.contains(query, ignoreCase = true) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Column(Modifier.fillMaxSize()) {
            if (bars) Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HubPill("‹ Guides", onClick = onBack)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(s.title, color = INK, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(section, "${(frac * 100).toInt()}%").joinToString(" · "), color = SUB, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) { detectTapGestures { bars = !bars } }) {
                    if (all == null) Text("Opening…", color = SUB, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    else LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 90.dp)) {
                        itemsIndexed(all) { i, b -> BlockView(b, textSp, query, i == hit, i in marks) }
                    }
                }
                // drag (or tap) the rail to jump through the guide
                BoxWithConstraints(Modifier.width(30.dp).fillMaxHeight().border(1.dp, LINE)
                    .pointerInput(count) {
                        detectTapGestures { o -> if (count > 0) scope.launch { list.scrollToItem(((o.y / size.height) * (count - 1)).toInt().coerceIn(0, count - 1)) } }
                    }
                    .pointerInput(count) {
                        detectVerticalDragGestures { change, _ ->
                            if (count > 0) scope.launch { list.scrollToItem(((change.position.y / size.height) * (count - 1)).toInt().coerceIn(0, count - 1)) }
                        }
                    }
                ) {
                    val h = maxHeight
                    Box(Modifier.align(Alignment.TopCenter).padding(vertical = 8.dp).width(2.dp).fillMaxHeight().background(Color(0x1AFFFFFF)))
                    if (count > 1) marks.forEach { m ->
                        Box(Modifier.align(Alignment.TopCenter).offset(y = (h - 16.dp) * (m.toFloat() / (count - 1)) + 4.dp).size(width = 12.dp, height = 3.dp).background(C.accent.copy(alpha = 0.7f)))
                    }
                    Box(Modifier.align(Alignment.TopCenter).offset(y = (h - 20.dp) * frac).size(20.dp).clip(CircleShape).background(C.accent))
                }
            }
        }
        if (bars && all != null) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xEB1E1C18))
                .border(1.dp, LINE, RoundedCornerShape(24.dp)).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            HubPill("☰ Contents") { contents = true }
            HubPill("Aa") {
                textSp = Guides.SIZES[(Guides.SIZES.indexOf(textSp) + 1) % Guides.SIZES.size]
                Guides.setTextSize(ctx, textSp)
            }
            HubPill("⌕ Find") { finding = true }
            HubPill(if (first in marks) "🔖 Marked" else "🔖 Mark", gold = first in marks) {
                marks = if (first in marks) marks - first else marks + first
                Guides.setBookmarks(ctx, s, marks)
            }
        }
        if (contents && all != null) {
            val heads = remember(all) { all.indices.filter { all[it] is Guides.Block.Heading } }
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { contents = false }) {
                Column(Modifier.fillMaxHeight().widthIn(max = 340.dp).fillMaxWidth(0.8f).background(Color(0xFA16140F)).clickable(enabled = false) {}.padding(12.dp)) {
                    Text("Contents", color = INK, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (marks.isNotEmpty()) {
                            item { SectionLabel("Bookmarks") }
                            itemsIndexed(marks.sorted()) { _, m ->
                                val near = all.take(m + 1).lastOrNull { it is Guides.Block.Heading }?.text
                                ContentsRow("🔖 " + (near ?: "Bookmark"), all.getOrNull(m)?.text?.take(60).orEmpty()) { contents = false; scope.launch { list.scrollToItem(m) } }
                            }
                            item { SectionLabel("Sections") }
                        }
                        if (heads.isEmpty()) item { Text("This guide has no section titles. Use Find, or drag the gold dot.", color = SUB, fontSize = 12.sp) }
                        itemsIndexed(heads) { _, h ->
                            ContentsRow(all[h].text, null, current = section == all[h].text) { contents = false; scope.launch { list.scrollToItem(h) } }
                        }
                    }
                }
            }
        }
        if (finding) FindBar(query, matches, hit,
            onQuery = { query = it; hit = -1 },
            onStep = { d ->
                if (matches.isNotEmpty()) {
                    val pos = matches.indexOf(hit)
                    val next = if (pos < 0) (if (d > 0) matches.firstOrNull { it >= first } ?: matches.first() else matches.lastOrNull { it <= first } ?: matches.last())
                        else matches[(pos + d + matches.size) % matches.size]
                    hit = next
                    scope.launch { list.scrollToItem(next) }
                }
            },
            onClose = { finding = false })
    }
}

@Composable
private fun ContentsRow(title: String, note: String?, current: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
        .background(if (current) C.accent.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(title, color = if (current) C.accent else INK, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!note.isNullOrBlank()) Text(note, color = SUB, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One block of a guide, with the find word marked in it. */
@Composable
private fun BlockView(b: Guides.Block, size: Int, query: String, isHit: Boolean, marked: Boolean) {
    val mark = SpanStyle(background = C.accent.copy(alpha = if (isHit) 0.6f else 0.3f))
    fun lit(t: String): AnnotatedString = if (query.length < 2) AnnotatedString(t) else buildAnnotatedString {
        var i = 0
        while (i < t.length) {
            val at = t.indexOf(query, i, ignoreCase = true)
            if (at < 0) { append(t.substring(i)); break }
            append(t.substring(i, at))
            pushStyle(mark); append(t.substring(at, at + query.length)); pop()
            i = at + query.length
        }
    }
    val edge = if (marked) Modifier.background(C.accent.copy(alpha = 0.06f)) else Modifier
    Box(edge.fillMaxWidth().padding(bottom = 10.dp)) {
        when (b) {
            is Guides.Block.Heading -> Text(lit(b.text.uppercase()), color = C.accent, fontSize = (size - 2).sp, letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            is Guides.Block.Para -> Text(lit(b.text), color = Color(0xFFE9E5DC), fontSize = size.sp, lineHeight = (size * 1.55f).sp)
            is Guides.Block.Lines -> Text(lit(b.text), color = Color(0xFFE9E5DC), fontSize = size.sp, lineHeight = (size * 1.45f).sp)
            is Guides.Block.Pre -> Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Text(lit(b.text), color = Color(0xFFD9D4C8), fontSize = (size - 3).sp, fontFamily = FontFamily.Monospace,
                    lineHeight = ((size - 3) * 1.3f).sp, softWrap = false)
            }
        }
    }
}

@Composable
private fun FindBar(query: String, matches: List<Int>, hit: Int, onQuery: (String) -> Unit, onStep: (Int) -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(false) } }
    LaunchedEffect(Unit) {
        com.eclipse.launcher.media.SecondaryPresentation.current?.setTyping(true)
        delay(80)
        runCatching { fr.requestFocus() }
        keyboard?.show()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.padding(8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFA1C1A16))
                .border(1.dp, C.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0x59000000)).padding(horizontal = 10.dp, vertical = 10.dp)) {
                BasicTextField(
                    query, onQuery, singleLine = true, textStyle = TextStyle(color = INK, fontSize = 14.sp), cursorBrush = SolidColor(C.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onStep(1) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr)
                )
                if (query.isEmpty()) Text("Find in this guide", color = SUB, fontSize = 14.sp)
            }
            Text(if (query.length < 2) "" else "${(matches.indexOf(hit) + 1).coerceAtLeast(0)}/${matches.size}", color = SUB, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp))
            HubPill("‹") { keyboard?.hide(); onStep(-1) }
            Spacer(Modifier.width(4.dp))
            HubPill("›") { keyboard?.hide(); onStep(1) }
            Spacer(Modifier.width(4.dp))
            HubPill("✕") { keyboard?.hide(); onClose() }
        }
    }
}

// ---------------------------------------------------------------- Manual tab

@Composable
private fun ManualTab(g: Game, root: File, open: (File) -> Unit, soon: () -> Unit) {
    val scope = rememberCoroutineScope()
    val kept = remember(g.id) { Guides.manualFile(root, g.id) }
    var keptThere by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<List<File>>(emptyList()) }
    var hits by remember { mutableStateOf<List<Guides.ArchiveHit>?>(null) }
    var vgm by remember { mutableStateOf<List<Guides.VgmHit>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(g.id) {
        withContext(Dispatchers.IO) {
            keptThere = kept.isFile
            local = runCatching { Guides.localManuals(File(g.path)) }.getOrDefault(emptyList())
        }
        vgm = Guides.vgmSearch(g.platform, g.name, g.path)
        hits = Guides.archiveSearch(g.name)
    }
    // downloads a manual into the game's Companion folder, then opens it
    fun fetch(label: String, url: suspend () -> String?) {
        if (busy != null) return
        busy = "Downloading $label…"; error = null
        scope.launch {
            val u = url()
            val ok = u != null && withContext(Dispatchers.IO) { kept.parentFile?.mkdirs(); com.eclipse.launcher.data.Http.download(u, kept) }
            busy = null
            if (ok) { keptThere = true; open(kept) } else error = "That one couldn't be downloaded. Try another."
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (keptThere || local.isNotEmpty()) {
            item { SectionLabel("On this device") }
            if (keptThere) item { HubRow("PDF", 0xFF5A4A1A, "Instruction manual", "Downloaded · on this device", trailing = "›") { open(kept) } }
            itemsIndexed(local) { _, f -> HubRow("PDF", 0xFF5A4A1A, f.nameWithoutExtension, f.parentFile?.name.orEmpty(), trailing = "›") { open(f) } }
        }
        busy?.let { b -> item { Text(b, color = C.accent, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) } }
        // Kirkland's scans: named like No-Intro ROMs, so the ROM's own file name finds the right one
        item { SectionLabel("VideoGameManual.com") }
        val v = vgm
        when {
            v == null -> item { Text("Looking for this game's manual…", color = SUB, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            v.isEmpty() -> item { Text("No manual for this game there.", color = SUB, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            else -> itemsIndexed(v) { _, m ->
                HubRow("VGM", 0xFF6B2F2F, m.name, "Scanned by Kirkland · tap to download and open", trailing = "⤓") { fetch(m.name) { m.url } }
            }
        }
        item { SectionLabel("Internet Archive") }
        val h = hits
        when {
            h == null -> item { Text("Looking for scanned manuals…", color = SUB, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            h.isEmpty() -> item { Text("No scanned manual found for this title.", color = SUB, fontSize = 12.sp, modifier = Modifier.padding(4.dp)) }
            else -> itemsIndexed(h) { _, a ->
                HubRow("IA", 0xFF444444, a.title, "${a.downloads} downloads · tap to download and open", trailing = "⤓") {
                    fetch(a.title) { Guides.archivePdf(a.id) }
                }
            }
        }
        error?.let { e -> item { Text(e, color = Color(0xFFFF8A7A), fontSize = 12.sp, modifier = Modifier.padding(4.dp)) } }
        item { SectionLabel("ScreenScraper") }
        item { HubRow("SS", 0xFF2B4D7A, "ScreenScraper", "Coming soon", greyed = true, trailing = "Soon", onClick = soon) }
        item {
            Text("Manuals you already have are found too: a PDF named like the game beside it, in a “manuals” folder, or in ES-DE's downloaded media. A new download replaces the one kept for this game.",
                color = SUB, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 16.dp))
        }
    }
}

/** A PDF manual, a page at a time: swipe or tap the sides to turn, pinch to zoom, drag to move around a zoomed page. */
@Composable
private fun ManualViewer(file: File, onBack: () -> Unit) {
    BackHandler { onBack() }
    val lock = remember { Mutex() }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(file) {
        val fd = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        val r = fd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
        renderer = r
        failed = r == null
        onDispose { runCatching { r?.close() }; runCatching { fd?.close() } }
    }
    var page by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    val pages = renderer?.pageCount ?: 0
    val widthPx = with(LocalDensity.current) { 480.dp.toPx() }.toInt().coerceIn(600, 1600)
    LaunchedEffect(renderer, page) {
        val r = renderer ?: return@LaunchedEffect
        scale = 1f; offset = Offset.Zero
        bmp = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    r.openPage(page.coerceIn(0, r.pageCount - 1)).use { p ->
                        val w = widthPx * 2
                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(android.graphics.Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    fun turn(d: Int) { if (pages > 0) page = (page + d).coerceIn(0, pages - 1) }

    Column(Modifier.fillMaxSize().background(Color(0xFF080807))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HubPill("‹ Manual", onClick = onBack)
            Text(if (pages > 0) "Page ${page + 1} of $pages" else "", color = SUB, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            HubPill("‹", enabled = page > 0) { turn(-1) }
            Spacer(Modifier.width(6.dp))
            HubPill("›", enabled = page < pages - 1) { turn(1) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(8.dp).clip(RoundedCornerShape(8.dp))
            .pointerInput(pages) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var dragX = 0f
                    var moved = false
                    var lastX = 0f
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.firstOrNull()?.let { lastX = it.position.x }
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        if (ev.changes.size > 1 || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            moved = true
                        } else {
                            dragX += pan.x
                            if (abs(pan.x) > 1f) moved = true
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale <= 1f && abs(dragX) > 60f) turn(if (dragX < 0) 1 else -1)
                    else if (!moved && scale <= 1f) {
                        // a tap on the left or right third turns the page
                        val x = lastX
                        if (x < size.width / 3f) turn(-1) else if (x > size.width * 2f / 3f) turn(1)
                    }
                }
            },
            contentAlignment = Alignment.Center
        ) {
            when {
                failed -> Text("This PDF can't be opened.", color = SUB, fontSize = 13.sp)
                bmp == null -> Text("Opening…", color = SUB, fontSize = 13.sp)
                else -> Image(bmp!!.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            }
        }
    }
}
