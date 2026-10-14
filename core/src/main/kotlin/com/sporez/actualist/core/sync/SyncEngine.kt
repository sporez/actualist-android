package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.db.PendingLocalMessage
import com.sporez.actualist.core.db.RemoteApplyResult
import com.sporez.actualist.core.server.SyncTransport

data class SyncConfiguration(
    val fileId: String,
    val groupId: String?,
    val encryptionKeyId: String?,
    val encryption: EncryptionContext?,
)

data class SyncResult(
    val uploaded: Int,
    val applied: Int,
    val quarantined: List<String>,
    /** Local changes still waiting in the outbox (a later batch). */
    val pendingAfter: Int,
)

/**
 * Actual's `/sync/sync` protocol over the local database. Port of Actualist `SyncClient`
 * and `flushPendingLocalMessages`:
 *
 * - Local changes are uploaded from the outbox with `since` = the oldest pending change's
 *   base timestamp, and only deleted from the outbox once the server returns every
 *   uploaded envelope (a second pull confirms when the first reply predates the insert).
 * - Remote changes are validated as a batch, then applied last-write-wins per cell.
 * - After applying, the server's merkle trie is compared with the local one and the
 *   engine pulls again from the first differing minute until they match, rebuilding the
 *   local trie once before declaring the budget out of sync.
 *
 * Nothing here reports success before the server confirmed it.
 */
