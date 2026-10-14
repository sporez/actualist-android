package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * A strictly parsed Actual hybrid-logical-clock timestamp:
 * `2026-09-08T00:00:00.000Z-00AF-0123456789abcdef`.
 *
 * Port of Actualist `SyncTimestamp`, which mirrors upstream `Timestamp.parse` and
 * `Timestamp.recv` (`packages/crdt/src/crdt/timestamp.ts`): five dash-separated parts,
 * a real millisecond ISO time, a hex counter no greater than 0xFFFF and a node of at
 * most 16 characters. Anything else is rejected.
 */
data class SyncTimestamp(
    val wallTime: String,
    val milliseconds: Long,
    val counter: Int,
    val node: String,
) {
    /** `true` when this timestamp is more than the allowed drift ahead of [nowMillis]. */
    fun exceedsDrift(nowMillis: Long): Boolean = milliseconds - nowMillis > MAXIMUM_DRIFT_MILLISECONDS

    companion object {
        /** Upstream `config.maxDrift`: five minutes. */
        const val MAXIMUM_DRIFT_MILLISECONDS: Long = 5L * 60 * 1_000
        const val ZERO = "1970-01-01T00:00:00.000Z-0000-0000000000000000"

        private const val WALL_TIME_LENGTH = 24
        private const val MAXIMUM_NODE_LENGTH = 16

        private val wallTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .withResolverStyle(ResolverStyle.STRICT)

        fun parse(string: String): SyncTimestamp? {
            val parts = string.split("-")
            if (parts.size != 5) return null
            val counterText = parts[3]
            if (counterText.length != 4 || !counterText.all { it.isAsciiHexDigit() }) return null
            val counter = counterText.toInt(16)
            val node = parts[4]
            if (node.isEmpty() || node.length > MAXIMUM_NODE_LENGTH) return null
            if (!node.all { it.isAsciiLetterOrDigit() }) return null
            val wallTime = parts.subList(0, 3).joinToString("-")
            if (wallTime.length != WALL_TIME_LENGTH) return null
            val millis = wallTimeMillis(wallTime) ?: return null
            if (wallTimeString(millis) != wallTime) return null
            if (millis < 0) return null
            return SyncTimestamp(wallTime, millis, counter, node)
        }

        fun wallTimeString(millis: Long): String = wallTimeFormatter.format(Instant.ofEpochMilli(millis))

        /** Lenient inverse of [wallTimeString]; [parse] adds the strict round-trip check. */
        fun wallTimeMillis(text: String): Long? = try {
            Instant.from(wallTimeFormatter.parse(text)).toEpochMilli()
        } catch (e: Exception) {
            null
        }

        /**
         * Validates a whole remote batch before anything is applied, like upstream
         * `receiveMessages`, which calls `Timestamp.recv` for every message first.
         */
        fun validateRemoteBatch(timestamps: Iterable<String>, nowMillis: Long) {
            for (string in timestamps) {
                val parsed = parse(string) ?: throw LocalFirstException.InvalidSyncTimestamp()
                if (parsed.exceedsDrift(nowMillis)) throw LocalFirstException.ClockDrift()
            }
        }

        /**
         * The `since` to send: the newest stored timestamp, but never further than the
         * allowed drift ahead of now. Stored rows dated in the far future would otherwise
         * freeze sync.
         */
        fun clampedSince(storedMaximum: String?, nowMillis: Long): String {
            val ceiling = wallTimeString(nowMillis + MAXIMUM_DRIFT_MILLISECONDS) + "-0000-0000000000000000"
            val parsed = storedMaximum?.let(::parse) ?: return ZERO
            return if (parsed.wallTime > ceiling.substring(0, WALL_TIME_LENGTH)) ceiling else storedMaximum
        }

        private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
        private fun Char.isAsciiLetterOrDigit(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'
    }
}
