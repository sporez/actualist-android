package com.sporez.actualist.core.reports

import com.sporez.actualist.core.privacy.SampleNameKind
import com.sporez.actualist.core.privacy.SampleValues
import com.sporez.actualist.core.query.QueryTransaction

/**
 * Actualist's "Use Sample Values" projection of the reports (`ReportsViewModel.sanitized`,
 * `ReportExplorerViewModel.sanitized` and `ReportTransactionDrilldownProjection`): every amount is replaced by a
 * stable one seeded by what it describes, derived totals are recomputed from the projected parts so they add up,
 * and names come from the sample name lists. Zero stays zero. Display only; the real data is untouched.
 */
object ReportSampleValues {
    private fun masked(amount: Long, seed: String, maximumDollars: Int): Long =
        if (amount == 0L) 0L else SampleValues.amount(amount, seed, 4, maximumDollars)

    // ---- Dashboard ----

    fun project(dashboard: ReportsDashboard): ReportsDashboard {
        val netWorthPoints = dashboard.netWorth.points.map { MonthValue(it.month, masked(it.value, "reports-net-worth-${it.month}", 250_000)) }
        val netWorthBalance = netWorthPoints.lastOrNull()?.value ?: 0L
        val netWorthChange = netWorthBalance - (netWorthPoints.firstOrNull()?.value ?: netWorthBalance)

        val flow = dashboard.cashFlow
        val income = masked(flow.income, "reports-cash-income", 25_000)
        val expenses = masked(flow.expenses, "reports-cash-expenses", 25_000)
        val cashFlow = CashFlowSummary(flow.month, income, expenses, income - expenses, masked(flow.uncategorized, "reports-cash-uncategorized", 2_500))
        // The earlier bars are projected per month; the card's own month shows the card's numbers.
        val history = dashboard.cashFlowHistory.map { entry ->
            if (entry.month == flow.month) {
                cashFlow
            } else {
                val entryIncome = masked(entry.income, "reports-cash-income-${entry.month}", 25_000)
                val entryExpenses = masked(entry.expenses, "reports-cash-expenses-${entry.month}", 25_000)
                CashFlowSummary(entry.month, entryIncome, entryExpenses, entryIncome - entryExpenses, masked(entry.uncategorized, "reports-cash-uncategorized-${entry.month}", 2_500))
            }
        }

        val spending = dashboard.spending.map {
            CategorySpending(
                categoryId = it.categoryId,
                name = SampleValues.name(SampleNameKind.CATEGORY, it.categoryId),
                groupName = SampleValues.overspentGroupName(it.groupName),
                spent = masked(it.spent, "reports-spending-spent-${it.categoryId}", 1_500),
                budgeted = masked(it.budgeted, "reports-spending-budgeted-${it.categoryId}", 1_500),
            )
        }.sortedByDescending { it.spent }

        return dashboard.copy(
            netWorth = NetWorthReport(netWorthPoints, netWorthBalance, netWorthChange),
            cashFlow = cashFlow,
            cashFlowHistory = history,
            spending = spending,
            budgetedExpenses = spending.sumOf { it.budgeted },
        )
    }

    private fun comparisonPoints(points: List<DailyComparisonPoint>, currentSeed: String, comparisonSeed: String) =
        points.map { point ->
            DailyComparisonPoint(
                point.day,
                point.current?.let { masked(it, "$currentSeed-${point.day}", 25_000) },
                masked(point.comparison, "$comparisonSeed-${point.day}", 25_000),
            )
        }

    fun project(report: MonthComparisonReport): MonthComparisonReport {
        val points = comparisonPoints(report.points, "reports-month-current", "reports-month-previous")
        val last = points.lastOrNull { it.current != null }
        return report.copy(points = points, variance = (last?.current ?: 0L) - (last?.comparison ?: 0L))
    }

    fun project(report: ThreeMonthAverageReport): ThreeMonthAverageReport {
        val points = comparisonPoints(report.points, "reports-average-current", "reports-average-history")
        val last = points.lastOrNull { it.current != null }
        val current = last?.current ?: 0L
        val average = last?.comparison ?: 0L
        return report.copy(points = points, currentExpenses = current, averageExpenses = average, variance = current - average)
    }

    fun project(months: List<TransactionCalendarMonth>): List<TransactionCalendarMonth> = months.map { month ->
        val days = month.days.map {
            it.copy(
                income = masked(it.income, "reports-calendar-income-${it.dayId}", 5_000),
                expenses = masked(it.expenses, "reports-calendar-expenses-${it.dayId}", 5_000),
            )
        }
        month.copy(days = days, income = days.sumOf { it.income }, expenses = days.sumOf { it.expenses })
    }

    // ---- Explorer ----

