package com.sporez.actualist.core.rules

import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import java.time.LocalDate
import java.util.UUID

/**
 * Actual's category learning (`updateCategoryRules`, as Actualist ports it): after the user
 * categorizes transactions, each affected payee's five latest transactions (from 180 days before
 * the oldest changed one to 180 days ahead, open accounts, payees with learning on) are scored;
 * when one category wins with at least three of them, a "payee is X → set category" rule is
 * created, or the existing ones are pointed at that category. The budget-wide
 * `learn-categories` preference set to "false" turns it off.
 */
class CategoryLearning(private val database: BudgetDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val sql get() = database.sql

    fun enabled(): Boolean {
        if (!database.tableExists("preferences")) return true
        return sql.queryString("SELECT value FROM preferences WHERE id = 'learn-categories' LIMIT 1") != "false"
    }

    /** Rule cells to learn from [changedTransactionIds] (rows whose category the user just set). */
    fun drafts(changedTransactionIds: Collection<String>, today: LocalDate): List<CellDraft> {
        if (changedTransactionIds.isEmpty() || !enabled()) return emptyList()
        if (!database.tableExists("payees") || "learn_categories" !in database.columnSet("payees")) return emptyList()
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val date = normalizedDateSql(split.date)
        val ids = changedTransactionIds.distinct()
        val marks = ids.joinToString(",") { "?" }
        val changed = sql.query(
            "SELECT t.id AS id, $date AS date, COALESCE(pm.targetId, t.description) AS payee_id FROM transactions t " +
                "LEFT JOIN payee_mapping pm ON pm.id = t.description WHERE t.id IN ($marks)",
            *ids.toTypedArray(),
        )
        val payeeIds = changed.mapNotNull { it.string("payee_id") }.filter { it.isNotEmpty() }.toSet()
        if (payeeIds.isEmpty()) return emptyList()
        val oldest = changed.mapNotNull { row -> row.string("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }.minOrNull() ?: today
        val payeeMarks = payeeIds.joinToString(",") { "?" }
        val register = sql.query(
            """
            SELECT t.id AS id, t.category AS category, COALESCE(pm.targetId, t.description) AS payee_id
            FROM transactions t
            LEFT JOIN payee_mapping pm ON pm.id = t.description
            LEFT JOIN accounts a ON a.id = t.acct
            JOIN payees p ON p.id = COALESCE(pm.targetId, t.description)
            WHERE (t.tombstone IS NULL OR t.tombstone = 0) AND (t.isParent IS NULL OR t.isParent = 0)
              AND (a.closed IS NULL OR a.closed = 0) AND p.learn_categories = 1
              AND $date >= ? AND $date <= ? AND COALESCE(pm.targetId, t.description) IN ($payeeMarks)
            ORDER BY $date DESC, t.id
            """.trimIndent(),
            oldest.minusDays(180).toString(), today.plusDays(180).toString(), *payeeIds.toTypedArray(),
        )
        val byPayee = register.groupBy { it.string("payee_id") ?: "" }
        val changedIds = ids.toSet()
        val categoriesToSet = LinkedHashMap<String, String>()
        for (payeeId in payeeIds.sorted()) {
            val latest = byPayee[payeeId].orEmpty().take(5)
            if (latest.none { it.string("id") in changedIds }) continue
            probableCategory(latest.map { it.string("category") })?.let { categoriesToSet[payeeId] = it }
        }
        if (categoriesToSet.isEmpty()) return emptyList()
        val rules = RuleEngine(database).fetchRules()
        val writes = RuleWrites(database, newId)
        val drafts = mutableListOf<CellDraft>()
        for ((payeeId, category) in categoriesToSet) {
            val setters = rules.mapNotNull { rule -> rule.draft?.let { rule.id to it } }.filter { (_, draft) ->
                draft.stage == RuleStage.NORMAL && draft.conditions.size == 1 &&
                    draft.conditions[0].let { (it.fieldName == "payee" || it.fieldName == "description") && it.op == "is" && it.value == RuleValue.Text(payeeId) } &&
                    draft.actions.firstOrNull()?.let { it.op == "set" && it.fieldName == "category" } == true
            }
            if (setters.isEmpty()) {
                drafts += writes.createDrafts(
                    newId(),
                    RuleDraft(
                        stage = RuleStage.NORMAL,
                        join = RuleConditionJoin.AND,
                        conditions = listOf(RuleCondition("payee", "is", RuleValue.Text(payeeId), "id")),
                        actions = listOf(RuleAction("set", "category", RuleValue.Text(category), "id")),
                    ),
                )
            } else {
                for ((ruleId, draft) in setters) {
                    val action = draft.actions[0]
                    if (action.value == RuleValue.Text(category)) continue
                    drafts += writes.updateDrafts(ruleId, draft.copy(actions = listOf(action.copy(value = RuleValue.Text(category))) + draft.actions.drop(1)))
                }
            }
        }
        return drafts
    }

    /** Commits what [drafts] plans, in its own batch (callers run it in the same session write as the edit). */
    fun learn(changedTransactionIds: Collection<String>, today: LocalDate): List<com.sporez.actualist.core.sync.SyncMessage> =
        database.commitLocal { drafts(changedTransactionIds, today) to Unit }.messages

    companion object {
        /**
         * Actual's `getProbableCategory`: newest first, a later row only displaces the current winner
         * with a strictly higher count, so an uncategorized newest row means nothing is learned.
         */
        fun probableCategory(categories: List<String?>): String? {
            if (categories.isEmpty()) return null
            val scores = categories.filterNotNull().groupingBy { it }.eachCount()
            var winner: String? = categories[0]
            var best: Int? = categories[0]?.let { scores[it] }
            for (category in categories.drop(1)) {
                val score = category?.let { scores[it] } ?: continue
                if (best != null && score > best) {
                    winner = category
                    best = score
                }
            }
            return if (best != null && best >= 3) winner else null
        }
    }
}
