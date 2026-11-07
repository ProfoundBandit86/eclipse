package com.eclipse.launcher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Missing disc / cartridge and 3D box pictures, drawn from a game's box art (Settings → Scrape → Fill missing…, and
 * Settings → Tools). Same look as the approved mock-up:
 *  - a disc: the cover cut into a disc (title at the top, the system's banner along the box top left off), a silver hub,
 *    clear rings, a rim and a soft sheen;
 *  - a cartridge: the system's shell with the cover on its label (N64: the cart's own shape, in the shell colour most of
 *    your N64 carts share, else grey; Switch game card; DS / 3DS card);
 *  - a 3D box: the front tilted, a spine in the case colour with the system and the title.
 * Everything returns a transparent ARGB bitmap; nothing here touches the network or the library.
 */
object ArtMaker {
    enum class Shape { DISC, N64, SWITCH, DS }

    private val DISC = setOf(
        "ps1", "ps2", "ps3", "saturn", "dreamcast", "gc", "wii", "wiiu", "scd", "mcd", "pccd", "tgcd", "3do", "ngcd",
        "jagcd", "xbox", "xbox360", "pcfx", "cdi", "triforce"
    )

    /** What kind of disc / cartridge picture a system gets (null: none, the system has no template). */
    fun mediaShape(platform: String): Shape? = when {
        platform in DISC -> Shape.DISC
        platform == "n64" -> Shape.N64
        platform == "switch" -> Shape.SWITCH
        platform == "nds" || platform == "3ds" -> Shape.DS
        else -> null
    }

    /** How much of the box's top is the system's banner (not printed on the disc). */
    private fun bannerTrim(platform: String): Float = when (platform) {
        "ps2" -> 0.075f
        "gc" -> 0.06f
        "wii", "wiiu", "ps3", "xbox", "xbox360" -> 0.08f
        else -> 0f
    }

    /** The case's spine colour for systems with a standard case; null: taken from the cover. */
    private fun caseColor(platform: String): Int? = when (platform) {
        "ps2", "ps3", "gc", "xbox", "xbox360", "psp", "dreamcast", "saturn", "3do", "ps1" -> Color.rgb(14, 14, 16)
        "wii", "wiiu", "switch", "nds", "3ds" -> Color.rgb(238, 238, 240)
        else -> null
    }

    const val GREY = 0xFF848692.toInt() // standard N64 grey (132, 134, 146)

    // ------------------------------------------------------------------------------------------------ loading

