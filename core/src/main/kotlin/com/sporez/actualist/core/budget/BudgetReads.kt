package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql

/**
 * Budget sheet reads straight from the imported Actual tables. Port of Actualist
 * `BudgetDatabase+BudgetReads`, `+CategoryValueTimeline`, `+EnvelopeCarryIn` and
 * `+EnvelopeHolds`.
 */
class BudgetReads(private val database: BudgetDatabase) {
    private val sql get() = database.sql

    fun budgetTable(): BudgetTable {
        if (!database.tableExists("preferences")) return BudgetTable.ENVELOPE
        val columns = database.columnSet("preferences")
        if ("id" !in columns || "value" !in columns) throw LocalFirstException.InvalidDownloadedBudget("preferences")
        val type = sql.queryString(
            "SELECT value FROM preferences WHERE id = 'budgetType' AND ${BudgetDatabase.livePredicate(columns)} LIMIT 1",
        )
        return if (type == "tracking") BudgetTable.TRACKING else BudgetTable.ENVELOPE
    }

    /** Months with budget rows or transactions, ascending. Envelope keeps deleted rows' months, as upstream. */
    fun availableMonths(): List<String> = sql.locked {
        val months = sortedSetOf<String>()
        val table = budgetTable().tableName
        if (database.tableExists(table) && "month" in database.columnSet(table)) {
            sql.query("SELECT DISTINCT ${normalizedMonthSql("month")} AS month FROM ${Sql.quoted(table)} WHERE month IS NOT NULL")
                .mapNotNullTo(months) { Months.canonical(it.string("month")) }
        }
        if (database.tableExists("transactions") && "date" in database.columnSet("transactions")) {
            val live = if (budgetTable() == BudgetTable.TRACKING) BudgetDatabase.livePredicate(database.columnSet("transactions")) else "1 = 1"
            sql.query("SELECT DISTINCT ${normalizedMonthSql("date")} AS month FROM transactions WHERE date IS NOT NULL AND $live")
                .mapNotNullTo(months) { Months.canonical(it.string("month")) }
        }
        months.toList()
    }

    fun fetchBudgetMonth(month: String): BudgetMonth = sql.locked {
        if (Months.canonical(month) != month) throw LocalFirstException.InvalidLocalWrite("invalid budget month")
        val table = budgetTable()
        val (current, previous) = categoryValuesWithPrevious(month)
        val noteIds = userNoteIds(month)
        val groups = fetchCategoryGroups(current, noteIds)
        val totals = BudgetCalculation.totals(groups, table)
        val tracking = table == BudgetTable.TRACKING
        val availability = if (tracking) 0L to 0L else envelopeAvailability(month, totals.balance)
        val carryIn = if (tracking) null else envelopeCarryIn(month, groups, previous)
        val fromLastMonth = carryIn?.first ?: 0L
        BudgetMonth(
            month = month,
            table = table,
            incomeAvailable = if (tracking) 0 else BudgetCalculation.checkedAdd(totals.income, fromLastMonth),
            lastMonthOverspent = carryIn?.second ?: 0,
            forNextMonth = availability.second,
            totalBudgeted = totals.budgeted,
            toBudget = availability.first,
            fromLastMonth = fromLastMonth,
            totalIncome = totals.income,
            totalSpent = totals.spent,
            totalBalance = totals.balance,
            categoryGroups = groups,
            trackingSummary = totals.tracking,
            hasNote = "budget-$month" in noteIds,
        )
    }

    /** Envelope `to-budget` and this month's hold: on-budget funds minus leftover minus the hold. */
    fun envelopeAvailability(month: String, totalBalance: Long): Pair<Long, Long> {
        val onBudget = onBudgetAccountBalance(month)
        val uncategorized = uncategorizedOnBudgetActivity(month)
        val hold = envelopeHold(month)
        return ((onBudget - uncategorized) - totalBalance - hold) to hold
    }

    fun envelopeToBudget(month: String): Long {
        val values = categoryValuesWithPrevious(month).first
        val groups = fetchCategoryGroups(values, emptySet())
        val totalBalance = groups.filter { !it.isIncome }.sumOf { it.balance }
        return envelopeAvailability(month, totalBalance).first
    }

