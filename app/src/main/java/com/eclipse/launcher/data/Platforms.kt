package com.eclipse.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One extra put on an emulator's launch intent. kind: s = string, z = boolean, i = int, l = long, a = string array. */
data class Extra(val kind: Char, val key: String, val value: String)

/**
 * How to start one emulator for one system.
 *  - RetroArch core: [core] is set; any installed RetroArch build is used.
 *  - Standalone app: [pkg]/[activity] plus the intent [action], [data], [type], [categories], [extras], [flags].
 * Placeholders in data/extras: {path} = ROM file path, {uri} = content:// link, {fileuri} = file:// link,
 * {content} = the id written inside the file (a .steam / .localgameid / .gog / .epic shortcut; else its file name).
 * [onlyExts]: offered only for files with these extensions (e.g. GameNative's Steam entry for .steam files).
 * [variant]: tells apart several entries for one app (GameNative Steam / GOG / Epic).
 */
data class EmuSpec(
    val name: String,
    val core: String? = null,
    val pkg: String? = null,
    val activity: String? = null,
    val action: String? = null,
    val data: String? = null,
    val type: String? = null,
    val categories: List<String> = emptyList(),
    val extras: List<Extra> = emptyList(),
    val flags: List<String> = emptyList(),
    val m3uFirstDisc: Boolean = false,
    val onlyExts: Set<String> = emptySet(),
    val variant: String? = null
) {
    /** Stable id used to remember your choice. */
    val key: String get() = if (core != null) "ra:$core" else "pkg:$pkg" + (variant?.let { "#$it" } ?: "")
    /** Can this entry open a file with this extension? */
    fun fits(ext: String): Boolean = onlyExts.isEmpty() || ext.lowercase() in onlyExts
}

/** An emulator choice that is actually installed ([pkg] = the app that will run, e.g. which RetroArch build). */
/**
 * An emulator you can use. For a RetroArch core: [detected] = true when Eclipse has seen it in your RetroArch,
 * false when it hasn't (it may still be there), null when nothing could be checked; [corePath] = its file, if readable.
 */
data class EmuChoice(val spec: EmuSpec, val pkg: String, val detected: Boolean? = null, val corePath: String? = null) {
    val label: String get() = if (spec.core != null) "RetroArch · ${spec.name}" else spec.name
}

data class PlatformDef(
    val id: String,
    val name: String,
    val short: String,
    val aliases: Set<String>,
    val exts: Set<String>,
    val raConsoleId: Int?,
    val emus: List<EmuSpec>
)

/**
 * Systems and the emulators that can run them, loaded from assets/systems.json (100+ systems, 600+ emulator entries).
 * Call [init] once at app start.
 */
object Platforms {
    val retroArch = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32", "com.retroarch.plus")

    /** Extensions that may appear in any system's folder (archives, disc images, playlists). */
    private val generic = setOf("zip", "7z", "chd", "iso", "bin", "cue", "m3u", "img", "pbp", "cso", "rvz", "gdi", "cdi", "ccd")

    /**
     * File types that are also ordinary files on any phone (app data, pictures, scripts): they only count as games
     * inside a folder named after a system. Otherwise every .app / .png / .bat lying around (Android app data
     * included) would turn up as a 3DS, PICO-8 or DOS game.
     */
    private val needsFolder = setOf("app", "axf", "elf", "png", "bat", "conf", "cmd", "com", "exe", "dat", "lnk", "desktop", "gz", "rar", "wasm")

    private val android = PlatformDef("android", "Android", "AND", emptySet(), emptySet(), null, emptyList())

    /** Your favourite games, from every system, as a "system" of their own on the platforms screen. */
    const val FAVORITES = "eclipse-favorites"
    private val favorites = PlatformDef(FAVORITES, "Favourites", "FAV", emptySet(), emptySet(), null, emptyList())

    /** Every game you have, as a folder of its own at the front of the platforms. */
    const val ALL = "eclipse-all"
    private val allGames = PlatformDef(ALL, "All games", "ALL", emptySet(), emptySet(), null, emptyList())

