package com.sporez.actualist.core.templates

import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.CategoryValue
import com.sporez.actualist.core.budget.normalizedMonthSql
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.rules.RuleAction
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.schedules.lastOccurrence
import com.sporez.actualist.core.templates.TemplateEngine.Category
import com.sporez.actualist.core.templates.TemplateEngine.MonthSources
import com.sporez.actualist.core.templates.TemplateEngine.ResolvedSchedule

/**
 * One template plan's shared read of budget and spending history: the budget and spending tables
 * are read once and the month recurrence runs once forward. Port of Actualist `TemplateHistory`.
 * Create it for one plan and drop it with the plan.
 */
class TemplateHistory(private val reads: BudgetReads) {
    private var cachedInputs: BudgetReads.Inputs? = null
    private var firstBudgetMonthByCategory: Map<String, String>? = null
    private var keptRange: IntRange? = null
    private var snapshots: Map<Int, Map<String, CategoryValue>> = emptyMap()

    fun inputs(): BudgetReads.Inputs = cachedInputs ?: reads.categoryValueInputs().also { cachedInputs = it }

    /**
     * Values for [month] (Actual's `leftover` family). The first request runs the recurrence forward
     * through [month], keeping snapshots from [keepFrom]; later requests inside that range are lookups.
     */
    fun categoryValues(month: Int, keepFrom: Int): Map<String, CategoryValue> {
        val inputs = inputs()
        val range = keptRange
        if (range != null && month in range) {
            snapshots[month]?.let { return it }
            // Precedes the first month with real data: a one-month recurrence.
            return reads.categoryValueTimeline(inputs, month, month).snapshots[month] ?: emptyMap()
        }
        val upper = maxOf(month, range?.last ?: month)
        val lower = minOf(keepFrom, month, range?.first ?: month)
        val timeline = reads.categoryValueTimeline(inputs, upper, lower)
        snapshots = timeline.snapshots
        keptRange = lower..upper
        timeline.snapshots[month]?.let { return it }
        // [month] precedes the first month with real data.
        return reads.categoryValueTimeline(inputs, month, month).snapshots[month] ?: emptyMap()
    }

    /** Earliest stored budget month for a category (`MIN(month)` over its rows). */
    fun firstBudgetMonth(categoryId: String): Int? {
        var earliest = firstBudgetMonthByCategory
        if (earliest == null) {
            val map = HashMap<String, String>()
            for ((month, byCategory) in inputs().budgetedByMonth) {
                for (id in byCategory.keys) {
                    val current = map[id]
                    if (current != null && current <= month) continue
                    map[id] = month
                }
            }
            firstBudgetMonthByCategory = map
            earliest = map
        }
        val text = earliest[categoryId] ?: return null
        return try {
            TemplateCalendar.parseMonth(text)
        } catch (error: UnsupportedTemplateException) {
            null
        }
    }
}

