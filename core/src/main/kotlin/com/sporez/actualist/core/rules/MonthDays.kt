package com.sporez.actualist.core.rules

/**
 * Days of the month as typed in the schedule editor ("1, 15, last"), to and from Actual's
 * `{ type: 'day', value }` patterns. "last" is Actual's -1.
 */
object MonthDays {
    /** Null when any part is not a day 1–31 or "last", or nothing was typed. Duplicates collapse; order follows the month. */
    fun parse(text: String): List<SchedulePattern.DayOfMonth>? {
        val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val days = parts.map { part ->
            if (part.equals("last", ignoreCase = true)) -1 else part.toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        }
        return days.distinct().sortedWith(compareBy { if (it == -1) 32 else it }).map { SchedulePattern.DayOfMonth(it) }
    }

    /** Null unless every pattern is a day of the month this form can write back (1–31 or last). */
    fun format(patterns: List<SchedulePattern>): String? {
        if (patterns.isEmpty()) return null
        val days = patterns.map { (it as? SchedulePattern.DayOfMonth)?.day?.takeIf { day -> day == -1 || day in 1..31 } ?: return null }
        return days.distinct().sortedWith(compareBy { if (it == -1) 32 else it }).joinToString(", ") { if (it == -1) "last" else it.toString() }
    }
}
