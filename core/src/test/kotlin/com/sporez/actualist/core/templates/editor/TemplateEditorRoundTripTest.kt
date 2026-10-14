package com.sporez.actualist.core.templates.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What the editor authors must survive text to draft and back, as Actual's template parser reads it. */
class TemplateEditorRoundTripTest {
    private val today = LocalDate.of(2026, 9, 4)

    private fun roundTrip(entries: List<TemplateDraftEntry>): List<TemplateDraftEntry> {
        val json = TemplateDefinition.encode(entries)
        return TemplateDefinition.drafts(json, today)!!
    }

    @Test
    fun earlySpendingTargetRoundTrips() {
        val entries = listOf<TemplateDraftEntry>(
            TemplateDraftEntry.DateTarget(
                amount = 1200.0, month = "2027-03", priority = 2, repeatInterval = 1, annual = true,
                fromMonth = "2026-12", isSpend = true, description = "Trip",
            ),
        )
        assertEquals(entries, roundTrip(entries))
        assertTrue(TemplateDefinition.encode(entries).contains("\"type\":\"spend\""))
    }

    @Test
    fun repeatingTargetEveryNMonthsRoundTrips() {
        val entries = listOf<TemplateDraftEntry>(
            TemplateDraftEntry.DateTarget(
                amount = 90.0, month = "2026-12", priority = 1, repeatInterval = 3, annual = false,
                fromMonth = null, isSpend = false,
            ),
        )
        assertEquals(entries, roundTrip(entries))
    }

    @Test
    fun weeklyAndDailyLimitsWithRetainedFundsRoundTrip() {
        val weekly = listOf(
            TemplateDraftEntry.monthlyFixed(amount = 50.0, today = today),
            TemplateDraftEntry.balanceLimit(amount = 200.0, hold = true, period = TemplateLimitPeriod.WEEKLY, start = "2026-09-06"),
        )
        assertEquals(weekly, roundTrip(weekly))
        val daily = listOf(
            TemplateDraftEntry.monthlyFixed(amount = 50.0, today = today),
            TemplateDraftEntry.balanceLimit(amount = 5.0, period = TemplateLimitPeriod.DAILY),
            TemplateDraftEntry.refill(priority = 2),
        )
        assertEquals(daily, roundTrip(daily))
    }

    @Test
    fun scheduleModesAndAdjustmentsRoundTrip() {
        val entries = listOf(
            TemplateDraftEntry.schedule(name = "Rent", scheduleId = "sch-1", full = true, adjustment = TemplateAdjustment.Percent(-5.0)),
            TemplateDraftEntry.average(numMonths = 6, adjustment = TemplateAdjustment.Fixed(-20.0)),
        )
        assertEquals(entries, roundTrip(entries))
    }

    @Test
    fun weeklyPeriodicWithAnchorRoundTrips() {
        val entries = listOf<TemplateDraftEntry>(
            TemplateDraftEntry.MonthlyFixed(
                amount = 25.0, priority = 1, starting = "2026-09-02", cadence = TemplateCadence.WEEK, interval = 2,
            ),
            TemplateDraftEntry.MonthlyFixed(
                amount = 4.0, priority = 3, starting = "2026-09-01", cadence = TemplateCadence.DAY, interval = 1,
            ),
        )
        assertEquals(entries, roundTrip(entries))
    }

    @Test
    fun clearedNoteLeavesNoDescriptionInTheText() {
        val noted = TemplateDraftEntry.goal(amount = 500.0, description = "Emergency fund")
        assertTrue(TemplateDefinition.encode(listOf(noted)).contains("Emergency fund"))
        val cleared = noted.updatingDescription("")
        assertEquals(null, cleared.description)
        assertEquals(listOf(cleared), roundTrip(listOf(cleared)))
        assertTrue(!TemplateDefinition.encode(listOf(cleared)).contains("description"))
    }
}
