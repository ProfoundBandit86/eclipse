package com.eclipse.launcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The Companion's Ask tab: questions about the game, answered by Claude (Anthropic's Messages API with its web search
 * tool) or Gemini (Google's generateContent with Google Search grounding), with the person's own API key. Both search
 * the web before answering and say where the answer came from.
 *
 * The answer comes back in one piece, in a shape set by [system]: an optional hint, "===FULL===", the full answer,
 * and an optional <checklist> block. [parse] splits it.
 */
object AiHelper {
    const val CLAUDE = "claude"
    const val GEMINI = "gemini"
    const val CLAUDE_MODEL = "claude-sonnet-5-5"
    /** Google's alias for its current Flash model (so a renamed model doesn't break it). */
    const val GEMINI_MODEL = "gemini-flash-latest"

    data class Source(val title: String, val url: String)

    /** One message of the conversation. [image]: a picture of the top screen sent with it (JPEG bytes, newest only). */
    data class Turn(val user: Boolean, val text: String, val image: ByteArray? = null)

    data class Answer(
        /** with "No spoilers" on: a nudge, shown before the full answer (null when the question needed none) */
        val hint: String?,
        val full: String,
        val sources: List<Source>,
        /** things to tick off, when the answer listed some */
        val checklist: List<String>,
        /** something to say about how it was answered (Gemini's free tier refusing web search, say) */
        val note: String? = null,
    )

    /** [code]: the HTTP status the service answered with (0 = no answer). */
    class AiError(message: String, val code: Int = 0) : Exception(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // searching takes a while
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** The instructions sent with every question: who's asking, about what, and how to answer. */
    fun system(game: String, platform: String, noSpoilers: Boolean, context: String?): String = buildString {
        append("You help someone who is playing \"").append(game).append("\" on ").append(platform)
        append(" in an emulator. They read your answer on a small second screen (about 600 × 450 pixels) beside the game.\n\n")
        append("How to answer:\n")
        append("- Search the web for anything about the game before answering: specific locations, items, puzzles, bosses, routes, guides. Don't answer those from memory.\n")
        append("- Be short and direct: a few sentences, or a short list. Plain text: no headings, no tables. **Bold** is fine for names.\n")
        append("- If you're not sure, say so. If versions of the game differ (regions, remakes), say which one you mean.\n")
        append("- When asked to find guides or walkthroughs, name the best few pages and what each is good for.\n")
        append("- When the answer names specific things to collect or do, end with them, one per line, between <checklist> and </checklist> (at most 40 short items, no numbering). Leave the block out otherwise.\n")
        if (noSpoilers) {
            append("- No spoilers: when the question asks how to solve, find or beat something, first give a hint of one or two sentences that points the way without giving it away, then a line with only ===FULL===, then the full answer. ")
            append("For other questions (facts, controls, finding guides) answer normally, without ===FULL===. Never reveal story events the player hasn't asked about.\n")
        }
        if (!context.isNullOrBlank()) {
            append("\nWhat Eclipse knows about their game (use it when it helps; don't repeat it back):\n")
            append(context.trim()).append('\n')
        }
    }

    /** Splits a reply into hint / full answer / checklist; strips the bits of markdown the screen doesn't draw. */
    fun parse(raw: String, sources: List<Source>): Answer {
        var text = raw.replace("\r", "")
        val items = ArrayList<String>()
        Regex("<checklist>(.*?)</checklist>", RegexOption.DOT_MATCHES_ALL).find(text)?.let { m ->
            m.groupValues[1].lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                .filter { it.isNotEmpty() }.take(40).forEach { items += it }
            text = text.removeRange(m.range)
        }
        text = tidy(text)
        val parts = text.split(Regex("\\n?\\s*={3}FULL={3}\\s*\\n?"), limit = 2)
        val hint = if (parts.size == 2) parts[0].trim().ifEmpty { null } else null
        val full = (if (parts.size == 2) parts[1] else parts[0]).trim()
        return Answer(hint, full.ifEmpty { hint ?: "" }, sources.distinctBy { it.url }.take(6), items)
    }

    private fun tidy(s: String): String = s
        .replace(Regex("(?m)^#{1,6}\\s*"), "")                       // headings
        .replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1")  // [text](url) → text (the sources list has the links)
        .replace(Regex("(?m)^\\s*[*]\\s+"), "• ")                     // "* item" → "• item"
        .replace(Regex("(?m)^\\s*-\\s+"), "• ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    // ---------------------------------------------------------------- talking to the services

    private suspend fun post(url: String, headers: Map<String, String>, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        headers.forEach { (k, v) -> rb.header(k, v) }
        val (code, text) = try {
            client.newCall(rb.build()).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: java.io.IOException) {
            throw AiError("No connection (${e.message ?: "network error"})", 0)
        }
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (code !in 200..299) {
            // the service's own words (first lines only): which limit, which model …
            val msg = o?.optJSONObject("error")?.optString("message").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
                .take(3).joinToString(" ").take(260)
            throw AiError(when (code) {
                401, 403 -> "The API key wasn't accepted" + if (msg.isNotBlank()) ": $msg" else ""
                429 -> "The key's limit was reached" + if (msg.isNotBlank()) ": $msg" else ". Try again in a minute."
                else -> "The service answered $code" + if (msg.isNotBlank()) ": $msg" else ""
            }, code)
        }
        o ?: throw AiError("The service sent something unreadable")
    }

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    /** Asks [provider]. [turns] = the conversation so far, ending with the new question. Throws [AiError]. */
    suspend fun ask(provider: String, key: String, model: String?, system: String, turns: List<Turn>): Answer =
        if (provider == GEMINI) gemini(key, model?.ifBlank { null } ?: GEMINI_MODEL, system, turns)
        else claude(key, model?.ifBlank { null } ?: CLAUDE_MODEL, system, turns)

    /**
     * A short question with no web search and a short answer (search turning a sentence into filters): the plain text
     * that came back. Throws [AiError] like [ask].
     */
    suspend fun plain(provider: String, key: String, model: String?, system: String, question: String): String {
        if (provider == GEMINI) {
            val body = JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
                .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            val m = model?.ifBlank { null } ?: GEMINI_MODEL
            val r = post("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent", mapOf("x-goog-api-key" to key), body)
            val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: throw AiError("No answer came back")
            val text = StringBuilder()
            for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { p -> if (!p.optBoolean("thought")) text.append(p.optString("text")) }
            return text.toString().ifBlank { throw AiError("No answer came back") }
        }
        val body = JSONObject().put("model", model?.ifBlank { null } ?: CLAUDE_MODEL).put("max_tokens", 400).put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
        val r = post("https://api.anthropic.com/v1/messages", claudeHeaders(key), body)
        val content = r.optJSONArray("content") ?: throw AiError("No answer came back")
        val text = StringBuilder()
        for (i in 0 until content.length()) content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.let { text.append(it.optString("text")) }
        return text.toString().ifBlank { throw AiError("No answer came back") }
    }

    /** A tiny question with no searching, to see whether the key works. Null = it does; else what went wrong. */
    suspend fun test(provider: String, key: String, model: String?): String? = try {
        if (provider == GEMINI) {
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK.")))))
            post("https://generativelanguage.googleapis.com/v1beta/models/${model?.ifBlank { null } ?: GEMINI_MODEL}:generateContent",
                mapOf("x-goog-api-key" to key), body)
        } else {
            val body = JSONObject().put("model", model?.ifBlank { null } ?: CLAUDE_MODEL).put("max_tokens", 5)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")))
            post("https://api.anthropic.com/v1/messages", claudeHeaders(key), body)
        }
        null
    } catch (e: AiError) { e.message }

    private fun claudeHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    private suspend fun claude(key: String, model: String, system: String, turns: List<Turn>): Answer {
        val messages = JSONArray()
        // the conversation must alternate user / assistant and start with the user
        var expectUser = true
        for (t in turns) {
            if (t.user != expectUser || t.text.isBlank() && t.image == null) continue
            val content: Any = if (t.image != null) JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(t.image))))
                .put(JSONObject().put("type", "text").put("text", t.text.ifBlank { "What's on the screen?" }))
            else t.text
            messages.put(JSONObject().put("role", if (t.user) "user" else "assistant").put("content", content))
            expectUser = !expectUser
        }
        val text = StringBuilder()
        val cited = ArrayList<Source>()
        val found = ArrayList<Source>()
        // a long search can stop part-way ("pause_turn"): it's continued by sending back what came so far
        repeat(3) {
            val body = JSONObject().put("model", model).put("max_tokens", 2000).put("system", system).put("messages", messages)
                .put("tools", JSONArray().put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 4)))
            val r = post("https://api.anthropic.com/v1/messages", claudeHeaders(key), body)
            val content = r.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val b = content.optJSONObject(i) ?: continue
                when (b.optString("type")) {
                    "text" -> {
                        text.append(b.optString("text"))
                        val cs = b.optJSONArray("citations")
                        if (cs != null) for (j in 0 until cs.length()) cs.optJSONObject(j)?.let { c ->
                            val u = c.optString("url")
                            if (u.startsWith("http")) cited += Source(c.optString("title").ifBlank { hostOf(u) }, u)
                        }
                    }
                    "web_search_tool_result" -> b.optJSONArray("content")?.let { rs ->
                        for (j in 0 until rs.length()) rs.optJSONObject(j)?.let { x ->
                            val u = x.optString("url")
                            if (u.startsWith("http")) found += Source(x.optString("title").ifBlank { hostOf(u) }, u)
                        }
                    }
                }
            }
            if (r.optString("stop_reason") != "pause_turn") {
                if (text.isBlank()) throw AiError("No answer came back")
                return parse(text.toString(), if (cited.isNotEmpty()) cited else found.take(4))
            }
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        if (text.isBlank()) throw AiError("The search took too long. Try asking again.")
        return parse(text.toString(), if (cited.isNotEmpty()) cited else found.take(4))
    }

    private suspend fun gemini(key: String, model: String, system: String, turns: List<Turn>): Answer {
        val contents = JSONArray()
        for (t in turns) {
            if (t.text.isBlank() && t.image == null) continue
            val parts = JSONArray()
            if (t.image != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64(t.image))))
            parts.put(JSONObject().put("text", t.text.ifBlank { "What's on the screen?" }))
            contents.put(JSONObject().put("role", if (t.user) "user" else "model").put("parts", parts))
        }
        fun body(search: Boolean) = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                if (search) system else system + "\nWeb search isn't available for this answer: answer from what you know, and say plainly when you're unsure.\n"))))
            .put("contents", contents)
            .apply { if (search) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))) }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        // Google's free tier often refuses web search ("Grounding with Google Search") with 429 while plain questions
        // still work: then the question is asked again without searching, and the answer says so
        var note: String? = null
        val r = try {
            post(url, mapOf("x-goog-api-key" to key), body(true))
        } catch (e: AiError) {
            if (e.code != 429) throw e
            note = "Not checked on the web: your Gemini key's free tier refused web search"
            post(url, mapOf("x-goog-api-key" to key), body(false))
        }
        val cand = r.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiError(r.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "The question was refused ($it)" } ?: "No answer came back")
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until parts.length()) parts.optJSONObject(i)?.let { p -> if (!p.optBoolean("thought")) text.append(p.optString("text")) }
        if (text.isBlank()) throw AiError("No answer came back")
        val sources = ArrayList<Source>()
        cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { ch ->
            for (i in 0 until ch.length()) ch.optJSONObject(i)?.optJSONObject("web")?.let { w ->
                val u = w.optString("uri")
                if (u.startsWith("http")) sources += Source(w.optString("title").ifBlank { hostOf(u) }, u)
            }
        }
        return parse(text.toString(), sources).copy(note = note)
    }

    private fun hostOf(u: String) = runCatching { java.net.URI(u).host?.removePrefix("www.") }.getOrNull() ?: u
}
