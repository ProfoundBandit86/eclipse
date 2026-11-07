package com.eclipse.launcher.media

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File

/**
 * The little click as the highlight moves (Settings → Look & Feel → Sounds: a volume slider and a sound picker).
 * Short sounds in assets/sounds, or a file you picked (kept in filesDir/sounds), played through a SoundPool so they
 * start instantly.
 */
object UiSound {
    private var pool: SoundPool? = null
    private var tick = 0
    private var ready = false
    private var last = 0L
    private var ctx: Context? = null
    private var current = "tick"

    /** 0 (off) to 100. */
    @Volatile var volume: Int = 40

    /** The sounds that come with Eclipse: (file name without .wav, label). */
    val BUILT_IN = listOf("tick" to "Tick", "click" to "Click", "pop" to "Pop", "soft" to "Soft", "blip" to "Blip", "wood" to "Wood")

    fun soundsDir(c: Context): File = File(c.filesDir, "sounds").apply { mkdirs() }

    fun init(c: Context) {
        ctx = c.applicationContext
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        pool = p
        choose(current)
    }

    /** Loads the sound: a built-in id ("pop") or "file:<name>" for one in the sounds folder. */
    fun choose(id: String) {
        current = id
        val p = pool ?: return
        val c = ctx ?: return
        ready = false
        if (tick != 0) p.unload(tick)
        tick = try {
            if (id.startsWith("file:")) p.load(File(soundsDir(c), id.removePrefix("file:")).absolutePath, 1)
            else c.assets.openFd("sounds/$id.wav").use { p.load(it, 1) }
        } catch (e: Exception) { 0 }
    }

    /** One click (at most one every 35 ms, so a fast scroll doesn't turn into a buzz). */
    fun tick() {
        val v = volume / 100f
        if (v <= 0f || !ready) return
        val now = System.currentTimeMillis()
        if (now - last < 35) return
        last = now
        pool?.play(tick, v, v, 1, 0, 1f)
    }
}
