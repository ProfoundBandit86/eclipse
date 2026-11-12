package com.eclipse.launcher

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eclipse.launcher.data.*
import com.eclipse.launcher.media.MediaRepo
import com.eclipse.launcher.media.ScreenshotService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.eclipse.launcher.ui.ThemeColors
import kotlinx.coroutines.withTimeoutOrNull
import com.eclipse.launcher.data.RomM
import java.io.File
import java.util.concurrent.Executors

class EclipseVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    val store = Store(ctx)
    /** Low power's starting value, until you choose (see [lowPowerOn]): on for devices with little memory. */
    private val lowPowerDefault: Boolean by lazy {
        runCatching {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            am.isLowRamDevice || mi.totalMem < 7L * 1024 * 1024 * 1024
        }.getOrDefault(false)
    }
    // ---- dual screen (the rest is with the other dual-screen settings): which display, which way round (see media/Screens.kt) ----
    /** "auto", "standard" (main screen on Android's default display) or "swapped" (on the other one). */
    var screenRoles by mutableStateOf(store.str("screen_roles", "auto"))
        private set
    /** The other screen's display name, when you picked one ("" = the best one Eclipse finds). */
    var secondName by mutableStateOf(store.str("second_display", ""))
        private set
    val media = MediaRepo(ctx)

    private companion object {
        const val GAMES_FILE = "games.json"
        const val ART_FILE = "art.json"
        const val DESC_FILE = "desc.json"
        const val PLAY_FILE = "playtime.json"
        const val HASH_FILE = "hasheous.json"
        const val ROMM_FILE = "romm.json"        // RomM game id -> what we know about it on the server
        const val ROMM_LINK_FILE = "romm_links.json" // your local game id -> its RomM game id
        const val MAX_SESSION_SECS = 12L * 3600L          // ignore anything longer (e.g. device off mid-game)
        const val APPS_SAFETY_REFRESH_MS = 30L * 60_000L  // backstop in case an install event is missed
    }

    // ---- navigation ----
    var screen by mutableStateOf(Screen.HOME)
    var settingsTab by mutableIntStateOf(0)
    var permTick by mutableIntStateOf(0)   // bumped on resume so permission statuses refresh
    private val menuGameState = mutableStateOf<Game?>(null)
    /** The game whose page is open; a different game's page starts on its first tab. */
    var menuGame: Game?
        get() = menuGameState.value
        set(v) { if (v?.id != menuGameState.value?.id) { menuTab = 0; menuEditing = false }; menuGameState.value = v }
    /** The open game page's tab (0 Overview, 1 Details, 2 Achievements, 3 Artwork, 4 Options): L1 / R1 change it. */
    var menuTab by mutableIntStateOf(0)
    /** The game page's details are being edited (L1 / R1 don't change tab then). */
    @Volatile var menuEditing = false
    fun menuTabStep(d: Int) { if (!menuEditing && menuGame != null) menuTab = (menuTab + d + 5) % 5 }
    var artGame by mutableStateOf<Game?>(null)
    var pendingKind by mutableStateOf(ArtKind.BOX)
    var message by mutableStateOf<String?>(null)

    /**
     * Eclipse's own pop-up (ui/NoticeToast.kt): [kind] in gold small caps ("CONVERTED TO CHD"), the game, a detail, the
     * game's [art] with a badge for [tone] ("ok", "down", "up", "chd", "error"; "" = a plain message).
     */
    data class Notice(val kind: String, val title: String, val detail: String? = null, val art: String? = null, val tone: String = "")
    var notice by mutableStateOf<Notice?>(null)

    /** Shows [n] in Eclipse's pop-up while Eclipse is on screen; otherwise as Android's toast (it shows over other apps). */
    fun showNotice(n: Notice) {
        if (resumed) notice = n
        else message = listOfNotNull(n.kind.takeIf { it.isNotEmpty() }?.lowercase()?.replaceFirstChar { it.uppercase() }, n.title).joinToString(": ")
    }
    val isResumed: Boolean get() = resumed
    var shotStatus by mutableStateOf<String?>(null)   // feedback shown on the bottom screen

    // ---- data (large sets are loaded from files in the background; see init) ----
    var loaded by mutableStateOf(false)
    var games by mutableStateOf<List<Game>>(emptyList())
    var art by mutableStateOf<Map<String, String>>(emptyMap())
    var playtime by mutableStateOf<Map<String, String>>(emptyMap())
    /** Your edits to games' details: id -> JSON (t = title, y = released, g = genre, d = developer, p = players). */
    private var gameMeta by mutableStateOf(store.map("game_meta"))
    var desc by mutableStateOf<Map<String, String>>(emptyMap())
    var hashInfo by mutableStateOf<Map<String, String>>(emptyMap()) // game id -> Hasheous match ("" = not in any database)
    var apps by mutableStateOf<List<AppEntry>>(emptyList())

    var dock by mutableStateOf(store.list("dock"))
    var homeSection by mutableStateOf(HomeSection.RECENTS)
    /** The right panel's last highlighted row, per panel ("screen/title" → row title); forgotten when you leave the page. */
    val drawerMemory = HashMap<String, String>()
    var dockNav = false                                     // true while Up / Down is moving focus inside the dock
    var dockFocused by mutableStateOf(false)               // the controller is in the left dock (Home previews its rows)
    var lastPlatform by mutableStateOf<String?>(null)    // platform card the controller was last on
    var platformSel by mutableStateOf<String?>(null)     // platform to open the games screen on
    var platformMenu by mutableStateOf<String?>(null)    // platform card whose menu is open
    var emuMenu by mutableStateOf<String?>(null)         // system whose emulator picker is open
    var viewMenu by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)               // the search layer (button on On Deck)
    var platDrawer by mutableStateOf(false)               // the options drawer on the right of the platforms
    var gamesDrawer by mutableStateOf(false)              // the same drawer on a system's games screen
    var deckDrawer by mutableStateOf(false)               // the options drawer on the right of On Deck
    /** Your own order for the platforms (ids, first to last); systems not in it keep their usual place after these. */
    var platOrder by mutableStateOf(store.list("plat_order"))
        private set
    fun savePlatOrder(ids: List<String>) { platOrder = ids; store.putList("plat_order", ids) }
    /** How the platforms are ordered: "az", "played" (most played first), "year" (oldest first) or "custom" (yours). */
    var platSort by mutableStateOf(store.str("plat_sort").ifEmpty { if (store.list("plat_order").isEmpty()) "az" else "custom" })
        private set
    fun choosePlatSort(v: String) { platSort = v; store.putStr("plat_sort", v) }
    /** [plats] in the chosen order, All games and Favourites always first. */
    fun orderPlats(plats: List<PlatformDef>): List<PlatformDef> {
        val pinned = listOf(com.eclipse.launcher.data.Platforms.ALL, com.eclipse.launcher.data.Platforms.FAVORITES)
        val front = pinned.mapNotNull { id -> plats.firstOrNull { it.id == id } }
        val rest = plats.filter { it.id !in pinned }
        val sorted = when (platSort) {
            "played" -> {
                val secs = games.groupBy { it.platform }.mapValues { (_, l) -> l.sumOf { playedSeconds(it) } }
                rest.sortedWith(compareByDescending<PlatformDef> { secs[it.id] ?: 0L }.thenBy { it.name.lowercase() })
            }
            "year" -> rest.sortedWith(compareBy<PlatformDef> { com.eclipse.launcher.data.Platforms.year(it.id) ?: 9999 }.thenBy { it.name.lowercase() })
            "custom" -> {
                val pos = platOrder.withIndex().associate { (i, id) -> id to i }
                rest.sortedWith(compareBy<PlatformDef> { pos[it.id] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
            }
            else -> rest.sortedBy { it.name.lowercase() }
        }
        return front + sorted
    }
    /** Whether a system shows on the platforms: it has games (All games and Favourites show whenever you have any). */
    fun showsPlat(id: String, counts: Map<String, Int>): Boolean = when (id) {
        com.eclipse.launcher.data.Platforms.ALL, com.eclipse.launcher.data.Platforms.FAVORITES -> (counts[com.eclipse.launcher.data.Platforms.ALL] ?: 0) > 0
        else -> (counts[id] ?: 0) > 0
    }
    var appearancePicker by mutableStateOf<String?>(null)  // "theme" / "icons" / "backdrops" picker open in Settings
    var platReorder by mutableStateOf(false)              // the "reorder platforms" screen is open
    /** The Companion (ui/Companion.kt) open for this game on the bottom screen / on this screen (no bottom screen showing). */
    var companionBottom by mutableStateOf<Game?>(null)
    var companionTop by mutableStateOf<Game?>(null)
    /** A game's page → Companion: on the bottom screen when it's showing, else over this one. */
    fun openCompanion(g: Game) {
        if (com.eclipse.launcher.media.SecondaryPresentation.current != null) { companionBottom = g; message = "Companion opened on the bottom screen" }
        else companionTop = g
    }
    var onDeckRemove by mutableStateOf<Game?>(null)       // "remove from Recently Played?" (Select on an On Deck game)
    /** Which systems the platforms show: "local" (only ones you have games for) or "romm" (plus your RomM systems). */
    /** Search (its right dock): where results come from ("all", "local" or "romm") and whether the letter strip shows. */
    var searchSource by mutableStateOf(store.str("search_source", "all"))
        private set
    fun chooseSearchSource(v: String) { searchSource = v; store.putStr("search_source", v) }
    var searchLetters by mutableStateOf(store.str("search_letters", "off") == "on")
        private set
    fun chooseSearchLetters(on: Boolean) { searchLetters = on; store.putStr("search_letters", if (on) "on" else "off") }
    /** Search: Up onto the search bar (or the mic) asks you to speak instead of typing. */
    var searchVoice by mutableStateOf(store.str("search_voice", "on") != "off")
        private set
    fun chooseSearchVoice(on: Boolean) { searchVoice = on; store.putStr("search_voice", if (on) "on" else "off") }
    var platSource by mutableStateOf(store.str("plat_source", "local"))
    fun choosePlatSource(v: String) { platSource = v; store.putStr("plat_source", v) }

    /** Game counts per system for the platforms: your games, plus RomM games not downloaded when the source includes RomM. */
    /** Games per system, worked out once and shared by every screen (it only recomputes when the games, favourites, hidden list or RomM catalogue change). */
    val platCounts: Map<String, Int> by derivedStateOf { platformCounts() }
    fun platformCounts(): Map<String, Int> {
        val romOnly = platSource == "rommonly" && rommOn()
        // Source RomM: only games that came from, or are matched to, RomM
        val shown = games.filter { it.id !in hiddenGames && (!romOnly || it.id in rommLink) }
        val local = shown.groupingBy { it.platform }.eachCount() +
            (com.eclipse.launcher.data.Platforms.FAVORITES to shown.count { it.id in favGames }) +
            (com.eclipse.launcher.data.Platforms.ALL to shown.count { it.platform != "android" })
        if ((platSource != "romm" && !romOnly) || !rommOn()) return local
        val remote = rommCatalog.filter { installedRom(it.id) == null }.groupingBy { it.bp }.eachCount()
        val all = (local.keys + remote.keys).associateWith { (local[it] ?: 0) + (remote[it] ?: 0) }
        return all + (com.eclipse.launcher.data.Platforms.ALL to (local[com.eclipse.launcher.data.Platforms.ALL] ?: 0) + remote.values.sum())
    }                // the games screen's "…" menu is open
    /** The games page's DEFAULT view (slide view unless you made another the default); systems can have their own. */
    var platformView by mutableStateOf(store.str("pview", "carousel")) // carousel / grid / list / coverflow …
    /** Views chosen for one system (id, or "all" for All games); a system without one uses [platformView]. */
    var sysViews by mutableStateOf(store.map("sys_views"))
        private set
    fun viewFor(sys: String?): String = sysViews[sys ?: "all"] ?: platformView
    fun choosePlatformView(sys: String?, v: String) { sysViews = sysViews + ((sys ?: "all") to v); store.putMap("sys_views", sysViews) }
    /** This view becomes the default for every system that hasn't got its own; this system then follows the default. */
    fun makeViewDefault(sys: String?, v: String) {
        platformView = v; store.putStr("pview", v)
        sysViews = sysViews - (sys ?: "all"); store.putMap("sys_views", sysViews)
    }
    fun useDefaultView(sys: String?) { sysViews = sysViews - (sys ?: "all"); store.putMap("sys_views", sysViews) }

    var pendingPlatform: String? = null                   // image picker target for a platform card
    var pendingPlatKind: PlatKind? = null                 // ...and which picture (null = the card image)
    var platArt by mutableStateOf(store.map("platArt"))  // your own images for platform cards
    /** The second-screen dock's four slots, left to right ("" = empty), so an app stays in the slot you put it in. */
    var dock2 by mutableStateOf(store.list("dock2").take(4).let { it + List(4 - it.size) { "" } })
    var appScreen by mutableStateOf(store.map("appScreen")) // pkg -> "top" / "bottom": where you chose to open it
    var dockPeek by mutableStateOf(false)             // dock opened by touch while auto-hide is on
    var appMenu by mutableStateOf<String?>(null)      // app-drawer app whose menu is open
    var dockMenu by mutableStateOf<String?>(null)      // dock app whose remove-menu is open
    var scrape by mutableStateOf<ScrapeState?>(null)
    var lbStatus by mutableStateOf<String?>(null)     // LaunchBox download/build progress line
    var lbProgress by mutableFloatStateOf(0f)
    private var lbJob: Job? = null
    private var scrapeJob: Job? = null
    var renames by mutableStateOf(store.map("renames"))
    var cats by mutableStateOf(store.map("cats"))
    var emuPick by mutableStateOf(store.map("emus"))
    /** Your own names for systems (System editor → Name), by id; Platforms uses them everywhere. */
    var sysNames by mutableStateOf(store.map("sys_names").also { Platforms.setNames(it) })
        private set
    fun renameSystem(id: String, name: String) {
        val n = name.trim()
        sysNames = if (n.isEmpty() || n == Platforms.originalName(id)) sysNames - id else sysNames + (id to n)
        store.putMap("sys_names", sysNames)
        Platforms.setNames(sysNames)
    }
    /**
     * Art style per system (its games' right-hand drawer → Art style): "box" (default), "media" (disc / cartridge),
     * "logo" or "shot" (screenshot / backdrop). Used wherever that system's games appear, On Deck included.
     */
    var artStyles by mutableStateOf(store.map("art_style"))
        private set
    /** The default art style for systems without their own (poster unless you made another the default). */
    var artStyleDefault by mutableStateOf(store.str("art_style_default", "poster"))
        private set
    fun artStyleFor(platform: String?): String = platform?.let { artStyles[it] } ?: artStyleDefault
    fun hasOwnArtStyle(platform: String): Boolean = platform in artStyles
    /** This style becomes the default for every system that hasn't got its own; this system then follows the default. */
    fun makeArtStyleDefault(platform: String, style: String) {
        artStyleDefault = style; store.putStr("art_style_default", style)
        artStyles = artStyles - platform; store.putMap("art_style", artStyles)
    }
    fun useDefaultArtStyle(platform: String) { artStyles = artStyles - platform; store.putMap("art_style", artStyles) }
    fun setArtStyle(platform: String, style: String) {
        artStyles = artStyles + (platform to style)
        store.putMap("art_style", artStyles)
    }
    var recent by mutableStateOf(store.list("recent"))
    /** where each game's cover is on screen right now (root coordinates): the game menu flips open from it */
    val artBounds = HashMap<String, androidx.compose.ui.geometry.Rect>()
    var romPath by mutableStateOf(store.str("romPath"))
    var scanning by mutableStateOf(false)
    /** more ROM folders scanned alongside [romPath] (Settings → Library → Add folder) */
    var extraFolders by mutableStateOf(store.list("rom_folders"))
    /** folders added for one system: everything in them counts as that system. id -> paths, one per line */
    var sysFolders by mutableStateOf(store.map("sys_folders"))
    /** the system whose rescan + scrape is running, if any */
    var scanningSys by mutableStateOf<String?>(null)
    /** the system open in the Edit system screen (Settings → Library) */
    var sysEdit by mutableStateOf<String?>(null)
    /** where the next picked folder goes: null = the ROM folders list, "@main" = replaces the main folder, else a system id */
    var folderTarget: String? = null

    // ---- screen mirror (the bottom screen's Mirror page) ----
    /** The part of the top screen shown, as fractions "x,y,w,h", per game ("" = when no game is running). */
    var mirrorRegions by mutableStateOf(store.map("mirror_regions"))
    fun mirrorKey(): String = session?.game?.id ?: ""
    fun mirrorRegion(): FloatArray =
        (mirrorRegions[mirrorKey()] ?: mirrorRegions[""])?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 }?.toFloatArray() ?: floatArrayOf(0f, 0f, 1f, 1f)
    fun setMirrorRegion(r: FloatArray) {
        mirrorRegions = mirrorRegions + (mirrorKey() to r.joinToString(","))
        store.putMap("mirror_regions", mirrorRegions)
    }

    // ---- focus / session ----
    var focused by mutableStateOf<Game?>(null)      // sticky: drives wallpapers
    var session by mutableStateOf<Session?>(null)

    // ---- settings ----
    val flags = mutableStateMapOf<String, Boolean>().apply { putAll(store.allBools()) }
    init {
        // Eclipse Arcade has been taken out for now: its games leave On Deck on installs that had them
        if (recent.any { it.startsWith("ea:") }) {
            recent = recent.filterNot { it.startsWith("ea:") }
            store.putList("recent", recent)
        }
    }

    /** First-run setup (permissions → ROM folder → connections). Not shown to anyone who already has a ROM folder. */
    var setupOpen by mutableStateOf(!(flags["setup_done"] ?: false) && romPath.isBlank())
    var setupStep by mutableStateOf(0)
    /** Opens a web page in the browser (Credits links). */
    fun openUrl(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            message = "No browser to open $url"
        }
    }
    // ---- Walkthrough button (the bottom screen's Now Playing page): GameFAQs in Eclipse's own viewer ----
    /** GameFAQs' search for a game, the default for the Walkthrough button. */
    fun gameFaqsSearch(name: String): String = "https://gamefaqs.gamespot.com/search?game=" + java.net.URLEncoder.encode(name, "UTF-8")
    /** What you changed a game's walkthrough address to, by game id (blank or missing = GameFAQs for the game). */
    var walkTexts by mutableStateOf(store.map("walk_texts"))
        private set
    fun walkText(g: Game) = walkTexts[g.id] ?: gameFaqsSearch(g.name)
    fun setWalkText(g: Game, t: String) {
        val v = t.trim()
        walkTexts = if (v.isEmpty() || v == gameFaqsSearch(g.name)) walkTexts - g.id else walkTexts + (g.id to v)
        store.putMap("walk_texts", walkTexts)
    }
    /** The address to open: what's typed if it's a web address (https:// added if missing); anything else is searched on GameFAQs. */
    fun walkUrl(g: Game, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return gameFaqsSearch(g.name)
        val isUrl = !t.contains(' ') && (t.startsWith("http://") || t.startsWith("https://") ||
            Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?$").matches(t))
        return if (isUrl) (if (t.startsWith("http")) t else "https://$t") else gameFaqsSearch(t)
    }

    /** Eclipse's version, from the installed package. */
    // ---- Eclipse's own updates (data/Updater.kt): checked at start, offered in a popup (UpdateSheet) ----
    /** A newer release waiting for an answer: the update popup shows while this is set. */
    var update by mutableStateOf<com.eclipse.launcher.data.Updater.Release?>(null)
    /** Download progress of the update (0..1, -1 = size unknown), null when not downloading. */
    var updateProgress by mutableStateOf<Float?>(null)
    /** What the last check found, for Settings ("Up to date", "Couldn't reach GitHub"…). */
    var updateStatus by mutableStateOf<String?>(null)
    private var updateFile: File? = null

    /** Looks for a newer Eclipse. At start ([manual] false) a version you skipped isn't offered again. */
    fun checkForUpdate(manual: Boolean) {
        if (manual) updateStatus = "Checking\u2026"
        viewModelScope.launch {
            val r = com.eclipse.launcher.data.Updater.latest()
            val cur = appVersion()
            when {
                r == null -> updateStatus = if (manual) "Couldn't reach GitHub" else updateStatus
                !com.eclipse.launcher.data.Updater.isNewer(r.version, cur) -> updateStatus = "Up to date (${cur})"
                !manual && store.str("update_skip") == r.version -> updateStatus = "Version ${r.version} skipped"
                else -> { updateStatus = "Version ${r.version} available"; update = r }
            }
        }
    }

    /** "Update now": download, then Android's installer (after allowing Eclipse to install apps, if needed). */
    fun startUpdate() {
        val r = update ?: return
        if (updateProgress != null) return
        updateFile?.takeIf { it.isFile }?.let { installUpdate(it); return }
        updateProgress = 0f
        viewModelScope.launch {
            var shown = -2f
            val f = com.eclipse.launcher.data.Updater.download(ctx, r) { p ->
                // (every 1 %, not every chunk)
                if (p < 0f || p - shown >= 0.01f || p >= 1f) { shown = p; viewModelScope.launch(Dispatchers.Main) { updateProgress = p } }
            }
            updateProgress = null
            if (f == null) { message = "Couldn't download the update. Try again later."; return@launch }
            updateFile = f
            installUpdate(f)
        }
    }

    private fun installUpdate(f: File) {
        if (!com.eclipse.launcher.data.Updater.canInstall(ctx)) {
            // Android asks once: allow Eclipse to install apps, then press Update again
            message = "Allow Eclipse to install apps, then press Update again"
            com.eclipse.launcher.data.Updater.askInstallPermission(ctx)
            return
        }
        if (!com.eclipse.launcher.data.Updater.install(ctx, f)) message = "Couldn't open Android's installer"
    }

    /** The update popup is up (not over the opening animation, the setup wizard or the tour). */
    val updateShowing: Boolean get() = update != null && splashDone && !setupOpen && !tutorialOpen

    fun updateLater() { if (updateProgress == null) update = null }
    fun skipUpdate() { update?.let { store.putStr("update_skip", it.version) }; if (updateProgress == null) update = null }

    fun appVersion(): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Setup's Artwork page was passed: fetch artwork for the whole library (once the scan is done), in the background. */
    private var setupScrapeStarted = false
    fun scrapeAfterSetup() {
        if (setupScrapeStarted) return
        setupScrapeStarted = true
        viewModelScope.launch {
            while (scanning) delay(1000)
            if (games.any { it.platform != "android" } && artProviders().isNotEmpty()) scrapeAll(false)
        }
    }

    fun finishSetup() { setFlag("setup_done", true); setupOpen = false; setupStep = 0 }
    fun runSetupAgain() { setupStep = 0; setupOpen = true }
    // passwords and API keys: encrypted with a key held in Android's Keystore (see Secrets)
    val keys = mutableStateMapOf<String, String>().apply { putAll(com.eclipse.launcher.data.Secrets.load(store)) }

    private val raCache = HashMap<String, RaProgress?>()

    // ---- background persistence: one writer thread, writes batched every ~0.5 s ----
    private val writer = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var saveJob: Job? = null

    private var resumed = false
    private var lastAppsLoad = 0L
    private var appsJob: Job? = null

    // ---- app install / uninstall events (instead of reloading every app on every resume) ----
    private val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = appsChanged()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = appsChanged()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = appsChanged()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = appsChanged()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = appsChanged()
    }

    private class Snapshot(
        val games: List<Game>,
        val art: Map<String, String>,
        val desc: Map<String, String>,
        val playtime: Map<String, String>,
        val hashes: Map<String, String>,
        val romm: Map<String, String>,
        val rommLinks: Map<String, String>
    )

    init {
        viewModelScope.launch {
            val snap = withContext(Dispatchers.IO) {
                LaunchBox.load(ctx)
                Snapshot(
                    store.loadGamesFile(GAMES_FILE, "games"),
                    store.loadMapFile(ART_FILE, "art"),
                    store.loadMapFile(DESC_FILE, "desc"),
                    store.loadMapFile(PLAY_FILE, "playtime"),
                    store.loadMapFile(HASH_FILE, "hasheous"),
                    store.loadMapFile(ROMM_FILE, "romm_legacy"),
                    store.loadMapFile(ROMM_LINK_FILE, "romm_links_legacy")
                )
            }
            games = withoutRemoved(snap.games)
            art = snap.art
            desc = snap.desc
            playtime = snap.playtime
            hashInfo = snap.hashes
            rommRoms = snap.romm
            rommLink = snap.rommLinks
            rebuildCatalog()
            // RomM games used to sit in the library as "not downloaded" entries; they live in the RomM store now
            if (games.any { it.id.startsWith("r_") }) { games = games.filter { !it.id.startsWith("r_") }; persistGames() }
            restoreSession()
            loaded = true
            noteAdded()
            // a newer Eclipse on GitHub? (Settings → General → Updates; off: only "Check for updates" asks)
            com.eclipse.launcher.data.Updater.cleanUp(ctx)
            if (flag("update_check", true)) checkForUpdate(manual = false)
            if (resumed) endStaleSession()
            refreshApps()
            loadThemes()
            loadPacks()
            // the picked ROM folder's SAF link, for emulators that only open games that way (Flycast)
            Launch.romTree = store.str("romTree").ifEmpty { null }
            Launch.romTreePath = romPath.ifEmpty { null }
            scanRaCores(force = false)
            if (rommConn() != null) rommSync(quiet = true) // pick up games added on the server since last time
            media.start()
            try { launcherApps.registerCallback(appsCallback, Handler(Looper.getMainLooper())) } catch (e: Exception) { }
        }
    }

    fun flag(k: String, d: Boolean = true): Boolean = flags[k] ?: d
    fun setFlag(k: String, v: Boolean) { flags[k] = v; store.putBool(k, v) }
    fun key(k: String): String = keys[k] ?: ""
    fun setKey(k: String, v: String) { keys[k] = v; com.eclipse.launcher.data.Secrets.save(store, keys.toMap()) }

    /** The dock's Settings button has focus on Home: Settings slides up into view as a preview (A, or Right, opens it). */
    var settingsPeek by mutableStateOf(false)
    /** The screen Settings was opened from (it slides away underneath as Settings comes up). */
    var prevScreen: Screen = Screen.HOME
    /** When the screen last changed: an A press that opened a screen mustn't also start a game on it. */
    var lastNavMs = 0L
    fun go(s: Screen) { lastNavMs = System.currentTimeMillis(); if (s == Screen.SETTINGS && screen != Screen.SETTINGS) prevScreen = screen; settingsPeek = false; menuGame = null; artGame = null; dockPeek = false; platDrawer = false; gamesDrawer = false; deckDrawer = false; appsDrawer = false; platReorder = false; screen = s
        CrashTrail.note(when (s) {
            Screen.HOME -> "Home"; Screen.APPS -> "Apps"; Screen.SETTINGS -> "Settings"
            Screen.PLATFORMS -> platformSel?.let { com.eclipse.launcher.data.Platforms.byId(it)?.short } ?: "All games"
        })
    }
    /** Settings → General → Crash reports is open (it replaces the General page). */
    var crashPage by mutableStateOf(false)

    fun onResume() {
        resumed = true
        permTick++
        // "All files access" just granted: move themes and packs into the Eclipse folder and show them from there
        if (!homeInUse && com.eclipse.launcher.data.EclipseHome.usable()) reloadImages(quiet = true)
        media.start()
        if (!loaded) return
        endStaleSession()
        scanRaCores()
        if (System.currentTimeMillis() - lastAppsLoad > APPS_SAFETY_REFRESH_MS) refreshApps()
        if (scrapeNew()) rescan(full = false, quiet = true)
    }

    fun onPause() {
        resumed = false
        flush()
    }

    // ---- persistence helpers ----
    private fun saveLater(name: String, write: () -> Unit) {
        pending[name] = write
        if (saveJob?.isActive != true) {
            saveJob = viewModelScope.launch {
                delay(500)
                flush()
            }
        }
    }

    /** Writes everything that is waiting, on the writer thread. Safe to call any time. */
    fun flush() {
        if (pending.isEmpty()) return
        val work = pending.values.toList()
        pending.clear()
        viewModelScope.launch(writer) { work.forEach { runCatching { it() } } }
    }

    // Each captures an immutable snapshot now; JSON is built and written later on the writer thread.
    // (Eclipse Arcade, the games that came with Eclipse, has been taken out for now)
    /** The library without games from things Eclipse no longer has (Eclipse Arcade's, saved by older versions). */
    private fun withoutRemoved(list: List<Game>): List<Game> = list.filter { !it.id.startsWith("ea:") && it.platform != "eclipse-arcade" }

    private fun persistGames() { val v = games; saveLater(GAMES_FILE) { store.saveGamesFile(GAMES_FILE, v) }; noteAdded() }
    private fun persistArt() { val v = art; saveLater(ART_FILE) { store.saveMapFile(ART_FILE, v) } }
    private fun persistDesc() { val v = desc; saveLater(DESC_FILE) { store.saveMapFile(DESC_FILE, v) } }
    private fun persistHashes() { val v = hashInfo; saveLater(HASH_FILE) { store.saveMapFile(HASH_FILE, v) } }
    private fun persistPlaytime() { val v = playtime; saveLater(PLAY_FILE) { store.saveMapFile(PLAY_FILE, v) } }

    // ---- apps ----
    private fun appsChanged() {
        appsJob?.cancel()
        appsJob = viewModelScope.launch {
            delay(600) // installs/updates fire several events in a row
            refreshApps()
        }
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = AppsLoader.load(ctx)
            withContext(Dispatchers.Main) {
                apps = list
                lastAppsLoad = System.currentTimeMillis()
                if (flag("auto_scan", true)) scanInstalledApps()
                else if (list.isNotEmpty() && loaded) {
                    // an Android game you uninstalled leaves the library even with auto-scan off
                    val have = list.map { it.pkg }.toSet()
                    val kept = games.filter { it.platform != "android" || it.path in have }
                    if (kept.size != games.size) { games = kept; persistGames() }
                }
            }
        }
    }

    fun nameOf(a: AppEntry): String = renames[a.pkg] ?: a.label

    fun catOf(a: AppEntry): String = cats[a.pkg] ?: when (a.sysCategory) {
        0 -> "Games"
        1, 2, 3 -> "Media"
        4, 5 -> "Social"
        6, 7 -> "Tools"
        else -> "Other"
    }

    /** Favourite apps, shown first in the app drawer. */
    var favApps by mutableStateOf(store.list("fav_apps"))
    fun toggleFav(pkg: String) {
        favApps = if (pkg in favApps) favApps - pkg else favApps + pkg
        store.putList("fav_apps", favApps)
    }
    var appCat by mutableStateOf("All")                   // app drawer category (its right-hand drawer)
    var appsDrawer by mutableStateOf(false)               // the app drawer's right-hand drawer
    /** A right-hand drawer is open: screens must not pull focus out of it when they change underneath. */
    fun drawerOpen(): Boolean = platDrawer || gamesDrawer || deckDrawer || appsDrawer

    /** Systems pinned to the left dock (ids, in the order pinned), under Pinned above the apps. */
    var dockSystems by mutableStateOf(store.list("dock_systems"))
        private set
    fun toggleDockSystem(id: String) {
        dockSystems = if (id in dockSystems) dockSystems - id
            else if (dockSystems.size >= 6) { message = "The dock holds up to 6 systems"; dockSystems } else dockSystems + id
        store.putList("dock_systems", dockSystems)
    }

    /**
     * Settings → Reload Eclipse: writes what's waiting, then starts Eclipse again from scratch (a fresh process), so
     * folders, themes, packs, add-ons and art are all read again.
     */
    fun reloadEclipse() {
        message = "Reloading Eclipse\u2026"
        viewModelScope.launch {
            val work = pending.values.toList()
            pending.clear()
            withContext(writer) { work.forEach { runCatching { it() } } }
            delay(500) // (lets SharedPreferences' own background writes finish)
            val restart = com.eclipse.launcher.media.SecondScreenHost.restart
            if (restart == null) { message = "Couldn't reload: close Eclipse and open it again"; return@launch }
            restart()
        }
    }

    fun toggleDock(pkg: String) {
        dock = if (pkg in dock) dock - pkg else if (dock.size >= 4) { message = "The dock holds up to 4 apps"; dock } else dock + pkg
        store.putList("dock", dock)
    }

    /**
     * Moves a second-screen dock app to slot [to] (0..3, left to right, the app-drawer button not counted). An empty
     * slot just takes it; an app already there swaps places with it.
     */
    fun moveDock2(pkg: String, to: Int) {
        val from = dock2.indexOf(pkg)
        if (from < 0) return
        val t = to.coerceIn(0, 3)
        if (t == from) return
        dock2 = dock2.toMutableList().apply { this[from] = this[t]; this[t] = pkg }
        store.putList("dock2", dock2)
    }
    /** The second-screen dock's look ("glass", "solid", "clear" = floating icons, "bar" = full width) and icon size. */
    var dock2Style by mutableStateOf(store.str("dock2_style", "glass"))
        private set
    fun chooseDock2Style(v: String) { dock2Style = v; store.putStr("dock2_style", v) }
    var dock2Size by mutableStateOf(store.str("dock2_size", "medium"))
        private set
    fun chooseDock2Size(v: String) { dock2Size = v; store.putStr("dock2_size", v) }

    fun toggleDock2(pkg: String) {
        val slot = dock2.indexOf(pkg)
        val free = dock2.indexOf("")
        dock2 = when {
            slot >= 0 -> dock2.toMutableList().apply { this[slot] = "" }           // its slot stays empty
            free < 0 -> { message = "The second-screen dock holds 4 apps (2 each side of the app drawer)"; dock2 }
            else -> dock2.toMutableList().apply { this[free] = pkg }               // the first empty slot
        }
        store.putList("dock2", dock2)
    }

    fun rename(pkg: String, name: String) {
        renames = if (name.isBlank()) renames - pkg else renames + (pkg to name.trim())
        store.putMap("renames", renames)
    }

    fun setCat(pkg: String, cat: String) { cats = cats + (pkg to cat); store.putMap("cats", cats) }

    private fun activityOf(pkg: String): String? = apps.firstOrNull { it.pkg == pkg }?.activity

    fun openApp(pkg: String) {
        dockPeek = false
        AppsLoader.launch(ctx, pkg, activityOf(pkg))?.let { message = "Couldn't open that app: $it" }
    }

    /** Opens an app on a specific display (used by the bottom-screen dock). Falls back to the main screen. */
    fun openAppOn(pkg: String, displayId: Int) {
        if (displayId == com.eclipse.launcher.media.Screens.mainId) { openApp(pkg); return }
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        if (AppsLoader.launch(ctx, pkg, activityOf(pkg), opts, displayId) == null) {
            com.eclipse.launcher.media.SecondScreenHost.hideForApp?.invoke(pkg, displayId)
        } else {
            openApp(pkg) // that screen refused it: open on the main screen instead
        }
    }

    /** Remembers which screen an app opens on when launched from the bottom screen. */
    fun setAppScreen(pkg: String, where: String) {
        appScreen = appScreen + (pkg to where)
        store.putMap("appScreen", appScreen)
    }

    fun resetAppScreens() {
        appScreen = emptyMap()
        store.putMap("appScreen", appScreen)
        message = "Eclipse will ask again where to open each app"
    }

    /** Launch from the bottom screen using the remembered choice. */
    fun openFromSecond(pkg: String, displayId: Int) = openOnScreen(pkg, appScreen[pkg] ?: "top", displayId)

    /** Launch from the bottom screen on the screen picked in the bubble ("top" or "bottom"). */
    fun openOnScreen(pkg: String, where: String, displayId: Int) {
        if (where == "bottom") openAppOn(pkg, displayId) else openApp(pkg)
    }

    // ---- library ----
    fun setRomFolder(path: String, tree: String? = null) {
        romPath = path
        store.putStr("romPath", path)
        if (tree != null) store.putStr("romTree", tree)
        Launch.romTree = tree ?: store.str("romTree").ifEmpty { null }
        Launch.romTreePath = path
        rescan()
    }

    /** Every ROM folder: the main one first, then the ones added after it. */
    fun romFolders(): List<String> = (listOf(romPath) + extraFolders).filter { it.isNotBlank() }.distinct()

    fun foldersFor(platformId: String): List<String> =
        sysFolders[platformId].orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The folders a system's games come from: sub-folders of your ROM folders named after it (snes, gba…),
     * worked out from where its games are, plus any folder added for it by hand.
     */
    fun detectedFoldersFor(platformId: String): List<String> {
        val roots = romFolders().map { File(it).absolutePath }.toSet()
        val added = foldersFor(platformId).toSet()
        return games.asSequence().filter { it.platform == platformId }.mapNotNull { g ->
            var d = File(g.path).parentFile ?: return@mapNotNull null
            if (added.any { g.path.startsWith("$it/") }) return@mapNotNull null
            var dir = d
            // climb to the highest folder named after the system, stopping at a ROM folder
            while (d.parentFile != null && d.absolutePath !in roots) {
                if (Platforms.matchFolder(d.name)?.id == platformId) dir = d
                d = d.parentFile!!
            }
            dir.absolutePath
        }.distinct().sorted().toList()
    }

    // ---- PlayStation Vita: shortcuts for the games installed in Vita3K (data/Vita.kt) ----

    /** Vita3K's storage folder, picked in the Vita system editor; "" = look in the usual places. */
    var vita3kDir by mutableStateOf(store.str("vita3k_dir"))
        private set
    /** What the last look at Vita3K found (shown in the Vita system editor). null = not looked yet. */
    var vitaNote by mutableStateOf<String?>(null)
        private set

    fun chooseVita3kDir(path: String?) {
        vita3kDir = path.orEmpty()
        store.putStr("vita3k_dir", vita3kDir)
        rescanSystem("psvita", scrapeAfter = true)
    }

    /**
     * Runs before every scan: each game installed in Vita3K gets a "<Title>.psvita" in your Vita folder (the one set
     * for the system, else one found in your ROM folders, else "psvita" in the main ROM folder, made only when there are
     * games). Shortcuts Eclipse made for games since removed from Vita3K go; yours are left alone.
     */
    private fun syncVita() {
        val root = com.eclipse.launcher.data.Vita.findRoot(vita3kDir)
        if (root == null) {
            vitaNote = if (vita3kDir.isNotBlank()) "No installed games found in that folder (it needs ux0/app inside)."
                else "Vita3K's folder wasn't found. In Vita3K, set its storage folder to a normal folder (like Vita3K at the root of your storage), or choose it below."
            return
        }
        val installed = com.eclipse.launcher.data.Vita.installed(root)
        val folders = (foldersFor("psvita") + detectedFoldersFor("psvita")).distinct().map { File(it) }
        val dest = folders.firstOrNull() ?: romPath.takeIf { it.isNotBlank() }?.let { File(it, "psvita") }
        if (dest == null) { vitaNote = "Choose a ROM folder first."; return }
        val r = com.eclipse.launcher.data.Vita.sync(installed, dest, folders)
        vitaNote = "${installed.size} ${if (installed.size == 1) "game" else "games"} installed in Vita3K (${root.absolutePath})" +
            (if (r.made > 0) " \u00b7 ${r.made} added" else "") + (if (r.removed > 0) " \u00b7 ${r.removed} removed" else "")
    }

    /** The folder picker came back. The first folder becomes the main ROM folder; later ones are added to a list. */
    fun onFolderPicked(path: String, tree: String?) {
        val target = folderTarget
        folderTarget = null
        when {
            target == "@main" -> setRomFolder(path, tree) // setup: choose / replace the main folder
            target == "@vita3k" -> chooseVita3kDir(path) // the Vita system editor: where Vita3K keeps its games
            target != null -> {
                val cur = foldersFor(target)
                if (path in cur) { message = "That folder is already added"; return }
                sysFolders = sysFolders + (target to (cur + path).joinToString("\n"))
                store.putMap("sys_folders", sysFolders)
                rescanSystem(target, scrapeAfter = false)
            }
            romPath.isBlank() -> setRomFolder(path, tree)
            path in romFolders() -> message = "That folder is already added"
            else -> {
                extraFolders = extraFolders + path
                store.putList("rom_folders", extraFolders)
                rescan(full = false)
            }
        }
    }

    /** Takes a folder off the list (the first extra one takes over as the main folder). Its games go on the next scan. */
    fun removeFolder(path: String) {
        if (path == romPath) {
            val next = extraFolders.firstOrNull().orEmpty()
            romPath = next
            store.putStr("romPath", next)
            extraFolders = extraFolders.drop(1)
        } else extraFolders = extraFolders - path
        store.putList("rom_folders", extraFolders)
        rescan(full = true)
    }

    fun removeSysFolder(platformId: String, path: String) {
        val left = foldersFor(platformId) - path
        sysFolders = if (left.isEmpty()) sysFolders - platformId else sysFolders + (platformId to left.joinToString("\n"))
        store.putMap("sys_folders", sysFolders)
        rescanSystem(platformId, scrapeAfter = false)
    }

    /** Everything in all the folders, as one list (no duplicates when folders overlap). */
    private fun scanAll(): List<Game> {
        runCatching { syncVita() }
        val out = LinkedHashMap<String, Game>()
        romFolders().forEach { f -> Scanner.scan(f).forEach { out.putIfAbsent(it.path, it) } }
        // a folder added for one system: the system is known, so only its file types count
        sysFolders.keys.forEach { id -> foldersFor(id).forEach { f -> Scanner.scan(f, id).forEach { out[it.path] = it } } }
        // arcade ROMs named like "simpsons2p" get their real titles if the LaunchBox data knows them
        // a title you set on the game's page wins over everything
        val aliased = out.values.map { g -> idAlias[g.path]?.let { g.copy(id = it) } ?: g }.distinctBy { it.id }
        return aliased.map { g -> metaTitle(g.id)?.let { g.copy(name = it) } ?: LaunchBox.titleFor(g)?.let { g.copy(name = it) } ?: g }
    }

    /**
     * [full] = start over from what's on disk (drops games whose files are gone).
     * Otherwise only adds games that aren't in the library yet.
     */
    // ---- Settings → Tools (CHD) ----

    /** A disc image that can become a CHD. [parts] = every file it's made of (the .cue and its .bins, or the .iso). */
    /**
     * [rvz]: a GameCube / Wii disc, which becomes RVZ (Dolphin's format: Dolphin can't play CHD); the rest become CHD.
     * [psp]: a PSP disc (its CHD must be DVD-style with 2048-byte hunks for PPSSPP).
     */
    data class ChdItem(
        val src: java.io.File, val parts: List<java.io.File>, val bytes: Long, val done: Boolean,
        val rvz: Boolean = false, val psp: Boolean = false,
        /** a cartridge ROM, compressed into a .zip (data/RomZip.kt) */
        val zip: Boolean = false,
        /** an original Xbox disc, rebuilt as an XISO (data/XIso.kt): what xemu plays, often gigabytes smaller */
        val xiso: Boolean = false,
        /** a 3DS file compressed for Azahar (.zcci .zcxi .z3dsx .zcia; data/Archives.kt) */
        val z3ds: com.eclipse.launcher.data.Archives.Z3ds? = null,
        /** an Xbox 360 disc packed as a ZArchive (.zar), for Xenia-based emulators (data/Archives.kt) */
        val zar: Boolean = false,
        /** a Wii U disc image (.wud) with its repeated sectors stored once (.wux; data/Wux.kt), for Cemu */
        val wux: Boolean = false,
        /** a decrypted Wii U game folder packed as Cemu's .wua (data/Archives.kt); [src] is its .rpx */
        val wua: com.eclipse.launcher.data.Archives.WiiUTitle? = null,
        /** where the result goes when it isn't "<src's name>.<ext>" beside [src] (a .wua goes beside the game's folder) */
        val dest: java.io.File? = null,
    ) {
        val ext: String get() = z3ds?.ext ?: if (wua != null) "wua" else if (wux) "wux" else if (zar) "zar" else if (xiso) "xiso.iso" else if (zip) "zip" else if (rvz) "rvz" else "chd"
        /** what it becomes, as people know it */
        val format: String get() = if (xiso) "XISO" else ext.uppercase()
    }

    /** A game's own file as something to compress: a cartridge ROM → ZIP, a 3DS file → Z3DS (Azahar). Null otherwise. */
    private fun romItem(g: Game): ChdItem? {
        zipItem(g)?.let { return it }
        if (g.platform == "wiiu") return wiiuItem(java.io.File(g.path))
        if (g.platform != "3ds") return null
        val f = java.io.File(g.path)
        val kind = com.eclipse.launcher.data.Archives.z3dsKind(f) ?: return null
        if (java.io.File(f.parentFile, f.nameWithoutExtension + "." + kind.ext).exists()) return null
        return ChdItem(f, listOf(f), f.length(), false, z3ds = kind)
    }

    /** A Wii U game to compress: a .wud → .wux, or a decrypted game folder (found by its .rpx) → .wua. */
    private fun wiiuItem(f: java.io.File): ChdItem? {
        if (com.eclipse.launcher.data.Wux.isWud(f)) {
            if (java.io.File(f.parentFile, f.nameWithoutExtension + ".wux").exists()) return null
            return ChdItem(f, listOf(f), f.length(), false, wux = true)
        }
        val t = com.eclipse.launcher.data.Archives.wiiuTitle(f) ?: return null
        val out = java.io.File(t.dir.parentFile, t.dir.name + ".wua")
        if (out.exists()) return null
        val bytes = t.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ChdItem(f, listOf(t.dir), bytes, false, wua = t, dest = out)
    }

    /**
     * An Xbox disc image worth rebuilding as an XISO: a Redump image (video partition and padding) or an XISO that
     * still carries padding. Null when it isn't one, or the rebuild would save less than 64 MB.
     */
    private fun xisoItem(f: java.io.File, done: Boolean): ChdItem? {
        if (!f.extension.equals("iso", true)) return null
        val newSize = runCatching { com.eclipse.launcher.data.XIso.rebuiltSize(f) }.getOrNull() ?: return null
        if (f.length() - newSize < 64L * 1024 * 1024) return null
        return ChdItem(f, listOf(f), f.length(), done, xiso = true)
    }

    /** [g]'s ROM as something to zip, or null (not a zip system, compressed already, zipped beside it …). */
    private fun zipItem(g: Game): ChdItem? {
        val f = java.io.File(g.path)
        if (!com.eclipse.launcher.data.RomZip.canZip(f, g.platform)) return null
        return ChdItem(f, listOf(f), f.length(), false, zip = true)
    }

    /**
     * A converted file that replaced its original (originals deleted) keeps the original's game id, so its artwork,
     * play time, favourite, notes … stay with it. new path → id. Applied by `scanAll`.
     */
    private var idAlias: Map<String, String> = store.map("id_alias")
    private fun keepId(old: java.io.File, new: java.io.File) {
        val id = idAlias[old.absolutePath] ?: ("g" + Integer.toHexString(old.absolutePath.hashCode()))
        idAlias = (idAlias - old.absolutePath) + (new.absolutePath to id)
        store.putMap("id_alias", idAlias)
    }

    /**
     * The system a disc belongs to: its game in the library, a system folder it's in, or a folder (up to 4 levels up)
     * named after a system. null = can't tell.
     */
    private fun discSystem(f: java.io.File, platformId: String? = null): com.eclipse.launcher.data.PlatformDef? {
        platformId?.let { id -> com.eclipse.launcher.data.Platforms.byId(id)?.let { return it } }
        val path = f.absolutePath
        games.firstOrNull { it.path == path }?.let { g -> com.eclipse.launcher.data.Platforms.byId(g.platform)?.let { return it } }
        for ((id, paths) in sysFolders) {
            if (paths.split('\n').map { it.trim() }.any { it.isNotEmpty() && inDir(f, it) })
                com.eclipse.launcher.data.Platforms.byId(id)?.let { return it }
        }
        var d = f.parentFile
        repeat(4) {
            if (d == null) return null
            com.eclipse.launcher.data.Platforms.matchFolder(d!!.name)?.let { return it }
            d = d!!.parentFile
        }
        return null
    }

    /**
     * What [f] can be converted to, or null when its system's emulators can't use the result. Goes by the system's own
     * file types in the catalog (systems.json): systems that list .chd get CHD, GameCube / Wii (listing .rvz) get RVZ.
     * Xbox, Xbox 360, PS3, Wii U, DOS and the like list neither, so their discs are left alone. A disc of an unknown
     * system: GameCube / Wii by its header → RVZ, a .cue → CHD (every system that uses .cue sheets reads CHD), an .iso → no.
     */
    private fun discItem(f: java.io.File, done: Boolean, platformId: String? = null): ChdItem? {
        if (!isConvertible(f)) return null
        val parts = if (f.extension.equals("cue", true)) com.eclipse.launcher.data.Chd.cueFiles(f) else listOf(f)
        val bytes = parts.sumOf { it.length() }
        val sys = discSystem(f, platformId)
        // original Xbox: no CHD for it (xemu can't read it), but a Redump image can become a much smaller XISO
        if (sys?.id == "xbox" || sys == null) xisoItem(f, done)?.let { return it }
        if (sys?.id == "xbox") return null
        // Xbox 360: a ZArchive (.zar), which Xenia Canary and X360 Mobile read
        if (sys?.id == "xbox360" || sys == null) {
            if (f.extension.equals("iso", true) && runCatching { com.eclipse.launcher.data.Archives.zarSource(f) }.getOrDefault(false))
                return ChdItem(f, listOf(f), f.length(), done, zar = true)
            if (sys?.id == "xbox360") return null
        }
        val nintendo = !f.extension.equals("cue", true) && com.eclipse.launcher.data.Rvz.isNintendoDisc(f)
        return when {
            sys == null -> when {
                nintendo -> ChdItem(f, parts, bytes, done, rvz = true)
                f.extension.equals("cue", true) -> ChdItem(f, parts, bytes, done)
                else -> null
            }
            "rvz" in sys.exts && nintendo -> ChdItem(f, parts, bytes, done, rvz = true)
            "chd" in sys.exts && !f.extension.equals("gcm", true) ->
                ChdItem(f, parts, bytes, done, psp = sys.id == "psp" || sys.id == "pspminis")
            else -> null
        }
    }

    /** What a disc becomes: "Game.rvz" for a GameCube / Wii disc, "Game.chd" for the rest. */
    private fun convertedOf(disc: java.io.File): java.io.File =
        java.io.File(disc.parentFile, disc.nameWithoutExtension + if (com.eclipse.launcher.data.Rvz.isNintendoDisc(disc)) ".rvz" else ".chd")
    /** A .chd or .rvz of this disc is already there. */
    private fun alreadyConverted(disc: java.io.File): Boolean =
        java.io.File(disc.parentFile, disc.nameWithoutExtension + ".chd").exists() || java.io.File(disc.parentFile, disc.nameWithoutExtension + ".rvz").exists() ||
            java.io.File(disc.parentFile, disc.nameWithoutExtension + ".xiso.iso").exists() ||
            java.io.File(disc.parentFile, disc.nameWithoutExtension + ".zar").exists()
    /** Disc images the converter takes: .cue sheets, .iso (and .gcm, GameCube's own). */
    private fun isConvertible(f: java.io.File) = f.extension.lowercase().let { it == "cue" || it == "iso" || it == "gcm" }
    /** null = not scanned yet */
    var chdItems by mutableStateOf<List<ChdItem>?>(null)
    var chdPicked by mutableStateOf<Set<String>>(emptySet())
    var chdScanning by mutableStateOf(false)
    var chdDelete by mutableStateOf(store.str("chd_delete") == "1")
    fun chooseChdDelete(on: Boolean) { chdDelete = on; store.putStr("chd_delete", if (on) "1" else "") }
    /** while converting: what's happening now */
    data class ChdJob(val index: Int, val total: Int, val name: String, val phase: String, val progress: Float, val rvz: Boolean = false, val zip: Boolean = false, val xiso: Boolean = false, val other: String? = null) {
        /** what it's turning into: "CHD", "RVZ", "ZIP", "XISO", or [other] ("ZCCI", "ZAR" …) */
        val format: String get() = other ?: if (xiso) "XISO" else if (zip) "ZIP" else if (rvz) "RVZ" else "CHD"
    }
    var chdJob by mutableStateOf<ChdJob?>(null)
    /** one line per disc when a run finishes */
    var chdLog by mutableStateOf<List<String>>(emptyList())
    @Volatile private var chdCancel = false
    /** Space saved by every CHD made so far, and how many discs (shown in Settings → Tools). */
    var chdSavedTotal by mutableStateOf(store.str("chd_saved_total").toLongOrNull() ?: 0L)
        private set
    var chdDiscsTotal by mutableStateOf(store.str("chd_discs_total").toIntOrNull() ?: 0)
        private set
    private fun addChdSaved(bytes: Long, discs: Int) {
        chdSavedTotal += maxOf(0L, bytes); chdDiscsTotal += discs
        store.putStr("chd_saved_total", chdSavedTotal.toString()); store.putStr("chd_discs_total", chdDiscsTotal.toString())
    }

    // ---- Tools → Shrink artwork: Eclipse's own PNGs re-saved as lossless WebP (data/WebpShrink.kt) ----
    /** PNGs in the art folder still worth trying, and their size; null = not looked yet. */
    data class WebpScan(val files: List<File>, val bytes: Long, val allFiles: Int = 0, val allBytes: Long = 0L, val webp: Int = 0)
    var webpScan by mutableStateOf<WebpScan?>(null)
        private set
    data class WebpJob(val index: Int, val total: Int, val saved: Long, val shrunk: Int = 0, val kept: Int = 0, val failed: Int = 0, val name: String = "")
    var webpJob by mutableStateOf<WebpJob?>(null)
        private set
    @Volatile private var webpCancel = false
    var webpSavedTotal by mutableStateOf(store.str("webp_saved_total").toLongOrNull() ?: 0L)
        private set
    var webpFilesTotal by mutableStateOf(store.str("webp_files_total").toIntOrNull() ?: 0)
        private set

    fun scanWebp() {
        if (webpJob != null) return
        viewModelScope.launch {
            webpScan = withContext(Dispatchers.IO) {
                val list = com.eclipse.launcher.data.WebpShrink.candidates(artDir, store.list("webp_skip").toSet())
                val all = artDir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }
                WebpScan(
                    list, list.sumOf { it.length() }, all.size, all.sumOf { it.length() },
                    all.count { com.eclipse.launcher.data.WebpShrink.mimeOf(it) == "image/webp" }
                )
            }
        }
    }

    fun startWebp() {
        if (webpJob != null) return
        if (!com.eclipse.launcher.data.WebpShrink.supported) { message = "Lossless WebP needs Android 10 or newer"; return }
        val files = webpScan?.files.orEmpty()
        if (files.isEmpty()) { message = "Nothing to shrink"; return }
        webpCancel = false
        webpJob = WebpJob(0, files.size, 0L)
        viewModelScope.launch {
            var saved = 0L; var n = 0; var kept = 0; var failed = 0
            val skip = store.list("webp_skip").toMutableSet()
            withContext(Dispatchers.IO) {
                for ((i, f) in files.withIndex()) {
                    if (webpCancel) break
                    withContext(Dispatchers.Main) { webpJob = WebpJob(i, files.size, saved, n, kept, failed, f.name) }
                    if (!f.isFile) continue
                    when (val r = com.eclipse.launcher.data.WebpShrink.convert(f)) {
                        is com.eclipse.launcher.data.WebpShrink.Result.Saved -> { saved += r.bytes; n++ }
                        com.eclipse.launcher.data.WebpShrink.Result.Kept -> { kept++; skip.add(com.eclipse.launcher.data.WebpShrink.keyOf(f)) }
                        com.eclipse.launcher.data.WebpShrink.Result.Failed -> failed++
                    }
                }
                withContext(Dispatchers.Main) { webpJob = WebpJob(files.size, files.size, saved, n, kept, failed) }
                // forget pictures that are gone
                val keys = artDir.listFiles().orEmpty().map { com.eclipse.launcher.data.WebpShrink.keyOf(it) }.toSet()
                store.putList("webp_skip", skip.filter { it in keys })
            }
            webpSavedTotal += saved; webpFilesTotal += n
            store.putStr("webp_saved_total", webpSavedTotal.toString()); store.putStr("webp_files_total", webpFilesTotal.toString())
            webpJob = null
            webpLast = "Last run: $n shrunk, $kept kept as they were" + (if (failed > 0) ", $failed couldn't be read" else "") + " \u00b7 saved ${fmtBytes(saved)}"
            message = if (n == 0) "No pictures got smaller" else "Saved ${fmtBytes(saved)} on $n picture${if (n == 1) "" else "s"}"
            scanWebp()
        }
    }

    fun cancelWebp() { webpCancel = true }
    /** What the last run did (shown under the tool until Eclipse restarts). */
    var webpLast by mutableStateOf<String?>(null)
        private set

    /** Finds .cue and .iso files in your ROM folders (and their sub-folders). */
    fun scanForChd() {
        if (chdScanning) return
        chdScanning = true
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val all = (romFolders() + sysFolders.values.flatMap { it.split('\n') }).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                // only the systems you ticked (none ticked = everything)
                val roots = if (chdScanDirs.isEmpty()) all else chdScanDirs.toList()
                val seen = HashSet<String>()
                val list = ArrayList<ChdItem>()
                // .bins a .cue uses aren't listed on their own
                for (root in roots) java.io.File(root).walkTopDown().maxDepth(4)
                    .onEnter { !it.name.startsWith(".") && it.name != "Android" }
                    .filter { it.isFile && isConvertible(it) }
                    .forEach { f ->
                        if (!seen.add(f.absolutePath)) return@forEach
                        discItem(f, alreadyConverted(f))?.let { list.add(it) }
                    }
                // cartridge ROMs that can be zipped (from the library: the system is known there)
                games.forEach { g -> romItem(g)?.takeIf { seen.add(it.src.absolutePath) }?.let { list.add(it) } }
                list.sortedWith(compareBy({ it.done }, { it.src.name.lowercase() }))
            }
            chdItems = found
            chdPicked = emptySet()   // nothing ticked until you pick (X ticks them all)
            chdScanning = false
        }
    }

    fun toggleChdPick(path: String) { chdPicked = if (path in chdPicked) chdPicked - path else chdPicked + path }

    /**
     * Converts the picked discs one after another: write "<name>.chd.part", read it all back to check it, then rename it
     * to "<name>.chd" (and delete the originals, if you asked). Multi-disc games get an .m3u playlist.
     */
    fun startChd() {
        val items = chdItems.orEmpty().filter { it.src.absolutePath in chdPicked }
        if (items.isEmpty() || chdJob != null) return
        runChd(items, chdDelete, auto = false)
    }

    /** The conversion itself (the Convert button, and auto-convert for new discs). */
    /**
     * [romm]: a disc just downloaded from RomM. Its game is added by `rommInstalled` and a multi-disc game's playlist
     * is kept up to date by `updateDiscPlaylist` as each disc is ready, so no playlists and no rescan here (both used to run after
     * each disc: a playlist of only the discs done so far, which then hid them from the library).
     */
    private fun runChd(items: List<ChdItem>, del: Boolean, auto: Boolean, romm: Boolean = false, onDone: ((saved: Long, ok: Int) -> Unit)? = null) {
        if (items.isEmpty() || chdJob != null) return
        chdCancel = false
        chdLog = emptyList()
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 6)
        viewModelScope.launch {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val lock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "eclipse:chd").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            val log = ArrayList<String>()
            var saved = 0L
            try {
                for ((i, item) in items.withIndex()) {
                    if (chdCancel) break
                    val name = item.dest?.nameWithoutExtension ?: item.src.nameWithoutExtension
                    val rvz = item.rvz
                    val ext = item.ext
                    val out = item.dest ?: java.io.File(item.src.parentFile, "$name.$ext")
                    val part = java.io.File(out.parentFile, out.name + ".part")
                    val other = item.z3ds != null || item.zar || item.wux || item.wua != null
                    chdJob = ChdJob(i, items.size, name, if (item.zip || other) "Compressing" else if (item.xiso) "Rebuilding" else "Converting", 0f, rvz, item.zip, item.xiso,
                        if (other) item.format else null)
                    val result = withContext(Dispatchers.IO) {
                        try {
                            if (item.wua != null) com.eclipse.launcher.data.Archives.makeWua(item.wua, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (item.wux) com.eclipse.launcher.data.Wux.compress(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (item.z3ds != null) com.eclipse.launcher.data.Archives.compress3ds(item.src, part, item.z3ds, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (item.zar) com.eclipse.launcher.data.Archives.makeZar(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (item.xiso) com.eclipse.launcher.data.XIso.rebuild(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (item.zip) com.eclipse.launcher.data.RomZip.zip(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else if (rvz) com.eclipse.launcher.data.Rvz.convert(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            else com.eclipse.launcher.data.Chd.convert(item.src, part, threads, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel }, psp = item.psp)
                            chdJob = chdJob?.copy(phase = "Checking", progress = 0f)
                            // RVZ: read it all back through Dolphin and compare with the original, byte for byte
                            val good = if (item.wua != null) com.eclipse.launcher.data.Archives.sameWua(item.wua, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (item.wux) com.eclipse.launcher.data.Wux.same(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (item.z3ds != null) com.eclipse.launcher.data.Archives.same3ds(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (item.zar) com.eclipse.launcher.data.Archives.sameZar(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (item.xiso) com.eclipse.launcher.data.XIso.same(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (item.zip) com.eclipse.launcher.data.RomZip.same(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else if (rvz) com.eclipse.launcher.data.Rvz.same(item.src, part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                                else com.eclipse.launcher.data.Chd.verify(part, { p -> chdJob = chdJob?.copy(progress = p) }, { chdCancel })
                            if (!good) {
                                part.delete()
                                if (chdCancel) null else "\u2717 $name: the check after converting failed, originals kept"
                            } else {
                                out.delete()
                                if (!part.renameTo(out)) throw java.io.IOException("couldn't rename the finished file")
                                saved += item.bytes - out.length()
                                if (del) {
                                    // (a .wua's original is the game's whole folder)
                                    item.parts.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
                                    // an XISO takes the original's place and name (still an .iso): the game keeps its path
                                    if (item.xiso && !out.renameTo(item.src)) keepId(item.src, out) else if (!item.xiso) keepId(item.src, out)
                                }
                                "\u2713 $name: ${fmtBytes(item.bytes)} \u2192 ${fmtBytes(out.length())}" + if (del) ", originals deleted" else ""
                            }
                        } catch (e: InterruptedException) { part.delete(); null
                        } catch (e: Exception) { part.delete(); "\u2717 $name: ${e.message ?: e.javaClass.simpleName}" }
                    }
                    result?.let { log.add(it); chdLog = log.toList() }
                }
                addChdSaved(saved, log.count { it.startsWith("\u2713") })
                onDone?.invoke(maxOf(0L, saved), log.count { it.startsWith("\u2713") })
                withContext(Dispatchers.IO) {
                    val dirs = items.map { it.dest?.parentFile ?: it.src.parentFile }.distinct().filterNotNull()
                    pointPlaylistsAtChd(dirs)
                    if (!romm) writeDiscPlaylists(dirs)
                }
                if (chdCancel) log.add("Stopped. Discs already finished are kept.")
                else log.add("Done. Saved ${fmtBytes(maxOf(0L, saved))}.")
                chdLog = log.toList()
                if (auto && !romm) {
                    // (a RomM download's conversion is told in its "ready" pop-up instead)
                    val ok = log.count { it.startsWith("\u2713") }
                    val first = items.first().src
                    val g = games.firstOrNull { it.path == first.absolutePath || it.path == convertedOf(first).absolutePath ||
                        it.path == java.io.File(first.parentFile, first.nameWithoutExtension + "." + items.first().ext).absolutePath }
                    val pic = g?.let { artOf(it, ArtKind.BOX) }
                    if (ok > 0) showNotice(Notice(if (items.all { it.xiso }) "Made XISO" else if (items.all { it.zar }) "Compressed to ZAR" else if (items.all { it.wux }) "Compressed to WUX" else if (items.all { it.wua != null }) "Compressed to WUA" else if (items.all { it.z3ds != null }) "Compressed for Azahar" else if (items.all { it.zip }) "Compressed to ZIP" else if (items.all { it.rvz }) "Converted to RVZ" else if (items.all { !it.rvz && !it.zip }) "Converted to CHD" else "Converted", if (ok == 1) (g?.name ?: first.nameWithoutExtension) else "$ok discs",
                        if (saved > 0) "Saved ${fmtBytes(saved)}" else null, pic, "chd"))
                    else if (log.any { it.startsWith("\u2717") }) showNotice(Notice("Couldn't convert", g?.name ?: first.nameWithoutExtension, "The originals were kept", pic, "error"))
                }
            } finally {
                runCatching { lock.release() }
                chdJob = null
                // whatever happened, auto-convert doesn't try these again (a failed one keeps its originals)
                if (auto) rememberChdKnown(items.map { it.src.absolutePath })
                if (!auto || chdItems != null) scanForChd()
                // originals deleted: a full rescan, so the library drops the old .cue / .iso and shows the .chd
                if (!romm) rescan(full = del)
            }
        }
    }

    fun cancelChd() { chdCancel = true }

    /** A game's page: can this one game be turned into a CHD? (an .iso or .cue with no .chd beside it yet) */
    fun canConvertToChd(g: Game): Boolean {
        if (g.platform == "android" || g.id.startsWith("rm:")) return false
        return romItem(g) != null || chdDiscsOf(g).isNotEmpty()
    }

    /**
     * The discs of [g] that could become CHDs: the game's own .cue / .iso, or, for a multi-disc game opened through an
     * .m3u playlist (RomM downloads are), every .cue / .iso the playlist lists. Ones with a .chd beside them are skipped.
     */
    private fun chdDiscsOf(g: Game): List<java.io.File> {
        val f = java.io.File(g.path)
        if (!f.isFile) return emptyList()
        val discs = if (f.extension.equals("m3u", true)) {
            runCatching { f.readLines() }.getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line -> java.io.File(line).let { if (it.isAbsolute) it else java.io.File(f.parentFile, line) } }
        } else listOf(f)
        return discs.filter { d ->
            d.isFile && isConvertible(d) && !alreadyConverted(d) && discItem(d, false, g.platform) != null
        }
    }

    /** The CHD conversion running for [g] right now, if it is (any of its discs, for a playlist). */
    fun chdJobFor(g: Game): ChdJob? = chdJob?.takeIf { j ->
        val f = java.io.File(g.path)
        j.name == f.nameWithoutExtension ||
            // a Wii U game folder (…/Game/code/game.rpx) is compressed under the folder's name
            (f.extension.equals("rpx", true) && f.parentFile?.parentFile?.name == j.name) || (f.extension.equals("m3u", true) && runCatching { f.readText() }.getOrDefault("").contains(j.name))
    }

    /**
     * Games converted from their page, and the space it saved: the page keeps its CHD row showing that until the
     * page is closed (then it's gone the next time).
     */
    val chdSavedFor = mutableStateMapOf<String, Long>()

    /** What a game's discs are converted to, by its system: "RVZ" for GameCube / Wii (Dolphin's format), else "CHD". */
    fun convertFormat(g: Game): String? {
        // a cartridge ROM of a zip system (a PC Engine CD's .cue in the same system still goes to CHD)
        if (g.platform == "xbox") return "XISO"
        if (g.platform == "xbox360") return "ZAR"
        if (g.platform == "3ds") return "Z3DS"
        if (g.platform == "wiiu") return if (java.io.File(g.path).extension.equals("rpx", true)) "WUA" else "WUX"
        if (g.platform in com.eclipse.launcher.data.RomZip.SYSTEMS && java.io.File(g.path).extension.lowercase() !in setOf("cue", "iso", "gcm", "chd", "rvz", "m3u", "gdi", "ccd", "pbp", "cso"))
            return "ZIP"
        val exts = com.eclipse.launcher.data.Platforms.byId(g.platform)?.exts ?: return null
        return when { "rvz" in exts -> "RVZ"; "chd" in exts -> "CHD"; else -> null }
    }

    /** A game's page → Convert to CHD: just this disc (originals deleted only if Settings → Tools says so). */
    fun convertGameToChd(g: Game) {
        if (chdJob != null) { message = "Already converting a disc: wait for it to finish (Settings \u2192 Tools)"; return }
        romItem(g)?.let { z ->
            message = "Compressing ${g.name} to ${z.format}\u2026"
            runChd(listOf(z), chdDelete, auto = true) { saved, ok -> if (ok > 0) chdSavedFor[g.id] = saved }
            return
        }
        val discs = chdDiscsOf(g)
        if (discs.isEmpty()) { message = "Nothing to convert: no .cue or .iso for ${g.name}"; return }
        message = "Converting ${g.name} to ${convertFormat(g) ?: "CHD"}\u2026"
        runChd(discs.mapNotNull { d -> discItem(d, false, g.platform) }, chdDelete, auto = true) { saved, ok -> if (ok > 0) chdSavedFor[g.id] = saved }
    }

    // ---- auto-convert: new .cue/.bin or .iso discs dropped into a ROM folder become a CHD by themselves ----

    /** Settings → Tools (CHD) → "Convert new discs automatically" (originals are deleted once checked). */
    var chdAuto by mutableStateOf(store.str("chd_auto") == "1")
    /** Discs auto-convert leaves alone: the ones already there when it was switched on, and ones it has tried. */
    private var chdKnown: Set<String> = store.str("chd_known").split('\n').filter { it.isNotEmpty() }.toSet()
    private fun rememberChdKnown(paths: Collection<String>) {
        chdKnown = chdKnown + paths
        store.putStr("chd_known", chdKnown.joinToString("\n"))
    }
    /** The system folders auto-convert watches (you opt in to each one). Nothing is watched until you pick some. */
    var chdAutoDirs by mutableStateOf(store.str("chd_auto_dirs").split('\n').filter { it.isNotEmpty() }.toSet())
        private set
    /** The system folders Find discs looks in (used by the Tools screen). Empty = all your ROM folders. */
    var chdScanDirs by mutableStateOf(store.str("chd_scan_dirs").split('\n').filter { it.isNotEmpty() }.toSet())
        private set

    /** System folders that can hold discs, for the opt-in list: (system name, folder path). null = not looked yet. */
    var chdFolders by mutableStateOf<List<Pair<String, String>>?>(null)
        private set

    /** Finds your system folders for disc-based systems: named sub-folders of your ROM folders, and folders set per system. */
    fun loadChdFolders() {
        viewModelScope.launch {
            chdFolders = withContext(Dispatchers.IO) {
                // only systems whose emulators read CHD or RVZ (not Xbox, PS3 …)
                val disc = setOf("chd", "rvz")
                val out = LinkedHashMap<String, String>() // path -> system name
                for (root in romFolders()) (java.io.File(root).listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { d ->
                    val p = com.eclipse.launcher.data.Platforms.matchFolder(d.name)
                    if (p != null && p.exts.any { it in disc }) out.putIfAbsent(d.absolutePath, p.name)
                }
                for ((id, paths) in sysFolders) {
                    val p = com.eclipse.launcher.data.Platforms.byId(id) ?: continue
                    if (p.exts.none { it in disc }) continue
                    paths.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.putIfAbsent(java.io.File(it).absolutePath, p.name) }
                }
                out.entries.map { it.value to it.key }.sortedBy { it.first.lowercase() }
            }
        }
    }

    /** Opt a system folder in or out. Opting in leaves the discs already there alone: only new ones get converted. */
    fun chooseChdAutoDir(path: String, on: Boolean) {
        chdAutoDirs = if (on) chdAutoDirs + path else chdAutoDirs - path
        store.putStr("chd_auto_dirs", chdAutoDirs.joinToString("\n"))
        if (on) viewModelScope.launch {
            val there = withContext(Dispatchers.IO) { findChdCandidates().filter { !it.done && inDir(it.src, path) } }
            rememberChdKnown(there.map { it.src.absolutePath })
        }
    }

    private fun inDir(f: java.io.File, dir: String): Boolean = f.absolutePath.startsWith(dir.trimEnd('/') + "/")

    /** A new disc's total size and newest change time at the last look: it's converted once these stop changing. */
    private val chdWatch = HashMap<String, Pair<Long, Long>>()

    fun chooseChdAuto(on: Boolean) {
        chdAuto = on
        store.putStr("chd_auto", if (on) "1" else "")
        chdWatch.clear()
        if (on) viewModelScope.launch {
            // only discs added from now on: what's already in your folders is left as it is
            val now = withContext(Dispatchers.IO) { findChdCandidates() }
            chdKnown = emptySet()
            rememberChdKnown(now.filter { !it.done }.map { it.src.absolutePath })
        }
    }

    /** Every .cue and .iso in your ROM folders (4 levels down), with the files each one is made of. */
    private fun findChdCandidates(): List<ChdItem> {
        val roots = (romFolders() + sysFolders.values.flatMap { it.split('\n') }).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val seen = HashSet<String>()
        val list = ArrayList<ChdItem>()
        for (root in roots) java.io.File(root).walkTopDown().maxDepth(4)
            .onEnter { !it.name.startsWith(".") && it.name != "Android" }
            .filter { it.isFile && isConvertible(it) }
            .forEach { f ->
                if (!seen.add(f.absolutePath)) return@forEach
                discItem(f, alreadyConverted(f))?.let { list.add(it) }
            }
        return list
    }

    /**
     * One look (every minute while auto-convert is on): new discs whose files have all arrived and stopped changing
     * for a minute get converted, then their originals deleted. Waits while a game is running, so it never slows one down.
     */
    private suspend fun autoChdTick() {
        if (!chdAuto || chdAutoDirs.isEmpty() || chdJob != null || session != null) return
        val now = System.currentTimeMillis()
        val ready = withContext(Dispatchers.IO) {
            val dirs = chdAutoDirs
            findChdCandidates().filter { !it.done && it.src.absolutePath !in chdKnown && dirs.any { d -> inDir(it.src, d) } }.filter { item ->
                val key = item.src.absolutePath
                // every .bin the .cue names is there, nothing half-written beside it
                val complete = item.parts.all { it.isFile } && !java.io.File(item.src.parentFile, item.src.nameWithoutExtension + ".chd.part").exists() &&
                    !java.io.File(item.src.parentFile, item.src.nameWithoutExtension + ".rvz.part").exists()
                val size = item.parts.sumOf { it.length() }
                val newest = item.parts.maxOf { it.lastModified() }
                val before = chdWatch.put(key, size to newest)
                complete && before == (size to newest) && now - newest > 60_000
            }
        }
        if (ready.isNotEmpty()) {
            ready.forEach { chdWatch.remove(it.src.absolutePath) }
            runChd(ready.sortedBy { it.src.name.lowercase() }, del = true, auto = true)
        }
    }

    init {
        viewModelScope.launch {
            var n = 0
            while (true) {
                delay(60_000)
                runCatching { autoChdTick() }
                // "Scrape new games automatically": every few minutes (and on coming back), a quiet look for new games
                if (++n % 3 == 0 && resumed && scrapeNew() && session == null) runCatching { rescan(full = false, quiet = true) }
            }
        }
    }

    /** "Game (Disc 1).chd", "Game (Disc 2).chd" … → "Game.m3u", for disc swapping (only where there's no playlist yet). */
    /** Playlists already in [dirs] (RomM's, or yours) now list each disc's .chd instead of its .cue / .iso. */
    private fun pointPlaylistsAtChd(dirs: List<java.io.File>) {
        for (dir in dirs) dir.listFiles()?.filter { it.isFile && it.extension.equals("m3u", true) }?.forEach { m ->
            runCatching {
                val lines = m.readLines()
                val fixed = lines.map { line ->
                    val t = line.trim()
                    val disc = java.io.File(t).let { if (it.isAbsolute) it else java.io.File(dir, t) }
                    val chd = java.io.File(disc.parentFile, disc.nameWithoutExtension + ".chd")
                    val rvz = java.io.File(disc.parentFile, disc.nameWithoutExtension + ".rvz")
                    when {
                        !isConvertible(disc) -> line
                        chd.isFile -> t.substringBeforeLast('.') + ".chd"
                        rvz.isFile -> t.substringBeforeLast('.') + ".rvz"
                        else -> line
                    }
                }
                if (fixed != lines) m.writeText(fixed.joinToString("\n") + "\n")
            }
        }
    }

        private fun writeDiscPlaylists(dirs: List<java.io.File>) {
        for (dir in dirs) for (set in com.eclipse.launcher.data.M3u.inFolder(dir)) {
            if (set.playlist == null) runCatching { com.eclipse.launcher.data.M3u.write(set) }
        }
    }

    // ---- Playlists for multi-disc games (the Tools screen) ----

    /** null = not scanned yet */
    var m3uSets by mutableStateOf<List<com.eclipse.launcher.data.M3u.DiscSet>?>(null)
    var m3uPicked by mutableStateOf<Set<String>>(emptySet())
    var m3uScanning by mutableStateOf(false)

    /** Finds multi-disc games in your ROM folders. Ones without an up-to-date playlist start ticked. */
    fun scanM3u() {
        if (m3uScanning) return
        m3uScanning = true
        viewModelScope.launch {
            val sets = withContext(Dispatchers.IO) {
                val roots = (romFolders() + sysFolders.values.flatMap { it.split('\n') }).map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { java.io.File(it) }
                com.eclipse.launcher.data.M3u.find(roots)
            }
            m3uSets = sets
            m3uPicked = sets.filter { !it.upToDate }.map { it.key }.toSet()
            m3uScanning = false
        }
    }

    fun toggleM3uPick(key: String) { m3uPicked = if (key in m3uPicked) m3uPicked - key else m3uPicked + key }

    /**
     * How big a game is: a RomM game's download, else its file(s) here (every disc; a .cue / .gdi / .m3u with the files
     * beside it that share its name; a folder with everything in it). 0 when unknown.
     */
    fun gameSizeBytes(g: Game): Long = runCatching {
        if (isRommEntry(g)) return@runCatching romFor(g)?.sizeBytes ?: 0L
        val f = File(g.path)
        when {
            f.isDirectory -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            !f.isFile -> 0L
            f.extension.lowercase() in setOf("cue", "gdi", "m3u", "ccd") -> {
                val stem = f.nameWithoutExtension.lowercase()
                val base = discsOf(g).map { it.nameWithoutExtension.lowercase() }.toSet() + stem
                f.parentFile?.listFiles()?.filter { x -> x.isFile && base.any { x.name.lowercase().startsWith(it) } }?.sumOf { it.length() } ?: f.length()
            }
            else -> f.length()
        }
    }.getOrDefault(0L)

    fun fmtBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }

    /**
     * Settings → Scrape → "Scrape new games automatically" (on unless you turn it off): every scan, automatic or by
     * hand, gets artwork for the games it just found, each once. Games already there are left alone (Scrape missing /
     * Re-scrape all do those), so scanning never spends lookups on the same unmatched games again and again.
     */
    fun scrapeNew(): Boolean = flag("scrape_new", true)

    /** After a scan: artwork for [fresh] (games it just found), when that's turned on. */
    private fun scrapeFresh(fresh: Collection<String>) {
        if (fresh.isEmpty() || !scrapeNew() || artProviders().isEmpty()) return
        val ids = fresh.toHashSet()
        scrapeGames(games.filter { it.path in ids }, false)
    }

    fun rescan(full: Boolean = true, quiet: Boolean = false) {
        if (romFolders().isEmpty() && sysFolders.isEmpty()) { if (!quiet) message = "Choose a ROM folder first"; return }
        if (!hasAllFiles()) { if (!quiet) message = "Allow all-files access first"; return }
        if (scanning) return
        scanning = true
        viewModelScope.launch(Dispatchers.IO) {
            val found = scanAll()
            withContext(Dispatchers.Main) {
                val apps = games.filter { it.platform == "android" && it.id.startsWith("a_") }
                if (full) {
                    val had = games.map { it.path }.toHashSet()
                    games = withoutRemoved(found + apps)
                    message = "Found ${found.size} games"
                    // games this scan found for the first time get their artwork too
                    val fresh = games.map { it.path }.filter { it !in had }
                    lbCatchUp { scrapeFresh(fresh) }
                } else {
                    val have = games.map { it.path }.toSet()
                    val fresh = found.filter { it.path !in have }
                    games = withoutRemoved(games + fresh)
                    if (!quiet || fresh.isNotEmpty()) message = if (fresh.isEmpty()) "No new games found" else "Added ${fresh.size} new ${if (fresh.size == 1) "game" else "games"}"
                    // new games: looked up in the kept LaunchBox file first, then they get their artwork, when that's turned on
                    if (fresh.isNotEmpty()) lbCatchUp { scrapeFresh(fresh.map { it.path }) }
                }
                scanning = false
                persistGames()
                pico8Art()
            }
        }
    }

    /**
     * PICO-8 carts are their own box art: a ".p8.png" is a picture of the cartridge, and a ".p8" keeps its label inside
     * (drawn once into the art folder). Only for carts with no box art yet, so a picture you picked stays.
     */
    private fun pico8Art() {
        val carts = games.filter { it.platform == "pico8" && art["${it.id}|${ArtKind.BOX.key}"] == null }
        if (carts.isEmpty()) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                carts.mapNotNull { g ->
                    val f = File(g.path)
                    val pic = when {
                        f.name.endsWith(".png", true) -> f.takeIf { it.isFile }
                        f.extension.equals("p8", true) -> com.eclipse.launcher.data.Pico8.label(f, File(artDir, "p8_${g.id}.png"))
                        else -> null
                    }
                    pic?.let { "${g.id}|${ArtKind.BOX.key}" to Uri.fromFile(it).toString() }
                }
            }
            if (found.isEmpty()) return@launch
            art = art + found
            persistArt()
        }
    }

    /**
     * Scans again for one system only, then (with [scrapeAfter]: the system menu's Scan) fetches artwork for its games
     * that have none; without it, only for the games it just found (when "Scrape new games automatically" is on).
     */
    fun rescanSystem(platformId: String, scrapeAfter: Boolean = true) {
        if (!hasAllFiles()) { message = "Allow all-files access first"; return }
        if (scanningSys != null || scanning) return
        scanningSys = platformId
        viewModelScope.launch(Dispatchers.IO) {
            val found = scanAll().filter { it.platform == platformId }
            withContext(Dispatchers.Main) {
                val had = games.map { it.path }.toHashSet()
                games = withoutRemoved(games.filter { it.platform != platformId } + found)
                persistGames()
                scanningSys = null
                val name = Platforms.byId(platformId)?.name ?: platformId
                message = "$name: ${found.size} ${if (found.size == 1) "game" else "games"}"
                lbCatchUp {
                    if (scrapeAfter && artProviders().isNotEmpty()) scrapeGames(games.filter { it.platform == platformId }, false)
                    else scrapeFresh(found.map { it.path }.filter { it !in had })
                }
            }
        }
    }

    fun hasAllFiles(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return
        val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- screenshots (of the main screen, from the bottom screen's Now Playing page) ----
    fun takeScreenshot() {
        val started = ScreenshotService.capture(ctx, session?.game?.name) { r ->
            shotStatus = when (r) {
                is ScreenshotService.Companion.Result.Saved -> "Screenshot saved to Pictures/Eclipse/" + (session?.game?.name ?: "Eclipse")
                ScreenshotService.Companion.Result.SystemShot -> "Screenshot saved"
                ScreenshotService.Companion.Result.Failed -> "Screenshot failed"
            }
        }
        if (!started) {
            shotStatus = "Turn on \"Eclipse screenshots\" in Accessibility to use this"
            requestScreenshotAccess()
        }
    }

    /** Opens Eclipse's page in Accessibility settings (or the Accessibility list on devices without the shortcut). */
    fun requestScreenshotAccess() {
        val cn = ComponentName(ctx, ScreenshotService::class.java).flattenToString()
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(detail) } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens Eclipse's own switch for notification access (Android 11+), else the list of apps. */
    fun requestNotifAccess() {
        val cn = ComponentName(ctx, com.eclipse.launcher.media.MediaListener::class.java).flattenToString()
        try {
            if (Build.VERSION.SDK_INT < 30) error("old Android")
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---- permissions overview (Settings -> Permissions) ----
    fun notifAccessOn(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun helperOn(): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return ScreenshotService.ready.value || enabled.contains(ctx.packageName + "/")
    }

    fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == ctx.packageName
    }

    fun batteryUnrestricted(): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    private fun open(intent: Intent, fallback: Intent? = null) {
        try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
            fallback?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }

    fun openHomeSettings() = open(Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    fun requestBatteryUnrestricted() = open(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    /** App info page: where Android 13+ hides "Allow restricted settings" (menu in the top corner). */
    fun openAppInfo() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))

    /** "Set up everything": opens the first permission that is still off. Returns false when all are on. */
    fun openNextPermission(): Boolean {
        when {
            !hasAllFiles() -> requestAllFiles()
            !notifAccessOn() -> requestNotifAccess()
            !helperOn() -> requestScreenshotAccess()
            !batteryUnrestricted() -> requestBatteryUnrestricted()
            !isDefaultHome() -> openHomeSettings()
            else -> { message = "Everything is set up"; return false }
        }
        return true
    }

    /** Home carousel: only games that have actually been launched, most recent first. */
    /** Recently Played's games (worked out again only when something it uses changes). */
    fun homeGames(): List<Game> = homeList
    private val homeList by androidx.compose.runtime.derivedStateOf { computeHomeGames() }
    private fun computeHomeGames(): List<Game> {
        val byId = games.associateBy { it.id }
        val played = recent.filter { it !in hiddenGames }.mapNotNull { byId[it] }
        if (deckShow == "played") return played.take(deckCount)
        // recently added: newest first (not hidden games, not ones you took off the row)
        val added = games.asSequence()
            .filter { it.id !in hiddenGames && it.id !in deckDismissed && (addedAt[it.id] ?: 0L) > 0L }
            .sortedByDescending { addedAt[it.id] ?: 0L }
            .take(deckCount).toList()
        if (deckShow == "added") return added
        // both: one timeline, each game at its latest moment (played or added)
        fun at(g: Game) = maxOf(lastPlayed[g.id]?.toLongOrNull() ?: 0L, addedAt[g.id] ?: 0L)
        val seen = HashSet<String>()
        val merged = (played + added).filter { seen.add(it.id) }
        // played games without a time keep their place at the front, in the order you played them
        val (timed, untimed) = merged.partition { at(it) > 0L }
        return (untimed.filter { it.id in recent } + timed.sortedByDescending { at(it) }).distinctBy { it.id }.take(deckCount)
    }

    /** Recently Played's right panel → Show: "both" (default), "played" or "added". */
    var deckShow by mutableStateOf(store.str("deck_show", "both"))
        private set
    fun chooseDeckShow(v: String) { deckShow = v; store.putStr("deck_show", v) }

    /** When each game first turned up (ms). The first time: its file's date; after that: when a scan found it. */
    var addedAt by mutableStateOf(store.map("added_at").mapValues { it.value.toLongOrNull() ?: 0L })
        private set
    /** Recently added games you took off the row (they're only back if you play them). */
    var deckDismissed by mutableStateOf(store.list("deck_dismissed").toSet())
        private set
    private var addedJob: Job? = null
    private fun noteAdded() {
        val known = addedAt
        val firstTime = known.isEmpty()
        val missing = games.filter { it.id !in known }
        if (missing.isEmpty() || addedJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val romTimes = missing.associate { g -> g.id to (romFor(g)?.addedMs ?: 0L) }
        addedJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                missing.associate { g ->
                    val rom = romTimes[g.id] ?: 0L
                    val file = if (g.path.startsWith("romm:")) 0L else runCatching { File(g.path).lastModified() }.getOrDefault(0L)
                    // first run: the file's own date (so "recently added" means something straight away); later: now
                    g.id to when { rom > 0L -> rom; firstTime && file > 0L -> minOf(file, now); else -> now }
                }
            }
            addedAt = addedAt + found
            store.putMap("added_at", addedAt.mapValues { it.value.toString() })
            noteAdded() // games that arrived meanwhile
        }
    }

    /** The click as the highlight moves: volume 0 (off) to 100 (Settings → Look & Feel → Sounds). */
    var uiVolume by mutableStateOf(
        (store.str("ui_volume").toIntOrNull()
            ?: when (store.str("ui_sound", "low")) { "" -> 0; "high" -> 90; else -> 40 }
        ).coerceIn(0, 100).also { com.eclipse.launcher.media.UiSound.volume = it }
    )
    fun chooseUiVolume(v: Int) { uiVolume = v.coerceIn(0, 100); com.eclipse.launcher.media.UiSound.volume = uiVolume; store.putStr("ui_volume", uiVolume.toString()) }
    /** Which sound: a built-in id ("tick", "pop"…) or "file:<name>" for one you picked. */
    var uiSoundPick by mutableStateOf(store.str("ui_sound_pick", "tick").also { com.eclipse.launcher.media.UiSound.choose(it) })
    fun chooseUiSoundPick(v: String) { uiSoundPick = v; store.putStr("ui_sound_pick", v); com.eclipse.launcher.media.UiSound.choose(v) }
    var importedSounds by mutableStateOf(listSounds())
        private set
    private fun listSounds(): List<String> =
        (File(ctx.filesDir, "sounds").listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name }.sortedBy { it.lowercase() }
    /** A sound file you picked: copied into Eclipse and used straight away. */
    fun importSound(uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val display = try {
                        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    } catch (e: Exception) { null } ?: "sound"
                    val ext = display.substringAfterLast('.', "wav").lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "wav" }
                    val base = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "sound" }.take(40)
                    val f = File(com.eclipse.launcher.media.UiSound.soundsDir(ctx), "$base.$ext")
                    val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext null
                    input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    if (f.length() > 2_000_000L) { f.delete(); null } else f.name
                } catch (e: Exception) { null }
            }
            if (saved != null) {
                importedSounds = listSounds()
                chooseUiSoundPick("file:$saved")
                com.eclipse.launcher.media.UiSound.tick()
                message = "Sound saved: ${saved.substringBeforeLast('.')}"
            } else message = "Couldn't use that file (a short sound under 2 MB)"
        }
    }
    fun deleteSound(file: String) {
        File(com.eclipse.launcher.media.UiSound.soundsDir(ctx), file).delete()
        importedSounds = listSounds()
        if (uiSoundPick == "file:$file") chooseUiSoundPick("tick")
    }

    /** How many games On Deck shows (its right dock): 5 … 30. */
    var deckCount by mutableStateOf(store.str("deck_count").toIntOrNull() ?: 20)
        private set
    /** Recently Played's art style ("" = each game's system's style). */
    var deckStyle by mutableStateOf(store.str("deck_style"))
        private set
    fun chooseDeckStyle(s: String) { deckStyle = s; store.putStr("deck_style", s) }
    fun chooseDeckCount(n: Int) { deckCount = n; store.putStr("deck_count", n.toString()) }

    // ---- hidden games (the game page's Hide; Settings → Library → Hidden games) ----
    var hiddenGames by mutableStateOf(store.list("hidden_games").toSet())
        private set
    var hiddenScreen by mutableStateOf(false)
    fun setHidden(g: Game, hide: Boolean) {
        hiddenGames = if (hide) hiddenGames + g.id else hiddenGames - g.id
        store.putList("hidden_games", hiddenGames.toList())
        if (hide) {
            if (menuGame?.id == g.id) menuGame = null
            if (focused?.id == g.id) focused = null
            message = "Hid ${g.name}. Settings \u2192 Library \u2192 Hidden games brings it back."
        } else message = "${g.name} is back in your library"
    }

    /** Takes a game off On Deck (it stays in your library; playing it again puts it back). */
    fun removeFromOnDeck(g: Game) {
        recent = recent.filter { it != g.id }
        store.putList("recent", recent)
        deckDismissed = deckDismissed + g.id
        store.putList("deck_dismissed", deckDismissed.toList())
        if (focused?.id == g.id) focused = homeGames().firstOrNull()
        message = "Removed ${g.name} from Recently Played"
    }

    /** The highlighted game on Home (always one of the carousel games). */
    fun homeFocus(): Game? {
        val list = homeGames()
        return list.firstOrNull { it.id == focused?.id } ?: list.firstOrNull()
    }

    // ---- emulators / play ----
    // Installed packages as a set, rebuilt only when the app list changes (no per-check system calls).
    private var pkgCacheSrc: List<AppEntry>? = null
    private var pkgCache: Set<String> = emptySet()

    fun installed(pkg: String): Boolean {
        if (pkgCacheSrc !== apps) {
            pkgCache = apps.mapTo(HashSet()) { it.pkg }
            pkgCacheSrc = apps
        }
        return pkg in pkgCache
    }

    /** Every way this system can be launched with what's installed, in catalog order. */
    fun installedFor(platformId: String): List<EmuChoice> {
        val p = Platforms.byId(platformId) ?: return emptyList()
        val ra = Platforms.retroArch.firstOrNull { installed(it) }
        val known = raCores?.cores
        val checked = !known.isNullOrEmpty()
        val list = p.emus.mapNotNull { s ->
            when {
                s.core != null -> ra?.let {
                    if (checked) EmuChoice(s, it, detected = s.core in known!!, corePath = known!![s.core]) else EmuChoice(s, it)
                }
                s.pkg != null && installed(s.pkg) -> EmuChoice(s, s.pkg)
                else -> null
            }
        }
        // cores found in your RetroArch first, then standalone emulators, then cores we couldn't confirm
        return list.sortedBy { c -> when (c.detected) { true -> 0; null -> 1; false -> 2 } }
    }

    // ---- RetroArch cores you have ----
    var raCores by mutableStateOf<RaCores.Found?>(null)
    private var raScanAt = 0L

    fun scanRaCores(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - raScanAt < 60_000) return
        raScanAt = System.currentTimeMillis()
        val pkgs = Platforms.retroArch.filter { installed(it) }
        if (pkgs.isEmpty()) { raCores = null; return }
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { RaCores.scan(pkgs) }
            raCores = f
            if (force) message = if (f.cores.isEmpty()) "Couldn't see any RetroArch cores" else "Found ${f.cores.size} RetroArch cores"
        }
    }

    /** Your pick for this system if it's installed, else the first installed option. */
    fun emuFor(g: Game): EmuChoice? {
        val ext = g.path.substringAfterLast('.', "").lowercase()
        // the emulator you picked for this system always wins: whatever the file type, and whether or not Eclipse
        // found its RetroArch core
        emuPick[g.platform]?.let { k -> installedFor(g.platform).firstOrNull { it.spec.key == k }?.let { return it } }
        val all = installedFor(g.platform).filter { it.spec.fits(ext) }
        // an app made for exactly this kind of file (GameNative for .steam, GameHub Lite for .localgameid) beats a
        // general one (Winlator would get a .steam file it can't run)
        val special = all.filter { ext in it.spec.onlyExts }
        val list = special.ifEmpty { all }
        // Auto never picks a core we didn't find in RetroArch (RetroArch hangs for ages on a missing core)
        return list.firstOrNull { it.detected != false }
    }


    fun setEmu(platformId: String, key: String?) {
        emuPick = if (key == null) emuPick - platformId else emuPick + (platformId to key)
        store.putMap("emus", emuPick)
    }

    // ---- installed-app scan: emulators + Android games ----
    fun detectedEmulators(): List<String> =
        apps.filter { Platforms.isEmulator(it.pkg) }.map { Platforms.emuName(it.pkg) ?: it.label }.distinct().sorted()

    /** Apps that call themselves emulators but aren't in our catalog. */
    fun possibleEmulators(): List<String> =
        apps.filter { !Platforms.isEmulator(it.pkg) && it.label.contains("emulator", ignoreCase = true) }.map { it.label }.sorted()

    fun androidGameCount(): Int = games.count { it.platform == "android" }

    /**
     * Android games = apps flagged as games by the system (or that you moved to the Games category),
     * minus emulators. They are added to the library under the Android platform and launch by package.
     */
    fun scanInstalledApps(manual: Boolean = false) {
        if (apps.isEmpty() || !loaded) return
        val found = apps.filter { a ->
            !Platforms.isEmulator(a.pkg) &&
                !a.label.contains("emulator", ignoreCase = true) &&
                (cats[a.pkg]?.let { it == "Games" } ?: a.isGame)
        }.map { Game("a_" + it.pkg, nameOf(it), "android", it.pkg) }
        val merged = games.filter { it.platform != "android" } + found
        if (merged != games) {
            games = merged
            persistGames()
        }
        if (manual) message = "Found ${detectedEmulators().size} emulators and ${found.size} Android games"
    }

    fun play(g: Game) {
        if (System.currentTimeMillis() - lastNavMs < 450) return // the press that opened this screen
        // a RomM game that isn't on this device: what Settings → RomM says (or ask)
        if (isRommEntry(g)) {
            val r = romOf(g) ?: run { message = "Sync RomM again, this game's details are missing"; return }
            val how = rommLaunch.takeIf { it == "play" || it == "download" } ?: "ask"
            if (how == "ask") { rommAsk = g; return }
            rommOpen(g, how)
            return
        }
        val rid = rommIdFor(g)
        if (rid != null && g.platform != "android" && flag("romm_saves", false) && rommConn() != null) {
            // bring down newer saves from RomM first (never holds up the launch for more than a few seconds)
            viewModelScope.launch {
                withTimeoutOrNull(8_000) { rommPullSaves(g, rid) }
                launchNow(g)
            }
            return
        }
        launchNow(g)
    }

    // ---- multi-disc games: which disc starts; and which screen a game starts on ----
    /** game id → the disc file to start instead of the playlist's first one */
    var discPick by mutableStateOf(store.map("disc_pick"))
        private set
    /** game id → "top" / "bottom" */
    var gameScreen by mutableStateOf(store.map("game_screen"))
        private set

    private val DISC_RE = Regex("\\s*[(\\[]\\s*(disc|disk|cd)\\s*(\\d+)(\\s*of\\s*\\d+)?\\s*[)\\]]", RegexOption.IGNORE_CASE)

    /** A multi-disc game's discs: what its .m3u lists, or the "(Disc 1)", "(Disc 2)" … files beside it. */
    fun discsOf(g: Game): List<java.io.File> {
        if (g.platform == "android" || g.path.startsWith("romm:")) return emptyList()
        val f = java.io.File(g.path)
        val dir = f.parentFile ?: return emptyList()
        if (f.extension.equals("m3u", true)) return runCatching { f.readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> java.io.File(line).let { if (it.isAbsolute) it else java.io.File(dir, line) } }
        if (!DISC_RE.containsMatchIn(f.name)) return emptyList()
        val base = f.nameWithoutExtension.replace(DISC_RE, "").trim()
        return (dir.listFiles() ?: return emptyList()).filter { o ->
            o.isFile && o.extension.equals(f.extension, true) && DISC_RE.containsMatchIn(o.name) && o.nameWithoutExtension.replace(DISC_RE, "").trim() == base
        }.sortedBy { DISC_RE.find(it.name)?.groupValues?.get(2)?.toIntOrNull() ?: 0 }
    }

    /** "Disc 2" on a game set to start another disc than the first; null otherwise. */
    fun discLabel(g: Game): String? {
        val p = discPick[g.id] ?: return null
        val n = DISC_RE.find(java.io.File(p).name)?.groupValues?.get(2) ?: return "Disc \u2022"
        return if (n == "1") null else "Disc $n"
    }

    fun chooseDisc(g: Game, path: String?) {
        discPick = if (path == null) discPick - g.id else discPick + (g.id to path)
        store.putMap("disc_pick", discPick)
    }

    fun chooseGameScreen(g: Game, where: String) {
        gameScreen = if (where == "top") gameScreen - g.id else gameScreen + (g.id to where)
        store.putMap("game_screen", gameScreen)
    }

    /** The bottom screen's display, when there is one. */
    /** The other screen's display (the bottom one on the AYN Thor), if there is one: see [com.eclipse.launcher.media.Screens]. */
    fun bottomDisplayId(): Int? = com.eclipse.launcher.media.Screens.companion(ctx, com.eclipse.launcher.media.Screens.mainId, secondName)?.displayId

    private fun launchNow(g0: Game) {
        // the disc you chose for this game (if it's still there)
        val g = discPick[g0.id]?.takeIf { java.io.File(it).isFile }?.let { g0.copy(path = it) } ?: g0
        if (g.platform == "android") {
            val err = AppsLoader.launch(ctx, g.path, activityOf(g.path))
            if (err == null) startSession(g) else message = "Couldn't open ${g.name}: $err"
            return
        }
        val choice = emuFor(g)
        if (choice == null) {
            val sys = Platforms.byId(g.platform)?.name ?: g.platform
            message = if (installedFor(g.platform).isNotEmpty())
                "None of RetroArch's cores for $sys were found. Download one in RetroArch (Load Core → Download a Core), or pick one in Settings → Emulators."
            else "No emulator installed for $sys"
            return
        }
        // no "Core not found" question: it starts with the chosen emulator straight away
        launchWith(g, choice)
    }

    /** Starts [g] with [choice], no questions asked (used after "Launch anyway"). */
    fun launchWith(g: Game, choice: EmuChoice) {
        val display = if (gameScreen[g.id] == "bottom") bottomDisplayId() else null
        val err = Launch.launch(ctx, g, choice, display)
        if (err == null) startSession(g) else message = "Couldn't start ${choice.label}: $err"
    }

    // ---- sessions: saved to storage so play time survives Android closing Eclipse mid-game ----
    // ---- themes: platform icons, dock icons, wallpapers, colours ----
    var themes by mutableStateOf<List<Themes.Theme>>(emptyList())
    var theme by mutableStateOf<Themes.Theme?>(null)

    private fun applyTheme(t: Themes.Theme?) {
        theme = t
        applyLook()
    }

    // ---- Appearance: your own choices, which win over the theme's ----
    /** Size of the posters in slide view and cover flow: 0.6 (small) … 1.5 (large), 1 = normal. */
    var posterScale by mutableFloatStateOf(store.str("poster_scale", "1").toFloatOrNull()?.coerceIn(0.6f, 1.5f) ?: 1f)
    /**
     * Icon size per screen and view (right-hand drawer → Icon size), 0.6 … 1.5. Keys are "<screen>/<view>" with screen
     * "deck", "platforms" or "sys:<platform id>" and view "grid", "carousel" or "coverflow"; "default/<view>" is what
     * screens without their own size use (until one is made, that's the Poster size in Settings → Appearance).
     */
    var iconSizes by mutableStateOf(
        store.str("icon_sizes").split(";").mapNotNull { e ->
            val k = e.substringBefore("=", "")
            e.substringAfter("=", "").toFloatOrNull()?.takeIf { k.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
        private set
    private fun saveIconSizes(m: Map<String, Float>) {
        iconSizes = m
        store.putStr("icon_sizes", m.entries.joinToString(";") { "${it.key}=${it.value}" })
    }
    fun iconSize(screen: String, view: String): Float =
        iconSizes["$screen/$view"] ?: iconSizes["default/$view"] ?: (if (view == "grid") 1f else posterScale)
    /** Has this screen its own setting for this view (grid: "cols")? */
    fun iconSizeCustom(screen: String, view: String): Boolean = "$screen/$view" in iconSizes
    fun setIconSize(screen: String, view: String, v: Float) =
        saveIconSizes(iconSizes + ("$screen/$view" to Math.round(v.coerceIn(0.6f, 1.5f) * 20) / 20f))
    /** Grid: icons per row (2 … 10) on this screen, else the default, else [fallback]. */
    fun gridCols(screen: String, fallback: Int): Int =
        (iconSizes["$screen/${gridKey(screen)}"] ?: iconSizes["default/${gridKey(screen)}"])?.toInt()?.coerceIn(2, 10) ?: fallback
    fun setGridCols(screen: String, n: Int) = saveIconSizes(iconSizes + ("$screen/${gridKey(screen)}" to n.coerceIn(2, 10).toFloat()))
    /** The platform cards and the games grids keep separate per-row settings (and defaults). */
    fun gridKey(screen: String) = if (screen == "platforms") "pcols" else "cols"
    /** This screen's setting for this view becomes the default for every screen without its own; this one follows it. */
    fun makeIconSizeDefault(screen: String, view: String, value: Float) {
        saveIconSizes(iconSizes - "$screen/$view" + ("default/$view" to value))
    }
    /** This screen goes back to the default for this view. */
    fun resetIconSize(screen: String, view: String) = saveIconSizes(iconSizes - "$screen/$view")

    var homeView by mutableStateOf(store.str("home_view", "carousel"))   // On Deck: carousel / coverflow
    // platforms: grid / carousel / coverflow (the XMB view is gone: anyone who had it gets the grid)
    var platView by mutableStateOf(store.str("plat_view", "grid").let { if (it == "xmb") "grid" else it })
    var wallTop by mutableStateOf(store.str("wall_top"))                 // your own wallpaper (file uri) or ""
    var wallBottom by mutableStateOf(store.str("wall_bottom"))
    var wallMode by mutableStateOf(store.str("wall_mode"))               // "" = as the theme says, "fallback", "always"
    var colorAccent by mutableStateOf(store.str("c_accent"))             // "#RRGGBB" or "" = theme / Eclipse's
    var colorFocus by mutableStateOf(store.str("c_focus"))
    /** Text colour ("#RRGGBB", or "" = Eclipse's own white) */
    var colorText by mutableStateOf(store.str("c_text"))
    /** The colour wheel popup: "focus" (highlight) or "text", while it's open */
    var colorPicker by mutableStateOf<String?>(null)
    var fontChoice by mutableStateOf(store.str("font"))                  // "" = theme / default, serif, mono, condensed, casual, custom
    var pendingWallpaper: String? = null                                  // image picker target: "top" / "bottom"

    fun chooseHomeView(v: String) { homeView = v; store.putStr("home_view", v) }
    fun choosePlatView(v: String) { platView = v; store.putStr("plat_view", v) }
    fun chooseWallMode(v: String) { wallMode = v; store.putStr("wall_mode", v) }
    /** Glass tint and the softer label colour ("#RRGGBB", or "" = the theme's / Eclipse's own). */
    var colorGlass by mutableStateOf(store.str("c_glass"))
    var colorLabels by mutableStateOf(store.str("c_labels"))
    /** Background: the colour of the shading over wallpapers and game art (slide view's fades too). */
    var colorBackdrop by mutableStateOf(store.str("c_backdrop"))
    /** Glass strength and background dim, 0..100, or -1 = the theme's / Eclipse's own. */
    var glassStrength by mutableStateOf(store.str("glass_pct").toIntOrNull() ?: -1)
    var bgDim by mutableStateOf(store.str("bg_dim").toIntOrNull() ?: -1)
    fun chooseGlassStrength(pct: Int) { glassStrength = pct.coerceIn(-1, 100); store.putStr("glass_pct", glassStrength.toString()); applyLook() }
    fun chooseBgDim(pct: Int) { bgDim = pct.coerceIn(-1, 100); store.putStr("bg_dim", bgDim.toString()); applyLook() }

    /** Settings → Appearance → Colours: one colour by its name ("glass", "accent", "focus", "text", "labels"). */
    fun colourHex(kind: String): String = when (kind) {
        "glass" -> colorGlass; "backdrop" -> colorBackdrop; "accent" -> colorAccent; "text" -> colorText; "labels" -> colorLabels; else -> colorFocus
    }
    fun chooseColour(kind: String, hex: String) {
        when (kind) {
            "glass" -> { colorGlass = hex; store.putStr("c_glass", hex) }
            "backdrop" -> { colorBackdrop = hex; store.putStr("c_backdrop", hex) }
            "accent" -> { colorAccent = hex; store.putStr("c_accent", hex) }
            "text" -> { colorText = hex; store.putStr("c_text", hex) }
            "labels" -> { colorLabels = hex; store.putStr("c_labels", hex) }
            else -> { colorFocus = hex; store.putStr("c_focus", hex) }
        }
        applyLook()
    }
    /** What a colour is when you haven't picked one: the theme's, else Eclipse's own for this mode. */
    fun colourAuto(kind: String): Color {
        val t = theme; val light = ThemeColors.light.value
        return when (kind) {
            "glass" -> t?.glass?.let { Color(it) } ?: if (light) Color(0xFFF2EEE6) else Color(0xFF1E1C19)
            "backdrop" -> themeBackdrop(t) ?: Color(0xFF0B0B0D)
            "accent" -> t?.accent?.let { Color(it) } ?: t?.focus?.let { Color(it) } ?: if (light) Color(0xFFB8871F) else ThemeColors.DefaultAccent
            "text" -> t?.text?.let { Color(it) } ?: if (light) Color(0xFF1B1A17) else Color(0xFFF3F3F6)
            "labels" -> t?.labels?.let { Color(it) } ?: if (light) Color(0xFF6F6A60) else Color(0xFFA9ABB4)
            else -> t?.focus?.let { Color(it) } ?: if (light) Color(0xFFC9961E) else ThemeColors.DefaultFocus
        }
    }
    /** Every colour, strength and dim back to the theme's. */
    fun resetColours() {
        for (k in listOf("c_glass", "c_backdrop", "c_accent", "c_text", "c_labels", "c_focus")) store.putStr(k, "")
        colorGlass = ""; colorBackdrop = ""; colorAccent = ""; colorText = ""; colorLabels = ""; colorFocus = ""
        glassStrength = -1; bgDim = -1; store.putStr("glass_pct", "-1"); store.putStr("bg_dim", "-1")
        applyLook()
    }
    /** Text size, as a percentage of normal (Settings → Appearance → Font). */
    var textSize by mutableStateOf(store.str("text_size").toIntOrNull() ?: 100)
    fun chooseTextSize(pct: Int) { textSize = pct.coerceIn(50, 130); store.putStr("text_size", textSize.toString()); applyLook() }
    fun chooseFont(f: String) { fontChoice = f; store.putStr("font", f); applyLook() }

    private fun hexColor(s: String): Color? = s.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

    /** Colours and font: yours if you picked one, else the theme's, else Eclipse's own. */
    /** Light or dark panels: "" = dark, "light", or "auto" (follows Android's dark theme setting). */
    /** Low power mode: no frosted glass or blur anywhere (Settings → Appearance → Look). */
    fun chooseLowPower(on: Boolean) { setFlag("low_power", on); applyLook() }
    /**
     * Low power (Settings → Theme → Glass effect off): no blur, plainer panels. Until you choose, it starts on for
     * devices with little memory (under ~7 GB: the Retroid Pocket Duo Lite, Anbernic's RG DS and the like), where
     * blur and video backdrops cost the most.
     */
    fun lowPowerOn(): Boolean = flag("low_power", lowPowerDefault)

    fun chooseScreenRoles(v: String) { screenRoles = v; store.putStr("screen_roles", v); com.eclipse.launcher.media.SecondScreenHost.resync?.invoke() }
    fun chooseSecondDisplay(name: String) { secondName = name; store.putStr("second_display", name); com.eclipse.launcher.media.SecondScreenHost.resync?.invoke() }
    fun bottomFocusOn(): Boolean = flag("bottom_focus", com.eclipse.launcher.media.Screens.bottomFocusDefault())
    fun setBottomFocus(on: Boolean) { setFlag("bottom_focus", on); com.eclipse.launcher.media.SecondScreenHost.resync?.invoke() }
    private fun isLight(): Boolean = false // dark only (the light / dark choice was removed)

    private fun applyLook() {
        val t = theme
        // one colour for both: selected buttons and the open tab use the highlight colour
        val light = isLight()
        ThemeColors.light.value = light
        ThemeColors.lowPower.value = lowPowerOn()
        val custom = hexColor(colorFocus) ?: t?.focus?.let { Color(it) }
        // Eclipse's gold goes a shade deeper on light panels so it still stands out
        val focus = custom ?: if (light) Color(0xFFC9961E) else ThemeColors.DefaultFocus
        ThemeColors.focus.value = focus
        // accent (selected items, gold writing): yours, else the theme's, else the highlight's
        ThemeColors.accent.value = hexColor(colorAccent) ?: t?.accent?.let { Color(it) } ?: custom ?: if (light) Color(0xFFB8871F) else focus
        ThemeColors.font.value = fontFamilyFor(fontChoice, t)
        ThemeColors.textScale.value = textSize / 100f
        val unset = androidx.compose.ui.graphics.Color.Unspecified
        ThemeColors.text.value = hexColor(colorText) ?: t?.text?.let { Color(it) } ?: unset
        ThemeColors.labels.value = hexColor(colorLabels) ?: t?.labels?.let { Color(it) } ?: unset
        ThemeColors.glass.value = hexColor(colorGlass) ?: t?.glass?.let { Color(it) } ?: unset
        ThemeColors.backdrop.value = hexColor(colorBackdrop) ?: themeBackdrop(t) ?: unset
        ThemeColors.glassAlpha.value = (if (glassStrength >= 0) glassStrength else t?.glassStrength ?: 70) / 100f
        ThemeColors.dim.value = (if (bgDim >= 0) bgDim else t?.dim ?: -1).let { if (it < 0) -1f else it / 100f }
    }

    /**
     * A theme's shading colour: its own "background", else its glass colour taken darker, else Eclipse's near-black
     * tinted a little toward its accent, so every theme gives the screens its own cast. Null with no theme.
     */
    private fun themeBackdrop(t: Themes.Theme?): Color? {
        t ?: return null
        t.background?.let { return Color(it) }
        val near = Color(0xFF0B0B0D)
        t.glass?.let { return androidx.compose.ui.graphics.lerp(near, Color(it), 0.45f).copy(alpha = 1f) }
        val tint = t.accent ?: t.focus ?: return null
        return androidx.compose.ui.graphics.lerp(near, Color(tint), 0.1f).copy(alpha = 1f)
    }

    val customFontFile: File get() = File(ctx.filesDir, "fonts/custom.ttf")

    /** The font a choice in Appearance stands for (for its "Aa" preview tile). */
    fun previewFont(choice: String): FontFamily = fontFamilyFor(choice, theme)

    private fun fontFamilyFor(choice: String, t: Themes.Theme?): FontFamily = try {
        when (choice) {
            "sans" -> FontFamily.SansSerif
            "system" -> FontFamily.Default
            "serif" -> FontFamily.Serif
            "mono" -> FontFamily.Monospace
            "casual" -> FontFamily.Cursive
            "condensed" -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
            "custom" -> if (customFontFile.isFile) FontFamily(Font(customFontFile)) else FontFamily.Default
            // an imported font, saved in Eclipse's storage
            else -> if (choice.startsWith("file:")) File(ctx.filesDir, "fonts/" + choice.removePrefix("file:")).takeIf { it.isFile }?.let { FontFamily(Font(it)) } ?: FontFamily.Default
                // "" = the theme's font, else Android's default
                else t?.font?.let { FontFamily(Font(it)) } ?: FontFamily.Default
        }
    } catch (e: Exception) {
        FontFamily.Default
    }

    /** A font file (.ttf / .otf) you picked: copied into Eclipse and used straight away. */
    /** Fonts you've imported, kept in Eclipse's own storage (file names in filesDir/fonts). Each is a choice in Appearance. */
    private val fontDir: File get() = File(ctx.filesDir, "fonts").apply { mkdirs() }
    var importedFonts by mutableStateOf(listFonts())
        private set
    private fun listFonts(): List<String> =
        (File(ctx.filesDir, "fonts").listFiles() ?: emptyArray())
            .filter { it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) }
            .map { it.name }.sortedBy { it.lowercase() }
    /** How an imported font is shown in the list: its file name without the extension. */
    fun fontLabel(file: String): String = if (file == "custom.ttf") "Imported" else file.substringBeforeLast('.')

    /** A font file (.ttf / .otf) you picked: copied into Eclipse (it stays even if the original goes) and used straight away. */
    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    // its own name, so fonts you import later sit beside it instead of replacing it
                    val display = try {
                        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    } catch (e: Exception) { null } ?: "Font"
                    val ext = display.substringAfterLast('.', "ttf").lowercase().let { if (it == "otf") "otf" else "ttf" }
                    val base = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { "Font" }.take(40)
                    var f = File(fontDir, "$base.$ext")
                    var n = 2
                    while (f.exists()) { f = File(fontDir, "$base $n.$ext"); n++ }
                    val tmp = File(fontDir, "import.part")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return@withContext null
                    // make sure Android can actually read it as a font before keeping it
                    try { android.graphics.Typeface.createFromFile(tmp) } catch (e: Exception) { tmp.delete(); return@withContext null }
                    if (tmp.renameTo(f)) f.name else null
                } catch (e: Exception) {
                    null
                }
            }
            if (saved != null) {
                importedFonts = listFonts()
                chooseFont("file:$saved")
                message = "Font saved: ${fontLabel(saved)}"
            } else {
                message = "That file isn't a font Android can read (use .ttf or .otf)"
            }
        }
    }

    /** Deletes an imported font; if it was in use, Eclipse goes back to the default font. */
    fun deleteFont(file: String) {
        File(fontDir, file).delete()
        importedFonts = listFonts()
        if (fontChoice == "file:$file" || (file == "custom.ttf" && fontChoice == "custom")) chooseFont("")
        message = "Font removed"
    }

    /** Your own wallpaper for the top or bottom screen (copied into Eclipse, so the original can go). */
    fun importWallpaper(which: String, uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val dir = File(ctx.filesDir, "wallpapers").apply { mkdirs() }
                    val f = File(dir, "${which}_${System.currentTimeMillis()}.img")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: return@withContext null
                    // the previous one of the same screen isn't needed any more
                    dir.listFiles()?.filter { it.name.startsWith(which + "_") && it != f }?.forEach { it.delete() }
                    Uri.fromFile(f).toString()
                } catch (e: Exception) {
                    null
                }
            }
            if (saved == null) { message = "Couldn't use that image"; return@launch }
            if (which == "bottom") { wallBottom = saved; store.putStr("wall_bottom", saved) } else { wallTop = saved; store.putStr("wall_top", saved) }
        }
    }

    fun clearWallpaper(which: String) {
        val old = if (which == "bottom") wallBottom else wallTop
        if (which == "bottom") { wallBottom = ""; store.putStr("wall_bottom", "") } else { wallTop = ""; store.putStr("wall_top", "") }
        if (old.startsWith("file:")) viewModelScope.launch(Dispatchers.IO) { runCatching { Uri.parse(old).path?.let { File(it).delete() } } }
    }

    /** The wallpaper to show on the top screen: yours, else the theme's. */
    /** Eclipse's own wallpaper (assets/wallpapers): used when neither you nor the theme picked one. */
    val defaultWallTop = "file:///android_asset/wallpapers/eclipse-top.jpg"
    private val defaultWallBottom = "file:///android_asset/wallpapers/eclipse-bottom.jpg"
    /** Android's own wallpaper (live wallpapers too) shows through behind Eclipse instead of a picture. */
    var sysWall by mutableStateOf(store.str("sys_wall") == "1")
        private set

    fun chooseSysWall(on: Boolean) {
        sysWall = on
        store.putStr("sys_wall", if (on) "1" else "")
        if (on) resetGlassSource()
    }

    /** Opens Android's live wallpaper chooser (or its wallpaper picker when there isn't one). */
    fun openWallpaperChooser() {
        val tries = listOf(
            Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
            Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose a wallpaper")
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        message = "No wallpaper picker on this device"
    }

    private fun resetGlassSource() { com.eclipse.launcher.ui.GlassSource.model = null }

    fun wallpaperTop(): String? = if (sysWall) null else wallTop.ifEmpty { null } ?: themeImage(theme?.wallpaper) ?: defaultWallTop

    /** The bottom screen's: yours, else the theme's bottom one, else whichever top one is in use. */
    fun wallpaperBottom(): String? = wallBottom.ifEmpty { null } ?: themeImage(theme?.bottom)
        ?: (if (wallTop.isNotEmpty() || theme?.wallpaper != null) wallpaperTop() else defaultWallBottom)

    /** Show the wallpaper even when the game has a backdrop of its own? */
    fun wallpaperAlways(): Boolean = when (wallMode) {
        "always" -> true
        "fallback" -> false
        else -> theme?.alwaysWallpaper == true
    }

    /** Platform card art that should fill the whole card (your own picture, or the theme's), rather than a box-art preview. */
    /**
     * How system cards look: "" = follow Android's light / dark setting, or "light" (cream card, black logo), "dark"
     * (dark card, white logo), "color" (dark card, colour logo). Settings → Look & Feel → Cards.
     */
    var cardMode by mutableStateOf(store.str("card_mode").let { if (it == "color") it else "" })

    /** How the systems look (the platforms' drawer → Style): "card" (icon over logo, the default) or "icon" (the picture alone). */
    var platStyle by mutableStateOf(store.str("plat_style", "card"))
        private set
    fun choosePlatStyle(v: String) { platStyle = v; store.putStr("plat_style", v) }

    /** How round the corners of game art and of system cards are (Settings → Look & Feel → Corners), 0..100. */
    var cornerArt by mutableStateOf(store.str("corner_art").toIntOrNull()?.coerceIn(0, 100) ?: 50)
        private set
    var cornerCard by mutableStateOf(store.str("corner_card").toIntOrNull()?.coerceIn(0, 100) ?: 60)
        private set
    fun chooseCornerArt(v: Int) { cornerArt = v.coerceIn(0, 100); store.putStr("corner_art", cornerArt.toString()) }
    /** How round system icons are (the icon on a card, and Style → Icon), 0..100. */
    var cornerIcon by mutableStateOf(store.str("corner_icon").toIntOrNull()?.coerceIn(0, 100) ?: 50)
        private set
    fun chooseCornerIcon(v: Int) { cornerIcon = v.coerceIn(0, 100); store.putStr("corner_icon", cornerIcon.toString()) }
    /** Style → Icon: 0..30 dp. */
    val iconCorner: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(cornerIcon * 0.30f)
    fun chooseCornerCard(v: Int) { cornerCard = v.coerceIn(0, 100); store.putStr("corner_card", cornerCard.toString()) }
    /** Game art: 0..24 dp. System cards: 0..30 dp. */
    val artCorner: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(cornerArt * 0.24f)
    val cardCorner: androidx.compose.ui.unit.Dp get() = androidx.compose.ui.unit.Dp(cornerCard * 0.30f)

    /** RomM is set up and its games are known: only then do RomM choices (Source → RomM, uploads) show. */
    fun rommOn(): Boolean = rommConn() != null && rommCatalog.isNotEmpty()
    fun chooseCardMode(v: String) { cardMode = v; store.putStr("card_mode", v) }

    /**
     * The picture on top of a system's card: whatever you picked for it (a card image, a Platform artwork icon, an
     * icon pack's or theme's picture), else the icon that comes with Eclipse, else a box-art preview. The card's logo
     * is separate and is never changed by any of these.
     */
    fun platformCardIcon(id: String): String? {
        // no stock icons any more: your own picture, else the icon pack's (ColorGrade by default)
        return platArt[id] ?: platIcon[id] ?: platformImage(id)
    }

    // ---- icon packs and backdrop packs (mix and match, whatever the theme) ----
    var iconPacks by mutableStateOf<List<Packs.Pack>>(emptyList())
    var backdropPacks by mutableStateOf<List<Packs.Pack>>(emptyList())
    var logoPacks by mutableStateOf<List<Packs.Pack>>(emptyList())
    /** "" = Stock (the pack that comes with Eclipse), "pack:<id>" = an installed pack, "theme:<id>" = an installed theme's */
    var iconChoice by mutableStateOf(store.str("icon_pack"))
    var backdropChoice by mutableStateOf(store.str("backdrop_pack"))
    /** Logos: "" = the current theme's (if it has any) else Eclipse's own, "pack:<id>" = a logo pack, "theme:<id>" = a theme's */
    var logoChoice by mutableStateOf(store.str("logo_pack"))
    var pendingPackKind: String? = null // zip picker target

    fun chooseIcons(v: String) { iconChoice = v; store.putStr("icon_pack", v) }
    fun chooseBackdrops(v: String) { backdropChoice = v; store.putStr("backdrop_pack", v) }
    fun chooseLogos(v: String) { logoChoice = v; store.putStr("logo_pack", v) }

    /** The logos in use: the chosen logo pack or theme's, else the current theme's, else none (Eclipse's own are used). */
    fun logoSource(): Map<String, File>? = when {
        logoChoice.startsWith("pack:") -> logoPacks.firstOrNull { it.id == logoChoice.removePrefix("pack:") }?.images
        logoChoice.startsWith("theme:") -> themes.firstOrNull { it.id == logoChoice.removePrefix("theme:") }?.logos
        else -> theme?.logos?.takeIf { it.isNotEmpty() }
    }

    /**
     * A system's logo wherever Eclipse shows one: one you picked for that system (Platform artwork), else the chosen
     * logo pack's or theme's, else Eclipse's own for the card [look] ("dark" = white, "light" = black, "color").
     */
    fun systemLogo(id: String, look: String = "dark"): String? =
        platLogo[id] ?: logoSource()?.get(id)?.let { Uri.fromFile(it).toString() } ?: com.eclipse.launcher.data.SystemArt.logo(id, look)

    /** The platform pictures in use: the chosen pack or theme, else the Stock pack that comes with Eclipse. */
    fun iconSource(): Map<String, File>? = when {
        iconChoice.startsWith("pack:") -> iconPacks.firstOrNull { it.id == iconChoice.removePrefix("pack:") }?.images
        iconChoice.startsWith("theme:") -> themes.firstOrNull { it.id == iconChoice.removePrefix("theme:") }?.platforms
        else -> null
    } ?: defaultIconPack()?.images

    /** With no pack chosen: ColorGrade if it's installed, else the first installed pack. */
    fun defaultIconPack(): Packs.Pack? {
        val usable = iconPacks.filter { it.id != com.eclipse.launcher.data.StockPack.ID }
        fun fold(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return usable.firstOrNull { it.id == com.eclipse.launcher.data.StockPack.COLORGRADE }
            ?: usable.firstOrNull { "colorgrade" in fold(it.name) || "colorgrade" in fold(it.id) } ?: usable.firstOrNull()
    }

    fun backdropSource(): Map<String, File>? = when {
        backdropChoice.startsWith("pack:") -> backdropPacks.firstOrNull { it.id == backdropChoice.removePrefix("pack:") }?.images
        backdropChoice.startsWith("theme:") -> themes.firstOrNull { it.id == backdropChoice.removePrefix("theme:") }?.backgrounds
        else -> null
    } ?: theme?.backgrounds

    /** The background for one system (from the chosen backdrop pack or theme), if there is one. */
    fun systemBackground(id: String?): String? = id?.let { backdropSource()?.get(it) }?.let { themeImage(it) }

    /** Themes and packs are in the Eclipse folder on your storage (not just in Eclipse's own storage). */
    var homeInUse by mutableStateOf(false)
        private set

    /** Reads every theme and pack again from their folders: after you've changed, added or removed pictures. */
    fun reloadImages(quiet: Boolean = false) {
        loadThemes()
        loadPacks()
        if (!quiet) message = "Images reloaded from " + if (com.eclipse.launcher.data.EclipseHome.usable()) "the Eclipse folder" else "Eclipse's storage"
    }

    private fun loadPacks() {
        viewModelScope.launch {
            val (i, b, l) = withContext(Dispatchers.IO) {
                com.eclipse.launcher.data.EclipseHome.setUp(ctx)
                // ColorGrade comes with Eclipse (also removes the old Stock pack's folder)
                com.eclipse.launcher.data.StockPack.install(ctx)
                Triple(Packs.list(ctx, Packs.ICONS), Packs.list(ctx, Packs.BACKDROPS), Packs.list(ctx, Packs.LOGOS))
            }
            homeInUse = com.eclipse.launcher.data.EclipseHome.usable()
            iconPacks = i
            backdropPacks = b
            logoPacks = l
        }
    }

    /** A pack zip with several picture folders, waiting on the pack import screen (which folder, icons or backdrops). */
    var packImport by mutableStateOf<Packs.Pending?>(null)

    fun importPack(kind: String, uri: Uri) {
        message = "Importing…"
        viewModelScope.launch {
            val step = withContext(Dispatchers.IO) { Packs.importStart(ctx, uri, kind) }
            packStep(step)
        }
    }

    /** The pack import screen's Import: [folder] becomes a pack of [kind]. */
    fun finishPackImport(folder: com.eclipse.launcher.data.ThemeConvert.FolderChoice, kind: String) {
        val p = packImport ?: return
        packImport = null
        message = "Importing…"
        viewModelScope.launch {
            val step = withContext(Dispatchers.IO) { Packs.importFinish(ctx, p, folder, kind) }
            packStep(step)
        }
    }

    fun cancelPackImport() {
        val p = packImport ?: return
        packImport = null
        viewModelScope.launch(Dispatchers.IO) { Packs.importCancel(p) }
    }

    private suspend fun packStep(step: Packs.Step) {
        when (step) {
            is Packs.Step.Failed -> message = step.message
            is Packs.Step.Choose -> { packImport = step.pending; message = null }
            is Packs.Step.Done -> {
                val p = step.pack
                val all = withContext(Dispatchers.IO) { Packs.list(ctx, p.kind) }
                when (p.kind) {
                    Packs.ICONS -> { iconPacks = all; chooseIcons("pack:${p.id}") }
                    Packs.LOGOS -> { logoPacks = all; chooseLogos("pack:${p.id}") }
                    else -> { backdropPacks = all; chooseBackdrops("pack:${p.id}") }
                }
                message = "\"${p.name}\": ${p.images.size} systems, now in use"
            }
        }
    }

    /** Reads the installed themes and packs again (the pickers do this as they open), without changing the look. */
    fun refreshThemeLists() {
        viewModelScope.launch {
            val (t, i, b) = withContext(Dispatchers.IO) {
                com.eclipse.launcher.data.EclipseHome.setUp(ctx)
                Triple(Themes.list(ctx), Packs.list(ctx, Packs.ICONS), Packs.list(ctx, Packs.BACKDROPS)) to Packs.list(ctx, Packs.LOGOS)
            }.let { (tib, l) -> logoPacks = l; tib }
            homeInUse = com.eclipse.launcher.data.EclipseHome.usable()
            themes = t; iconPacks = i; backdropPacks = b
        }
    }

    fun deletePack(p: Packs.Pack) {
        if (p.kind == Packs.ICONS && iconChoice == "pack:${p.id}") chooseIcons("")
        if (p.kind == Packs.BACKDROPS && backdropChoice == "pack:${p.id}") chooseBackdrops("")
        if (p.kind == Packs.LOGOS && logoChoice == "pack:${p.id}") chooseLogos("")
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { Packs.delete(p); Packs.list(ctx, p.kind) }
            if (p.kind == Packs.ICONS) {
                iconPacks = all
            } else if (p.kind == Packs.LOGOS) {
                logoPacks = all
            } else {
                backdropPacks = all
            }
        }
    }

    private fun loadThemes() {
        viewModelScope.launch {
            val (all, active) = withContext(Dispatchers.IO) {
                com.eclipse.launcher.data.EclipseHome.setUp(ctx)
                Themes.installBuiltIns(ctx)
                val l = Themes.list(ctx)
                l to l.firstOrNull { it.id == store.str("theme") }
            }
            themes = all
            applyTheme(active)
        }
    }

    fun useTheme(t: Themes.Theme?) {
        store.putStr("theme", t?.id ?: "")
        // a theme made for light (or dark) panels switches the mode (you can change it back in Appearance)
        // a new theme starts from its own colours: anything you picked in Appearance → Colours is cleared
        resetColours()
        applyTheme(t)
    }

    /** A theme from another frontend waiting on the import screen (which folder is icons, logos, backdrops). */
    var themeImport by mutableStateOf<Themes.Pending?>(null)
    var themeImporting by mutableStateOf(false)

    fun importTheme(uri: Uri) {
        message = "Importing theme…"
        themeImporting = true
        viewModelScope.launch {
            val step = withContext(Dispatchers.IO) { Themes.importStart(ctx, uri) }
            themeImporting = false
            themeStep(step)
        }
    }

    /** The import screen's Import button: convert with the roles you picked. */
    fun finishThemeImport(roles: Map<String, String>) {
        val p = themeImport ?: return
        themeImport = null
        themeImporting = true
        message = "Importing theme…"
        viewModelScope.launch {
            val step = withContext(Dispatchers.IO) { Themes.importFinish(ctx, p, roles) }
            themeImporting = false
            themeStep(step)
        }
    }

    fun cancelThemeImport() {
        val p = themeImport ?: return
        themeImport = null
        viewModelScope.launch(Dispatchers.IO) { Themes.importCancel(p) }
        message = "Theme import cancelled"
    }

    private suspend fun themeStep(step: Themes.Step) {
        when (step) {
            is Themes.Step.Failed -> message = step.message
            is Themes.Step.Choose -> { themeImport = step.pending; message = null }
            is Themes.Step.Done -> {
                val t = step.theme
                themes = withContext(Dispatchers.IO) { Themes.list(ctx) }
                useTheme(themes.firstOrNull { it.id == t.id } ?: t)
                message = (Themes.lastNote?.let { "$it. " } ?: "") + "Theme \"${t.name}\" applied"
            }
        }
    }

    fun deleteTheme(t: Themes.Theme) {
        if (theme?.id == t.id) useTheme(null)
        viewModelScope.launch {
            themes = withContext(Dispatchers.IO) { Themes.delete(t); Themes.list(ctx) }
        }
    }

    fun saveThemeTemplate() {
        if (!hasAllFiles()) { message = "Allow all-files access first (Settings → General → Permissions)"; return }
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) { Themes.writeTemplate() }
            message = if (f != null) "Saved to Download/${f.name}" else "Couldn't save the template"
        }
    }

    /** Image from the active theme as something Coil can load. */
    fun themeImage(f: File?): String? = f?.let { Uri.fromFile(it).toString() }

    // ---- RomM: your self-hosted library, shown in its own store (not mixed into your games) ----
    var rommRoms by mutableStateOf<Map<String, String>>(emptyMap())   // RomM id -> RomM.Rom.encode()
    var rommLink by mutableStateOf<Map<String, String>>(emptyMap())   // local game id -> RomM id
    var rommStatus by mutableStateOf<String?>(null)
    var rommBusy by mutableStateOf(false)
    /** The store's games, decoded once (rebuilt after each sync). */
    var rommCatalog by mutableStateOf<List<RomM.Rom>>(emptyList())

    var storeDelete by mutableStateOf<Int?>(null)     // RomM id waiting for "delete download?" (the game page)
    var gameDelete by mutableStateOf<Game?>(null)     // a game waiting for "uninstall?" (the game page, the app menu)

    private fun persistRomm() {
        val a = rommRoms
        val b = rommLink
        saveLater(ROMM_FILE) { store.saveMapFile(ROMM_FILE, a) }
        saveLater(ROMM_LINK_FILE) { store.saveMapFile(ROMM_LINK_FILE, b) }
    }

    private fun rebuildCatalog() {
        val all = rommRoms.values.mapNotNull { RomM.Rom.decode(it) }.filter { it.bp.isNotEmpty() }.sortedBy { it.name.lowercase() }
        // RomM lists "Game (Disc 1)", "Game (Disc 2)" … as separate games: show them as one (like local multi-disc
        // games), under disc 1, named without the disc tag
        fun discNo(r: RomM.Rom) = com.eclipse.launcher.data.M3u.discNumber(r.fsName.substringBeforeLast('.')) ?: com.eclipse.launcher.data.M3u.discNumber(r.name)
        val discs = HashMap<Int, List<RomM.Rom>>()
        all.filter { discNo(it) != null }
            .groupBy { it.bp + "|" + com.eclipse.launcher.data.M3u.titleOf(it.name).lowercase() }.values
            .filter { it.size > 1 }
            .forEach { set -> val sorted = set.sortedBy { discNo(it) ?: 0 }; sorted.forEach { discs[it.id] = sorted } }
        rommDiscs = discs
        val shown = all.mapNotNull { r ->
            val set = discs[r.id] ?: return@mapNotNull r
            if (set.first().id == r.id) r.copy(name = com.eclipse.launcher.data.M3u.titleOf(r.name)) else null
        }
        rommCatalog = shown
        rommById = all.associateBy { it.id } + shown.associateBy { it.id }
    }

    /** Any disc's RomM id → every disc of that game, disc 1 first (multi-disc games on RomM). */
    var rommDiscs: Map<Int, List<RomM.Rom>> = emptyMap()
        private set
    /** A RomM multi-disc game's discs (empty for other games). */
    fun discsOfRomm(g: Game): List<RomM.Rom> = romOf(g)?.let { rommDiscs[it.id] } ?: emptyList()
    /** RomM game (its disc 1 id) → the disc picked on its page (that disc's RomM id); streaming uses it */
    var rommDiscPick by mutableStateOf<Map<Int, Int>>(emptyMap())
    /** The "download which discs?" popup: the game, and whether to play once it's down. */
    var rommDiscAsk by mutableStateOf<Pair<Game, Boolean>?>(null)

    /** Downloads [which] discs of a RomM game ([play]: start the first one when it's down). */
    fun rommDownloadDiscs(g: Game, which: List<RomM.Rom>, play: Boolean) {
        if (which.isEmpty()) return
        message = if (which.size > 1) "Installing ${g.name} (${which.size} discs) from RomM\u2026" else "Installing ${g.name} from RomM\u2026"
        which.forEachIndexed { i, d -> rommDownload(d, playWhenDone = play && i == 0) }
    }

    /**
     * Every disc of a RomM multi-disc game is on this device: write its .m3u playlist and show the game once, as the
     * playlist (art, description and the RomM link move over from disc 1).
     */
    /**
     * A disc of a multi-disc RomM game is ready ([file]: just downloaded, or just converted to CHD). The game is in the
     * library as ONE entry, its playlist, from the first disc on, and the playlist is rewritten every time another
     * disc is ready: every disc of the game in that folder, in its best form (CHD before .cue...). Any separate entry
     * for one of its discs (a raw .cue picked up by a scan, say) is removed. Returns the game.
     */
    private fun updateDiscPlaylist(rom: RomM.Rom, file: File): Game? {
        val set = rommDiscs[rom.id] ?: return null
        val dir = file.parentFile ?: return null
        val title = com.eclipse.launcher.data.M3u.titleOf(file.nameWithoutExtension)
        if (title.isEmpty()) return null
        fun sameGame(x: File) = com.eclipse.launcher.data.M3u.discNumber(x.nameWithoutExtension) != null &&
            com.eclipse.launcher.data.M3u.titleOf(x.nameWithoutExtension).replace(TRACK_TAG, "").trim().equals(title, ignoreCase = true)
        // each disc's best file: the order of DISC_EXTS (chd first)
        val discs = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && it.extension.lowercase() in DISC_EXTS && sameGame(it) }
            .groupBy { com.eclipse.launcher.data.M3u.discNumber(it.nameWithoutExtension)!! }
            .toSortedMap()
            .map { (_, files) -> files.minByOrNull { DISC_EXTS.indexOf(it.extension.lowercase()) }!! }
        if (discs.isEmpty()) return null
        val m3u = File(dir, "$title.m3u")
        if (runCatching { m3u.writeText(discs.joinToString("\n") { it.name } + "\n") }.isFailure) return null
        val head = set.first()
        val pg = Game("g" + Integer.toHexString(m3u.absolutePath.hashCode()), com.eclipse.launcher.data.M3u.titleOf(head.name), head.bp, m3u.absolutePath)
        val setIds = set.map { it.id.toString() }.toSet()
        // its discs as separate games (linked to this RomM set, or any file of it in this folder, gone or not)
        val discIds = (rommLink.filterValues { it in setIds }.keys +
            games.filter { g -> !g.path.endsWith(".m3u", true) && File(g.path).let { f -> f.parentFile?.absolutePath == dir.absolutePath && sameGame(f) } }.map { it.id }
            ).toSet() - pg.id
        val newArt = HashMap(art)
        head.cover?.let { newArt.putIfAbsent("${pg.id}|${ArtKind.BOX.key}", it) }
        head.screenshot?.let { newArt.putIfAbsent("${pg.id}|${ArtKind.BACKDROP.key}", it) }
        art = newArt
        head.summary?.let { if (desc[pg.id] == null) desc = desc + (pg.id to it) }
        games = games.filter { it.id !in discIds }.let { l -> if (l.none { it.id == pg.id }) l + pg else l }
        rommLink = rommLink.filterKeys { it !in discIds } + (pg.id to head.id.toString())
        if (recent.any { it in discIds }) { recent = recent.filter { it !in discIds }; store.putList("recent", recent) }
        persistGames(); persistArt(); persistDesc(); persistRomm()
        return pg
    }

    /**
     * Sends a game's name, description and box art to RomM (PUT /api/roms/{id}). The box art goes up as a picture
     * when it's a file on this device, else as a web address RomM fetches. Needs an editor or admin account on RomM.
     */
    fun sendToRomm(g: Game) {
        val rid = rommIdFor(g)?.toIntOrNull() ?: romOf(g)?.id ?: run { message = "${g.name} isn't on RomM"; return }
        val c = rommConn() ?: run { message = "Connect to RomM first (Settings \u2192 Connections \u2192 RomM)"; return }
        val box = artOf(g, ArtKind.BOX)
        val file = box?.takeIf { !it.startsWith("http") }?.let { File(it.removePrefix("file://")) }?.takeIf { it.isFile }
        val url = box?.takeIf { it.startsWith("http") }
        val shot = artOf(g, ArtKind.BACKDROP) ?: artOf(g, ArtKind.HERO)
        message = "Sending ${g.name}'s art and info to RomM\u2026"
        viewModelScope.launch {
            val ok = RomM.updateRom(c, rid, g.name, descOf(g) ?: "", file, url)
            // the backdrop goes up as a screenshot (RomM versions without screenshot uploads just skip it)
            val shotOk = ok && shot != null && RomM.pictureBytes(shot)?.let { b ->
                RomM.uploadScreenshot(c, rid, b, "eclipse-backdrop." + (shot.substringAfterLast('.', "jpg").take(4).lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "jpg"))
            } == true
            showNotice(when {
                !ok -> Notice("Couldn't send to RomM", g.name, "It needs an editor or admin account", box, "error")
                shotOk -> Notice("Sent to RomM", g.name, "Name, description, box art and backdrop", box, "up")
                else -> Notice("Sent to RomM", g.name, "Name, description and box art", box, "up")
            })
            if (ok) rommSync(quiet = true)
        }
    }

    /** RomM games by id, for the "show RomM games" option on a system's games screen. */
    var rommById: Map<Int, RomM.Rom> = emptyMap()
        private set

    /** A RomM game shown among a system's games (not downloaded yet): id "rm:<RomM id>", path "romm:<RomM id>". */
    fun isRommEntry(g: Game) = g.path.startsWith("romm:")
    private fun romOf(g: Game): RomM.Rom? = if (g.id.startsWith("rm:")) g.id.removePrefix("rm:").toIntOrNull()?.let { rommById[it] } else null

    /** The RomM entry for a game: a RomM game in a list, or a local game matched / downloaded from RomM. */
    fun romFor(g: Game): RomM.Rom? = romOf(g) ?: rommIdFor(g)?.toIntOrNull()?.let { rommById[it] }

    /** Your RomM games for [platform] that aren't on this device, as entries for the games screen. */
    fun rommEntriesFor(platform: String?): List<Game> =
        rommCatalog.filter { (platform == null || it.bp == platform) && installedRom(it.id) == null }
            .map { Game("rm:${it.id}", it.name, it.bp, "romm:${it.id}") }

    /** Description for a game, including RomM's for RomM entries. */
    fun descOf(g: Game): String? = desc[g.id] ?: romOf(g)?.summary

    fun rommConn(): RomM.Conn? {
        val url = key("romm_url")
        if (url.isBlank() || key("romm_user").isBlank()) return null
        return RomM.Conn(url, key("romm_user"), key("romm_pass"))
    }

    fun rommIdFor(g: Game): String? = rommLink[g.id]

    // ---- uploading games to RomM, over its library's network share (Settings → RomM) ----
    /** Eclipse system → the folder name RomM uses for it (learnt on each sync). */
    var rommFolders by mutableStateOf(store.map("romm_folders"))
        private set
    /** Your own choice of RomM folder per system (Library → a system's options), over the one learnt on sync. */
    var rommFolderPick by mutableStateOf(store.map("romm_folder_pick"))
        private set
    /** Where [platformId]'s games are uploaded: your choice, else RomM's folder for it, else Eclipse's own name. */
    fun rommUploadFolder(platformId: String): String = rommFolderPick[platformId] ?: rommFolders[platformId] ?: platformId
    fun setRommFolder(platformId: String, folder: String?) {
        rommFolderPick = if (folder == null) rommFolderPick - platformId else rommFolderPick + (platformId to folder)
        store.putMap("romm_folder_pick", rommFolderPick)
    }
    /** One finished upload to RomM (up) or download from it, for Settings → RomM → Transfers. */
    data class Transfer(
        val up: Boolean, val name: String, val platform: String, val time: Long, val ok: Boolean, val bytes: Long, val error: String? = null
    )
    /** Finished transfers, newest first (the last 100 are kept). */
    var transfers by mutableStateOf(loadTransfers())
        private set
    private fun loadTransfers(): List<Transfer> = runCatching {
        val a = org.json.JSONArray(store.str("romm_history", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Transfer(o.optBoolean("up"), o.optString("name"), o.optString("platform"), o.optLong("time"), o.optBoolean("ok"), o.optLong("bytes"), o.optString("error").ifBlank { null })
        }
    }.getOrDefault(emptyList())
    private fun addTransfer(t: Transfer) {
        transfers = (listOf(t) + transfers).take(100)
        saveTransfers()
    }
    fun clearTransfers() { transfers = emptyList(); saveTransfers() }
    private fun saveTransfers() {
        val a = org.json.JSONArray()
        transfers.forEach { t ->
            a.put(org.json.JSONObject().put("up", t.up).put("name", t.name).put("platform", t.platform).put("time", t.time)
                .put("ok", t.ok).put("bytes", t.bytes).put("error", t.error ?: ""))
        }
        store.putStr("romm_history", a.toString())
    }
    /** Uploads in progress: game id → 0..1. */
    val uploads = androidx.compose.runtime.mutableStateMapOf<String, Float>()

    /** The computer RomM runs on (from its address), which usually also shares the library folder. */
    fun rommHost(): String = key("romm_url").trim().substringAfter("://").substringBefore('/').substringBefore(':')

    /** The computer sharing RomM's library: the one you typed, else RomM's own. */
    fun smbHost(): String = key("smb_host").trim().ifEmpty { rommHost() }

    private fun smbTarget(): com.eclipse.launcher.data.SmbUpload.Target? {
        val host = smbHost()
        if (host.isEmpty() || key("smb_share").isBlank()) return null
        return com.eclipse.launcher.data.SmbUpload.Target(host, key("smb_share").trim(), key("smb_path").trim(), key("smb_user").trim(), key("smb_pass"))
    }
    fun smbReady(): Boolean = smbTarget() != null
    var smbCheck by mutableStateOf<String?>(null)
        private set

    fun checkSmb() {
        val t = smbTarget() ?: run { smbCheck = null; return }
        smbCheck = "Checking\u2026"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { com.eclipse.launcher.data.SmbUpload.check(t) }
            smbCheck = r ?: "Connected"
        }
    }

    // ---- choosing the library folder by browsing the computer's shares (Settings → RomM → library share)
    data class SmbBrowse(
        val share: String = "", val path: String = "",
        val listing: com.eclipse.launcher.data.SmbUpload.Listing? = null,
        val loading: Boolean = true, val error: String? = null,
        /** picking the RomM folder for this system (System editor), not the library itself */
        val forSystem: String? = null
    )
    /** The share browser, when it's open. */
    var smbBrowse by mutableStateOf<SmbBrowse?>(null)

    /** Opens the browser where the library folder is now (or at the computer's list of shares). */
    fun openSmbBrowser() {
        if (smbHost().isEmpty()) { message = "Type the computer's address first (or set up RomM's server address)"; return }
        smbBrowseTo(key("smb_share").trim(), key("smb_path").trim().trim('/'))
    }

    /**
     * Opens the browser to pick the RomM folder that [platformId]'s games are uploaded to: it starts in the library's
     * roms folder (or the library, or the computer's shares, whichever can be reached).
     */
    fun openSmbBrowserFor(platformId: String) {
        if (smbHost().isEmpty()) { message = "Set up RomM's library share first (Settings \u2192 Connections \u2192 RomM)"; return }
        val share = key("smb_share").trim(); val lib = key("smb_path").trim().trim('/')
        smbBrowse = SmbBrowse(forSystem = platformId)
        smbBrowseTo(share, if (share.isEmpty()) "" else listOf(lib, "roms").filter { it.isNotEmpty() }.joinToString("/"))
    }

    /** Shows what's in [share]/[path] ("" share = the computer's shares). */
    fun smbBrowseTo(share: String, path: String) {
        val forSystem = smbBrowse?.forSystem
        smbBrowse = SmbBrowse(share, path, smbBrowse?.listing, loading = true, forSystem = forSystem)
        val host = smbHost(); val user = key("smb_user").trim(); val pass = key("smb_pass")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { com.eclipse.launcher.data.SmbUpload.browse(host, user, pass, share, path) }
            }
            // (closed, or moved somewhere else, while this was loading)
            val now = smbBrowse ?: return@launch
            if (now.share != share || now.path != path) return@launch
            smbBrowse = r.fold(
                { SmbBrowse(share, path, it, loading = false, forSystem = forSystem) },
                { e ->
                    // a saved folder that's gone: start again from the computer's shares
                    if (share.isNotEmpty() && path.isNotEmpty() && now.listing == null) {
                        // (a system's pick: try the library itself before the share's top)
                        val lib = key("smb_path").trim().trim('/')
                        smbBrowseTo(share, if (forSystem != null && path != lib && lib.isNotEmpty()) lib else "")
                        return@launch
                    }
                    SmbBrowse(share, path, null, loading = false, error = smbError(e), forSystem = forSystem)
                }
            )
        }
    }

    /** One level up: from a folder to its parent, from a share's top to the list of shares. */
    fun smbBrowseUp() {
        val b = smbBrowse ?: return
        when {
            b.path.isNotEmpty() -> smbBrowseTo(b.share, b.path.substringBeforeLast('/', ""))
            b.share.isNotEmpty() -> smbBrowseTo("", "")
            else -> smbBrowse = null
        }
    }

    /** "Use this folder": saves the share and the path inside it (and the computer, if it came from RomM's address). */
    fun smbUseFolder() {
        val b = smbBrowse ?: return
        if (b.share.isEmpty()) return
        if (b.forSystem != null) {
            // a system's folder: its name inside roms/ (or the library) is what uploads use
            val name = b.path.substringAfterLast('/')
            if (name.isEmpty()) { message = "Open the system's folder first"; return }
            setRommFolder(b.forSystem, name)
            smbBrowse = null
            message = "${Platforms.byId(b.forSystem)?.name ?: b.forSystem} uploads go to $name"
            return
        }
        if (key("smb_host").isBlank()) setKey("smb_host", smbHost())
        setKey("smb_share", b.share)
        setKey("smb_path", b.path)
        smbBrowse = null
        checkSmb()
    }

    private fun smbError(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("Logon failure", true) || m.contains("LOGON", false) || m.contains("ACCESS_DENIED", true) || m.contains("Access is denied", true) ->
                "The computer turned down the username or password"
            m.contains("timed out", true) || m.contains("timeout", true) || e is java.net.ConnectException || e.cause is java.net.ConnectException ->
                "Couldn't reach ${smbHost()}: is it on, and is file sharing turned on?"
            m.contains("BAD_NETWORK_NAME", true) || m.contains("cannot find", true) -> "That share isn't there any more"
            else -> m.ifBlank { "Couldn't look at that folder" }
        }
    }

    /** Can [g] be uploaded? A file on this device that RomM doesn't have yet. */
    fun canUploadToRomm(g: Game): Boolean =
        // (not PC games: Steam / Windows entries are shortcuts, not files RomM can hold)
        isConfigured("romm") && g.platform !in setOf("android", "steam", "windows", "ps3", "xbox360") && !g.id.startsWith("rm:") && rommIdFor(g) == null && File(g.path).isFile

    /** Copies the game (every disc of a multi-disc game) into RomM's library; RomM adds it on its next scan. */
    fun uploadToRomm(g: Game) {
        if (g.id in uploads) return
        val t = smbTarget() ?: run {
            message = "Set up RomM's library share first (Settings \u2192 Connections \u2192 RomM)"
            settingsTab = com.eclipse.launcher.ui.SettingsTabs.CONNECTIONS; go(Screen.SETTINGS); return
        }
        val files = com.eclipse.launcher.data.SmbUpload.filesOf(File(g.path))
        val folder = rommUploadFolder(g.platform)
        uploads[g.id] = 0f
        message = "Uploading ${g.name} to RomM (${files.size} file${if (files.size == 1) "" else "s"})\u2026"
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                var last = 0f
                com.eclipse.launcher.data.SmbUpload.upload(t, folder, g.name, files) { p ->
                    if (p - last >= 0.01f || p >= 1f) { last = p; viewModelScope.launch { uploads[g.id] = p } }
                }
            }
            uploads.remove(g.id)
            addTransfer(Transfer(true, g.name, g.platform, System.currentTimeMillis(), err == null, if (err == null) files.sumOf { it.length() } else 0L, err))
            if (err != null) { message = "Upload failed: $err"; return@launch }
            message = "Uploaded ${g.name}. Asking RomM to scan for it\u2026"
            rommScanAfterUpload(folder, g.name)
        }
    }

    /**
     * After an upload: asks RomM to scan that platform's folder (its API), waits for the scan to finish, then syncs
     * Eclipse's copy of the RomM library so the game shows as on RomM. Falls back to saying it'll come on RomM's own
     * next scan when the account can't start scans.
     */
    private suspend fun rommScanAfterUpload(folder: String, name: String) {
        val c = rommConn() ?: return
        val (task, problem) = RomM.scan(c, folder)
        if (problem != null) { message = "Uploaded $name. $problem, so it'll appear after RomM's next scan."; return }
        rommStatus = "RomM is scanning for $name\u2026"
        if (task != null) {
            // follow the scan for up to five minutes
            for (i in 0 until 60) {
                kotlinx.coroutines.delay(5_000)
                val st = RomM.taskStatus(c, task) ?: break
                if (st == "finished") break
                if (st == "failed" || st == "stopped" || st == "canceled") { message = "RomM's scan didn't finish ($st). Sync library later."; return }
            }
        } else kotlinx.coroutines.delay(20_000) // a scan was already running, or no id to follow
        message = "RomM has scanned: syncing\u2026"
        rommSync()
    }

    // ---- Settings → Library → Tools: CHD (the converter above), playlists, and sending many games to RomM ----
    var toolsOpen by mutableStateOf(false)

    // ---- Settings → Views: which view types are offered, and installing new ones ----
    /** The built-in games-list views: id → name. Your own are "view:<id>" (from Eclipse/views). */
    val builtinViews = listOf(
        "carousel" to "Slide view", "coverflow" to "Cover flow", "detailflow" to "Detail cover flow", "grid" to "Grid", "list" to "List"
    )
    var viewsOff by mutableStateOf(store.str("views_off").split(',').filter { it.isNotBlank() }.toSet())
        private set
    fun viewOn(id: String) = id !in viewsOff
    /** Turns a view on / off; [all] is every view there is, so the last one on can't be turned off. */
    fun setViewOn(id: String, on: Boolean, all: List<String>) {
        if (!on && all.count { it != id && viewOn(it) } == 0) { message = "Keep at least one view on"; return }
        viewsOff = if (on) viewsOff - id else viewsOff + id
        store.putStr("views_off", viewsOff.joinToString(","))
    }
    /**
     * The views a screen offers ("games", "recent" or "systems"): its built-in ones, then your view types made for it
     * (their "screens"). Not yet filtered by on / off (Settings → Views): [viewOn] does that.
     */
    fun viewsFor(screen: String, custom: List<com.eclipse.launcher.data.Views.Spec>): List<Pair<String, String>> {
        val built = when (screen) {
            "recent" -> listOf("carousel" to "Slide view", "coverflow" to "Cover flow", "detailflow" to "Detail cover flow")
            "systems" -> listOf("grid" to "Grid", "carousel" to "Slide view", "coverflow" to "Cover flow")
            else -> builtinViews
        }
        return built + custom.filter { it.on(screen) }.map { "view:" + it.id to it.name }
    }

    /** The view a games screen shows: the one chosen if it's on (and still there), else the first one that's on. */
    fun shownView(chosen: String, all: List<String>): String =
        chosen.takeIf { it in all && viewOn(it) } ?: all.firstOrNull { viewOn(it) } ?: "grid"

    /**
     * Installs a view type from a file you pick: a .json (one view), or a .zip with one or more (a "view.json" in a
     * folder, or any .json files). Each is read first; ones with a mistake are left out and named. Goes into
     * Eclipse/views, where the games screen finds it within two seconds.
     */
    fun installView(uri: Uri) {
        viewModelScope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val display = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "view.json"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var n: Int
                        while (input.read(buf).also { n = it } > 0) { out.write(buf, 0, n); if (out.size() > 4_000_000) throw java.io.IOException("that file is too big for a view") }
                        out.toByteArray()
                    } ?: throw java.io.IOException("couldn't open it")
                    val dir = com.eclipse.launcher.data.Views.dir().apply { mkdirs() }
                    val safe = { s: String -> s.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ').ifEmpty { "view" } }
                    // (name, json text) for every view in the file
                    val found = ArrayList<Pair<String, String>>()
                    if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
                            var e = z.nextEntry
                            while (e != null) {
                                if (!e.isDirectory && e.name.endsWith(".json", true) && found.size < 50) {
                                    val text = z.readBytes().toString(Charsets.UTF_8)
                                    // "pack/view.json" is named after its folder; "neat-flow.json" after itself
                                    val name = if (e.name.substringAfterLast('/').equals("view.json", true))
                                        (e.name.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { display.substringBeforeLast('.') }) + ".json"
                                    else e.name
                                    found += safe(name) to text
                                }
                                e = z.nextEntry
                            }
                        }
                    } else found += safe(if (display.endsWith(".json", true)) display else display.substringBeforeLast('.') + ".json") to bytes.toString(Charsets.UTF_8)
                    if (found.isEmpty()) return@runCatching "No view in that file: it needs a .json view (see VIEWS.md)"
                    val added = ArrayList<String>(); val bad = ArrayList<String>()
                    for ((name, text) in found) {
                        val tmp = File(ctx.cacheDir, "view-check.json").apply { writeText(text) }
                        val problem = com.eclipse.launcher.data.Views.check(tmp)
                        if (problem != null) { bad += "$name ($problem)"; continue }
                        File(dir, name).writeText(text)
                        added += name.removeSuffix(".json")
                        tmp.delete()
                    }
                    when {
                        added.isEmpty() -> "Couldn't install: ${bad.joinToString("; ")}"
                        bad.isEmpty() -> "Installed ${added.joinToString(", ")}. Pick it in the View list (press R) of the screens it's made for."
                        else -> "Installed ${added.joinToString(", ")}. Left out: ${bad.joinToString("; ")}"
                    }
                }.getOrElse { "Couldn't install that view: ${it.message ?: it.javaClass.simpleName}" }
            }
        }
    }

    /** Deletes one of your views (its file, or its folder when it's a folder's view.json). */
    fun removeView(spec: com.eclipse.launcher.data.Views.Spec) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val f = spec.file
                val dir = com.eclipse.launcher.data.Views.dir()
                if (f.name.equals("view.json", true) && f.parentFile?.parentFile?.absolutePath == dir.absolutePath) f.parentFile?.deleteRecursively()
                else f.delete()
            }
            viewsOff = viewsOff - ("view:" + spec.id); store.putStr("views_off", viewsOff.joinToString(","))
            message = "Removed ${spec.name}"
        }
    }

    // ---- the first-run tour of Home (after setup; Settings → General → Show the tutorial again) ----
    var tutorialOpen by mutableStateOf(false)
    var tutorialStep by mutableStateOf(0)
    fun finishTutorial() {
        setFlag("tutorial_done", true)
        tutorialOpen = false; tutorialStep = 0
        dockPeek = false; platDrawer = false; deckDrawer = false; appsDrawer = false
    }
    fun restartTutorial() {
        setFlag("tutorial_done", false)
        tutorialStep = 0
        go(Screen.HOME)
    }

    /** The opening animation has played (or was skipped / is turned off): once per start of Eclipse. */
    var splashDone by mutableStateOf(false)
    /** 0 CHD, 1 Playlists, 2 Send to RomM */
    var toolsTab by mutableStateOf(0)

    // Playlists: the multi-disc games found by scanM3u() (the Tools screen)
    var m3uBusy by mutableStateOf(false)
        private set

    /** Disc numbers missing from a set ("Disc 3" of 1, 2, 4): those games don't get a playlist. */
    fun m3uMissing(s: com.eclipse.launcher.data.M3u.DiscSet): List<Int> {
        val have = s.discs.mapNotNull { com.eclipse.launcher.data.M3u.discNumber(it.nameWithoutExtension) }.toSet()
        return (1..(have.maxOrNull() ?: 0)).filter { it !in have }
    }


    /** Writes (or rewrites) the picked playlists, then rescans so each game shows once. */
    fun makePlaylists() {
        val sets = m3uSets.orEmpty().filter { it.key in m3uPicked && m3uMissing(it).isEmpty() }
        if (sets.isEmpty() || m3uBusy) return
        m3uBusy = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { sets.count { runCatching { com.eclipse.launcher.data.M3u.write(it) }.isSuccess } }
            message = if (ok == sets.size) "Made $ok playlist${if (ok == 1) "" else "s"}" else "Made $ok of ${sets.size} playlists (some folders couldn't be written)"
            m3uBusy = false
            scanM3u()
            rescan(full = true)
        }
    }

    /** Games on this device that RomM doesn't have, by system then name. */
    fun rommUpCandidates(): List<Game> = games.filter { canUploadToRomm(it) }
        .sortedWith(compareBy({ com.eclipse.launcher.data.Platforms.byId(it.platform)?.name?.lowercase() ?: it.platform }, { it.name.lowercase() }))

    /** Ticked games on the Send to RomM tab (null = all of them, until you change something). */
    var rommUpPicked by mutableStateOf<Set<String>?>(null)
    fun rommUpIsPicked(id: String) = rommUpPicked?.contains(id) ?: true
    fun toggleRommUp(id: String, all: List<Game>) {
        val cur = rommUpPicked ?: all.map { it.id }.toSet()
        rommUpPicked = if (id in cur) cur - id else cur + id
    }
    /** True when the system has no folder on RomM yet (one named after Eclipse's id is made). */
    fun rommFolderIsNew(platformId: String) = rommFolderPick[platformId] == null && rommFolders[platformId] == null

    var upScanAfter by mutableStateOf(store.str("up_scan") != "0")
        private set
    fun chooseUpScan(on: Boolean) { upScanAfter = on; store.putStr("up_scan", if (on) "1" else "0") }

    /** A run of uploads: which game it's on, bytes sent so far of all of them, and when it started. */
    data class UpQueue(val ids: List<String>, val index: Int, val name: String, val bytesDone: Long, val bytesAll: Long, val startedAt: Long)
    var upQueue by mutableStateOf<UpQueue?>(null)
        private set
    @Volatile private var upStop = false
    var upStopping by mutableStateOf(false)
        private set

    /** Sends the games one at a time over RomM's share, then asks RomM to scan each folder that got something. */
    fun startBulkUpload(list: List<Game>) {
        if (upQueue != null || list.isEmpty()) return
        val t = smbTarget() ?: run { message = "Set up RomM's library share first (Settings \u2192 Connections \u2192 RomM)"; return }
        upStop = false; upStopping = false
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { list.associate { g -> g.id to com.eclipse.launcher.data.SmbUpload.filesOf(File(g.path)) } }
            val sizes = files.mapValues { (_, fs) -> fs.sumOf { it.length() } }
            val started = System.currentTimeMillis()
            upQueue = UpQueue(list.map { it.id }, 0, list.first().name, 0L, sizes.values.sum(), started)
            var done = 0L
            var ok = 0; var failed = 0
            val folders = LinkedHashSet<String>()
            for ((i, g) in list.withIndex()) {
                if (upStop) break
                val size = sizes[g.id] ?: 0L
                val folder = rommUploadFolder(g.platform)
                upQueue = upQueue?.copy(index = i, name = g.name, bytesDone = done)
                uploads[g.id] = 0f
                val base = done
                val err = withContext(Dispatchers.IO) {
                    var last = 0f
                    com.eclipse.launcher.data.SmbUpload.upload(t, folder, g.name, files[g.id].orEmpty()) { p ->
                        if (p - last >= 0.01f || p >= 1f) {
                            last = p
                            viewModelScope.launch { uploads[g.id] = p; upQueue = upQueue?.copy(bytesDone = base + (size * p).toLong()) }
                        }
                    }
                }
                uploads.remove(g.id)
                addTransfer(Transfer(true, g.name, g.platform, System.currentTimeMillis(), err == null, if (err == null) size else 0L, err))
                done += size
                if (err == null) { ok++; folders += folder } else failed++
            }
            upQueue = null; upStopping = false
            rommUpPicked = null
            message = buildString {
                append(if (upStop) "Stopped. " else "")
                append("Sent $ok game${if (ok == 1) "" else "s"} to RomM")
                if (failed > 0) append(", $failed failed (see Transfers)")
            }
            if (ok > 0 && upScanAfter) for (f in folders) rommScanAfterUpload(f, "$ok game${if (ok == 1) "" else "s"}")
        }
    }

    /** Pause: the game being sent finishes, then it stops. */
    fun stopBulkUpload() { upStop = true; upStopping = true }

    /** Your downloaded copy of a RomM game, if you have one. */
    fun installedRom(romId: Int): Game? {
        val id = romId.toString()
        val gid = rommLink.entries.firstOrNull { it.value == id }?.key ?: return null
        return games.firstOrNull { it.id == gid }
    }

    /**
     * Reads your whole RomM library into the store. Games you already have in your ROM folder are linked to their
     * RomM entry (they show as installed, and get RomM's artwork / description where theirs is missing).
     */
    /** When the RomM library was last read (0: never); shown on Settings → Connections. */
    var rommSyncedAt by mutableStateOf(store.str("romm_synced").toLongOrNull() ?: 0L)

    fun rommSync(quiet: Boolean = false) {
        val c = rommConn() ?: run { if (!quiet) rommStatus = "Enter the server address, username and password first"; return }
        if (rommBusy) return
        rommBusy = true
        rommStatus = "Reading your RomM library…"
        viewModelScope.launch {
            val plats = RomM.platforms(c)
            if (plats == null) {
                rommStatus = RomM.test(c).second.let { if (it.startsWith("Connected")) "Couldn't read the platform list" else it }
                rommBusy = false
                return@launch
            }
            val roms = HashMap<String, String>()
            val slugs = HashMap(rommFolders)
            val links = HashMap(rommLink)
            var skipped = 0
            val local = games.filter { it.platform != "android" }
            val byFile = local.associateBy { it.platform + "|" + File(it.path).name.lowercase() }
            val byFolder = local.associateBy { it.platform + "|" + (File(it.path).parentFile?.name ?: "").lowercase() }
            val byStem = local.associateBy { it.platform + "|" + File(it.path).nameWithoutExtension.lowercase() }
            val newArt = HashMap(art)
            val newDesc = HashMap(desc)
            val fillMeta = flag("romm_meta")
            for ((i, p) in plats.withIndex()) {
                val bp = Platforms.matchFolder(p.fsSlug) ?: Platforms.matchFolder(p.slug) ?: Platforms.byId(p.slug)
                if (bp == null) { skipped++; continue }
                slugs[bp.id] = p.fsSlug
                rommStatus = "Reading ${p.name} (${i + 1} of ${plats.size})…"
                val list = RomM.roms(c, p.id) ?: continue
                for (raw in list) {
                    val r = raw.copy(bp = bp.id, platformName = p.name.ifBlank { bp.name })
                    roms[r.id.toString()] = r.encode()
                    val fsLower = r.fsName.lowercase()
                    val have = byFile[bp.id + "|" + fsLower]
                        ?: (if (r.multi) byFolder[bp.id + "|" + fsLower.substringBeforeLast('.')] ?: byFolder[bp.id + "|" + fsLower] else null)
                        ?: byStem[bp.id + "|" + fsLower.substringBeforeLast('.')]
                        ?: continue
                    links[have.id] = r.id.toString()
                    if (!fillMeta) continue
                    r.cover?.let { if (newArt["${have.id}|${ArtKind.BOX.key}"] == null) newArt["${have.id}|${ArtKind.BOX.key}"] = it }
                    r.screenshot?.let { if (newArt["${have.id}|${ArtKind.BACKDROP.key}"] == null) newArt["${have.id}|${ArtKind.BACKDROP.key}"] = it }
                    r.summary?.let { if (newDesc[have.id] == null) newDesc[have.id] = it }
                }
            }
            rommRoms = roms
            rommFolders = slugs; store.putMap("romm_folders", slugs)
            rommLink = links.filterKeys { id -> local.any { it.id == id } }
            art = newArt
            desc = newDesc
            rebuildCatalog()
            persistRomm(); persistArt(); persistDesc()
            rommSyncedAt = System.currentTimeMillis(); store.putStr("romm_synced", rommSyncedAt.toString())
            rommStatus = "Synced: ${roms.size} games on RomM · ${rommLink.size} already on this device" +
                if (skipped > 0) " · $skipped systems Eclipse doesn't know" else ""
            rommBusy = false
        }
    }

    /** Where a system's games go in your ROM folder: an existing folder for that system, else one named after it. */
    private fun systemFolder(platform: String): File {
        val root = File(romPath)
        root.listFiles()?.firstOrNull { it.isDirectory && Platforms.matchFolder(it.name)?.id == platform }?.let { return it }
        return File(root, platform)
    }

    /**
     * What opening a RomM game (one not on this device) does: "play" = download and play, "download" = just download,
     * "stream" = play it in RomM's browser player, "ask" = the popup with all three (Settings → RomM).
     */
    var rommLaunch by mutableStateOf(store.str("romm_launch", "ask"))
        private set
    fun chooseRommLaunch(v: String) { rommLaunch = v; store.putStr("romm_launch", v) }

    // ---- scopes: the main screens in the left dock (Search, Recent, Systems, Apps) ----
    // Settings → General → Left dock moves them up / down and hides them. Ids: "search", "recent", "systems", "apps".
    var scopeOrder by mutableStateOf(store.str("scope_order").split(',').filter { it.isNotBlank() })
        private set
    var scopeHidden by mutableStateOf(store.str("scope_hidden").split(',').filter { it.isNotBlank() }.toSet())
        private set
    /** Every scope, in your order. */
    fun scopes(): List<String> {
        val all = listOf("search", "recent", "systems", "apps")
        return scopeOrder.filter { it in all } + all.filter { it !in scopeOrder }
    }
    fun moveScope(id: String, by: Int) {
        val l = scopes().toMutableList()
        val i = l.indexOf(id); val j = i + by
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i))
        scopeOrder = l
        store.putStr("scope_order", l.joinToString(","))
    }
    fun showScope(id: String, on: Boolean) {
        scopeHidden = if (on) scopeHidden - id else scopeHidden + id
        store.putStr("scope_hidden", scopeHidden.joinToString(","))
    }
    fun scopeName(id: String): String = when (id) {
        "search" -> "Search"; "recent" -> "Recent"; "systems" -> "Systems"; "apps" -> "Apps"
        else -> id
    }
    /** The RomM game whose "download / play / stream?" popup is open. */
    var rommAsk by mutableStateOf<Game?>(null)

    /** Opens a RomM game the way [how] says ("play", or "download"). */
    fun rommOpen(g: Game, how: String) {
        val r = romOf(g) ?: run { message = "Sync RomM again, this game's details are missing"; return }
        val discs = rommDiscs[r.id].orEmpty()
        // a multi-disc game: download all its discs, or one?
        if (discs.size > 1) { rommDiscAsk = g to (how != "download"); return }
        when (how) {
            "download" -> { message = "Installing ${g.name} from RomM\u2026"; rommDownload(r, playWhenDone = false) }
            else -> { message = "Installing ${g.name} from RomM\u2026 it starts when it's ready"; rommDownload(r, playWhenDone = true) }
        }
    }

    /** Queues a RomM game for download (in the background, with a notification). */
    fun rommDownload(rom: RomM.Rom, playWhenDone: Boolean = false) {
        val c = rommConn() ?: run { message = "Connect to RomM first (Settings → Connections)"; return }
        if (romPath.isBlank()) { message = "Choose a ROM folder first (Settings → Library)"; return }
        if (!hasAllFiles()) { message = "Allow all-files access first (Settings → General → Permissions)"; return }
        installedRom(rom.id)?.let { g -> if (playWhenDone) play(g); return }
        PermissionAsk.notifications?.invoke()
        RommDownloads.enqueue(ctx, c, rom, systemFolder(rom.bp), playWhenDone)
    }

    init {
        RommDownloads.onDone = { rom, file, playNow ->
            addTransfer(Transfer(false, rom.name, rom.bp, System.currentTimeMillis(), true, file.length()))
            rommInstalled(rom, file, playNow)
        }
        RommDownloads.onFailed = { rom ->
            addTransfer(Transfer(false, rom.name, rom.bp, System.currentTimeMillis(), false, 0L))
            showNotice(Notice("Couldn't download", rom.name, "Check the connection to RomM and try again", rom.cover, "error"))
        }
    }

    /** A download finished: it's a normal local game now, with the same id a rescan will give it. */
    private fun rommInstalled(rom: RomM.Rom, file: File, playNow: Boolean, converted: Boolean = false, savedBytes: Long = 0L) {
        // a disc of a multi-disc game: the game (its playlist) is there from the first disc, updated with each one
        if (rommDiscs[rom.id] != null) {
            val pg = updateDiscPlaylist(rom, file)
            if (!converted && convertThenInstall(rom, file, playNow)) return
            if (pg != null) { readyNotice(rom, pg.name, converted, savedBytes, file); if (playNow) play(pg); return }
        }
        if (!converted && convertThenInstall(rom, file, playNow)) return
        readyNotice(rom, rom.name, converted, savedBytes, file)
        val g = Game("g" + Integer.toHexString(file.absolutePath.hashCode()), rom.name, rom.bp, file.absolutePath)
        if (games.none { it.id == g.id }) games = games + g
        rommLink = rommLink + (g.id to rom.id.toString())
        val newArt = HashMap(art)
        rom.cover?.let { newArt.putIfAbsent("${g.id}|${ArtKind.BOX.key}", it) }
        rom.screenshot?.let { newArt.putIfAbsent("${g.id}|${ArtKind.BACKDROP.key}", it) }
        art = newArt
        rom.summary?.let { if (desc[g.id] == null) desc = desc + (g.id to it) }
        persistGames(); persistArt(); persistDesc(); persistRomm()
        if (playNow) play(g)
    }

    /** The "ready" pop-up of a RomM download (a disc of a multi-disc game says which disc). */
    private fun readyNotice(rom: RomM.Rom, name: String, converted: Boolean, savedBytes: Long, file: File) {
        val set = rommDiscs[rom.id]
        // GameCube / Wii discs become RVZ, the rest CHD
        val fmt = if (rom.bp == "xbox") "XISO" else if (file.extension.equals("rvz", true) || (converted && com.eclipse.launcher.data.Rvz.isNintendoDisc(file))) "RVZ" else "CHD"
        val kind = if (set != null && set.size > 1) "Disc ${set.indexOfFirst { it.id == rom.id } + 1} of ${set.size} ready" else "Ready to play"
        val detail = "Downloaded from RomM" + when {
            converted && savedBytes > 0 -> " \u00b7 $fmt, saved ${fmtBytes(savedBytes)}"
            converted && savedBytes == 0L -> " \u00b7 converted to $fmt"
            converted -> " \u00b7 kept as is ($fmt failed)"
            else -> ""
        }
        showNotice(Notice(kind, name, detail, (set?.firstOrNull() ?: rom).cover, "ok"))
    }

    /**
     * Settings → Connections → RomM → "Convert installs to CHD": a finished .cue / .iso install is converted first (to RVZ
     * for a GameCube / Wii disc, which Dolphin plays; CHD for the rest) and
     * only then shown (and played, for "Install & play"). A multi-disc game plays once disc 1 is converted; the other
     * discs convert after it. If a conversion fails the original is used. Returns true when it took over.
     */
    private fun convertThenInstall(rom: RomM.Rom, file: File, playNow: Boolean): Boolean {
        if (!flag("romm_chd", false)) return false
        if (!isConvertible(file)) return false
        if (alreadyConverted(file)) return false
        // a system whose emulators can't use CHD / RVZ (Xbox, PS3 …): installed as it came
        val item = discItem(file, false, rom.bp) ?: return false
        viewModelScope.launch {
            while (chdJob != null) delay(1000)
            message = "Converting ${rom.name} to ${item.format}\u2026"
            val del = chdDelete
            runChd(listOf(item), del, auto = true, romm = true) { saved, ok ->
                // (an XISO replaces the original under its own name when originals are deleted)
                val done = if (item.xiso && del) file else File(file.parentFile, file.nameWithoutExtension + "." + item.ext)
                rommInstalled(rom, if (ok > 0 && done.isFile) done else file, playNow, converted = true, savedBytes = if (ok > 0) saved else -1L)
            }
        }
        return true
    }

    /** Deletes your downloaded copy (RomM keeps its own). Your play time and artwork are kept for next time. */
    fun rommDeleteDownload(romId: Int) {
        val g = installedRom(romId) ?: return
        deleteLocalGame(g, rommCatalog.firstOrNull { it.id == romId })
    }

    /** What "Uninstall" does to this game, or null when there's nothing on this device to remove (a RomM entry). */
    fun uninstallKind(g: Game): String? = when {
        g.id.startsWith("rm:") || g.path.startsWith("romm:") -> null
        g.platform == "android" -> "app"
        rommIdFor(g)?.toIntOrNull()?.let { installedRom(it)?.id == g.id } == true -> "romm"
        else -> "files"
    }

    /**
     * Uninstalls any game: an Android game through Android's own uninstall prompt, a game downloaded from RomM by
     * deleting the download, anything else by deleting its files from your ROM folders.
     */
    fun uninstallGame(g: Game) {
        when (uninstallKind(g)) {
            "app" -> {
                // Android asks you to confirm; the app list refreshes itself once the package is gone
                val ok = runCatching {
                    ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:${g.path}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
                if (!ok) message = "Couldn't uninstall ${g.name}"
            }
            "romm" -> rommIdFor(g)?.toIntOrNull()?.let { rommDeleteDownload(it) }
            "files" -> deleteLocalGame(g, null)
            else -> message = "${g.name} isn't installed on this device"
        }
    }

    /** Android's uninstall prompt for an app (the app menu). */
    fun uninstallApp(pkg: String, name: String) {
        if (pkg == ctx.packageName) { message = "Eclipse can't uninstall itself from here"; return }
        uninstallGame(Game("a_$pkg", name, "android", pkg))
    }

    /** Every folder games are kept in: your ROM folders and each system's own folders. */
    private fun gameRoots(): List<File> =
        (romFolders() + sysFolders.values.flatMap { it.split('\n') }).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { runCatching { File(it).canonicalFile }.getOrNull() }

    private val DISC_EXTS = listOf("chd", "rvz", "cue", "gdi", "ccd", "mds", "gcz", "iso", "gcm", "cso", "pbp")
    private val TRACK_EXTS = listOf("bin", "img", "sub", "raw", "mdf")
    private val TRACK_TAG = Regex("""\s*\(\s*track\s*\d+\s*\)""", RegexOption.IGNORE_CASE)
    private fun addDiscOnce(d: File, into: MutableSet<File>, inside: (File) -> Boolean) {
        if (d in into || !inside(d)) return
        into += d
        if (d.extension.equals("cue", true) || d.extension.equals("gdi", true))
            runCatching { com.eclipse.launcher.data.Chd.cueFiles(d) }.getOrDefault(emptyList()).filter(inside).forEach { into += it }
    }

    /** Deletes a game's files (only ever inside your game folders) and takes it out of the library. */
    private fun deleteLocalGame(g: Game, rom: RomM.Rom?) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val f = File(g.path).canonicalFile
                val roots = gameRoots()
                val root = roots.firstOrNull { f.path.startsWith(it.path + File.separator) } ?: return@withContext false
                fun inside(x: File) = runCatching { x.canonicalPath.startsWith(root.path + File.separator) }.getOrDefault(false)
                // a folder that holds only this game (never a ROM folder or a system folder itself)
                fun gameFolder(d: File?) = d != null && d.canonicalFile != root && d.canonicalFile.parentFile != root &&
                    roots.none { it == d.canonicalFile } && inside(d)
                val folder = f.parentFile
                when {
                    f.isDirectory -> gameFolder(f) && f.deleteRecursively()
                    // a multi-file game from RomM lives in its own folder named after it: remove the whole folder
                    rom != null && rom.multi && folder != null && gameFolder(folder) &&
                        RomM.safeName(rom.fsName).let { n -> folder.name.equals(n.substringBeforeLast('.').ifEmpty { n }, ignoreCase = true) } ->
                        folder.deleteRecursively()
                    f.extension.equals("cue", true) || f.extension.equals("gdi", true) -> {
                        // the sheet, the files it names, any CHD made from it
                        runCatching { com.eclipse.launcher.data.Chd.cueFiles(f) }.getOrDefault(emptyList())
                            .filter { inside(it) && it.canonicalFile != f }.forEach { it.delete() }
                        File(f.parentFile, f.nameWithoutExtension + ".chd").delete()
                        f.delete() || !f.exists()
                    }
                    f.extension.equals("m3u", true) -> {
                        // a multi-disc game: every disc of it in this folder, in every form (the playlist can be out
                        // of date: a disc converted to CHD after it was written is still listed as its .cue)
                        val dir = f.parentFile
                        val doomed = LinkedHashSet<File>()
                        fun addDisc(d: File) {
                            if (!inside(d)) return
                            doomed += d
                            if (d.extension.equals("cue", true) || d.extension.equals("gdi", true))
                                runCatching { com.eclipse.launcher.data.Chd.cueFiles(d) }.getOrDefault(emptyList()).filter { inside(it) }.forEach { doomed += it }
                            // the same disc in its other forms: "Game (Disc 1).cue" ↔ "Game (Disc 1).chd"
                            for (ext in DISC_EXTS) File(d.parentFile, d.nameWithoutExtension + "." + ext).takeIf { it.isFile }?.let { addDiscOnce(it, doomed, ::inside) }
                        }
                        runCatching { com.eclipse.launcher.data.M3u.entries(f) }.getOrDefault(emptyList()).forEach { addDisc(File(dir, it)) }
                        // and any file in the folder named after this game with a disc tag ("Title (Disc 3).chd",
                        // "Title (Disc 3) (Track 2).bin"), listed or not
                        val title = f.nameWithoutExtension
                        dir?.listFiles()?.filter { x ->
                            x.isFile && x.extension.lowercase() in DISC_EXTS + TRACK_EXTS &&
                                com.eclipse.launcher.data.M3u.discNumber(x.nameWithoutExtension) != null &&
                                com.eclipse.launcher.data.M3u.titleOf(x.nameWithoutExtension).replace(TRACK_TAG, "").trim().equals(title, ignoreCase = true)
                        }?.forEach { addDisc(it) }
                        doomed.forEach { it.delete() }
                        f.delete() || !f.exists()
                    }
                    else -> f.delete() || !f.exists()
                }.also { done ->
                    // a game folder left empty goes too
                    if (done && folder != null && gameFolder(folder) && folder.listFiles()?.isEmpty() == true) folder.delete()
                }
            }
            if (!ok) { message = "Couldn't delete ${g.name}"; return@launch }
            games = games.filter { it.id != g.id }
            rommLink = rommLink - g.id
            recent = recent.filter { it != g.id }
            store.putList("recent", recent)
            if (focused?.id == g.id) focused = null
            if (menuGame?.id == g.id) menuGame = null
            persistGames(); persistRomm()
            message = "Deleted ${g.name}"
        }
    }

    // ---- save sync (files named after the game, in the save folders you list; RetroArch's by default) ----
    private val saveExts = setOf("srm", "sav", "dsv", "mcr", "mcd", "eep", "fla", "sra", "mpk", "rtc", "nv", "bkr", "brm", "vmu", "bin", "dat", "state", "ps2")

    fun saveDirs(): List<File> =
        key("romm_savedirs").ifBlank { "/storage/emulated/0/RetroArch/saves" }
            .split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }

    private fun saveFilesFor(g: Game): List<File> {
        val stem = File(g.path).nameWithoutExtension.lowercase()
        return saveDirs().flatMap { d ->
            d.walkTopDown().maxDepth(4).filter { it.isFile && it.nameWithoutExtension.lowercase() == stem }.toList()
        }.filter { it.extension.lowercase() in saveExts || it.extension.lowercase().startsWith("state") }
    }

    private suspend fun rommPullSaves(g: Game, rid: String) {
        val c = rommConn() ?: return
        val id = rid.toIntOrNull() ?: return
        val remote = RomM.saves(c, id)
        if (remote.isEmpty()) return
        val localFiles = withContext(Dispatchers.IO) { saveFilesFor(g) }
        for (s in remote) {
            val mine = localFiles.firstOrNull { it.name.equals(s.fileName, ignoreCase = true) }
            val newer = mine == null || (s.updatedMs > 0 && s.updatedMs > mine.lastModified() + 60_000)
            if (!newer) continue
            // (the server's name is cleaned: it can't point outside the save folder)
            val dest = mine ?: File(saveDirs().firstOrNull() ?: continue, RomM.safeName(s.fileName, "save"))
            RomM.downloadSave(c, s, dest)
        }
    }

    private fun rommPushSaves(g: Game, sinceMs: Long) {
        if (!flag("romm_saves", false) || g.platform == "android") return
        val c = rommConn() ?: return
        val id = rommIdFor(g)?.toIntOrNull() ?: return
        val emu = emuFor(g)?.label?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.ifEmpty { null } ?: "eclipse"
        viewModelScope.launch(Dispatchers.IO) {
            val changed = saveFilesFor(g).filter { it.lastModified() >= sinceMs - 5_000 }
            var sent = 0
            for (f in changed) if (RomM.uploadSave(c, id, emu, f)) sent++
            if (sent > 0) withContext(Dispatchers.Main) { message = "Uploaded $sent save${if (sent == 1) "" else "s"} to RomM" }
        }
    }

    // ---- noticing that the game closed (needs the Eclipse helper; without it the session ends when you're back in Eclipse)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sessionSeen = false            // the game has been in front since it started
    private var pendingEnd: Runnable? = null

    /** Package that runs the current game: the Android game itself, or its emulator. */
    private fun sessionPkg(): String? = session?.game?.let { g -> if (g.platform == "android") g.path else emuFor(g)?.pkg }

    private val overlayPkgs = setOf("com.android.systemui", "com.google.android.gms", "com.google.android.play.games", "android")

    init {
        ScreenshotService.onMainForeground = { pkg -> mainHandler.post { onMainForeground(pkg) } }
        // the app switcher is about to bring [pkg] to the front: resume its game before Eclipse stops
        com.eclipse.launcher.media.SecondScreenHost.onSwitch = { pkg -> resumeIfGame(pkg) }
    }

    /** The last game that was playing and the app that runs it (see [resumeIfGame]). */
    private var lastSession: Pair<Game, String>? = null
    private var lastSessionEnd = 0L

    /**
     * A game you left is back in front (the app switcher, Android's Recents): its session starts again, so the bottom
     * screen shows Now Playing again and play time counts from now. Within 12 hours of leaving it.
     */
    private fun resumeIfGame(pkg: String) {
        if (session != null) return
        // kept on disk too: Android may have closed Eclipse in the background while you played
        if (lastSession == null) runCatching {
            val parts = store.str("last_session").split('\n')
            val g = games.firstOrNull { it.id == parts.getOrNull(0) }
            val p = parts.getOrNull(1)
            if (g != null && !p.isNullOrBlank()) { lastSession = g to p; lastSessionEnd = parts.getOrNull(2)?.toLongOrNull() ?: 0L }
        }
        val (g, gamePkg) = lastSession ?: return
        if (pkg != gamePkg || System.currentTimeMillis() - lastSessionEnd > 12 * 3_600_000L) return
        val s = Session(g, System.currentTimeMillis())
        session = s
        store.putStr("session", "${g.id}|${s.startMs}")
        sessionSeen = true
        pendingEnd?.let { mainHandler.removeCallbacks(it) }
        pendingEnd = null
        focused = g
        com.eclipse.launcher.media.SecondScreenHost.lastLaunchMs = System.currentTimeMillis()
        com.eclipse.launcher.media.SecondScreenHost.show?.invoke() // (the bottom screen may have been closed when Eclipse stopped)
    }

    private fun onMainForeground(pkg: String) {
        if (session == null) { resumeIfGame(pkg); return }
        val game = sessionPkg() ?: return
        if (pkg == game) {
            sessionSeen = true
            pendingEnd?.let { mainHandler.removeCallbacks(it) }
            pendingEnd = null
            return
        }
        // Eclipse coming back ends the session in onResume. Pop-ups, keyboards and system overlays don't count:
        // only a launcher or another real app taking the screen means the game is gone.
        if (!sessionSeen || pkg == ctx.packageName || pkg in overlayPkgs || pkg.contains("inputmethod") || pkg.contains("permissioncontroller")) return
        val isApp = apps.any { it.pkg == pkg } || pkg in homePackages()
        if (!isApp || pendingEnd != null) return
        // wait a moment, in case the game comes straight back
        val r = Runnable { pendingEnd = null; endSession() }
        pendingEnd = r
        mainHandler.postDelayed(r, 1_500)
    }

    private fun homePackages(): Set<String> = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun startSession(g: Game) {
        sessionSeen = false
        pendingEnd?.let { mainHandler.removeCallbacks(it) }
        pendingEnd = null
        val s = Session(g, System.currentTimeMillis())
        session = s
        store.putStr("session", "${g.id}|${s.startMs}")
        focused = g
        recent = (listOf(g.id) + recent.filter { it != g.id }).take(30)
        store.putList("recent", recent)
        lastPlayed = lastPlayed + (g.id to System.currentTimeMillis().toString())
        store.putMap("last_played", lastPlayed)
    }

    /** When each game was last started (slide view: "Last played"). */
    var lastPlayed by mutableStateOf(store.map("last_played"))
    fun lastPlayedLabel(g: Game): String? {
        val ms = lastPlayed[g.id]?.toLongOrNull() ?: return null
        val day = 86_400_000L
        val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        return when {
            ms >= today -> "Today"
            ms >= today - day -> "Yesterday"
            ms >= today - 6 * day -> "${((today - ms) / day) + 1} days ago"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(ms))
        }
    }

    /** The last few searches (search screen, bottom left). */
    var recentSearches by mutableStateOf(store.list("recent_searches"))
    fun addRecentSearch(t: String) {
        recentSearches = (listOf(t) + recentSearches.filter { !it.equals(t, true) }).take(6)
        store.putList("recent_searches", recentSearches)
    }

    /** Grid view's sort: "az", "recent", "played" or "fav" (favourites only). */
    var gameSort by mutableStateOf(store.str("game_sort").ifEmpty { "az" })
    fun chooseGameSort(v: String) { gameSort = v; store.putStr("game_sort", v) }

    /** Favourite games: shown first in each system's list (slide view: the heart, or Y). */
    var favGames by mutableStateOf(store.list("fav_games"))
    fun isFavGame(g: Game) = g.id in favGames
    fun toggleFavGame(g: Game) {
        favGames = if (g.id in favGames) favGames - g.id else favGames + g.id
        store.putList("fav_games", favGames)
        message = if (g.id in favGames) "Added \u201c${g.name}\u201d to favourites" else "Removed from favourites"
    }

    private fun restoreSession() {
        val raw = store.str("session")
        if (raw.isBlank()) return
        val parts = raw.split("|")
        val g = games.firstOrNull { it.id == parts[0] }
        val start = parts.getOrNull(1)?.toLongOrNull()
        if (g != null && start != null) session = Session(g, start) else store.putStr("session", "")
    }

    /** Back on the launcher for real (not a quick blip): the game session is over. */
    private fun endStaleSession() {
        val s = session ?: return
        if (System.currentTimeMillis() - s.startMs > 5_000) endSession()
    }

    fun endSession() {
        val s = session ?: return
        // remembered: if this game's app comes back to the front (app switcher, Recents), its session picks up again
        sessionPkg()?.let {
            lastSession = s.game to it; lastSessionEnd = System.currentTimeMillis()
            store.putStr("last_session", "${s.game.id}\n$it\n$lastSessionEnd")
        }
        rommPushSaves(s.game, s.startMs)
        val secs = ((System.currentTimeMillis() - s.startMs) / 1000).coerceIn(0L, MAX_SESSION_SECS)
        val total = (playtime[s.game.id]?.toLongOrNull() ?: 0L) + secs
        playtime = playtime + (s.game.id to total.toString())
        session = null
        store.putStr("session", "")
        persistPlaytime()
        flush() // play time matters: write it now
    }

    fun playedSeconds(g: Game): Long = playtime[g.id]?.toLongOrNull() ?: 0L

    // ---- platform cards (home screen, second row) ----
    /** Your own image if you set one, else the box art of the last game you played on that system, else any box art. */
    /** Box art for Eclipse's own games, from the app itself. */
    fun platformImage(id: String): String? {
        platArt[id]?.let { return it }
        if (id == com.eclipse.launcher.data.Platforms.ALL && iconSource()?.containsKey(id) != true)
            return recent.firstNotNullOfOrNull { rid -> games.firstOrNull { it.id == rid }?.let { artOf(it, ArtKind.BOX) } }
                ?: games.firstNotNullOfOrNull { artOf(it, ArtKind.BOX) }
        if (id == com.eclipse.launcher.data.Platforms.FAVORITES && iconSource()?.containsKey(id) != true)
            return favGames.firstNotNullOfOrNull { fid -> games.firstOrNull { it.id == fid }?.let { artOf(it, ArtKind.BOX) } }
        iconSource()?.get(id)?.let { return themeImage(it) }
        for (rid in recent) {
            val g = games.firstOrNull { it.id == rid && it.platform == id } ?: continue
            artOf(g, ArtKind.BOX)?.let { return it }
        }
        return games.asSequence().filter { it.platform == id }.mapNotNull { artOf(it, ArtKind.BOX) }.firstOrNull()
    }

    fun setPlatformArt(id: String, url: String?) {
        platArt = if (url == null) platArt - id else platArt + (id to url)
        store.putMap("platArt", platArt)
    }

    fun importPlatformArt(id: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(artDir, "up_plat_${id}_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                webpIfOn(f)
                withContext(Dispatchers.Main) { setPlatformArt(id, Uri.fromFile(f).toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't import that image" }
            }
        }
    }

    // ---- platform artwork: icon, logo, backdrop (Platform menu → Platform artwork) ----
    /** Pictures picked or fetched for systems, by system id. Icons and logos show Fit; backdrops fill the screen. */
    var platIcon by mutableStateOf(store.map("plat_icon"))
        private set
    var platLogo by mutableStateOf(store.map("plat_logo"))
        private set
    var platBg by mutableStateOf(store.map("plat_bg"))
        private set
    /** The system whose artwork picker is open, and the kind it opens on. */
    var platArtScreen by mutableStateOf<String?>(null)
    var platKindStart by mutableStateOf(PlatKind.ICON)
    /** When each system's kind was last looked up automatically ("id|kind" -> time): not asked again for a week. */

    fun platArtOf(id: String, k: PlatKind): String? = when (k) { PlatKind.CARD -> platArt[id]; PlatKind.ICON -> platIcon[id]; PlatKind.LOGO -> platLogo[id]; PlatKind.BACKDROP -> platBg[id] }

    /** A theme's background for the system, else the one picked for it. */
    fun platformBackdrop(id: String): String? = systemBackground(id) ?: platBg[id]

    private fun setPlatKind(id: String, k: PlatKind, url: String?) {
        val old = platArtOf(id, k)
        fun up(m: Map<String, String>) = if (url == null) m - id else m + (id to url)
        when (k) {
            PlatKind.CARD -> { platArt = up(platArt); store.putMap("platArt", platArt) }
            PlatKind.ICON -> { platIcon = up(platIcon); store.putMap("plat_icon", platIcon) }
            PlatKind.LOGO -> { platLogo = up(platLogo); store.putMap("plat_logo", platLogo) }
            PlatKind.BACKDROP -> { platBg = up(platBg); store.putMap("plat_bg", platBg) }
        }
        if (old != null && old != url && isScrapedFile(old)) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { Uri.parse(old).path?.let { File(it).delete() } } }
        }
    }

    /** Which sources can give this kind of picture right now (id to name). */
    fun platProviders(k: PlatKind): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        when (k) {
            PlatKind.CARD -> if (iconPacks.isNotEmpty()) l.add("packs" to "Icon packs")
            PlatKind.ICON -> {
                if (flag("p_lr")) l.add("lr" to "libretro")
                if (isConfigured("ra")) l.add("ra" to "RetroAchievements")
                if (igdbReady()) l.add("igdb" to "IGDB")
            }
            PlatKind.LOGO -> if (igdbReady()) l.add("igdb" to "IGDB")
            PlatKind.BACKDROP -> l.add("own" to "Your games")
        }
        // (LaunchBox was a source here too; taken out at the user's request. PlatformArt.launchBox is kept, unused.)
        return l
    }

    /** Every option from the sources (or just [src]) for a system's [k]; [first] stops at the first source that has any. */
    suspend fun searchPlatformArt(id: String, k: PlatKind, src: String?, first: Boolean = false): List<ArtOption> {
        val p = com.eclipse.launcher.data.Platforms.byId(id) ?: return emptyList()
        val name = com.eclipse.launcher.data.Platforms.originalName(id) ?: p.name // (not a name you gave it)
        val out = ArrayList<ArtOption>()
        for ((pid, _) in platProviders(k)) {
            if (src != null && src != pid) continue
            val list = runCatching {
                when (pid) {
                    "lr" -> com.eclipse.launcher.data.PlatformArt.libretro(id)
                    "ra" -> com.eclipse.launcher.data.PlatformArt.retroAch(key("ra_user"), key("ra_key"), p.raConsoleId)
                    "igdb" -> Scrapers.igdbPlatformLogos(key("igdb_id"), key("igdb_secret"), name)
                    "lb" -> com.eclipse.launcher.data.PlatformArt.launchBox(LaunchBox.platformNames(id, name), k)
                    "packs" -> iconPacks.mapNotNull { pk -> pk.images[id]?.let { ArtOption(Uri.fromFile(it).toString(), pk.name) } }
                    "own" -> games.asSequence().filter { it.platform == id }
                        .mapNotNull { g -> artOf(g, ArtKind.HERO) ?: artOf(g, ArtKind.BACKDROP) }.distinct().take(30)
                        .map { ArtOption(it, "Your games") }.toList()
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            out.addAll(list)
            if (first && out.isNotEmpty()) break
        }
        return out
    }

    /** A picture chosen in the picker: shows straight away, then swaps to a downloaded copy for offline use. */
    fun pickPlatformArt(id: String, k: PlatKind, url: String) {
        setPlatKind(id, k, url)
        if (!url.startsWith("http")) return
        viewModelScope.launch {
            val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "img"
            val f = File(artDir, "sc_plat_${id}_${k.key}_${System.currentTimeMillis()}.$ext")
            if (Http.download(url, f) && platArtOf(id, k) == url) { webpIfOn(f); if (platArtOf(id, k) == url) setPlatKind(id, k, Uri.fromFile(f).toString()) }
        }
    }

    fun importPlatformKind(id: String, k: PlatKind, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(artDir, "up_plat_${id}_${k.key}_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                webpIfOn(f)
                withContext(Dispatchers.Main) { setPlatKind(id, k, Uri.fromFile(f).toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't import that image" }
            }
        }
    }

    /** Back to nothing for this kind. */
    fun clearPlatformArt(id: String, k: PlatKind) = setPlatKind(id, k, null)

    fun openPlatform(id: String?) {
        if (id != null) lastPlatform = id
        // All games is every system at once
        platformSel = id?.takeIf { it != com.eclipse.launcher.data.Platforms.ALL }
        go(Screen.PLATFORMS)
    }

    // ---- artwork ----
    // Files in filesDir/art: "sc_*" = scraped (ours to replace/delete), anything else = uploaded by you (never touched).
    private val artDir: File get() = File(ctx.filesDir, "art").apply { mkdirs() }
    // only Eclipse's own art folder: a picture elsewhere (a PICO-8 cart used as its box art) is never deleted
    private fun isScrapedFile(u: String) = u.startsWith("file:") &&
        runCatching { File(Uri.parse(u).path ?: "").let { it.name.startsWith("sc_") && it.parentFile?.absolutePath == artDir.absolutePath } }.getOrDefault(false)
    private fun isUserFile(u: String) = u.startsWith("file:") && !u.contains("/sc_")

    fun artOf(g: Game, k: ArtKind): String? = art["${g.id}|${k.key}"] ?: romOf(g)?.let { r ->
        when (k) {
            ArtKind.BOX -> r.cover
            ArtKind.BACKDROP -> r.screenshot
            else -> null
        }
    }

    fun setArt(g: Game, k: ArtKind, url: String?) {
        val key = "${g.id}|${k.key}"
        val old = art[key]
        art = if (url == null) art - key else art + (key to url)
        persistArt()
        if (old != null && old != url && isScrapedFile(old)) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { Uri.parse(old).path?.let { File(it).delete() } } }
        }
    }

    /** Downloads [url] into the app's art folder and returns a file:// uri, or null on failure. */
    private suspend fun downloadArt(g: Game, k: ArtKind, url: String): String? {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "img"
        val f = File(artDir, "sc_${g.id}_${k.key}_${System.currentTimeMillis()}.$ext")
        return if (Http.download(url, f)) { webpIfOn(f); Uri.fromFile(f).toString() } else null
    }

    /** Scrape settings → Save artwork as WebP (off by default): a new picture in the art folder re-saved losslessly. */
    private suspend fun webpIfOn(f: File) {
        if (!flag("webp_new", false) || !com.eclipse.launcher.data.WebpShrink.supported) return
        withContext(Dispatchers.IO) {
            if (com.eclipse.launcher.data.WebpShrink.isPng8(f)) runCatching { com.eclipse.launcher.data.WebpShrink.convert(f) }
        }
    }

    /** Artwork chosen in the picker: shows straight away, then swaps to a downloaded copy for offline use. */
    fun pickArt(g: Game, k: ArtKind, url: String) {
        setArt(g, k, url)
        viewModelScope.launch {
            val local = downloadArt(g, k, url)
            if (local != null && artOf(g, k) == url) setArt(g, k, local)
        }
    }

    fun importArt(g: Game, k: ArtKind, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(artDir, "up_${g.id}_${k.key}_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                webpIfOn(f)
                withContext(Dispatchers.Main) { setArt(g, k, Uri.fromFile(f).toString()) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "Couldn't import that image" }
            }
        }
    }

    /** Providers that are both implemented and configured. */
    fun artProviders(): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        if (flag("p_lb") && LaunchBox.loaded) l.add("lb" to "LaunchBox") // local + offline: checked first
        l.add("has" to "Hasheous") // always on: it identifies the game for everything else
        if (ssReady()) l.add("ss" to "ScreenScraper") // by the ROM's checksum: exact matches, scans of the real box
        // free, no account: GameTDB (Nintendo discs and cartridges by ID), ArcadeDB (arcade), libretro (everything else)
        if (flag("p_gtdb")) l.add("gtdb" to "GameTDB")
        if (flag("p_adb")) l.add("adb" to "ArcadeDB")
        if (flag("p_lr")) l.add("lr" to "libretro")
        if (flag("p_sgdb") && key("sgdb").isNotBlank()) l.add("sgdb" to "SteamGridDB")
        if (igdbReady()) l.add("igdb" to "IGDB")
        return l
    }

    private fun igdbReady(): Boolean = flag("p_igdb") && key("igdb_id").isNotBlank() && key("igdb_secret").isNotBlank()

    // ---- ScreenScraper (data/ScreenScraper.kt): this build's developer login + your own account ----
    /** This build can use ScreenScraper at all (its developer login was built in). */
    val ssBuilt: Boolean get() = com.eclipse.launcher.data.ScreenScraper.built
    /** Signed in and switched on: ScreenScraper is used for artwork, descriptions, details and manuals. */
    fun ssReady(): Boolean = ssBuilt && flag("p_ss") && key("ss_user").isNotBlank() && key("ss_pass").isNotBlank()
    private suspend fun ssEntry(g: Game): com.eclipse.launcher.data.ScreenScraper.Entry? =
        if (!ssReady() || g.platform == "android") null
        else com.eclipse.launcher.data.ScreenScraper.lookup(key("ss_user"), key("ss_pass"), g.platform, g.path, queryGame(g).name)
    /** The Companion's Manual tab: the address of [g]'s manual on ScreenScraper, or null. */
    suspend fun ssManual(g: Game): String? = ssEntry(g)?.let { com.eclipse.launcher.data.ScreenScraper.manual(it) }

    // ---- connection status (the coloured dots in Settings → Connections) ----
    /** service -> (works?, what to say). A service that isn't in here hasn't been checked yet. */
    var connCheck by mutableStateOf<Map<String, Pair<Boolean, String>>>(emptyMap())

    fun isConfigured(id: String): Boolean = when (id) {
        "sgdb" -> key("sgdb").isNotBlank()
        "igdb" -> key("igdb_id").isNotBlank() && key("igdb_secret").isNotBlank()
        "ra" -> key("ra_user").isNotBlank() && key("ra_key").isNotBlank()
        "romm" -> rommConn() != null
        "ss" -> ssBuilt && key("ss_user").isNotBlank() && key("ss_pass").isNotBlank()
        "ai" -> aiReady()
        else -> false
    }

    /** Signs in to one service in the background and records whether it worked. */
    fun checkConnection(id: String) {
        if (!isConfigured(id)) { connCheck = connCheck - id; return }
        connCheck = connCheck - id // "checking…"
        viewModelScope.launch {
            val r: Pair<Boolean, String> = when (id) {
                "sgdb" -> if (Scrapers.sgdbCheck(key("sgdb"))) true to "Connected" else false to "Key not accepted, or no connection"
                "igdb" -> if (Scrapers.igdbCheck(key("igdb_id"), key("igdb_secret"))) true to "Connected" else false to "Client ID or secret not accepted, or no connection"
                "ra" -> if (RetroAch.check(key("ra_user"), key("ra_key"))) true to "Connected as ${key("ra_user")}" else false to "Username or key not accepted, or no connection"
                "romm" -> rommCheckSecure()
                "ss" -> com.eclipse.launcher.data.ScreenScraper.account(key("ss_user"), key("ss_pass"))?.let { a ->
                    true to ("Signed in as ${a.name}" + if (a.maxPerDay > 0) " \u00b7 ${a.today} of ${a.maxPerDay} lookups today" else "")
                } ?: (false to "Username or password not accepted, or no connection")
                "ai" -> com.eclipse.launcher.data.AiHelper.test(aiProvider, aiKey(), aiModel())?.let { false to it }
                    ?: (true to "Working \u00b7 " + aiName())
                else -> return@launch
            }
            connCheck = connCheck + (id to r)
        }
    }

    // ---- the Companion's Ask tab: Claude or Gemini with your own key (data/AiHelper.kt; Settings → Connections → AI helper) ----
    /** "claude" or "gemini": the one chosen, else the one that has a key. */
    val aiProvider: String get() = key("ai_provider").ifBlank {
        if (key("ai_claude").isBlank() && key("ai_gemini").isNotBlank()) com.eclipse.launcher.data.AiHelper.GEMINI else com.eclipse.launcher.data.AiHelper.CLAUDE
    }
    fun aiName(): String = if (aiProvider == com.eclipse.launcher.data.AiHelper.GEMINI) "Gemini" else "Claude"
    fun aiKey(): String = key(if (aiProvider == com.eclipse.launcher.data.AiHelper.GEMINI) "ai_gemini" else "ai_claude")
    /** A model of your own choosing (blank = Eclipse's default). */
    fun aiModel(): String = key(if (aiProvider == com.eclipse.launcher.data.AiHelper.GEMINI) "ai_gemini_model" else "ai_claude_model")
    fun aiReady(): Boolean = aiKey().isNotBlank()
    fun chooseAi(p: String) { setKey("ai_provider", p); checkConnection("ai") }

    /** RomM on http:// sends your password unencrypted (Basic auth): the server settings warn about it. */
    fun rommInsecure(): Boolean = rommConn()?.base?.startsWith("http://") == true

    /**
     * Tests the RomM connection. On an http:// address, the same server is tried over https:// first: when it
     * answers there, the address is switched to https so the password is encrypted on the way.
     */
    private suspend fun rommCheckSecure(): Pair<Boolean, String> {
        val c = rommConn() ?: return false to "Not set up"
        if (c.base.startsWith("http://")) {
            val secure = RomM.Conn("https://" + c.base.removePrefix("http://"), key("romm_user"), key("romm_pass"))
            val (ok, _) = runCatching { RomM.test(secure) }.getOrDefault(false to "")
            if (ok) {
                setKey("romm_url", secure.base)
                return true to "Connected over https (switched from http, so your password is encrypted)"
            }
        }
        val (ok, msg) = RomM.test(c)
        return if (ok && c.base.startsWith("http://")) true to "$msg \u2014 over http, so your password isn't encrypted. Turn on https for your RomM server to protect it."
        else ok to msg
    }

    fun checkConnections() = listOf("sgdb", "igdb", "ra", "romm", "ss", "ai").forEach { if (isConfigured(it) && it !in connCheck) checkConnection(it) }

    // ---- Hasheous: identify games from the ROM file itself ----
    /** The Hasheous match for this game (hashing the file the first time), or null. Cached per game. */
    suspend fun hasheousFor(g: Game): Hasheous.Match? {
        if (g.platform == "android") return null
        hashInfo[g.id]?.let { return Hasheous.decode(it) }
        val (answered, m) = try { Hasheous.identify(g.path) } catch (e: Exception) { false to null }
        if (answered) {
            hashInfo = hashInfo + (g.id to (m?.encode() ?: ""))
            persistHashes()
        }
        return m
    }

    /** The game as the scrapers should search for it: Hasheous' proper name when the file was recognised. */
    private suspend fun queryGame(g: Game): Game =
        matchNames[g.id]?.let { g.copy(name = it) } ?: hasheousFor(g)?.name?.let { g.copy(name = Scanner.cleanTitle(it)) } ?: g

    /** Titles you typed to match a game by hand (the game page's Match): the sources are asked for this name instead. */
    var matchNames by mutableStateOf(store.map("match_names"))
        private set

    /** Hasheous's identification, unless you matched the game by hand (then its IDs would point at the wrong game). */
    private suspend fun hashFor(g: Game): Hasheous.Match? = if (matchNames[g.id] != null) null else hasheousFor(g)

    /** Match [g] by hand to [title] and scrape it again from scratch. */
    fun manualMatch(g: Game, title: String) {
        val t = title.trim()
        matchNames = if (t.isEmpty()) matchNames - g.id else matchNames + (g.id to t)
        store.putMap("match_names", matchNames)
        trailerTried -= g.id
        trailers = trailers - g.id; store.putMap("trailers", trailers)
        rescrapeGame(g)
    }

    /**
     * Possible titles for [query] from every source that can search (Match by hand's list): this system's libretro
     * names, LaunchBox, IGDB and SteamGridDB. Same titles from several sources are shown once.
     */
    suspend fun matchCandidates(g: Game, query: String): List<MatchHit> = kotlinx.coroutines.coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val jobs = listOf(
            async(kotlinx.coroutines.Dispatchers.IO) { if (flag("p_lr")) runCatching { com.eclipse.launcher.data.Libretro.search(g.platform, q, ctx.cacheDir) }.getOrDefault(emptyList()) else emptyList() },
            async(kotlinx.coroutines.Dispatchers.IO) { if (LaunchBox.loaded) runCatching { LaunchBox.search(q) }.getOrDefault(emptyList()) else emptyList() },
            async(kotlinx.coroutines.Dispatchers.IO) { if (key("igdb_id").isNotBlank() && key("igdb_secret").isNotBlank()) runCatching { Scrapers.igdbSearch(key("igdb_id"), key("igdb_secret"), q) }.getOrDefault(emptyList()) else emptyList() },
            async(kotlinx.coroutines.Dispatchers.IO) { if (key("sgdb").isNotBlank()) runCatching { Scrapers.sgdbSearch(key("sgdb"), q) }.getOrDefault(emptyList()) else emptyList() }
        )
        val all = jobs.flatMap { it.await() }
        val fq = LaunchBox.fold(q)
        all.groupBy { LaunchBox.fold(it.name) }.values
            .map { same -> same.first().copy(source = same.map { it.source }.distinct().joinToString(" · ")) }
            .sortedBy { h -> val f = LaunchBox.fold(h.name); if (f == fq) 0 else if (f.startsWith(fq)) 1 else if (f.contains(fq)) 2 else 3 }
            .take(30)
    }

    private suspend fun fromProvider(id: String, g: Game, k: ArtKind, first: Boolean = false): List<ArtOption> = when (id) {
        "gtdb" -> if (com.eclipse.launcher.data.GameTdb.covers(g.platform)) com.eclipse.launcher.data.GameTdb.art(g, k, first) else emptyList()
        "adb" -> if (com.eclipse.launcher.data.ArcadeDb.covers(g.platform)) com.eclipse.launcher.data.ArcadeDb.lookup(g)?.let { e ->
            e.history?.let { saveDesc(g, it) }
            com.eclipse.launcher.data.ArcadeDb.art(e, k)
        } ?: emptyList() else emptyList()
        "lr" -> if (com.eclipse.launcher.data.Libretro.covers(g.platform) && k != ArtKind.MEDIA)
            com.eclipse.launcher.data.Libretro.art(g, k, ctx.cacheDir, matchNames[g.id] ?: hashFor(g)?.name) else emptyList()
        "has" -> if (k == ArtKind.BOX) listOfNotNull(hashFor(g)?.logo?.let { ArtOption(it, "Hasheous") }) else emptyList()
        "lb" -> LaunchBox.lookup(queryGame(g))?.let { e ->
            e.overview?.let { saveDesc(g, it) }
            LaunchBox.art(e, k)
        } ?: emptyList()
        "sgdb" -> Scrapers.sgdb(key("sgdb"), queryGame(g), k)
        "ss" -> ssEntry(g)?.let { e ->
            e.synopsis?.let { saveDesc(g, it) }
            com.eclipse.launcher.data.ScreenScraper.art(e, k)
        } ?: emptyList()
        "igdb" -> {
            val r = Scrapers.igdb(key("igdb_id"), key("igdb_secret"), queryGame(g), k, hashFor(g)?.igdbId)
            r.summary?.let { saveDesc(g, it) }
            r.art
        }
        else -> emptyList()
    }

    /** Every option from every connected source (for the picker). */
    suspend fun searchArt(g: Game, k: ArtKind, src: String?): List<ArtOption> {
        val out = ArrayList<ArtOption>()
        for ((id, _) in artProviders()) {
            if (src != null && src != id) continue
            out.addAll(fromProvider(id, g, k))
        }
        return out
    }

    /** First match only, stopping at the first source that has one (for bulk scraping). */
    private suspend fun bestArt(g: Game, k: ArtKind): String? {
        for ((id, _) in artProviders()) {
            fromProvider(id, g, k, first = true).firstOrNull()?.let { return it.url }
        }
        return null
    }

    // ---- description / achievements ----
    private fun saveDesc(g: Game, text: String) {
        if (desc[g.id] != null) return
        desc = desc + (g.id to text)
        persistDesc()
    }

    suspend fun ensureDesc(g: Game): String? {
        desc[g.id]?.let { return it }
        if (flag("p_lb") && LaunchBox.loaded) LaunchBox.lookup(queryGame(g))?.overview?.let { saveDesc(g, it) }
        desc[g.id]?.let { return it }
        ssEntry(g)?.synopsis?.let { saveDesc(g, it) }
        desc[g.id]?.let { return it }
        // arcade games: ArcadeDB's history of the game
        if (flag("p_adb") && com.eclipse.launcher.data.ArcadeDb.covers(g.platform)) com.eclipse.launcher.data.ArcadeDb.lookup(g)?.history?.let { saveDesc(g, it) }
        desc[g.id]?.let { return it }
        // IGDB results are cached per title, so this is free if artwork was just scraped
        if (igdbReady()) Scrapers.igdb(key("igdb_id"), key("igdb_secret"), queryGame(g), ArtKind.BOX, hashFor(g)?.igdbId).summary?.let { saveDesc(g, it) }
        return desc[g.id]
    }

    // ---- scraping: box art, backdrop, logo, description ----
    /**
     * What scraping fetches (Settings → Library): any of box, bg, logo, media, box3d, hero, desc, video.
     * Starts with box art, backdrop, logo and description.
     */
    var scrapeKinds by mutableStateOf(store.str("scrape_kinds").ifBlank { "box,poster,bg,logo,desc" }.split(',').filter { it.isNotBlank() }.toSet()
        // posters arrived after some libraries had chosen what to scrape: switched on once for them too
        .let { set -> if (store.str("poster_added").isEmpty()) { store.putStr("poster_added", "1"); if (store.str("scrape_kinds").isNotBlank()) (set + "poster").also { store.putStr("scrape_kinds", it.joinToString(",")) } else set } else set })
        private set

    fun chooseScrapeKind(k: String, on: Boolean) {
        scrapeKinds = if (on) scrapeKinds + k else scrapeKinds - k
        store.putStr("scrape_kinds", scrapeKinds.joinToString(","))
    }

    // (disc / cartridge, 3D box and hero pictures don't decide whether a game "needs" scraping: most sources don't
    // have them, so every game would always need them)
    // (a poster counts while a poster source is on and the game hasn't been looked up for one this session)
    private fun needsScrape(g: Game): Boolean =
        ArtKind.values().filter { it.key in scrapeKinds && it != ArtKind.MEDIA && it != ArtKind.BOX3D && it != ArtKind.HERO && it != ArtKind.POSTER }
            .any { artOf(g, it).let { u -> u == null || u.startsWith("http") } } ||
            ("poster" in scrapeKinds && posterSourceOn() && g.id !in posterTried && artOf(g, ArtKind.POSTER).let { u -> u == null || u.startsWith("http") }) ||
            ("desc" in scrapeKinds && desc[g.id] == null) || ("desc" in scrapeKinds && gameInfo[g.id] == null && infoSourcesReady()) || ("video" in scrapeKinds && trailers[g.id] == null && g.id !in trailerTried)

    /** Games already looked up for a poster this session (most sources have none for many games: don't ask every time). */
    private val posterTried = HashSet<String>()
    /** Any source that has posters: SteamGridDB, IGDB, or ArcadeDB (arcade flyers). */
    private fun posterSourceOn(): Boolean = (flag("p_sgdb") && key("sgdb").isNotBlank()) || igdbReady() || flag("p_adb")

    // ---- trailers (YouTube links from LaunchBox and IGDB) ----
    var trailers by mutableStateOf(store.map("trailers"))
        private set
    private val trailerTried = HashSet<String>()

    private suspend fun fetchTrailer(g: Game) {
        if (trailers[g.id] != null || g.id in trailerTried) return
        trailerTried += g.id
        val url = (if (flag("p_lb") && LaunchBox.loaded) LaunchBox.lookup(queryGame(g))?.video else null)
            ?: (if (igdbReady()) Scrapers.igdbTrailer(key("igdb_id"), key("igdb_secret"), queryGame(g), hashFor(g)?.igdbId) else null)
            ?: return
        trailers = trailers + (g.id to url)
        store.putMap("trailers", trailers)
    }

    private suspend fun scrapeGame(g: Game, force: Boolean) {
        if ("video" in scrapeKinds) runCatching { fetchTrailer(g) }
        for (k in ArtKind.values()) {
            if (k.key !in scrapeKinds) continue
            val cur = artOf(g, k)
            if (cur != null && isUserFile(cur)) continue // never overwrite artwork you uploaded
            // (a drawn disc / cartridge / 3D box: a source's real one replaces it when there is one, else it stays)
            if (cur != null && !force && !com.eclipse.launcher.data.ArtMaker.isGenerated(cur)) {
                // already has art: just make older web links available offline
                if (cur.startsWith("http")) downloadArt(g, k, cur)?.let { setArt(g, k, it) }
                continue
            }
            if (k == ArtKind.POSTER) posterTried += g.id
            val best = bestArt(g, k) ?: continue
            setArt(g, k, downloadArt(g, k, best) ?: best)
        }
        if (flag("fill_art", false)) runCatching { fillMissingArt(g) }
        if (force) { gameInfo = gameInfo - g.id; store.putMap("game_info", gameInfo) }
        runCatching { ensureInfo(g) }
        if ("desc" !in scrapeKinds) return
        if (force && igdbReady()) {
            desc = desc - g.id
            persistDesc()
        }
        ensureDesc(g)
    }

    fun scrapeAll(force: Boolean) = scrapeGames(games, force)

    // ---- missing disc / cartridge / 3D box art, drawn from the box art (data/ArtMaker.kt) ----
    /** Shell colour per system (from your real carts), worked out once per run. */
    private val shellColors = HashMap<String, Int>()

    private fun shellColorFor(platform: String): Int = shellColors.getOrPut(platform) {
        val files = games.asSequence().filter { it.platform == platform }
            .mapNotNull { g -> artOf(g, ArtKind.MEDIA)?.takeIf { it.startsWith("file:") && !com.eclipse.launcher.data.ArtMaker.isGenerated(it) } }
            .mapNotNull { Uri.parse(it).path?.let { p -> File(p) } }.filter { it.isFile }.take(16).toList()
        com.eclipse.launcher.data.ArtMaker.shellColor(files)
    }

    /** What a game is missing that can be drawn: its disc / cartridge (systems with a template) and its 3D box. */
    private fun fillableKinds(g: Game): List<ArtKind> {
        if (g.platform == "android") return emptyList()
        val out = ArrayList<ArtKind>()
        if (artOf(g, ArtKind.MEDIA) == null && com.eclipse.launcher.data.ArtMaker.mediaShape(g.platform) != null) out += ArtKind.MEDIA
        if (artOf(g, ArtKind.BOX3D) == null) out += ArtKind.BOX3D
        return out
    }

    private fun canFill(g: Game): Boolean = fillableKinds(g).isNotEmpty() && (artOf(g, ArtKind.BOX) ?: artOf(g, ArtKind.POSTER)) != null

    /** The box art as a bitmap (a downloaded copy, or fetched once into the cache for a web link). */
    private suspend fun coverBitmap(g: Game): android.graphics.Bitmap? {
        val u = artOf(g, ArtKind.BOX) ?: artOf(g, ArtKind.POSTER) ?: return null
        val f = when {
            u.startsWith("file:") -> Uri.parse(u).path?.let { File(it) }
            u.startsWith("http") -> File(ctx.cacheDir, "fill_cover.img").takeIf { Http.download(u, it) }
            else -> null
        } ?: return null
        return withContext(Dispatchers.IO) { com.eclipse.launcher.data.ArtMaker.decode(f) }
    }

    /** Draws whatever [g] is missing (disc / cartridge, 3D box) from its box art. Returns how many pictures were made. */
    suspend fun fillMissingArt(g: Game): Int {
        val kinds = fillableKinds(g)
        if (kinds.isEmpty()) return 0
        val cover = coverBitmap(g) ?: return 0
        val shape = com.eclipse.launcher.data.ArtMaker.mediaShape(g.platform)
        val shell = if (shape == com.eclipse.launcher.data.ArtMaker.Shape.N64) withContext(Dispatchers.IO) { shellColorFor(g.platform) } else com.eclipse.launcher.data.ArtMaker.GREY
        val sysName = com.eclipse.launcher.data.Platforms.byId(g.platform)?.name ?: g.platform.uppercase()
        var made = 0
        for (k in kinds) {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = when (k) {
                        ArtKind.MEDIA -> com.eclipse.launcher.data.ArtMaker.cart(cover, shape ?: return@runCatching null, g.platform, shell)
                        else -> com.eclipse.launcher.data.ArtMaker.box3d(cover, g.platform, sysName, g.name)
                    }
                    val f = File(artDir, "sc_gen_${g.id.replace(Regex("[^A-Za-z0-9._-]"), "_")}_${k.key}_${System.currentTimeMillis()}.png")
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    webpIfOn(f)
                    f
                }.getOrNull()
            } ?: continue
            if (artOf(g, k) == null) { setArt(g, k, Uri.fromFile(file).toString()); made++ } else file.delete()
        }
        return made
    }

    /** Settings → Tools → Fill missing art: every game that can get one, one at a time, in the background. */
    data class FillJob(val index: Int, val total: Int, val made: Int, val name: String)
    var fillJob by mutableStateOf<FillJob?>(null)
        private set
    @Volatile private var fillCancel = false
    /** How many games could get a drawn disc / cartridge or 3D box right now. */
    fun fillCandidates(): Int = games.count { canFill(it) }

    fun startFillArt() {
        if (fillJob != null) return
        val list = games.filter { canFill(it) }
        if (list.isEmpty()) { message = "Every game that has box art already has its disc / cartridge and 3D box"; return }
        fillCancel = false
        shellColors.clear()
        fillJob = FillJob(0, list.size, 0, "")
        viewModelScope.launch {
            var made = 0
            for ((i, g) in list.withIndex()) {
                if (fillCancel) break
                fillJob = FillJob(i, list.size, made, g.name)
                made += runCatching { fillMissingArt(g) }.getOrDefault(0)
            }
            fillJob = null
            flush()
            message = if (made == 0) "Nothing could be drawn (no box art to work from)" else "Drew $made picture${if (made == 1) "" else "s"}"
        }
    }

    fun cancelFillArt() { fillCancel = true }

    fun scrapeGames(pool: List<Game>, force: Boolean) {
        if (artProviders().isEmpty()) { message = "Turn on an artwork source first (Settings → Connections)"; return }
        if (scrapeJob?.isActive == true) return
        val list = pool.filter { force || needsScrape(it) }
        if (list.isEmpty()) { message = "Every game already has artwork and a description"; return }
        scrapeJob = viewModelScope.launch {
            var n = 0
            for (g in list) {
                if (!isActive) break
                scrape = ScrapeState(n, list.size, g.name)
                try { scrapeGame(g, force) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                n++
                delay(150)
            }
            scrape = null
            flush()
            message = "Scraped $n games"
        }
    }


    // ---- LaunchBox Games Database (downloaded once and kept, then used offline) ----
    /**
     * Update (or first download): asks LaunchBox whether its file changed, downloads it only if so, then matches
     * your games again. [quiet]: the monthly check, which says nothing unless a newer file came down.
     */
    fun updateLaunchBox(quiet: Boolean = false) {
        if (lbJob?.isActive == true) return
        if (games.isEmpty()) { if (!quiet) message = "Add games first, then download the LaunchBox data"; return }
        lbStatus = "Starting\u2026"
        lbProgress = 0f
        lbJob = viewModelScope.launch {
            try {
                val r = LaunchBox.update(ctx, games) { s, p -> lbStatus = s; lbProgress = p }
                afterLaunchBox()
                setFlag("p_lb", true)
                if (!quiet || r.downloaded) message =
                    if (r.downloaded) "LaunchBox: matched ${r.matched} of your games. Run \"Scrape missing\" to fill in artwork."
                    else "LaunchBox data is already up to date (${r.matched} of your games matched)"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) message = "LaunchBox update failed: ${e.message}"
            } finally {
                lbStatus = null
            }
        }
    }

    /** After the index changes: proper titles for MAME-named arcade ROMs, and look details up again where none were found. */
    private fun afterLaunchBox() {
        val renamed = games.map { g -> LaunchBox.titleFor(g)?.takeIf { it != g.name }?.let { g.copy(name = it) } ?: g }
        if (renamed != games) { games = renamed; persistGames() }
        gameInfo = gameInfo.filterValues { it != "{}" }; store.putMap("game_info", gameInfo)
    }

    /**
     * Games LaunchBox hasn't been searched for yet (just added): looks them up in the kept database file, with nothing
     * downloaded, then runs [then] (e.g. scraping them, so LaunchBox's art is there to find). Without a kept file
     * (data from an older version) it just runs [then].
     */
    private fun lbCatchUp(then: () -> Unit = {}) {
        if (!flag("p_lb") || !LaunchBox.loaded || !LaunchBox.hasZip(ctx) || lbJob?.isActive == true || LaunchBox.unlooked(games).isEmpty()) {
            then(); return
        }
        lbStatus = "Looking up your new games\u2026"
        lbProgress = 0f
        lbJob = viewModelScope.launch {
            var ok = true
            try {
                if (LaunchBox.catchUp(ctx, games) { s, p -> lbStatus = s; lbProgress = p } != null) afterLaunchBox()
            } catch (e: CancellationException) {
                ok = false; throw e
            } catch (e: Exception) {
            } finally {
                lbStatus = null
                if (ok) then()
            }
        }
    }

    /** Once a month, on Wi-Fi (or any unmetered network): is there a newer LaunchBox file? Downloads only if there is. */
    private fun lbMonthlyCheck() {
        if (!flag("p_lb") || !LaunchBox.loaded || games.isEmpty()) return
        if (System.currentTimeMillis() - LaunchBox.checkedAt < 30L * 24 * 3600 * 1000) return
        if (!onUnmetered()) return
        updateLaunchBox(quiet = true)
    }

    /** Size of the kept LaunchBox file (0 = none kept). */
    fun lbKeptBytes(): Long = if (LaunchBox.hasZip(ctx)) LaunchBox.zipSize(ctx) else 0L

    private fun onUnmetered(): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    init {
        // a minute after start (out of the way of loading): the monthly LaunchBox check
        viewModelScope.launch { delay(60_000); lbMonthlyCheck() }
    }

    fun cancelLaunchBox() {
        lbJob?.cancel()
        lbStatus = null
    }

    fun cancelScrape() {
        scrapeJob?.cancel()
        scrape = null
        flush()
    }

    // ---- a game's details, as you edit them on its page ----

    /** The games on screen right now, in order (set by the screen showing them): LB / RB on a game's page step through them. */
    var browseList: List<Game> = emptyList()
    var menuStepped = false

    fun menuStep(d: Int) {
        val g = menuGame ?: return
        val list = browseList.takeIf { l -> l.any { it.id == g.id } } ?: games.filter { it.platform == g.platform }.sortedBy { it.name.lowercase() }
        val i = list.indexOfFirst { it.id == g.id }
        if (i < 0 || list.size < 2) return
        val next = list[(i + d + list.size) % list.size]
        focused = next
        com.eclipse.launcher.ui.GamepadFocus.lastKey = "g:${next.id}"
        menuStepped = true
        menuGame = next
    }

    /** Opening the artwork screen at a kind (the game page's Box / Backdrop / Logo / Physical buttons). */
    var artKindStart by mutableStateOf(com.eclipse.launcher.data.ArtKind.BOX)

    private fun metaJson(id: String): org.json.JSONObject? = gameMeta[id]?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
    private fun metaTitle(id: String): String? = metaJson(id)?.optString("t")?.ifBlank { null }

    // ---- details the databases know (year, developer, publisher, genre, players, rating) ----
    /** game id -> JSON of what LaunchBox / IGDB said ("{}" = looked, nothing found). What you typed always wins. */
    var gameInfo by mutableStateOf(store.map("game_info"))
        private set

    private fun infoSourcesReady(): Boolean = (flag("p_lb") && LaunchBox.loaded) || igdbReady() || ssReady()

    /** Looks the game's details up (LaunchBox first, IGDB for what is still missing) and keeps them. */
    suspend fun ensureInfo(g: Game) {
        if (gameInfo[g.id] != null || !infoSourcesReady()) return
        var info: com.eclipse.launcher.data.GameInfo? = null
        if (flag("p_lb") && LaunchBox.loaded) info = LaunchBox.lookup(queryGame(g))?.info
        if (info == null || info.year == null || info.developer == null || info.genres == null) {
            val ss = ssEntry(g)?.info
            if (ss != null) info = (info ?: com.eclipse.launcher.data.GameInfo()).orElse(ss)
        }
        if ((info == null || info.year == null || info.developer == null || info.genres == null) && igdbReady()) {
            val ig = Scrapers.igdbInfo(key("igdb_id"), key("igdb_secret"), queryGame(g).name, hashFor(g)?.igdbId)
            info = (info ?: com.eclipse.launcher.data.GameInfo()).orElse(ig)
        }
        gameInfo = gameInfo + (g.id to (info?.toJson() ?: org.json.JSONObject()).toString())
        store.putMap("game_info", gameInfo)
    }

    /** One of a game's details: what you typed, else what the sources know ("" when nobody knows). */
    fun gameDetail(g: Game, field: String): String {
        metaJson(g.id)?.optString(field)?.ifBlank { null }?.let { return it }
        gameInfo[g.id]?.let { j -> runCatching { org.json.JSONObject(j).optString(field) }.getOrNull()?.ifBlank { null } }?.let { return it }
        val rom = romFor(g)
        return when (field) {
            "t" -> g.name
            "y" -> rom?.year?.toString() ?: ""
            "g" -> rom?.genres?.take(3)?.joinToString(" \u00b7 ") ?: ""
            else -> ""
        }
    }

    /**
     * Saves what you typed on a game's page: a new title renames it everywhere, the description replaces the old one,
     * and the rest is kept with the game. [toRomm]: also send the title and description to your RomM server.
     */
    fun saveGameDetails(g: Game, title: String, year: String, genre: String, developer: String, players: String, description: String, toRomm: Boolean, publisher: String = "", rating: String = "") {
        val o = org.json.JSONObject().put("t", title.trim()).put("y", year.trim()).put("g", genre.trim()).put("d", developer.trim()).put("p", players.trim())
            .put("pub", publisher.trim()).put("r", rating.trim())
        gameMeta = gameMeta + (g.id to o.toString())
        store.putMap("game_meta", gameMeta)
        val newName = title.trim().ifBlank { g.name }
        if (newName != g.name) {
            games = games.map { if (it.id == g.id) it.copy(name = newName) else it }
            persistGames()
            if (menuGame?.id == g.id) menuGame = menuGame?.copy(name = newName)
            if (focused?.id == g.id) focused = focused?.copy(name = newName)
        }
        if (description.isNotBlank() && description != descOf(g)) { desc = desc + (g.id to description.trim()); persistDesc() }
        message = "Saved ${newName}"
        val rid = rommIdFor(g)?.toIntOrNull() ?: g.id.removePrefix("rm:").toIntOrNull()?.takeIf { g.id.startsWith("rm:") }
        if (toRomm && rid != null) {
            val c = rommConn() ?: run { message = "Saved here; RomM isn't connected"; return }
            viewModelScope.launch {
                val ok = com.eclipse.launcher.data.RomM.updateRom(c, rid, newName, description.trim().ifBlank { descOf(g) ?: "" })
                message = if (ok) "Saved ${newName} here and on RomM" else "Saved here, but RomM didn't accept the change"
            }
        }
    }

    /** The game page's Rescrape: your edits stay, but the artwork and description are fetched again. */
    fun rescrapeGame(g: Game) {
        desc = desc - g.id
        persistDesc()
        scrapeOne(g)
    }

    fun scrapeOne(g: Game) {
        if (artProviders().isEmpty()) { message = "Turn on an artwork source first (Settings → Connections)"; return }
        message = "Scraping ${g.name}…"
        viewModelScope.launch {
            try { scrapeGame(g, true); message = "Updated ${g.name}" } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "Scrape failed for ${g.name}" }
        }
    }

    suspend fun loadRa(g: Game): RaProgress? {
        if (!flag("ra_on", true) || key("ra_user").isBlank() || key("ra_key").isBlank()) return null
        if (raCache.containsKey(g.id)) return raCache[g.id]
        val p = try { RetroAch.progress(key("ra_user"), key("ra_key"), g, hasheousFor(g)?.raId) } catch (e: Exception) { null }
        raCache[g.id] = p
        return p
    }
}