/** The database reads behind the template plan. Port of Actualist `BudgetDatabase+Template*` reads. */
class TemplateReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql
    val budgetReads = BudgetReads(database)

    fun currency(): TemplateCurrency = TemplateCurrency.catalog(
        code = preferenceValue("defaultCurrencyCode") ?: "",
        hideFraction = preferenceValue("hideFraction") == "true",
    )

    private fun preferenceValue(id: String): String? {
        if (!database.tableExists("preferences")) return null
        val columns = database.columnSet("preferences")
        if ("id" !in columns || "value" !in columns) return null
        val tombstone = if ("tombstone" in columns) "AND (tombstone = 0 OR tombstone IS NULL)" else ""
        return sql.queryString("SELECT value FROM preferences WHERE id = ? $tombstone LIMIT 1", id)
    }

    /** `goal_def` JSON per live category, skipping blank values. */
    fun goalDefsRaw(): Map<String, String> {
        if (!database.tableExists("categories")) return emptyMap()
        val columns = database.columnSet("categories")
        if ("goal_def" !in columns) return emptyMap()
        val result = HashMap<String, String>()
        for (row in sql.query("SELECT id, goal_def FROM categories WHERE goal_def IS NOT NULL AND ${BudgetDatabase.livePredicate(columns)}")) {
            val id = row.string("id") ?: continue
            val json = row.string("goal_def") ?: continue
            if (json.trim().isEmpty()) continue
            result[id] = json
        }
        return result
    }

    fun categoryNames(): Map<String, String> {
        if (!database.tableExists("categories")) return emptyMap()
        val columns = database.columnSet("categories")
        val name = if ("name" in columns) "name" else "id"
        val result = HashMap<String, String>()
        for (row in sql.query("SELECT id, $name AS name FROM categories WHERE ${BudgetDatabase.livePredicate(columns)}")) {
            val id = row.string("id") ?: continue
            val label = row.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id
            result[id] = label
        }
        return result
    }

    fun categoryIdsInCategoryOrder(): List<String> {
        if (!database.tableExists("categories")) return emptyList()
        val columns = database.columnSet("categories")
        val order = mutableListOf<String>()
        if ("sort_order" in columns) order.add("c.sort_order")
        order.add("c.id")
        return sql.query(
            "SELECT c.id AS id FROM categories c WHERE ${BudgetDatabase.livePredicate(columns, "c")} ORDER BY ${order.joinToString(", ")}",
        ).mapNotNull { it.string("id") }
    }

    /** Live category ids in the order Actualist's template scope walks them: group income flag, group order, category order. */
    fun categoryIdsInBudgetOrder(includeIncome: Boolean, includeHidden: Boolean): List<String> {
        if (!database.tableExists("categories")) return emptyList()
        val categoryColumns = database.columnSet("categories")
        val groupColumn = when {
            "cat_group" in categoryColumns -> "cat_group"
            "group_id" in categoryColumns -> "group_id"
            else -> null
        }
        val predicates = mutableListOf(BudgetDatabase.livePredicate(categoryColumns, "c"))
        if (!includeIncome && "is_income" in categoryColumns) predicates.add("(c.is_income = 0 OR c.is_income IS NULL)")
        if (!includeHidden && "hidden" in categoryColumns) predicates.add("(c.hidden = 0 OR c.hidden IS NULL)")

        val groupsExist = database.tableExists("category_groups")
        val groupColumns = if (groupsExist) database.columnSet("category_groups") else emptySet()
        var join = ""
        val order = mutableListOf<String>()
        if (groupsExist && groupColumn != null) {
            if (includeHidden) {
                join = "LEFT JOIN category_groups g ON g.id = c.$groupColumn"
            } else {
                join = "INNER JOIN category_groups g ON g.id = c.$groupColumn"
                val visible = mutableListOf(BudgetDatabase.livePredicate(groupColumns, "g"))
                if ("hidden" in groupColumns) visible.add("(g.hidden = 0 OR g.hidden IS NULL)")
                predicates.add(visible.joinToString(" AND "))
            }
            if ("is_income" in groupColumns) order.add("g.is_income")
            if ("sort_order" in groupColumns) order.add("g.sort_order")
            order.add("g.id")
        } else if (!includeHidden) {
            return emptyList()
        }
        if ("sort_order" in categoryColumns) order.add("c.sort_order")
        order.add("c.id")
        return sql.query(
            "SELECT c.id AS id FROM categories c $join WHERE ${predicates.joinToString(" AND ")} ORDER BY ${order.joinToString(", ")}",
        ).mapNotNull { it.string("id") }
    }

    /** Income categories, without schedule or percentage month data. */
    fun incomeCatalog(): MonthSources {
        if (!database.tableExists("categories")) return MonthSources()
        val columns = database.columnSet("categories")
        val isIncome = if ("is_income" in columns) "is_income" else "0"
        val name = if ("name" in columns) "name" else "id"
        val hidden = if ("hidden" in columns) "hidden" else "0"
        val order = mutableListOf<String>()
        if ("sort_order" in columns) order.add("sort_order")
        order.add("id")
        val rows = sql.query(
            "SELECT id, $name AS name, $hidden AS hidden FROM categories " +
                "WHERE ${BudgetDatabase.livePredicate(columns)} AND ($isIncome = 1) ORDER BY ${order.joinToString(", ")}",
        )
        val ids = LinkedHashSet<String>()
        val idByName = HashMap<String, String>()
        val inOrder = mutableListOf<String>()
        val names = HashMap<String, String>()
        val hiddenIds = HashSet<String>()
        for (row in rows) {
            val id = row.string("id") ?: continue
            ids.add(id)
            val label = row.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: id
            inOrder.add(id)
            names[id] = label
            if (row.bool("hidden")) hiddenIds.add(id)
            val lowered = label.lowercase()
            if (lowered !in idByName) idByName[lowered] = id
        }
        return MonthSources(
            incomeCategoryIds = ids,
            incomeCategoryIdByLocalizedName = idByName,
            incomeCategoryIdsInOrder = inOrder,
            incomeCategoryNamesById = names,
            hiddenIncomeCategoryIds = hiddenIds,
        )
    }

    /** The incomeCatalog with the live schedules' ids and trimmed names filled in. */
    fun catalogWithSchedules(): MonthSources {
        val (ids, names) = activeSchedules()
        return incomeCatalog().copy(activeScheduleIds = ids, activeScheduleNames = names)
    }

    fun activeSchedules(): Pair<Set<String>, Set<String>> {
        if (!database.tableExists("schedules")) return emptySet<String>() to emptySet<String>()
        val columns = database.columnSet("schedules")
        if ("name" !in columns) return emptySet<String>() to emptySet<String>()
        val idSelection = if ("id" in columns) "id" else "NULL"
        val ids = HashSet<String>()
        val names = HashSet<String>()
        for (row in sql.query("SELECT $idSelection AS id, name FROM schedules WHERE name IS NOT NULL AND ${BudgetDatabase.livePredicate(columns)}")) {
            row.string("id")?.takeIf { it.isNotEmpty() }?.let { ids.add(it) }
            row.string("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
        }
        return ids to names
    }

    /** Stored `goal` per category for [month] (`YYYY-MM`). */
    fun categoryGoals(month: String): Map<String, Long> {
        val table = budgetReads.budgetTable().tableName
        if (!database.tableExists(table)) return emptyMap()
        val columns = database.columnSet(table)
        if ("goal" !in columns) return emptyMap()
        val category = if ("category" in columns) "category" else "NULL"
        val result = HashMap<String, Long>()
        for (row in sql.query(
            "SELECT $category AS category_id, goal FROM ${Sql.quoted(table)} WHERE ${normalizedMonthSql("month")} = ? AND goal IS NOT NULL",
            month,
        )) {
            val id = row.string("category_id") ?: continue
            result[id] = row.longOr0("goal")
        }
        return result
    }

    /** Category notes from the generic `notes` table, keyed by category id. */
    fun categoryNotes(): Map<String, String> {
        if (!database.tableExists("notes")) return emptyMap()
        val columns = database.columnSet("notes")
        if ("note" !in columns) return emptyMap()
        val idSelection = if ("id" in columns) "id" else "NULL"
        val result = HashMap<String, String>()
        for (row in sql.query("SELECT $idSelection AS id, note FROM notes WHERE note IS NOT NULL")) {
            val id = row.string("id") ?: continue
            val note = row.string("note") ?: continue
            result[id] = note
        }
        return result
    }

    /** `template_settings.source` per category, when that column exists. */
    fun categoryTemplateSources(): Map<String, String> {
        if (!database.tableExists("categories")) return emptyMap()
        val columns = database.columnSet("categories")
        if ("template_settings" !in columns) return emptyMap()
        val result = HashMap<String, String>()
        for (row in sql.query("SELECT id, template_settings FROM categories WHERE template_settings IS NOT NULL")) {
            val id = row.string("id") ?: continue
            val source = TemplateNoteGuard.templateSource(row.string("template_settings")) ?: continue
            result[id] = source
        }
        return result
    }

    // region Engine inputs

    class EngineInputs(val categories: Map<String, Category>, val monthSources: MonthSources)

    fun engineInputs(
        categoryTemplates: Map<String, List<TemplateEntry>>,
        monthValue: Int,
        categoryIsIncome: Map<String, Boolean>,
        previouslyBudgeted: Map<String, Long>,
        isTracking: Boolean,
        history: TemplateHistory,
        currency: TemplateCurrency,
    ): EngineInputs {
        val spentByMonth: Map<String, Map<String, Long>> =
            if (needsSpendingHistory(categoryTemplates)) history.inputs().spentByMonth else emptyMap()
        primeLeftoverHistory(categoryTemplates, monthValue, history)

        var monthSources = catalogWithSchedules()
        if (categoryTemplates.values.any { entries -> entries.any { it.type == "percentage" } }) {
            monthSources = percentageMonthSources(monthSources, monthValue, spentByMonth)
        }

        val categories = HashMap<String, Category>()
        for ((categoryId, entries) in categoryTemplates) {
            categories[categoryId] = engineCategory(
                categoryId,
                entries,
                monthValue,
                categoryIsIncome[categoryId] ?: false,
                previouslyBudgeted[categoryId] ?: 0L,
                isTracking,
                spentByMonth,
                history,
                currency,
            )
        }
        return EngineInputs(categories, monthSources)
    }

    private fun engineCategory(
        categoryId: String,
        entries: List<TemplateEntry>,
        monthValue: Int,
        isIncome: Boolean,
        previouslyBudgeted: Long,
        isTracking: Boolean,
        spentByMonth: Map<String, Map<String, Long>>,
        history: TemplateHistory,
        currency: TemplateCurrency,
    ): Category {
        val lookBacks = entries.mapNotNull { if (it.type == "copy") it.lookBack else null }.toSet()
        val copiedBudgetedByLookBack = HashMap<Int, Long>()
        for (lookBack in lookBacks) {
            val sourceMonthValue = TemplateCalendar.shiftedMonth(monthValue, -lookBack)
            copiedBudgetedByLookBack[lookBack] =
                history.inputs().budgetedByMonth[TemplateCalendar.monthId(sourceMonthValue)]?.get(categoryId)?.first ?: 0L
        }

        val fromLastMonth = if (needsPreviousBalance(entries)) {
            carryIn(categoryId, monthValue, isIncome, isTracking, history)
        } else {
            0L
        }

        val spend = spendHistory(categoryId, entries, monthValue, spentByMonth, history)
        return Category(
            entries = entries,
            fromLastMonth = fromLastMonth,
            copiedBudgetedByLookBack = copiedBudgetedByLookBack,
            isIncome = isIncome,
            activityByMonth = averageActivityByMonth(categoryId, entries, spentByMonth),
            firstRelevantMonth = averageFirstRelevantMonth(categoryId, entries, spentByMonth, history),
            budgetedByMonth = spend.budgeted,
            leftoverByMonth = spend.leftover,
            spentByMonth = spend.spent,
            resolvedSchedules = resolvedSchedules(entries, monthValue, isIncome, currency),
            previouslyBudgeted = previouslyBudgeted,
        )
    }

    /** Actual: `leftover < 0 && !carryover || is_income || tracking && !carryover` carries nothing in. */
    private fun carryIn(categoryId: String, monthValue: Int, isIncome: Boolean, isTracking: Boolean, history: TemplateHistory): Long {
        val previousMonthValue = TemplateCalendar.shiftedMonth(monthValue, -1)
        val previous = history.categoryValues(previousMonthValue, previousMonthValue)[categoryId] ?: CategoryValue()
        if (isIncome) return 0L
        if (isTracking && !previous.carryover) return 0L
        if (previous.balance < 0 && !previous.carryover) return 0L
        return previous.balance
    }

    private fun percentageMonthSources(
        catalog: MonthSources,
        monthValue: Int,
        spentByMonth: Map<String, Map<String, Long>>,
    ): MonthSources {
        val totalIncomeByLookBack = HashMap<Int, Long>()
        val incomeActivityByCategoryId = HashMap<String, MutableMap<Int, Long>>()
        for (lookBack in listOf(0, 1)) {
            val month = TemplateCalendar.monthId(TemplateCalendar.shiftedMonth(monthValue, -lookBack))
            var total = 0L
            for (categoryId in catalog.incomeCategoryIds) {
                val activity = spentByMonth[month]?.get(categoryId) ?: 0L
                incomeActivityByCategoryId.getOrPut(categoryId) { HashMap() }[lookBack] = activity
                total = TemplateMath.add(total, activity)
            }
            totalIncomeByLookBack[lookBack] = total
        }
        return catalog.copy(
            totalIncomeByLookBack = totalIncomeByLookBack,
            incomeActivityByCategoryId = incomeActivityByCategoryId,
        )
    }

    private fun averageActivityByMonth(
        categoryId: String,
        entries: List<TemplateEntry>,
        spentByMonth: Map<String, Map<String, Long>>,
    ): Map<Int, Long> {
        if (entries.none { it.type == "average" }) return emptyMap()
        val result = HashMap<Int, Long>()
        for ((monthId, byCategory) in spentByMonth) {
            val amount = byCategory[categoryId] ?: continue
            val month = tryParseMonth(monthId) ?: continue
            result[month] = amount
        }
        return result
    }

    private fun averageFirstRelevantMonth(
        categoryId: String,
        entries: List<TemplateEntry>,
        spentByMonth: Map<String, Map<String, Long>>,
        history: TemplateHistory,
    ): Int? {
        if (entries.none { it.type == "average" }) return null
        var earliest: Int? = history.firstBudgetMonth(categoryId)
        for ((monthId, byCategory) in spentByMonth) {
            if (!byCategory.containsKey(categoryId)) continue
            val month = tryParseMonth(monthId) ?: continue
            val current = earliest
            earliest = if (current == null) month else minOf(current, month)
        }
        return earliest
    }

    /** Runs the month recurrence once, through the month before [monthValue], keeping every month a Spend template needs. */
    private fun primeLeftoverHistory(
        categoryTemplates: Map<String, List<TemplateEntry>>,
        monthValue: Int,
        history: TemplateHistory,
    ) {
        if (categoryTemplates.values.none { needsPreviousBalance(it) }) return
        val previousMonthValue = TemplateCalendar.shiftedMonth(monthValue, -1)
        val earliestSpendMonth = categoryTemplates.values.flatten()
            .mapNotNull { entry -> if (entry.type == "spend") entry.fromMonth?.let { tryParseMonth(it) } else null }
            .minOrNull()
        history.categoryValues(previousMonthValue, minOf(earliestSpendMonth ?: previousMonthValue, previousMonthValue))
    }

    private fun needsSpendingHistory(categoryTemplates: Map<String, List<TemplateEntry>>): Boolean =
        categoryTemplates.values.any { entries -> entries.any { it.type == "average" || it.type == "percentage" || it.type == "spend" } }

    private class SpendMonths(val budgeted: Map<Int, Long>, val leftover: Map<Int, Long>, val spent: Map<Int, Long>)

    private fun spendHistory(
        categoryId: String,
        entries: List<TemplateEntry>,
        monthValue: Int,
        spentByMonth: Map<String, Map<String, Long>>,
        history: TemplateHistory,
    ): SpendMonths {
        val fromMonths = entries.mapNotNull { entry -> if (entry.type == "spend") entry.fromMonth?.let { tryParseMonth(it) } else null }
        if (fromMonths.isEmpty()) return SpendMonths(emptyMap(), emptyMap(), emptyMap())

        val budgeted = HashMap<Int, Long>()
        val leftover = HashMap<Int, Long>()
        val spent = HashMap<Int, Long>()
        val earliest = fromMonths.minOrNull() ?: monthValue
        val budgetedHistory = history.inputs().budgetedByMonth
        var cursor = earliest
        while (cursor < monthValue) {
            val month = TemplateCalendar.monthId(cursor)
            budgeted[cursor] = budgetedHistory[month]?.get(categoryId)?.first ?: 0L
            spent[cursor] = spentByMonth[month]?.get(categoryId) ?: 0L
            leftover[cursor] = history.categoryValues(cursor, earliest)[categoryId]?.balance ?: 0L
            cursor = TemplateCalendar.shiftedMonth(cursor, 1)
        }
        return SpendMonths(budgeted, leftover, spent)
    }

    private fun tryParseMonth(text: String): Int? = try {
        TemplateCalendar.parseMonth(text)
    } catch (error: UnsupportedTemplateException) {
        null
    }

    // endregion

    // region Schedules

    private fun resolvedSchedules(
        entries: List<TemplateEntry>,
        monthValue: Int,
        isIncome: Boolean,
        currency: TemplateCurrency,
    ): Map<String, ResolvedSchedule> {
        val resolved = HashMap<String, ResolvedSchedule>()
        for (entry in entries) {
            if (entry.type != "schedule") continue
            val key = entry.scheduleLookupKey ?: continue
            if (key in resolved) continue
            resolved[key] = resolveSchedule(entry, monthValue, isIncome, currency)
        }
        return resolved
    }

    private fun resolveSchedule(entry: TemplateEntry, monthValue: Int, isIncome: Boolean, currency: TemplateCurrency): ResolvedSchedule {
        val missing = entry.missingScheduleReason
        if (!database.tableExists("schedules") || !database.tableExists("rules")) throw UnsupportedTemplateException(missing)
        val scheduleColumns = database.columnSet("schedules")
        if ("name" !in scheduleColumns) throw UnsupportedTemplateException(missing)
        val completedColumn = if ("completed" in scheduleColumns) "completed" else "0"
        val ruleColumn = if ("rule" in scheduleColumns) "rule" else "NULL"
        val identityPredicate: String
        val identityArgument: String
        val scheduleId = entry.presentScheduleId
        if (scheduleId != null) {
            if ("id" !in scheduleColumns) throw UnsupportedTemplateException(missing)
            identityPredicate = "id = ?"
            identityArgument = scheduleId
        } else {
            identityPredicate = "TRIM(name) = ?"
            identityArgument = entry.trimmedScheduleName ?: ""
        }
        val row = sql.queryOne(
            "SELECT name AS name, $ruleColumn AS rule, $completedColumn AS completed FROM schedules " +
                "WHERE $identityPredicate AND ${BudgetDatabase.livePredicate(scheduleColumns)} LIMIT 1",
            identityArgument,
        ) ?: throw UnsupportedTemplateException(missing)
        val completed = row.bool("completed")
        val ruleId = row.string("rule")?.takeIf { it.isNotEmpty() } ?: throw UnsupportedTemplateException("schedule")

        val ruleColumns = database.columnSet("rules")
        if ("conditions" !in ruleColumns || "actions" !in ruleColumns) throw UnsupportedTemplateException("schedule")
        val ruleRow = sql.queryOne(
            "SELECT conditions, actions FROM rules WHERE id = ? AND ${BudgetDatabase.livePredicate(ruleColumns)} LIMIT 1",
            ruleId,
        )
        val conditionsJson = ruleRow?.string("conditions")
        val actionsJson = ruleRow?.string("actions")
        val conditions: List<RuleCondition>? = conditionsJson?.let { RuleJson.decodeConditions(it) }
        val actions: List<RuleAction>? = actionsJson?.let { RuleJson.decodeActions(it) }
        if (conditions == null || actions == null) throw UnsupportedTemplateException("schedule")
        if (actions.any { scheduleActionIsUnsupported(it) }) {
            throw UnsupportedTemplateException("schedule formula and split actions are not supported locally yet")
        }

        val amountCondition = conditions.firstOrNull { it.op in listOf("is", "isapprox", "isbetween") && it.fieldName == "amount" }
        val dateCondition = conditions.firstOrNull { it.op in listOf("is", "isapprox") && it.fieldName == "date" }
        if (amountCondition == null || dateCondition == null) throw UnsupportedTemplateException("schedule")

        val engine = TemplateEngine(currency)
        var scheduleAmount = scheduleRuleAmount(amountCondition)
        val adjustment = entry.adjustment
        val adjustmentType = entry.adjustmentType
        if (adjustment != null && adjustmentType != null) {
            when (adjustmentType) {
                "percent" -> scheduleAmount = (1 + adjustment / 100) * scheduleAmount
                "fixed" -> {
                    val delta = engine.amountToMinorUnits(adjustment).toDouble()
                    scheduleAmount += (if (scheduleAmount < 0) -1 else 1) * delta
                }
                else -> Unit
            }
        }
        val roundedAmount = TemplateMath.actualRound(scheduleAmount)
        val target = (if (isIncome) 1L else -1L) * roundedAmount
        val recurrence = scheduleRecurrence(dateCondition)
        val monthStart = TemplateCalendar.monthStartDate(monthValue)
        val nextDateString: String
        val isRepeating: Boolean
        val frequency: String?
        val interval: Long
        val dateValue = dateCondition.value
        if (recurrence != null) {
            // Upstream `getNextDate` falls back to the last occurrence once an ending recurrence has
            // no occurrence left, which puts it in the past.
            nextDateString = recurrence.nextOccurrence(TemplateCalendar.dayId(monthStart), true)
                ?: recurrence.lastOccurrence(true)
                ?: throw UnsupportedTemplateException("schedule")
            isRepeating = true
            frequency = recurrence.frequency.raw
            interval = recurrence.interval
        } else if (dateValue is RuleValue.Text && TemplateCalendar.validatedDate(dateValue.value) != null) {
            nextDateString = dateValue.value
            isRepeating = false
            frequency = null
            interval = 1L
        } else {
            throw UnsupportedTemplateException("schedule")
        }

        val nextMonthValue = TemplateCalendar.parseMonth(nextDateString.take(7))
        val monthsUntil = TemplateCalendar.monthDistance(monthValue, nextMonthValue)
        val resolvedName = row.string("name") ?: entry.name ?: entry.presentScheduleId ?: ""
        return ResolvedSchedule(
            name = resolvedName,
            amount = roundedAmount,
            nextDate = nextDateString,
            monthsUntil = monthsUntil,
            interval = interval,
            frequency = frequency,
            completed = completed,
            full = entry.full ?: false,
            isRepeating = isRepeating,
            recurrence = recurrence,
            monthlyRepeatingTarget = target,
        )
    }

    private fun scheduleActionIsUnsupported(action: RuleAction): Boolean {
        if (action.options?.get("formula") != null) return true
        if (action.op == "set" && action.fieldName == "amount") return true
        if (action.op == "split" || action.op == "set-split-amount") return true
        return false
    }

    private fun scheduleRuleAmount(condition: RuleCondition): Double {
        if (condition.op == "isbetween") {
            val range = (condition.value as? RuleValue.Obj)?.values ?: throw UnsupportedTemplateException("schedule")
            val num1 = range["num1"]?.number
            val num2 = range["num2"]?.number
            if (num1 == null || num2 == null) throw UnsupportedTemplateException("schedule")
            return TemplateMath.actualRound(num1 + num2).toDouble() / 2
        }
        return condition.value.number ?: throw UnsupportedTemplateException("schedule")
    }

    private fun scheduleRecurrence(condition: RuleCondition): ScheduleRecurrence? {
        return when (val value = condition.value) {
            is RuleValue.Text -> null
            is RuleValue.Obj -> {
                if (value.values["patterns"] != null) {
                    throw UnsupportedTemplateException("schedule date patterns are not supported locally yet")
                }
                try {
                    ScheduleRecurrence.fromRule(value)
                } catch (error: Exception) {
                    throw UnsupportedTemplateException("schedule")
                }
            }
            else -> throw UnsupportedTemplateException("schedule")
        }
    }

    // endregion

    companion object {
        fun needsPreviousBalance(entries: List<TemplateEntry>): Boolean = entries.any { entry ->
            entry.type == "by" || entry.type == "refill" || entry.type == "spend" || entry.type == "schedule" ||
                TemplateEngine.hasEffectiveLimit(entry)
        }
    }
}