    /**
     * Upstream `from-last-month` (previous `to-budget` plus previous `buffered-selected`)
     * and `last-month-overspent` (previous negative leftovers of expense categories
     * without carryover). Identity: `toBudget == totalIncome + fromLastMonth +
     * lastMonthOverspent - totalBudgeted - forNextMonth`.
     */
    private fun envelopeCarryIn(
        month: String,
        groups: List<BudgetMonthCategoryGroup>,
        previousValues: Map<String, CategoryValue>,
    ): Pair<Long, Long> {
        val previousMonth = Months.previous(month)
        val expenseIds = groups.filter { !it.isIncome }.flatMap { it.categories }.map { it.id }
        val previous = expenseIds.map { previousValues[it] ?: CategoryValue() }
        val previousAvailability = envelopeAvailability(previousMonth, BudgetCalculation.sum(previous.map { it.balance }))
        val overspent = BudgetCalculation.sum(previous.filter { !it.carryover }.map { minOf(0, it.balance) })
        return BudgetCalculation.checkedAdd(previousAvailability.first, previousAvailability.second) to overspent
    }

    /**
     * Amount held out of [month]'s To Budget: the manual hold in `zero_budget_months`, or
     * when there is none, the income received into categories flagged carryover that month.
     */
    fun envelopeHold(month: String): Long {
        val manual = manualEnvelopeHold(month)
        return if (manual != 0L) manual else inferredIncomeCarryoverHold(month)
    }

    fun holdReview(month: String): HoldReview {
        if (Months.canonical(month) != month) throw LocalFirstException.InvalidLocalWrite("invalid hold month")
        if (budgetTable() == BudgetTable.TRACKING) throw LocalFirstException.UnsupportedBudgetAction()
        val manual = manualEnvelopeHold(month)
        val automatic = inferredIncomeCarryoverHold(month)
        return HoldReview(
            month = month,
            toBudget = envelopeToBudget(month),
            heldAmount = if (manual == 0L) automatic else manual,
            manualHeldAmount = manual,
            automaticHeldAmount = automatic,
        )
    }

    fun manualEnvelopeHold(month: String): Long {
        if (!database.tableExists("zero_budget_months")) return 0
        val columns = database.columnSet("zero_budget_months")
        val buffered = if ("buffered" in columns) "buffered" else "0"
        for (row in sql.query("SELECT id AS raw_month, $buffered AS buffered FROM zero_budget_months")) {
            if (Months.canonical(row.string("raw_month")) == month) return row.longOr0("buffered")
        }
        return 0
    }

    private fun inferredIncomeCarryoverHold(month: String): Long {
        val budgets = categoryBudgets(month)
        val incomeIds = incomeCategoryIds()
        val spending = categorySpending(month)
        return budgets.entries.sumOf { (categoryId, budget) ->
            if (budget.second && categoryId in incomeIds) spending[categoryId] ?: 0L else 0L
        }
    }

    fun incomeCategoryIds(): Set<String> {
        if (!database.tableExists("categories")) return emptySet()
        val columns = database.columnSet("categories")
        val isIncome = if ("is_income" in columns) "is_income" else "0"
        return sql.query("SELECT id FROM categories WHERE ${BudgetDatabase.livePredicate(columns)} AND $isIncome = 1")
            .mapNotNull { it.string("id") }.toSet()
    }

    fun categoryIsIncomeById(): Map<String, Boolean> {
        if (!database.tableExists("categories")) return emptyMap()
        val columns = database.columnSet("categories")
        val isIncome = if ("is_income" in columns) "is_income" else "0"
        return sql.query("SELECT id, $isIncome AS is_income FROM categories WHERE ${BudgetDatabase.livePredicate(columns)}")
            .mapNotNull { row -> row.string("id")?.let { it to row.bool("is_income") } }.toMap()
    }

