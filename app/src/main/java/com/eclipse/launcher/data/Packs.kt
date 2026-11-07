package com.eclipse.launcher.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Icon packs and backdrop packs: a .zip of pictures named after systems (snes.png, psx.jpg, megadrive.webp …),
 * in any folders. Each pack is kept on its own, so you can mix, say, one pack's icons with another pack's backdrops,
 * whatever theme you use. Kinds: [ICONS] (platform cards) and [BACKDROPS] (system backgrounds).
 */
object Packs {
    const val ICONS = "icons"
    const val BACKDROPS = "backdrops"

    data class Pack(val id: String, val kind: String, val name: String, val dir: File, val images: Map<String, File>)

    private val imageExts = setOf("png", "jpg", "jpeg", "webp", "gif")
    private const val MAX_BYTES = 600L * 1024 * 1024

    /** Eclipse/Icon packs or Eclipse/Backdrop packs on your storage (see [EclipseHome]). */
    fun root(ctx: Context, kind: String) = EclipseHome.packs(ctx, kind)

    fun list(ctx: Context, kind: String): List<Pack> =
        root(ctx, kind).listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it, kind) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun load(dir: File, kind: String): Pack? {
        val images = HashMap<String, File>()
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.forEach { f ->
            // Eclipse's system names, or the folder names other frontends use (so renamed files still work)
            ThemeConvert.system(f.nameWithoutExtension)?.let { images.putIfAbsent(it, f) }
        }
        if (images.isEmpty()) return null
        val name = runCatching { JSONObject(File(dir, "pack.json").readText()).optString("name") }.getOrNull()?.ifBlank { null } ?: dir.name
        return Pack(dir.name, kind, name, dir, images)
    }

    /**
     * A zip unpacked and waiting for you to choose which of its folders becomes the pack (see [importFinish]).
     * [folders]: every folder holding pictures named after systems, most systems first.
     */
    class Pending(val tmp: File, val name: String, val kind: String, val folders: List<ThemeConvert.FolderChoice>)

    sealed class Step {
        class Done(val pack: Pack) : Step()
        class Failed(val message: String) : Step()
        class Choose(val pending: Pending) : Step()
    }

    /**
     * Unpacks a zip and looks for folders of pictures named after systems (snes.png, psx.jpg …), the same way the
     * theme import does. One such folder: imported straight away. Several (a theme from another frontend, say, with
     * icons, logos and backdrops side by side): [Step.Choose], so you can pick the folder and whether it's icons or
     * backdrops.
     */
    fun importStart(ctx: Context, uri: Uri, kind: String): Step {
        val tmp = File(root(ctx, kind), ".import_${System.currentTimeMillis()}").apply { mkdirs() }
        var keep = false
        try {
            var total = 0L
            val input = ctx.contentResolver.openInputStream(uri) ?: return Step.Failed("Couldn't open that file")
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    if (name.startsWith("__MACOSX/") || name.substringAfterLast('/').startsWith(".")) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in imageExts && ext != "svg") continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw IllegalStateException("too big")
                            os.write(buf, 0, n)
                        }
                    }
                }
            }
            val folders = scan(tmp)
            if (folders.isEmpty()) return Step.Failed("No pictures named after systems in that zip (like snes.png or psx.jpg)")
            val name = Themes.displayName(ctx, uri)
            if (folders.size == 1) return finish(ctx, tmp, name, folders[0], kind)
            keep = true
            return Step.Choose(Pending(tmp, name, kind, folders))
        } catch (e: IllegalStateException) {
            return Step.Failed("That pack is too big (over 600 MB)")
        } catch (e: Exception) {
            return Step.Failed("Couldn't read that zip: ${e.message ?: "not a zip file"}")
        } finally {
            if (!keep) tmp.deleteRecursively()
        }
    }

    /** Every folder in [src] with pictures named after systems (one is enough), the fullest first. */
    private fun scan(src: File): List<ThemeConvert.FolderChoice> {
        ThemeConvert.rasteriseSvgs(src) // SVG logos / icons become PNGs
        // the theme import's guesses (icons / backdrops / logos), for folders it knows about
        val guesses = runCatching { ThemeConvert.scanFolders(src) }.getOrDefault(emptyList()).associate { it.dir.canonicalPath to it.guess }
        return src.walkTopDown().filter { it.isDirectory && !it.name.startsWith(".") }.mapNotNull { d ->
            val m = LinkedHashMap<String, File>()
            d.listFiles()?.filter { it.isFile && it.extension.lowercase() in imageExts }?.sortedBy { it.name.lowercase() }?.forEach { f ->
                ThemeConvert.system(f.nameWithoutExtension)?.let { id -> m.putIfAbsent(id, f) }
            }
            if (m.isEmpty()) null
            else ThemeConvert.FolderChoice(d.relativeTo(src).path.ifEmpty { "(top of the zip)" }, d, m, guesses[d.canonicalPath] ?: ThemeConvert.SKIP)
        }.sortedByDescending { it.files.size }.toList()
    }

    /** Imports [folder] of a [Pending] zip as a pack of [kind] (ICONS or BACKDROPS). */
    fun importFinish(ctx: Context, p: Pending, folder: ThemeConvert.FolderChoice, kind: String): Step = try {
        finish(ctx, p.tmp, p.name, folder, kind)
    } catch (e: Exception) {
        Step.Failed("Couldn't import that pack: ${e.message ?: e.javaClass.simpleName}")
    } finally {
        p.tmp.deleteRecursively()
    }

    fun importCancel(p: Pending) { p.tmp.deleteRecursively() }

    private fun finish(ctx: Context, tmp: File, zipName: String, folder: ThemeConvert.FolderChoice, kind: String): Step {
        // a zip with several folders: name the pack after the folder too ("Art Book - icons")
        val name = if (folder.rel == "(top of the zip)" || folder.rel.isBlank()) zipName else "$zipName - ${folder.dir.name}"
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
        val dest = File(root(ctx, kind), id)
        dest.deleteRecursively() // importing the same pack again replaces it
        dest.mkdirs()
        for ((sys, f) in folder.files) f.copyTo(File(dest, "$sys.${f.extension.lowercase()}"), overwrite = true)
        File(dest, "pack.json").writeText(JSONObject().put("name", name).toString())
        return load(dest, kind)?.let { Step.Done(it) } ?: Step.Failed("Nothing usable in that folder")
    }

    fun delete(p: Pack) { p.dir.deleteRecursively() }
}
