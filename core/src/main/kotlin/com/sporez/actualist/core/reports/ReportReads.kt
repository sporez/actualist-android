package com.sporez.actualist.core.reports

import com.sporez.actualist.core.budget.BudgetCalculation
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase

data class MonthValue(val month: String, val value: Long)

data class NetWorthReport(val points: List<MonthValue>, val balance: Long, val change: Long)

data class CashFlowSummary(val month: String, val income: Long, val expenses: Long, val net: Long, val uncategorized: Long)

data class CategorySpending(val categoryId: String, val name: String, val groupName: String, val spent: Long, val budgeted: Long)

data class ReportsDashboard(
    val anchorMonth: String,
    val netWorth: NetWorthReport,
    val cashFlow: CashFlowSummary,
    val cashFlowHistory: List<CashFlowSummary>,
    val spending: List<CategorySpending>,
    val budgetedExpenses: Long,
    val hasData: Boolean,
)

/**
 * The reports dashboard, ported from Actualist `BudgetDatabase+Reports` and
 * `+ReportActivity`: net worth sums every live account (on and off budget) through each
 * month; cash flow counts on-budget, non-transfer activity (inflows as income, outflows as
 * expenses) and reports uncategorized activity separately.
 */
class ReportReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun dashboard(anchorMonth: String, endDay: String, monthsBack: Int = 5): ReportsDashboard = sql.locked {
        val startMonth = Months.shifted(anchorMonth, -monthsBack)
        val netWorth = netWorth(startMonth, anchorMonth)
        val history = (monthsBack downTo 0).map { cashFlow(Months.shifted(anchorMonth, -it), if (it == 0) endDay else null) }
        val month = BudgetReads(database).fetchBudgetMonth(anchorMonth)
        val spending = month.categoryGroups.filter { !it.isIncome }.flatMap { group ->
            group.categories.filter { it.spent != 0L || it.budgeted != 0L }.map {
                CategorySpending(it.id, it.name, group.name, -it.spent, it.budgeted)
            }
        }.sortedByDescending { it.spent }
        ReportsDashboard(
            anchorMonth = anchorMonth,
            netWorth = netWorth,
            cashFlow = history.last(),
            cashFlowHistory = history,
            spending = spending,
            budgetedExpenses = month.totalBudgeted,
            hasData = netWorth.points.any { it.value != 0L } || history.any { it.income != 0L || it.expenses != 0L },
        )
    }

    fun netWorth(startMonth: String, anchorMonth: String): NetWorthReport {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val month = "substr(${normalizedDateSql(split.date)}, 1, 7)"
        val changes = sql.query(
            """
            SELECT $month AS month, SUM(${split.amount}) AS amount
            FROM transactions t JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()} AND (a.tombstone IS NULL OR a.tombstone = 0) AND $month <= ?
            GROUP BY $month
            """.trimIndent(),
            anchorMonth,
        ).associate { (it.string("month") ?: "") to it.longOr0("amount") }
        var balance = BudgetCalculation.sum(changes.filterKeys { it < startMonth }.values.toList())
        val points = mutableListOf<MonthValue>()
        var cursor = startMonth
        while (cursor <= anchorMonth) {
            balance = BudgetCalculation.checkedAdd(balance, changes[cursor] ?: 0)
            points += MonthValue(cursor, balance)
            cursor = Months.shifted(cursor, 1)
        }
        val first = points.firstOrNull()?.value ?: balance
        val latest = points.lastOrNull()?.value ?: balance
        return NetWorthReport(points, latest, latest - first)
    }

    /** Cash flow for one month, optionally only through [endDay] (`YYYY-MM-DD`). */
    fun cashFlow(month: String, endDay: String? = null): CashFlowSummary {
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val day = normalizedDateSql(split.date)
        val rows = sql.query(
            """
            SELECT CASE WHEN ${split.category} IS NULL OR ${split.category} = '' THEN 1 ELSE 0 END AS uncategorized,
                   CASE WHEN p.transfer_acct IS NOT NULL AND p.transfer_acct != '' THEN 1 ELSE 0 END AS is_transfer,
                   CASE WHEN ${split.amount} > 0 THEN 1 ELSE 0 END AS is_inflow,
                   SUM(${split.amount}) AS amount
            FROM transactions t
            JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            LEFT JOIN payee_mapping pm ON pm.id = ${split.payee}
            LEFT JOIN payees p ON p.id = COALESCE(pm.targetId, ${split.payee})
            WHERE ${split.liveInlinePredicate()}
              AND (a.tombstone IS NULL OR a.tombstone = 0) AND COALESCE(a.offbudget, 0) = 0
              AND substr($day, 1, 7) = ? AND (? IS NULL OR $day <= ?)
            GROUP BY 1, 2, 3
            """.trimIndent(),
            month,
            endDay,
            endDay,
        )
        var income = 0L
        var expenses = 0L
        var uncategorized = 0L
        for (row in rows) {
            val amount = row.longOr0("amount")
            if (row.bool("is_transfer")) continue
            if (row.bool("uncategorized")) uncategorized += amount
            if (row.bool("is_inflow")) income += amount else expenses -= amount
        }
        return CashFlowSummary(month, income, expenses, income - expenses, uncategorized)
    }
}