    /** Expense (or all) category ids in budget order: group order, then category order. */
    fun categoryIdsInBudgetOrder(includeIncome: Boolean, includeHidden: Boolean): List<String> {
        if (!database.tableExists("categories")) return emptyList()
        val columns = database.columnSet("categories")
        val predicates = mutableListOf(BudgetDatabase.livePredicate(columns, "c"))
        if (!includeIncome && "is_income" in columns) predicates += "(c.is_income = 0 OR c.is_income IS NULL)"
        if (!includeHidden && "hidden" in columns) predicates += "(c.hidden = 0 OR c.hidden IS NULL)"
        val join = if (includeHidden) {
            "LEFT JOIN category_groups g ON g.id = c.cat_group"
        } else {
            predicates += BudgetDatabase.livePredicate(database.columnSet("category_groups"), "g") + " AND (g.hidden = 0 OR g.hidden IS NULL)"
            "INNER JOIN category_groups g ON g.id = c.cat_group"
        }
        return sql.query(
            "SELECT c.id AS id FROM categories c $join WHERE ${predicates.joinToString(" AND ")} " +
                "ORDER BY g.sort_order, lower(g.name), c.sort_order, lower(c.name)",
        ).mapNotNull { it.string("id") }
    }

    // region Category values

    /** Values for [month] and for the month before it, computed by one forward recurrence. */
    fun categoryValuesWithPrevious(month: String): Pair<Map<String, CategoryValue>, Map<String, CategoryValue>> {
        if (Months.canonical(month) != month) throw LocalFirstException.InvalidLocalWrite("invalid budget month")
        val target = Months.monthInt(month)
        val timeline = categoryValueTimeline(categoryValueInputs(), target, target)
        return (timeline.snapshots[target] ?: emptyMap()) to timeline.previous
    }

    data class Inputs(
        val table: BudgetTable,
        val incomeByCategory: Map<String, Boolean>,
        val budgetedByMonth: Map<String, Map<String, Pair<Long, Boolean>>>,
        val spentByMonth: Map<String, Map<String, Long>>,
    )

    data class Timeline(
        val snapshots: Map<Int, Map<String, CategoryValue>>,
        val previous: Map<String, CategoryValue>,
    )

    fun categoryValueInputs(): Inputs {
        val table = budgetTable()
        return Inputs(
            table = table,
            incomeByCategory = if (table == BudgetTable.TRACKING) categoryIsIncomeById() else emptyMap(),
            budgetedByMonth = categoryBudgetsByMonth(),
            spentByMonth = categorySpendingByMonth(),
        )
    }

    /**
     * Runs the recurrence forward from the first month holding real data (a nonzero budget
     * or activity, or a carryover flag). Earlier months only contribute zero values.
     */
    fun categoryValueTimeline(inputs: Inputs, target: Int, keepFrom: Int): Timeline {
        val candidates = (inputs.budgetedByMonth.keys + inputs.spentByMonth.keys)
            .mapNotNull { key -> Months.canonical(key)?.let(Months::monthInt) }
            .filter { it <= target }
            .toSet()

        fun categoryIds(month: Int): Set<String> {
            val key = Months.monthId(month)
            return (inputs.budgetedByMonth[key]?.keys ?: emptySet()) + (inputs.spentByMonth[key]?.keys ?: emptySet())
        }

        fun hasRealData(month: Int): Boolean {
            val key = Months.monthId(month)
            return inputs.budgetedByMonth[key].orEmpty().values.any { it.first != 0L || it.second } ||
                inputs.spentByMonth[key].orEmpty().values.any { it != 0L }
        }

        val start = candidates.filter(::hasRealData).minOrNull() ?: target
        var values = HashMap<String, CategoryValue>()
        for (month in candidates) {
            if (month < start) for (id in categoryIds(month)) values[id] = CategoryValue()
        }
        var previous: Map<String, CategoryValue> = values
        val snapshots = HashMap<Int, Map<String, CategoryValue>>()
        var cursor = start
        while (cursor <= target) {
            val key = Months.monthId(cursor)
            val budgeted = inputs.budgetedByMonth[key].orEmpty()
            val spent = inputs.spentByMonth[key].orEmpty()
            val next = HashMap<String, CategoryValue>()
            for (id in budgeted.keys + spent.keys + values.keys) {
                val budget = budgeted[id] ?: (0L to false)
                next[id] = BudgetCalculation.category(
                    table = inputs.table,
                    isIncome = inputs.incomeByCategory[id] ?: false,
                    budgeted = budget.first,
                    activity = spent[id] ?: 0L,
                    carryover = budget.second,
                    previous = values[id] ?: CategoryValue(),
                )
            }
            previous = values
            values = next
            if (cursor >= keepFrom) snapshots[cursor] = next
            cursor = Months.next(cursor)
        }
        return Timeline(snapshots, previous)
    }

