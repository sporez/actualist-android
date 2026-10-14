package com.sporez.actualist.core.reports

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportExplorerTest {
    @Test
    fun totalsMatchTheLedgerForARangeAccountAndCategory() {
        val clock = TestClock()
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), clock).use { db ->
            val explorer = ReportExplorer(db)
            val start = LocalDate.of(2026, 9, 1)
            val end = LocalDate.of(2026, 10, 31)
            fun sql(extra: String, vararg args: Any?): Long = db.sql.queryLong(
                "SELECT COALESCE(SUM(t.amount), 0) FROM transactions t " +
                    "WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND (t.isParent IS NULL OR t.isParent = 0) " +
                    "AND t.date BETWEEN 20260901 AND 20261031 AND t.transferred_id IS NULL $extra",
                *args,
            ) ?: 0L

            val all = explorer.explore(ExplorerFilter(start, end))
            assertTrue(all.rows.isNotEmpty())
            assertEquals(sql(""), all.net)
            assertEquals(all.net, all.byCategory.sumOf { it.total })

            val dining = explorer.explore(ExplorerFilter(start, end, categoryIds = setOf("dining")))
            assertEquals(sql("AND t.category = ?", "dining"), dining.net)
            assertTrue(dining.byCategory.all { it.categoryId == "dining" })

            val checking = explorer.explore(ExplorerFilter(start, end, accountIds = setOf("checking")))
            assertEquals(sql("AND t.acct = ?", "checking"), checking.net)
        }
    }
}
