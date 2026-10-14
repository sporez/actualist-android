package com.sporez.actualist.core.sync

import com.sporez.actualist.core.server.ServerException
import com.sporez.actualist.core.server.SyncTransport
import java.io.IOException
import java.util.TreeMap

/**
 * An in-memory Actual sync server that follows the real server's `sync-simple` handler:
 * it reads `timestamp > since` BEFORE inserting the uploaded envelopes, inserts with
 * `INSERT OR IGNORE` (the first envelope for a timestamp wins), and returns the merkle
 * trie of everything it holds. It stores envelopes opaquely, so encrypted budgets stay
 * encrypted on the server. Failure hooks simulate the network.
 */
class FakeActualServer(private val fileId: String = "file-1", private val groupId: String = "group-1") {
    private val messages = TreeMap<String, SyncProto.MessageEnvelope>()
    private var trie = MerkleTrie.EMPTY
    var requests = 0
        private set

    /** Throw after the server committed the upload, as a dropped response would. */
    var failAfterCommit = 0

    /** Throw before the server sees the request. */
    var failBeforeCommit = 0

    /** Send every reply twice in the message list (duplicate delivery). */
    var duplicateReplies = false

    /** Withhold messages from the reply (but still count them in the merkle). */
    var withholdFromReplies: Set<String> = emptySet()

    val storedTimestamps: Set<String> get() = messages.keys

    fun envelope(timestamp: String) = messages[timestamp]

    val transport = SyncTransport { body, token ->
        requests++
        if (token != TOKEN) throw ServerException.AuthenticationFailed()
        if (failBeforeCommit > 0) {
            failBeforeCommit--
            throw ServerException.Unreachable(IOException("simulated connection failure"))
        }
        val request = SyncProto.decodeRequest(body)
        require(request.fileId == fileId) { "wrong file" }
        require(request.groupId == groupId) { "wrong group" }
        val reply = messages.tailMap(request.since, false).values.filter { it.timestamp !in withholdFromReplies }
        for (envelope in request.messages) {
            if (envelope.timestamp !in messages) {
                messages[envelope.timestamp] = envelope
                trie = trie.insert(SyncTimestamp.parse(envelope.timestamp)!!)
            }
        }
        if (failAfterCommit > 0) {
            failAfterCommit--
            throw ServerException.Unreachable(IOException("simulated dropped response"))
        }
        val replyMessages = if (duplicateReplies) reply + reply else reply
        SyncProto.encode(SyncProto.SyncResponse(replyMessages, trie.pruned().jsonString))
    }

    fun configuration(encryption: EncryptionContext? = null) =
        SyncConfiguration(fileId, groupId, encryption?.keyId, encryption)

    companion object {
        const val TOKEN = "synthetic-token"
    }
}
