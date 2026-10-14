package com.sporez.actualist.core.widgets

import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.TransactionRow

/** What Needs Attention shows, as Actualist's widget: overspent categories and uncategorized transactions. */
data class AttentionSummary(val overspent: List<BudgetMonthCategory>, val uncategorizedCount: Int) {
    val issueCount: Int get() = overspent.size + uncategorizedCount
}

object WidgetShaping {
    /** Expense categories whose available balance is negative, most overspent first. */
    fun overspent(categories: List<BudgetMonthCategory>): List<BudgetMonthCategory> =
        categories.filter { !it.isIncome && !it.hidden && it.balance < 0 }.sortedBy { it.balance }

    /** Open on-budget accounts in their sort order, as the Account Balances widget lists them. */
    fun onBudgetAccounts(accounts: List<Account>, limit: Int): List<Account> =
        accounts.filter { !it.closed && !it.offBudget }.sortedBy { it.sortOrder }.take(limit)

    /** The newest rows for Recent Transactions: no split children and no starting balances. */
    fun recent(rows: List<TransactionRow>, limit: Int): List<TransactionRow> =
        rows.filter { !it.isChild && !it.startingBalance }.take(limit)
}
