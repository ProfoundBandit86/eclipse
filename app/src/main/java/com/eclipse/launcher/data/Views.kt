package com.eclipse.launcher.data

import org.json.JSONObject
import java.io.File

/**
 * View types you make yourself: a `view.json` in /storage/emulated/0/Eclipse/views/ (one file each, any name, or
 * `views/<name>/view.json`). Each says where every game's cover goes with small formulas, and what else is drawn
 * (backdrop, title …). They show in a system's options drawer → View, next to the built-in views. See VIEWS.md.
 */
object Views {

    /** One slot: something drawn besides the covers (the backdrop, the highlighted game's title …). */
    class Slot(
        val kind: String, val x: Expr?, val y: Expr?, val size: Float, val align: String,
        val blur: Float, val dim: Float, val color: Long?
    )

    class Spec(
        val id: String, val name: String, val file: File,
        val cardW: Expr, val cardH: Expr,
        val x: Expr, val y: Expr, val scale: Expr, val rotateY: Expr, val rotateX: Expr, val alpha: Expr, val z: Expr,
        /** how many games Up / Down move (0: Up / Down leave the view, like slide view) */
        val step: Int,
        /** covers further than this from the highlighted one aren't drawn */
        val window: Int,
        /** "box" (the system's art style, like the other views), "backdrop" or "logo" */
        val art: String,
        val corner: Float,
        val slots: List<Slot>,
        /** where it's offered: "games" (a system's games), "recent" (Recently Played), "systems" (Home's systems row) */
        val screens: Set<String> = setOf("games")
    ) {
        fun on(screen: String) = screen in screens
    }

    /** The screens a view can be made for, and what they're called. */
    val SCREENS = listOf("games" to "Games", "recent" to "Recently Played", "systems" to "Systems")

    /** What went wrong reading each file (shown in the View list as the view's name, and in VIEWS.md's tips). */
    val errors = HashMap<String, String>()

    fun dir(): File = File(EclipseHome.dir(), "views")

    private fun files(): List<File> {
        val d = dir()
        val top = d.listFiles()?.filter { it.isFile && it.extension.equals("json", true) } ?: emptyList()
        val sub = d.listFiles()?.filter { it.isDirectory }?.mapNotNull { f -> File(f, "view.json").takeIf { it.isFile } } ?: emptyList()
        return (top + sub).sortedBy { it.path.lowercase() }
    }

    /** A number that changes when any view file does (the games screen re-reads them then). */
    fun signature(): Long = files().fold(17L) { acc, f -> acc * 31 + f.lastModified() + f.length() + f.path.hashCode() }

    /** Every view you've made that reads correctly. */
    fun list(): List<Spec> {
        errors.clear()
        return files().mapNotNull { f ->
            try { parse(f) } catch (e: Exception) { errors[f.name] = e.message ?: e.javaClass.simpleName; null }
        }.distinctBy { it.id }
    }

    fun byId(id: String): Spec? = list().firstOrNull { it.id == id }

    /** Reads [f] as a view: null when it's fine, else what's wrong (used before installing one). */
    fun check(f: File): String? = try { parse(f); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    /** The id a file would get (to tell whether an install replaces a view you already have). */
    fun idOf(f: File): String? = runCatching { parse(f).id }.getOrNull()

    private fun parse(f: File): Spec {
        val o = JSONObject(f.readText())
        val id = o.optString("id").ifBlank { (if (f.name == "view.json") f.parentFile?.name else f.nameWithoutExtension) ?: "view" }
        val card = o.optJSONObject("card") ?: JSONObject()
        val t = o.optJSONObject("transform") ?: JSONObject()
        fun e(src: JSONObject, k: String, def: String) = Expr.parse(src.opt(k)?.toString()?.ifBlank { null } ?: def)
        val slots = ArrayList<Slot>()
        o.optJSONArray("slots")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                slots += Slot(
                    s.optString("kind"),
                    s.opt("x")?.toString()?.let(Expr::parse), s.opt("y")?.toString()?.let(Expr::parse),
                    s.optDouble("size", 18.0).toFloat(), s.optString("align", "left"),
                    s.optDouble("blur", 0.0).toFloat(), s.optDouble("dim", 0.0).toFloat(),
                    s.optString("color").takeIf { it.startsWith("#") }?.let { c -> runCatching { java.lang.Long.parseLong(c.drop(1).let { if (it.length == 6) "FF$it" else it }, 16) }.getOrNull() }
                )
            }
        }
        return Spec(
            id, o.optString("name").ifBlank { id }, f,
            e(card, "width", "h*0.42*0.75"), e(card, "height", "h*0.42"),
            e(t, "x", "d*w*0.16"), e(t, "y", "0"), e(t, "scale", "1"), e(t, "rotateY", "0"), e(t, "rotateX", "0"),
            e(t, "alpha", "1"), e(t, "z", "-abs(d)"),
            o.optInt("step", 0).coerceAtLeast(0), o.optInt("window", 8).coerceIn(1, 60),
            o.optString("art", "box"), o.optDouble("corner", 8.0).toFloat(), slots,
            screensOf(o)
        )
    }

    /** "screens": ["games", "recent", "systems"] (or one word); left out = games only. Unknown names are an error. */
    private fun screensOf(o: JSONObject): Set<String> {
        val raw = o.opt("screens") ?: return setOf("games")
        val names = when (raw) {
            is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> listOf(raw.toString())
        }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val known = SCREENS.map { it.first }.toSet()
        names.firstOrNull { it !in known }?.let { throw IllegalArgumentException("\"screens\" can't hold \"$it\": use games, recent or systems") }
        return names.toSet().ifEmpty { setOf("games") }
    }
}

