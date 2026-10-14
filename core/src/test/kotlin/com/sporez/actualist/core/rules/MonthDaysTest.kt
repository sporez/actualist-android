package com.sporez.actualist.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthDaysTest {
    private fun day(value: Int) = SchedulePattern.DayOfMonth(value)
    private fun weekday(weekday: ScheduleWeekday, ordinal: Int) = SchedulePattern.Weekday(weekday, ordinal)

    @Test
    fun parsesDaysAndLastInMonthOrder() {
        assertEquals(listOf(day(1), day(15), day(-1)), MonthDays.parse("last, 15 1"))
        assertEquals(listOf(day(5)), MonthDays.parse(" 5, 5 "))
        assertEquals(listOf(day(-1)), MonthDays.parse("LAST"))
    }

    @Test
    fun parsesWeekdaysAfterDays() {
        assertEquals(
            listOf(day(15), weekday(ScheduleWeekday.FRIDAY, 1), weekday(ScheduleWeekday.FRIDAY, 3), weekday(ScheduleWeekday.MONDAY, -1)),
            MonthDays.parse("last monday, 3rd fri, 15, 1st Fri"),
        )
        assertEquals(listOf(weekday(ScheduleWeekday.THURSDAY, 2)), MonthDays.parse("2nd thu"))
    }

    @Test
    fun refusesWhatActualCannotStore() {
        assertNull(MonthDays.parse(""))
        assertNull(MonthDays.parse("0"))
        assertNull(MonthDays.parse("32"))
        assertNull(MonthDays.parse("1, fifteen"))
        assertNull(MonthDays.parse("-2"))
        assertNull(MonthDays.parse("fri"))
        assertNull(MonthDays.parse("6th fri"))
        assertNull(MonthDays.parse("2nd 3rd fri"))
        assertNull(MonthDays.parse("2nd fr"))
    }

    @Test
    fun formatsWhatItCanWriteBack() {
        assertEquals("1, 15, last", MonthDays.format(listOf(day(-1), day(15), day(1))))
        assertEquals("1, 1st fri, last mon", MonthDays.format(listOf(weekday(ScheduleWeekday.MONDAY, -1), day(1), weekday(ScheduleWeekday.FRIDAY, 1))))
        assertNull(MonthDays.format(emptyList()))
        // Actual stores -2 as "second to last"; this form cannot show it, so the rule stays view-only.
        assertNull(MonthDays.format(listOf(day(-2))))
        assertNull(MonthDays.format(listOf(weekday(ScheduleWeekday.FRIDAY, -2))))
    }

    @Test
    fun formattedTextParsesBackToTheSamePatterns() {
        val patterns = listOf(day(1), day(-1), weekday(ScheduleWeekday.WEDNESDAY, 2), weekday(ScheduleWeekday.SUNDAY, -1))
        assertEquals(patterns, MonthDays.parse(MonthDays.format(patterns)!!))
    }

    @Test
    fun parsedDaysDriveTheRecurrence() {
        val twiceMonthly = ScheduleRecurrence("2026-10-01", ScheduleFrequency.MONTHLY, patterns = MonthDays.parse("1, 15")!!)
        assertEquals("2026-10-15", twiceMonthly.nextOccurrence("2026-10-02"))
        assertEquals("2026-11-01", twiceMonthly.nextOccurrence("2026-10-16"))
        val midAndEnd = ScheduleRecurrence("2026-02-01", ScheduleFrequency.MONTHLY, patterns = MonthDays.parse("15, last")!!)
        assertEquals("2026-02-28", midAndEnd.nextOccurrence("2026-02-16"))
        assertEquals(MonthDays.format(twiceMonthly.patterns), "1, 15")
        // October 2026: Fridays are the 2nd, 9th, 16th, 23rd and 30th.
        val firstAndThirdFriday = ScheduleRecurrence("2026-10-01", ScheduleFrequency.MONTHLY, patterns = MonthDays.parse("1st fri, 3rd fri")!!)
        assertEquals("2026-10-02", firstAndThirdFriday.nextOccurrence("2026-10-01"))
        assertEquals("2026-10-16", firstAndThirdFriday.nextOccurrence("2026-10-03"))
        assertEquals("2026-11-06", firstAndThirdFriday.nextOccurrence("2026-10-17"))
    }
}
