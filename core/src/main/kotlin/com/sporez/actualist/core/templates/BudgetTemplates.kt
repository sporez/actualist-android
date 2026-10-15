package com.sporez.actualist.core.templates

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.BudgetCalculation
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.budget.CategoryValue
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.budget.normalizedMonthSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.time.ZoneId

enum class TemplateMetricKind { AVAILABLE, BALANCE, RECEIVED }

/** The category figure a template apply moves: Available (envelope), Balance (tracking expense) or Received (tracking income). */
data class TemplateMetric(val kind: TemplateMetricKind, val before: Long, val after: Long)

data class TemplateCategoryPreview(
    val categoryId: String,
    val name: String,
    val current: Long,
    val proposed: Long,
    /** The proposed amount split across the category's template entries, in entry order. */
    val perTemplate: List<Long>,
    val evaluatedDemand: Long,
    /** Positive demand not met because the funds ran out. */
    val shortfall: Long,
    val isGoalOnlyUpdate: Boolean,
    val goalBefore: Long?,
    val goalAfter: Long?,
    val metric: TemplateMetric,
    /** The distinct priorities of the category's template entries, ascending. */
    val priorityLevels: List<Int> = emptyList(),
)

/**
 * What applying templates to a month would do, without writing anything. [errors] holds the
 * refusal reason when the templates cannot be applied (then [categories] is empty).
 */
data class TemplatePreview(
    val month: String,
    val mode: TemplateApplyMode,
    val categories: List<TemplateCategoryPreview> = emptyList(),
    /** Money newly assigned (positive deltas). */
    val assigned: Long = 0,
    /** Money released (negative deltas, as a positive number). */
    val released: Long = 0,
    /** Funds left after the apply: To Budget for envelope budgets, Total Saved for tracking. */
    val leftover: Long = 0,
    val isTracking: Boolean = false,
    val evaluatedDemand: Long = 0,
    val fundingRequired: Long = 0,
    val stillNeeded: Long = 0,
    val availableBefore: Long = 0,
    val availableAfter: Long = 0,
    val hasNonMoneyUpdates: Boolean = false,
    val hasEligibleTemplates: Boolean = false,
    val errors: List<String> = emptyList(),
) {
    val isReady: Boolean get() = errors.isEmpty()
}

data class TemplateAssignment(val categoryId: String, val amount: Long)

/** The committed result of an apply: the per-category assignments and the CRDT messages written. */
data class TemplateApplyResult(val assignments: List<TemplateAssignment>, val messages: List<SyncMessage>) {
    val messageCount: Int get() = messages.size
    val isNoOp: Boolean get() = messages.isEmpty()
}

internal class PreparedTemplatePlan(
    val compute: TemplateEngine.ComputePlan,
    val currentBudgeted: Map<String, Long>,
    val currentGoals: Map<String, Long>,
    val entriesByCategory: Map<String, List<TemplateEntry>>,
    val categoryIsIncome: Map<String, Boolean>,
    val isTracking: Boolean,
    val canWriteGoals: Boolean,
    val orphanGoalCategoryIds: List<String>,
    val table: BudgetTable,
    val columns: Set<String>,
    val initialAvailableBudget: Long,
    val history: TemplateHistory,
)

/**
 * Budget templates (Actual's `#template` goal notes and `template_settings`) applied to a month.
 * Port of Actualist's `BudgetDatabase+TemplateApply`, `+TemplatePreview` and `+TemplateWrites`.
 *
 * Only stored `categories.goal_def` definitions are applied; note text is read only to refuse a
 * stale note-managed definition (see [TemplateNoteGuard]).
 */
