package com.sporez.actualist.core.reports

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportCardsTest {
    private val base = "FROM transactions t JOIN accounts a ON a.id = t.acct " +
        "WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND (t.isParent IS NULL OR t.isParent = 0) " +
        "AND (a.tombstone IS NULL OR a.tombstone = 0)"

    @Test
    fun cardsMatchIndependentLedgerSums() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), TestClock()).use { db ->
            val reads = ReportCardReads(db)
            fun ledger(extra: String): Long = db.sql.queryLong("SELECT COALESCE(SUM(t.amount), 0) $base $extra") ?: 0L
            // Non-income, on-budget spending in a date window (days 1..28 buckets, so month-end 29+ folds into 28).
            fun spent(from: Int, to: Int): Long = -ledger(
                "AND COALESCE(a.offbudget, 0) = 0 AND t.date BETWEEN $from AND $to " +
                    "AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = t.category AND c.is_income = 1)",
            )

            val comparison = reads.monthComparison("2026-10", "2026-10-15")
            assertEquals(28, comparison.points.size)
            assertEquals(spent(20261001, 20261015), comparison.points[14].current)
            assertEquals(null, comparison.points[15].current)
            assertEquals(spent(20260901, 20260915), comparison.points[14].comparison)
            assertEquals(comparison.points[14].current!! - comparison.points[14].comparison, comparison.variance)
            assertEquals(spent(20260901, 20260930), comparison.points[27].comparison)

            val average = reads.threeMonthAverage("2026-10", "2026-10-15")
            val expectedAverage = (spent(20260701, 20260715) + spent(20260801, 20260815) + spent(20260901, 20260915)) / 3
            assertEquals(expectedAverage, average.averageExpenses)
            assertEquals(spent(20261001, 20261015), average.currentExpenses)
            assertEquals(average.currentExpenses - average.averageExpenses, average.variance)
            assertTrue(average.currentExpenses != 0L || average.averageExpenses != 0L)

            val calendar = reads.transactionCalendar("2026-10")
            assertEquals(listOf("2026-08", "2026-09", "2026-10"), calendar.map { it.month })
            val october = calendar.last()
            assertEquals(31, october.days.size)
            // 2026-10-01 is a Thursday: four blank cells in a Sunday-first week.
            assertEquals(4, october.leadingBlankCount)
            assertEquals(ledger("AND t.date BETWEEN 20261001 AND 20261031 AND t.amount > 0"), october.income)
            assertEquals(-ledger("AND t.date BETWEEN 20261001 AND 20261031 AND t.amount < 0"), october.expenses)
            assertEquals(october.income, october.days.sumOf { it.income })
            for (month in calendar) {
                val compact = month.month.replace("-", "")
                assertEquals(ledger("AND t.date BETWEEN ${compact}01 AND ${compact}31 AND t.amount > 0"), month.income)
            }
        }
    }
}
