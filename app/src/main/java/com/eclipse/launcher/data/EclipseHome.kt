package com.eclipse.launcher.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * The "Eclipse" folder at the top of your storage (/storage/emulated/0/Eclipse), where every theme, icon pack and
 * backdrop pack lives as ordinary files, so they're easy to find, copy and tweak from any file manager or a computer:
 *
 *   Eclipse/Themes/<theme>/icons/snes.png        a theme's platform icons, named after each system
 *   Eclipse/Themes/<theme>/backdrops/snes.jpg    its system backdrops
 *   Eclipse/Themes/<theme>/wallpaper.jpg …       its wallpapers, dock icons, font and theme.json
 *   Eclipse/Icon packs/<pack>/snes.png           icon packs
 *   Eclipse/Backdrop packs/<pack>/snes.jpg       backdrop packs
 *
 * Edit, replace or add a picture and Eclipse uses it the next time it starts (or after Settings → Appearance →
 * Reload). It needs "All files access" (Settings → General → Permissions); without it everything stays inside Eclipse's own
 * storage as before, and moves here by itself once access is granted.
 */
object EclipseHome {
    fun dir(): File = File(Environment.getExternalStorageDirectory(), "Eclipse")

    /** Eclipse may write to shared storage. */
    fun usable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else dir().let { it.mkdirs(); it.isDirectory && it.canWrite() }
    } catch (e: Exception) { false }

    fun themes(ctx: Context): File = (if (usable()) File(dir(), "Themes") else File(ctx.filesDir, "themes")).apply { mkdirs() }

    fun packs(ctx: Context, kind: String): File =
        (if (usable()) File(dir(), if (kind == Packs.ICONS) "Icon packs" else "Backdrop packs") else File(ctx.filesDir, "packs/$kind")).apply { mkdirs() }

    @Volatile private var done = false

    /**
     * Once access is there: moves themes and packs out of Eclipse's own storage into the Eclipse folder, gives each
     * theme's folders their plain names (icons, backdrops), and writes a README. Safe to call any number of times.
     */
    @Synchronized
    fun setUp(ctx: Context) {
        if (done || !usable()) return
        val home = dir().apply { mkdirs() }
        move(File(ctx.filesDir, "themes"), File(home, "Themes"))
        move(File(ctx.filesDir, "packs/${Packs.ICONS}"), File(home, "Icon packs"))
        move(File(ctx.filesDir, "packs/${Packs.BACKDROPS}"), File(home, "Backdrop packs"))
        File(home, "Themes").listFiles()?.filter { it.isDirectory }?.forEach { t ->
            rename(File(t, "platforms"), File(t, "icons"))
            rename(File(t, "backgrounds"), File(t, "backdrops"))
        }
        // keeps gallery apps from filling up with hundreds of system icons
        runCatching { File(home, ".nomedia").createNewFile() }
        runCatching { File(home, "README.txt").writeText(README) }
        done = true
    }

    /** Every folder in [from] into [to] (a folder already in [to] with the same name is kept, the old copy dropped). */
    private fun move(from: File, to: File) {
        val kids = from.listFiles()?.filter { !it.name.startsWith(".") } ?: return
        to.mkdirs()
        for (k in kids) {
            val dest = File(to, k.name)
            if (!dest.exists()) {
                if (!k.renameTo(dest)) runCatching { k.copyRecursively(dest, overwrite = false) }.onFailure { return }
            }
            k.deleteRecursively()
        }
        from.deleteRecursively()
    }

    private fun rename(from: File, to: File) {
        if (!from.isDirectory) return
        if (!to.exists()) { from.renameTo(to); return }
        from.listFiles()?.forEach { f -> val d = File(to, f.name); if (!d.exists()) f.renameTo(d) }
        from.deleteRecursively()
    }

    private val README = """
        ECLIPSE
        =======
        Everything Eclipse shows for your systems lives here, as ordinary pictures you can open, replace or add to.
        Changes show the next time Eclipse starts, or straight away with Settings > Appearance > Reload images.

        Themes/<theme name>/
          icons/        each system's icon (tile), named after the system: snes.png, psx.jpg, megadrive.webp ...
          backdrops/    each system's backdrop, named the same way: snes.jpg ...
          dock/         dock icons: home.png, games.png, romm.png, apps.png, settings.png
          wallpaper.jpg, bottom.jpg, a font, and theme.json (name, colours, wallpaper settings)

        Icon packs/<pack name>/       icons, one picture per system (snes.png ...)
        Backdrop packs/<pack name>/   backdrops, one picture per system (snes.jpg ...)

        Names: Eclipse's own system names (snes, psx, megadrive, n64, gba ...) and the folder names other frontends
        use both work. Pictures can be .png, .jpg, .webp or .gif. Icons look best square (about 512 x 512),
        backdrops at 1920 x 1080.

        To add a theme or pack by hand, make a new folder here laid out the same way.
    """.trimIndent() + "\n"
}
