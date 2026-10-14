package com.sporez.actualist.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthDaysTest {
    @Test
    fun parsesDaysAndLastInMonthOrder() {
        assertEquals(listOf(1, 15, -1), MonthDays.parse("last, 15 1")?.map { it.day })
        assertEquals(listOf(5), MonthDays.parse(" 5, 5 ")?.map { it.day })
        assertEquals(listOf(-1), MonthDays.parse("LAST")?.map { it.day })
    }

    @Test
    fun refusesWhatActualCannotStore() {
        assertNull(MonthDays.parse(""))
        assertNull(MonthDays.parse("0"))
        assertNull(MonthDays.parse("32"))
        assertNull(MonthDays.parse("1, fifteen"))
        assertNull(MonthDays.parse("-2"))
    }

    @Test
    fun formatsOnlyDayPatterns() {
        assertEquals("1, 15, last", MonthDays.format(listOf(SchedulePattern.DayOfMonth(-1), SchedulePattern.DayOfMonth(15), SchedulePattern.DayOfMonth(1))))
        assertNull(MonthDays.format(emptyList()))
        assertNull(MonthDays.format(listOf(SchedulePattern.DayOfMonth(1), SchedulePattern.Weekday(ScheduleWeekday.FRIDAY, 1))))
        // Actual stores -2 as "second to last"; this form cannot show it, so the rule stays view-only.
        assertNull(MonthDays.format(listOf(SchedulePattern.DayOfMonth(-2))))
    }

    @Test
    fun parsedDaysDriveTheRecurrence() {
        val twiceMonthly = ScheduleRecurrence("2026-10-01", ScheduleFrequency.MONTHLY, patterns = MonthDays.parse("1, 15")!!)
        assertEquals("2026-10-15", twiceMonthly.nextOccurrence("2026-10-02"))
        assertEquals("2026-11-01", twiceMonthly.nextOccurrence("2026-10-16"))
        val midAndEnd = ScheduleRecurrence("2026-02-01", ScheduleFrequency.MONTHLY, patterns = MonthDays.parse("15, last")!!)
        assertEquals("2026-02-28", midAndEnd.nextOccurrence("2026-02-16"))
        assertEquals(MonthDays.format(twiceMonthly.patterns), "1, 15")
    }
}
