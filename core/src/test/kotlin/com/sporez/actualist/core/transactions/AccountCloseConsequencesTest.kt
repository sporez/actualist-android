package com.sporez.actualist.core.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountCloseConsequencesTest {
    private fun review(action: AccountCloseAction?, count: Int, simpleFin: Boolean = false) = AccountCloseReview(
        accountId = "a",
        balance = 1000L,
        liveTransactionCount = count,
        offBudget = false,
        hasSimpleFinLink = simpleFin,
        action = action,
        blockers = emptyList(),
    )

    @Test
    fun actionTitlesFollowTheResolvedAction() {
        assertEquals("Delete Account", AccountCloseConsequences.actionTitle(AccountCloseAction.DeleteEmpty))
        assertEquals("Close Account", AccountCloseConsequences.actionTitle(AccountCloseAction.CloseAtZero))
        assertEquals("Transfer and Close", AccountCloseConsequences.actionTitle(AccountCloseAction.CloseWithTransfer("b", 1000L, null)))
        assertNull(AccountCloseConsequences.actionTitle(null))
    }

    @Test
    fun rowsListCountBankLinkAndSchedules() {
        val rows = AccountCloseConsequences.rows(
            review(AccountCloseAction.CloseWithTransfer("b", 1000L, null), 12, simpleFin = true),
            balanceText = "10.00",
            destinationName = "Savings",
            categoryName = null,
            scheduleNames = listOf("Rent", "Gym"),
        )
        assertEquals(
            listOf("balance", "transactions", "destination", "bank", "schedules", "schedule-posting"),
            rows.map { it.id },
        )
        assertEquals("12", rows.first { it.id == "transactions" }.value)
        assertEquals("Rent, Gym", rows.first { it.id == "schedules" }.value)
        assertEquals(AccountCloseConsequences.POSTING_PAUSES, rows.last().value)
    }

    @Test
    fun deletingStopsPostingAndNoSchedulesAddsNoRows() {
        val deleting = AccountCloseConsequences.rows(review(AccountCloseAction.DeleteEmpty, 0), "0.00", null, null, listOf("Rent"))
        assertEquals(AccountCloseConsequences.POSTING_STOPS, deleting.last().value)
        val plain = AccountCloseConsequences.rows(review(AccountCloseAction.CloseAtZero, 3), "0.00", null, null, emptyList())
        assertEquals(listOf("balance", "transactions"), plain.map { it.id })
    }
}