    /** First release year of each system (for Order → Year on the platforms); hacks go with their system. */
    private val years = mapOf(
        "2600" to 1977, "32x" to 1994, "3do" to 1993, "3ds" to 2011, "5200" to 1982, "7800" to 1986, "a2" to 1977, "a2001" to 1982,
        "amiga" to 1985, "arcade" to 1978, "ard" to 2015, "ast" to 1985, "aw" to 2003, "bbcmicro" to 1981, "c64" to 1982, "cdi" to 1991,
        "chf" to 1976, "cpc" to 1984, "cps1" to 1988, "cps2" to 1993, "cps3" to 1996, "cv" to 1982, "dreamcast" to 1998, "dos" to 1981,
        "nds" to 2004, "duck" to 1993, "fbneo" to 1978, "fc" to 1983, "fds" to 1986, "gb" to 1989, "gba" to 2001, "gbc" to 1998,
        "gc" to 2001, "gen" to 1989, "genesis" to 1989, "gg" to 1990, "gw" to 1980, "intv" to 1979, "jag" to 1993, "jagcd" to 1995,
        "lynx" to 1989, "mame" to 1978, "mark3" to 1985, "mcd" to 1991, "md" to 1988, "mini" to 2001, "mo2" to 1978, "model3" to 1996,
        "msx" to 1983, "n64" to 1996, "naomi" to 1998, "naomi2" to 2000, "naomigd" to 2001, "neogeo" to 1990, "nes" to 1985,
        "ngcd" to 1994, "ngp" to 1998, "ngpc" to 1999, "palm" to 1996, "pc88" to 1981, "pc98" to 1982, "pccd" to 1988, "pce" to 1987,
        "pcfx" to 1994, "pet" to 1977, "pico" to 1993, "pico8" to 2015, "plus4" to 1984, "ps1" to 1994, "ps2" to 2000, "ps3" to 2006,
        "psp" to 2004, "psvita" to 2011, "pspminis" to 2008, "rpgmaker" to 1992, "saturn" to 1994, "satellaview" to 1995, "scd" to 1992, "scummvm" to 1987,
        "sfc" to 1990, "sg1k" to 1983, "sharpx68000" to 1987, "sms" to 1986, "snes" to 1991, "steam" to 2003, "switch" to 2017,
        "tg16" to 1989, "tgcd" to 1989, "tic80" to 2017, "triforce" to 2002, "uze" to 2008, "vb" to 1995, "vc4k" to 1978, "vect" to 1982,
        "vic20" to 1980, "wasm4" to 2021, "wii" to 2006, "wiiu" to 2012, "windows" to 1985, "ws" to 1999, "wsc" to 2000, "wsv" to 1992,
        "x1" to 1982, "xbox" to 2001, "xbox360" to 2005, "zx81" to 1981, "zxspectrum" to 1982, "android" to 2008
    )
    fun year(id: String): Int? = years[id] ?: years[id.removeSuffix("-hacks")]

    @Volatile
    private var base: List<PlatformDef> = listOf(allGames, favorites, android)

    /** Names you gave systems yourself (System editor → Name), by id. */
    @Volatile
    private var names: Map<String, String> = emptyMap()

    /** Sets your own names for systems ("" or a missing id = its usual name). */
    fun setNames(m: Map<String, String>) { names = m.filterValues { it.isNotBlank() } }

    /** A system's usual name (the one in systems.json), whatever you renamed it to. */
    fun originalName(id: String): String? = byIdMap[id]?.name

    private fun named(p: PlatformDef): PlatformDef = names[p.id]?.let { p.copy(name = it) } ?: p

    val all: List<PlatformDef> get() = base.let { b -> if (names.isEmpty()) b else b.map(::named) }

    private var byIdMap: Map<String, PlatformDef> = mapOf(android.id to android, favorites.id to favorites, allGames.id to allGames)
    private var aliasMap: Map<String, PlatformDef> = emptyMap()
    private var extOwner: Map<String, PlatformDef> = emptyMap()
    private var emuPkgs: Map<String, String> = emptyMap() // package -> display name

    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return // (all starts with Android and Eclipse Arcade, so its size can't tell)
        val text = try { ctx.assets.open("systems.json").bufferedReader().use { it.readText() } } catch (e: Exception) { return }
        val list = ArrayList<PlatformDef>()
        list.add(allGames)
        list.add(favorites)
        val arr = JSONObject(text).getJSONArray("systems")
        for (i in 0 until arr.length()) list.add(parseSystem(arr.getJSONObject(i)))
        list.add(android)

