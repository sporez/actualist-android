package com.sporez.actualist.core

import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.query.TransactionQueries
import com.sporez.actualist.core.reports.ReportReads
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A smoke test against a large synthetic budget (50,000 transactions over about 7 years), with
 * generous limits for a shared CI runner. It catches accidental full scans or per-row queries in
 * the hot reads, not fine-grained regressions.
 */
class LargeBudgetPerformanceTest {
    private fun timed(label: String, limitMillis: Long, block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        val millis = (System.nanoTime() - start) / 1_000_000
        println("$label: ${millis}ms (limit ${limitMillis}ms)")
        assertTrue("$label took ${millis}ms, over ${limitMillis}ms", millis <= limitMillis)
        return millis
    }

    @Test
    fun hotReadsStayFastOnALargeBudget() {
        val day = LocalDate.of(2026, 10, 15)
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, clock).use { db ->
            val categories = listOf("groceries", "dining", "shopping", "entertainment", "transportation", "utilities")
            val accounts = listOf("checking", "credit")
            timed("seed 50,000 rows", 60_000) {
                db.sql.transaction {
                    for (i in 0 until 50_000) {
                        val date = day.minusDays((i % 2_500).toLong())
                        db.sql.execute(
                            "INSERT INTO transactions (id, acct, amount, category, description, date, isParent, isChild, tombstone, cleared, sort_order) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?)",
                            "bulk-$i", accounts[i % 2], -((i % 9_000) + 100).toLong(), categories[i % categories.size], "grocer",
                            date.year * 10_000L + date.monthValue * 100L + date.dayOfMonth, i % 3 != 0, i.toDouble(),
                        )
                    }
                }
            }
            assertEquals(50_000L, db.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE id LIKE 'bulk-%'"))
            timed("budget month", 5_000) { BudgetReads(db).fetchBudgetMonth("2026-10") }
            timed("budget month (memoized)", 1_000) { BudgetReads(db).fetchBudgetMonth("2026-10") }
            timed("account balances", 3_000) { LedgerReads(db).accounts() }
            timed("feed, newest 500", 3_000) { assertEquals(500, LedgerReads(db).transactions(TransactionQuery(limit = 500)).size) }
            timed("search feed", 5_000) { LedgerReads(db).transactions(TransactionQuery(search = "grocer", limit = 500)) }
            timed("structured query page", 5_000) { TransactionQueries(db).query(accountId = "checking", limit = 100) }
            timed("reports dashboard", 10_000) { ReportReads(db).dashboard("2026-10", "2026-10-15") }
        }
    }
}
