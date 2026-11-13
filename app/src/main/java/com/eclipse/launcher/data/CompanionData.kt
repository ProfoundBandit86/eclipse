package com.eclipse.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Companion's Checklist and Maps tabs: what's kept for a game, beside its saved guides
 * (filesDir/companion/<game id>/checklist.json, …/maps/ with maps.json).
 */
object CompanionData {

    /** Bumped when something is saved from outside the Companion (its browser window): its lists load again. */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)
    fun changed() { changes.intValue++ }

    // ---------------------------------------------------------------- checklist

    /**
     * One thing to tick off. [section]: the heading it sits under ("" = none). [color]: a colour tag, 0 = none, else
     * 1..[TAG_COLORS] (see the Checklist tab).
     */
    data class Item(val id: Long, val text: String, val done: Boolean = false, val missable: Boolean = false, val section: String = "", val color: Int = 0)

    /** How many colour tags there are (the Checklist tab draws them). */
    const val TAG_COLORS = 6

    private fun checklistFile(root: File, gameId: String) = File(Guides.dir(root, gameId), "checklist.json")

    fun checklist(root: File, gameId: String): List<Item> {
        val arr = runCatching { JSONArray(checklistFile(root, gameId).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optLong("id"), o.optString("text"), o.optBoolean("done"), o.optBoolean("missable"), o.optString("section"), o.optInt("color"))
        }
    }

    fun saveChecklist(root: File, gameId: String, items: List<Item>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done).put("missable", it.missable)
                .put("section", it.section).put("color", it.color))
        }
        Guides.dir(root, gameId).mkdirs()
        checklistFile(root, gameId).writeText(arr.toString())
    }

    /** A saved guide's section titles, as things to tick off (ones already on the list are left out). */
    fun fromGuide(s: Guides.Saved, have: List<Item>): List<Item> {
        val text = runCatching { s.file.readText() }.getOrNull() ?: return emptyList()
        val known = have.map { it.text.lowercase() }.toHashSet()
        var id = System.currentTimeMillis()
        return Guides.reflow(text, s.html).filterIsInstance<Guides.Block.Heading>()
            .map { it.text.trim() }.filter { it.length in 3..70 && known.add(it.lowercase()) }
            .take(200).map { Item(id++, it) }
    }

    /**
     * A pasted or Assistant-written list as items: "# Group" and "## Section" lines are headings ("Group · Section"
     * when both), "- [ ] thing", "- [x] thing", "- thing", "* thing" and "1. thing" are items (ticked for [x]), other
     * lines are items too. Markdown emphasis is dropped; "(missable)" or "MISSABLE" marks one missable.
     */
    fun parseList(text: String): List<Item> {
        var group = ""; var section = ""
        var id = System.currentTimeMillis()
        val out = ArrayList<Item>()
        fun clean(s: String) = s.replace(Regex("[*_`]+"), "").replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").trim()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("```") || line == "---") continue
            val h = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (h != null) {
                val title = clean(h.groupValues[2])
                if (h.groupValues[1].length == 1) { group = title; section = "" } else section = title
                continue
            }
            val m = Regex("^(?:[-*+]|\\d+[.)])\\s+(?:\\[( |x|X)]\\s+)?(.+)$").find(line)
            val body = clean(m?.groupValues?.get(2) ?: line)
            if (body.isEmpty()) continue
            val done = m?.groupValues?.get(1)?.equals("x", true) == true
            val missable = Regex("\\(missable\\)|\\bMISSABLE\\b").containsMatchIn(body)
            val label = listOf(group, section).filter { it.isNotEmpty() }.joinToString(" \u00b7 ")
            out += Item(id++, body.replace(Regex("\\s*\\(missable\\)", RegexOption.IGNORE_CASE), "").trim(), done, missable, label)
        }
        return out.take(400)
    }

    /**
     * Ready-made requests for the Assistant to write a checklist ({game} = the game's name). Adapted from Game Twin's
     * checklist prompts (Mert Aslantürk, GPL-3.0).
     */
    val CHECKLIST_PROMPTS = listOf(
        "Full completion" to "Write a completionist checklist for {game}: story milestones, collectibles, side content, achievements and anything you can permanently miss.",
        "Bosses and how to beat them" to "List every boss in {game}, in the order you meet them. Each boss is a section; its items are a short, concrete strategy: weak points, recommended level or gear, the attacks to dodge.",
        "Essential items in order" to "For {game}, list the essential items and upgrades to pick up, in the order they become available. Use sections for early, mid and late game.",
        "Puzzle hints" to "For {game}, list the puzzles in order. Each puzzle is a section; its first item is a light nudge, the next ones are bigger hints, and the last item is the full solution.",
        "Missables only" to "List everything in {game} that can be permanently missed, grouped by the point in the game where you lose the chance. Say what to do and when."
    )

    /** What every checklist request ends with: the format [parseList] reads. */
    const val CHECKLIST_FORMAT = "Format: '#' for 1-3 top-level groups, '##' for sections, '- [ ]' for each item; add '(missable)' after anything that can be missed. Return only the list, no introduction, closing remarks or code fences."

    // ---------------------------------------------------------------- maps

    /** A pin on a map: where (0..1 across and down the picture) and what it says. */
    data class Marker(val x: Float, val y: Float, val label: String)
    data class GameMap(val file: File, val name: String, val markers: List<Marker>)

    private fun mapsDir(root: File, gameId: String) = File(Guides.dir(root, gameId), "maps")
    private fun mapsIndex(root: File, gameId: String) = File(mapsDir(root, gameId), "maps.json")

    fun maps(root: File, gameId: String): List<GameMap> {
        val arr = runCatching { JSONArray(mapsIndex(root, gameId).readText()) }.getOrNull() ?: return emptyList()
        val d = mapsDir(root, gameId)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = File(d, o.optString("file"))
            if (!f.isFile) return@mapNotNull null
            val ms = o.optJSONArray("markers") ?: JSONArray()
            GameMap(f, o.optString("name"), (0 until ms.length()).mapNotNull { j ->
                ms.optJSONObject(j)?.let { m -> Marker(m.optDouble("x").toFloat(), m.optDouble("y").toFloat(), m.optString("label")) }
            })
        }
    }

    private fun writeMaps(root: File, gameId: String, list: List<GameMap>) {
        val arr = JSONArray()
        list.forEach { m ->
            val ms = JSONArray()
            m.markers.forEach { ms.put(JSONObject().put("x", it.x.toDouble()).put("y", it.y.toDouble()).put("label", it.label)) }
            arr.put(JSONObject().put("file", m.file.name).put("name", m.name).put("markers", ms))
        }
        mapsDir(root, gameId).mkdirs()
        mapsIndex(root, gameId).writeText(arr.toString())
    }

    /** A new, empty file in the game's maps folder for a picture about to be written. */
    fun newMapFile(root: File, gameId: String): File = File(mapsDir(root, gameId).apply { mkdirs() }, "m" + System.currentTimeMillis() + ".img")

    /** Lists [file] (already in the maps folder) as a map called [name]. */
    fun addMap(root: File, gameId: String, file: File, name: String): GameMap {
        val m = GameMap(file, name.ifBlank { "Map" }, emptyList())
        writeMaps(root, gameId, maps(root, gameId) + m)
        return m
    }

    fun updateMap(root: File, gameId: String, map: GameMap) =
        writeMaps(root, gameId, maps(root, gameId).map { if (it.file == map.file) map else it })

    fun deleteMap(root: File, gameId: String, map: GameMap) {
        map.file.delete()
        writeMaps(root, gameId, maps(root, gameId).filter { it.file != map.file })
    }

    // ---------------------------------------------------------------- Ask (the AI helper's conversation)

    /**
     * One message of the Ask tab. A question: [text], and [image] (a picture of the top screen kept beside it, if one
     * was sent). An answer: [hint] / [text] (the full answer), [sources], [checklist]; [revealed] once the full answer
     * of a hinted one has been opened; [error] when asking failed.
     */
    data class Msg(
        val id: Long, val user: Boolean, val text: String, val image: String? = null, val hint: String? = null,
        val sources: List<AiHelper.Source> = emptyList(), val checklist: List<String> = emptyList(),
        val revealed: Boolean = false, val error: Boolean = false,
        /** how it was answered, when that's worth saying ("not checked on the web …") */
        val note: String? = null,
    )

    private fun askFile(root: File, gameId: String) = File(Guides.dir(root, gameId), "ask.json")

    fun conversation(root: File, gameId: String): List<Msg> {
        val arr = runCatching { JSONArray(askFile(root, gameId).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val src = o.optJSONArray("sources") ?: JSONArray()
            val ch = o.optJSONArray("checklist") ?: JSONArray()
            Msg(
                o.optLong("id"), o.optBoolean("user"), o.optString("text"), o.optString("image").ifEmpty { null },
                o.optString("hint").ifEmpty { null },
                (0 until src.length()).mapNotNull { j -> src.optJSONObject(j)?.let { AiHelper.Source(it.optString("title"), it.optString("url")) } },
                (0 until ch.length()).map { ch.optString(it) }, o.optBoolean("revealed"), o.optBoolean("error"),
                o.optString("note").ifEmpty { null },
            )
        }
    }

    /** Keeps the last 60 messages (pictures of messages that fall off are deleted). */
    fun saveConversation(root: File, gameId: String, list: List<Msg>) {
        val keep = list.takeLast(60)
        list.dropLast(keep.size).forEach { m -> m.image?.let { File(it).delete() } }
        val arr = JSONArray()
        keep.forEach { m ->
            val src = JSONArray(); m.sources.forEach { src.put(JSONObject().put("title", it.title).put("url", it.url)) }
            val ch = JSONArray(); m.checklist.forEach { ch.put(it) }
            arr.put(JSONObject().put("id", m.id).put("user", m.user).put("text", m.text).put("image", m.image ?: "")
                .put("hint", m.hint ?: "").put("sources", src).put("checklist", ch).put("revealed", m.revealed).put("error", m.error).put("note", m.note ?: ""))
        }
        Guides.dir(root, gameId).mkdirs()
        askFile(root, gameId).writeText(arr.toString())
    }

    /** Starts over: the conversation and its pictures go. */
    fun clearConversation(root: File, gameId: String) {
        conversation(root, gameId).forEach { m -> m.image?.let { File(it).delete() } }
        askFile(root, gameId).delete()
    }

    /** Where a picture sent with a question is kept. */
    fun newAskImage(root: File, gameId: String): File = File(Guides.dir(root, gameId).apply { mkdirs() }, "ask_" + System.currentTimeMillis() + ".jpg")
}
