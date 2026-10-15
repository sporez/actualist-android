package com.sporez.actualist.core.store

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** A gesture the app made on its own is listed in History, flagged, persisted, and never undoable. */
class AutomaticHistoryTest {
    private val clock = TestClock()
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun anAutomaticGestureIsFlaggedPersistedAndNotUndoable() {
        val file = TestDatabases.tempFile()
        val db = DemoBudget.create(TestDatabases.driver, file.absolutePath, day, clock)
        try {
            val history = UndoHistory(db)
            history.record(true) { BudgetWrites(db).assign("2026-10", "groceries", 777) }
            assertTrue(history.details().single().automatic)
            assertFalse(history.canUndo)
            assertNull(history.undo())
            assertEquals(
                HistoryExplanations.AUTOMATIC,
                HistoryExplanations.undoBlockedReason(0, false, 0, history.details().single().automatic),
            )
        } finally {
            db.close()
        }
        TestDatabases.reopen(file, DemoBudget.NODE_ID, clock).use { reopened ->
            val history = UndoHistory(reopened)
            assertTrue(history.details().single().automatic)
            assertFalse(history.canUndo)
        }
    }

    @Test
    fun aManualGestureIsNotAutomatic() {
        val db = DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock)
        db.use {
            val history = UndoHistory(it)
            history.record { BudgetWrites(it).assign("2026-10", "groceries", 555) }
            assertFalse(history.details().single().automatic)
            assertTrue(history.canUndo)
        }
    }
}
