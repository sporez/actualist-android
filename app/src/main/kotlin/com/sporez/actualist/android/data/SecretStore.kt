package com.sporez.actualist.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (server tokens, derived budget encryption keys) encrypted with an AES-256-GCM
 * key that lives in the Android Keystore and never leaves it. Only ciphertext and IVs
 * reach SharedPreferences. Backups exclude this file (see `data_extraction_rules.xml`).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("actualist_secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(value)
        prefs.edit()
            .putString("$name.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$name.data", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    fun putString(name: String, value: String) = put(name, value.toByteArray(Charsets.UTF_8))

    /** Null when absent or when the Keystore key changed (the secret must be entered again). */
    fun get(name: String): ByteArray? {
        val iv = prefs.getString("$name.iv", null) ?: return null
        val data = prefs.getString("$name.data", null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
        } catch (error: Exception) {
            null
        }
    }

    fun getString(name: String): String? = get(name)?.toString(Charsets.UTF_8)

    /**
     * True when a secret named [name] is stored but cannot be decrypted (the Keystore key was lost or
     * replaced), as opposed to never having been saved. Actualist's "Saved credentials unavailable".
     */
    fun isUnreadable(name: String): Boolean =
        prefs.getString("$name.data", null) != null && prefs.getString("$name.iv", null) != null && get(name) == null

    /** Why the saved credentials cannot be used, or null when they are readable or absent. */
    fun recoveryMessage(): String? =
        if (isUnreadable(SERVER_TOKEN)) "This device could not read its saved credentials." else null

    fun remove(name: String) {
        prefs.edit().remove("$name.iv").remove("$name.data").apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "actualist_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SERVER_TOKEN = "server_token"
        fun budgetKey(budgetId: String) = "budget_key.$budgetId"
    }
}
