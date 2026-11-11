package com.eclipse.launcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Companion's Checklist and Maps tabs: what's kept for a game, beside its saved guides
 * (filesDir/companion/<game id>/checklist.json, …/maps/ with maps.json).
 */
object CompanionData {

    // ---------------------------------------------------------------- checklist

    data class Item(val id: Long, val text: String, val done: Boolean = false, val missable: Boolean = false)

    private fun checklistFile(root: File, gameId: String) = File(Guides.dir(root, gameId), "checklist.json")

    fun checklist(root: File, gameId: String): List<Item> {
        val arr = runCatching { JSONArray(checklistFile(root, gameId).readText()) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optLong("id"), o.optString("text"), o.optBoolean("done"), o.optBoolean("missable"))
        }
    }

    fun saveChecklist(root: File, gameId: String, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done).put("missable", it.missable)) }
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
