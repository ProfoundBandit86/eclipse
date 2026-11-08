package com.eclipse.launcher.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords and API keys (Settings' "keys": RomM, SMB, SteamGridDB...), encrypted with AES-GCM under a key that lives
 * in Android's Keystore. The key never leaves the Keystore (hardware-backed where the device has it), so the saved
 * text alone can't be read, even on a rooted device or from a copy of Eclipse's files.
 */
object Secrets {
    private const val ALIAS = "eclipse_secrets_v1"
    private const val PREFIX = "gcm1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Null when it can't be read (the Keystore key is gone, or the text was changed). */
    fun decrypt(text: String): String? = runCatching {
        val raw = Base64.decode(text.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Reads the saved keys. Older versions kept them as plain text under "keys": those are read once, saved
     * encrypted, and the plain copy removed.
     */
    fun load(store: Store): Map<String, String> {
        val enc = store.str("keys_enc")
        if (enc.isNotEmpty()) {
            val json = decrypt(enc) ?: return emptyMap()
            return runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrDefault(emptyMap())
        }
        val old = store.map("keys")
        if (old.isNotEmpty() && runCatching { save(store, old) }.isSuccess) store.remove("keys")
        return old
    }

    fun save(store: Store, keys: Map<String, String>) {
        store.putStr("keys_enc", encrypt(JSONObject(keys).toString()))
    }
}
