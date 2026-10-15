package com.sporez.actualist.core.schedules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleReferenceTextTest {
    private fun item(
        payee: String? = "Landlord",
        payeeMissing: Boolean = false,
        account: String? = "Checking",
        availability: ScheduleReferenceAvailability = ScheduleReferenceAvailability.AVAILABLE,
        supported: Boolean = true,
    ) = ScheduleListItem(
        id = "s",
        name = "Rent",
        payeeName = payee,
        accountId = "a",
        accountName = account,
        amount = ScheduleAmount.Exact(-100),
        amountMinorUnits = -100,
        amountLower = -100,
        amountUpper = -100,
        isApproximate = false,
        nextDate = "2026-11-05",
        status = ScheduleStatus.SCHEDULED,
        recurrenceDescription = "Every month",
        isRecurring = true,
        postsTransaction = false,
        completed = false,
        isSupported = supported,
        canPost = true,
        canSkip = true,
        canComplete = true,
        canDelete = true,
        canEdit = true,
        payeeMissing = payeeMissing,
        accountAvailability = availability,
    )

    @Test
    fun missingPayeeAndClosedAccountAreLabelled() {
        val row = item(payee = null, payeeMissing = true, account = "Closed Card", availability = ScheduleReferenceAvailability.CLOSED)
        assertEquals("Unavailable payee • Closed Card (Closed)", ScheduleListPresentation.referenceText(row))
    }

    @Test
    fun missingAccountIsUnavailable() {
        val row = item(account = null, availability = ScheduleReferenceAvailability.MISSING)
        assertEquals("Unavailable account", ScheduleListPresentation.accountText(row))
        assertEquals("Landlord", ScheduleListPresentation.payeeText(row))
    }

    @Test
    fun limitationOnlyWhenUnsupported() {
        assertNull(ScheduleListPresentation.limitationText(item()))
        assertEquals("Some schedule options are unavailable", ScheduleListPresentation.limitationText(item(supported = false)))
    }

    @Test
    fun upcomingWindowMarksTheBudgetDefault() {
        assertEquals("7 days (Budget default)", ScheduleListPresentation.upcomingWindowText(null, "7"))
        assertEquals("14 days", ScheduleListPresentation.upcomingWindowText("14", "7"))
    }
}
