package com.eclipse.launcher.data

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import java.io.File
import java.util.Properties

/**
 * Copies games into your RomM library over a network share (SMB / Windows file sharing), the way RomM recommends
 * for big and multi-disc games. RomM then finds them on its next library scan.
 *
 * RomM's library can be laid out two ways; both are handled:
 *   A)  <library>/roms/<platform>/<game files>
 *   B)  <library>/<platform>/roms/<game files>
 * A single-file game goes straight into the platform folder; a game in several files (discs and their playlist, a
 * .cue with its .bins) goes into a folder of its own, named after the game, which is how RomM groups multi-file games.
 */
object SmbUpload {
    data class Target(val host: String, val share: String, val path: String, val user: String, val password: String, val domain: String = "")

    private fun context(t: Target): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        return if (t.user.isBlank()) base.withAnonymousCredentials()
        else base.withCredentials(NtlmPasswordAuthenticator(t.domain, t.user, t.password))
    }

    private fun url(t: Target, rel: String): String {
        val host = t.host.trim().removePrefix("smb://").trimEnd('/')
        val parts = (listOf(t.share) + t.path.split('/') + rel.split('/')).map { it.trim() }.filter { it.isNotEmpty() }
        return "smb://$host/" + parts.joinToString("/") { it } + "/"
    }

    /** Signs in and looks at the library folder: null when it's fine, else what went wrong (for Settings). */
    fun check(t: Target): String? = try {
        val f = SmbFile(url(t, ""), context(t))
        if (!f.exists()) "Connected, but the library folder isn't there" else null
    } catch (e: Exception) { e.message ?: "Couldn't connect" }

    /** One folder (or share) seen while browsing: its name and whether it looks like RomM's library. */
    data class Folder(val name: String, val library: Boolean)
    /** What's in one place on the computer: its folders, and whether that place itself looks like RomM's library. */
    data class Listing(val folders: List<Folder>, val isLibrary: Boolean)

    /**
     * Browses the computer: with [share] blank, its shared folders (hidden ones like C$ and IPC$ left out); otherwise
     * the folders inside [share]/[path]. Throws with a readable message when it can't sign in or the place is gone.
     */
    fun browse(host: String, user: String, password: String, share: String, path: String): Listing {
        val t = Target(host, share, path, user, password)
        val ctx = context(t)
        if (share.isBlank()) {
            val h = host.trim().removePrefix("smb://").trimEnd('/')
            val shares = SmbFile("smb://$h/", ctx).listFiles()
                .map { it.name.trimEnd('/') }
                .filter { it.isNotEmpty() && !it.endsWith("$") }
                .sortedBy { it.lowercase() }
            return Listing(shares.map { Folder(it, false) }, false)
        }
        val here = SmbFile(url(t, ""), ctx)
        val dirs = here.listFiles().filter { runCatching { it.isDirectory }.getOrDefault(false) }
        val names = dirs.map { it.name.trimEnd('/') }.filter { it.isNotEmpty() && !it.startsWith(".") }
        // RomM's library holds roms/<platform> (layout A) or <platform>/roms (layout B)
        val isLib = names.any { it.equals("roms", true) } ||
            dirs.take(6).any { d -> runCatching { d.listFiles().any { it.name.trimEnd('/').equals("roms", true) } }.getOrDefault(false) }
        val folders = names.sortedBy { it.lowercase() }.map { n ->
            // a quick look one level down, only for folders named like RomM's own
            val lib = n.equals("library", true) || n.equals("romm", true)
            Folder(n, lib)
        }
        return Listing(folders, isLib)
    }

    /** Every file that makes up a game: a playlist's discs (and their .bins), a .cue's .bins, or "(Disc 1)…" siblings. */
    fun filesOf(game: File): List<File> {
        val dir = game.parentFile ?: return listOf(game)
        val ext = game.extension.lowercase()
        val out = LinkedHashSet<File>()
        fun addDisc(f: File) {
            if (!f.isFile) return
            if (f.extension.equals("cue", true)) Chd.cueFiles(f).filter { it.isFile }.forEach { out.add(it) } else out.add(f)
        }
        when (ext) {
            "m3u" -> {
                out.add(game)
                game.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { addDisc(File(dir, it)) }
            }
            else -> {
                addDisc(game)
                // "Game (Disc 1).chd" → its other discs, and the playlist if there is one
                val m = Regex("""^(.*?)\s*\((Disc|CD|Disk)\s*\d+[^)]*\)(.*)$""", RegexOption.IGNORE_CASE).find(game.nameWithoutExtension)
                if (m != null) {
                    val base = m.groupValues[1].trim().lowercase()
                    dir.listFiles()?.filter { f ->
                        f.isFile && f.nameWithoutExtension.lowercase().startsWith(base) &&
                            Regex("""\((Disc|CD|Disk)\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(f.name) &&
                            f.extension.lowercase() in setOf("cue", "chd", "rvz", "iso", "gcm", "cso", "pbp", "img", "bin", ext)
                    }?.sortedBy { it.name.lowercase() }?.forEach { addDisc(it) }
                    File(dir, m.groupValues[1].trim() + ".m3u").takeIf { it.isFile }?.let { out.add(it) }
                }
            }
        }
        return out.toList()
    }

    /**
     * Copies [files] for the game [title] into the [platform] folder of the library. [progress] gets 0..1.
     * Returns null when done, else what went wrong.
     */
    fun upload(t: Target, platform: String, title: String, files: List<File>, progress: (Float) -> Unit): String? {
        return try {
            val ctx = context(t)
            val root = SmbFile(url(t, ""), ctx)
            if (!root.exists()) return "The library folder wasn't found on the share"
            // layout A (roms/<platform>) or B (<platform>/roms)
            val structureA = SmbFile(url(t, "roms"), ctx).exists()
            val platRel = if (structureA) "roms/$platform" else "$platform/roms"
            val folderRel = if (files.size > 1) "$platRel/${safe(title)}" else platRel
            SmbFile(url(t, folderRel), ctx).let { if (!it.exists()) it.mkdirs() }
            val total = files.sumOf { it.length() }.coerceAtLeast(1L)
            var done = 0L
            val buf = ByteArray(1 shl 20)
            for (f in files) {
                val dest = SmbFile(url(t, folderRel) + safe(f.name), ctx)
                if (dest.exists() && dest.length() == f.length()) { done += f.length(); progress(done / total.toFloat()); continue }
                val part = SmbFile(url(t, folderRel) + safe(f.name) + ".part", ctx)
                f.inputStream().use { input ->
                    SmbFileOutputStream(part).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done / total.toFloat())
                        }
                    }
                }
                if (dest.exists()) dest.delete()
                part.renameTo(dest)
            }
            null
        } catch (e: Exception) {
            e.message ?: "Upload failed"
        }
    }

    private fun safe(name: String) = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
}
