package com.eclipse.launcher.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Storage.
 *  - Small settings live in SharedPreferences (str / putBool / map / list).
 *  - Large, frequently-changing data (games, artwork, descriptions, play time) live in their own
 *    JSON files written atomically, so saving one never rewrites the others and a crash mid-write
 *    can't corrupt them. Call the *File functions from a background thread.
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("eclipse", Context.MODE_PRIVATE)
    private val dir: File = ctx.filesDir

    // ---------- SharedPreferences (small values) ----------
    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun remove(k: String) = sp.edit().remove(k).apply()

    fun putBool(k: String, v: Boolean) = sp.edit().putBoolean("f_$k", v).apply()

    fun allBools(): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for ((k, v) in sp.all) {
            if (k.startsWith("f_") && v is Boolean) out[k.removePrefix("f_")] = v
        }
        return out
    }

    fun map(k: String): Map<String, String> = parseMap(str(k, "{}"))
    fun putMap(k: String, m: Map<String, String>) = putStr(k, JSONObject(m).toString())

    fun list(k: String): List<String> {
        val a = try { JSONArray(str(k, "[]")) } catch (e: Exception) { JSONArray() }
        return List(a.length()) { a.optString(it) }
    }

    fun putList(k: String, l: List<String>) = putStr(k, JSONArray(l).toString())

    // ---------- JSON files (large values; background thread only) ----------
    private fun readText(name: String): String? {
        val f = AtomicFile(File(dir, name))
        if (!f.baseFile.exists()) return null
        return try { String(f.readFully(), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    private fun writeText(name: String, text: String) {
        val f = AtomicFile(File(dir, name))
        var out: FileOutputStream? = null
        try {
            out = f.startWrite()
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) f.failWrite(out)
        }
    }

    /** Reads [name]; on first run after the update, moves the old SharedPreferences value [legacyKey] into the file. */
    private fun readWithMigration(name: String, legacyKey: String): String? {
        readText(name)?.let { return it }
        val old = sp.getString(legacyKey, null) ?: return null
        writeText(name, old)
        sp.edit().remove(legacyKey).apply()
        return old
    }

    fun loadMapFile(name: String, legacyKey: String): Map<String, String> =
        parseMap(readWithMigration(name, legacyKey) ?: "{}")

    fun saveMapFile(name: String, m: Map<String, String>) = writeText(name, JSONObject(m).toString())

    fun loadGamesFile(name: String, legacyKey: String): List<Game> {
        val a = try { JSONArray(readWithMigration(name, legacyKey) ?: "[]") } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Game>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(Game(o.optString("id"), o.optString("name"), o.optString("platform"), o.optString("path")))
        }
        return out
    }

    fun saveGamesFile(name: String, games: List<Game>) {
        val a = JSONArray()
        games.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("platform", it.platform).put("path", it.path))
        }
        writeText(name, a.toString())
    }

    private fun parseMap(text: String): Map<String, String> {
        val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        val m = LinkedHashMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) {
            val key = it.next()
            m[key] = o.optString(key)
        }
        return m
    }
}
