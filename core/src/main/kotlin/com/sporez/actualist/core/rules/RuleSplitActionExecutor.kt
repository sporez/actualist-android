package com.sporez.actualist.core.rules

import java.time.LocalDate

/** Canonical split-family row (Actualist `SplitTransactionRecord`). Children are separate rows. */
internal data class SplitRecord(
    var id: String,
    var amount: Long = 0,
    var account: String? = null,
    var date: String? = null,
    var category: String? = null,
    var payee: String? = null,
    var notes: String? = null,
    var cleared: Boolean? = null,
    var reconciled: Boolean? = null,
    var startingBalance: Boolean? = null,
    var sortOrder: Double? = null,
    var isParent: Boolean = false,
    var isChild: Boolean = false,
    var parentId: String? = null,
    var deleted: Boolean = false,
)

/**
 * Actual `execActions` / `execSplitActions` for rule-created families. The family is held as a
 * flat list: the parent first, then its children.
 */
object RuleSplitActionExecutor {
    /** Applies [actions] to the context's transaction. Returns false when the rule fails closed. */
    fun apply(actions: List<RuleAction>, context: RuleEvaluationContext, idGenerator: () -> String): Boolean {
        val record = recordFrom(context)
        val formula = formulaContext(context)
        val result = execActions(actions, record, formula, idGenerator) ?: return false
        write(result.first, result.second, context)
        return true
    }

    private fun execActions(
        actions: List<RuleAction>,
        transaction: SplitRecord,
        formula: RuleFormulaContext,
        idGenerator: () -> String,
    ): Pair<SplitRecord, List<SplitRecord>>? {
        val parentActions = actions.filter { (it.splitIndex ?: 0) == 0 }
        val childActions = actions.filter { (it.splitIndex ?: 0) != 0 }
        val totalSplitCount = (actions.maxOfOrNull { it.splitIndex ?: 0 } ?: 0) + 1

        val result = transaction.copy()
        for (action in parentActions) {
            if (!applyOne(action, result, formula, null)) return null
        }
        if (totalSplitCount == 1) return Pair(result, emptyList())
        if (result.isChild) return Pair(result, emptyList())
        return execSplitActions(childActions, result, formula, idGenerator)
    }

    private fun makeChild(parent: SplitRecord, id: String): SplitRecord = SplitRecord(
        id = (if (parent.id == "temp") "temp" else "") + id,
        amount = 0,
        account = parent.account,
        date = parent.date,
        category = parent.category,
        payee = parent.payee,
        notes = null,
        cleared = parent.cleared,
        reconciled = parent.reconciled,
        startingBalance = parent.startingBalance,
        sortOrder = parent.sortOrder,
        isParent = false,
        isChild = true,
        parentId = parent.id,
    )

    /** Actual `splitTransaction`: a plain transaction becomes a parent with one placeholder child. */
    private fun splitRows(parent: SplitRecord, idGenerator: () -> String): MutableList<SplitRecord> {
        if (parent.isParent || parent.isChild) return mutableListOf(parent)
        val child = makeChild(parent, idGenerator())
        val newParent = parent.copy(isParent = true, payee = null)
        // Actual: `sort_order || -1`, so 0 is also replaced.
        child.sortOrder = if ((child.sortOrder ?: 0.0) == 0.0) -1.0 else child.sortOrder
        return mutableListOf(newParent, child)
    }

    /** Actual `addSplitTransaction`: appends a zero-amount child to a parent family. */
    private fun addSplitRow(rows: MutableList<SplitRecord>, idGenerator: () -> String) {
        val parent = rows[0]
        if (!parent.isParent) return
        val previous = if (rows.size > 1) rows[rows.size - 1] else null
        val child = makeChild(parent, idGenerator())
        child.amount = 0
        child.payee = previous?.payee ?: parent.payee
        child.sortOrder = (previous?.sortOrder ?: 0.0) - 1
        rows.add(child)
    }

    private fun splitRemainder(rows: List<SplitRecord>): Long {
        var total = 0L
        for (index in 1 until rows.size) total += rows[index].amount
        val difference = rows[0].amount - total
        return if (difference == 0L) 0L else difference
    }

