package com.sporez.actualist.core.reports

import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.budget.normalizedMonthSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.query.QueryCondition
import com.sporez.actualist.core.query.QueryDateCondition
import com.sporez.actualist.core.query.QueryDateOperation
import com.sporez.actualist.core.query.QueryDay
import com.sporez.actualist.core.query.QueryIdCondition
import com.sporez.actualist.core.query.TransactionQueries
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Actualist `ReportExplorerMetric`. */
enum class ReportMetric(val title: String) {
    NET_WORTH("Net Worth"),
    CASH_FLOW("Cash Flow"),
    SPENDING("Spending"),
    BUDGET_OVERVIEW("Budget Overview"),
    SPENDING_AVERAGE("Spending Average"),
    ;

    val supportsCategoryFilters: Boolean get() = this != NET_WORTH
}

/** Actualist `ReportInterval`. */
enum class ReportInterval(val title: String) { DAY("Daily"), MONTH("Monthly") }

/** Actualist `ReportExplorerRangePreset`. */
enum class RangePreset(val title: String) {
    MONTH_TO_DATE("Month to Date"),
    THREE_MONTHS("Last 3 Months"),
    SIX_MONTHS("Last 6 Months"),
    YEAR_TO_DATE("Year to Date"),
    CUSTOM("Custom Range"),
    ;

