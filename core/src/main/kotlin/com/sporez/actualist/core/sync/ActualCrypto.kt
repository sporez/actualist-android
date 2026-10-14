package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionContext(val keyId: String, val keyData: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is EncryptionContext && other.keyId == keyId && other.keyData.contentEquals(keyData)

    override fun hashCode(): Int = keyId.hashCode() * 31 + keyData.contentHashCode()

    // Never print key material.
    override fun toString(): String = "EncryptionContext(keyId=$keyId)"
}

class EncryptedPayload(val data: ByteArray, val iv: ByteArray, val authTag: ByteArray)

/**
 * Actual's end-to-end encryption primitives (loot-core `encryption-internals`), ported
 * from Actualist `ActualBudgetCrypto`: PBKDF2-HMAC-SHA512 with 10,000 iterations over the
 * UTF-8 password and salt text, deriving a 32-byte AES-256-GCM key; 12-byte IVs and
 * 16-byte tags.
 */
object ActualCrypto {
    const val ALGORITHM = "aes-256-gcm"
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16

    // Part of Actual's wire format.
    private const val ITERATIONS = 10_000

    private val random = SecureRandom()

    /**
     * PBKDF2 is computed directly over `HmacSHA512` instead of `SecretKeyFactory`, so the
     * password-to-bytes conversion is explicitly UTF-8 on every JVM and Android provider.
     */
    fun deriveKey(password: String, salt: String): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        if (passwordBytes.isEmpty()) throw LocalFirstException.InvalidEncryptionKey()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA512"))
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        val output = ByteArray(KEY_BYTES)
        var offset = 0
        var block = 1
        while (offset < KEY_BYTES) {
            mac.update(saltBytes)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(ITERATIONS - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val count = minOf(t.size, KEY_BYTES - offset)
            System.arraycopy(t, 0, output, offset, count)
            offset += count
            block++
        }
        return output
    }

    fun decrypt(encrypted: EncryptedPayload, keyData: ByteArray): ByteArray {
        if (encrypted.iv.size != NONCE_BYTES || encrypted.authTag.size != TAG_BYTES || keyData.size != KEY_BYTES) {
            throw LocalFirstException.InvalidEncryptedPayload()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyData, "AES"), GCMParameterSpec(TAG_BYTES * 8, encrypted.iv))
            cipher.doFinal(encrypted.data + encrypted.authTag)
        } catch (e: AEADBadTagException) {
            throw LocalFirstException.InvalidEncryptionPassword()
        } catch (e: Exception) {
            throw LocalFirstException.InvalidEncryptionPassword()
        }
    }

    fun encrypt(data: ByteArray, context: EncryptionContext, iv: ByteArray? = null): EncryptedPayload {
        if (context.keyData.size != KEY_BYTES) throw LocalFirstException.InvalidEncryptionKey()
        val nonce = iv ?: ByteArray(NONCE_BYTES).also(random::nextBytes)
        if (nonce.size != NONCE_BYTES) throw LocalFirstException.InvalidEncryptedPayload()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(context.keyData, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            val sealed = cipher.doFinal(data)
            EncryptedPayload(
                data = sealed.copyOfRange(0, sealed.size - TAG_BYTES),
                iv = nonce,
                authTag = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size),
            )
        } catch (e: Exception) {
            throw LocalFirstException.InvalidEncryptedPayload()
        }
    }
}