    fun project(snapshot: SeriesSnapshot): SeriesSnapshot {
        val catalog = project(snapshot.catalog)
        if (snapshot.query.metric == ReportMetric.NET_WORTH) return projectNetWorth(snapshot, catalog)
        val points = snapshot.points.map { point ->
            val id = periodId(point.period)
            val income = masked(point.income, "report-detail-income-$id", 250_000)
            val expenses = masked(point.expenses, "report-detail-expenses-$id", 250_000)
            SeriesPoint(
                period = point.period,
                income = income,
                expenses = expenses,
                net = income - expenses,
                endingBalance = 0L,
                budgeted = masked(point.budgeted, "report-detail-budgeted-$id", 250_000),
                comparison = masked(point.comparison, "report-detail-comparison-$id", 250_000),
            )
        }
        val income = points.sumOf { it.income }
        val cumulative = snapshot.query.metric == ReportMetric.BUDGET_OVERVIEW || snapshot.query.metric == ReportMetric.SPENDING_AVERAGE
        val expenses = if (cumulative) points.lastOrNull()?.expenses ?: 0L else points.sumOf { it.expenses }
        val budgeted = if (cumulative) points.lastOrNull()?.budgeted ?: 0L else points.sumOf { it.budgeted }
        return snapshot.copy(
            points = points,
            totals = SeriesTotals(
                income = income,
                expenses = expenses,
                net = income - expenses,
                endingBalance = 0L,
                balanceChange = 0L,
                openingBalance = 0L,
                budgeted = budgeted,
                averageSpending = if (snapshot.query.metric == ReportMetric.SPENDING_AVERAGE) points.lastOrNull()?.comparison ?: 0L else 0L,
            ),
            catalog = catalog,
        )
    }

    private fun projectNetWorth(snapshot: SeriesSnapshot, catalog: SeriesCatalog): SeriesSnapshot {
        val opening = masked(snapshot.totals.openingBalance, "report-detail-opening-balance", 250_000)
        val change = masked(snapshot.totals.balanceChange, "report-detail-balance-change", 250_000)
        val ending = opening + change
        val points = snapshot.points.mapIndexed { index, point ->
            SeriesPoint(
                period = point.period,
                income = 0L,
                expenses = 0L,
                net = 0L,
                endingBalance = if (index == snapshot.points.lastIndex) ending else masked(point.endingBalance, "report-detail-balance-${periodId(point.period)}", 250_000),
                budgeted = 0L,
                comparison = 0L,
            )
        }
        return snapshot.copy(
            points = points,
            totals = SeriesTotals(0L, 0L, 0L, ending, change, opening, 0L, 0L),
            catalog = catalog,
        )
    }

    /** Names only; ids (and so every selection) stay real. A category with no group keeps Actualist's "Categories". */
    fun project(catalog: SeriesCatalog): SeriesCatalog = SeriesCatalog(
        accounts = catalog.accounts.map { it.copy(name = SampleValues.name(SampleNameKind.ACCOUNT, it.id)) },
        categories = catalog.categories.map {
            it.copy(
                name = SampleValues.name(SampleNameKind.CATEGORY, it.id),
                groupName = if (it.groupName.isBlank()) "Categories" else SampleValues.overspentGroupName(it.groupName),
            )
        },
    )

    private fun periodId(period: SeriesPeriod): String = "${period.start}..${period.end}"

    /**
     * The transactions list and totals under the Explorer chart. Row amounts are projected like Actualist's report
     * transaction rows, then income, expenses and the per-category totals are recomputed from them so they add up.
     */
    fun project(result: ExplorerResult): ExplorerResult {
        val rows = result.rows.map { transaction(it) }
        val byCategory = rows.groupBy { it.categoryId }
            .map { (category, items) -> ExplorerCategoryTotal(category, items.sumOf { it.amount ?: 0L }, items.size) }
            .sortedBy { it.total }
        return ExplorerResult(
            income = rows.sumOf { (it.amount ?: 0L).coerceAtLeast(0L) },
            expenses = rows.sumOf { (it.amount ?: 0L).coerceAtMost(0L) },
            byCategory = byCategory,
            rows = rows,
        )
    }

    // ---- Transactions ----

    /** Actualist's report transaction row: the amount, payee and notes are seeded by the transaction's id. */
    fun transaction(row: QueryTransaction): QueryTransaction {
        val id = rowId(row)
        return row.copy(
            amount = row.amount?.let { masked(it, "transaction-amount-$id", 275) },
            // An imported name stands in for a missing payee on screen, so it is replaced the same way.
            payeeName = if (row.payeeName.isNullOrBlank() && row.importedPayee.isNullOrBlank()) row.payeeName else SampleValues.name(SampleNameKind.PAYEE, "payee-$id"),
            importedPayee = null,
            notes = row.notes?.takeIf { it.isNotBlank() }?.let { SampleValues.name(SampleNameKind.PAYEE, "notes-$id") },
            subtransactions = row.subtransactions.map { transaction(it) },
        )
    }

    /** The category shown on a projected report row (Actualist seeds it by the transaction, not the category). */
    fun categoryName(row: QueryTransaction): String = SampleValues.name(SampleNameKind.CATEGORY, "category-${rowId(row)}")

    fun accountName(accountId: String): String = SampleValues.name(SampleNameKind.ACCOUNT, accountId)

    fun project(result: DrilldownResult): DrilldownResult = DrilldownResult(
        groups = result.groups.map { group -> group.copy(rows = group.rows.map { it.copy(transaction = transaction(it.transaction)) }) },
        countedCount = result.countedCount,
    )

    private fun rowId(row: QueryTransaction): String = row.id ?: "${row.date}-${row.accountId}"
}