    private fun execSplitActions(
        actions: List<RuleAction>,
        parent: SplitRecord,
        formula: RuleFormulaContext,
        idGenerator: () -> String,
    ): Pair<SplitRecord, List<SplitRecord>>? {
        val rows = splitRows(parent, idGenerator)

        for (action in actions) {
            val splitTransactionIndex = (action.splitIndex ?: 0) + 1
            if (splitTransactionIndex >= rows.size) addSplitRow(rows, idGenerator)
            if (splitTransactionIndex >= rows.size) return null
            if (!applyOne(action, rows[splitTransactionIndex], formula, parent.amount)) return null
        }

        val splitAmountActions = actions.filter { it.op == "set-split-amount" }
        val remainingAfterFixedAmounts = splitRemainder(rows)
        for (action in splitAmountActions) {
            if (action.splitMethod != "fixed-percent") continue
            val splitTransactionIndex = (action.splitIndex ?: 0) + 1
            if (splitTransactionIndex >= rows.size) return null
            val percent = numericValue(action.value) ?: return null
            try {
                rows[splitTransactionIndex].amount =
                    RuleFormulaEvaluator.javascriptRound(remainingAfterFixedAmounts.toDouble() * (percent / 100))
            } catch (e: RuleFormulaException) {
                return null
            }
        }

        val remainderActions = splitAmountActions.filter { it.splitMethod == "remainder" }
        if (remainderActions.isNotEmpty()) {
            val remainingAfterFixedPercents = splitRemainder(rows)
            val amountPerRemainderSplit = try {
                RuleFormulaEvaluator.javascriptRound(remainingAfterFixedPercents.toDouble() / remainderActions.size.toDouble())
            } catch (e: RuleFormulaException) {
                return null
            }
            var lastNonFixedTransactionIndex = -1
            for (action in remainderActions) {
                val splitTransactionIndex = (action.splitIndex ?: 0) + 1
                if (splitTransactionIndex >= rows.size) return null
                rows[splitTransactionIndex].amount = amountPerRemainderSplit
                lastNonFixedTransactionIndex = maxOf(lastNonFixedTransactionIndex, splitTransactionIndex)
            }
            if (lastNonFixedTransactionIndex >= 0) {
                rows[lastNonFixedTransactionIndex].amount += splitRemainder(rows)
            }
        }

        if (rows.size <= 1) return null
        rows.removeAt(1)
        return Pair(rows[0], rows.drop(1))
    }

    private fun applyOne(action: RuleAction, record: SplitRecord, formula: RuleFormulaContext, parentAmount: Long?): Boolean {
        val fields = HashMap(formula.fields)
        fields["amount"] = RuleFormulaValue.Num(record.amount.toDouble())
        fields["account"] = RuleFormulaValue.Str(record.account ?: "")
        fields["date"] = RuleFormulaValue.Str(record.date ?: "")
        fields["category"] = RuleFormulaValue.Str(record.category ?: "")
        fields["payee"] = RuleFormulaValue.Str(record.payee ?: "")
        fields["notes"] = RuleFormulaValue.Str(record.notes ?: "")
        fields["cleared"] = RuleFormulaValue.Flag(record.cleared ?: false)
        fields["reconciled"] = RuleFormulaValue.Flag(record.reconciled ?: false)
        fields["is_parent"] = RuleFormulaValue.Flag(record.isParent)
        fields["is_child"] = RuleFormulaValue.Flag(record.isChild)
        fields["parent_id"] = RuleFormulaValue.Str(record.parentId ?: "")
        record.sortOrder?.let { fields["sort_order"] = RuleFormulaValue.Num(it) }
        if (parentAmount != null) fields["parent_amount"] = RuleFormulaValue.Num(parentAmount.toDouble())
        val formulaContext = RuleFormulaContext(fields, formula.balanceOfPrefetch, formula.today)

        when (action.op) {
            "set" -> {
                val formulaText = (action.options?.get("formula") as? RuleValue.Text)?.value
                if (formulaText != null) {
                    val value = evaluateSetFormula(formulaText, action.fieldName, formulaContext) ?: return false
                    assign(action.fieldName, value, record)
                    return true
                }
                assign(action.fieldName, action.value, record)
                return true
            }
            "set-split-amount" -> {
                when (action.splitMethod) {
                    "fixed-amount" -> {
                        record.amount = integerAmount(action.value) ?: return false
                    }
                    "formula" -> {
                        val formulaText = (action.options?.get("formula") as? RuleValue.Text)?.value ?: return true
                        try {
                            val result = RuleFormulaEvaluator.evaluate(formulaText, formulaContext)
                            val number = result.number ?: return false
                            record.amount = integerAmount(RuleValue.Num(number)) ?: return false
                        } catch (e: RuleFormulaException) {
                            return false
                        }
                    }
                    else -> Unit
                }
                return true
            }
            "prepend-notes" -> {
                val value = (action.value as? RuleValue.Text)?.value
                if (value != null) record.notes = record.notes?.let { value + it } ?: value
                return true
            }
            "append-notes" -> {
                val value = (action.value as? RuleValue.Text)?.value
                if (value != null) record.notes = record.notes?.let { it + value } ?: value
                return true
            }
            "delete-transaction" -> {
                record.deleted = true
                return true
            }
            "link-schedule" -> return true
            else -> return false
        }
    }

