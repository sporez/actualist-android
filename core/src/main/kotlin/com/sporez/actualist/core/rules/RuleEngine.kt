package com.sporez.actualist.core.rules

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.TransactionSplitSql
import com.sporez.actualist.core.budget.normalizedDateSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import java.time.LocalDate
import java.util.UUID

/** The transaction a rule pass starts from (Actualist `TransactionDraft`). */
data class RuleTransactionInput(
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    val payeeId: String? = null,
    val payeeName: String = "",
    val categoryId: String? = null,
    val notes: String? = null,
    val cleared: Boolean = false,
    val isTransfer: Boolean = false,
    val importedPayee: String? = null,
    val sortOrder: Double? = null,
    val reconciled: Boolean = false,
    val isParent: Boolean = false,
    val scheduleId: String? = null,
)

data class RuleSplitResult(
    val id: String?,
    val categoryId: String?,
    val amount: Long,
    val payeeId: String?,
    val notes: String?,
    val sortOrder: Double?,
)

/**
 * The transaction after every rule ran. [payeesToCreate] is always empty: like Actualist's
 * preview, this engine fails closed on rules that would create a payee by name.
 */
data class RuleRunResult(
    val accountId: String,
    val date: LocalDate,
    val amount: Long,
    val payeeId: String?,
    val categoryId: String?,
    val notes: String?,
    val cleared: Boolean,
    val scheduleId: String?,
    val deletesTransaction: Boolean,
    val splits: List<RuleSplitResult>,
    val appliedRuleIds: List<String>,
    val payeesToCreate: List<String> = emptyList(),
)

/**
 * Port of Actualist `BudgetDatabase+Rules` (rule loading and `previewRules`) and
 * `BudgetDatabase+RuleBalanceOf`. Reads only; the caller applies the result.
 */
