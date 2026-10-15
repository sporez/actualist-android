package com.sporez.actualist.core.store

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.server.BudgetImporter
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The History table in the budget file: survives reopening, stays local, stays bounded. */
class HistoryPersistenceTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 15)

    private fun create(file: File): BudgetDatabase = DemoBudget.create(TestDatabases.driver, file.absolutePath, day, clock)

    private fun reopen(file: File): BudgetDatabase = TestDatabases.reopen(file, DemoBudget.NODE_ID, clock)

    private fun BudgetDatabase.budgeted(category: String) = BudgetReads(this).fetchBudgetMonth("2026-10").category(category)!!.budgeted

    private fun assign(db: BudgetDatabase, history: UndoHistory, amount: Long) {
        history.record { BudgetWrites(db).assign("2026-10", "groceries", amount) }
    }

    @Test
    fun aRecordedEditSurvivesReopeningTheBudget() {
        val file = TestDatabases.tempFile()
        var original = 0L
        val db = create(file)
        try {
            original = db.budgeted("groceries")
            assign(db, UndoHistory(db), original + 12_300)
        } finally {
            db.close()
        }
        reopen(file).use { reopened ->
            val history = UndoHistory(reopened)
            assertEquals(1, history.items().size)
            assertTrue(history.canUndo)
            assertFalse(history.canRedo)
            assertEquals(original + 12_300, reopened.budgeted("groceries"))
        }
    }

    @Test
    fun undoAndRedoWorkAfterReloadAndTheCursorPersists() {
        val file = TestDatabases.tempFile()
        var original = 0L
        val db = create(file)
        try {
            original = db.budgeted("groceries")
            assign(db, UndoHistory(db), original + 500)
        } finally {
            db.close()
        }
        val second = reopen(file)
        try {
            val history = UndoHistory(second)
            assertNotNull(history.undo())
            assertEquals(original, second.budgeted("groceries"))
        } finally {
            second.close()
        }
        reopen(file).use { third ->
            val history = UndoHistory(third)
            assertFalse(history.canUndo)
            assertTrue(history.canRedo)
            assertTrue(history.items().single().undone)
            assertNotNull(history.redo())
            assertEquals(original + 500, third.budgeted("groceries"))
        }
        reopen(file).use { fourth ->
            val history = UndoHistory(fourth)
            assertTrue(history.canUndo)
            assertFalse(history.canRedo)
        }
    }

    @Test
    fun aNewEditDropsThePersistedRedoTail() {
        val file = TestDatabases.tempFile()
        val db = create(file)
        try {
            val history = UndoHistory(db)
            val original = db.budgeted("groceries")
            assign(db, history, original + 100)
            history.undo()
            assign(db, history, original + 200)
        } finally {
            db.close()
        }
        reopen(file).use { reopened ->
            val history = UndoHistory(reopened)
            assertEquals(1, history.items().size)
            assertFalse(history.canRedo)
        }
    }

    @Test
    fun aRemoteChangeAfterReloadStillRefusesUndo() {
        val file = TestDatabases.tempFile()
        val db = create(file)
        lateinit var touched: SyncMessage
        try {
            val history = UndoHistory(db)
            assign(db, history, db.budgeted("groceries") + 700)
            touched = history.details().single().batches.first().messages.first()
        } finally {
            db.close()
        }
        reopen(file).use { reopened ->
            val history = UndoHistory(reopened)
            val remote = SyncMessage(
                SyncTimestamp.wallTimeString(clock.millis + 5_000) + "-0000-00000000000000aa",
                touched.dataset,
                touched.row,
                touched.column,
                "N:424242",
            )
            reopened.applyRemoteMessages(listOf(remote), clock.millis + 5_000)
            val error = assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { history.undo() }
            assertNotNull(error)
            assertTrue(history.canUndo)
        }
    }

    @Test
    fun theHistoryIsBoundedByCountAndAge() {
        val file = TestDatabases.tempFile()
        val db = create(file)
        try {
            val history = UndoHistory(db, limit = 3)
            val original = db.budgeted("groceries")
            for (step in 1..5) assign(db, history, original + step * 100L)
            assertEquals(3, history.items().size)
        } finally {
            db.close()
        }
        reopen(file).use { reopened ->
            assertEquals(3, UndoHistory(reopened, limit = 3).items().size)
            assertEquals(2, UndoHistory(reopened, limit = 2).items().size)
        }
        reopen(file).use { reopened ->
            val later = System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000
            val history = UndoHistory(reopened, limit = 3, nowMillis = { later })
            assertTrue(history.items().isEmpty())
            assertFalse(history.canUndo)
        }
    }

    @Test
    fun theTableIsLocalOnly() {
        assertTrue(BudgetDatabase.isReservedDataset(HistoryStore.TABLE))
        val file = TestDatabases.tempFile()
        val db = create(file)
        try {
            assign(db, UndoHistory(db), db.budgeted("groceries") + 50)
            val crdtDatasets = db.sql.query("SELECT DISTINCT dataset FROM messages_crdt").mapNotNull { it.string("dataset") }
            assertTrue(crdtDatasets.none { it.startsWith("actualist_") })
            val outboxDatasets = db.sql.query("SELECT DISTINCT dataset FROM actualist_outbox").mapNotNull { it.string("dataset") }
            assertTrue(outboxDatasets.none { it == HistoryStore.TABLE })
            assertEquals(1, HistoryStore(db).count())
        } finally {
            db.close()
        }
        BudgetImporter.sanitize(file, TestDatabases.driver)
        reopen(file).use { sanitized ->
            assertNull(sanitized.sql.queryOne("SELECT name FROM sqlite_master WHERE name = ?", HistoryStore.TABLE))
            assertTrue(UndoHistory(sanitized).items().isEmpty())
        }
    }

    @Test
    fun clearEmptiesThePersistedHistory() {
        val file = TestDatabases.tempFile()
        val db = create(file)
        try {
            val history = UndoHistory(db)
            assign(db, history, db.budgeted("groceries") + 50)
            history.clear()
        } finally {
            db.close()
        }
        reopen(file).use { reopened -> assertTrue(UndoHistory(reopened).items().isEmpty()) }
    }
}