    private fun evaluateSetFormula(formulaText: String, field: String?, context: RuleFormulaContext): RuleValue? {
        try {
            val result = RuleFormulaEvaluator.evaluate(formulaText, context)
            return when (field) {
                "amount" -> result.number?.let { RuleValue.Num(it) }
                "notes", "payee", "category", "account", "acct", "date" -> when (result) {
                    is RuleFormulaValue.Str -> RuleValue.Text(result.value)
                    is RuleFormulaValue.Num -> RuleValue.Text(result.value.toLong().toString())
                    is RuleFormulaValue.Flag -> RuleValue.Text(if (result.value) "true" else "false")
                }
                "cleared" -> (result as? RuleFormulaValue.Flag)?.let { RuleValue.Bool(it.value) }
                else -> null
            }
        } catch (e: RuleFormulaException) {
            return null
        }
    }

    private fun assign(field: String?, value: RuleValue, record: SplitRecord) {
        when (field) {
            "account", "acct" -> (value as? RuleValue.Text)?.let { record.account = it.value }
            "amount" -> integerAmount(value)?.let { record.amount = it }
            "category" -> record.category = stringOrNil(value)
            "date" -> (value as? RuleValue.Text)?.let { record.date = it.value }
            "notes" -> record.notes = stringOrNil(value)
            "payee", "description" -> record.payee = stringOrNil(value)
            "cleared" -> (value as? RuleValue.Bool)?.let { record.cleared = it.value }
            else -> Unit
        }
    }

    private fun integerAmount(value: RuleValue): Long? = when (value) {
        is RuleValue.Num -> RuleNumbers.truncatedLong(value.value)
        is RuleValue.Text -> value.value.toLongOrNull()
        else -> null
    }

    private fun numericValue(value: RuleValue): Double? = when (value) {
        is RuleValue.Num -> value.value
        is RuleValue.Text -> RuleNumbers.parseDouble(value.value)
        else -> null
    }

    private fun stringOrNil(value: RuleValue): String? {
        if (value is RuleValue.Text) return if (value.value.isEmpty()) null else value.value
        return null
    }

    private fun recordFrom(context: RuleEvaluationContext) = SplitRecord(
        id = context.evaluationId,
        amount = context.amount,
        account = context.accountId,
        date = ScheduleRecurrence.dayId(context.date),
        category = context.categoryId,
        payee = context.payeeId,
        notes = context.notes,
        cleared = context.cleared,
        reconciled = context.reconciled,
        startingBalance = context.startingBalance,
        sortOrder = context.sortOrder,
        isParent = context.isParent,
        isChild = context.isChild,
        parentId = context.parentId,
        deleted = context.deletesTransaction,
    )

    private fun write(record: SplitRecord, children: List<SplitRecord>, context: RuleEvaluationContext) {
        val metadata = context.metadata
        context.amount = record.amount
        context.accountId = record.account ?: context.accountId
        context.accountName = metadata.accountNames[context.accountId] ?: context.accountName
        context.accountIsOffBudget = context.accountId in metadata.offBudgetAccountIds
        val date: LocalDate? = record.date?.let { ScheduleRecurrence.parseDay(it) }
        if (date != null) context.date = date
        context.categoryId = record.category
        context.categoryName = context.categoryId?.let { metadata.categoryNames[it] }
        context.categoryGroupId = context.categoryId?.let { metadata.categoryGroupsByCategoryId[it] }
        context.categoryGroupName = context.categoryGroupId?.let { metadata.categoryGroupNames[it] }
        context.payeeId = record.payee
        context.payeeName = context.payeeId?.let { metadata.payeeNames[it] } ?: ""
        context.notes = record.notes
        context.cleared = record.cleared ?: false
        context.reconciled = record.reconciled ?: false
        context.isParent = record.isParent
        context.isChild = record.isChild
        context.parentId = record.parentId
        context.sortOrder = record.sortOrder
        context.startingBalance = record.startingBalance ?: false
        context.deletesTransaction = record.deleted
        context.splits = children.map { child ->
            RuleEvaluationSplit(child.id, child.category, child.amount, child.payee, child.notes, child.sortOrder)
        }
    }

    private fun formulaContext(context: RuleEvaluationContext) = RuleFormulaContext(
        fields = mapOf(
            "amount" to RuleFormulaValue.Num(context.amount.toDouble()),
            "account" to RuleFormulaValue.Str(context.accountId),
            "account_name" to RuleFormulaValue.Str(context.accountName),
            "category" to RuleFormulaValue.Str(context.categoryId ?: ""),
            "category_name" to RuleFormulaValue.Str(context.categoryName ?: ""),
            "payee" to RuleFormulaValue.Str(context.payeeId ?: ""),
            "notes" to RuleFormulaValue.Str(context.notes ?: ""),
            "imported_payee" to RuleFormulaValue.Str(context.importedPayee ?: ""),
            "cleared" to RuleFormulaValue.Flag(context.cleared),
            "reconciled" to RuleFormulaValue.Flag(context.reconciled),
            "is_parent" to RuleFormulaValue.Flag(context.isParent),
            "is_child" to RuleFormulaValue.Flag(context.isChild),
            "balance" to RuleFormulaValue.Num(context.balance.toDouble()),
        ),
        balanceOfPrefetch = context.balanceOfPrefetch,
        today = context.today,
    )
}
