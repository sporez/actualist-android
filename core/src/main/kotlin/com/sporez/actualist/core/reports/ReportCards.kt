package com.sporez.actualist.core.reports

import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import java.time.YearMonth

/** One day of a cumulative comparison chart; [current] is null after the comparable day. */
data class DailyComparisonPoint(val day: Int, val current: Long?, val comparison: Long)

data class MonthComparisonReport(
    val currentMonth: String,
    val comparisonMonth: String,
    val points: List<DailyComparisonPoint>,
    val variance: Long,
)

data class ThreeMonthAverageReport(
    val month: String,
    val points: List<DailyComparisonPoint>,
    val currentExpenses: Long,
    val averageExpenses: Long,
    val variance: Long,
)

data class TransactionCalendarDay(val dayId: String, val day: Int, val income: Long, val expenses: Long)

data class TransactionCalendarMonth(
    val month: String,
    /** Blank cells before day 1 in a Sunday-first week. */
    val leadingBlankCount: Int,
    val days: List<TransactionCalendarDay>,
    val income: Long,
    val expenses: Long,
)

/**
 * The "This Month", "3-Month Average" and "Transaction Calendar" cards, ported from Actualist
 * `BudgetDatabase+Reports` and `+ReportActivity`. Spending follows Actual's rule: every
 * non-income row of an on-budget account counts as `-amount`, including uncategorized rows
 * and both sides of on-budget transfers. Days are bucketed 1..28, folding days 29+ into 28.
 * The calendar counts every live account (on and off budget) and honours the transfer
 * condition of the dashboard's calendar card (default: all transactions).
 */
class ReportCardReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun monthComparison(anchorMonth: String, endDay: String): MonthComparisonReport = sql.locked {
        val comparisonMonth = Months.shifted(anchorMonth, -1)
        val spending = spendingByDay(Months.shifted(anchorMonth, -3), anchorMonth)
        val currentDay = comparableDay(endDay)
        var current = 0L
        var comparison = 0L
        var currentAt = 0L
        var comparisonAt = 0L
        val points = (1..28).map { day ->
            val currentValue: Long? = if (day <= currentDay) {
                current += bucket(spending, anchorMonth, day)
                currentAt = current
                current
            } else {
                null
            }
            comparison += bucket(spending, comparisonMonth, day)
            if (day == currentDay) comparisonAt = comparison
            DailyComparisonPoint(day, currentValue, comparison)
        }
        MonthComparisonReport(anchorMonth, comparisonMonth, points, currentAt - comparisonAt)
    }

    fun threeMonthAverage(anchorMonth: String, endDay: String): ThreeMonthAverageReport = sql.locked {
        val history = (-3..-1).map { Months.shifted(anchorMonth, it) }
        val spending = spendingByDay(history.first(), anchorMonth)
        val currentDay = comparableDay(endDay)
        val cumulative = LongArray(history.size)
        var current = 0L
        var currentAt = 0L
        var averageAt = 0L
        val points = (1..28).map { day ->
            val currentValue: Long? = if (day <= currentDay) {
                current += bucket(spending, anchorMonth, day)
                currentAt = current
                current
            } else {
                null
            }
            history.forEachIndexed { index, month -> cumulative[index] += bucket(spending, month, day) }
            val average = cumulative.sum() / history.size
            if (day == currentDay) averageAt = average
            DailyComparisonPoint(day, currentValue, average)
        }
        ThreeMonthAverageReport(anchorMonth, points, currentAt, averageAt, currentAt - averageAt)
    }

    /** The anchor month and the two before it, oldest first. */
    fun transactionCalendar(anchorMonth: String): List<TransactionCalendarMonth> = sql.locked {
        val start = Months.shifted(anchorMonth, -2)
        val activity = calendarActivity(start, anchorMonth, calendarTransferFilter())
        (-2..0).map { offset ->
            val month = Months.shifted(anchorMonth, offset)
            val yearMonth = YearMonth.parse(month)
            val days = (1..yearMonth.lengthOfMonth()).map { day ->
                val id = dayId(month, day)
                val (income, expenses) = activity[id] ?: (0L to 0L)
                TransactionCalendarDay(id, day, income, expenses)
            }
            TransactionCalendarMonth(
                month = month,
                leadingBlankCount = yearMonth.atDay(1).dayOfWeek.value % 7,
                days = days,
                income = days.sumOf { it.income },
                expenses = days.sumOf { it.expenses },
            )
        }
    }

    private fun dayId(month: String, day: Int) = "$month-${"%02d".format(java.util.Locale.ROOT, day)}"

    private fun comparableDay(endDay: String): Int = (endDay.takeLast(2).toIntOrNull() ?: 1).coerceIn(1, 28)

    private fun bucket(spending: Map<String, Long>, month: String, day: Int): Long {
        val last = if (day == 28) maxOf(YearMonth.parse(month).lengthOfMonth(), 28) else day
        return (day..last).sumOf { spending[dayId(month, it)] ?: 0L }
    }

    /** Net spending (`-amount` of non-income rows) per `YYYY-MM-DD` day over whole months. */
    private fun spendingByDay(startMonth: String, endMonth: String): Map<String, Long> {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val day = normalizedDateSql(split.date)
        val categoryColumns = database.columnSet("categories")
        val groupColumns = database.columnSet("category_groups")
        val categoryIncome = if ("is_income" in categoryColumns) "COALESCE(c.is_income, 0)" else "0"
        val groupIncome = if ("is_income" in groupColumns) "COALESCE(g.is_income, 0)" else "0"
        val groupColumn = if ("cat_group" in categoryColumns) "c.cat_group" else "NULL"
        val rows = sql.query(
            """
            SELECT $day AS day, SUM(${split.amount}) AS amount
            FROM transactions t
            JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            LEFT JOIN category_mapping cm ON cm.id = ${split.category}
            LEFT JOIN categories c ON c.id = COALESCE(cm.transferId, ${split.category})
            LEFT JOIN category_groups g ON g.id = $groupColumn
            WHERE ${split.liveInlinePredicate()}
              AND (a.tombstone IS NULL OR a.tombstone = 0) AND COALESCE(a.offbudget, 0) = 0
              AND substr($day, 1, 7) BETWEEN ? AND ?
              AND NOT ($categoryIncome != 0 OR $groupIncome != 0)
            GROUP BY $day
            """.trimIndent(),
            startMonth,
            endMonth,
        )
        return rows.mapNotNull { row -> row.string("day")?.let { it to -row.longOr0("amount") } }.toMap()
    }

    /** Per day: (income, expenses) with expenses as a positive magnitude. */
    private fun calendarActivity(startMonth: String, endMonth: String, filter: TransferFilter): Map<String, Pair<Long, Long>> {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val day = normalizedDateSql(split.date)
        val transferred = split.transferredId
        val transferPredicate = when (filter) {
            TransferFilter.ALL -> "1"
            TransferFilter.TRANSFERS -> "$transferred IS NOT NULL AND $transferred != ''"
            TransferFilter.NON_TRANSFERS -> "$transferred IS NULL OR $transferred = ''"
        }
        val rows = sql.query(
            """
            SELECT $day AS day, CASE WHEN ${split.amount} > 0 THEN 1 ELSE 0 END AS is_inflow, SUM(${split.amount}) AS amount
            FROM transactions t
            JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()}
              AND (a.tombstone IS NULL OR a.tombstone = 0)
              AND substr($day, 1, 7) BETWEEN ? AND ?
              AND ($transferPredicate)
            GROUP BY $day, 2
            """.trimIndent(),
            startMonth,
            endMonth,
        )
        val result = mutableMapOf<String, Pair<Long, Long>>()
        for (row in rows) {
            val id = row.string("day") ?: continue
            val amount = row.longOr0("amount")
            val (income, expenses) = result[id] ?: (0L to 0L)
            result[id] = when {
                row.bool("is_inflow") -> (income + amount) to expenses
                amount < 0 -> income to (expenses - amount)
                else -> income to expenses
            }
        }
        return result
    }

    private enum class TransferFilter { ALL, TRANSFERS, NON_TRANSFERS }

    /** Reads the first live `calendar-card` of the dashboard for its `transfer is` condition. */
    private fun calendarTransferFilter(): TransferFilter = runCatching {
        val columns = database.columnSet("dashboard")
        if ("type" !in columns || "meta" !in columns) return@runCatching TransferFilter.ALL
        val order = listOf("y", "x").filter { it in columns }.joinToString(", ")
        val orderClause = if (order.isEmpty()) "" else "ORDER BY $order"
        val meta = sql.queryString(
            "SELECT meta FROM dashboard WHERE ${BudgetDatabase.livePredicate(columns)} AND type = 'calendar-card' $orderClause LIMIT 1",
        ) ?: return@runCatching TransferFilter.ALL
        val condition = Regex("""\{[^{}]*"field"\s*:\s*"transfer"[^{}]*\}""").findAll(meta)
            .map { it.value }
            .firstOrNull { Regex(""""op"\s*:\s*"is"""").containsMatchIn(it) }
            ?: return@runCatching TransferFilter.ALL
        val value = Regex(""""value"\s*:\s*(true|false|1|0)""").find(condition)?.groupValues?.get(1)
            ?: return@runCatching TransferFilter.ALL
        if (value == "true" || value == "1") TransferFilter.TRANSFERS else TransferFilter.NON_TRANSFERS
    }.getOrDefault(TransferFilter.ALL)
}
