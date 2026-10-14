package com.sporez.actualist.core.db

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.sync.MerkleTrie
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncTimestamp
import com.sporez.actualist.core.sync.SyncValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetDatabaseTest {
    private val clock = TestClock()

    private fun rebuiltTrie(database: BudgetDatabase): MerkleTrie {
        var trie = MerkleTrie.EMPTY
        for (row in database.sql.query("SELECT timestamp FROM messages_crdt")) {
            trie = trie.insert(SyncTimestamp.parse(row.string("timestamp")!!)!!)
        }
        return trie.pruned()
    }

    private fun remote(minuteOffset: Int, dataset: String, row: String, column: String, value: String, counter: Int = 0) = SyncMessage(
        SyncTimestamp.wallTimeString(clock.millis + minuteOffset * 60_000L) + "-%04X".format(counter) + "-remote0000000001",
        dataset,
        row,
        column,
        value,
    )

    @Test
    fun starterSchemaHasActualTables() {
        TestDatabases.starter(clock = clock).use { database ->
            for (table in listOf("accounts", "transactions", "categories", "category_groups", "zero_budgets", "zero_budget_months",
                "messages_crdt", "messages_clock", "payees", "rules", "schedules", "notes", "preferences")) {
                assertTrue(table, database.tableExists(table))
            }
            assertTrue("acct" in database.columnSet("transactions"))
            assertEquals(0L, database.messageCount())
        }
    }

    @Test
    fun localCommitAppliesLogsEnqueuesAndAdvancesTheClock() {
        TestDatabases.starter(clock = clock).use { database ->
            val messages = database.commitLocal(
                listOf(
                    CellDraft("accounts", "acct-1", "name", SyncValue.text("Synthetic Checking")),
                    CellDraft("accounts", "acct-1", "offbudget", SyncValue.bool(false)),
                ),
            )
            assertEquals(2, messages.size)
            assertEquals("2026-10-15T12:00:00.000Z-0000-node1", messages[0].timestamp)
            assertEquals("2026-10-15T12:00:00.000Z-0001-node1", messages[1].timestamp)
            assertEquals("Synthetic Checking", database.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
            assertEquals(2L, database.messageCount())
            assertEquals(2, database.pendingLocalMessageCount())
            assertEquals(messages[1].timestamp, database.localClockTimestamp)
            val pending = database.pendingLocalMessages()
            assertEquals(listOf("S:Synthetic Checking", "N:0"), pending.map { it.message.serializedValue })
            assertEquals(SyncTimestamp.ZERO, pending[0].baseTimestamp)
            assertEquals(rebuiltTrie(database), database.merkleTrie())
        }
    }

    @Test
    fun failedCommitRollsBackRowsLogOutboxAndClock() {
        TestDatabases.starter(clock = clock).use { database ->
            database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("A"))))
            val clockBefore = database.localClockTimestamp
            val trieBefore = database.merkleTrie()
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                database.commitLocal(
                    listOf(
                        CellDraft("accounts", "acct-1", "name", SyncValue.text("B")),
                        CellDraft("accounts", "acct-1", "no_such_column", SyncValue.text("x")),
                    ),
                )
            }
            assertEquals("A", database.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
            assertEquals(1L, database.messageCount())
            assertEquals(1, database.pendingLocalMessageCount())
            assertEquals(clockBefore, database.localClockTimestamp)
            assertEquals(trieBefore, database.merkleTrie())
        }
    }

    @Test
    fun reservedAndUnknownDatasetsAreRefusedLocally() {
        TestDatabases.starter(clock = clock).use { database ->
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                database.commitLocal(listOf(CellDraft("messages_crdt", "x", "value", SyncValue.text("x"))))
            }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                database.commitLocal(listOf(CellDraft("actualist_outbox", "x", "value", SyncValue.text("x"))))
            }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) {
                database.commitLocal(listOf(CellDraft("no_table", "x", "value", SyncValue.text("x"))))
            }
        }
    }

    @Test
    fun remoteApplyIsLastWriteWinsPerCell() {
        TestDatabases.starter(clock = clock).use { database ->
            val older = remote(-2, "accounts", "acct-1", "name", "S:Older")
            val newer = remote(-1, "accounts", "acct-1", "name", "S:Newer")
            assertEquals(1, database.applyRemoteMessages(listOf(newer)).appliedMessageCount)
            // The older write arrives late: stored for the log and merkle, never applied.
            assertEquals(0, database.applyRemoteMessages(listOf(older)).appliedMessageCount)
            assertEquals("Newer", database.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
            assertEquals(2L, database.messageCount())
            assertEquals(rebuiltTrie(database), database.merkleTrie())
            // Remote messages are never enqueued for upload.
            assertEquals(0, database.pendingLocalMessageCount())
        }
    }

    @Test
    fun batchOrderDoesNotMatterForTheWinner() {
        val messages = listOf(
            remote(-3, "categories", "cat", "name", "S:First"),
            remote(-1, "categories", "cat", "name", "S:Third"),
            remote(-2, "categories", "cat", "name", "S:Second"),
        )
        for (order in listOf(messages, messages.reversed(), listOf(messages[1], messages[0], messages[2]))) {
            TestDatabases.starter(clock = clock).use { database ->
                database.applyRemoteMessages(order)
                assertEquals("Third", database.sql.queryString("SELECT name FROM categories WHERE id = 'cat'"))
            }
        }
    }

    @Test
    fun exactDuplicatesAreSkippedSoTheMerkleDoesNotCancel() {
        TestDatabases.starter(clock = clock).use { database ->
            val message = remote(-1, "payees", "p1", "name", "S:Synthetic Payee")
            database.applyRemoteMessages(listOf(message))
            val trie = database.merkleTrie()
            assertTrue(trie.hash != 0)
            val second = database.applyRemoteMessages(listOf(message, message))
            assertEquals(0, second.appliedMessageCount)
            assertEquals(1L, database.messageCount())
            assertEquals(trie, database.merkleTrie())
        }
    }

    @Test
    fun reservedDatasetsAndUnknownColumnsAreStoredButNotApplied() {
        TestDatabases.starter(clock = clock).use { database ->
            val result = database.applyRemoteMessages(
                listOf(
                    remote(-3, "actualist_outbox", "x", "value", "S:evil"),
                    remote(-2, "accounts", "acct-1", "not_a_column", "S:x"),
                    remote(-1, "a_future_table", "x", "y", "S:z"),
                ),
            )
            assertEquals(3, result.appliedMessageCount)
            assertEquals(3L, database.messageCount())
            assertEquals(0, database.pendingLocalMessageCount())
            assertNull(database.sql.queryOne("SELECT id FROM accounts WHERE id = 'acct-1'"))
        }
    }

    @Test
    fun unreadableValuesAreQuarantinedWithoutWedgingTheBatch() {
        TestDatabases.starter(clock = clock).use { database ->
            val bad = remote(-2, "accounts", "acct-1", "name", "Q:bogus")
            val good = remote(-1, "accounts", "acct-2", "name", "S:Fine")
            val result = database.applyRemoteMessages(listOf(bad, good))
            assertEquals(listOf(bad.timestamp), result.quarantinedTimestamps)
            assertEquals(1, result.appliedMessageCount)
            assertEquals("Fine", database.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-2'"))
            assertNull(database.sql.queryOne("SELECT id FROM accounts WHERE id = 'acct-1'"))
        }
    }

    @Test
    fun aMalformedOrFarFutureBatchIsRejectedWhole() {
        TestDatabases.starter(clock = clock).use { database ->
            val good = remote(-1, "accounts", "acct-1", "name", "S:A")
            val future = remote(30, "accounts", "acct-2", "name", "S:B")
            assertThrows(LocalFirstException.ClockDrift::class.java) { database.applyRemoteMessages(listOf(good, future)) }
            assertEquals(0L, database.messageCount())
            val malformed = good.copy(timestamp = "nope")
            assertThrows(LocalFirstException.InvalidSyncTimestamp::class.java) { database.applyRemoteMessages(listOf(good, malformed)) }
            assertEquals(0L, database.messageCount())
        }
    }

    @Test
    fun remoteApplyAdvancesTheLocalClockPastObservedTimestamps() {
        TestDatabases.starter(clock = clock).use { database ->
            val ahead = remote(2, "accounts", "acct-1", "name", "S:Remote", counter = 7)
            database.applyRemoteMessages(listOf(ahead))
            val local = database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("Local"))))
            assertTrue(local.single().timestamp > ahead.timestamp)
            assertEquals("Local", database.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
        }
    }

    @Test
    fun localWriteIsRefusedWhenTheCellHasANewerMessage() {
        // A peer with a skewed clock wrote within the allowed drift; the local clock is not told.
        TestDatabases.starter(clock = clock).use { database ->
            database.sql.execute(
                "INSERT INTO messages_crdt (timestamp, dataset, row, column, value) VALUES (?, 'accounts', 'acct-1', 'name', 'S:Peer')",
                SyncTimestamp.wallTimeString(clock.millis + 60_000) + "-0000-peer",
            )
            assertThrows(LocalFirstException.LocalWriteSuperseded::class.java) {
                database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("Mine"))))
            }
            assertEquals(0, database.pendingLocalMessageCount())
        }
    }

    @Test
    fun outboxClockAndMerkleSurviveRestart() {
        val file = TestDatabases.tempFile()
        val committed: List<SyncMessage> = TestDatabases.starter(clock = clock, file = file).use { database ->
            database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("Before restart"))))
        }
        TestDatabases.reopen(file, clock = clock).use { database ->
            assertEquals(committed.last().timestamp, database.localClockTimestamp)
            assertEquals(1, database.pendingLocalMessageCount())
            assertEquals(rebuiltTrie(database), database.merkleTrie())
            val next = database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("After restart"))))
            assertTrue(next.single().timestamp > committed.last().timestamp)
            database.deletePendingLocalMessages(database.pendingLocalMessages().map { it.message.timestamp })
            assertEquals(0, database.pendingLocalMessageCount())
        }
    }

    @Test
    fun drainingTheOutboxKeepsTheLog() {
        TestDatabases.starter(clock = clock).use { database ->
            database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("Demo"))))
            assertEquals(1, database.drainAllPendingLocalMessages())
            assertEquals(0, database.pendingLocalMessageCount())
            assertEquals(1L, database.messageCount())
        }
    }

    @Test
    fun failedUploadAttemptsAreRecorded() {
        TestDatabases.starter(clock = clock).use { database ->
            val message = database.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("X")))).single()
            database.markPendingLocalMessagesFailed(listOf(message.timestamp), "network unavailable")
            val pending = database.pendingLocalMessages().single()
            assertEquals(1, pending.attemptCount)
            assertEquals("network unavailable", pending.lastError)
        }
    }

    @Test
    fun anImportedUntrustedClockIsRebuiltOnce() {
        val file = TestDatabases.tempFile()
        TestDatabases.starter(nodeId = null, clock = clock, file = file).use { database ->
            database.sql.execute(
                "INSERT INTO messages_crdt (timestamp, dataset, row, column, value) VALUES (?, 'accounts', 'a', 'name', 'S:x')",
                "2026-10-15T11:00:00.000Z-0000-peer",
            )
            database.sql.execute("DELETE FROM actualist_local_migrations")
            database.sql.execute("INSERT OR REPLACE INTO messages_clock (id, clock) VALUES (1, '{\"timestamp\":\"bad\",\"merkle\":{\"hash\":42}}')")
        }
        TestDatabases.reopen(file, clock = clock).use { database ->
            assertEquals(rebuiltTrie(database), database.merkleTrie())
            assertFalse(database.merkleTrie().hash == 42)
            assertTrue(database.localMigrationApplied(BudgetDatabase.MERKLE_REBUILD_MIGRATION))
        }
    }
}
