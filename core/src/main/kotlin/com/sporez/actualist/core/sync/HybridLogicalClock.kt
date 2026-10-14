package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import java.util.UUID

/**
 * Actual's hybrid logical clock. Port of Actualist `HybridLogicalClock`.
 *
 * Immutable: [next] returns the advanced clock with the minted timestamp, so a write
 * that rolls back simply drops the advanced copy and in-memory time never moves.
 */
class HybridLogicalClock(nodeId: String, val lastTimestamp: String = SyncTimestamp.ZERO) {
    val nodeId: String = normalizedNodeId(nodeId)

    data class Minted(val clock: HybridLogicalClock, val timestamp: String)

    /**
     * Mirrors upstream `Timestamp.send`: the clock never moves backward, and it refuses
     * to mint a timestamp when stored changes are more than five minutes ahead of now.
     * Future-dated stored changes are never clamped: last-write-wins needs the new change
     * to sort after them, so the write is refused with a specific error.
     */
    fun next(nowMillis: Long): Minted {
        val nowWallTime = SyncTimestamp.wallTimeString(nowMillis)
        val parsedLast = SyncTimestamp.parse(lastTimestamp)
        val nextWallTime: String
        val counter: Int
        if (parsedLast != null && parsedLast.wallTime >= nowWallTime) {
            if (parsedLast.exceedsDrift(nowMillis)) {
                throw LocalFirstException.StoredChangeDatedInFuture(parsedLast.wallTime)
            }
            nextWallTime = parsedLast.wallTime
            counter = parsedLast.counter + 1
        } else {
            nextWallTime = nowWallTime
            counter = 0
        }
        if (counter > 0xFFFF) throw LocalFirstException.HybridLogicalClockOverflow()
        // Actual's Timestamp.toString uppercases the counter. Peers hash the verbatim
        // timestamp string into the merkle trie, so lowercase hex corrupts sync.
        val timestamp = "$nextWallTime-${String.format(java.util.Locale.ROOT, "%04X", counter)}-$nodeId"
        return Minted(HybridLogicalClock(nodeId, timestamp), timestamp)
    }

    /**
     * Returns the clock after observing a remote timestamp. Anything that is not a
     * strictly valid timestamp is ignored; drift is judged by batch validation first.
     */
    fun observe(timestamp: String): HybridLogicalClock {
        val observed = SyncTimestamp.parse(timestamp) ?: return this
        val current = SyncTimestamp.parse(lastTimestamp) ?: return HybridLogicalClock(nodeId, timestamp)
        val newer = observed.wallTime > current.wallTime ||
            (observed.wallTime == current.wallTime && observed.counter > current.counter)
        return if (newer) HybridLogicalClock(nodeId, timestamp) else this
    }

    override fun equals(other: Any?): Boolean =
        other is HybridLogicalClock && other.nodeId == nodeId && other.lastTimestamp == lastTimestamp

    override fun hashCode(): Int = 31 * nodeId.hashCode() + lastTimestamp.hashCode()

    override fun toString(): String = "HybridLogicalClock($nodeId, $lastTimestamp)"

    companion object {
        private const val NODE_ID_LENGTH = 16

        fun makeClientId(uuid: UUID = UUID.randomUUID()): String = normalizedNodeId(uuid.toString())

        fun normalizedNodeId(nodeId: String): String {
            val compact = nodeId.replace("-", "").lowercase()
            return if (compact.length > NODE_ID_LENGTH) compact.takeLast(NODE_ID_LENGTH) else compact
        }
    }
}
