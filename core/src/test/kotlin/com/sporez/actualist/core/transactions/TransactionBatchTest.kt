package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.store.UndoHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TransactionBatchTest {
    private val day = LocalDate.of(2026, 10, 14)

    @Test
    fun batchActionsAreAtomicSkipLockedRowsAndUndoAsOne() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { db ->
            var n = 0
            val writes = TransactionWrites(db, clock) { "batch-${++n}" }
            val a = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -1_000, payeeName = "Synthetic A", cleared = false)).transactionId
            val b = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -2_000, payeeName = "Synthetic B", cleared = false)).transactionId
            val transfer = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -3_000, payeeId = "xfer-savings", isTransfer = true)).transactionId
            val pair = db.sql.queryString("SELECT transferred_id FROM transactions WHERE id = ?", transfer)!!
            val locked = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -4_000, payeeName = "Synthetic Locked", cleared = true)).transactionId
            db.sql.execute("UPDATE transactions SET reconciled = 1 WHERE id = ?", locked)

            val batch = TransactionBatch(db, clock)
            val history = UndoHistory(db)
            assertEquals(BatchResult(2, 1), history.record { batch.setCleared(listOf(a, b, locked), true) })
            assertTrue(LedgerReads(db).transactions(TransactionQuery(accountId = "checking")).filter { it.id in setOf(a, b) }.all { it.cleared })
            // One gesture: one undo restores both rows.
            history.undo()
            assertTrue(LedgerReads(db).transactions(TransactionQuery(accountId = "checking")).filter { it.id in setOf(a, b) }.none { it.cleared })

            // Transfers keep no category between on-budget accounts; they are skipped.
            assertEquals(BatchResult(2, 2), batch.categorize(listOf(a, b, transfer, locked), "dining"))
            assertEquals("dining", db.sql.queryString("SELECT category FROM transactions WHERE id = ?", a))

            // Both legs of the transfer selected: each row is tombstoned once.
            assertEquals(BatchResult(2, 1), batch.delete(listOf(transfer, pair, locked)))
            assertEquals(1L, db.sql.queryLong("SELECT tombstone FROM transactions WHERE id = ?", pair))
            assertFalse(db.sql.queryLong("SELECT tombstone FROM transactions WHERE id = ?", locked) == 1L)
        }
    }
}
