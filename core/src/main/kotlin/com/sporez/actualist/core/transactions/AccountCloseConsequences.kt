package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.schedules.ScheduleReads
import com.sporez.actualist.core.schedules.ScheduleStatus

/** One labeled line of the close/delete review ("Transactions: 12"). */
data class AccountConsequenceRow(val id: String, val label: String, val value: String)

/** The consequences Actualist's close/delete review lists, in its words. */
object AccountCloseConsequences {
    const val SIMPLEFIN_REMOVED = "SimpleFIN connection will be removed and cannot be restored from History"
    const val POSTING_PAUSES = "Posting pauses while closed and resumes if the account is reopened."
    const val POSTING_STOPS = "Scheduled posting stops because this account will be deleted."

    /** The names of the account's schedules that are not completed. */
    fun activeScheduleNames(database: BudgetDatabase, accountId: String, today: String): List<String> =
        ScheduleReads(database).fetchSchedules(today).schedules
            .filter { it.account.id == accountId && it.status != ScheduleStatus.COMPLETED }
            .map { it.displayName }

    /** The button title for the resolved action, or null while a destination is still needed. */
    fun actionTitle(action: AccountCloseAction?): String? = when (action) {
        AccountCloseAction.DeleteEmpty -> "Delete Account"
        AccountCloseAction.CloseAtZero -> "Close Account"
        is AccountCloseAction.CloseWithTransfer -> "Transfer and Close"
        null -> null
    }

    fun rows(
        review: AccountCloseReview,
        balanceText: String,
        destinationName: String?,
        categoryName: String?,
        scheduleNames: List<String>,
    ): List<AccountConsequenceRow> {
        val rows = mutableListOf(
            AccountConsequenceRow("balance", "Balance", balanceText),
            AccountConsequenceRow("transactions", "Transactions", review.liveTransactionCount.toString()),
        )
        if (destinationName != null) rows += AccountConsequenceRow("destination", "Transfer to", destinationName)
        if (categoryName != null) rows += AccountConsequenceRow("category", "Category", categoryName)
        if (review.hasSimpleFinLink) rows += AccountConsequenceRow("bank", "Bank connection", SIMPLEFIN_REMOVED)
        if (scheduleNames.isNotEmpty()) {
            rows += AccountConsequenceRow("schedules", "Active schedules", scheduleNames.joinToString(", "))
            val effect = if (review.action == AccountCloseAction.DeleteEmpty) POSTING_STOPS else POSTING_PAUSES
            rows += AccountConsequenceRow("schedule-posting", "Scheduled posting", effect)
        }
        return rows
    }
}
