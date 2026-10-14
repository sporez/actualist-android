package com.sporez.actualist.core.sync

import com.sporez.actualist.core.LocalFirstException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAndTimestampTest {
    private val now = SyncTimestamp.wallTimeMillis("2026-10-15T12:00:00.000Z")!!

    @Test
    fun parsesValidTimestamps() {
        val parsed = SyncTimestamp.parse("2026-09-08T00:00:00.000Z-00AF-0123456789abcdef")!!
        assertEquals("2026-09-08T00:00:00.000Z", parsed.wallTime)
        assertEquals(0xAF, parsed.counter)
        assertEquals("0123456789abcdef", parsed.node)
        assertEquals(SyncTimestamp.wallTimeMillis("2026-09-08T00:00:00.000Z"), parsed.milliseconds)
        assertNotNull(SyncTimestamp.parse(SyncTimestamp.ZERO))
    }

    @Test
    fun rejectsMalformedTimestamps() {
        val invalid = listOf(
            "",
            "garbage",
            "2026-09-08T00:00:00.000Z-0AF-0123456789abcdef",
            "2026-09-08T00:00:00.000Z-00AG-0123456789abcdef",
            "2026-09-08T00:00:00.000Z-00AF-",
            "2026-09-08T00:00:00.000Z-00AF-0123456789abcdef0",
            "2026-09-08T00:00:00.000Z-00AF-node_1",
            "2026-02-30T00:00:00.000Z-0000-0123456789abcdef",
            "2026-09-08T00:00:00Z-0000-0123456789abcdef",
            "2026-09-08 00:00:00.000Z-0000-0123456789abcdef",
            "2026-09-08T00:00:00.000Z-0000-0123456789abcdef-extra",
        )
        for (text in invalid) assertNull(text, SyncTimestamp.parse(text))
    }

    @Test
    fun remoteBatchRejectsMalformedAndFarFutureTimestamps() {
        SyncTimestamp.validateRemoteBatch(listOf(SyncTimestamp.wallTimeString(now + 60_000) + "-0000-node"), now)
        assertThrows(LocalFirstException.InvalidSyncTimestamp::class.java) {
            SyncTimestamp.validateRemoteBatch(listOf("bad"), now)
        }
        assertThrows(LocalFirstException.ClockDrift::class.java) {
            SyncTimestamp.validateRemoteBatch(listOf(SyncTimestamp.wallTimeString(now + 6 * 60_000) + "-0000-node"), now)
        }
    }

    @Test
    fun sinceIsClampedToTheDriftCeiling() {
        assertEquals(SyncTimestamp.ZERO, SyncTimestamp.clampedSince(null, now))
        assertEquals(SyncTimestamp.ZERO, SyncTimestamp.clampedSince("garbage", now))
        val recent = "2026-10-15T11:59:00.000Z-0003-node"
        assertEquals(recent, SyncTimestamp.clampedSince(recent, now))
        val farFuture = "2030-01-01T00:00:00.000Z-0000-node"
        assertEquals("2026-10-15T12:05:00.000Z-0000-0000000000000000", SyncTimestamp.clampedSince(farFuture, now))
    }

    @Test
    fun clockMintsMonotonicUppercaseTimestamps() {
        val clock = HybridLogicalClock("ABCDEF01-2345-6789-ABCD-EF0123456789")
        assertEquals("6789abcdef012345".length, clock.nodeId.length)
        assertEquals("abcdef0123456789", clock.nodeId)
        val first = clock.next(now)
        assertEquals("2026-10-15T12:00:00.000Z-0000-abcdef0123456789", first.timestamp)
        val second = first.clock.next(now)
        assertEquals("2026-10-15T12:00:00.000Z-0001-abcdef0123456789", second.timestamp)
        var c = second.clock
        repeat(9) { c = c.next(now).clock }
        // Counter 11 is written as uppercase hex.
        assertEquals("2026-10-15T12:00:00.000Z-000B-abcdef0123456789", c.next(now).timestamp)
        // Wall time moving forward resets the counter.
        assertEquals("2026-10-15T12:00:01.000Z-0000-abcdef0123456789", c.next(now + 1000).timestamp)
        // Wall time moving backward keeps the last wall time and advances the counter.
        assertTrue(c.next(now - 10_000).timestamp > c.lastTimestamp)
    }

    @Test
    fun clockRefusesWhenStoredChangesAreFarInTheFuture() {
        val future = SyncTimestamp.wallTimeString(now + 10 * 60_000) + "-0000-remote"
        val clock = HybridLogicalClock("local", future)
        assertThrows(LocalFirstException.StoredChangeDatedInFuture::class.java) { clock.next(now) }
    }

    @Test
    fun clockOverflowIsAnError() {
        val clock = HybridLogicalClock("local", SyncTimestamp.wallTimeString(now) + "-FFFF-local")
        assertThrows(LocalFirstException.HybridLogicalClockOverflow::class.java) { clock.next(now) }
    }

    @Test
    fun observeOnlyMovesForward() {
        val clock = HybridLogicalClock("local", "2026-10-15T12:00:00.000Z-0005-local")
        assertSame(clock, clock.observe("garbage"))
        assertSame(clock, clock.observe("2026-10-15T11:00:00.000Z-0009-remote"))
        assertSame(clock, clock.observe("2026-10-15T12:00:00.000Z-0004-remote"))
        assertEquals("2026-10-15T12:00:00.000Z-0006-remote", clock.observe("2026-10-15T12:00:00.000Z-0006-remote").lastTimestamp)
        val advanced = clock.observe("2026-10-15T12:01:00.000Z-0000-remote")
        assertEquals("2026-10-15T12:01:00.000Z-0001-local", advanced.next(now).timestamp)
    }
}
