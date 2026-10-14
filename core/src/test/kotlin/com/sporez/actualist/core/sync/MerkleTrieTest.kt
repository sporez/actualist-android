package com.sporez.actualist.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Replays `packages/crdt/src/crdt/merkle.test.ts` (pinned Actual v26.9.0) as Actualist's
 * `MerkleTrieTests` does, against the Kotlin port.
 */
class MerkleTrieTest {
    private val node = "0123456789ABCDEF"

    private fun minute(timestamp: String) = SyncTimestamp.parse(timestamp)!!.milliseconds / 60_000

    private fun trie(entries: List<Pair<String, Int>>, start: MerkleTrie = MerkleTrie.EMPTY): MerkleTrie =
        entries.fold(start) { trie, (timestamp, hash) -> trie.insert(minute(timestamp), hash) }

    private fun iso(millis: Long?) = millis?.let(SyncTimestamp::wallTimeString)

    @Test
    fun realTimestampsHashLikeUpstreamAndMatchTheSnapshotRoot() {
        val first = SyncTimestamp.parse("2018-11-12T13:21:40.122Z-0000-$node")!!
        val second = SyncTimestamp.parse("2018-11-13T13:21:40.122Z-0000-$node")!!
        assertEquals(1_983_295_247, MerkleHash.hash(first))
        assertEquals(1_469_038_940, MerkleHash.hash(second))
        val trie = MerkleTrie.EMPTY.insert(first).insert(second)
        assertEquals(565_800_531, trie.hash)
    }

    @Test
    fun hashUsesTheNormalizedTimestampNotTheStoredText() {
        val stored = SyncTimestamp.parse("2018-11-12T13:21:40.122Z-00ab-node1")!!
        val canonical = "2018-11-12T13:21:40.122Z-00AB-00000000000node1"
        assertEquals(canonical, MerkleHash.canonical(stored))
        assertEquals(MerkleHash.hash(canonical), MerkleHash.hash(stored))
        assertNotEquals(MerkleHash.hash("2018-11-12T13:21:40.122Z-00ab-node1"), MerkleHash.hash(stored))
    }

    @Test
    fun diffReturnsTheCorrectTimeDifference() {
        val messages = listOf(
            "2018-11-13T13:20:40.122Z-0000-$node" to 1000,
            "2018-11-14T13:05:35.122Z-0000-$node" to 1100,
            "2018-11-15T22:19:00.122Z-0000-$node" to 1200,
            "2018-11-20T13:19:40.122Z-0000-$node" to 1300,
            "2018-11-25T13:19:40.122Z-0000-$node" to 1400,
        )
        var trie1 = trie(messages.subList(0, 3))
        var trie2 = trie(messages.subList(3, 5))
        assertEquals(788, trie1.hash)
        assertEquals(108, trie2.hash)
        assertEquals("2018-11-02T17:15:00.000Z", iso(MerkleTrie.diff(trie1, trie2)))

        trie1 = trie(messages.subList(3, 5), trie1)
        trie2 = trie(messages.subList(0, 3), trie2)
        assertEquals(888, trie1.hash)
        assertEquals(trie2.hash, trie1.hash)
        assertNull(MerkleTrie.diff(trie1, trie2))
    }

    @Test
    fun diffingAnEmptyTrieReturnsZero() {
        val populated = MerkleTrie.EMPTY.insert(SyncTimestamp.parse("2009-01-02T10:17:37.789Z-0000-0000testinguuid1")!!)
        assertEquals(0L, MerkleTrie.diff(MerkleTrie.EMPTY, populated))
        assertEquals(0L, MerkleTrie.diff(populated, MerkleTrie.EMPTY))
    }

    private val pruneMessages = listOf(
        "2018-11-01T01:00:00.000Z-0000-$node" to 1000, "2018-11-01T01:09:00.000Z-0000-$node" to 1100,
        "2018-11-01T01:18:00.000Z-0000-$node" to 1200, "2018-11-01T01:27:00.000Z-0000-$node" to 1300,
        "2018-11-01T01:36:00.000Z-0000-$node" to 1400, "2018-11-01T01:45:00.000Z-0000-$node" to 1500,
        "2018-11-01T01:54:00.000Z-0000-$node" to 1600, "2018-11-01T02:03:00.000Z-0000-$node" to 1700,
        "2018-11-01T02:10:00.000Z-0000-$node" to 1800, "2018-11-01T02:19:00.000Z-0000-$node" to 1900,
        "2018-11-01T02:28:00.000Z-0000-$node" to 2000, "2018-11-01T02:37:00.000Z-0000-$node" to 2100,
    )

