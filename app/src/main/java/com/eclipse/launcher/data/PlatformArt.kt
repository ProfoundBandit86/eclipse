package com.eclipse.launcher.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray

/*
 * Pictures of the systems themselves (not their games): icons from RetroArch's own icon sets (libretro) and from
 * RetroAchievements' console list, and logos from IGDB. Every function returns an empty list when the service can't
 * be reached or doesn't have the system.
 */
object PlatformArt {
    private const val ASSETS = "https://raw.githubusercontent.com/libretro/retroarch-assets/master"

    /** RetroArch's icon sets: name -> folder. Each set has one picture per system, named like the libretro system. */
    private val styles = listOf(
        "Ozone" to "ozone/png/icons", "Flat UI" to "xmb/flatui/png", "Monochrome" to "xmb/monochrome/png",
        "Systematic" to "xmb/systematic/png", "Neoactive" to "xmb/neoactive/png", "Retroactive" to "xmb/retroactive/png",
        "Pixel" to "xmb/pixel/png", "Automatic" to "xmb/automatic/png"
    )

    /** The system's icon in each RetroArch icon set that has one (checked with a small HEAD request each). */
    suspend fun libretro(platform: String): List<ArtOption> = coroutineScope {
        val name = Libretro.systemName(platform) ?: return@coroutineScope emptyList()
        val file = Http.enc(name).replace("+", "%20")
        styles.map { (label, dir) ->
            async { "$ASSETS/$dir/$file.png".let { u -> if (Http.exists(u)) ArtOption(u, "libretro \u00b7 $label") else null } }
        }.awaitAll().filterNotNull()
    }

    // ---- LaunchBox Games Database: a system's Device / Clear Logo / Fanart / Box pictures (web pages, no API) ----
    private const val LB = "https://gamesdb.launchbox-app.com"
    /** Folded platform name -> its page reference ("25-nintendo-64", or just "25"), read once from the home page. */
    private var lbRefs: Map<String, String>? = null
    /** Platform page reference -> (picture type, option), so switching kinds doesn't read the page again. */
    private val lbPics = HashMap<String, List<Pair<String, ArtOption>>>()
    private fun lbFold(s: String) = s.lowercase().replace("&amp;", "and").replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

    private suspend fun lbRef(names: List<String>): String? {
        val refs = lbRefs ?: run {
            val html = Http.get("$LB/") ?: return null
            val m = HashMap<String, String>()
            // the dropdown of every platform (value = its number), then the linked list (number + slug)
            Regex("""<option[^>]*value=\"(\d+)\"[^>]*>([^<]+)</option>""").findAll(html).forEach { m.putIfAbsent(lbFold(it.groupValues[2]), it.groupValues[1]) }
            Regex("""/platforms/(?:games|details|images)/(\d+)-([a-z0-9-]+)""").findAll(html).forEach { m[lbFold(it.groupValues[2])] = "${it.groupValues[1]}-${it.groupValues[2]}" }
            if (m.isEmpty()) return null
            m.also { lbRefs = it }
        }
        return names.firstNotNullOfOrNull { refs[lbFold(it)] }
    }

    /** What kinds of system picture a LaunchBox picture type suits. */
    private fun lbKinds(type: String): Set<PlatKind> {
        val t = type.lowercase()
        return when {
            "logo" in t -> setOf(PlatKind.LOGO)
            "fanart" in t || "background" in t -> setOf(PlatKind.BACKDROP)
            "device" in t -> setOf(PlatKind.ICON, PlatKind.CARD)
            "box" in t || "cart" in t -> setOf(PlatKind.CARD)
            "banner" in t -> emptySet()
            else -> setOf(PlatKind.ICON, PlatKind.CARD)
        }
    }

    /** The system's pictures of [kind] on LaunchBox, found by any of its LaunchBox [names]. */
    suspend fun launchBox(names: List<String>, kind: PlatKind): List<ArtOption> {
        if (names.isEmpty()) return emptyList()
        val ref = lbRef(names) ?: return emptyList()
        val pics = lbPics[ref] ?: run {
            val html = Http.get("$LB/platforms/images/$ref") ?: return emptyList()
            val out = ArrayList<Pair<String, ArtOption>>()
            val seen = HashSet<String>()
            // each picture: <a href="full picture"><img src="thumb" alt="Nintendo 64 - Platform Device Thumb"></a>
            Regex("""href=\"(https://images\.launchbox-app\.com/Platforms/[^\"]+)\"""").findAll(html).forEach { a ->
                val full = a.groupValues[1]
                if (!seen.add(full)) return@forEach
                val tail = html.substring(a.range.last, minOf(html.length, a.range.last + 800)).substringBefore("</a>")
                val thumb = Regex("""src=\"(https://images\.launchbox-app\.com/[^\"]+)\"""").find(tail)?.groupValues?.get(1) ?: full
                val alt = Regex("""alt=\"([^\"]*)\"""").find(tail)?.groupValues?.get(1).orEmpty()
                val type = alt.substringAfter(" - ", "").removeSuffix(" Thumb").removePrefix("Platform ").trim()
                out.add(type to ArtOption(full, if (type.isEmpty()) "LaunchBox" else "LaunchBox \u00b7 $type", thumb))
            }
            out.also { lbPics[ref] = it }
        }
        return pics.filter { kind in lbKinds(it.first) }.map { it.second }
    }

    private var raConsoles: Map<Int, String>? = null

    /** RetroAchievements' icon for console [console] (its console list, fetched once). */
    suspend fun retroAch(user: String, key: String, console: Int?): List<ArtOption> {
        if (console == null || user.isBlank() || key.isBlank()) return emptyList()
        val map = raConsoles ?: run {
            val body = Http.get("https://retroachievements.org/API/API_GetConsoleIDs.php?z=${Http.enc(user)}&y=${Http.enc(key)}") ?: return emptyList()
            val arr = try { JSONArray(body) } catch (e: Exception) { return emptyList() }
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { o -> o.optInt("ID") to o.optString("IconURL") } }
                .filter { it.second.isNotEmpty() }.toMap().also { raConsoles = it }
        }
        return map[console]?.let { listOf(ArtOption(it, "RetroAchievements")) } ?: emptyList()
    }
}
