package com.sporez.actualist.core.reports

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.query.QueryTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportDrilldownTest {
    private fun tx(id: String, date: String, amount: Long, parent: Boolean = false, child: Boolean = false, children: List<QueryTransaction> = emptyList()) =
        QueryTransaction(
            id = id, accountId = "a", date = date, amount = amount, payeeId = null, payeeName = null, importedPayee = null,
            categoryId = null, notes = null, cleared = false, reconciled = false, isParent = parent, isChild = child,
            parentId = null, scheduleId = null, subtransactions = children,
        )

    @Test
    fun groupsByDayNewestFirstAndLabelsRoles() {
        val childA = tx("c1", "2026-03-02", -300, child = true)
        val childB = tx("c2", "2026-03-02", -200, child = true)
        val parent = tx("p", "2026-03-02", -500, parent = true, children = listOf(childA, childB))
        val older = tx("o", "2026-03-01", -100)
        val result = ReportDrilldownProjection.project(listOf(older, parent), setOf("c1", "o"))
        assertEquals(listOf("2026-03-02", "2026-03-01"), result.groups.map { it.date })
        val first = result.groups[0].rows
        assertEquals(listOf("p", "c1", "c2"), first.map { it.id })
        assertEquals(DrilldownKind.SPLIT_PARENT, first[0].kind)
        assertEquals(DrilldownKind.SPLIT_CHILD, first[1].kind)
        assertEquals(DrilldownRole.CONTEXT, first[0].role)
        assertEquals(DrilldownRole.COUNTED, first[1].role)
        assertEquals(DrilldownRole.CONTEXT, first[2].role)
        assertEquals(2, result.countedCount)
    }

    @Test
    fun duplicateRowsAppearOnce() {
        val child = tx("c1", "2026-03-02", -300, child = true)
        val parent = tx("p", "2026-03-02", -300, parent = true, children = listOf(child))
        val result = ReportDrilldownProjection.project(listOf(parent, child), setOf("c1"))
        assertEquals(2, result.groups.single().rows.size)
    }

    @Test
    fun noAccountsListsNothingAndNullListsEveryAccount() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), TestClock()).use { db ->
            val drilldown = ReportDrilldown(db)
            val start = LocalDate.of(2026, 9, 1)
            val end = LocalDate.of(2026, 10, 31)
            assertTrue(drilldown.load(ExplorerFilter(start, end)).countedCount > 0)
            assertEquals(0, drilldown.load(ExplorerFilter(start, end, accountIds = emptySet())).countedCount)
            assertTrue(drilldown.load(ExplorerFilter(start, end, accountIds = emptySet())).groups.isEmpty())
        }
    }
}
