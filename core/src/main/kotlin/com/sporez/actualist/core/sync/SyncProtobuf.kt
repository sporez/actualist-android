package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import java.io.ByteArrayOutputStream

/**
 * Hand-written proto3 codec for Actual's `sync.proto` (`packages/crdt/src/proto/sync.proto`).
 * Field numbers match Actualist's generated `Sync.pb.swift`:
 *
 * - `Message`: dataset=1, row=2, column=3, value=4
 * - `MessageEnvelope`: timestamp=1, isEncrypted=2, content=3
 * - `EncryptedData`: iv=1, authTag=2, data=3
 * - `SyncRequest`: messages=1, fileId=2, groupId=3, keyId=5, since=6
 * - `SyncResponse`: messages=1, merkle=2
 *
 * Proto3 defaults (empty strings, false, empty bytes) are not written, as upstream.
 */
object SyncProto {
    data class Message(val dataset: String = "", val row: String = "", val column: String = "", val value: String = "")

    class MessageEnvelope(val timestamp: String = "", val isEncrypted: Boolean = false, val content: ByteArray = ByteArray(0)) {
        override fun equals(other: Any?): Boolean = other is MessageEnvelope &&
            other.timestamp == timestamp && other.isEncrypted == isEncrypted && other.content.contentEquals(content)

        override fun hashCode(): Int = (timestamp.hashCode() * 31 + isEncrypted.hashCode()) * 31 + content.contentHashCode()
    }

    class EncryptedData(val iv: ByteArray = ByteArray(0), val authTag: ByteArray = ByteArray(0), val data: ByteArray = ByteArray(0))

    data class SyncRequest(
        val messages: List<MessageEnvelope> = emptyList(),
        val fileId: String = "",
        val groupId: String = "",
        val keyId: String = "",
        val since: String = "",
    )

    data class SyncResponse(val messages: List<MessageEnvelope> = emptyList(), val merkle: String = "")

    fun encode(message: Message): ByteArray = ProtoWriter().apply {
        string(1, message.dataset)
        string(2, message.row)
        string(3, message.column)
        string(4, message.value)
    }.toByteArray()

    fun encode(envelope: MessageEnvelope): ByteArray = ProtoWriter().apply {
        string(1, envelope.timestamp)
        bool(2, envelope.isEncrypted)
        bytes(3, envelope.content)
    }.toByteArray()

    fun encode(data: EncryptedData): ByteArray = ProtoWriter().apply {
        bytes(1, data.iv)
        bytes(2, data.authTag)
        bytes(3, data.data)
    }.toByteArray()

    fun encode(request: SyncRequest): ByteArray = ProtoWriter().apply {
        for (envelope in request.messages) embedded(1, encode(envelope))
        string(2, request.fileId)
        string(3, request.groupId)
        string(5, request.keyId)
        string(6, request.since)
    }.toByteArray()

    fun encode(response: SyncResponse): ByteArray = ProtoWriter().apply {
        for (envelope in response.messages) embedded(1, encode(envelope))
        string(2, response.merkle)
    }.toByteArray()

    fun decodeMessage(bytes: ByteArray): Message {
        var dataset = ""
        var row = ""
        var column = ""
        var value = ""
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> dataset = reader.string()
                2 -> row = reader.string()
                3 -> column = reader.string()
                4 -> value = reader.string()
                else -> reader.skip()
            }
        }
        return Message(dataset, row, column, value)
    }

    fun decodeEnvelope(bytes: ByteArray): MessageEnvelope {
        var timestamp = ""
        var isEncrypted = false
        var content = ByteArray(0)
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> timestamp = reader.string()
                2 -> isEncrypted = reader.varint() != 0L
                3 -> content = reader.bytes()
                else -> reader.skip()
            }
        }
        return MessageEnvelope(timestamp, isEncrypted, content)
    }

    fun decodeEncryptedData(bytes: ByteArray): EncryptedData {
        var iv = ByteArray(0)
        var authTag = ByteArray(0)
        var data = ByteArray(0)
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> iv = reader.bytes()
                2 -> authTag = reader.bytes()
                3 -> data = reader.bytes()
                else -> reader.skip()
            }
        }
        return EncryptedData(iv, authTag, data)
    }

    fun decodeRequest(bytes: ByteArray): SyncRequest {
        val messages = mutableListOf<MessageEnvelope>()
        var fileId = ""
        var groupId = ""
        var keyId = ""
        var since = ""
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> messages += decodeEnvelope(reader.bytes())
                2 -> fileId = reader.string()
                3 -> groupId = reader.string()
                5 -> keyId = reader.string()
                6 -> since = reader.string()
                else -> reader.skip()
            }
        }
        return SyncRequest(messages, fileId, groupId, keyId, since)
    }

    fun decodeResponse(bytes: ByteArray): SyncResponse {
        val messages = mutableListOf<MessageEnvelope>()
        var merkle = ""
        ProtoReader(bytes).forEachField { field, reader ->
            when (field) {
                1 -> messages += decodeEnvelope(reader.bytes())
                2 -> merkle = reader.string()
                else -> reader.skip()
            }
        }
        return SyncResponse(messages, merkle)
    }
}

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun string(field: Int, value: String) {
        if (value.isEmpty()) return
        lengthDelimited(field, value.toByteArray(Charsets.UTF_8))
    }

    fun bytes(field: Int, value: ByteArray) {
        if (value.isEmpty()) return
        lengthDelimited(field, value)
    }

    fun bool(field: Int, value: Boolean) {
        if (!value) return
        tag(field, 0)
        varint(1)
    }

    /** Repeated embedded messages are always written, even when empty. */
    fun embedded(field: Int, value: ByteArray) = lengthDelimited(field, value)

    private fun lengthDelimited(field: Int, value: ByteArray) {
        tag(field, 2)
        varint(value.size.toLong())
        out.write(value)
    }

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

internal class ProtoReader(private val bytes: ByteArray) {
    private var position = 0
    private var wireType = 0

    fun forEachField(body: (field: Int, reader: ProtoReader) -> Unit) {
        while (position < bytes.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            wireType = (key and 7).toInt()
            if (field <= 0) throw LocalFirstException.MalformedProtobuf("invalid field number")
            body(field, this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size) throw LocalFirstException.MalformedProtobuf("truncated varint")
            if (shift >= 64) throw LocalFirstException.MalformedProtobuf("varint too long")
            val byte = bytes[position++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    fun bytes(): ByteArray {
        if (wireType != 2) throw LocalFirstException.MalformedProtobuf("expected length-delimited field")
        val length = varint()
        if (length < 0 || length > bytes.size - position) {
            throw LocalFirstException.MalformedProtobuf("truncated field")
        }
        val start = position
        position += length.toInt()
        return bytes.copyOfRange(start, position)
    }

    fun string(): String = bytes().toString(Charsets.UTF_8)

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> advance(8)
            2 -> bytes()
            5 -> advance(4)
            else -> throw LocalFirstException.MalformedProtobuf("unsupported wire type $wireType")
        }
    }

    private fun advance(count: Int) {
        if (count > bytes.size - position) throw LocalFirstException.MalformedProtobuf("truncated field")
        position += count
    }
}