    /** A picture file, decoded at about [maxSide] on its longest side. */
    fun decode(file: File, maxSide: Int = 900): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }.getOrNull()

    // ------------------------------------------------------------------------------------------------ helpers

    /** [src] cropped to w:h around [bias] (0 top .. 1 bottom) and drawn into [dst]. */
    private fun drawCrop(c: Canvas, src: Bitmap, dst: RectF, bias: Float, paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)) {
        val r = dst.width() / dst.height()
        val iw = src.width.toFloat(); val ih = src.height.toFloat()
        val s: Rect = if (iw / ih > r) {
            val nw = ih * r; val x = (iw - nw) / 2
            Rect(x.toInt(), 0, (x + nw).toInt(), ih.toInt())
        } else {
            val nh = iw / r; val y = (ih - nh) * bias
            Rect(0, y.toInt(), iw.toInt(), (y + nh).toInt())
        }
        c.drawBitmap(src, s, dst, paint)
    }

    /** The whole cover fitted into [dst], over a blurred, darker copy filling the rest. */
    private fun drawFitFill(c: Canvas, src: Bitmap, dst: RectF) {
        // (a cheap blur: shrink a lot, grow back with filtering)
        val tw = max(4, (dst.width() / 16).toInt()); val th = max(4, (dst.height() / 16).toInt())
        val tiny = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        drawCrop(Canvas(tiny), src, RectF(0f, 0f, tw.toFloat(), th.toFloat()), 0.5f)
        c.drawBitmap(tiny, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        c.drawRect(dst, Paint().apply { color = Color.argb(64, 0, 0, 0) })
        val s = min(dst.width() / src.width, dst.height() / src.height)
        val w = src.width * s; val h = src.height * s
        val l = dst.centerX() - w / 2; val t = dst.centerY() - h / 2
        c.drawBitmap(src, null, RectF(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun shade(color: Int, d: Int, alpha: Int = 255): Int =
        Color.argb(alpha, (Color.red(color) + d).coerceIn(0, 255), (Color.green(color) + d).coerceIn(0, 255), (Color.blue(color) + d).coerceIn(0, 255))

    private fun white(a: Int) = Color.argb(a, 255, 255, 255)

    /** The cover's main colour (saturated, mid-bright pixels; most common bucket), for a spine's accent. */
    private fun mainColor(src: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(src, 24, 24, true)
        val counts = HashMap<Int, Int>()
        var fallbackR = 0L; var fallbackG = 0L; var fallbackB = 0L
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            fallbackR += r; fallbackG += g; fallbackB += b
            val lum = (r + g + b) / 3
            val sat = max(r, max(g, b)) - min(r, min(g, b))
            if (lum in 41..219 && sat > 30) {
                val key = Color.rgb(r / 24 * 24 + 12, g / 24 * 24 + 12, b / 24 * 24 + 12)
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts.maxByOrNull { it.value }?.key
            ?: Color.rgb((fallbackR / 576).toInt(), (fallbackG / 576).toInt(), (fallbackB / 576).toInt())
    }

    /**
     * The shell colour most of a system's carts share: each real cart picture's wing (left side, upper middle) is
     * sampled, the samples are grouped, and the biggest group wins, so one green special edition doesn't decide it.
     * [GREY] when there's nothing to go on.
     */
    fun shellColor(files: List<File>): Int {
        val samples = files.mapNotNull { f -> decode(f, 120)?.let { wingSample(it) } }
        if (samples.isEmpty()) return GREY
        val groups = samples.groupBy { Triple(Color.red(it) / 28, Color.green(it) / 28, Color.blue(it) / 28) }
        val best = groups.values.maxByOrNull { it.size } ?: return GREY
        return Color.rgb(best.map { Color.red(it) }.average().toInt(), best.map { Color.green(it) }.average().toInt(), best.map { Color.blue(it) }.average().toInt())
    }

    private fun wingSample(b: Bitmap): Int? {
        // the cart's bounds (its opaque pixels; the whole picture if it has no transparency)
        var x0 = b.width; var y0 = b.height; var x1 = -1; var y1 = -1
        for (y in 0 until b.height) for (x in 0 until b.width) if (Color.alpha(b.getPixel(x, y)) > 200) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        val w = x1 - x0; val h = y1 - y0
        if (w < 10 || h < 10) return null
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in (y0 + h * 0.30).toInt()..(y0 + h * 0.60).toInt()) for (x in (x0 + w * 0.06).toInt()..(x0 + w * 0.18).toInt()) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            rs += Color.red(p); gs += Color.green(p); bs += Color.blue(p)
        }
        if (rs.isEmpty()) return null
        rs.sort(); gs.sort(); bs.sort()
        return Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
    }

    // ------------------------------------------------------------------------------------------------ disc

    fun disc(cover: Bitmap, platform: String, size: Int = 512): Bitmap {
        val s = size.toFloat(); val r = s / 2; val cx = r; val cy = r
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val trim = bannerTrim(platform)
        val art = if (trim > 0f) Bitmap.createBitmap(cover, 0, (cover.height * trim).toInt(), cover.width, cover.height - (cover.height * trim).toInt()) else cover
        // the label: the cover between the clear inner ring and the rim, title at the top
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, r * 0.985f, Path.Direction.CW) })
        c.clipOutPath(Path().apply { addCircle(cx, cy, r * 0.335f, Path.Direction.CW) })
        drawCrop(c, art, RectF(0f, 0f, s, s), 0f)
        c.restore()
        // the silver hub (light and dark twice round, like brushed metal)
        val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = r * 0.185f
            shader = SweepGradient(cx, cy, intArrayOf(Color.rgb(230, 230, 236), Color.rgb(150, 150, 156), Color.rgb(230, 230, 236), Color.rgb(150, 150, 156), Color.rgb(230, 230, 236)), null)
        }
        c.drawCircle(cx, cy, r * 0.2425f, hub)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.rgb(225, 225, 225) }
        ring.strokeWidth = r * 0.04f; c.drawCircle(cx, cy, r * 0.17f, ring)
        ring.strokeWidth = r * 0.015f; c.drawCircle(cx, cy, r * 0.3275f, ring)
        // the rim
        ring.color = Color.rgb(200, 200, 204); ring.strokeWidth = r * 0.035f; c.drawCircle(cx, cy, r * 0.9825f, ring)
        // a soft diagonal sheen across the disc (not the hole)
        c.save()
        c.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        c.clipOutPath(Path().apply { addCircle(cx, cy, r * 0.15f, Path.Direction.CW) })
        c.drawRect(0f, 0f, s, s, Paint().apply {
            shader = LinearGradient(0f, 0f, s, s, intArrayOf(0, white(0), white(60), white(0), 0, white(30), white(0)),
                floatArrayOf(0f, 0.12f, 0.2f, 0.3f, 0.6f, 0.68f, 0.76f), Shader.TileMode.CLAMP)
        })
        c.restore()
        c.drawCircle(cx, cy, r - 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = white(90) })
        return out
    }

    // ------------------------------------------------------------------------------------------------ 3D box

    fun box3d(cover: Bitmap, platform: String, systemName: String, title: String, height: Int = 640): Bitmap {
        val h = height.toFloat()
        val fw = h * 0.70f
        val sp = fw * 0.20f
        val w = sp + fw
        val k = h * 0.035f
        val out = Bitmap.createBitmap((w + 2).toInt(), height + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // the spine, drawn upright (text running down it), then fitted to its slanted quad
        val sw = (h * 0.11f).toInt()
        val spine = Bitmap.createBitmap(sw, height, Bitmap.Config.ARGB_8888)
        val sc = Canvas(spine)
        val case = caseColor(platform) ?: shade(mainColor(cover), -70)
        sc.drawColor(case)
        val dark = (Color.red(case) + Color.green(case) + Color.blue(case)) / 3 < 128
        val ink = if (dark) Color.rgb(236, 236, 240) else Color.rgb(24, 24, 28)
        sc.save()
        sc.rotate(90f); sc.translate(0f, -sw.toFloat()) // now x runs down the spine, y across it
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = ink }
        tp.textSize = sw * 0.34f
        val mid = sw / 2f - (tp.descent() + tp.ascent()) / 2
        sc.drawText(fitText(systemName, tp, h * 0.30f), sw * 0.5f, mid, tp)
        sc.drawRect(h * 0.355f, sw * 0.14f, h * 0.37f, sw * 0.86f, Paint().apply { color = mainColor(cover) })
        tp.textSize = sw * 0.46f
        val mid2 = sw / 2f - (tp.descent() + tp.ascent()) / 2
        sc.drawText(fitText(title.uppercase(), tp, h * 0.56f), h * 0.40f, mid2, tp)
        sc.restore()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun warp(bmp: Bitmap, dst: FloatArray) {
            val m = Matrix()
            m.setPolyToPoly(floatArrayOf(0f, 0f, bmp.width.toFloat(), 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0f, bmp.height.toFloat()), 0, dst, 0, 4)
            c.drawBitmap(bmp, m, paint)
        }
        val sq = floatArrayOf(0f, k, sp, 0f, sp, h, 0f, h - k)
        warp(spine, sq)
        c.drawPath(quad(sq), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }) // it faces away from the light
        // the front, turned slightly away
        val front = Bitmap.createBitmap(fw.toInt(), height, Bitmap.Config.ARGB_8888)
        drawCrop(Canvas(front), cover, RectF(0f, 0f, fw, h), 0f)
        val fq = floatArrayOf(sp, 0f, w, k * 0.55f, w, h - k * 0.55f, sp, h)
        warp(front, fq)
        // light on the front near the spine, and the corner's highlight
        c.save(); c.clipPath(quad(fq))
        c.drawRect(sp, 0f, w, h, Paint().apply { shader = LinearGradient(sp, 0f, sp + fw / 3, 0f, white(40), white(0), Shader.TileMode.CLAMP) })
        c.restore()
        c.drawLine(sp, 0f, sp, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(120); strokeWidth = 2f })
        return out
    }

    private fun quad(p: FloatArray) = Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]); lineTo(p[6], p[7]); close() }

    private fun fitText(t: String, p: Paint, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var s = t
        while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1)
        return "$s…"
    }

    // ------------------------------------------------------------------------------------------------ cartridges

    fun cart(cover: Bitmap, shape: Shape, platform: String, shell: Int = GREY): Bitmap = when (shape) {
        Shape.N64 -> n64(cover, shell)
        Shape.SWITCH -> switchCard(cover)
        Shape.DS -> dsCard(cover, if (platform == "3ds") "NINTENDO 3DS" else "NINTENDO DS")
        Shape.DISC -> disc(cover, platform)
    }

    /** A filled shell on its own layer: later paints with [atop] only land on the shell. */
    private val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)

    private fun plastic(c: Canvas, w: Float, h: Float, outline: Path, u: Float) {
        // light from above, darker towards the bottom
        c.drawRect(0f, 0f, w, h, Paint().apply {
            xfermode = atop; shader = LinearGradient(0f, 0f, 0f, h, white(60), white(0), Shader.TileMode.CLAMP)
        })
        // a soft darker inner edge
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = atop; style = Paint.Style.STROKE; strokeWidth = 10f * u; color = Color.argb(60, 0, 0, 0)
            pathEffect = CornerPathEffect(6f * u)
        })
    }

    private fun label(c: Canvas, cover: Bitmap, box: RectF, radius: Float, bias: Float, border: Float = 0f, fit: Boolean = false) {
        if (border > 0f) {
            val b = RectF(box.left - border, box.top - border, box.right + border, box.bottom + border)
            c.drawRoundRect(b, radius + border, radius + border, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 245, 245) })
        }
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) })
        if (fit) drawFitFill(c, cover, box) else drawCrop(c, cover, box, bias)
        // printed-on: a faint sheen across the top-left of the label
        c.drawRect(box, Paint().apply {
            shader = LinearGradient(box.left, box.top, box.left + box.width() * 0.6f, box.top + box.height() * 0.5f, white(34), white(0), Shader.TileMode.CLAMP)
        })
        c.restore()
    }

    /** The N64 cart from the front: two wings and a raised centre panel under one arched top, as on the real carts. */
    private fun n64(cover: Bitmap, shell: Int, width: Int = 600): Bitmap {
        val w = width.toFloat(); val h = w * 0.62f; val u = w / 560f
        val out = Bitmap.createBitmap(width, h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        fun arc(x: Float) = (0.02f + 0.55f * (x - 0.5f).pow(2)) * h
        fun top(x: Float) = arc(x) + if (x < 0.215f || x > 0.785f) 0.014f * h else 0f
        val outline = Path().apply {
            moveTo(0.03f * w, h)
            var x = 0.03f
            while (x <= 0.97f) { lineTo(x * w, top(x)); x += 0.005f }
            lineTo(0.97f * w, top(0.97f)); lineTo(0.97f * w, h); close()
        }
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shell; pathEffect = CornerPathEffect(w * 0.022f) })
        // the wings' curved plastic: lighter towards the centre panel
        for ((x0, x1, flip) in listOf(Triple(0.03f, 0.215f, false), Triple(0.785f, 0.97f, true))) {
            val a = if (flip) x1 * w else x0 * w; val b = if (flip) x0 * w else x1 * w
            c.drawRect(x0 * w, 0f, x1 * w, h, Paint().apply {
                xfermode = atop
                shader = LinearGradient(a, 0f, b, 0f, intArrayOf(white(0), white(35), white(3)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
        }
        plastic(c, w, h, outline, u)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = atop; strokeCap = Paint.Cap.ROUND }
        // the grooves between the wings and the centre panel
        for (gx in listOf(0.215f, 0.785f)) {
            line.color = shade(shell, -60, 230); line.strokeWidth = 3.5f * u
            c.drawLine(gx * w, top(gx) + 2 * u, gx * w, h - 3 * u, line)
            val off = (if (gx < 0.5f) 3f else -3f) * u
            line.color = white(40); line.strokeWidth = 2f * u
            c.drawLine(gx * w + off, top(gx) + 6 * u, gx * w + off, h - 6 * u, line)
        }
        // the notch low on each wing's outer edge
        for ((x0, x1, left) in listOf(Triple(0.03f, 0.075f, true), Triple(0.925f, 0.97f, false))) {
            c.drawRoundRect(RectF(x0 * w, 0.68f * h, x1 * w, 0.99f * h), w * 0.02f, w * 0.02f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = atop; color = shade(shell, 12, 150) })
            val ex = if (left) x1 * w else x0 * w
            line.color = shade(shell, -40, 170); line.strokeWidth = 2f * u
            c.drawLine(ex, 0.70f * h, ex, 0.97f * h, line)
        }
        // a thin light line along the arched top
        val arcLine = Path().apply { var x = 0.06f; moveTo(x * w, top(x) + 2 * u); while (x < 0.94f) { x += 0.01f; lineTo(x * w, top(x) + 2 * u) } }
        c.drawPath(arcLine, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = atop; style = Paint.Style.STROKE; color = white(45); strokeWidth = 2f * u })
        // the label, set into the centre panel
        val lb = RectF(0.275f * w, 0.13f * h, 0.725f * w, 0.89f * h)
        c.drawRoundRect(RectF(lb.left - 3 * u, lb.top - 3 * u, lb.right + 3 * u, lb.bottom + 3 * u), 13 * u, 13 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shade(shell, -25) })
        label(c, cover, lb, 11 * u, 0.1f)
        return out
    }

    /** A Switch game card: dark plastic, the cut top-right corner, a grip at the top, the cover on the label. */
    private fun switchCard(cover: Bitmap, width: Int = 320): Bitmap {
        val w = width.toFloat(); val h = w * 1.38f; val u = w / 300f
        val out = Bitmap.createBitmap(width, h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cut = w * 0.16f
        val outline = Path().apply { addRoundRect(RectF(0f, 0f, w, h), w * 0.07f, w * 0.07f, Path.Direction.CW) }
        outline.op(Path().apply { moveTo(w - cut, 0f); lineTo(w, 0f); lineTo(w, cut); close() }, Path.Op.DIFFERENCE)
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 36, 40) })
        plastic(c, w, h, outline, u)
        val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = atop; color = white(28); strokeWidth = 2f * u }
        for (i in 0 until 5) { val y = h * (0.035f + i * 0.022f); c.drawLine(w * 0.08f, y, w * 0.62f, y, grip) }
        label(c, cover, RectF(w * 0.08f, h * 0.17f, w * 0.92f, h * 0.95f), 8 * u, 0.05f)
        return out
    }

    /** A DS / 3DS card: grey plastic, the side notch, ridges and the name, the whole cover on the label. */
    private fun dsCard(cover: Bitmap, name: String, width: Int = 340): Bitmap {
        val w = width.toFloat(); val h = w * 0.93f; val u = w / 330f
        val out = Bitmap.createBitmap(width, h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val outline = Path().apply { addRoundRect(RectF(0f, 0f, w, h), w * 0.05f, w * 0.05f, Path.Direction.CW) }
        outline.op(Path().apply { addRect(0f, h * 0.18f, w * 0.03f, h * 0.30f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(78, 80, 86) })
        plastic(c, w, h, outline, u)
        val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = atop; color = white(30); strokeWidth = 2f * u }
        for (i in 0 until 4) { val y = h * (0.04f + i * 0.03f); c.drawLine(w * 0.12f, y, w * 0.88f, y, ridge) }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.argb(210, 225, 225, 230); textSize = h * 0.045f; textAlign = Paint.Align.CENTER }
        c.drawText(name, w * 0.5f, h * 0.19f - (tp.descent() + tp.ascent()) / 2, tp)
        label(c, cover, RectF(w * 0.10f, h * 0.25f, w * 0.90f, h * 0.94f), 6 * u, 0.12f, border = 4 * u, fit = true)
        return out
    }

    /** True for a file this made (named sc_gen_…): real art from a source may replace it. */
    fun isGenerated(uri: String?): Boolean = uri != null && uri.substringAfterLast('/').startsWith("sc_gen_")
}
