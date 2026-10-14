package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireFormatTest {
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun valuesSerializeInActualForm() {
        assertEquals("0:", SyncValue.Null.serialized)
        assertEquals("N:-30000", SyncValue.int(-30000).serialized)
        assertEquals("N:1", SyncValue.bool(true).serialized)
        assertEquals("N:0", SyncValue.bool(false).serialized)
        assertEquals("S:groceries", SyncValue.text("groceries").serialized)
        assertEquals("S:", SyncValue.text("").serialized)
        assertEquals("N:1.5", SyncValue.Real(1.5).serialized)
        assertEquals("N:16384", SyncValue.Real(16384.0).serialized)
    }

    @Test
    fun valuesDeserializeStrictly() {
        assertEquals(SyncValue.Null, SyncValue.deserialize("0:"))
        assertEquals(SyncValue.Integer(202608), SyncValue.deserialize("N:202608"))
        assertEquals(SyncValue.Integer(-5), SyncValue.deserialize("N:-5.0"))
        assertEquals(SyncValue.Real(0.25), SyncValue.deserialize("N:0.25"))
        assertEquals(SyncValue.Integer(100000), SyncValue.deserialize("N:1e5"))
        assertEquals(SyncValue.Text("a:b"), SyncValue.deserialize("S:a:b"))
        assertEquals(SyncValue.Text(""), SyncValue.deserialize("S:"))
        for (bad in listOf("", "X:1", "N:", "N:abc", "N:NaN", "N:Infinity", "N:1.5d", "N: 1", "N:1e999")) {
            assertThrows(bad, LocalFirstException.InvalidSyncValue::class.java) { SyncValue.deserialize(bad) }
        }
    }

    @Test
    fun messageEncodesToTheProto3Bytes() {
        val message = SyncProto.Message("accounts", "a", "name", "S:x")
        val expected = "0a086163636f756e7473" + "120161" + "1a046e616d65" + "2203533a78"
        assertEquals(expected, SyncProto.encode(message).hex())
        assertEquals(message, SyncProto.decodeMessage(hex(expected)))
    }

    @Test
    fun defaultsAreOmittedAndBooleansUseVarints() {
        assertEquals("", SyncProto.encode(SyncProto.Message()).hex())
        val envelope = SyncProto.MessageEnvelope("ts", true, byteArrayOf(1, 2))
        assertEquals("0a027473" + "1001" + "1a020102", SyncProto.encode(envelope).hex())
        assertEquals("0a027473", SyncProto.encode(SyncProto.MessageEnvelope("ts", false, ByteArray(0))).hex())
    }

    @Test
    fun requestAndResponseRoundTrip() {
        val envelope = SyncProto.MessageEnvelope("2026-10-15T12:00:00.000Z-0000-abc", false, SyncProto.encode(SyncProto.Message("d", "r", "c", "N:1")))
        val request = SyncProto.SyncRequest(listOf(envelope, envelope), "file", "group", "key", "since")
        val bytes = SyncProto.encode(request)
        val decoded = SyncProto.decodeRequest(bytes)
        assertEquals(request, decoded)
        // keyId is field 5 and since is field 6 (field 4 is unused upstream).
        assertEquals(true, bytes.hex().contains("2a036b6579"))
        assertEquals(true, bytes.hex().contains("320573696e6365"))
        val response = SyncProto.SyncResponse(listOf(envelope), "{\"hash\":1}")
        assertEquals(response, SyncProto.decodeResponse(SyncProto.encode(response)))
    }

    @Test
    fun unknownFieldsAreSkippedAndTruncationIsAnError() {
        // field 7 varint 150, field 8 fixed32, field 9 fixed64, then dataset.
        val bytes = hex("389601" + "4501020304" + "490102030405060708" + "0a0161")
        assertEquals(SyncProto.Message(dataset = "a"), SyncProto.decodeMessage(bytes))
        assertThrows(LocalFirstException.MalformedProtobuf::class.java) { SyncProto.decodeMessage(hex("0a05616263")) }
        assertThrows(LocalFirstException.MalformedProtobuf::class.java) { SyncProto.decodeMessage(hex("0a")) }
    }

    @Test
    fun encryptedDataFieldOrder() {
        val data = SyncProto.EncryptedData(iv = byteArrayOf(1), authTag = byteArrayOf(2), data = byteArrayOf(3))
        assertEquals("0a0101" + "120102" + "1a0103", SyncProto.encode(data).hex())
        val decoded = SyncProto.decodeEncryptedData(SyncProto.encode(data))
        assertArrayEquals(byteArrayOf(1), decoded.iv)
        assertArrayEquals(byteArrayOf(2), decoded.authTag)
        assertArrayEquals(byteArrayOf(3), decoded.data)
    }
}
