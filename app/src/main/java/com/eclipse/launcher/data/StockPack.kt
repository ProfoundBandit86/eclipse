package com.eclipse.launcher.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * ColorGrade, by grvceless: the icon pack that comes with Eclipse (assets/iconpacks/colorgrade, system icons only,
 * one file per Eclipse system id). Written out as an ordinary pack in the icon packs folder so it shows in
 * Look & Feel → Icons like any other, and is what Eclipse uses unless you pick another.
 * Written again when [VERSION] changes or files are missing.
 */
object StockPack {
    /** the old Stock pack (Eclipse's own icons), removed */
    const val ID = "stock"
    const val COLORGRADE = "colorgrade-eclipse"
    private const val ASSETS = "iconpacks/colorgrade"
    private const val VERSION = 2

    fun install(ctx: Context) {
        val root = Packs.root(ctx, Packs.ICONS)
        runCatching { File(root, ID).deleteRecursively() }
        val dir = File(root, COLORGRADE)
        val files = runCatching { ctx.assets.list(ASSETS)?.toList() }.getOrNull().orEmpty()
        if (files.isEmpty()) return
        val current = runCatching { JSONObject(File(dir, "pack.json").readText()).optInt("version") }.getOrDefault(0)
        if (current == VERSION && files.all { File(dir, it).isFile }) return
        dir.mkdirs()
        for (f in files) {
            runCatching { ctx.assets.open("$ASSETS/$f").use { i -> File(dir, f).outputStream().use { o -> i.copyTo(o) } } }
        }
        runCatching {
            File(dir, "pack.json").writeText(JSONObject().put("name", "ColorGrade").put("author", "grvceless").put("version", VERSION).toString())
        }
    }
}
