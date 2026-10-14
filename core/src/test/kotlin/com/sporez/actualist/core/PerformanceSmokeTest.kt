package com.sporez.actualist.core

import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.reports.ReportReads
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A large synthetic budget (30,000 transactions over five years, 40 categories) read through
 * the same paths the screens use. Bounds are generous: this catches pathological regressions
 * (a month view scanning per category, say), not small slowdowns. Timings are printed.
 */
class PerformanceSmokeTest {
    @Test
    fun largeBudgetReadsStayFast() {
        TestDatabases.starter(clock = TestClock()).use { database ->
            val sql = database.sql
            sql.transaction {
                sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES ('a1', 'Checking', 0, 0, 0, 1), ('a2', 'Card', 0, 0, 0, 2), ('a3', 'Brokerage', 1, 0, 0, 3)")
                sql.execute("INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES ('inc', 'Income', 1, 0, 0, 99)")
                sql.execute("INSERT INTO categories (id, name, is_income, cat_group, hidden, tombstone, sort_order) VALUES ('salary', 'Salary', 1, 'inc', 0, 0, 1)")
                for (g in 0 until 5) {
                    sql.execute("INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES (?, ?, 0, 0, 0, ?)", "g$g", "Group $g", g)
                    for (c in 0 until 8) {
                        val id = "c$g-$c"
                        sql.execute("INSERT INTO categories (id, name, is_income, cat_group, hidden, tombstone, sort_order) VALUES (?, ?, 0, ?, 0, 0, ?)", id, "Category $g-$c", "g$g", c)
                        sql.execute("INSERT INTO category_mapping (id, transferId) VALUES (?, ?)", id, id)
                    }
                }
                var n = 0
                for (year in 2022..2026) {
                    for (month in 1..12) {
                        val packedMonth = year * 100 + month
                        sql.execute("INSERT INTO transactions (id, acct, category, amount, date, isParent, isChild, tombstone, cleared, sort_order) VALUES (?, 'a1', 'salary', 600000, ?, 0, 0, 0, 1, 0)", "pay-$packedMonth", packedMonth * 100 + 1)
                        for (g in 0 until 5) for (c in 0 until 8) {
                            sql.execute("INSERT INTO zero_budgets (id, month, category, amount, carryover) VALUES (?, ?, ?, 12000, 0)", "$packedMonth-c$g-$c", packedMonth, "c$g-$c")
                        }
                        repeat(500) { i ->
                            n++
                            sql.execute(
                                "INSERT INTO transactions (id, acct, category, amount, date, isParent, isChild, tombstone, cleared, sort_order) VALUES (?, ?, ?, ?, ?, 0, 0, 0, 1, ?)",
                                "t$n", if (i % 3 == 0) "a2" else "a1", "c${i % 5}-${i % 8}", -(100L + i), packedMonth * 100 + (i % 28) + 1, n.toDouble(),
                            )
                        }
                    }
                }
            }
            assertEquals(30_060L, sql.queryLong("SELECT COUNT(*) FROM transactions"))

            fun <T> timed(label: String, limitMillis: Long, block: () -> T): T {
                val start = System.nanoTime()
                val value = block()
                val millis = (System.nanoTime() - start) / 1_000_000
                println("perf $label: ${millis}ms")
                assertTrue("$label took ${millis}ms (limit $limitMillis)", millis < limitMillis)
                return value
            }
            val reads = BudgetReads(database)
            val month = timed("budget month", 5_000) { reads.fetchBudgetMonth("2026-10") }
            assertEquals(40, month.categories.count { !it.isIncome })
            // Moving to another month reuses the memoized inputs; a write invalidates them.
            timed("adjacent month (memoized inputs)", 5_000) { reads.fetchBudgetMonth("2026-09") }
            com.sporez.actualist.core.budget.BudgetWrites(database).assign("2026-10", "c0-0", 99_999)
            assertEquals(99_999L, reads.fetchBudgetMonth("2026-10").category("c0-0")!!.budgeted)
            timed("transaction list (500)", 3_000) { LedgerReads(database).transactions(TransactionQuery(limit = 500)) }
            timed("account balances", 2_000) { LedgerReads(database).accounts() }
            timed("reports dashboard", 5_000) { ReportReads(database).dashboard("2026-10", "2026-10-15") }
        }
    }
}