class RuleEngine(
    private val database: BudgetDatabase,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val sql: Sql get() = database.sql

    fun fetchRules(): List<ManagedRule> = sql.locked { loadRules() }

    fun run(input: RuleTransactionInput, today: LocalDate): RuleRunResult = run(listOf(input), today).first()

    /** Evaluates a batch against one rules, schedules and metadata snapshot. */
    fun run(inputs: List<RuleTransactionInput>, today: LocalDate): List<RuleRunResult> {
        if (inputs.isEmpty()) return emptyList()
        return sql.locked { runLocked(inputs, today) }
    }

    private fun runLocked(inputs: List<RuleTransactionInput>, today: LocalDate): List<RuleRunResult> {
        val rules = loadRules()
        val schedules = loadScheduleIndex()
        val metadata = loadMetadata()
        val formulas = rules.mapNotNull { it.executionDraft() }
            .flatMap { it.actions }
            .mapNotNull { (it.options?.get("formula") as? RuleValue.Text)?.value }
        // A failed prefetch must throw: an empty map would evaluate every BALANCE_OF as 0.
        val balances = prefetchBalanceOf(formulas, inputs.map { Pair(it.date, it.sortOrder) })
        val todayText = ScheduleRecurrence.dayId(today)
        return inputs.mapIndexed { index, input ->
            val context = contextFor(input, metadata)
            context.balanceOfPrefetch = balances[index]
            context.today = todayText
            RuleConditionEvaluator.applying(rules, context, schedules, idGenerator)
            RuleRunResult(
                accountId = context.accountId,
                date = context.date,
                amount = context.amount,
                payeeId = context.payeeId,
                categoryId = context.categoryId,
                notes = context.notes,
                cleared = context.cleared,
                scheduleId = context.scheduleId,
                deletesTransaction = context.deletesTransaction,
                splits = context.splits.map {
                    RuleSplitResult(it.id, it.categoryId, it.amount, it.payeeId, it.notes, it.sortOrder)
                },
                appliedRuleIds = context.appliedRuleIds.toList(),
            )
        }
    }

    private fun contextFor(input: RuleTransactionInput, metadata: RuleMetadata): RuleEvaluationContext {
        val effectiveCategoryId = if (input.isParent) null else input.categoryId
        val categoryGroupId = effectiveCategoryId?.let { metadata.categoryGroupsByCategoryId[it] }
        return RuleEvaluationContext(
            metadata = metadata,
            accountId = input.accountId,
            accountName = metadata.accountNames[input.accountId] ?: "",
            accountIsOffBudget = input.accountId in metadata.offBudgetAccountIds,
            amount = input.amount,
            categoryId = effectiveCategoryId,
            categoryName = effectiveCategoryId?.let { metadata.categoryNames[it] },
            categoryGroupId = categoryGroupId,
            categoryGroupName = categoryGroupId?.let { metadata.categoryGroupNames[it] },
            date = input.date,
            notes = input.notes,
            payeeId = input.payeeId,
            payeeName = input.payeeName,
            importedPayee = input.importedPayee,
            cleared = input.cleared,
            reconciled = input.reconciled,
            isTransfer = input.isTransfer,
            isParent = input.isParent,
            sortOrder = input.sortOrder,
            scheduleId = input.scheduleId,
        )
    }

    // region Rule loading

    private fun loadRules(): List<ManagedRule> {
        if (!database.tableExists("rules")) return emptyList()
        val columns = database.columnSet("rules")
        if (!columns.containsAll(listOf("id", "conditions", "actions"))) return emptyList()
        val stageColumn = if ("stage" in columns) "stage" else "NULL"
        val joinColumn = if ("conditions_op" in columns) "conditions_op" else "'and'"
        val rows = sql.query(
            "SELECT id, $stageColumn AS stage, conditions, actions, $joinColumn AS conditions_op " +
                "FROM rules WHERE ${BudgetDatabase.livePredicate(columns)}",
        )
        val completedRuleIds = loadScheduleIndex().completedRuleIds
        val payeeTargets = payeeMappingTargets()
        val rules = rows.mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            val rawStage = row.string("stage")
            val rawConditions = row.string("conditions") ?: "[]"
            val rawActions = row.string("actions") ?: "[]"
            val rawJoin = row.string("conditions_op")
            val join = RuleConditionJoin.fromRaw(rawJoin ?: "and")
            val stage = rawStage?.let { RuleStage.fromRaw(it) } ?: RuleStage.NORMAL
            val conditions = RuleJson.decodeConditions(rawConditions)?.map { resolvePayeeMappings(it, payeeTargets) }
            val actions = RuleJson.decodeActions(rawActions)
            val decoded = if (conditions != null && actions != null) {
                RuleDraft(stage, join ?: RuleConditionJoin.AND, conditions, actions)
            } else {
                null
            }
            val hasOnlyKnownKeys = RuleJson.conditionsHaveOnlyKnownKeys(rawConditions) &&
                RuleJson.actionsHaveOnlyKnownKeys(rawActions) &&
                (rawStage == null || RuleStage.fromRaw(rawStage) != null) &&
                join != null
            val draft = if (decoded != null && hasOnlyKnownKeys && decoded.canRoundTripAndEvaluate) decoded else null
            ManagedRule(
                id = id,
                draft = draft,
                rawStage = rawStage,
                rawConditionsJson = rawConditions,
                rawActionsJson = rawActions,
                isCompletedScheduleRule = id in completedRuleIds,
                rawConditionsJoin = rawJoin,
            )
        }
        return RuleRanking.rank(rules)
    }

    private fun resolvePayeeMappings(condition: RuleCondition, targets: Map<String, String>): RuleCondition {
        if (condition.fieldName != "payee" && condition.fieldName != "description") return condition
        if (!(condition.type == null || condition.type == RuleValueKind.ID.raw)) return condition
        val value = condition.value
        return when (condition.op) {
            "is", "isNot" ->
                if (value is RuleValue.Text) condition.copy(value = RuleValue.Text(targets[value.value] ?: value.value)) else condition
            "oneOf", "notOneOf" ->
                if (value is RuleValue.Arr) {
                    condition.copy(
                        value = RuleValue.Arr(
                            value.values.map { entry ->
                                if (entry is RuleValue.Text) RuleValue.Text(targets[entry.value] ?: entry.value) else entry
                            },
                        ),
                    )
                } else {
                    condition
                }
            else -> condition
        }
    }

    private fun payeeMappingTargets(): Map<String, String> {
        if (!database.tableExists("payee_mapping")) return emptyMap()
        val columns = database.columnSet("payee_mapping")
        if ("id" !in columns) return emptyMap()
        val target = when {
            "targetId" in columns -> "targetId"
            "target_id" in columns -> "target_id"
            else -> return emptyMap()
        }
        val targets = HashMap<String, String>()
        for (row in sql.query("SELECT id, ${Sql.quoted(target)} AS target_id FROM payee_mapping")) {
            val id = row.string("id") ?: continue
            val targetId = row.string("target_id") ?: continue
            targets[id] = targetId
        }
        return targets
    }

    private fun loadScheduleIndex(): RuleScheduleIndex {
        if (!database.tableExists("schedules")) return RuleScheduleIndex.EMPTY
        val columns = database.columnSet("schedules")
        if ("rule" !in columns) return RuleScheduleIndex.EMPTY
        val idSelection = if ("id" in columns) "id" else "NULL"
        val completedSelection = if ("completed" in columns) "completed" else "0"
        val tombstoneSelection = if ("tombstone" in columns) "tombstone" else "0"
        val rows = sql.query(
            "SELECT $idSelection AS id, rule, $completedSelection AS completed, $tombstoneSelection AS tombstone " +
                "FROM schedules WHERE rule IS NOT NULL",
        )
        val ruleIdByScheduleId = HashMap<String, String>()
        val inactiveRuleIds = HashSet<String>()
        for (row in rows) {
            val ruleId = row.string("rule")
            if (ruleId.isNullOrEmpty()) continue
            if (row.bool("tombstone") || row.bool("completed")) {
                inactiveRuleIds.add(ruleId)
                continue
            }
            val scheduleId = row.string("id")
            if (!scheduleId.isNullOrEmpty()) ruleIdByScheduleId[scheduleId] = ruleId
        }
        inactiveRuleIds.removeAll(ruleIdByScheduleId.values.toSet())
        return RuleScheduleIndex(ruleIdByScheduleId, inactiveRuleIds)
    }

    // endregion

    // region Metadata

    private fun loadMetadata(): RuleMetadata {
        val accountNames = HashMap<String, String>()
        val offBudget = HashSet<String>()
        if (database.tableExists("accounts")) {
            val columns = database.columnSet("accounts")
            val name = if ("name" in columns) "name" else "''"
            val offbudget = if ("offbudget" in columns) "offbudget" else "0"
            for (row in sql.query("SELECT id, $name AS name, $offbudget AS offbudget FROM accounts")) {
                val id = row.string("id") ?: continue
                accountNames[id] = row.string("name") ?: ""
                if (row.bool("offbudget")) offBudget.add(id)
            }
        }
        val categoryNames = HashMap<String, String>()
        val categoryGroupsByCategoryId = HashMap<String, String>()
        val categoryGroupNames = HashMap<String, String>()
        if (database.tableExists("category_groups")) {
            for (row in sql.query("SELECT id, name FROM category_groups")) {
                val id = row.string("id") ?: continue
                categoryGroupNames[id] = row.string("name") ?: ""
            }
        }
        if (database.tableExists("categories")) {
            val columns = database.columnSet("categories")
            val group = when {
                "cat_group" in columns -> "cat_group"
                "group_id" in columns -> "group_id"
                else -> null
            }
            val groupSelection = if (group != null) "${Sql.quoted(group)} AS group_id" else "NULL AS group_id"
            for (row in sql.query("SELECT id, name, $groupSelection FROM categories")) {
                val id = row.string("id") ?: continue
                categoryNames[id] = row.string("name") ?: ""
                val groupId = row.string("group_id")
                if (groupId != null) categoryGroupsByCategoryId[id] = groupId
            }
        }
        val payeeNames = HashMap<String, String>()
        val transferPayeeIds = HashSet<String>()
        if (database.tableExists("payees")) {
            val columns = database.columnSet("payees")
            val transferAccount = if ("transfer_acct" in columns) "transfer_acct" else "NULL"
            for (row in sql.query("SELECT id, name, $transferAccount AS transfer_account FROM payees")) {
                val id = row.string("id") ?: continue
                val transferAccountId = row.string("transfer_account")
                if (transferAccountId != null) transferPayeeIds.add(id)
                val rawName = row.string("name") ?: ""
                payeeNames[id] = if (rawName.isEmpty()) (transferAccountId?.let { accountNames[it] } ?: "") else rawName
            }
        }
        return RuleMetadata(
            accountNames, offBudget, categoryNames, categoryGroupsByCategoryId,
            categoryGroupNames, payeeNames, transferPayeeIds,
        )
    }

    // endregion

    // region BALANCE_OF

    private class LedgerRow(val day: String, val sortOrder: Double?, val amount: Long)

    private class Ledger(val ordersBySortOrder: Boolean, rows: List<LedgerRow>) {
        private val sorted: List<LedgerRow> = rows.sortedBy { it.day }

        /** Live inline rows on an earlier day, plus same-day rows with a lower non-null sort order. */
        fun balanceBefore(day: String, sortOrder: Double?): Long {
            var total = 0L
            try {
                for (row in sorted) {
                    if (row.day > day) break
                    if (row.day < day) {
                        total = Math.addExact(total, row.amount)
                    } else {
                        val rowOrder = row.sortOrder ?: continue
                        val comparison = if (ordersBySortOrder) sortOrder else null
                        if (comparison == null || rowOrder < comparison) total = Math.addExact(total, row.amount)
                    }
                }
            } catch (e: ArithmeticException) {
                throw LocalFirstException.InvalidLocalWrite("BALANCE_OF total overflowed")
            }
            return total
        }
    }

    /**
     * Prefetched balances for Actual `BALANCE_OF("...")` literals, one map per cutoff
     * (`result[i]` belongs to `cutoffs[i]`). Missing accounts resolve to 0. The cutoff matches
     * loot-core `getRunningBalanceBeforeTransaction`.
     */
    private fun prefetchBalanceOf(formulas: List<String>, cutoffs: List<Pair<LocalDate, Double?>>): List<Map<String, Long>> {
        val empty = cutoffs.map { emptyMap<String, Long>() }
        val literals = LinkedHashSet<String>()
        for (formula in formulas) literals.addAll(RuleFormulaEvaluator.extractBalanceOfLiterals(formula))
        if (literals.isEmpty() || cutoffs.isEmpty() || !database.tableExists("accounts")) return empty
        val columns = database.columnSet("accounts")
        if ("id" !in columns) return empty
        val name = if ("name" in columns) "name" else "id"
        val order = if ("sort_order" in columns) "sort_order, lower(name)" else "lower(name)"
        val accounts = sql.query(
            "SELECT id, $name AS name FROM accounts WHERE ${BudgetDatabase.livePredicate(columns)} ORDER BY $order",
        ).mapNotNull { row ->
            val id = row.string("id") ?: return@mapNotNull null
            Pair(id, row.string("name") ?: "")
        }
        val accountIds = accounts.map { it.first }.toSet()
        val resolved = HashMap<String, String?>()
        for (literal in literals) {
            resolved[literal] = if (literal in accountIds) literal else accounts.firstOrNull { it.second == literal }?.first
        }
        val ledgers = HashMap<String, Ledger>()
        for (accountId in resolved.values) {
            if (accountId != null && accountId !in ledgers) ledgers[accountId] = ledgerFor(accountId)
        }
        return cutoffs.map { cutoff ->
            val day = ScheduleRecurrence.dayId(cutoff.first)
            val balances = HashMap<String, Long>()
            for (literal in literals) {
                val accountId = resolved[literal]
                balances[literal] = if (accountId == null) 0L else ledgers.getValue(accountId).balanceBefore(day, cutoff.second)
            }
            balances
        }
    }

    private fun ledgerFor(accountId: String): Ledger {
        if (!database.tableExists("transactions")) return Ledger(false, emptyList())
        val columns = database.columnSet("transactions")
        val expressions = TransactionSplitSql(columns, "t")
        val rows = sql.query(
            "SELECT ${normalizedDateSql(expressions.date)} AS day, ${expressions.sortOrder} AS sort_order, " +
                "${expressions.amount} AS amount FROM transactions t ${expressions.parentJoin()} " +
                "WHERE ${expressions.liveInlinePredicate()} AND ${expressions.account} = ?",
            accountId,
        )
        val ledgerRows = rows.mapNotNull { row ->
            val day = row.string("day") ?: return@mapNotNull null
            // A text or blob sort order sorts after every number in SQLite.
            val sortOrder: Double? = when (val raw = row["sort_order"]) {
                null -> null
                is Long -> raw.toDouble()
                is Double -> raw
                else -> Double.POSITIVE_INFINITY
            }
            LedgerRow(day, sortOrder, row.long("amount") ?: 0L)
        }
        return Ledger("sort_order" in columns, ledgerRows)
    }

    // endregion
}