    @Test
    fun pruningKeepsTheRootHashAndTheLastTwoKeysPerLevel() {
        val trie = trie(pruneMessages)
        assertEquals(2496, trie.hash)
        val pruned = trie.pruned()
        assertEquals(2496, pruned.hash)
        assertNotEquals(trie, pruned)
        assertEquals(pruned, pruned.pruned())
        assertEquals(MerkleTrie.EMPTY, MerkleTrie.EMPTY.pruned())
    }

    @Test
    fun diffingDifferentlyShapedTriesMatchesUpstream() {
        val trie = trie(pruneMessages)
        assertEquals("1970-01-01T00:00:00.000Z", iso(MerkleTrie.diff(MerkleTrie.EMPTY, trie)))
        assertEquals("1970-01-01T00:00:00.000Z", iso(MerkleTrie.diff(trie, MerkleTrie.EMPTY)))

        val older = "2018-11-01T00:59:00.000Z-0000-$node" to 900
        val trie1 = trie(listOf(older), trie)
        assertEquals("2018-11-01T00:54:00.000Z", iso(MerkleTrie.diff(trie1, trie)))
        assertEquals("2018-11-01T00:45:00.000Z", iso(MerkleTrie.diff(trie1.pruned(), trie)))
        assertEquals("2018-11-01T00:45:00.000Z", iso(MerkleTrie.diff(trie1, trie.pruned())))
        assertEquals("2018-11-01T00:45:00.000Z", iso(MerkleTrie.diff(trie1.pruned(), trie.pruned())))

        val trie2 = trie(listOf(older, "2018-11-01T01:15:00.000Z-0000-$node" to 1422), trie)
        assertEquals("2018-11-01T00:54:00.000Z", iso(MerkleTrie.diff(trie2, trie)))
        assertEquals("2018-11-01T00:45:00.000Z", iso(MerkleTrie.diff(trie2.pruned(), trie)))
        assertEquals("2018-11-01T00:45:00.000Z", iso(MerkleTrie.diff(trie2, trie.pruned())))
        assertEquals("2018-11-01T01:12:00.000Z", iso(MerkleTrie.diff(trie2.pruned(), trie.pruned())))
    }

    @Test
    fun jsonRoundTripsInUpstreamShapeAndTruncatesToInt32() {
        val trie = trie(pruneMessages).pruned()
        assertEquals(trie, MerkleTrie.fromJson(trie.jsonString))
        org.junit.Assert.assertTrue(trie.jsonString.contains("\"hash\":2496"))
        val wide = MerkleTrie.fromJson("{\"hash\":4294967297,\"1\":{\"hash\":-5}}")!!
        assertEquals(1, wide.hash)
        assertEquals(-5, wide.children.getValue(1).hash)
        assertEquals(MerkleTrie.EMPTY, MerkleTrie.fromJson("{}"))
    }

    @Test
    fun unreadableMerkleJsonIsNull() {
        for (text in listOf("", "not json", "[]", "{\"hash\":\"1\"}", "{\"0\":5}", "{\"hash\":true}")) {
            assertNull(text, MerkleTrie.fromJson(text))
        }
    }

    @Test
    fun absurdlyDeepJsonIsRejected() {
        var text = "{\"hash\":1}"
        repeat(40) { text = "{\"hash\":1,\"0\":$text}" }
        assertNull(MerkleTrie.fromJson(text))
    }

    @Test
    fun insertingTheSameTimestampTwiceCancels() {
        val timestamp = SyncTimestamp.parse("2026-08-01T12:00:00.000Z-0000-abcdef0123456789")!!
        val twice = MerkleTrie.EMPTY.insert(timestamp).insert(timestamp)
        assertEquals(0, twice.hash)
    }
}
