package com.eclipse.launcher.data

import android.content.Context
import org.json.JSONArray

/**
 * Guide and map pages known by title (assets/companion/*.json), so the Companion opens a game's own GameFAQs guide,
 * StrategyWiki walkthrough or VGMaps map instead of a search page.
 *
 * The three catalogues and the way titles are matched come from Game Twin by Mert Aslantürk
 * (https://github.com/alpha-minus/game-twin, GPL-3.0): titles become tokens (accents and region tags dropped,
 * "and" ignored, Roman numerals as numbers), a match needs 60% of the words in common and exactly the same sequel
 * numbers ("II" = "2", but "2" never matches "3" or the first game).
 */
object GuideCatalogs {
    /** One page: its title on the site, the system it's for (the site's own code), and its address. */
    data class Entry(val title: String, val system: String, val url: String) {
        internal val tokens: List<String> by lazy { tokens(title) }
    }

    private const val THRESHOLD = 0.6

    @Volatile private var gamefaqs: List<Entry>? = null
    @Volatile private var strategywiki: List<Entry>? = null
    @Volatile private var vgmaps: List<Entry>? = null

    private fun load(ctx: Context, file: String, make: (org.json.JSONObject) -> Entry?): List<Entry> = try {
        val a = JSONArray(ctx.assets.open("companion/$file").bufferedReader().use { it.readText() })
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(make) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun gf(ctx: Context) = gamefaqs ?: load(ctx, "gamefaqs_catalog.json") { o ->
        Entry(o.optString("t"), o.optString("p"), "https://gamefaqs.gamespot.com/" + o.optString("u").ifEmpty { return@load null })
    }.also { gamefaqs = it }

    private fun sw(ctx: Context) = strategywiki ?: load(ctx, "strategywiki_catalog.json") { o ->
        Entry(o.optString("t"), "", "https://strategywiki.org/wiki/" + o.optString("a").ifEmpty { return@load null })
    }.also { strategywiki = it }

    private fun vm(ctx: Context) = vgmaps ?: load(ctx, "vgmaps_catalog.json") { o ->
        val s = o.optString("s"); val a = o.optString("a")
        if (s.isEmpty() || a.isEmpty() || Regex("""^\s*\(.+\)\s*$""").matches(o.optString("t"))) null
        else Entry(o.optString("t"), s, "https://www.vgmaps.com/Atlas/$s/index.htm#$a")
    }.also { vgmaps = it }

    /** GameFAQs' system codes for Eclipse's systems (a guide for the same system wins a tie). */
    private val GF_SYSTEMS = mapOf(
        "nes" to setOf("NES"), "fc" to setOf("NES"), "fds" to setOf("FAMICOMDS"), "snes" to setOf("SNES"), "sfc" to setOf("SNES"),
        "gb" to setOf("GAMEBOY", "GB"), "gbc" to setOf("GBC"), "gba" to setOf("GBA"), "n64" to setOf("N64"), "nds" to setOf("DS"),
        "3ds" to setOf("3DS"), "gc" to setOf("GAMECUBE", "GC"), "wii" to setOf("WII"), "wiiu" to setOf("WIIU"), "switch" to setOf("NS", "SWITCH"),
        "ps1" to setOf("PS"), "ps2" to setOf("PS2"), "ps3" to setOf("PS3"), "psp" to setOf("PSP"), "psvita" to setOf("VITA"),
        "genesis" to setOf("GENESIS", "GEN"), "md" to setOf("GENESIS", "GEN"), "sms" to setOf("SMS"), "gg" to setOf("GG", "GAMEGEAR"),
        "saturn" to setOf("SATURN", "SAT"), "dreamcast" to setOf("DREAMCAST", "DC"), "xbox" to setOf("XBOX"), "xbox360" to setOf("X360", "XBOX360"),
        "arcade" to setOf("ARCADE"), "mame" to setOf("ARCADE"), "fbneo" to setOf("ARCADE"), "neogeo" to setOf("NEO", "ARCADE"),
        "tg16" to setOf("TG16"), "pce" to setOf("TG16"), "dos" to setOf("PC"), "windows" to setOf("PC"), "2600" to setOf("ATARI2600"), "c64" to setOf("C64")
    )

    /** VGMaps' atlas folders for Eclipse's systems. */
    val VGMAPS_SYSTEMS = mapOf(
        "nes" to "NES", "fc" to "NES", "fds" to "NES", "snes" to "SuperNES", "sfc" to "SuperNES", "gb" to "GB-GBC", "gbc" to "GB-GBC", "gba" to "GBA",
        "n64" to "N64", "nds" to "DS", "3ds" to "3DS", "genesis" to "Genesis", "md" to "Genesis", "sms" to "MasterSystem", "gg" to "GameGear",
        "ps1" to "PSX", "ps2" to "PS2", "psp" to "PSP", "tg16" to "TG16", "pce" to "TG16", "arcade" to "Arcade", "mame" to "Arcade", "fbneo" to "Arcade",
        "neogeo" to "Neo-Geo", "gc" to "GCN", "wii" to "Wii", "wiiu" to "WiiU", "switch" to "Switch", "saturn" to "Saturn", "dreamcast" to "DC",
        "msx" to "MSX", "c64" to "C64", "dos" to "PC", "windows" to "PC", "2600" to "Atari2600", "cv" to "ColecoVision", "amiga" to "Amiga",
        "ws" to "WS-WSC", "wsc" to "WS-WSC", "ngp" to "NGP-NGPC", "ngpc" to "NGP-NGPC", "zxspectrum" to "ZXSpectrum", "cpc" to "CPC"
    )

    /** The game's own GameFAQs guide, or null when the catalogue doesn't have it. */
    fun gameFaqs(ctx: Context, title: String, platform: String): Entry? = rank(gf(ctx), title, GF_SYSTEMS[platform]).firstOrNull()

    /** The game's StrategyWiki walkthrough, or null. */
    fun strategyWiki(ctx: Context, title: String): Entry? = rank(sw(ctx), title, null).firstOrNull()

    /** VGMaps pages for the game (up to 4, its own system first). */
    fun vgmaps(ctx: Context, title: String, platform: String): List<Entry> =
        rank(vm(ctx), title, VGMAPS_SYSTEMS[platform]?.let { setOf(it) }).take(4)

    private fun rank(entries: List<Entry>, title: String, systems: Set<String>?): List<Entry> {
        val input = tokens(title)
        if (input.isEmpty()) return emptyList()
        val inSet = input.toSet()
        val nums = numbers(input)
        val norm = input.joinToString(" ")
        data class Scored(val e: Entry, val score: Double, val exact: Boolean, val sameSystem: Boolean, val prefix: Boolean)
        return entries.mapNotNull { e ->
            val t = e.tokens
            if (numbers(t) != nums) return@mapNotNull null
            val ts = t.toSet()
            val union = inSet.union(ts).size
            if (union == 0) return@mapNotNull null
            val score = inSet.intersect(ts).size.toDouble() / union
            if (score < THRESHOLD) return@mapNotNull null
            val nt = t.joinToString(" ")
            Scored(e, score, nt == norm, systems != null && e.system in systems, nt.startsWith(norm))
        }.sortedWith(
            compareByDescending<Scored> { it.exact }.thenByDescending { it.score }.thenByDescending { it.sameSystem }
                .thenByDescending { it.prefix }.thenBy { it.e.title.length }
        ).map { it.e }
    }

    private val PARENS = Regex("""[\(\[][^)\]]*[)\]]""")
    private val ROMAN = Regex("^[ivx]+$")

    /** "The Legend of Zelda II (USA)" → [the, legend, of, zelda, 2]. */
    internal fun tokens(name: String): List<String> {
        // ROM-set names ("Legend of Zelda, The - …") read the same as the usual order once the article is a token too
        val s = java.text.Normalizer.normalize(PARENS.replace(name, " "), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\band\\b"), " ")
        return s.split(' ').filter { it.isNotEmpty() }.map { roman(it)?.toString() ?: it }
    }

    private fun numbers(t: List<String>): Set<Int> = t.mapNotNull { it.toIntOrNull() }.toSet()

    /** A strict Roman numeral (i, v, x only) from 1 to 39, else null: "mix" and "civil" stay words. */
    private fun roman(s: String): Int? {
        if (!ROMAN.matches(s)) return null
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10)
        var total = 0; var prev = 0
        for (c in s.reversed()) { val n = v.getValue(c); if (n < prev) total -= n else { total += n; prev = n } }
        val tens = arrayOf("", "x", "xx", "xxx"); val ones = arrayOf("", "i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix")
        return if (total in 1..39 && tens[total / 10] + ones[total % 10] == s) total else null
    }
}