/**
 * A small formula: numbers, the names a view gets (d, i, s, n, w, h), + - * / %, parentheses, and the functions
 * min, max, abs, clamp, floor, ceil, round, sqrt, pow, sin, cos, sign. Parsed once, then evaluated per cover.
 */
class Expr private constructor(private val f: (Map<String, Float>) -> Float, val source: String) {
    fun eval(vars: Map<String, Float>): Float = f(vars).let { if (it.isNaN() || it.isInfinite()) 0f else it }

    companion object {
        fun parse(src: String): Expr = Expr(Parser(src).parseAll(), src)
    }

    private class Parser(val s: String) {
        var p = 0
        fun parseAll(): (Map<String, Float>) -> Float {
            val e = sum()
            skip()
            if (p < s.length) throw IllegalArgumentException("Didn't understand \"${s.substring(p)}\" in \"$s\"")
            return e
        }
        fun skip() { while (p < s.length && s[p].isWhitespace()) p++ }
        fun eat(c: Char): Boolean { skip(); return if (p < s.length && s[p] == c) { p++; true } else false }

        fun sum(): (Map<String, Float>) -> Float {
            var l = product()
            while (true) {
                l = when {
                    eat('+') -> { val a = l; val b = product(); { v -> a(v) + b(v) } }
                    eat('-') -> { val a = l; val b = product(); { v -> a(v) - b(v) } }
                    else -> return l
                }
            }
        }
        fun product(): (Map<String, Float>) -> Float {
            var l = unary()
            while (true) {
                l = when {
                    eat('*') -> { val a = l; val b = unary(); { v -> a(v) * b(v) } }
                    eat('/') -> { val a = l; val b = unary(); { v -> b(v).let { d -> if (d == 0f) 0f else a(v) / d } } }
                    eat('%') -> { val a = l; val b = unary(); { v -> b(v).let { d -> if (d == 0f) 0f else ((a(v) % d) + d) % d } } }
                    else -> return l
                }
            }
        }
        fun unary(): (Map<String, Float>) -> Float =
            if (eat('-')) { val a = unary(); { v -> -a(v) } } else if (eat('+')) unary() else atom()

        fun atom(): (Map<String, Float>) -> Float {
            skip()
            if (eat('(')) { val e = sum(); if (!eat(')')) throw IllegalArgumentException("Missing ) in \"$s\""); return e }
            val start = p
            if (p < s.length && (s[p].isDigit() || s[p] == '.')) {
                while (p < s.length && (s[p].isDigit() || s[p] == '.')) p++
                val n = s.substring(start, p).toFloatOrNull() ?: throw IllegalArgumentException("Bad number in \"$s\"")
                return { _ -> n }
            }
            while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_')) p++
            val name = s.substring(start, p)
            if (name.isEmpty()) throw IllegalArgumentException("Expected a number or a name in \"$s\"")
            if (eat('(')) {
                val args = ArrayList<(Map<String, Float>) -> Float>()
                if (!eat(')')) {
                    do { args += sum() } while (eat(','))
                    if (!eat(')')) throw IllegalArgumentException("Missing ) after $name( in \"$s\"")
                }
                fun need(n: Int) { if (args.size != n) throw IllegalArgumentException("$name needs $n value${if (n == 1) "" else "s"} in \"$s\"") }
                return when (name) {
                    "min" -> { need(2); val (a, b) = args; { v -> minOf(a(v), b(v)) } }
                    "max" -> { need(2); val (a, b) = args; { v -> maxOf(a(v), b(v)) } }
                    "abs" -> { need(1); val a = args[0]; { v -> kotlin.math.abs(a(v)) } }
                    "clamp" -> { need(3); val (a, lo, hi) = args; { v -> a(v).coerceIn(minOf(lo(v), hi(v)), maxOf(lo(v), hi(v))) } }
                    "floor" -> { need(1); val a = args[0]; { v -> kotlin.math.floor(a(v)) } }
                    "ceil" -> { need(1); val a = args[0]; { v -> kotlin.math.ceil(a(v)) } }
                    "round" -> { need(1); val a = args[0]; { v -> kotlin.math.round(a(v)) } }
                    "sqrt" -> { need(1); val a = args[0]; { v -> kotlin.math.sqrt(kotlin.math.max(0f, a(v))) } }
                    "pow" -> { need(2); val (a, b) = args; { v -> Math.pow(a(v).toDouble(), b(v).toDouble()).toFloat() } }
                    "sin" -> { need(1); val a = args[0]; { v -> kotlin.math.sin(Math.toRadians(a(v).toDouble())).toFloat() } }
                    "cos" -> { need(1); val a = args[0]; { v -> kotlin.math.cos(Math.toRadians(a(v).toDouble())).toFloat() } }
                    "sign" -> { need(1); val a = args[0]; { v -> kotlin.math.sign(a(v)) } }
                    else -> throw IllegalArgumentException("Unknown function $name in \"$s\"")
                }
            }
            if (name == "pi") return { _ -> Math.PI.toFloat() }
            return { v -> v[name] ?: throw IllegalArgumentException("Unknown name \"$name\" (use d, i, s, n, w or h)") }
        }
    }
}
