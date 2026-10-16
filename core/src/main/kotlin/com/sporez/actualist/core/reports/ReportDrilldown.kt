package com.sporez.actualist.core.reports

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.query.QueryCondition
import com.sporez.actualist.core.query.QueryDateCondition
import com.sporez.actualist.core.query.QueryDateOperation
import com.sporez.actualist.core.query.QueryDay
import com.sporez.actualist.core.query.QueryIdCondition
import com.sporez.actualist.core.query.QueryTransaction
import com.sporez.actualist.core.query.TransactionQueries

/** Whether a drilldown row is part of the report's total ("Counted") or shown only for its split family ("Context only"). */
enum class DrilldownRole { COUNTED, CONTEXT }

/** A row's place in a split family. */
enum class DrilldownKind { ROOT, SPLIT_PARENT, SPLIT_CHILD }

data class DrilldownRow(val transaction: QueryTransaction, val role: DrilldownRole, val kind: DrilldownKind) {
    val id: String get() = transaction.id ?: "${transaction.date}-${transaction.hashCode()}"
}

/** One day's rows, newest day first. [date] is `YYYY-MM-DD`. */
data class DrilldownGroup(val date: String, val rows: List<DrilldownRow>)

data class DrilldownResult(val groups: List<DrilldownGroup>, val countedCount: Int)

/** Actualist's `ReportTransactionDrilldownProjection`: date groups with each split family kept together. */
object ReportDrilldownProjection {
    fun project(roots: List<QueryTransaction>, contributingIds: Set<String>): DrilldownResult {
        val seen = HashSet<String>()
        fun role(transaction: QueryTransaction) =
            if (transaction.id != null && transaction.id in contributingIds) DrilldownRole.COUNTED else DrilldownRole.CONTEXT

        fun append(transaction: QueryTransaction, kind: DrilldownKind, rows: MutableList<DrilldownRow>) {
            val key = transaction.id ?: "${transaction.date}-${transaction.hashCode()}"
            if (!seen.add(key)) return
            rows.add(DrilldownRow(transaction, role(transaction), kind))
        }

        val groups = roots.groupBy { it.date }.toSortedMap(compareByDescending<String> { it }).map { (date, items) ->
            val rows = ArrayList<DrilldownRow>()
            for (transaction in items) {
                append(transaction, if (transaction.isChild) DrilldownKind.SPLIT_CHILD else if (transaction.isParent) DrilldownKind.SPLIT_PARENT else DrilldownKind.ROOT, rows)
                for (child in transaction.subtransactions) append(child, DrilldownKind.SPLIT_CHILD, rows)
            }
            DrilldownGroup(date, rows)
        }.filter { it.rows.isNotEmpty() }
        return DrilldownResult(groups, groups.sumOf { group -> group.rows.count { it.role == DrilldownRole.COUNTED } })
    }
}

/** The transactions behind a report range, run through the same query as [ReportExplorer]. */
class ReportDrilldown(private val database: BudgetDatabase) {
    fun load(filter: ExplorerFilter): DrilldownResult {
        // An empty account set is "None" in the filter sheet: nothing is counted, so nothing is listed.
        if (filter.accountIds?.isEmpty() == true) return DrilldownResult(emptyList(), 0)
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
        return ReportDrilldownProjection.project(page.transactions, page.contributingTransactionIds)
    }
}