    fun categoryBudgets(month: String): Map<String, Pair<Long, Boolean>> = categoryBudgetsByMonth()[month].orEmpty()

    fun categoryBudgetsByMonth(): Map<String, Map<String, Pair<Long, Boolean>>> {
        val table = budgetTable().tableName
        if (!database.tableExists(table)) return emptyMap()
        val columns = database.columnSet(table)
        val amount = if ("amount" in columns) "amount" else "0"
        val carryover = if ("carryover" in columns) "carryover" else "0"
        val result = HashMap<String, HashMap<String, Pair<Long, Boolean>>>()
        for (row in sql.query(
            "SELECT ${normalizedMonthSql("month")} AS month, category AS category_id, $amount AS amount, $carryover AS carryover " +
                "FROM ${Sql.quoted(table)} WHERE month IS NOT NULL",
        )) {
            val month = row.string("month") ?: continue
            val category = row.string("category_id") ?: continue
            result.getOrPut(month) { HashMap() }[category] = row.longOr0("amount") to row.bool("carryover")
        }
        return result
    }

    fun categorySpending(month: String): Map<String, Long> = categorySpendingByMonth()[month].orEmpty()

    /** Live non-parent on-budget activity per month and category, through `category_mapping`. */
    fun categorySpendingByMonth(): Map<String, Map<String, Long>> {
        if (!database.tableExists("transactions") || !database.tableExists("accounts")) return emptyMap()
        val split = TransactionSplitSql(database.columnSet("transactions"))
        val mappingColumn = categoryMappingTransferColumn()
        val mappingJoin = mappingColumn?.let { "LEFT JOIN category_mapping cm ON cm.id = ${split.category}" } ?: ""
        val mapped = mappingColumn?.let { "COALESCE(cm.${Sql.quoted(it)}, ${split.category})" } ?: split.category
        val month = normalizedMonthSql(split.date)
        val result = HashMap<String, HashMap<String, Long>>()
        for (row in sql.query(
            """
            SELECT $month AS month, $mapped AS category_id, SUM(${split.amount}) AS amount
            FROM transactions t
            $mappingJoin
            LEFT JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()}
              AND ${split.category} IS NOT NULL
              AND a.offbudget = 0
            GROUP BY $month, $mapped
            """.trimIndent(),
        )) {
            val m = row.string("month") ?: continue
            val category = row.string("category_id") ?: continue
            result.getOrPut(m) { HashMap() }[category] = row.longOr0("amount")
        }
        return result
    }

    private fun categoryMappingTransferColumn(): String? {
        if (!database.tableExists("category_mapping")) return null
        val columns = database.columnSet("category_mapping")
        return listOf("transferId", "transfer_id").firstOrNull { it in columns }
            ?: throw LocalFirstException.SchemaUnavailable("missing column category_mapping.transferId")
    }

    fun onBudgetAccountBalance(throughMonth: String): Long {
        if (!database.tableExists("transactions") || !database.tableExists("accounts")) return 0
        val split = TransactionSplitSql(database.columnSet("transactions"))
        return sql.queryLong(
            """
            SELECT SUM(${split.amount}) FROM transactions t
            LEFT JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()} AND a.offbudget = 0 AND ${normalizedMonthSql(split.date)} <= ?
            """.trimIndent(),
            throughMonth,
        ) ?: 0
    }

