package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Vectors generated with Node's `crypto` exactly as Actual derives and seals
 * (`pbkdf2Sync(password, salt, 10000, 32, 'sha512')`, `aes-256-gcm`); the generator is
 * `tools/crypto-vectors.mjs`. All inputs are synthetic.
 */
class ActualCryptoTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val keyHex = "0726e68c6adfdbe3349ebaf7e8e50a39518a18117820c243e10082fc68dcb95e"

    @Test
    fun derivesTheSameKeyAsNodePbkdf2() {
        assertEquals(keyHex, ActualCrypto.deriveKey("synthetic-password-1", "c3ludGhldGljLXNhbHQ=").hex())
    }

    @Test
    fun passwordsAreEncodedAsUtf8() {
        assertEquals(
            "39e836f36527c1e052ea889c8ce733fb2cb0664d032ee63d1481ec0a96a05be8",
            ActualCrypto.deriveKey("pässwörd✓", "salt").hex(),
        )
    }

    @Test
    fun encryptsAndDecryptsLikeNodeAesGcm() {
        val context = EncryptionContext("key-1", hex(keyHex))
        val plaintext = hex("0a086163636f756e7473120161")
        val sealed = ActualCrypto.encrypt(plaintext, context, iv = hex("000102030405060708090a0b"))
        assertEquals("112bd3bb362bd3ae4727e493c3", sealed.data.hex())
        assertEquals("2673e1cde6b1e6dfdf9d3436d2024e6c", sealed.authTag.hex())
        assertArrayEquals(plaintext, ActualCrypto.decrypt(sealed, context.keyData))
    }

    @Test
    fun wrongKeyOrTamperedDataIsAPasswordError() {
        val sealed = EncryptedPayload(hex("112bd3bb362bd3ae4727e493c3"), hex("000102030405060708090a0b"), hex("2673e1cde6b1e6dfdf9d3436d2024e6c"))
        val wrongKey = ActualCrypto.deriveKey("other-password", "c3ludGhldGljLXNhbHQ=")
        assertThrows(LocalFirstException.InvalidEncryptionPassword::class.java) { ActualCrypto.decrypt(sealed, wrongKey) }
        val tampered = EncryptedPayload(hex("012bd3bb362bd3ae4727e493c3"), sealed.iv, sealed.authTag)
        assertThrows(LocalFirstException.InvalidEncryptionPassword::class.java) { ActualCrypto.decrypt(tampered, hex(keyHex)) }
    }

    @Test
    fun malformedPayloadShapesAreRejected() {
        val key = hex(keyHex)
        assertThrows(LocalFirstException.InvalidEncryptedPayload::class.java) {
            ActualCrypto.decrypt(EncryptedPayload(ByteArray(4), ByteArray(11), ByteArray(16)), key)
        }
        assertThrows(LocalFirstException.InvalidEncryptedPayload::class.java) {
            ActualCrypto.decrypt(EncryptedPayload(ByteArray(4), ByteArray(12), ByteArray(15)), key)
        }
        assertThrows(LocalFirstException.InvalidEncryptionKey::class.java) {
            ActualCrypto.encrypt(ByteArray(1), EncryptionContext("k", ByteArray(16)))
        }
    }

    @Test
    fun randomIvsDifferPerSeal() {
        val context = EncryptionContext("key-1", hex(keyHex))
        val a = ActualCrypto.encrypt(byteArrayOf(1, 2, 3), context)
        val b = ActualCrypto.encrypt(byteArrayOf(1, 2, 3), context)
        assertEquals(12, a.iv.size)
        assertEquals(false, a.iv.contentEquals(b.iv))
    }
}
