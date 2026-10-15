package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.ScheduleFrequency
import com.sporez.actualist.core.rules.ScheduleRecurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAutomationPresentationTest {
    private fun item(id: String, name: String, nextDate: String?, canPost: Boolean = true) =
        ScheduleListItem(
            id = id,
            name = name,
            payeeName = null,
            accountId = null,
            accountName = null,
            amount = ScheduleAmount.Exact(-1_000),
            amountMinorUnits = -1_000,
            amountLower = -1_000,
            amountUpper = -1_000,
            isApproximate = false,
            nextDate = nextDate,
            status = ScheduleStatus.DUE,
            recurrenceDescription = "Every month",
            isRecurring = true,
            postsTransaction = true,
            completed = false,
            isSupported = true,
            canPost = canPost,
            canSkip = true,
            canComplete = true,
            canDelete = true,
            canEdit = true,
        )

    private fun refusal(id: String, day: String) =
        ScheduleAutoPostRefusal(id, null, day, SchedulePostingRefusal.RULE_DELETES_TRANSACTION)

    @Test
    fun refusalRowsKeepOnlyOccurrencesThatStillApply() {
        val items = listOf(item("a", "Rent", "2026-11-01"), item("b", "Phone", "2026-12-01"))
        val rows = ScheduleAutoPostRefusals.rows(
            listOf(refusal("a", "2026-11-01"), refusal("b", "2026-11-01"), refusal("gone", "2026-11-01")),
            items,
        )
        assertEquals(1, rows.size)
        assertEquals("a", rows[0].scheduleId)
        assertEquals("Rent", rows[0].title)
        assertEquals(SchedulePostingRefusal.RULE_DELETES_TRANSACTION.message, rows[0].message)
    }

    @Test
    fun upcomingWindowOptionsMatchActualist() {
        assertEquals(listOf(null, "7", "14", "30", "currentMonth", "oneMonth"), ScheduleUpcomingWindow.options.map { it.first })
        assertEquals("Budget default", ScheduleUpcomingWindow.label(null))
        assertEquals("Current month", ScheduleUpcomingWindow.label("currentMonth"))
        assertEquals("45 days", ScheduleUpcomingWindow.label("45"))
    }

    @Test
    fun skipPreviewIsTheNextOccurrence() {
        val monthly = ScheduleRecurrence("2026-10-05", ScheduleFrequency.MONTHLY)
        assertEquals("2026-11-05", ScheduleReviewPresentation.nextDateAfterSkip(monthly, "2026-10-05"))
        assertNull(ScheduleReviewPresentation.nextDateAfterSkip(null, "2026-10-05"))
        assertNull(ScheduleReviewPresentation.nextDateAfterSkip(monthly, null))
    }

    @Test
    fun postStaysValidOnlyWhileTheReviewedOccurrenceIsStillNext() {
        val reviewed = item("a", "Rent", "2026-11-01")
        assertTrue(ScheduleReviewPresentation.stillPostable(reviewed, item("a", "Rent", "2026-11-01")))
        assertFalse(ScheduleReviewPresentation.stillPostable(reviewed, item("a", "Rent", "2026-12-01")))
        assertFalse(ScheduleReviewPresentation.stillPostable(reviewed, item("a", "Rent", "2026-11-01", canPost = false)))
        assertFalse(ScheduleReviewPresentation.stillPostable(reviewed, null))
    }
}
