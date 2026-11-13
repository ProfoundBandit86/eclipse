package com.eclipse.launcher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Search that understands a sentence: "metroid games before 2002", "snes rpgs from the 90s", "4 player party games".
 * [parse] picks out what it knows (systems, years, genres, players, favourites / never played) and the words left over
 * are the title. Each thing it understood is a [Chip] under the search bar; removing one widens the search. What it
 * can't place can go to the Assistant ([aiSystem] / [fromAi]), whose answer comes back as the same chips.
 */
object SearchQuery {
    /** One understood part of a search. [key]: title, system, year, genre, players, developer, publisher, fav, unplayed. */
    data class Chip(val key: String, val label: String, val value: String, val ai: Boolean = false) {
        val id get() = "$key:$value"
    }

    class Parsed(
        val chips: List<Chip>,
        /** words that weren't understood and aren't filler: they're the title (also its own chip when other chips exist) */
        val leftover: String,
        /** the number of words typed (the Assistant is only asked about sentences, not single names) */
        val words: Int
    ) {
        /** more than a plain name: there is something besides the title to filter on */
        val structured get() = chips.any { it.key != "title" }
    }

    /** Words that carry no meaning in a search ("show me all the … games"). */
    private val filler = setOf(
        "show", "me", "find", "list", "all", "the", "a", "an", "of", "for", "with", "my", "some", "any", "and", "or",
        "game", "games", "titles", "title", "that", "are", "is", "were", "was", "released", "made", "came", "out",
        "on", "in", "from", "by", "to", "i", "have", "which", "what", "every", "only", "please", "search", "look", "up"
    )

    /** Genre words, and what they match in a game's genre text. */
    private val genres = linkedMapOf(
        "rpg" to listOf("rpg", "role-playing", "role playing"), "rpgs" to listOf("rpg", "role-playing", "role playing"),
        "role playing" to listOf("rpg", "role-playing", "role playing"), "jrpg" to listOf("rpg", "role-playing"),
        "platformer" to listOf("platform"), "platformers" to listOf("platform"), "platforming" to listOf("platform"),
        "shooter" to listOf("shoot"), "shooters" to listOf("shoot"), "shmup" to listOf("shoot"), "shmups" to listOf("shoot"),
        "fighting" to listOf("fight"), "fighter" to listOf("fight"), "fighters" to listOf("fight"),
        "racing" to listOf("racing", "driving"), "racer" to listOf("racing", "driving"), "racers" to listOf("racing", "driving"), "driving" to listOf("racing", "driving"),
        "puzzle" to listOf("puzzle"), "puzzles" to listOf("puzzle"), "sports" to listOf("sport"), "sport" to listOf("sport"),
        "strategy" to listOf("strategy", "tactic"), "tactics" to listOf("strategy", "tactic"), "tactical" to listOf("strategy", "tactic"),
        "horror" to listOf("horror"), "adventure" to listOf("adventure"), "adventures" to listOf("adventure"), "action" to listOf("action"),
        "simulation" to listOf("simulat"), "sim" to listOf("simulat"), "sims" to listOf("simulat"), "music" to listOf("music", "rhythm"),
        "rhythm" to listOf("music", "rhythm"), "party" to listOf("party"), "stealth" to listOf("stealth"), "survival" to listOf("survival"),
        "beat em up" to listOf("beat"), "beat 'em up" to listOf("beat"), "brawler" to listOf("beat", "brawl"), "brawlers" to listOf("beat", "brawl"),
        "metroidvania" to listOf("metroidvania", "platform"), "pinball" to listOf("pinball"), "card" to listOf("card"), "board" to listOf("board"),
        "educational" to listOf("educat"), "visual novel" to listOf("visual novel", "novel"), "arcade" to listOf("arcade")
    )

    /** Systems people name differently from their folders. */
    private val systemWords = mapOf(
        "playstation" to "ps1", "ps1" to "ps1", "psone" to "ps1", "psx" to "ps1", "playstation 2" to "ps2", "playstation 3" to "ps3",
        "playstation portable" to "psp", "vita" to "psvita", "super nintendo" to "snes", "super famicom" to "snes",
        "famicom" to "nes", "genesis" to "genesis", "mega drive" to "md", "megadrive" to "md", "game boy" to "gb", "gameboy" to "gb",
        "game boy color" to "gbc", "gameboy color" to "gbc", "game boy advance" to "gba", "gameboy advance" to "gba",
        "nintendo 64" to "n64", "gamecube" to "gc", "game cube" to "gc", "nintendo ds" to "nds", "3ds" to "3ds",
        "dreamcast" to "dreamcast", "saturn" to "saturn", "wii" to "wii", "wii u" to "wiiu", "switch" to "switch"
    )

