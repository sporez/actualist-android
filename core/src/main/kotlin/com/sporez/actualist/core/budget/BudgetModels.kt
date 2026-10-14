package com.sporez.actualist.core.budget

/** Which Actual budget sheet the file uses. */
enum class BudgetTable(val tableName: String) {
    ENVELOPE("zero_budgets"),
    TRACKING("reflect_budgets"),
}

/** One category's value for one month: amounts are integer minor units. */
data class CategoryValue(
    val budgeted: Long = 0,
    val spent: Long = 0,
    val balance: Long = 0,
    val carryover: Boolean = false,
)

data class BudgetMonthCategory(
    val id: String,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val groupId: String,
    val budgeted: Long,
    val spent: Long,
    val balance: Long,
    val carryover: Boolean,
    val hasNote: Boolean = false,
)

data class BudgetMonthCategoryGroup(
    val id: String,
    val name: String,
    val isIncome: Boolean,
    val hidden: Boolean,
    val budgeted: Long,
    val spent: Long,
    val balance: Long,
    val categories: List<BudgetMonthCategory>,
)

data class TrackingSummary(
    val budgetedIncome: Long,
    val budgetedExpenses: Long,
    val receivedIncome: Long,
    val expenseActivity: Long,
    val plannedSavings: Long,
    val actualSavings: Long,
)

/**
 * One month of the budget sheet. Field meanings follow Actual's envelope sheet:
 * [toBudget] is `to-budget`, [incomeAvailable] is `available-funds`, [forNextMonth] is
 * `buffered-selected`, [totalBalance] is `total-leftover`. [totalBudgeted] is positive
 * (Actual's envelope sheet stores its negation).
 */
data class BudgetMonth(
    val month: String,
    val table: BudgetTable,
    val incomeAvailable: Long,
    val lastMonthOverspent: Long,
    val forNextMonth: Long,
    val totalBudgeted: Long,
    val toBudget: Long,
    val fromLastMonth: Long,
    val totalIncome: Long,
    val totalSpent: Long,
    val totalBalance: Long,
    val categoryGroups: List<BudgetMonthCategoryGroup>,
    val trackingSummary: TrackingSummary?,
    val hasNote: Boolean = false,
) {
    val categories: List<BudgetMonthCategory> get() = categoryGroups.flatMap { it.categories }

    fun category(id: String): BudgetMonthCategory? = categories.firstOrNull { it.id == id }
}

/** One leg of Move Money. A null category is To Budget. */
data class MoveMoneyCommand(
    val fromCategoryId: String?,
    val toCategoryId: String?,
    val amount: Long,
    val kind: Kind = Kind.MOVE,
) {
    enum class Kind { MOVE, COVER, TRANSFER_AVAILABLE }

    /**
     * A cover or transfer-available is limited to what its source has left. A plain move
     * passes through unchanged; a cover with nothing to move is dropped (null).
     */
    fun limitedTo(sourceAvailable: Long): MoveMoneyCommand? = when (kind) {
        Kind.MOVE -> this
        Kind.COVER -> {
            val limited = minOf(amount, sourceAvailable)
            if (amount <= 0 || limited <= 0) null else copy(amount = limited)
        }
        Kind.TRANSFER_AVAILABLE -> copy(amount = maxOf(minOf(amount, sourceAvailable), 0))
    }

    companion object {
        /** Largest accepted single move, far from Long overflow in per-category sums. */
        const val MAXIMUM_AMOUNT = 1_000_000_000_000L
    }
}

sealed class HoldCommand {
    data class Hold(val amount: Long) : HoldCommand()
    data object Reset : HoldCommand()
}

data class HoldReview(
    val month: String,
    val toBudget: Long,
    /** Actual's effective `buffered-selected`: manual when nonzero, otherwise automatic. */
    val heldAmount: Long,
    val manualHeldAmount: Long,
    val automaticHeldAmount: Long,
) {
    /** What a manual hold may take: To Budget plus the money an automatic hold is holding. */
    val holdableAmount: Long get() = if (manualHeldAmount == 0L) toBudget + maxOf(0, automaticHeldAmount) else toBudget
    val isAutomaticHold: Boolean get() = manualHeldAmount == 0L && automaticHeldAmount != 0L
}