    fun uncategorizedOnBudgetActivity(throughMonth: String): Long {
        if (!database.tableExists("transactions") || !database.tableExists("accounts")) return 0
        val split = TransactionSplitSql(database.columnSet("transactions"))
        return sql.queryLong(
            """
            SELECT SUM(${split.amount}) FROM transactions t
            LEFT JOIN accounts a ON a.id = ${split.account}
            ${split.parentJoin()}
            WHERE ${split.liveInlinePredicate()}
              AND (${split.category} IS NULL OR ${split.category} = '')
              AND a.offbudget = 0 AND ${normalizedMonthSql(split.date)} <= ?
            """.trimIndent(),
            throughMonth,
        ) ?: 0
    }

    // endregion

    // region Groups

    fun fetchCategoryGroups(values: Map<String, CategoryValue>, noteIds: Set<String>): List<BudgetMonthCategoryGroup> {
        if (!database.tableExists("category_groups")) return emptyList()
        val table = budgetTable()
        val groupColumns = database.columnSet("category_groups")
        val groupHidden = if ("hidden" in groupColumns) "hidden" else "0"
        val groupIncome = if ("is_income" in groupColumns) "is_income" else "0"
        val groupOrder = if ("sort_order" in groupColumns) "sort_order, lower(name)" else "lower(name)"
        val categoriesByGroup = fetchCategoriesByGroup(values, noteIds)
        return sql.query(
            "SELECT id, name, $groupIncome AS is_income, $groupHidden AS hidden FROM category_groups " +
                "WHERE ${BudgetDatabase.livePredicate(groupColumns)} ORDER BY $groupOrder",
        ).map { row ->
            val id = row.string("id") ?: ""
            val categories = categoriesByGroup[id].orEmpty()
            val included = BudgetCalculation.includedCategories(categories, table)
            BudgetMonthCategoryGroup(
                id = id,
                name = row.string("name") ?: "",
                isIncome = row.bool("is_income"),
                hidden = row.bool("hidden"),
                budgeted = BudgetCalculation.sum(included.map { it.budgeted }, table),
                spent = BudgetCalculation.sum(included.map { it.spent }, table),
                balance = BudgetCalculation.sum(included.map { it.balance }, table),
                categories = categories,
            )
        }
    }

    private fun fetchCategoriesByGroup(values: Map<String, CategoryValue>, noteIds: Set<String>): Map<String, List<BudgetMonthCategory>> {
        if (!database.tableExists("categories")) return emptyMap()
        val columns = database.columnSet("categories")
        val group = when {
            "cat_group" in columns -> "cat_group"
            "group_id" in columns -> "group_id"
            else -> "NULL"
        }
        val hidden = if ("hidden" in columns) "hidden" else "0"
        val income = if ("is_income" in columns) "is_income" else "0"
        val order = if ("sort_order" in columns) "sort_order, lower(name)" else "lower(name)"
        val result = LinkedHashMap<String, MutableList<BudgetMonthCategory>>()
        for (row in sql.query(
            "SELECT id, name, $income AS is_income, $hidden AS hidden, $group AS group_id FROM categories " +
                "WHERE ${BudgetDatabase.livePredicate(columns)} ORDER BY $order",
        )) {
            val groupId = row.string("group_id") ?: continue
            val id = row.string("id") ?: ""
            val value = values[id] ?: CategoryValue()
            result.getOrPut(groupId) { mutableListOf() } += BudgetMonthCategory(
                id = id,
                name = row.string("name") ?: "",
                isIncome = row.bool("is_income"),
                hidden = row.bool("hidden"),
                groupId = groupId,
                budgeted = value.budgeted,
                spent = value.spent,
                balance = value.balance,
                carryover = value.carryover,
                hasNote = id in noteIds,
            )
        }
        return result
    }

    private fun userNoteIds(month: String): Set<String> {
        if (!database.tableExists("notes")) return emptySet()
        return sql.query(
            "SELECT id, note FROM notes WHERE id = ? OR id IN (SELECT id FROM categories) OR id IN (SELECT id FROM category_groups)",
            "budget-$month",
        ).filter { !it.string("note").isNullOrBlank() }.mapNotNull { it.string("id") }.toSet()
    }

    // endregion
}
