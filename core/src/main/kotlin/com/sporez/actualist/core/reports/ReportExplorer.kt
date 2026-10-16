package com.sporez.actualist.core.reports

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.query.QueryCondition
import com.sporez.actualist.core.query.QueryDateCondition
import com.sporez.actualist.core.query.QueryDateOperation
import com.sporez.actualist.core.query.QueryDay
import com.sporez.actualist.core.query.QueryIdCondition
import com.sporez.actualist.core.query.QueryTransaction
import com.sporez.actualist.core.query.TransactionQueries
import java.time.LocalDate

data class ExplorerFilter(
    val start: LocalDate,
    val end: LocalDate,
    /** Null means every account. */
    val accountIds: Set<String>? = null,
    /** Null means every category; an empty set is never sent. */
    val categoryIds: Set<String>? = null,
    val transfers: ReportTransferFilter = ReportTransferFilter.NON_TRANSFERS,
)

data class ExplorerCategoryTotal(val categoryId: String?, val total: Long, val count: Int)

data class ExplorerResult(
    val income: Long,
    val expenses: Long,
    val byCategory: List<ExplorerCategoryTotal>,
    /** The matching rows (split children rather than their parents), newest first. */
    val rows: List<QueryTransaction>,
) {
    val net: Long get() = income + expenses
}

/**
 * Actualist's Report Explorer: totals and per-category amounts for a date range, optionally
 * narrowed to accounts and categories, through the ported transaction query engine (so split
 * children count under their own categories and transfers can be left out).
 */
class ReportExplorer(private val database: BudgetDatabase) {
    fun explore(filter: ExplorerFilter): ExplorerResult {
        val conditions = buildList {
            add(QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_ON_OR_AFTER, QueryDay.of(filter.start.toString())!!)))
            add(QueryCondition.Date(QueryDateCondition(QueryDateOperation.IS_ON_OR_BEFORE, QueryDay.of(filter.end.toString())!!)))
            filter.accountIds?.takeIf { it.isNotEmpty() }?.let { add(QueryCondition.Account(QueryIdCondition.oneOf(it.toList()))) }
            filter.categoryIds?.takeIf { it.isNotEmpty() }?.let { add(QueryCondition.Category(QueryIdCondition.oneOf(it.toList()))) }
            when (filter.transfers) {
                ReportTransferFilter.ALL -> Unit
                ReportTransferFilter.TRANSFERS -> add(QueryCondition.Transfer(true))
                ReportTransferFilter.NON_TRANSFERS -> add(QueryCondition.Transfer(false))
            }
        }
        val page = TransactionQueries(database).query(conditions = conditions)
        val rows = page.contributingTransactions.filter { !it.isParent && it.amount != null }.sortedByDescending { it.date }
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
}