class BudgetTemplates(
    private val database: BudgetDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val reads = TemplateReads(database)
    private val sql get() = database.sql

    /** The calendar day [clock] reports in [zone]. */
    fun today(): LocalDate = java.time.Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    /** True when any live category has a stored template definition. */
    fun hasTemplates(): Boolean = reads.goalDefsRaw().values.any { json ->
        val trimmed = json.trim()
        trimmed.isNotEmpty() && trimmed != "null" && trimmed != "[]"
    }

    /**
     * Previews applying templates to [month] (`YYYY-MM`). [today] sets the current month that
     * average templates look back from. With [categoryIds] only those categories are evaluated,
     * regardless of their current budget; without it [mode] decides whether budgeted categories are skipped.
     */
    fun preview(
        month: String,
        today: LocalDate = today(),
        categoryIds: Set<String>? = null,
        mode: TemplateApplyMode = TemplateApplyMode.FILL_EMPTY,
    ): TemplatePreview = previewForCurrentMonth(month, Months.id(today.year, today.monthValue), categoryIds, mode)

    /**
     * Applies templates to [month] and commits the budget writes. With [categoryIds] only those
     * categories are applied (and always overwritten); otherwise [mode] decides. Throws
     * [UnsupportedTemplateException] without writing anything when a definition cannot be applied.
     */
    fun apply(
        month: String,
        today: LocalDate = today(),
        categoryIds: Set<String>? = null,
        mode: TemplateApplyMode = TemplateApplyMode.FILL_EMPTY,
    ): TemplateApplyResult = applyForCurrentMonth(month, Months.id(today.year, today.monthValue), categoryIds, mode)

    fun previewForCurrentMonth(
        month: String,
        currentMonth: String,
        categoryIds: Set<String>? = null,
        mode: TemplateApplyMode = TemplateApplyMode.FILL_EMPTY,
    ): TemplatePreview = sql.locked {
        try {
            buildPreview(month, currentMonth, categoryIds, mode)
        } catch (error: UnsupportedTemplateException) {
            TemplatePreview(month = month, mode = mode, errors = listOf(error.reason))
        } catch (error: LocalFirstException) {
            TemplatePreview(month = month, mode = mode, errors = listOf(error.message ?: "The template preview could not be loaded."))
        }
    }

    fun applyForCurrentMonth(
        month: String,
        currentMonth: String,
        categoryIds: Set<String>? = null,
        mode: TemplateApplyMode = TemplateApplyMode.FILL_EMPTY,
    ): TemplateApplyResult {
        val committed = database.commitLocal {
            val prepared = plan(month, currentMonth, categoryIds, mode, skipAvailableClamp = false, skipStaleCheck = false)
            val drafts = applyDrafts(prepared, month)
            val assignments = prepared.compute.writes
                .filter { prepared.currentBudgeted[it.categoryId] != it.amount }
                .map { TemplateAssignment(it.categoryId, it.amount) }
            drafts to assignments
        }
        return TemplateApplyResult(committed.outcome, committed.messages)
    }

    // region Plan

    internal fun plan(
        month: String,
        currentMonth: String,
        categoryIds: Set<String>?,
        mode: TemplateApplyMode,
        skipAvailableClamp: Boolean,
        skipStaleCheck: Boolean,
        history: TemplateHistory = TemplateHistory(reads.budgetReads),
        goalDefOverrides: Map<String, String> = emptyMap(),
    ): PreparedTemplatePlan {
        val monthValue = Months.actualMonthValue(month)
        val currentMonthValue = Months.actualMonthValue(currentMonth)
        val currency = reads.currency()
        val engine = TemplateEngine(currency)
        val table = reads.budgetReads.budgetTable()
        val columns = database.requiredColumns(table.tableName, listOf("month", "category", "amount"))
        val canWriteGoals = "goal" in columns
        val goalDefsRaw = HashMap(reads.goalDefsRaw())
        goalDefsRaw.putAll(goalDefOverrides)
        val categoryNames = reads.categoryNames()
        val categoryIsIncome = reads.budgetReads.categoryIsIncomeById()
        val isTracking = table == BudgetTable.TRACKING
        val targeted = (categoryIds ?: emptySet()).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val scope: List<String> = if (targeted.isEmpty()) {
            reads.categoryIdsInBudgetOrder(includeIncome = isTracking, includeHidden = false)
        } else {
            reads.categoryIdsInCategoryOrder().filter { it in targeted }
        }

        if (!skipStaleCheck) {
            val scopeSet = scope.toSet()
            val staleSource = goalDefsRaw.filter { it.key in scopeSet && goalDefOverrides[it.key] == null }
            val stale = TemplateNoteGuard.staleCategories(reads, staleSource)
            if (stale.isNotEmpty()) {
                val described = stale.map { (categoryNames[it.categoryId] ?: it.categoryId) + " — " + it.reason }.sorted()
                throw UnsupportedTemplateException(
                    "note-managed template definition(s) are stale relative to their category notes and were not applied: " +
                        "${described.joinToString("; ")}. Open the budget in Actual and apply templates once to refresh the stored definitions.",
                )
            }
        }

        val force = mode == TemplateApplyMode.OVERWRITE || targeted.isNotEmpty()
        val monthKey = Months.monthId(monthValue)
        val currentBudgets: Map<String, Long> = reads.budgetReads.categoryBudgets(monthKey).mapValues { it.value.first }
        val existingGoals = if (canWriteGoals) reads.categoryGoals(monthKey) else emptyMap()

        val unsupported = mutableListOf<String>()
        val categoryTemplates = LinkedHashMap<String, List<TemplateEntry>>()
        val orphanGoalCategoryIds = mutableListOf<String>()
        val initialAvailableBudget = if (isTracking) {
            reads.budgetReads.fetchBudgetMonth(monthKey).trackingSummary?.plannedSavings ?: 0L
        } else {
            reads.budgetReads.envelopeToBudget(monthKey)
        }
        var availableBudget = initialAvailableBudget
        val incomeCatalog = reads.catalogWithSchedules()
        for (categoryId in scope) {
            val currentBudgeted = currentBudgets[categoryId] ?: 0L
            val json = goalDefsRaw[categoryId]
            if (json == null) {
                if (existingGoals[categoryId] != null) orphanGoalCategoryIds.add(categoryId)
                continue
            }
            if (!force && currentBudgeted != 0L) continue

            val label = categoryNames[categoryId] ?: categoryId
            try {
                val entries = engine.decodeSupportedEntries(json)
                if (entries != null) {
                    engine.validate(entries, monthValue)
                    engine.validatePercentageSources(entries, incomeCatalog)
                    engine.validateByScheduleAndSpend(entries, monthValue, incomeCatalog.activeScheduleNames, incomeCatalog.activeScheduleIds)
                    categoryTemplates[categoryId] = entries
                    if (!entries.isGoalOnly) availableBudget = TemplateMath.add(availableBudget, currentBudgeted)
                }
            } catch (error: UnsupportedTemplateException) {
                unsupported.add("$label (${error.reason})")
                continue
            } catch (error: Exception) {
                unsupported.add("$label (unreadable template definition)")
                continue
            }
        }

        if (unsupported.isNotEmpty()) {
            throw UnsupportedTemplateException("categories use template types not supported yet: ${unsupported.sorted().joinToString(", ")}")
        }

        val inputs = reads.engineInputs(categoryTemplates, monthValue, categoryIsIncome, currentBudgets, isTracking, history, currency)
        val compute = engine.computePlan(
            categories = inputs.categories,
            orderedCategoryIds = scope.filter { inputs.categories.containsKey(it) },
            monthValue = monthValue,
            availableBudget = availableBudget,
            monthSources = inputs.monthSources,
            currentMonthValue = currentMonthValue,
            skipAvailableClamp = skipAvailableClamp,
        )
        if (compute.writes.any { it.longGoal == 1L } && !canWriteGoals) {
            throw UnsupportedTemplateException("goal writes require ${table.tableName}.goal")
        }
        return PreparedTemplatePlan(
            compute = compute,
            currentBudgeted = currentBudgets,
            currentGoals = existingGoals,
            entriesByCategory = categoryTemplates,
            categoryIsIncome = categoryIsIncome,
            isTracking = isTracking,
            canWriteGoals = canWriteGoals,
            orphanGoalCategoryIds = orphanGoalCategoryIds,
            table = table,
            columns = columns,
            initialAvailableBudget = initialAvailableBudget,
            history = history,
        )
    }

    // endregion

    // region Writes

    private fun applyDrafts(prepared: PreparedTemplatePlan, month: String): List<CellDraft> {
        val monthValue = Months.actualMonthValue(month)
        val drafts = mutableListOf<CellDraft>()
        for (write in prepared.compute.writes.sortedBy { it.categoryId }) {
            drafts.addAll(assignDrafts(write.categoryId, write.amount, monthValue, prepared.table, prepared.columns))
            if (prepared.canWriteGoals) {
                drafts.addAll(goalDrafts(write.categoryId, write.goal, write.longGoal, monthValue, prepared.table, prepared.columns))
            }
        }
        if (prepared.canWriteGoals) {
            for (categoryId in prepared.orphanGoalCategoryIds.sorted()) {
                drafts.addAll(goalDrafts(categoryId, null, null, monthValue, prepared.table, prepared.columns))
            }
        }
        drafts.addAll(orphanCleanupGroupDrafts())
        return drafts
    }

    /** Actual's setBudget: update an existing row's amount, or insert identity cells and amount. */
    private fun assignDrafts(categoryId: String, budgeted: Long, monthValue: Int, table: BudgetTable, columns: Set<String>): List<CellDraft> {
        val existing = budgetRowId(table, monthValue, categoryId, columns)
        val rowId = existing ?: BudgetWrites.budgetRowId(monthValue, categoryId)
        val drafts = mutableListOf<CellDraft>()
        if (existing == null) drafts.addAll(identityDrafts(table, rowId, monthValue, categoryId))
        drafts.add(CellDraft(table.tableName, rowId, "amount", SyncValue.int(budgeted)))
        return drafts
    }

    private fun goalDrafts(
        categoryId: String,
        goal: Long?,
        longGoal: Long?,
        monthValue: Int,
        table: BudgetTable,
        columns: Set<String>,
    ): List<CellDraft> {
        if ("goal" !in columns) return emptyList()
        val existing = budgetRowId(table, monthValue, categoryId, columns)
        val rowId = existing ?: BudgetWrites.budgetRowId(monthValue, categoryId)
        val dataset = table.tableName
        val drafts = mutableListOf<CellDraft>()
        drafts.addAll(identityDrafts(table, rowId, monthValue, categoryId))
        if (existing == null && "carryover" in columns) drafts.add(CellDraft(dataset, rowId, "carryover", SyncValue.bool(false)))
        drafts.add(CellDraft(dataset, rowId, "goal", if (goal != null) SyncValue.int(goal) else SyncValue.Null))
        if ("long_goal" in columns) {
            drafts.add(CellDraft(dataset, rowId, "long_goal", if (longGoal != null) SyncValue.int(longGoal) else SyncValue.Null))
        }
        return drafts
    }

    private fun identityDrafts(table: BudgetTable, rowId: String, monthValue: Int, categoryId: String) = listOf(
        CellDraft(table.tableName, rowId, "month", SyncValue.int(monthValue)),
        CellDraft(table.tableName, rowId, "category", SyncValue.text(categoryId)),
    )

    private fun budgetRowId(table: BudgetTable, monthValue: Int, categoryId: String, columns: Set<String>): String? {
        val idSelection = if ("id" in columns) "id" else "NULL"
        val row = sql.queryOne(
            "SELECT $idSelection AS id FROM ${Sql.quoted(table.tableName)} " +
                "WHERE ${normalizedMonthSql("month")} = ? AND category = ? LIMIT 1",
            Months.monthId(monthValue),
            categoryId,
        ) ?: return null
        return if ("id" in columns) row.string("id") else BudgetWrites.budgetRowId(monthValue, categoryId)
    }

    /** Tombstones cleanup groups no live category's `cleanup_def` references any more. */
    private fun orphanCleanupGroupDrafts(): List<CellDraft> {
        if (!database.tableExists("cleanup_groups") || !database.tableExists("categories")) return emptyList()
        val groupColumns = database.columnSet("cleanup_groups")
        val categoryColumns = database.columnSet("categories")
        if ("tombstone" !in groupColumns || "cleanup_def" !in categoryColumns) return emptyList()

        // An unreadable definition may reference any group, so fail closed: keep every group.
        val live = BudgetDatabase.livePredicate(categoryColumns, "c")
        val invalidDefinitions = sql.queryLong(
            "SELECT COUNT(*) FROM categories c WHERE $live AND c.cleanup_def IS NOT NULL AND json_valid(c.cleanup_def) = 0",
        ) ?: 0L
        if (invalidDefinitions != 0L) return emptyList()
        val referenced = sql.query(
            "SELECT DISTINCT json_extract(je.value, '\$.groupId') AS group_id " +
                "FROM categories c, json_each(CASE WHEN json_valid(c.cleanup_def) THEN c.cleanup_def ELSE '[]' END) je " +
                "WHERE $live AND c.cleanup_def IS NOT NULL AND json_extract(je.value, '\$.groupId') IS NOT NULL",
        ).mapNotNull { it.string("group_id") }.filter { it.isNotEmpty() }.toSet()
        return sql.query("SELECT id FROM cleanup_groups WHERE ${BudgetDatabase.livePredicate(groupColumns)}")
            .mapNotNull { it.string("id") }
            .filter { it !in referenced }
            .map { CellDraft("cleanup_groups", it, "tombstone", SyncValue.bool(true)) }
    }

    // endregion

    // region Preview

    private fun buildPreview(month: String, currentMonth: String, categoryIds: Set<String>?, mode: TemplateApplyMode): TemplatePreview {
        val prepared = plan(month, currentMonth, categoryIds, mode, skipAvailableClamp = false, skipStaleCheck = false)
        val monthValue = Months.actualMonthValue(month)
        val names = reads.categoryNames()
        val currentValues = prepared.history.categoryValues(monthValue, monthValue)
        val includedTrackingIds: Set<String> = if (prepared.isTracking) {
            val groups = reads.budgetReads.fetchBudgetMonth(Months.monthId(monthValue)).categoryGroups
            val firstIncomeGroupId = groups.firstOrNull { it.isIncome }?.id
            groups.filter { if (it.isIncome) it.id == firstIncomeGroupId else !it.hidden }
                .flatMap { group -> group.categories.filter { !it.hidden }.map { it.id } }
                .toSet()
        } else {
            emptySet()
        }
        val writesByCategory = prepared.compute.writes.associateBy { it.categoryId }
        val orderedIds = prepared.compute.writes.map { it.categoryId }.toMutableList()
        for (id in prepared.orphanGoalCategoryIds) if (id !in orderedIds) orderedIds.add(id)

        val categories = mutableListOf<TemplateCategoryPreview>()
        var assigned = 0L
        var released = 0L
        var hasNonMoneyUpdates = false
        for (categoryId in orderedIds) {
            val write = writesByCategory[categoryId]
            val current = prepared.currentBudgeted[categoryId] ?: 0L
            val proposed = write?.amount ?: current
            val evaluatedDemand = prepared.compute.evaluatedDemandByCategory[categoryId] ?: 0L
            val shortfall = prepared.compute.clampShortfallByCategory[categoryId] ?: 0L
            val goalBefore = prepared.currentGoals[categoryId]
            val goalAfter = write?.goal
            val goalChanged = goalBefore != goalAfter
            val entries = prepared.entriesByCategory[categoryId] ?: emptyList()
            val isOrphanGoal = categoryId in prepared.orphanGoalCategoryIds
            val isGoalOnlyUpdate = (entries.isGoalOnly && goalChanged) || isOrphanGoal
            if (current == proposed && shortfall <= 0L && !goalChanged) continue

            val delta = TemplateMath.subtract(proposed, current)
            if (delta >= 0) assigned = TemplateMath.add(assigned, delta) else released = TemplateMath.add(released, TemplateMath.subtract(0, delta))
            hasNonMoneyUpdates = hasNonMoneyUpdates || goalChanged

            val before = currentValues[categoryId] ?: CategoryValue()
            val afterBalance = BudgetCalculation.sum(listOf(before.balance, delta), prepared.table)
            val metric = if (prepared.isTracking) {
                if (prepared.categoryIsIncome[categoryId] == true) {
                    TemplateMetric(TemplateMetricKind.RECEIVED, before.spent, before.spent)
                } else {
                    TemplateMetric(TemplateMetricKind.BALANCE, before.balance, afterBalance)
                }
            } else {
                TemplateMetric(TemplateMetricKind.AVAILABLE, before.balance, afterBalance)
            }
            categories.add(
                TemplateCategoryPreview(
                    categoryId = categoryId,
                    name = names[categoryId] ?: categoryId,
                    current = current,
                    proposed = proposed,
                    perTemplate = prepared.compute.contributions[categoryId] ?: List(entries.size) { 0L },
                    evaluatedDemand = evaluatedDemand,
                    shortfall = shortfall,
                    isGoalOnlyUpdate = isGoalOnlyUpdate,
                    goalBefore = goalBefore,
                    goalAfter = goalAfter,
                    metric = metric,
                    priorityLevels = entries.mapNotNull { it.priority }.distinct().sorted(),
                ),
            )
        }

        var netFundingRequired = 0L
        for ((categoryId, entries) in prepared.entriesByCategory) {
            if (entries.isGoalOnly) continue
            val targetChange = TemplateMath.subtract(
                prepared.compute.evaluatedDemandByCategory[categoryId] ?: 0L,
                prepared.currentBudgeted[categoryId] ?: 0L,
            )
            // Tracking income adds to Total Saved; expenses use it.
            val cost = if (prepared.isTracking && prepared.categoryIsIncome[categoryId] == true) TemplateMath.subtract(0, targetChange) else targetChange
            netFundingRequired = TemplateMath.add(netFundingRequired, cost)
        }
        var availableAfter = prepared.compute.leftover
        if (prepared.isTracking) {
            // The engine's remaining availability is an allocation input, not tracking's displayed
            // Total Saved. Project only the visible categories Total Saved includes.
            availableAfter = prepared.initialAvailableBudget
            for (write in prepared.compute.writes) {
                if (write.categoryId !in includedTrackingIds) continue
                val delta = TemplateMath.subtract(write.amount, prepared.currentBudgeted[write.categoryId] ?: 0L)
                val effect = if (prepared.categoryIsIncome[write.categoryId] == true) delta else TemplateMath.subtract(0, delta)
                availableAfter = BudgetCalculation.sum(listOf(availableAfter, effect), BudgetTable.TRACKING)
            }
        }
        return TemplatePreview(
            month = month,
            mode = mode,
            categories = categories,
            assigned = assigned,
            released = released,
            leftover = prepared.compute.leftover,
            isTracking = prepared.isTracking,
            evaluatedDemand = prepared.compute.evaluatedDemand,
            fundingRequired = maxOf(0L, netFundingRequired),
            stillNeeded = prepared.compute.clampShortfall,
            availableBefore = prepared.initialAvailableBudget,
            availableAfter = availableAfter,
            hasNonMoneyUpdates = hasNonMoneyUpdates,
            hasEligibleTemplates = prepared.entriesByCategory.isNotEmpty(),
        )
    }

    // endregion
}
