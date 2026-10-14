package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException

/**
 * The category recurrence and aggregate contract (Actualist `BudgetFinancialCalculation`).
 * SQL selects activity; this never re-sums transactions.
 */
object BudgetCalculation {
    /** Actual's safeNumber bound (2^51 - 1), applied to tracking sheet arithmetic. */
    private const val TRACKING_LIMIT = (1L shl 51) - 1

    fun category(
        table: BudgetTable,
        isIncome: Boolean,
        budgeted: Long,
        activity: Long,
        carryover: Boolean,
        previous: CategoryValue,
    ): CategoryValue {
        val contribution = when {
            previous.carryover -> previous.balance
            table == BudgetTable.TRACKING -> 0
            else -> maxOf(0, previous.balance)
        }
        val signedActivity = if (table == BudgetTable.TRACKING && isIncome) {
            if (activity == Long.MIN_VALUE) throw LocalFirstException.NumericValueOutOfRange()
            -activity
        } else {
            activity
        }
        return CategoryValue(
            budgeted = budgeted,
            spent = activity,
            balance = sum(listOf(budgeted, signedActivity, contribution), table),
            carryover = carryover,
        )
    }

    fun includedCategories(categories: List<BudgetMonthCategory>, table: BudgetTable) =
        if (table == BudgetTable.TRACKING) categories.filter { !it.hidden } else categories

    data class Totals(
        val budgeted: Long,
        val spent: Long,
        val balance: Long,
        val income: Long,
        val tracking: TrackingSummary?,
    )

    fun totals(groups: List<BudgetMonthCategoryGroup>, table: BudgetTable): Totals {
        val expenses = groups.filter { !it.isIncome && (table == BudgetTable.ENVELOPE || !it.hidden) }
        val incomeGroups = groups.filter { it.isIncome }
        val budgeted = sum(expenses.map { it.budgeted }, table)
        val spent = sum(expenses.map { it.spent }, table)
        val balance = sum(expenses.map { it.balance }, table)
        val income = if (table == BudgetTable.TRACKING) {
            incomeGroups.firstOrNull()?.spent ?: 0
        } else {
            sum(incomeGroups.map { it.spent }, table)
        }
        val summary = if (table == BudgetTable.TRACKING) {
            val plannedIncome = incomeGroups.firstOrNull()?.budgeted ?: 0
            TrackingSummary(
                budgetedIncome = plannedIncome,
                budgetedExpenses = budgeted,
                receivedIncome = income,
                expenseActivity = spent,
                plannedSavings = sum(listOf(plannedIncome, -budgeted), table),
                actualSavings = sum(listOf(income, spent), table),
            )
        } else {
            null
        }
        return Totals(budgeted, spent, balance, income, summary)
    }

    fun sum(amounts: List<Long>, table: BudgetTable = BudgetTable.ENVELOPE): Long {
        var total = 0L
        for (amount in amounts) {
            total = try {
                Math.addExact(total, amount)
            } catch (error: ArithmeticException) {
                throw LocalFirstException.NumericValueOutOfRange()
            }
        }
        if (table == BudgetTable.TRACKING && total !in -TRACKING_LIMIT..TRACKING_LIMIT) {
            throw LocalFirstException.NumericValueOutOfRange()
        }
        return total
    }

    fun checkedAdd(a: Long, b: Long): Long = try {
        Math.addExact(a, b)
    } catch (error: ArithmeticException) {
        throw LocalFirstException.NumericValueOutOfRange()
    }

    fun checkedSubtract(a: Long, b: Long): Long = try {
        Math.subtractExact(a, b)
    } catch (error: ArithmeticException) {
        throw LocalFirstException.NumericValueOutOfRange()
    }
}