class SyncEngine(
    private val database: BudgetDatabase,
    private val transport: SyncTransport,
    private val configuration: SyncConfiguration,
    private val clock: Clock = Clock.SYSTEM,
    private val batchSize: Int = 500,
) {
    /** One sync pass: upload one outbox batch (if any) and pull everything new. */
    fun sync(token: String): SyncResult {
        val pending = database.pendingLocalMessages(batchSize)
        if (pending.isEmpty()) {
            val pulled = pullAndApply(token)
            return SyncResult(0, pulled.appliedMessageCount, pulled.quarantinedTimestamps, database.pendingLocalMessageCount())
        }
        var confirmed = false
        try {
            val result = pushAndPull(token, pending) {
                database.deletePendingLocalMessages(pending.map { it.message.timestamp })
                confirmed = true
            }
            return SyncResult(pending.size, result.appliedMessageCount, result.quarantinedTimestamps, database.pendingLocalMessageCount())
        } catch (error: Exception) {
            // Confirmed rows were already deleted; a later failure is a pull failure.
            if (!confirmed) {
                database.markPendingLocalMessagesFailed(pending.map { it.message.timestamp }, safeDescription(error))
            }
            throw error
        }
    }

    fun pullAndApply(token: String): RemoteApplyResult {
        val request = SyncProto.SyncRequest(
            fileId = configuration.fileId,
            groupId = configuration.groupId ?: "",
            keyId = configuration.encryptionKeyId ?: "",
            since = database.latestSyncTimestamp(clock.nowMillis()),
        )
        val clockBefore = database.localClockTimestamp
        val response = SyncProto.decodeResponse(transport.sync(SyncProto.encode(request), token))
        val messages = decode(response.messages)
        val localTimeChanged = database.localClockTimestamp != clockBefore
        val applied = database.applyRemoteMessages(messages, clock.nowMillis())
        return repullUntilMerkleMatches(token, applied, response.merkle, localTimeChanged)
    }

    private fun pushAndPull(token: String, pending: List<PendingLocalMessage>, onUploadConfirmed: () -> Unit): RemoteApplyResult {
        val envelopes = pending.map { envelope(it.message) }
        val since = pending.minOf { it.baseTimestamp }
        val request = SyncProto.SyncRequest(
            messages = envelopes,
            fileId = configuration.fileId,
            groupId = configuration.groupId ?: "",
            keyId = configuration.encryptionKeyId ?: "",
            since = since,
        )
        val clockBefore = database.localClockTimestamp
        val response = SyncProto.decodeResponse(transport.sync(SyncProto.encode(request), token))
        val responseEnvelopes = response.messages.toMutableList()
        var merkle = response.merkle
        val pushedByTimestamp = envelopes.associateBy { it.timestamp }
        val confirmedTimestamps = confirmedUploads(response.messages, pushedByTimestamp).toMutableSet()

        // The server reads before inserting this upload, so confirm it with a second pull.
        if (confirmedTimestamps.size != pushedByTimestamp.size) {
            val confirmation = SyncProto.decodeResponse(
                transport.sync(
                    SyncProto.encode(request.copy(messages = emptyList())),
                    token,
                ),
            )
            responseEnvelopes += confirmation.messages
            merkle = confirmation.merkle
            confirmedTimestamps += confirmedUploads(confirmation.messages, pushedByTimestamp)
        }
        val unconfirmed = pushedByTimestamp.keys.count { it !in confirmedTimestamps }
        if (unconfirmed > 0) throw LocalFirstException.SyncUploadNotConfirmed(unconfirmed)
        // The server holds the upload now: report it before the merkle re-pull, whose
        // failure must not leave confirmed messages queued for another upload.
        onUploadConfirmed()

        val combined = responseEnvelopes.associateBy { it.timestamp }.values.sortedBy { it.timestamp }
        val remote = decode(combined)
        val localTimeChanged = database.localClockTimestamp != clockBefore
        val applied = database.applyRemoteMessages(remote, clock.nowMillis())
        return repullUntilMerkleMatches(token, applied, merkle, localTimeChanged)
    }

    /**
     * Upstream `_fullSync`'s loop: pull again from the first differing minute until the
     * server's merkle matches this file's. Late or older messages from other devices
     * arrive this way instead of being skipped forever by `since = MAX(timestamp)`.
     */
    private fun repullUntilMerkleMatches(
        token: String,
        initial: RemoteApplyResult,
        initialMerkle: String,
        initialLocalChange: Boolean,
    ): RemoteApplyResult {
        var total = initial
        var server = MerkleTrie.fromJson(initialMerkle)
        var count = if (initialLocalChange) 0 else 1
        var previousDivergence: Long? = null
        var rebuilt = false
        while (true) {
            // An empty or unreadable server merkle (older servers) skips the comparison.
            val serverTrie = server ?: return total
            val divergence = database.merkleDivergence(serverTrie) ?: return total
            if ((count >= 10 && divergence == previousDivergence) || count >= 100) {
                // A stale stored trie is a cheaper explanation than a diverged history.
                if (rebuilt) throw LocalFirstException.SyncOutOfSync()
                rebuilt = true
                database.rebuildMerkleTrie()
                count = 0
                previousDivergence = null
                continue
            }
            val request = SyncProto.SyncRequest(
                fileId = configuration.fileId,
                groupId = configuration.groupId ?: "",
                keyId = configuration.encryptionKeyId ?: "",
                since = SyncTimestamp.wallTimeString(divergence) + "-0000-0000000000000000",
            )
            val clockBefore = database.localClockTimestamp
            val response = SyncProto.decodeResponse(transport.sync(SyncProto.encode(request), token))
            val messages = decode(response.messages)
            val localTimeChanged = database.localClockTimestamp != clockBefore
            total = total.merging(database.applyRemoteMessages(messages, clock.nowMillis()))
            server = MerkleTrie.fromJson(response.merkle)
            count = if (localTimeChanged) 0 else count + 1
            previousDivergence = divergence
        }
    }

    private fun envelope(message: SyncMessage): SyncProto.MessageEnvelope {
        val bytes = SyncProto.encode(SyncProto.Message(message.dataset, message.row, message.column, message.serializedValue))
        val encryption = configuration.encryption ?: return SyncProto.MessageEnvelope(message.timestamp, false, bytes)
        val sealed = ActualCrypto.encrypt(bytes, encryption)
        val content = SyncProto.encode(SyncProto.EncryptedData(sealed.iv, sealed.authTag, sealed.data))
        return SyncProto.MessageEnvelope(message.timestamp, true, content)
    }

    /**
     * Uploaded envelopes the server returned. Actual keeps the first envelope for a
     * timestamp, and a retry uses a fresh IV, so encrypted envelopes are compared by their
     * decrypted content.
     */
    private fun confirmedUploads(
        envelopes: List<SyncProto.MessageEnvelope>,
        uploaded: Map<String, SyncProto.MessageEnvelope>,
    ): Set<String> {
        val confirmed = HashSet<String>()
        for (envelope in envelopes) {
            val expected = uploaded[envelope.timestamp] ?: continue
            if (envelope == expected) {
                confirmed += envelope.timestamp
            } else if (envelope.isEncrypted && expected.isEncrypted && decodeOne(envelope) == decodeOne(expected)) {
                confirmed += envelope.timestamp
            }
        }
        return confirmed
    }

    private fun decode(envelopes: List<SyncProto.MessageEnvelope>): List<SyncMessage> = envelopes.map(::decodeOne)

    private fun decodeOne(envelope: SyncProto.MessageEnvelope): SyncMessage {
        val bytes = if (envelope.isEncrypted) {
            val encryption = configuration.encryption ?: throw LocalFirstException.EncryptedBudgetRequiresPassword()
            try {
                val data = SyncProto.decodeEncryptedData(envelope.content)
                ActualCrypto.decrypt(EncryptedPayload(data.data, data.iv, data.authTag), encryption.keyData)
            } catch (error: LocalFirstException) {
                throw LocalFirstException.UndecryptableMessage()
            }
        } else {
            envelope.content
        }
        val message = try {
            SyncProto.decodeMessage(bytes)
        } catch (error: LocalFirstException) {
            throw LocalFirstException.UndecryptableMessage()
        }
        return SyncMessage(envelope.timestamp, message.dataset, message.row, message.column, message.value)
    }

    companion object {
        /** A failure description safe to store and show: never a token, key or payload. */
        fun safeDescription(error: Throwable): String = when (error) {
            is LocalFirstException -> error.message ?: error::class.simpleName.orEmpty()
            is com.sporez.actualist.core.server.ServerException -> error.message ?: "server error"
            else -> error::class.simpleName ?: "error"
        }
    }
}
