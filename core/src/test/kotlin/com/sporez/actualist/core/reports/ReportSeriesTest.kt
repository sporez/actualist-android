package com.sporez.actualist.core.reports

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportSeriesTest {
    private val sep1 = LocalDate.of(2026, 9, 1)
    private val oct31 = LocalDate.of(2026, 10, 31)

    private val base = "FROM transactions t JOIN accounts a ON a.id = t.acct " +
        "WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND (t.isParent IS NULL OR t.isParent = 0) " +
        "AND (a.tombstone IS NULL OR a.tombstone = 0)"
    private val onBudget = "AND COALESCE(a.offbudget, 0) = 0"
    private val notIncome = "AND NOT EXISTS (SELECT 1 FROM categories c WHERE c.id = t.category AND c.is_income = 1)"

    private fun <T> withDemo(body: (BudgetDatabase) -> T): T =
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, LocalDate.of(2026, 10, 15), TestClock()).use(body)

    private fun ledger(db: BudgetDatabase, extra: String): Long = db.sql.queryLong("SELECT COALESCE(SUM(t.amount), 0) $base $extra") ?: 0L

    private fun query(metric: ReportMetric, interval: ReportInterval = ReportInterval.MONTH, filters: SeriesFilters = SeriesFilters()) =
        SeriesQuery(metric, sep1, oct31, interval, filters)

    @Test
    fun cashFlowMatchesTheLedgerPerMonthAndInTotal() = withDemo { db ->
        val snapshot = ReportSeries(db).snapshot(query(ReportMetric.CASH_FLOW))
        assertEquals(2, snapshot.points.size)
        val window = "AND t.transferred_id IS NULL $onBudget AND t.date BETWEEN 20260901 AND 20261031"
        assertEquals(ledger(db, "$window AND t.amount > 0"), snapshot.totals.income)
        assertEquals(-ledger(db, "$window AND t.amount < 0"), snapshot.totals.expenses)
        assertEquals(snapshot.totals.income - snapshot.totals.expenses, snapshot.totals.net)
        val september = "AND t.transferred_id IS NULL $onBudget AND t.date BETWEEN 20260901 AND 20260930"
        assertEquals(ledger(db, "$september AND t.amount > 0"), snapshot.points[0].income)
        assertEquals(-ledger(db, "$september AND t.amount < 0"), snapshot.points[0].expenses)
        assertTrue(snapshot.hasData)
    }

    @Test
    fun spendingCountsNonIncomeRowsAndTheUncategorizedFilterNarrowsIt() = withDemo { db ->
        val series = ReportSeries(db)
        val window = "$onBudget $notIncome AND t.date BETWEEN 20260901 AND 20261031"
        val all = series.snapshot(query(ReportMetric.SPENDING))
        assertEquals(-ledger(db, window), all.totals.expenses)
        assertEquals(all.totals.expenses, all.points.sumOf { it.expenses })

        val categorized = series.snapshot(query(ReportMetric.SPENDING, filters = SeriesFilters(includeUncategorized = false)))
        assertEquals(-ledger(db, "$window AND t.category IS NOT NULL"), categorized.totals.expenses)
    }

    @Test
    fun accountAndCategoryFiltersNarrowTheSeries() = withDemo { db ->
        val series = ReportSeries(db)
        val window = "$notIncome AND t.date BETWEEN 20260901 AND 20261031"
        val checking = series.snapshot(query(ReportMetric.SPENDING, filters = SeriesFilters(accounts = setOf("checking"))))
        assertEquals(-ledger(db, "$window AND t.acct = 'checking'"), checking.totals.expenses)

        val dining = series.snapshot(query(ReportMetric.SPENDING, filters = SeriesFilters(categories = setOf("dining"), includeUncategorized = false)))
        assertEquals(-ledger(db, "$onBudget $window AND t.category = 'dining'"), dining.totals.expenses)

        val none = series.snapshot(query(ReportMetric.SPENDING, filters = SeriesFilters(accounts = emptySet())))
        assertEquals(0L, none.totals.expenses)
        assertFalse(none.hasData)
    }

    @Test
    fun netWorthRunsTheBalanceAndOffBudgetAccountsAreAnOption() = withDemo { db ->
        val series = ReportSeries(db)
        val onBudgetOnly = series.snapshot(query(ReportMetric.NET_WORTH))
        assertEquals(ledger(db, "$onBudget AND t.date <= 20261031"), onBudgetOnly.totals.endingBalance)
        assertEquals(ledger(db, "$onBudget AND t.date < 20260901"), onBudgetOnly.totals.openingBalance)
        assertEquals(ledger(db, "$onBudget AND t.date <= 20260930"), onBudgetOnly.points[0].endingBalance)
        assertEquals(onBudgetOnly.totals.endingBalance - onBudgetOnly.totals.openingBalance, onBudgetOnly.totals.balanceChange)

        val everything = series.snapshot(query(ReportMetric.NET_WORTH, filters = SeriesFilters(includeOffBudget = true)))
        assertEquals(ledger(db, "AND t.date <= 20261031"), everything.totals.endingBalance)
    }

    @Test
    fun dailyNetWorthHasOnePointPerDay() = withDemo { db ->
        val snapshot = ReportSeries(db).snapshot(SeriesQuery(ReportMetric.NET_WORTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), ReportInterval.DAY))
        assertEquals(15, snapshot.points.size)
        assertEquals(ledger(db, "$onBudget AND t.date <= 20261015"), snapshot.points.last().endingBalance)
    }

    @Test
    fun budgetOverviewAccumulatesSpendingAgainstTheBudget() = withDemo { db ->
        val snapshot = ReportSeries(db).snapshot(query(ReportMetric.BUDGET_OVERVIEW))
        val spending = -ledger(db, "$onBudget $notIncome AND t.date BETWEEN 20260901 AND 20261031")
        assertEquals(spending, snapshot.totals.expenses)
        val budgeted = db.sql.queryLong(
            "SELECT COALESCE(SUM(z.amount), 0) FROM zero_budgets z JOIN categories c ON c.id = z.category " +
                "WHERE CAST(z.month AS TEXT) IN ('202609', '202610') AND (c.tombstone IS NULL OR c.tombstone = 0) " +
                "AND COALESCE(c.is_income, 0) = 0 AND COALESCE((SELECT g.is_income FROM category_groups g WHERE g.id = c.cat_group), 0) = 0",
        ) ?: 0L
        assertEquals(budgeted, snapshot.totals.budgeted)
        assertEquals(-ledger(db, "$onBudget $notIncome AND t.date BETWEEN 20260901 AND 20260930"), snapshot.points[0].expenses)
    }

    @Test
    fun dailyBudgetOverviewFoldsDay28ToMonthEnd() = withDemo { db ->
        val snapshot = ReportSeries(db).snapshot(SeriesQuery(ReportMetric.BUDGET_OVERVIEW, sep1, LocalDate.of(2026, 9, 30), ReportInterval.DAY))
        assertEquals(28, snapshot.points.size)
        assertEquals(LocalDate.of(2026, 9, 28), snapshot.points.last().period.start)
        assertEquals(LocalDate.of(2026, 9, 30), snapshot.points.last().period.end)
        assertEquals(-ledger(db, "$onBudget $notIncome AND t.date BETWEEN 20260901 AND 20260930"), snapshot.totals.expenses)
    }

    @Test
    fun spendingAverageComparesWithTheThreePriorMonths() = withDemo { db ->
        fun spent(from: Int, to: Int) = -ledger(db, "$onBudget $notIncome AND t.date BETWEEN $from AND $to")
        val snapshot = ReportSeries(db).snapshot(SeriesQuery(ReportMetric.SPENDING_AVERAGE, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), ReportInterval.DAY))
        assertEquals(15, snapshot.points.size)
        assertEquals(spent(20261001, 20261015), snapshot.totals.expenses)
        val sum = spent(20260701, 20260715) + spent(20260801, 20260815) + spent(20260901, 20260915)
        // Rounded half away from zero for a non-negative sum.
        assertEquals((2 * sum + 3) / 6, snapshot.totals.averageSpending)
        assertEquals(spent(20261001, 20261003), snapshot.points[2].expenses)
    }

    @Test
    fun filterCatalogListsLiveAccountsAndCategories() = withDemo { db ->
        val catalog = ReportSeries(db).catalog()
        assertTrue(catalog.accounts.any { it.id == "checking" })
        assertTrue(catalog.categories.any { it.id == "dining" && !it.isIncome })
        assertTrue(catalog.categories(ReportMetric.SPENDING).none { it.isIncome })
    }

    @Test
    fun validationFollowsActualist() {
        val start = LocalDate.of(2026, 10, 1)
        assertEquals(SeriesError.INVALID_RANGE, SeriesQuery(ReportMetric.CASH_FLOW, start, start.minusDays(1), ReportInterval.DAY).validationError)
        assertEquals(SeriesError.RANGE_TOO_LARGE, SeriesQuery(ReportMetric.CASH_FLOW, start.minusDays(1100), start, ReportInterval.DAY).validationError)
        assertNull(SeriesQuery(ReportMetric.CASH_FLOW, start.minusDays(1099), start, ReportInterval.DAY).validationError)
        assertEquals(SeriesError.SPENDING_AVERAGE_SINGLE_MONTH, SeriesQuery(ReportMetric.SPENDING_AVERAGE, start.minusMonths(1), start, ReportInterval.DAY).validationError)
        assertEquals(
            SeriesError.NET_WORTH_CATEGORY_FILTER,
            SeriesQuery(ReportMetric.NET_WORTH, start, start, ReportInterval.DAY, SeriesFilters(categories = setOf("a"))).validationError,
        )
        assertTrue(SeriesQuery(ReportMetric.CASH_FLOW, start.minusDays(1), start, ReportInterval.DAY).periods.size == 2)
    }

    @Test
    fun rangeLimitsBoundTheBucketCountAndThePickers() {
        val end = LocalDate.of(2026, 10, 15)
        val daily = SeriesQuery.earliestStart(end, ReportInterval.DAY)
        assertNull(SeriesQuery(ReportMetric.CASH_FLOW, daily, end, ReportInterval.DAY).validationError)
        assertEquals(1100, SeriesQuery(ReportMetric.CASH_FLOW, daily, end, ReportInterval.DAY).periods.size)
        assertEquals(SeriesError.RANGE_TOO_LARGE, SeriesQuery(ReportMetric.CASH_FLOW, daily.minusDays(1), end, ReportInterval.DAY).validationError)
        assertTrue(SeriesQuery(ReportMetric.CASH_FLOW, daily.minusDays(1), end, ReportInterval.DAY).periods.isEmpty())
        val monthly = SeriesQuery.earliestStart(end, ReportInterval.MONTH)
        assertNull(SeriesQuery(ReportMetric.CASH_FLOW, monthly, end, ReportInterval.MONTH).validationError)
        assertEquals(SeriesError.RANGE_TOO_LARGE, SeriesQuery(ReportMetric.CASH_FLOW, monthly.minusMonths(1), end, ReportInterval.MONTH).validationError)
        assertEquals(end, SeriesQuery.latestEnd(daily, ReportInterval.DAY))
    }

    @Test
    fun presetsAndMonthlyPeriodsClampToTheRange() {
        val today = LocalDate.of(2026, 10, 15)
        assertEquals(LocalDate.of(2026, 10, 1) to today, RangePreset.MONTH_TO_DATE.range(today))
        assertEquals(LocalDate.of(2026, 8, 1) to today, RangePreset.THREE_MONTHS.range(today))
        assertEquals(LocalDate.of(2026, 5, 1) to today, RangePreset.SIX_MONTHS.range(today))
        assertEquals(LocalDate.of(2026, 1, 1) to today, RangePreset.YEAR_TO_DATE.range(today))
        assertNull(RangePreset.CUSTOM.range(today))
        val periods = SeriesQuery(ReportMetric.CASH_FLOW, LocalDate.of(2026, 8, 20), today, ReportInterval.MONTH).periods
        assertEquals(listOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)), periods.map { it.start })
        assertEquals(listOf(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), today), periods.map { it.end })
    }

    @Test
    fun filterSelectionBecomesAllWhenEverythingIsPicked() {
        val available = setOf("a", "b", "c")
        var filters = SeriesFilters()
        filters = filters.withAccount("b", selected = false, available = available)
        assertEquals(setOf("a", "c"), filters.accounts)
        assertFalse(filters.isAccountSelected("b"))
        filters = filters.withAccount("b", selected = true, available = available)
        assertNull(filters.accounts)
        filters = filters.clearCategories()
        assertEquals(emptySet<String>(), filters.categories)
        assertFalse(filters.includeUncategorized)
        assertEquals(2, filters.activeCount(ReportMetric.SPENDING))
        assertEquals(0, filters.activeCount(ReportMetric.NET_WORTH))
        assertEquals(SeriesFilters(), filters.normalized(ReportMetric.NET_WORTH))
    }

    @Test
    fun scaledRoundsHalfAwayFromZero() {
        assertEquals(1L, ReportSeries.scaled(1, 1, 2))
        assertEquals(-1L, ReportSeries.scaled(-1, 1, 2))
        assertEquals(3L, ReportSeries.scaled(10, 1, 3))
        assertEquals(100L, ReportSeries.scaled(100, 30, 30))
    }
}
