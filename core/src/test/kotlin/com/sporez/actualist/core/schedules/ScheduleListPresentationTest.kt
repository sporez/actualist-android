package com.sporez.actualist.core.schedules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleListPresentationTest {
    private fun item(id: String, name: String, status: ScheduleStatus, payee: String? = null, account: String? = null, amount: Long = -1_599) =
        ScheduleListItem(
            id = id,
            name = name,
            payeeName = payee,
            accountId = null,
            accountName = account,
            amount = ScheduleAmount.Exact(amount),
            amountMinorUnits = amount,
            amountLower = amount,
            amountUpper = amount,
            isApproximate = false,
            nextDate = "2026-11-05",
            status = status,
            recurrenceDescription = "Every month",
            isRecurring = true,
            postsTransaction = false,
            completed = status == ScheduleStatus.COMPLETED,
            isSupported = true,
            canPost = true,
            canSkip = true,
            canComplete = true,
            canDelete = true,
            canEdit = true,
        )

    private val items = listOf(
        item("a", "Rent", ScheduleStatus.SCHEDULED, payee = "Landlord", account = "Checking", amount = -180_000),
        item("b", "StreamFlix", ScheduleStatus.MISSED, payee = "StreamFlix Inc", account = "Credit"),
        item("c", "Phone bill", ScheduleStatus.PAID, payee = "Telco", account = "Checking"),
        item("d", "Old gym", ScheduleStatus.COMPLETED, payee = "Gym", account = "Checking"),
        item("e", "Water", ScheduleStatus.DUE, payee = "Utility"),
        item("f", "Cable", ScheduleStatus.UPCOMING),
    )

    @Test
    fun groupsFollowTheSectionOrderAndHideCompletedByDefault() {
        val groups = ScheduleListPresentation.groups(items, "", showCompleted = false)
        assertEquals(
            listOf(
                ScheduleListSection.MISSED,
                ScheduleListSection.DUE,
                ScheduleListSection.UPCOMING,
                ScheduleListSection.PAID,
                ScheduleListSection.LATER,
            ),
            groups.map { it.section },
        )
        assertEquals(1, ScheduleListPresentation.completedCount(items))
        assertTrue(groups.flatMap { it.items }.none { it.id == "d" })
    }

    @Test
    fun showingCompletedAddsTheLastSection() {
        val groups = ScheduleListPresentation.groups(items, "", showCompleted = true)
        assertEquals(ScheduleListSection.COMPLETED, groups.last().section)
        assertEquals(listOf("d"), groups.last().items.map { it.id })
    }

    @Test
    fun searchMatchesNamePayeeAccountAndAmountIgnoringCase() {
        fun ids(query: String, completed: Boolean = false) =
            ScheduleListPresentation.groups(items, query, completed).flatMap { it.items }.map { it.id }.sorted()
        assertEquals(listOf("a"), ids("rent"))
        assertEquals(listOf("b"), ids("  STREAMFLIX INC "))
        assertEquals(listOf("a", "c"), ids("checking"))
        assertEquals(listOf("a"), ids("1800.00"))
        assertEquals(emptyList<String>(), ids("zzz"))
        // A completed schedule is still hidden by search unless completed ones are shown.
        assertEquals(listOf("a", "c"), ids("checking", completed = false))
        assertEquals(listOf("a", "c", "d"), ids("checking", completed = true))
    }
}
