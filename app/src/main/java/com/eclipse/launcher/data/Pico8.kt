package com.eclipse.launcher.data

import android.graphics.Bitmap
import java.io.File

/**
 * PICO-8 carts: tidy titles from their file names, and their own label as box art. A ".p8.png" cart already is a
 * picture of the cartridge; a ".p8" text cart keeps its 128 x 128 label in a "__label__" section (one hex-like
 * character per pixel, 0-9 a-v = PICO-8's 16 colours and its 16 extra ones).
 */
object Pico8 {
    /** PICO-8's palette, then its "secret" second palette (colours 128-143, written g-v in labels). */
    private val PAL = intArrayOf(
        0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
        0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA,
        0x291814, 0x111D35, 0x422136, 0x125359, 0x742F29, 0x49333B, 0xA28879, 0xF3EF7D,
        0xBE1250, 0xFF6C24, 0xA8E72E, 0x00B543, 0x065AB5, 0x754665, 0xFF6E59, 0xFF9D81
    )

    private val VERSION = Regex("""[-_]\d+$""")

    /**
     * "celeste_classic-2.p8.png" → "Celeste Classic". Drops ".png" / ".p8", the BBS's "-2" version number, and capitalises
     * an all-lowercase name. Names that already have capitals are left as they are.
     */
    fun title(fileName: String): String {
        var s = fileName
        if (s.endsWith(".png", true)) s = s.dropLast(4)
        if (s.endsWith(".p8", true)) s = s.dropLast(3)
        s = VERSION.replace(s, "").replace('_', ' ').replace('-', ' ').replace(Regex("\\s{2,}"), " ").trim()
        if (s.isEmpty()) return fileName
        return if (s.any { it.isUpperCase() }) s else s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /**
     * Draws a .p8 cart's label into [out] (512 x 512, each pixel a 4 x 4 block so it stays crisp when scaled).
     * Returns null when the cart has no label (or an empty, all-black one). Reuses [out] if it's newer than the cart.
     */
    fun label(cart: File, out: File, scale: Int = 4): File? {
        if (out.isFile && out.lastModified() >= cart.lastModified()) return out
        val rows = ArrayList<String>(128)
        runCatching {
            cart.bufferedReader().useLines { lines ->
                var inLabel = false
                for (l in lines) {
                    val t = l.trim()
                    if (t.startsWith("__") && t.endsWith("__") && t.length > 4) {
                        if (inLabel) break
                        inLabel = t == "__label__"
                        continue
                    }
                    if (inLabel && t.isNotEmpty()) { rows.add(t); if (rows.size == 128) break }
                }
            }
        }.onFailure { return null }
        if (rows.size < 128) return null
        val w = 128 * scale
        val px = IntArray(w * w)
        var any = false
        for (y in 0 until 128) {
            val row = rows[y]
            for (x in 0 until 128) {
                val c = (row.getOrNull(x)?.digitToIntOrNull(32) ?: 0) and 31
                if (c != 0) any = true
                val argb = (0xFF shl 24) or PAL[c]
                val base = (y * scale) * w + x * scale
                for (dy in 0 until scale) for (dx in 0 until scale) px[base + dy * w + dx] = argb
            }
        }
        if (!any) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            out
        }.getOrNull()
    }
}
