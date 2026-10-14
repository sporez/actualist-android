package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.sync.SyncMessage
import com.sporez.actualist.core.sync.SyncValue

/**
 * Budget writes as Actual CRDT cells. Port of Actualist `BudgetDatabase+BudgetWrites` and
 * `+HoldWrites`; each `*Drafts` function must run inside the commit transaction so it
 * plans from the latest committed rows.
 */
class BudgetWrites(private val database: BudgetDatabase) {
    private val reads = BudgetReads(database)
    private val sql get() = database.sql

    // region Commit entry points

    fun assign(month: String, categoryId: String, budgeted: Long): List<SyncMessage> =
        database.commitLocal { assignDrafts(month, categoryId, budgeted) to Unit }.messages

    fun move(month: String, commands: List<MoveMoneyCommand>): List<SyncMessage> =
        database.commitLocal { moveDrafts(resolvedMoveCommands(commands, month), month) to Unit }.messages

    fun setCarryover(categoryId: String, carryover: Boolean, startMonth: String, throughMonth: String): List<SyncMessage> =
        database.commitLocal { carryoverDrafts(listOf(categoryId), carryover, startMonth, throughMonth, validate = true) to Unit }.messages

    fun setAllExpenseCarryover(carryover: Boolean, startMonth: String, throughMonth: String): List<SyncMessage> =
        database.commitLocal {
            val ids = reads.categoryIdsInBudgetOrder(includeIncome = false, includeHidden = true)
            carryoverDrafts(ids, carryover, startMonth, throughMonth, validate = false) to Unit
        }.messages

    fun hold(month: String, command: HoldCommand): List<SyncMessage> =
        database.commitLocal { holdDrafts(reads.holdReview(month), command) to Unit }.messages

    // endregion

    // region Assign

