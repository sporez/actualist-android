package com.sporez.actualist.core.store

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.transactions.TransactionWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Actual's cell-level undo and redo (`server/undo.ts`) through [UndoHistory]. */
class UndoHistoryTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 15)

    private fun demo(block: (BudgetDatabase, UndoHistory) -> Unit) =
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            block(db, UndoHistory(db))
        }

    private fun BudgetDatabase.budgeted(category: String) = BudgetReads(this).fetchBudgetMonth("2026-10").category(category)!!.budgeted

    @Test
    fun anAssignmentUndoesAndRedoes() = demo { db, history ->
        val before = db.budgeted("groceries")
        history.record { BudgetWrites(db).assign("2026-10", "groceries", before + 12_300) }
        assertTrue(history.canUndo)
        val undone = history.undo()!!
        assertEquals(before, db.budgeted("groceries"))
        assertTrue(undone.all { it.dataset == "zero_budgets" && it.column == "amount" })
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        history.redo()
        assertEquals(before + 12_300, db.budgeted("groceries"))
        assertNull(history.redo())
    }

    @Test
    fun aNewTransactionIsTombstonedOnUndoAndResurrectedOnRedo() = demo { db, history ->
        var n = 0
        val writes = TransactionWrites(db, clock) { "undo-${++n}" }
        val id = history.record {
            writes.create(TransactionDraft(accountId = "checking", date = day, amount = -1_999, payeeName = "Synthetic Undo Shop", categoryId = "dining")).transactionId
        }
        fun visible() = LedgerReads(db).transactions(TransactionQuery(accountId = "checking")).any { it.id == id }
        assertTrue(visible())
        val undone = history.undo()!!
        assertFalse(visible())
        // Every cell of the new row becomes a tombstone cell, in reverse order, as Actual emits.
        assertTrue(undone.filter { it.row == id }.all { it.column == "tombstone" && it.serializedValue == "N:1" })
        // The payee the edit created is tombstoned too; its mapping row is left alone, as in Actual.
        assertTrue(undone.none { it.dataset == "payee_mapping" })
        history.redo()
        assertTrue(visible())
        assertEquals(0L, db.sql.queryLong("SELECT tombstone FROM transactions WHERE id = ?", id))
    }

    @Test
    fun aNoteUndoesToNull() = demo { db, history ->
        // A month without a note: the note row is new, so undo writes null (Actual's rule for notes).
        assertNull(BudgetReads(db).monthNote("2027-03"))
        history.record { db.commitLocal(listOf(CellDraft("notes", "budget-2027-03", "note", SyncValue.text("Synthetic note")))) }
        assertEquals("Synthetic note", BudgetReads(db).monthNote("2027-03"))
        history.undo()
        assertNull(BudgetReads(db).monthNote("2027-03"))
        // An existing note goes back to its old text.
        val october = BudgetReads(db).monthNote("2026-10")
        history.record { db.commitLocal(listOf(CellDraft("notes", "budget-2026-10", "note", SyncValue.text("Replaced")))) }
        history.undo()
        assertEquals(october, BudgetReads(db).monthNote("2026-10"))
    }

    @Test
    fun aGestureEditedAgainCannotBeUndone() = demo { db, history ->
        history.record { BudgetWrites(db).assign("2026-10", "dining", 1_000) }
        // A later write the history did not record (for example one that arrived by sync).
        BudgetWrites(db).assign("2026-10", "dining", 2_000)
        assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { history.undo() }
        assertEquals(2_000L, db.budgeted("dining"))
    }

    @Test
    fun aNewGestureDropsTheRedoTailAndUndoIsLastInFirstOut() = demo { db, history ->
        val dining = db.budgeted("dining")
        val shopping = db.budgeted("shopping")
        history.record { BudgetWrites(db).assign("2026-10", "dining", dining + 1_000) }
        history.record { BudgetWrites(db).assign("2026-10", "dining", dining + 2_000) }
        history.undo()
        assertEquals(dining + 1_000, db.budgeted("dining"))
        history.record { BudgetWrites(db).assign("2026-10", "shopping", shopping + 500) }
        assertFalse(history.canRedo)
        history.undo()
        assertEquals(shopping, db.budgeted("shopping"))
        history.undo()
        assertEquals(dining, db.budgeted("dining"))
        assertFalse(history.canUndo)
    }

    @Test
    fun unrecordedAndNonUndoableCommitsStayOutOfHistory() {
        TestDatabases.starter(clock = clock).use { db ->
            val history = UndoHistory(db)
            db.commitLocal(listOf(CellDraft("accounts", "a", "name", SyncValue.text("Outside"))))
            history.record { db.commitLocal(undoable = false) { listOf(CellDraft("accounts", "b", "name", SyncValue.text("Hidden"))) to Unit } }
            assertFalse(history.canUndo)
        }
    }
}