        byIdMap = list.associateBy { it.id }
        aliasMap = HashMap<String, PlatformDef>().apply {
            // a folder named after a system's id wins over the same word used as another system's alias
            list.forEach { p -> p.aliases.forEach { putIfAbsent(it, p) } }
            list.forEach { p -> put(norm(p.id), p) }
        }
        // An extension identifies a system on its own only when one main system claims it
        // (or one system is literally named after it, e.g. .gb -> Game Boy).
        val owners = HashMap<String, MutableList<PlatformDef>>()
        list.filter { !it.id.endsWith("-hacks") }.forEach { p -> p.exts.forEach { owners.getOrPut(it) { ArrayList() }.add(p) } }
        extOwner = owners.mapNotNull { (ext, ps) ->
            if (ext in generic || ext in needsFolder) return@mapNotNull null
            val p = if (ps.size == 1) ps[0] else ps.firstOrNull { it.id == ext || ext in it.aliases }
            p?.let { ext to it }
        }.toMap()
        emuPkgs = list.flatMap { it.emus }.filter { it.pkg != null }.associate { it.pkg!! to it.name } +
            retroArch.associateWith { "RetroArch" }
        base = list
        loaded = true
    }

    private fun parseSystem(o: JSONObject): PlatformDef {
        val emus = ArrayList<EmuSpec>()
        val ea = o.optJSONArray("emus") ?: JSONArray()
        for (i in 0 until ea.length()) {
            val e = ea.getJSONObject(i)
            emus.add(
                EmuSpec(
                    name = e.getString("n"),
                    core = e.optString("core").ifEmpty { null },
                    pkg = e.optString("pkg").ifEmpty { null },
                    activity = e.optString("act").ifEmpty { null },
                    action = e.optString("a").ifEmpty { null },
                    data = e.optString("d").ifEmpty { null },
                    type = e.optString("t").ifEmpty { null },
                    categories = strings(e.optJSONArray("c")),
                    extras = e.optJSONArray("x")?.let { xa ->
                        (0 until xa.length()).map { j ->
                            val x = xa.getJSONArray(j)
                            Extra(x.getString(0)[0], x.getString(1), x.getString(2))
                        }
                    } ?: emptyList(),
                    flags = strings(e.optJSONArray("f")),
                    m3uFirstDisc = e.optBoolean("m3u", false),
                    onlyExts = strings(e.optJSONArray("only")).map { it.lowercase() }.toSet(),
                    variant = e.optString("v").ifEmpty { null }
                )
            )
        }
        return PlatformDef(
            id = o.getString("id"),
            name = o.getString("name"),
            short = o.optString("short").ifEmpty { o.getString("id").uppercase() },
            aliases = strings(o.optJSONArray("folders")).toSet(),
            exts = strings(o.optJSONArray("exts")).toSet(),
            raConsoleId = if (o.isNull("ra")) null else o.optInt("ra"),
            emus = emus
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun byId(id: String): PlatformDef? = byIdMap[id]?.let(::named)

    /**
     * The system a folder name stands for. Never "Android": that's the system folder at the top of every phone's
     * storage, and Android games come from your installed apps, not from files.
     */
    fun matchFolder(name: String): PlatformDef? = aliasMap[norm(name)]?.takeIf { it.id != android.id }?.let(::named)

    /** [hint] = platform detected from the enclosing folder name, if any. */
    fun detect(hint: String?, ext: String): PlatformDef? {
        if (hint != null) {
            val p = byId(hint) ?: return null
            return if (ext in p.exts || ext in generic) p else null
        }
        return extOwner[ext]?.let(::named)
    }

    fun isEmulator(pkg: String): Boolean = pkg in emuPkgs

    /** Friendly name for an emulator app. */
    fun emuName(pkg: String): String? = emuPkgs[pkg]
}