    private fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9'+\\- ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Picks [phrase] out of [text] as whole words; returns the text without it, or null when it isn't there. */
    private fun take(text: String, phrase: String): String? {
        val m = Regex("(^| )" + Regex.escape(phrase) + "( |$)").find(text) ?: return null
        return (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
    }

    fun parse(query: String, systems: List<PlatformDef>): Parsed {
        var t = " " + words(query).joinToString(" ") + " "
        val count = words(query).size
        val chips = ArrayList<Chip>()
        fun cut(r: Regex, f: (MatchResult) -> Unit) {
            while (true) {
                val m = r.find(t) ?: break
                f(m)
                t = (t.substring(0, m.range.first) + " " + t.substring(m.range.last + 1))
            }
        }
        // ---- years: before / after / in / between, decades ("90s", "the 1990s"), ranges ("1995-2000")
        val year = "(19[5-9][0-9]|20[0-4][0-9])"
        cut(Regex(" (between|from) $year (and|to|-) $year ")) { m -> chips += Chip("year", "Year", "${m.groupValues[2]}–${m.groupValues[4]}") }
        cut(Regex(" $year ?(-|to) ?$year ")) { m -> chips += Chip("year", "Year", "${m.groupValues[1]}–${m.groupValues[3]}") }
        cut(Regex(" (before|pre|older than|earlier than) $year ")) { m -> chips += Chip("year", "Year", "before ${m.groupValues[2]}") }
        cut(Regex(" (after|since|post|newer than|later than) $year ")) { m -> chips += Chip("year", "Year", "after ${m.groupValues[2]}") }
        cut(Regex(" (?:the )?(?:19)?([5-9])0'?s ")) { m -> val d = "19${m.groupValues[1]}0"; chips += Chip("year", "Year", "$d–${d.toInt() + 9}") }
        cut(Regex(" (?:the )?(?:20)?([0-4])0'?s ")) { m -> val d = "20${m.groupValues[1]}0"; chips += Chip("year", "Year", "$d–${d.toInt() + 9}") }
        cut(Regex(" (?:in |from )?$year ")) { m -> chips += Chip("year", "Year", m.groupValues[1]) }
        // ---- players: "multiplayer", "co-op", "2 player", "4-players", "for 4"
        cut(Regex(" ([2-8]) ?-?(players?|player co-?op) ")) { m -> chips += Chip("players", "Players", "${m.groupValues[1]} or more") }
        cut(Regex(" (multiplayer|multi-player|co-?op|coop|cooperative|local co-?op|two player|2p) ")) { _ -> chips += Chip("players", "Players", "2 or more") }
        // ---- your own lists
        cut(Regex(" (favourites|favorites|favourite|favorite|faves|fav) ")) { _ -> chips += Chip("fav", "Only", "Favourites") }
        cut(Regex(" (never played|unplayed|not played|haven't played|havent played) ")) { _ -> chips += Chip("unplayed", "Only", "Never played") }
        // ---- systems: their names, short names, folder names and the common ways people say them (longest first)
        val names = LinkedHashMap<String, String>()
        systemWords.forEach { (w, id) -> if (systems.any { it.id == id }) names[w] = id }
        for (p in systems) {
            if (p.id == "android" || p.id == Platforms.ALL || p.id == Platforms.FAVORITES) continue
            (listOf(p.id, p.name, p.short) + p.aliases).map { words(it).joinToString(" ") }
                .filter { it.length >= 2 && it !in filler && it !in genres }
                .forEach { names.putIfAbsent(it, p.id) }
        }
        for (w in names.keys.sortedByDescending { it.length }) {
            val left = take(t.trim(), w) ?: continue
            val id = names.getValue(w)
            val name = systems.firstOrNull { it.id == id }?.let { it.short.ifBlank { it.name } } ?: id
            if (chips.none { it.key == "system" && it.value == id }) chips += Chip("system", "System", id).copy(label = "System:$name")
            t = " $left "
        }
        // ---- genres (longest first: "beat em up" before "beat")
        for (g in genres.keys.sortedByDescending { it.length }) {
            val left = take(t.trim(), g) ?: continue
            val main = genres.getValue(g).first()
            if (chips.none { it.key == "genre" && it.value == main }) chips += Chip("genre", "Genre:" + g.trimEnd('s').replaceFirstChar { it.uppercase() }.let { if (it == "Rpg") "RPG" else it }, main)
            t = " $left "
        }
        // what's left: the title, without filler words
        val rest = words(t).filter { it !in filler }.joinToString(" ")
        if (rest.isNotEmpty()) chips.add(0, Chip("title", "Title", rest))
        return Parsed(chips, rest, count)
    }

    /** What a chip shows: "SYSTEM" + "SNES". */
    fun keyOf(c: Chip) = c.label.substringBefore(':').uppercase()
    fun shown(c: Chip): String = c.label.substringAfter(':', "").ifEmpty {
        when (c.key) { "title" -> c.value.replaceFirstChar { it.uppercase() }; else -> c.value }
    }

    // ---------------------------------------------------------------- matching

    /** What a game offers to match against (any of it can be blank). */
    class Facts(
        val name: String, val system: String, val year: Int?, val genre: String, val players: Int?,
        val developer: String, val publisher: String, val fav: Boolean, val played: Boolean
    )

    /** First 4-digit year in a date ("1994", "1994-03-19", "Mar 1994"). */
    fun yearOf(s: String?): Int? = s?.let { Regex("(19|20)[0-9]{2}").find(it)?.value?.toIntOrNull() }
    /** The most players a "1-4" / "2" / "Up to 4" says. */
    fun playersOf(s: String?): Int? = s?.let { Regex("[0-9]+").findAll(it).mapNotNull { m -> m.value.toIntOrNull() }.maxOrNull() }

    /** True when the game fits every chip. A chip about something the game's details don't say: it doesn't fit. */
    fun matches(f: Facts, chips: List<Chip>): Boolean = chips.all { c ->
        when (c.key) {
            "title" -> words(c.value).all { it in f.name.lowercase() }
            "system" -> f.system == c.value
            "year" -> {
                val y = f.year
                val v = c.value
                when {
                    y == null -> false
                    v.startsWith("before ") -> y < (v.removePrefix("before ").toIntOrNull() ?: 0)
                    v.startsWith("after ") -> y > (v.removePrefix("after ").toIntOrNull() ?: 9999)
                    '–' in v -> { val (a, b) = v.split('–').map { it.trim().toIntOrNull() ?: 0 }; y in a..b }
                    else -> y == v.toIntOrNull()
                }
            }
            "genre" -> (genres.values.firstOrNull { it.first() == c.value } ?: listOf(c.value)).any { it in f.genre.lowercase() }
            "players" -> (f.players ?: 0) >= (c.value.substringBefore(' ').toIntOrNull() ?: 2)
            "developer" -> c.value.lowercase() in f.developer.lowercase()
            "publisher" -> c.value.lowercase() in f.publisher.lowercase() || c.value.lowercase() in f.developer.lowercase()
            "fav" -> f.fav
            "unplayed" -> !f.played
            else -> true
        }
    }

    // ---------------------------------------------------------------- the Assistant

    /** The instructions for turning a sentence into filters: JSON only, using Eclipse's system ids. */
    fun aiSystem(systems: List<PlatformDef>): String = buildString {
        append("You turn a search typed or spoken in a retro game launcher into filters for the user's own game library. ")
        append("Reply with one JSON object and nothing else, with any of these keys: ")
        append("\"title\" (words that must be in the game's name, or null), \"systems\" (a list of system ids from the list below), ")
        append("\"yearMin\" and \"yearMax\" (numbers), \"genres\" (a list of plain genre words such as rpg, platform, shooter, racing, puzzle, ")
        append("fighting, sports, strategy, horror, adventure, action), \"minPlayers\" (a number; co-op or multiplayer means 2), ")
        append("\"developer\" and \"publisher\" (names). Leave out anything the search doesn't ask for. Don't invent titles.\n")
        append("System ids: ")
        append(systems.filter { it.id != "android" && it.id != Platforms.ALL && it.id != Platforms.FAVORITES }.joinToString(", ") { "${it.id} (${it.name})" })
    }

    /** The Assistant's JSON as chips (blue ones). Null when the answer couldn't be read. */
    fun fromAi(text: String, systems: List<PlatformDef>): List<Chip>? = try {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        val o = JSONObject(text.substring(start, end + 1))
        val out = ArrayList<Chip>()
        fun list(k: String): List<String> = (o.opt(k) as? JSONArray)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?: o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { listOf(it) } ?: emptyList()
        o.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { out += Chip("title", "Title", it.lowercase(), ai = true) }
        list("systems").mapNotNull { id -> systems.firstOrNull { it.id == id.lowercase() } }.forEach { p ->
            out += Chip("system", "System:" + p.short.ifBlank { p.name }, p.id, ai = true)
        }
        val lo = o.optInt("yearMin", 0).takeIf { it > 1900 }; val hi = o.optInt("yearMax", 0).takeIf { it > 1900 }
        when {
            lo != null && hi != null -> out += Chip("year", "Year", if (lo == hi) "$lo" else "$lo–$hi", ai = true)
            lo != null -> out += Chip("year", "Year", "after ${lo - 1}", ai = true)
            hi != null -> out += Chip("year", "Year", "before ${hi + 1}", ai = true)
        }
        list("genres").forEach { g ->
            val k = g.lowercase().trim()
            val main = genres[k]?.first() ?: genres[k + "s"]?.first() ?: k
            out += Chip("genre", "Genre:" + g.replaceFirstChar { it.uppercase() }, main, ai = true)
        }
        o.optInt("minPlayers", 0).takeIf { it >= 2 }?.let { out += Chip("players", "Players", "$it or more", ai = true) }
        o.optString("developer").takeIf { it.isNotBlank() && it != "null" }?.let { out += Chip("developer", "Developer:$it", it, ai = true) }
        o.optString("publisher").takeIf { it.isNotBlank() && it != "null" }?.let { out += Chip("publisher", "Publisher:$it", it, ai = true) }
        out
    } catch (e: Exception) {
        null
    }
}
