package com.sporez.actualist.core.importer

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ImportReconcilerTest {
    private val today = LocalDate.parse("2026-10-15")

    private fun database(): BudgetDatabase {
        val database = TestDatabases.starter(clock = TestClock())
        database.sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES ('acc', 'Checking', 0, 0, 0)")
        database.sql.execute("INSERT INTO payees (id, name, tombstone) VALUES ('pay-store', 'Synthetic Market', 0)")
        database.sql.execute(
            "INSERT INTO transactions (id, acct, date, amount, description, cleared, sort_order, tombstone) " +
                "VALUES ('ex-1', 'acc', 20261010, -1500, 'pay-store', 0, 1.0, 0)",
        )
        return database
    }

    private val rows = listOf(
        ImportRow(LocalDate.parse("2026-10-10"), -1500, payeeName = "SYNTHETIC MARKET", importedId = "csv-1"),
        ImportRow(LocalDate.parse("2026-10-20"), -999, payeeName = "NEW PLACE"),
    )

    @Test
    fun previewMatchesAnExistingRowAndAddsTheOther() {
        database().use { database ->
            val plan = ImportReconciler(database, TestClock()).preview("acc", rows, ImportReconcileOptions.CSV, today)
            assertEquals(1, plan.addedCount)
            assertEquals(1, plan.updatedCount)
            assertEquals(0, plan.unchangedCount)
            assertEquals(listOf(ImportDecisionKind.UPDATED, ImportDecisionKind.ADDED), plan.decisions.map { it.kind })
            assertEquals("ex-1", plan.decisions[0].existingId)
            // Preview writes nothing.
            assertEquals(1L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
        }
    }

    @Test
    fun applyWritesTheMatchAndTheInsertThenReimportIsUnchanged() {
        database().use { database ->
            var counter = 0
            val reconciler = ImportReconciler(database, TestClock()) { "id-${++counter}" }
            val result = reconciler.apply("acc", rows, ImportReconcileOptions.CSV, today)
            assertEquals(1, result.insertedCount)
            assertEquals(1, result.updatedCount)
            assertEquals("csv-1", database.sql.queryString("SELECT financial_id FROM transactions WHERE id = 'ex-1'"))
            assertEquals(2L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
            assertEquals("New Place", database.sql.queryString("SELECT name FROM payees WHERE id <> 'pay-store' AND name IS NOT NULL"))
            assertNull(database.sql.queryString("SELECT category FROM transactions WHERE id = 'ex-1'"))

            val again = reconciler.preview("acc", rows, ImportReconcileOptions.CSV, today)
            assertEquals(0, again.addedCount)
            assertEquals(0, again.updatedCount)
            assertEquals(2, again.unchangedCount)
        }
    }

    @Test
    fun anExactImportedIdMatchesOutsideTheDateWindow() {
        database().use { database ->
            val farRow = ImportRow(LocalDate.parse("2026-12-01"), -1500, payeeName = "Synthetic Market", importedId = "csv-9")
            database.sql.execute("UPDATE transactions SET financial_id = 'csv-9' WHERE id = 'ex-1'")
            val plan = ImportReconciler(database, TestClock()).preview("acc", listOf(farRow), ImportReconcileOptions.CSV, today)
            // The matched row keeps its date (updateDates is off) and only learns its imported payee.
            assertEquals(0, plan.addedCount)
            assertEquals(listOf(ImportDecisionKind.UPDATED), plan.decisions.map { it.kind })
            assertEquals("ex-1", plan.decisions[0].existingId)
            assertNull(plan.decisions[0].update?.dayId)
        }
    }
}
