package com.sporez.actualist.core.csv

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.importer.ImportDecisionKind
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvImportReviewTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun previewMarksNewRowsThenDuplicatesAndWritesNothing() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { database ->
            val csv = "Date,Payee,Notes,Amount\n2026-10-14,Review Cafe,,-4.75\n2026-10-13,Review Books,,-12.00\n"
            val reviewer = CsvImportReviewer(database, clock, day)
            val before = LedgerReads(database).transactions(TransactionQuery(accountId = "checking", limit = 10_000)).size
            val first = reviewer.review("checking", csv)
            assertEquals(2, first.added)
            assertTrue(first.rows.all { it.kind == ImportDecisionKind.ADDED })
            assertEquals(setOf(0, 1), first.defaultIncluded)
            assertEquals(before, LedgerReads(database).transactions(TransactionQuery(accountId = "checking", limit = 10_000)).size)

            val plan = reviewer.apply("checking", first, first.defaultIncluded)
            assertEquals(2, plan.imported)
            val second = reviewer.review("checking", csv)
            assertEquals(0, second.added)
            assertTrue(second.rows.none { it.selectable && it.kind == ImportDecisionKind.ADDED })
        }
    }

    @Test
    fun leftOutRowsAreNotImported() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { database ->
            val csv = "Date,Payee,Notes,Amount\n2026-10-14,Keep Cafe,,-4.75\n2026-10-13,Skip Books,,-12.00\n"
            val reviewer = CsvImportReviewer(database, clock, day)
            val review = reviewer.review("checking", csv)
            val plan = reviewer.apply("checking", review, setOf(0))
            assertEquals(1, plan.imported)
            val rows = LedgerReads(database).transactions(TransactionQuery(accountId = "checking", limit = 10_000))
            assertTrue(rows.any { it.amount == -475L && it.date == LocalDate.of(2026, 10, 14) })
            assertFalse(rows.any { it.amount == -1200L && it.date == LocalDate.of(2026, 10, 13) })
        }
    }

    @Test
    fun columnsReportWhatTheHeaderRowProvides() {
        val columns = CsvImportReviewer.columns(CsvParser.parse("Date,Payee,Amount\n2026-10-14,A,-1\n"))
        assertTrue(columns.single { it.field == "Date" }.found)
        assertFalse(columns.single { it.field == "Notes" }.found)
        assertEquals("Amount", columns.single { it.field == "Amount" }.header)
        val positional = CsvImportReviewer.columns(CsvParser.parse("2026-10-14,A,,-1\n", hasHeaderRow = false))
        assertEquals(4, positional.size)
        assertEquals(3, positional.single { it.field == "Amount" }.position)
    }
}
