package com.sporez.actualist.core.store

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionEdit
import com.sporez.actualist.core.transactions.TransactionWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** History row text and the undo review, derived from recorded cell changes. */
class HistoryPresenterTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 15)

    private fun demo(block: (BudgetDatabase, UndoHistory, HistoryPresenter) -> Unit) =
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            block(db, UndoHistory(db), HistoryPresenter(db))
        }

    private fun BudgetDatabase.budgeted(category: String) = BudgetReads(this).fetchBudgetMonth("2026-10").category(category)!!.budgeted

    private fun name(db: BudgetDatabase, dataset: String, id: String) = db.rowSnapshot(dataset, id)!!.getValue("name").removePrefix("S:")

    @Test
    fun anAssignmentNamesTheCategoryAndShowsBeforeAndAfter() = demo { db, history, presenter ->
        val before = db.budgeted("groceries")
        history.record { BudgetWrites(db).assign("2026-10", "groceries", before + 12_300) }
        val row = presenter.row(history.details().single().batches)
        assertEquals(HistoryKind.Assign, row.kind)
        assertTrue(row.title, row.title.startsWith("Assigned "))
        assertTrue(row.title, row.title.endsWith(" to " + name(db, "categories", "groceries")))
        assertEquals("+$123.00", row.amountText)
        assertEquals(1, row.amountSign)
        val change = row.changes.single()
        assertTrue(change.label.contains("Oct 2026"))
        assertTrue(change.from != change.to)
    }

    @Test
    fun aMoveBetweenCategoriesIsDescribedAsAMove() = demo { db, history, presenter ->
        val dining = db.budgeted("dining")
        val shopping = db.budgeted("shopping")
        history.record {
            BudgetWrites(db).assign("2026-10", "dining", dining - 500)
            BudgetWrites(db).assign("2026-10", "shopping", shopping + 500)
        }
        val row = presenter.row(history.details().single().batches)
        assertEquals(HistoryKind.Move, row.kind)
        assertEquals("Moved $5.00", row.title)
        assertTrue(row.detail.contains(name(db, "categories", "dining") + " → " + name(db, "categories", "shopping")))
    }

    @Test
    fun aNewTransactionShowsPayeeAccountCategoryAndAmount() = demo { db, history, presenter ->
        var n = 0
        val writes = TransactionWrites(db, clock) { "hist-${++n}" }
        history.record {
            writes.create(TransactionDraft(accountId = "checking", date = day, amount = -1_999, payeeName = "Synthetic History Shop", categoryId = "dining"))
        }
        val row = presenter.row(history.details().single().batches)
        assertEquals("Added transaction", row.title)
        assertEquals(HistoryKind.Transaction, row.kind)
        assertEquals("-$19.99", row.amountText)
        assertEquals(-1, row.amountSign)
        assertTrue(row.detail, row.detail.contains("Synthetic History Shop"))
        assertTrue(row.detail, row.detail.contains(name(db, "accounts", "checking")))
        assertTrue(row.detail, row.detail.contains(name(db, "categories", "dining")))
    }

    @Test
    fun anEditedTransactionShowsTheChangedFieldBeforeAndAfter() = demo { db, history, presenter ->
        var n = 0
        val writes = TransactionWrites(db, clock) { "hist-edit-${++n}" }
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -1_000, payeeName = "Synthetic Edit Shop", categoryId = "dining")).transactionId
        history.record { writes.update(id, TransactionEdit(amount = -2_500)) }
        val row = presenter.row(history.details().single().batches)
        assertEquals("Edited transaction", row.title)
        val amount = row.changes.single { it.label == "Amount" }
        assertEquals("-$10.00", amount.from)
        assertEquals("-$25.00", amount.to)
    }

    @Test
    fun aDeletedTransactionStillReadsAsDeleted() = demo { db, history, presenter ->
        var n = 0
        val writes = TransactionWrites(db, clock) { "hist-del-${++n}" }
        val id = writes.create(TransactionDraft(accountId = "checking", date = day, amount = -700, payeeName = "Synthetic Delete Shop", categoryId = "dining")).transactionId
        history.record { writes.delete(id) }
        val row = presenter.row(history.details().single().batches)
        assertEquals("Deleted transaction", row.title)
        assertTrue(row.detail.contains("Synthetic Delete Shop"))
    }

    @Test
    fun theReviewShowsTheCurrentValueAndWhatItBecomes() = demo { db, history, presenter ->
        val before = db.budgeted("groceries")
        history.record { BudgetWrites(db).assign("2026-10", "groceries", before + 4_000) }
        val batches = history.details().single().batches
        val undo = presenter.review(batches, undoing = true).entries.single()
        val redo = presenter.review(batches, undoing = false).entries.single()
        assertEquals(undo.current, redo.proposed)
        assertEquals(undo.proposed, redo.current)
        assertFalse(undo.current == undo.proposed)
    }

    @Test
    fun onlyTheLatestGestureUndoesAndAStaleOneStillRefuses() = demo { db, history, _ ->
        val dining = db.budgeted("dining")
        history.record { BudgetWrites(db).assign("2026-10", "dining", dining + 1_000) }
        history.record { BudgetWrites(db).assign("2026-10", "dining", dining + 2_000) }
        // Newest first: the latest gesture is the one undo reverses.
        assertEquals(2, history.details().size)
        history.undo()
        assertEquals(dining + 1_000, db.budgeted("dining"))
        assertTrue(history.details().first().undone)
        BudgetWrites(db).assign("2026-10", "dining", dining + 9_000)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { history.undo() }
        assertEquals(dining + 9_000, db.budgeted("dining"))
    }
}
