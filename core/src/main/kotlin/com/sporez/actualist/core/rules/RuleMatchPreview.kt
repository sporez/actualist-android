package com.sporez.actualist.core.rules

import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import java.time.LocalDate

/** One existing transaction a rule draft matches (Actualist `RuleTransactionMatch`). */
data class RuleTransactionMatch(
    val id: String,
    val date: String,
    val payeeName: String,
    val categoryName: String,
    val accountName: String,
    val amount: Long,
)

/** The first [transactions] of [totalCount] matches. */
data class RuleMatchPreview(val transactions: List<RuleTransactionMatch>, val totalCount: Int)

/**
 * Which live, non-parent transactions a rule draft's conditions match, as Actualist's
 * `fetchMatchingTransactions`: each row is evaluated with [RuleConditionEvaluator.conditionsMatch]
 * against its payee (resolved through `payee_mapping`), category, account and flags. Reads only.
 */
class RuleMatches(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun matching(draft: RuleDraft, limit: Int = 25, today: LocalDate = LocalDate.now()): RuleMatchPreview {
        if (draft.conditions.isEmpty() || !database.tableExists("transactions")) return RuleMatchPreview(emptyList(), 0)
        val metadata = RuleEngine(database).metadata()
        val columns = database.columnSet("transactions")
        val split = TransactionSplitSql(columns, "t")
        val imported = if ("imported_description" in columns) "t.imported_description" else "NULL"
        val mapping = if (database.tableExists("payee_mapping")) {
            "LEFT JOIN payee_mapping pm ON pm.id = ${split.payee}"
        } else {
            ""
        }
        val payee = if (mapping.isEmpty()) split.payee else "COALESCE(pm.targetId, ${split.payee})"
        val rows = sql.query(
            "SELECT t.id AS id, ${normalizedDateSql(split.date)} AS day, ${split.account} AS acct, ${split.amount} AS amount, " +
                "${split.category} AS category, $payee AS payee, ${split.notes} AS notes, ${split.cleared} AS cleared, " +
                "${split.reconciled} AS reconciled, ${split.sortOrder} AS sort_order, $imported AS imported, ${split.schedule} AS schedule " +
                "FROM transactions t ${split.parentJoin()} $mapping WHERE ${split.liveInlinePredicate()} ORDER BY ${split.defaultOrder()}",
        )
        val todayText = ScheduleRecurrence.dayId(today)
        val matches = mutableListOf<RuleTransactionMatch>()
        for (row in rows) {
            val id = row.string("id") ?: continue
            val day = row.string("day") ?: continue
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: continue
            val accountId = row.string("acct") ?: continue
            val payeeId = row.string("payee")?.takeIf { it.isNotEmpty() }
            val categoryId = row.string("category")?.takeIf { it.isNotEmpty() }
            val isTransfer = payeeId != null && payeeId in metadata.transferPayeeIds
            val payeeName = payeeId?.let { metadata.payeeNames[it] } ?: ""
            val groupId = categoryId?.let { metadata.categoryGroupsByCategoryId[it] }
            val context = RuleEvaluationContext(
                metadata = metadata,
                accountId = accountId,
                accountName = metadata.accountNames[accountId] ?: "",
                accountIsOffBudget = accountId in metadata.offBudgetAccountIds,
                amount = row.longOr0("amount"),
                categoryId = categoryId,
                categoryName = categoryId?.let { metadata.categoryNames[it] },
                categoryGroupId = groupId,
                categoryGroupName = groupId?.let { metadata.categoryGroupNames[it] },
                date = date,
                notes = row.string("notes"),
                payeeId = payeeId,
                payeeName = payeeName,
                importedPayee = row.string("imported"),
                cleared = row.bool("cleared"),
                reconciled = row.bool("reconciled"),
                isTransfer = isTransfer,
                isParent = false,
                sortOrder = row.double("sort_order"),
                scheduleId = row.string("schedule"),
            )
            context.today = todayText
            if (!RuleConditionEvaluator.conditionsMatch(draft, context)) continue
            val categoryName = when {
                categoryId != null -> metadata.categoryNames[categoryId] ?: "Deleted category"
                isTransfer -> "Account Transfer"
                else -> "Uncategorized"
            }
            matches += RuleTransactionMatch(
                id = id,
                date = day,
                payeeName = payeeName.ifEmpty { row.string("imported") ?: "Unknown payee" },
                categoryName = categoryName,
                accountName = metadata.accountNames[accountId] ?: "Deleted account",
                amount = row.longOr0("amount"),
            )
        }
        return RuleMatchPreview(matches.take(maxOf(0, limit)), matches.size)
    }
}