    /** The preset's range ending [today]; null for the custom range. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate>? {
        val monthStart = today.withDayOfMonth(1)
        val start = when (this) {
            MONTH_TO_DATE -> monthStart
            THREE_MONTHS -> monthStart.minusMonths(2)
            SIX_MONTHS -> monthStart.minusMonths(5)
            YEAR_TO_DATE -> LocalDate.of(today.year, 1, 1)
            CUSTOM -> return null
        }
        return start to today
    }
}

enum class SeriesError(val message: String) {
    INVALID_RANGE("The report start date must be on or before its end date."),
    RANGE_TOO_LARGE("That range is too long. Choose up to 1100 days for daily reports or 600 months for monthly reports."),
    SPENDING_AVERAGE_SINGLE_MONTH("Spending Average compares one month at a time."),
    NET_WORTH_CATEGORY_FILTER("Net Worth supports account filters, not category filters."),
}

class ReportSeriesException(val error: SeriesError) : Exception(error.message)

/**
 * Actualist `ReportExplorerFilters`. A null selection means everything; defaults are Actualist's:
 * on-budget accounts only, hidden categories and uncategorized activity included.
 */
data class SeriesFilters(
    val accounts: Set<String>? = null,
    val categories: Set<String>? = null,
    val includeOffBudget: Boolean = false,
    val includeHiddenCategories: Boolean = true,
    val includeUncategorized: Boolean = true,
) {
    fun isAccountSelected(id: String): Boolean = accounts?.contains(id) ?: true
    fun isCategorySelected(id: String): Boolean = categories?.contains(id) ?: true

    fun withAccount(id: String, selected: Boolean, available: Set<String>): SeriesFilters =
        copy(accounts = updated(accounts, id, selected, available))

    fun withCategory(id: String, selected: Boolean, available: Set<String>): SeriesFilters =
        copy(categories = updated(categories, id, selected, available))

    fun clearAccounts(): SeriesFilters = copy(accounts = emptySet())

    fun clearCategories(): SeriesFilters = copy(categories = emptySet(), includeUncategorized = false)

    /** Net Worth ignores category filters. */
    fun normalized(metric: ReportMetric): SeriesFilters =
        if (metric == ReportMetric.NET_WORTH) copy(categories = null, includeHiddenCategories = true, includeUncategorized = true) else this

    /** How many filters differ from the defaults (Actualist's badge on the filter button). */
    fun activeCount(metric: ReportMetric): Int {
        var count = 0
        if (accounts != null) count++
        if (metric.supportsCategoryFilters && categories != null) count++
        if (includeOffBudget) count++
        if (metric.supportsCategoryFilters && !includeHiddenCategories) count++
        if (metric.supportsCategoryFilters && !includeUncategorized) count++
        return count
    }

    private fun updated(current: Set<String>?, id: String, selected: Boolean, available: Set<String>): Set<String>? {
        val ids = (current ?: available).toMutableSet()
        if (selected) ids.add(id) else ids.remove(id)
        return if (ids == available) null else ids
    }
}

data class SeriesPeriod(val start: LocalDate, val end: LocalDate)

/** Actualist `ReportExplorerQuery`. */
data class SeriesQuery(
    val metric: ReportMetric,
    val start: LocalDate,
    val end: LocalDate,
    val interval: ReportInterval,
    val filters: SeriesFilters = SeriesFilters(),
) {
    val validationError: SeriesError?
        get() {
            if (start.isAfter(end)) return SeriesError.INVALID_RANGE
            val exceeds = when (interval) {
                ReportInterval.DAY -> ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS
                ReportInterval.MONTH -> ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1 > MAX_MONTHS
            }
            if (exceeds) return SeriesError.RANGE_TOO_LARGE
            if (metric == ReportMetric.SPENDING_AVERAGE && YearMonth.from(start) != YearMonth.from(end)) {
                return SeriesError.SPENDING_AVERAGE_SINGLE_MONTH
            }
            if (metric == ReportMetric.NET_WORTH &&
                (filters.categories != null || !filters.includeHiddenCategories || !filters.includeUncategorized)
            ) {
                return SeriesError.NET_WORTH_CATEGORY_FILTER
            }
            return null
        }

    val periods: List<SeriesPeriod>
        get() {
            if (validationError != null) return emptyList()
            return when (interval) {
                ReportInterval.DAY -> days(start, end).map { SeriesPeriod(it, it) }
                ReportInterval.MONTH -> months(start, end).map { month ->
                    SeriesPeriod(maxOf(start, month.atDay(1)), minOf(end, month.atEndOfMonth()))
                }
            }
        }

    companion object {
        const val MAX_DAYS = 1_100L
        const val MAX_MONTHS = 600L

        /** Earliest start a range ending on [end] may have (Actualist `earliestStart(forEnd:)`). */
        fun earliestStart(end: LocalDate, interval: ReportInterval): LocalDate = when (interval) {
            ReportInterval.DAY -> end.minusDays(MAX_DAYS - 1)
            ReportInterval.MONTH -> end.minusMonths(MAX_MONTHS - 1)
        }

        /** Latest end a range starting on [start] may have (Actualist `latestEnd(forStart:)`). */
        fun latestEnd(start: LocalDate, interval: ReportInterval): LocalDate = when (interval) {
            ReportInterval.DAY -> start.plusDays(MAX_DAYS - 1)
            ReportInterval.MONTH -> start.plusMonths(MAX_MONTHS - 1)
        }

        fun days(from: LocalDate, through: LocalDate): List<LocalDate> {
            if (from.isAfter(through)) return emptyList()
            val count = ChronoUnit.DAYS.between(from, through).toInt() + 1
            return List(count) { from.plusDays(it.toLong()) }
        }

        fun months(from: LocalDate, through: LocalDate): List<YearMonth> {
            val first = YearMonth.from(from)
            val last = YearMonth.from(through)
            val count = ChronoUnit.MONTHS.between(first, last).toInt() + 1
            return List(maxOf(count, 0)) { first.plusMonths(it.toLong()) }
        }
    }
}

data class SeriesPoint(
    val period: SeriesPeriod,
    val income: Long,
    val expenses: Long,
    val net: Long,
    val endingBalance: Long,
    val budgeted: Long,
    val comparison: Long,
)

data class SeriesTotals(
    val income: Long,
    val expenses: Long,
    val net: Long,
    val endingBalance: Long,
    val balanceChange: Long,
    val openingBalance: Long,
    val budgeted: Long,
    val averageSpending: Long,
)

data class SeriesAccountOption(val id: String, val name: String, val isOffBudget: Boolean, val isClosed: Boolean)

data class SeriesCategoryOption(val id: String, val name: String, val groupName: String, val isIncome: Boolean, val isHidden: Boolean)

data class SeriesCatalog(val accounts: List<SeriesAccountOption>, val categories: List<SeriesCategoryOption>) {
    /** Cash Flow offers income categories too; the other reports only expense categories. */
    fun categories(metric: ReportMetric): List<SeriesCategoryOption> =
        if (metric == ReportMetric.CASH_FLOW) categories else categories.filter { !it.isIncome }
}

data class SeriesSnapshot(
    val query: SeriesQuery,
    val points: List<SeriesPoint>,
    val totals: SeriesTotals,
    val hasData: Boolean,
    val catalog: SeriesCatalog,
)

/**
 * The Report Explorer's series, ported from Actualist `BudgetDatabase+ReportExplorer`: net worth as
 * a running balance, cash flow and spending per period, budget overview as cumulative spending
 * against the cumulative budget, and spending average against the three months before.
 */
class ReportSeries(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    private class DayRow(val day: LocalDate, val isIncome: Boolean, val amount: Long)

    fun catalog(): SeriesCatalog = sql.locked {
        val accounts = if (database.tableExists("accounts")) {
            val columns = database.columnSet("accounts")
            fun col(name: String, fallback: String) = if (name in columns) name else fallback
            sql.query(
                "SELECT id, ${col("name", "id")} AS name, ${col("offbudget", "0")} AS offbudget, ${col("closed", "0")} AS closed " +
                    "FROM accounts WHERE ${BudgetDatabase.livePredicate(columns)} ORDER BY lower(${col("name", "id")}), id",
            ).mapNotNull { row ->
                val id = row.string("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SeriesAccountOption(id, row.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Account", row.bool("offbudget"), row.bool("closed"))
            }
        } else {
            emptyList()
        }
        val categories = if (database.tableExists("categories")) {
            val columns = database.columnSet("categories")
            val groups = database.tableExists("category_groups")
            val groupColumns = if (groups) database.columnSet("category_groups") else emptySet()
            val hidden = if ("hidden" in columns) "COALESCE(c.hidden, 0)" else "0"
            val income = if ("is_income" in columns) "COALESCE(c.is_income, 0)" else "0"
            val groupHidden = if ("hidden" in groupColumns) "COALESCE(g.hidden, 0)" else "0"
            val groupIncome = if ("is_income" in groupColumns) "COALESCE(g.is_income, 0)" else "0"
            val groupName = if ("name" in groupColumns) "g.name" else "''"
            val join = if (groups && "cat_group" in columns) "LEFT JOIN category_groups g ON g.id = c.cat_group" else ""
            val name = if ("name" in columns) "c.name" else "c.id"
            val rows = sql.query(
                "SELECT c.id AS id, $name AS name, ${if (join.isEmpty()) "''" else groupName} AS group_name, " +
                    "CASE WHEN $income != 0 OR ${if (join.isEmpty()) "0" else groupIncome} != 0 THEN 1 ELSE 0 END AS is_income, " +
                    "CASE WHEN $hidden != 0 OR ${if (join.isEmpty()) "0" else groupHidden} != 0 THEN 1 ELSE 0 END AS hidden " +
                    "FROM categories c $join WHERE ${BudgetDatabase.livePredicate(columns, "c")} " +
                    "ORDER BY lower(${if (join.isEmpty()) "''" else groupName}), lower($name), c.id",
            )
            rows.mapNotNull { row ->
                val id = row.string("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SeriesCategoryOption(
                    id,
                    row.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown Category",
                    row.string("group_name")?.trim()?.takeIf { it.isNotEmpty() } ?: "Categories",
                    row.bool("is_income"),
                    row.bool("hidden"),
                )
            }
        } else {
            emptyList()
        }
        SeriesCatalog(accounts, categories)
    }

    fun snapshot(query: SeriesQuery): SeriesSnapshot = sql.locked {
        query.validationError?.let { throw ReportSeriesException(it) }
        val catalog = catalog()
        if (query.metric == ReportMetric.NET_WORTH) netWorth(query, catalog) else activity(query, catalog)
    }

    // region Net worth

    private fun netWorth(query: SeriesQuery, catalog: SeriesCatalog): SeriesSnapshot {
        val accountIds = accountIds(query, catalog)
        val rows = balanceChanges(query.end, accountIds)
        val startText = query.start.toString()
        val opening = rows.filter { it.first < startText }.sumOf { it.second }
        val changes = rows.filter { it.first >= startText }.associate { it.first to it.second }
        var balance = opening
        val points = query.periods.map { period ->
            for (day in SeriesQuery.days(period.start, period.end)) balance += changes[day.toString()] ?: 0L
            SeriesPoint(period, 0, 0, 0, balance, 0, 0)
        }
        return SeriesSnapshot(
            query = query,
            points = points,
            totals = SeriesTotals(0, 0, 0, balance, balance - opening, opening, 0, 0),
            hasData = rows.isNotEmpty(),
            catalog = catalog,
        )
    }

    private fun balanceChanges(through: LocalDate, accountIds: Set<String>): List<Pair<String, Long>> {
        if (accountIds.isEmpty() || !database.tableExists("transactions") || !database.tableExists("accounts")) return emptyList()
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val day = normalizedDateSql(split.date)
        val ids = accountIds.sorted()
        val placeholders = ids.joinToString(", ") { "?" }
        val args: List<Any?> = listOf(through.toString()) + ids
        return sql.query(
            """
            SELECT $day AS day, SUM(${split.amount}) AS amount
            FROM transactions t JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()}
              AND ${BudgetDatabase.livePredicate(database.columnSet("accounts"), "a")}
              AND $day <= ? AND ${split.account} IN ($placeholders)
            GROUP BY $day ORDER BY $day
            """.trimIndent(),
            *args.toTypedArray(),
        ).mapNotNull { row ->
            val dayText = row.string("day") ?: return@mapNotNull null
            dayText to row.longOr0("amount")
        }
    }

    // endregion

    // region Activity

    private fun activity(query: SeriesQuery, catalog: SeriesCatalog): SeriesSnapshot {
        val incomeIds = catalog.categories.filter { it.isIncome }.map { it.id }.toSet()
        val accountIds = accountIds(query, catalog)
        val current = activityRows(query, query.start, query.end, catalog, accountIds, incomeIds)
        if (query.metric == ReportMetric.SPENDING_AVERAGE) return spendingAverage(query, catalog, current, accountIds, incomeIds)

        val budgetedByMonth: Map<YearMonth, Long> = if (query.metric == ReportMetric.BUDGET_OVERVIEW) {
            val categoryIds = categoryIds(query, catalog, includeIncome = false)
            SeriesQuery.months(query.start, query.end).associateWith { budgeted(it, categoryIds) }
        } else {
            emptyMap()
        }
        val rowsByDay = current.groupBy { it.day }
        val cumulative = query.metric == ReportMetric.BUDGET_OVERVIEW
        val periods = if (cumulative) cumulativePeriods(query) else query.periods
        var hasContributing = false
        var cumulativeSpending = 0L
        var cumulativeBudget = 0L
        val points = periods.map { period ->
            var income = 0L
            var expenses = 0L
            val budgeted = if (cumulative) budgetedAmount(period, budgetedByMonth) else 0L
            for (day in SeriesQuery.days(period.start, period.end)) {
                for (row in rowsByDay[day].orEmpty()) {
                    if (query.metric == ReportMetric.CASH_FLOW) {
                        hasContributing = hasContributing || row.amount != 0L
                        if (row.amount > 0) income += row.amount else if (row.amount < 0) expenses -= row.amount
                    } else if (!row.isIncome) {
                        hasContributing = hasContributing || row.amount != 0L
                        expenses -= row.amount
                    }
                }
            }
            val pointExpenses: Long
            val pointBudgeted: Long
            if (cumulative) {
                cumulativeSpending += expenses
                cumulativeBudget += budgeted
                pointExpenses = cumulativeSpending
                pointBudgeted = cumulativeBudget
            } else {
                pointExpenses = expenses
                pointBudgeted = budgeted
            }
            SeriesPoint(period, income, pointExpenses, income - pointExpenses, 0, pointBudgeted, 0)
        }
        val income = points.sumOf { it.income }
        val expenses = if (cumulative) points.lastOrNull()?.expenses ?: 0L else points.sumOf { it.expenses }
        val budgeted = if (cumulative) points.lastOrNull()?.budgeted ?: 0L else points.sumOf { it.budgeted }
        return SeriesSnapshot(
            query = query,
            points = points,
            totals = SeriesTotals(income, expenses, income - expenses, 0, 0, 0, budgeted, 0),
            hasData = hasContributing || budgeted != 0L,
            catalog = catalog,
        )
    }

    private fun spendingAverage(
        query: SeriesQuery,
        catalog: SeriesCatalog,
        current: List<DayRow>,
        accountIds: Set<String>,
        incomeIds: Set<String>,
    ): SeriesSnapshot {
        val firstMonth = YearMonth.from(query.start).minusMonths(3)
        val historyStart = firstMonth.atDay(1)
        val historyEnd = YearMonth.from(query.start).minusMonths(1).atEndOfMonth()
        val history = activityRows(query, historyStart, historyEnd, catalog, accountIds, incomeIds)
        val spendingByDay = HashMap<LocalDate, Long>()
        for (row in current + history) if (!row.isIncome) spendingByDay[row.day] = (spendingByDay[row.day] ?: 0L) - row.amount

        val dayIndex = HashMap<LocalDate, Int>()
        val running = ArrayList<Long>()
        var total = 0L
        for (day in SeriesQuery.days(historyStart, query.end)) {
            total += spendingByDay[day] ?: 0L
            dayIndex[day] = running.size
            running.add(total)
        }
        fun amount(from: LocalDate, through: LocalDate): Long {
            val startIndex = dayIndex[from] ?: return 0L
            val endIndex = dayIndex[through] ?: return 0L
            if (startIndex > endIndex) return 0L
            return running[endIndex] - (if (startIndex > 0) running[startIndex - 1] else 0L)
        }

        val points = cumulativePeriods(query).map { period ->
            val spent = amount(query.start, period.end)
            var historical = 0L
            for (offset in -3..-1) {
                val month = YearMonth.from(query.start).plusMonths(offset.toLong())
                val through = if (period.end.dayOfMonth >= 28) month.atEndOfMonth() else period.end.plusMonths(offset.toLong())
                historical += amount(query.start.plusMonths(offset.toLong()), through)
            }
            SeriesPoint(period, 0, spent, -spent, 0, 0, scaled(historical, 1, 3))
        }
        val spent = points.lastOrNull()?.expenses ?: 0L
        val average = points.lastOrNull()?.comparison ?: 0L
        return SeriesSnapshot(
            query = query,
            points = points,
            totals = SeriesTotals(0, spent, -spent, 0, 0, 0, 0, average),
            hasData = points.any { it.expenses != 0L || it.comparison != 0L },
            catalog = catalog,
        )
    }

    private fun activityRows(
        query: SeriesQuery,
        start: LocalDate,
        end: LocalDate,
        catalog: SeriesCatalog,
        accountIds: Set<String>,
        incomeIds: Set<String>,
    ): List<DayRow> {
        if (accountIds.isEmpty()) return emptyList()
        val conditions = buildList {
            add(QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_ON_OR_AFTER, QueryDay.of(start.toString())!!)))
            add(QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_ON_OR_BEFORE, QueryDay.of(end.toString())!!)))
            add(QueryCondition.Account(QueryIdCondition.oneOf(accountIds.toList())))
            categoryCondition(query, catalog)?.let { add(QueryCondition.Category(it)) }
            if (query.metric == ReportMetric.CASH_FLOW) add(QueryCondition.Transfer(false))
        }
        val page = TransactionQueries(database).query(conditions = conditions)
        return page.contributingTransactions.mapNotNull { row ->
            val amount = row.amount ?: return@mapNotNull null
            val day = runCatching { LocalDate.parse(row.date) }.getOrNull() ?: return@mapNotNull null
            DayRow(day, row.categoryId?.let { it in incomeIds } ?: false, amount)
        }
    }

    private fun categoryCondition(query: SeriesQuery, catalog: SeriesCatalog): QueryIdCondition? {
        val filters = query.filters
        val selected = categoryIds(query, catalog, includeIncome = query.metric == ReportMetric.CASH_FLOW)
        if (filters.categories == null && filters.includeHiddenCategories && filters.includeUncategorized) {
            val excludedIncome = catalog.categories.filter { it.isIncome }.map { it.id }
            return if (query.metric == ReportMetric.CASH_FLOW || excludedIncome.isEmpty()) null else QueryIdCondition.notOneOf(excludedIncome)
        }
        val values = mutableListOf<String?>()
        values.addAll(selected)
        if (filters.includeUncategorized) values.add(null)
        if (values.size == 1 && values[0] == null) return QueryIdCondition.equalTo(null)
        return QueryIdCondition.oneOf(values)
    }

    private fun accountIds(query: SeriesQuery, catalog: SeriesCatalog): Set<String> {
        val eligible = catalog.accounts.filter { query.filters.includeOffBudget || !it.isOffBudget }.map { it.id }.toSet()
        return query.filters.accounts?.let { it.intersect(eligible) } ?: eligible
    }

    private fun categoryIds(query: SeriesQuery, catalog: SeriesCatalog, includeIncome: Boolean): Set<String> {
        val eligible = catalog.categories
            .filter { (includeIncome || !it.isIncome) && (query.filters.includeHiddenCategories || !it.isHidden) }
            .map { it.id }.toSet()
        return query.filters.categories?.let { it.intersect(eligible) } ?: eligible
    }

    /** Actual's cumulative graphs: one bucket per day through day 27, then day 28 to month end in one. */
    private fun cumulativePeriods(query: SeriesQuery): List<SeriesPeriod> {
        if (query.interval != ReportInterval.DAY) return query.periods
        val periods = mutableListOf<SeriesPeriod>()
        for (month in SeriesQuery.months(query.start, query.end)) {
            val start = maxOf(query.start, month.atDay(1))
            val end = minOf(query.end, month.atEndOfMonth())
            val startNumber = start.dayOfMonth
            val endNumber = end.dayOfMonth
            if (startNumber <= minOf(endNumber, 27)) {
                for (day in startNumber..minOf(endNumber, 27)) {
                    val date = month.atDay(day)
                    periods.add(SeriesPeriod(date, date))
                }
            }
            if (endNumber >= 28) periods.add(SeriesPeriod(maxOf(start, month.atDay(28)), end))
        }
        return periods
    }

    private fun budgeted(month: YearMonth, categoryIds: Set<String>): Long {
        val table = BudgetReads(database).budgetTable().tableName
        if (categoryIds.isEmpty() || !database.tableExists(table)) return 0L
        val ids = categoryIds.sorted()
        val args: List<Any?> = listOf(month.toString()) + ids
        return sql.queryLong(
            "SELECT SUM(z.amount) FROM $table z WHERE ${normalizedMonthSql("z.month")} = ? AND z.category IN (${ids.joinToString(", ") { "?" }})",
            *args.toTypedArray(),
        ) ?: 0L
    }

    private fun budgetedAmount(period: SeriesPeriod, budgetedByMonth: Map<YearMonth, Long>): Long {
        var amount = 0L
        for (month in SeriesQuery.months(period.start, period.end)) {
            val dayCount = month.lengthOfMonth()
            val firstDay = if (month == YearMonth.from(period.start)) period.start.dayOfMonth else 1
            val lastDay = if (month == YearMonth.from(period.end)) period.end.dayOfMonth else dayCount
            val monthly = budgetedByMonth[month] ?: 0L
            amount += scaled(monthly, lastDay.toLong(), dayCount) - scaled(monthly, maxOf(firstDay - 1, 0).toLong(), dayCount)
        }
        return amount
    }

    // endregion

    companion object {
        /** Actualist `ReportArithmetic.scaled`: value * multiplier / divisor, rounded half away from zero. */
        fun scaled(value: Long, multiplier: Long, divisor: Int): Long {
            val product = value * multiplier
            val quotient = product / divisor
            val remainder = product % divisor
            if (kotlin.math.abs(remainder) * 2 < divisor) return quotient
            return quotient + if (product >= 0) 1 else -1
        }
    }
}
