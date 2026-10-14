package com.sporez.actualist.core.server

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.transactions.LedgerReads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Export a budget ZIP and import it as a new budget, as Actualist's portable archive does. */
class PortableBudgetTest {
    @Test
    fun exportsASanitizedSnapshotThatImportsWithAFreshHistory() {
        val clock = TestClock()
        val dir = TestDatabases.tempFile().parentFile
        DemoBudget.create(TestDatabases.driver, File(dir, "source.sqlite").absolutePath, LocalDate.of(2026, 10, 15), clock).use { source ->
            source.sql.execute("CREATE TABLE IF NOT EXISTS actualist_private (x TEXT)")
            val archive = PortableBudget.exportArchive(source, "Synthetic Export", TestDatabases.driver, File(dir, "scratch")) { "fresh-id" }
            assertEquals("Synthetic Export", PortableBudget.budgetName(archive))

            val imported = File(dir, "imported.sqlite")
            BudgetImporter.install(archive, imported, TestDatabases.driver)
            PortableBudget.resetSyncHistory(imported, TestDatabases.driver)
            BudgetDatabase.open(TestDatabases.driver, imported.absolutePath, "i1i1i1i1i1i1i1i1", clock).use { copy ->
                assertEquals(0L, copy.sql.queryLong("SELECT COUNT(*) FROM messages_crdt"))
                assertNull(copy.sql.queryOne("SELECT name FROM sqlite_master WHERE name = 'actualist_private'"))
                assertEquals(0L, copy.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE tombstone = 1"))
                assertEquals(
                    LedgerReads(source).accounts().associate { it.id to it.balance },
                    LedgerReads(copy).accounts().associate { it.id to it.balance },
                )
                assertEquals(BudgetReads(source).fetchBudgetMonth("2026-10").totalBudgeted, BudgetReads(copy).fetchBudgetMonth("2026-10").totalBudgeted)
                assertEquals(0L, copy.messageCount())
            }
        }
    }
}
