package com.sporez.actualist.core.templates.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TemplateEditorTransitionsTest {
    private val today = LocalDate.of(2026, 9, 4)

    private fun target(): TemplateDraftEntry.DateTarget =
        TemplateDraftEntry.dateTarget(amount = 600.0, month = "2027-03", repeatInterval = null, annual = false, isSpend = false, today = today)
            as TemplateDraftEntry.DateTarget

    @Test
    fun repeatsToggleKeepsOrClearsTheInterval() {
        val repeating = TemplateDraftTransitions.setRepeats(target(), true)
        assertEquals(1, repeating.repeatInterval)
        val yearly = TemplateDraftTransitions.setAnnual(repeating.copy(repeatInterval = 2), true)
        assertTrue(yearly.annual)
        assertEquals(2, TemplateDraftTransitions.setRepeats(yearly, true).repeatInterval)
        val off = TemplateDraftTransitions.setRepeats(yearly, false)
        assertNull(off.repeatInterval)
        assertFalse(off.annual)
        // Annual only applies to a repeating target.
        assertFalse(TemplateDraftTransitions.setAnnual(target(), true).annual)
    }

    @Test
    fun earlySpendingStartsAtTheTargetMonthAndClears() {
        val on = TemplateDraftTransitions.setEarlySpending(target(), true)
        assertTrue(on.isSpend)
        assertEquals("2027-03", on.fromMonth)
        val moved = on.copy(fromMonth = "2027-01")
        assertEquals("2027-01", TemplateDraftTransitions.setEarlySpending(moved, true).fromMonth)
        val off = TemplateDraftTransitions.setEarlySpending(moved, false)
        assertFalse(off.isSpend)
        assertNull(off.fromMonth)
    }

    @Test
    fun weeklyLimitGetsAStartAndWeekdayMovesWithinTheWeek() {
        val limit = TemplateDraftEntry.balanceLimit(amount = 300.0) as TemplateDraftEntry.BalanceLimit
        val start = TemplateDraftTransitions.defaultWeeklyStart(listOf(target(), limit), today)
        assertEquals("2027-03-01", start)
        val weekly = TemplateDraftTransitions.setLimitPeriod(limit, TemplateLimitPeriod.WEEKLY, start)
        assertEquals("2027-03-01", weekly.start)
        // 2027-03-01 is a Monday (2); Friday is 6, Sunday is 1.
        assertEquals(2, TemplateDraftTransitions.limitWeekday(weekly, start))
        assertEquals("2027-03-05", TemplateDraftTransitions.setLimitWeekday(weekly, 6, start).start)
        assertEquals("2027-02-28", TemplateDraftTransitions.setLimitWeekday(weekly, 1, start).start)
        assertEquals("Friday", TemplateDraftTransitions.weekdayName(6))
        // Going monthly again keeps the start so switching back loses nothing.
        assertEquals("2027-03-01", TemplateDraftTransitions.setLimitPeriod(weekly, TemplateLimitPeriod.MONTHLY, start).start)
    }

    @Test
    fun defaultWeeklyStartFallsBackToTheCurrentMonth() {
        assertEquals("2026-09-01", TemplateDraftTransitions.defaultWeeklyStart(emptyList(), today))
    }

    @Test
    fun adjustmentModesKeepTheValueAndDirection() {
        val schedule = TemplateDraftEntry.schedule(name = "Rent", scheduleId = "s1")
        assertEquals(TemplateAdjustmentMode.NONE, TemplateDraftTransitions.adjustmentMode(schedule))
        val fixed = TemplateDraftTransitions.setAdjustmentMode(schedule, TemplateAdjustmentMode.FIXED)
        assertEquals(TemplateAdjustment.Fixed(10.0), fixed.modifierAdjustment)
        val percent = TemplateDraftTransitions.setAdjustmentMode(fixed, TemplateAdjustmentMode.PERCENT)
        assertEquals(TemplateAdjustment.Percent(10.0), percent.modifierAdjustment)
        val decrease = TemplateDraftTransitions.setAdjustmentDirection(percent, TemplateAdjustmentDirection.DECREASE)
        assertEquals(TemplateAdjustment.Percent(-10.0), decrease.modifierAdjustment)
        assertEquals(TemplateAdjustmentDirection.DECREASE, TemplateDraftTransitions.adjustmentDirection(decrease))
        val retyped = TemplateDraftTransitions.setAdjustmentValue(decrease, 25.0)
        assertEquals(TemplateAdjustment.Percent(-25.0), retyped.modifierAdjustment)
        assertNull(TemplateDraftTransitions.setAdjustmentMode(retyped, TemplateAdjustmentMode.NONE).modifierAdjustment)
        // Entries without a modifier ignore adjustments.
        val goal = TemplateDraftEntry.goal()
        assertEquals(goal, TemplateDraftTransitions.setAdjustmentMode(goal, TemplateAdjustmentMode.FIXED))
    }

    @Test
    fun lastMonthPercentageCannotUseAvailableFunds() {
        val entry = TemplateDraftEntry.percentage(percent = 10.0, sourceCategory = "available funds") as TemplateDraftEntry.Percentage
        assertEquals("", TemplateDraftTransitions.setPercentagePrevious(entry, true).sourceCategory)
        assertEquals("available funds", TemplateDraftTransitions.setPercentagePrevious(entry, false).sourceCategory)
    }

    @Test
    fun singletonKindsAreOfferedOnlyOnce() {
        val entries = listOf(TemplateDraftEntry.monthlyFixed(today = today), TemplateDraftEntry.goal())
        val addable = TemplateDraftTransitions.addableKinds(entries)
        assertFalse(TemplateEditorKind.GOAL in addable)
        assertTrue(TemplateEditorKind.MONTHLY_FIXED in addable)
        // The goal itself may keep its own kind when retyping.
        assertTrue(TemplateEditorKind.GOAL in TemplateDraftTransitions.typeChangeKinds(entries, 1))
        assertFalse(TemplateEditorKind.GOAL in TemplateDraftTransitions.typeChangeKinds(entries, 0))
    }
}
