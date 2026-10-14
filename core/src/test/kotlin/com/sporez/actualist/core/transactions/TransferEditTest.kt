package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Editing into, out of and between transfers, following Actual's `transfer.onUpdate`. */
class TransferEditTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 14)

    private fun demo(block: (BudgetDatabase, TransactionWrites) -> Unit) =
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { db ->
            var n = 0
            block(db, TransactionWrites(db, clock) { "edit-${++n}" })
        }

    private fun BudgetDatabase.row(id: String) = sql.queryOne(
        "SELECT acct, amount, description, category, notes, date, transferred_id, tombstone FROM transactions WHERE id = ?", id,
    )!!

    @Test
    fun choosingATransferPayeeCreatesThePairedRow() = demo { db, writes ->
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -2_000, payeeName = "Synthetic Shop", categoryId = "shopping")).transactionId
        writes.update(id, TransactionEdit(payeeId = "xfer-savings"))
        val source = db.row(id)
        val pairedId = source.string("transferred_id")!!
        val paired = db.row(pairedId)
        assertEquals("savings", paired.string("acct"))
        assertEquals(2_000L, paired.long("amount"))
        assertEquals("xfer-checking", paired.string("description"))
        assertEquals(id, paired.string("transferred_id"))
        // Both on budget: the source loses its category, as Actual clears it.
        assertNull(source.string("category"))
    }

    @Test
    fun leavingATransferRemovesThePairedRow() = demo { db, writes ->
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -3_000, payeeId = "xfer-savings", isTransfer = true)).transactionId
        val pairedId = db.row(id).string("transferred_id")!!
        writes.update(id, TransactionEdit(payeeId = "grocer"))
        assertEquals(true, db.row(pairedId).bool("tombstone"))
        assertNull(db.row(id).string("transferred_id"))
        assertEquals("grocer", db.row(id).string("description"))
    }

    @Test
    fun aTransferFollowsAmountNotesAndDestinationButNotTheDate() = demo { db, writes ->
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -4_000, payeeId = "xfer-savings", isTransfer = true, notes = "a")).transactionId
        val pairedId = db.row(id).string("transferred_id")!!
        writes.update(id, TransactionEdit(amount = -4_500, notes = "b", date = day.minusDays(3)))
        assertEquals(4_500L, db.row(pairedId).long("amount"))
        assertEquals("b", db.row(pairedId).string("notes"))
        assertEquals(20261014L, db.row(pairedId).long("date"))
        assertEquals(20261011L, db.row(id).long("date"))
        // Re-point to the card: the same paired row moves accounts.
        writes.update(id, TransactionEdit(payeeId = "xfer-credit"))
        assertEquals("credit", db.row(pairedId).string("acct"))
        assertEquals(pairedId, db.row(id).string("transferred_id"))
        // Moving the source account keeps the pair and renames its counterparty.
        writes.update(id, TransactionEdit(accountId = "savings"))
        assertEquals("savings", db.row(id).string("acct"))
        assertEquals("xfer-savings", db.row(pairedId).string("description"))
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { writes.update(id, TransactionEdit(accountId = "credit")) }
        val ledger = LedgerReads(db).accounts().associateBy { it.id }
        assertTrue(ledger.getValue("credit").balance != 0L)
    }

    @Test
    fun aSimpleRowMovesAccounts() = demo { db, writes ->
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -1_234, payeeName = "Synthetic Deli", categoryId = "dining")).transactionId
        writes.update(id, TransactionEdit(accountId = "credit"))
        assertEquals("credit", db.row(id).string("acct"))
        assertEquals("dining", db.row(id).string("category"))
    }
}
