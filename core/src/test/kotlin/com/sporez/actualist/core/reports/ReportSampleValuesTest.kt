package com.sporez.actualist.core.reports

import com.sporez.actualist.core.query.QueryTransaction
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSampleValuesTest {
    private fun dashboard() = ReportsDashboard(
        anchorMonth = "2026-03",
        netWorth = NetWorthReport(listOf(MonthValue("2026-02", 500_000), MonthValue("2026-03", 640_000)), 640_000, 140_000),
        cashFlow = CashFlowSummary("2026-03", 300_000, 200_000, 100_000, 5_000),
        cashFlowHistory = listOf(
            CashFlowSummary("2026-02", 310_000, 190_000, 120_000, 0),
            CashFlowSummary("2026-03", 300_000, 200_000, 100_000, 5_000),
        ),
        spending = listOf(
            CategorySpending("c1", "Real Food", "Real Group", 40_000, 50_000),
            CategorySpending("c2", "Real Fun", "Real Group", 90_000, 0),
        ),
        budgetedExpenses = 50_000,
        hasData = true,
    )

    @Test
    fun dashboardTotalsAddUpFromTheProjectedParts() {
        val real = dashboard()
        val shown = ReportSampleValues.project(real)
        assertEquals(shown, ReportSampleValues.project(real))
        assertEquals(shown.netWorth.points.last().value, shown.netWorth.balance)
        assertEquals(shown.netWorth.points.last().value - shown.netWorth.points.first().value, shown.netWorth.change)
        assertEquals(shown.cashFlow.income - shown.cashFlow.expenses, shown.cashFlow.net)
        assertEquals(shown.cashFlow, shown.cashFlowHistory.last())
        assertNotEquals(real.cashFlow.income, shown.cashFlow.income)
        assertEquals(shown.spending.sumOf { it.budgeted }, shown.budgetedExpenses)
        assertEquals(0L, shown.spending.single { it.categoryId == "c2" }.budgeted)
        assertTrue(shown.spending.none { it.name.startsWith("Real") || it.groupName.startsWith("Real") })
        assertEquals(listOf("c1", "c2").toSet(), shown.spending.map { it.categoryId }.toSet())
        assertEquals(shown.spending.sortedByDescending { it.spent }, shown.spending)
    }

    @Test
    fun comparisonVarianceFollowsTheLastComparableDay() {
        val points = (1..28).map { DailyComparisonPoint(it, if (it <= 10) it * 1_000L else null, it * 1_500L) }
        val month = ReportSampleValues.project(MonthComparisonReport("2026-03", "2026-02", points, -5_000))
        val last = month.points.last { it.current != null }
        assertEquals(10, last.day)
        assertEquals(last.current!! - last.comparison, month.variance)
        assertTrue(month.points.drop(10).all { it.current == null })

        val average = ReportSampleValues.project(ThreeMonthAverageReport("2026-03", points, 10_000, 15_000, -5_000))
        assertEquals(last.day, average.points.last { it.current != null }.day)
        assertEquals(average.currentExpenses - average.averageExpenses, average.variance)
    }

    @Test
    fun calendarMonthTotalsAreTheSumOfTheProjectedDays() {
        val days = (1..3).map { TransactionCalendarDay("2026-03-0$it", it, 1_000L * it, if (it == 2) 0L else 700L) }
        val months = ReportSampleValues.project(listOf(TransactionCalendarMonth("2026-03", 0, days, 6_000, 1_400)))
        val month = months.single()
        assertEquals(month.days.sumOf { it.income }, month.income)
        assertEquals(month.days.sumOf { it.expenses }, month.expenses)
        assertEquals(0L, month.days[1].expenses)
        assertTrue(month.days.all { it.income > 0 })
    }

    private fun period(day: Int) = SeriesPeriod(LocalDate.of(2026, 3, day), LocalDate.of(2026, 3, day))

    private fun snapshot(metric: ReportMetric, catalog: SeriesCatalog = SeriesCatalog(emptyList(), emptyList())): SeriesSnapshot {
        val points = (1..3).map { SeriesPoint(period(it), 1_000L * it, 700L * it, 300L * it, 5_000L * it, 900L * it, 800L * it) }
        return SeriesSnapshot(
            SeriesQuery(metric, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), ReportInterval.DAY),
            points,
            SeriesTotals(6_000, 4_200, 1_800, 15_000, 10_000, 5_000, 5_400, 4_800),
            true,
            catalog,
        )
    }

    @Test
    fun spendingAndCashFlowTotalsSumThePointsAndBudgetReportsTakeTheLast() {
        val cash = ReportSampleValues.project(snapshot(ReportMetric.CASH_FLOW))
        assertEquals(cash.points.sumOf { it.income }, cash.totals.income)
        assertEquals(cash.points.sumOf { it.expenses }, cash.totals.expenses)
        assertEquals(cash.totals.income - cash.totals.expenses, cash.totals.net)
        assertTrue(cash.points.all { it.net == it.income - it.expenses && it.endingBalance == 0L })

        val budget = ReportSampleValues.project(snapshot(ReportMetric.BUDGET_OVERVIEW))
        assertEquals(budget.points.last().expenses, budget.totals.expenses)
        assertEquals(budget.points.last().budgeted, budget.totals.budgeted)

        val average = ReportSampleValues.project(snapshot(ReportMetric.SPENDING_AVERAGE))
        assertEquals(average.points.last().comparison, average.totals.averageSpending)
        assertEquals(0L, cash.totals.averageSpending)
    }

    @Test
    fun netWorthEndsOnOpeningPlusChange() {
        val shown = ReportSampleValues.project(snapshot(ReportMetric.NET_WORTH))
        assertEquals(shown.totals.openingBalance + shown.totals.balanceChange, shown.totals.endingBalance)
        assertEquals(shown.totals.endingBalance, shown.points.last().endingBalance)
        assertTrue(shown.points.all { it.income == 0L && it.expenses == 0L && it.budgeted == 0L })
    }

    @Test
    fun catalogNamesAreSampledAndIdsAndFlagsKept() {
        val catalog = SeriesCatalog(
            listOf(SeriesAccountOption("a1", "Real Checking", true, false)),
            listOf(SeriesCategoryOption("c1", "Real Food", "Real Group", false, true), SeriesCategoryOption("c2", "Loose", "", true, false)),
        )
        val shown = ReportSampleValues.project(catalog)
        assertEquals("a1", shown.accounts.single().id)
        assertTrue(shown.accounts.single().isOffBudget)
        assertTrue(shown.accounts.single().name.matches(Regex(".+ \\d\\d")))
        assertTrue(shown.categories.none { it.name.startsWith("Real") || it.groupName.startsWith("Real") })
        assertTrue(shown.categories[0].isHidden)
        assertEquals("Categories", shown.categories[1].groupName)
        assertTrue(shown.categories[1].isIncome)
    }

    private fun row(id: String, amount: Long?, payee: String? = "Corner Shop", category: String? = "c1", notes: String? = "private") = QueryTransaction(
        id = id, accountId = "a1", date = "2026-03-02", amount = amount, payeeId = "p1", payeeName = payee, importedPayee = null,
        categoryId = category, notes = notes, cleared = false, reconciled = false, isParent = false, isChild = false,
        parentId = null, scheduleId = null,
    )

    @Test
    fun explorerResultIsRecomputedFromTheProjectedRows() {
        val rows = listOf(row("t1", -2_500), row("t2", 9_000), row("t3", -400, category = null))
        val real = ExplorerResult(9_000, -2_900, emptyList(), rows)
        val shown = ReportSampleValues.project(real)
        assertEquals(shown.rows.sumOf { (it.amount ?: 0L).coerceAtLeast(0L) }, shown.income)
        assertEquals(shown.rows.sumOf { (it.amount ?: 0L).coerceAtMost(0L) }, shown.expenses)
        assertEquals(shown.income + shown.expenses, shown.net)
        assertEquals(shown.rows.sumOf { it.amount ?: 0L }, shown.byCategory.sumOf { it.total })
        assertEquals(setOf("c1", null), shown.byCategory.map { it.categoryId }.toSet())
        assertEquals(shown.byCategory.sortedBy { it.total }, shown.byCategory)
        assertEquals(listOf(true, false, true), shown.rows.map { it.amount!! < 0 })
    }

    @Test
    fun rowsHideNotesAndPayeesButKeepSignAndIds() {
        val shown = ReportSampleValues.transaction(row("t1", -2_500))
        assertEquals("t1", shown.id)
        assertEquals("c1", shown.categoryId)
        assertNotEquals("Corner Shop", shown.payeeName)
        assertNotEquals("private", shown.notes)
        assertTrue(shown.amount!! < 0)
        assertTrue(-shown.amount!! in 400L..27_599L)
        assertEquals(shown, ReportSampleValues.transaction(row("t1", -2_500)))
        assertNull(ReportSampleValues.transaction(row("t2", 100, payee = null, notes = null)).payeeName)
        assertEquals(0L, ReportSampleValues.transaction(row("t3", 0)).amount)
    }

    @Test
    fun drilldownKeepsRolesAndCounts() {
        val parent = row("p", -5_000, category = null).copy(isParent = true, subtransactions = listOf(row("s", -5_000).copy(isChild = true, parentId = "p")))
        val result = ReportDrilldownProjection.project(listOf(parent), setOf("s"))
        val shown = ReportSampleValues.project(result)
        assertEquals(result.countedCount, shown.countedCount)
        assertEquals(result.groups.map { g -> g.rows.map { it.role to it.kind } }, shown.groups.map { g -> g.rows.map { it.role to it.kind } })
        assertEquals(result.groups.map { g -> g.rows.map { it.id } }, shown.groups.map { g -> g.rows.map { it.id } })
        assertTrue(shown.groups.flatMap { it.rows }.all { it.transaction.notes != "private" })
    }
}