    fun assignDrafts(month: String, categoryId: String, budgeted: Long): List<CellDraft> {
        val trimmed = categoryId.trim()
        if (trimmed.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing category")
        val monthValue = Months.actualMonthValue(month)
        val table = reads.budgetTable()
        val columns = database.requiredColumns(table.tableName, listOf("month", "category", "amount"))
        if (database.tableExists("categories") && !database.liveRowExists("categories", trimmed)) {
            throw LocalFirstException.InvalidLocalWrite("missing category")
        }
        return assignCellDrafts(trimmed, budgeted, monthValue, table, columns)
    }

    /** Actual's setBudget: update an existing row's amount, or insert identity cells and amount. */
    private fun assignCellDrafts(categoryId: String, budgeted: Long, monthValue: Int, table: BudgetTable, columns: Set<String>): List<CellDraft> {
        val existing = budgetRowId(table, monthValue, categoryId, columns)
        val rowId = existing ?: budgetRowId(monthValue, categoryId)
        val drafts = mutableListOf<CellDraft>()
        if (existing == null) drafts += identityDrafts(table, rowId, monthValue, categoryId, monthAsText = false)
        drafts += CellDraft(table.tableName, rowId, "amount", SyncValue.int(budgeted))
        return drafts
    }

    // endregion

    // region Move money

    /**
     * Covers and transfers from To Budget are limited to what their source has left in
     * [month], each leg seeing the legs before it. Plain moves pass through unchanged.
     */
    fun resolvedMoveCommands(commands: List<MoveMoneyCommand>, month: String): List<MoveMoneyCommand> {
        if (commands.none { it.kind != MoveMoneyCommand.Kind.MOVE }) return commands
        val budgetMonth = reads.fetchBudgetMonth(month)
        val remaining = HashMap<String?, Long>()
        remaining[null] = budgetMonth.toBudget
        for (category in budgetMonth.categories) remaining[category.id] = category.balance
        val resolved = mutableListOf<MoveMoneyCommand>()
        for (command in commands) {
            if (command.kind == MoveMoneyCommand.Kind.MOVE) {
                resolved += command
                continue
            }
            val capped = command.limitedTo(remaining[command.fromCategoryId] ?: 0) ?: continue
            remaining[command.fromCategoryId] = BudgetCalculation.checkedSubtract(remaining[command.fromCategoryId] ?: 0, capped.amount)
            resolved += capped
        }
        return resolved
    }

    fun moveDrafts(commands: List<MoveMoneyCommand>, month: String): List<CellDraft> {
        if (commands.isEmpty()) return emptyList()
        val monthValue = Months.actualMonthValue(month)
        val table = reads.budgetTable()
        if (table == BudgetTable.TRACKING) throw LocalFirstException.UnsupportedBudgetAction()
        val columns = database.requiredColumns(table.tableName, listOf("month", "category", "amount"))
        val budgeted = HashMap(reads.categoryBudgets(Months.monthId(monthValue)).mapValues { it.value.first })
        val affected = sortedSetOf<String>()
        for (command in commands) {
            // A transfer-available limited to nothing still writes the destination's row, as Actual does.
            val zeroTransfer = command.kind == MoveMoneyCommand.Kind.TRANSFER_AVAILABLE && command.amount == 0L
            if (command.amount <= 0 && !zeroTransfer) throw LocalFirstException.InvalidLocalWrite("missing amount")
            if (command.amount > MoveMoneyCommand.MAXIMUM_AMOUNT) throw LocalFirstException.NumericValueOutOfRange()
            if (command.fromCategoryId == null && command.toCategoryId == null) {
                throw LocalFirstException.InvalidLocalWrite("missing category")
            }
            command.fromCategoryId?.trim()?.let { from ->
                validateCategory(from)
                budgeted[from] = BudgetCalculation.checkedSubtract(budgeted[from] ?: 0, command.amount)
                affected += from
            }
            command.toCategoryId?.trim()?.let { to ->
                validateCategory(to)
                budgeted[to] = BudgetCalculation.checkedAdd(budgeted[to] ?: 0, command.amount)
                affected += to
            }
        }
        val drafts = mutableListOf<CellDraft>()
        // Actual's transfer-category from To Budget stores the move under a `to-budget` row
        // whose amount it reads from a sheet cell that never exists, so it writes the negation.
        commands.lastOrNull { it.fromCategoryId == null && it.kind == MoveMoneyCommand.Kind.MOVE }?.let { leg ->
            drafts += assignCellDrafts(TO_BUDGET_ROW_CATEGORY, -leg.amount, monthValue, table, columns)
        }
        for (id in affected) drafts += assignCellDrafts(id, budgeted[id] ?: 0, monthValue, table, columns)
        return drafts
    }

    private fun validateCategory(id: String) {
        if (id.isEmpty()) throw LocalFirstException.InvalidLocalWrite("missing category")
        if (database.tableExists("categories") && !database.liveRowExists("categories", id)) {
            throw LocalFirstException.InvalidLocalWrite("missing category")
        }
    }

    // endregion

    // region Carryover

    /** Actual applies rollover changes through the existing budget horizon. */
    fun carryoverDrafts(
        categoryIds: List<String>,
        carryover: Boolean,
        startMonth: String,
        throughMonth: String,
        validate: Boolean,
    ): List<CellDraft> {
        val start = Months.actualMonthValue(startMonth)
        val through = Months.actualMonthValue(throughMonth)
        if (through < start) throw LocalFirstException.InvalidLocalWrite("invalid carryover month range")
        val table = reads.budgetTable()
        val incomeById = reads.categoryIsIncomeById()
        if (table == BudgetTable.TRACKING && categoryIds.any { incomeById[it.trim()] == true }) {
            throw LocalFirstException.UnsupportedBudgetAction()
        }
        val columns = database.requiredColumns(table.tableName, listOf("month", "category", "carryover"))
        if (validate) categoryIds.forEach { validateCategory(it.trim()) }
        // Bound the fan-out: a stray far-future budget row must not become a loop over thousands of months.
        val cappedLast = Months.shifted(start, MAXIMUM_CARRYOVER_MONTH_SPAN - 1)
        if (through > cappedLast) throw LocalFirstException.InvalidLocalWrite("carryover month range too large")
        val lastExisting = reads.availableMonths().lastOrNull()?.let(Months::monthInt) ?: 0
        val effectiveThrough = minOf(cappedLast, maxOf(through, lastExisting))
        val existing = budgetRowIds(table, columns)
        val drafts = mutableListOf<CellDraft>()
        for (rawId in categoryIds) {
            val id = rawId.trim()
            var month = start
            while (month <= effectiveThrough) {
                val existingRow = existing[Months.monthId(month)]?.get(id)
                val rowId = existingRow ?: budgetRowId(month, id)
                // Actual writes identity cells only when it inserts the row, and its carryover insert stores the month as text.
                if (existingRow == null) drafts += identityDrafts(table, rowId, month, id, monthAsText = true)
                drafts += CellDraft(table.tableName, rowId, "carryover", SyncValue.bool(carryover))
                month = Months.next(month)
            }
        }
        return drafts
    }

    // endregion

    // region Holds

    fun holdDrafts(review: HoldReview, command: HoldCommand): List<CellDraft> = when (command) {
        is HoldCommand.Hold -> {
            if (command.amount <= 0 || command.amount > MAXIMUM_USER_AMOUNT) {
                throw LocalFirstException.InvalidLocalWrite("invalid hold amount")
            }
            // The hold disables this month's automatic hold, so the money it was holding can be held.
            if (review.holdableAmount <= 0) throw LocalFirstException.InvalidLocalWrite("no money is available to hold")
            val delta = minOf(command.amount, review.holdableAmount)
            val total = BudgetCalculation.checkedAdd(review.manualHeldAmount, delta)
            if (total < 0 || total > MAXIMUM_USER_AMOUNT) throw LocalFirstException.NumericValueOutOfRange()
            // Desktop dispatches reset-income-carryover with every hold.
            listOf(bufferDraft(review.month, total)) + resetIncomeCarryoverDrafts(review.month)
        }
        HoldCommand.Reset ->
            // With only an automatic hold, release disables it; otherwise the stored hold becomes zero.
            if (review.isAutomaticHold) resetIncomeCarryoverDrafts(review.month) else listOf(bufferDraft(review.month, 0))
    }

    private fun bufferDraft(month: String, amount: Long): CellDraft {
        database.requiredColumns("zero_budget_months", listOf("id", "buffered"))
        val matching = sql.query("SELECT id FROM zero_budget_months")
            .mapNotNull { it.string("id") }
            .filter { Months.canonical(it) == month }
        if (matching.size > 1) throw LocalFirstException.InvalidLocalWrite("duplicate hold rows for month")
        return CellDraft("zero_budget_months", matching.firstOrNull() ?: month, "buffered", SyncValue.int(amount))
    }

    /** Actual resets every live income category for the selected month only. */
    fun resetIncomeCarryoverDrafts(month: String): List<CellDraft> {
        val monthValue = Months.actualMonthValue(month)
        val table = reads.budgetTable()
        val columns = database.requiredColumns(table.tableName, listOf("month", "category", "carryover"))
        val drafts = mutableListOf<CellDraft>()
        for (id in reads.incomeCategoryIds().sorted()) {
            val existing = budgetRowId(table, monthValue, id, columns)
            val rowId = existing ?: budgetRowId(monthValue, id)
            if (existing == null) drafts += identityDrafts(table, rowId, monthValue, id, monthAsText = true)
            drafts += CellDraft(table.tableName, rowId, "carryover", SyncValue.bool(false))
        }
        return drafts
    }

    // endregion

    // region Row identity

    private fun identityDrafts(table: BudgetTable, rowId: String, monthValue: Int, categoryId: String, monthAsText: Boolean) = listOf(
        CellDraft(table.tableName, rowId, "month", if (monthAsText) SyncValue.text(monthValue.toString()) else SyncValue.int(monthValue)),
        CellDraft(table.tableName, rowId, "category", SyncValue.text(categoryId)),
    )

    private fun budgetRowId(table: BudgetTable, monthValue: Int, categoryId: String, columns: Set<String>): String? {
        if ("id" !in columns) {
            return if (sql.queryOne(
                    "SELECT category FROM ${Sql.quoted(table.tableName)} WHERE ${normalizedMonthSql("month")} = ? AND category = ? LIMIT 1",
                    Months.monthId(monthValue),
                    categoryId,
                ) != null
            ) {
                budgetRowId(monthValue, categoryId)
            } else {
                null
            }
        }
        return sql.queryOne(
            "SELECT id FROM ${Sql.quoted(table.tableName)} WHERE ${normalizedMonthSql("month")} = ? AND category = ? LIMIT 1",
            Months.monthId(monthValue),
            categoryId,
        )?.string("id")
    }

    /** Existing row ids by normalized month, then category; the first row for a pair decides. */
    private fun budgetRowIds(table: BudgetTable, columns: Set<String>): Map<String, Map<String, String>> {
        val result = HashMap<String, HashMap<String, String>>()
        val idColumn = if ("id" in columns) "id" else "NULL"
        for (row in sql.query("SELECT ${normalizedMonthSql("month")} AS month, category, $idColumn AS id FROM ${Sql.quoted(table.tableName)}")) {
            val month = row.string("month") ?: continue
            val category = row.string("category") ?: continue
            val byCategory = result.getOrPut(month) { HashMap() }
            if (category in byCategory) continue
            val id = if ("id" in columns) row.string("id") else budgetRowId(Months.monthInt(month), category)
            if (id != null) byCategory[category] = id
        }
        return result
    }

    // endregion

    companion object {
        const val MAXIMUM_CARRYOVER_MONTH_SPAN = 600
        const val TO_BUDGET_ROW_CATEGORY = "to-budget"
        const val MAXIMUM_USER_AMOUNT = 9_000_000_000_000_000L

        fun budgetRowId(monthValue: Int, categoryId: String) = "$monthValue-$categoryId"
    }
}
