package com.sporez.actualist.core.rules

/**
 * Days of the month as typed in the schedule editor ("1, 15, last, 2nd fri, last mon"), to and
 * from Actual's `{ type: 'day' }` and weekday patterns. "last" is Actual's -1.
 */
object MonthDays {
    private val ordinals = mapOf("1st" to 1, "2nd" to 2, "3rd" to 3, "4th" to 4, "5th" to 5, "last" to -1)

    /** Null when any part is not a day 1–31, "last", or an ordinal and a weekday, or nothing was typed. Duplicates collapse. */
    fun parse(text: String): List<SchedulePattern>? {
        val patterns = mutableListOf<SchedulePattern>()
        for (part in text.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
            val words = part.split(' ').filter { it.isNotEmpty() }
            val weekday = words.lastOrNull()?.let(::weekday)
            if (weekday != null) {
                // "2nd fri", "last monday"
                val ordinal = words.singleOrNull { it != words.last() }?.let { ordinals[it] } ?: return null
                patterns += SchedulePattern.Weekday(weekday, ordinal)
            } else {
                // "1 15" is two days, as is "1, 15".
                for (word in words) {
                    patterns += SchedulePattern.DayOfMonth(if (word == "last") -1 else word.toIntOrNull()?.takeIf { it in 1..31 } ?: return null)
                }
            }
        }
        return patterns.distinct().sortedWith(order).takeIf { it.isNotEmpty() }
    }

    /** Null unless every pattern is one this form can write back (days 1–31 or last; weekdays 1st–5th or last). */
    fun format(patterns: List<SchedulePattern>): String? {
        if (patterns.isEmpty()) return null
        val parts = patterns.distinct().sortedWith(order).map { pattern ->
            when (pattern) {
                is SchedulePattern.DayOfMonth -> when (pattern.day) {
                    -1 -> "last"
                    in 1..31 -> pattern.day.toString()
                    else -> return null
                }
                is SchedulePattern.Weekday -> {
                    val ordinal = ordinals.entries.firstOrNull { it.value == pattern.ordinal }?.key ?: return null
                    "$ordinal ${pattern.weekday.name.take(3).lowercase()}"
                }
            }
        }
        return parts.joinToString(", ")
    }

    private fun weekday(word: String): ScheduleWeekday? =
        ScheduleWeekday.values().firstOrNull { word.length >= 3 && it.name.lowercase().startsWith(word) }

    /** Days in month order (last at the end), then weekdays by ordinal and day of the week. */
    private val order = compareBy<SchedulePattern>(
        { if (it is SchedulePattern.DayOfMonth) 0 else 1 },
        {
            when (it) {
                is SchedulePattern.DayOfMonth -> if (it.day == -1) 32 else it.day
                is SchedulePattern.Weekday -> if (it.ordinal == -1) 6 else it.ordinal
            }
        },
        { (it as? SchedulePattern.Weekday)?.weekday?.calendarWeekday ?: 0 },
    )
}
