package com.sporez.actualist.core.rules

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class ScheduleFrequency(val raw: String) {
    DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly");

    companion object {
        fun fromRaw(raw: String): ScheduleFrequency? = values().firstOrNull { it.raw == raw }
    }
}

/** [calendarWeekday] follows the Gregorian calendar numbering: Sunday is 1 and Saturday is 7. */
enum class ScheduleWeekday(val raw: String, val calendarWeekday: Int) {
    SUNDAY("SU", 1), MONDAY("MO", 2), TUESDAY("TU", 3), WEDNESDAY("WE", 4),
    THURSDAY("TH", 5), FRIDAY("FR", 6), SATURDAY("SA", 7);

    companion object {
        fun fromRaw(raw: String): ScheduleWeekday? = values().firstOrNull { it.raw == raw }
    }
}

sealed class SchedulePattern {
    data class DayOfMonth(val day: Int) : SchedulePattern()
    data class Weekday(val weekday: ScheduleWeekday, val ordinal: Int) : SchedulePattern()
}

sealed class ScheduleEnding {
    object Never : ScheduleEnding()
    data class AfterOccurrences(val count: Long) : ScheduleEnding()
    data class OnDate(val dayId: String) : ScheduleEnding()
}

enum class WeekendAdjustment(val raw: String) {
    BEFORE("before"), AFTER("after");

    companion object {
        fun fromRaw(raw: String): WeekendAdjustment? = values().firstOrNull { it.raw == raw }
    }
}

class ScheduleRecurrenceException(message: String) : Exception(message)

/**
 * Port of Actualist `ActualScheduleRecurrence`: Actual's date-only recurrence contract over
 * civil `YYYY-MM-DD` days. Only the occurrence search that rule conditions need is ported.
 */
class ScheduleRecurrence(
    val startDayId: String,
    val frequency: ScheduleFrequency,
    val interval: Long = 1,
    val patterns: List<SchedulePattern> = emptyList(),
    val skipWeekend: Boolean = false,
    val weekendAdjustment: WeekendAdjustment = WeekendAdjustment.AFTER,
    val ending: ScheduleEnding = ScheduleEnding.Never,
) {
    private val startDate: LocalDate

    init {
        startDate = parseDay(startDayId) ?: throw ScheduleRecurrenceException("invalid start date")
        if (interval <= 0) throw ScheduleRecurrenceException("invalid interval")
        if (frequency == ScheduleFrequency.WEEKLY && interval > Long.MAX_VALUE / 7) {
            throw ScheduleRecurrenceException("invalid interval")
        }
        if (!patterns.all { isValidPattern(it) }) throw ScheduleRecurrenceException("invalid pattern")
        when (ending) {
            is ScheduleEnding.Never -> Unit
            is ScheduleEnding.AfterOccurrences -> if (ending.count <= 0) throw ScheduleRecurrenceException("invalid ending")
            is ScheduleEnding.OnDate -> if (parseDay(ending.dayId) == null) throw ScheduleRecurrenceException("invalid ending")
        }
    }

    /**
     * The first occurrence on or after [targetDayId], or null when none remains. With
     * [applyWeekendAdjustment] the weekend move happens after the natural occurrence is chosen.
     */
    fun nextOccurrence(targetDayId: String, applyWeekendAdjustment: Boolean = true): String? {
        val target = parseDay(targetDayId) ?: throw ScheduleRecurrenceException("invalid start date")
        var cursor = if (target > startDate) target else startDate
        while (true) {
            val natural = nextNaturalOccurrence(cursor) ?: return null
            val naturalId = dayId(natural)
            if (isWithinEnding(naturalId)) {
                val display = if (applyWeekendAdjustment) skippedWeekend(natural) else natural
                return dayId(display)
            }
            val limit = ending
            if (limit !is ScheduleEnding.AfterOccurrences || !splitMonthlyRulesHaveRemaining(natural, limit.count)) return null
            cursor = natural.plusDays(1)
        }
    }

    fun skippedWeekend(date: LocalDate): LocalDate {
        if (!skipWeekend) return date
        val weekday = calendarWeekday(date)
        if (weekday != 1 && weekday != 7) return date
        val offset = when (weekendAdjustment) {
            WeekendAdjustment.AFTER -> if (weekday == 7) 2L else 1L
            WeekendAdjustment.BEFORE -> if (weekday == 7) -1L else -2L
        }
        return date.plusDays(offset)
    }

    private fun linearStep(): Long {
        if (frequency != ScheduleFrequency.WEEKLY) return interval
        try {
            return Math.multiplyExact(interval, 7L)
        } catch (e: ArithmeticException) {
            throw ScheduleRecurrenceException("invalid interval")
        }
    }

    private fun nextNaturalOccurrence(target: LocalDate): LocalDate? = try {
        when (frequency) {
            ScheduleFrequency.DAILY, ScheduleFrequency.WEEKLY -> {
                val distance = ChronoUnit.DAYS.between(startDate, target)
                val step = linearStep()
                val positiveDistance = maxOf(distance, 0L)
                val index = positiveDistance / step + (if (positiveDistance % step == 0L) 0L else 1L)
                val offset = try {
                    Math.multiplyExact(index, step)
                } catch (e: ArithmeticException) {
                    throw ScheduleRecurrenceException("occurrence limit exceeded")
                }
                startDate.plusDays(offset)
            }
            ScheduleFrequency.MONTHLY -> nextMonthlyOccurrence(target)
            ScheduleFrequency.YEARLY -> nextYearlyOccurrence(target)
        }
    } catch (e: DateTimeException) {
        null
    }

    private fun nextMonthlyOccurrence(target: LocalDate): LocalDate? {
        val startOrdinal = startDate.year.toLong() * 12 + startDate.monthValue - 1
        val targetOrdinal = target.year.toLong() * 12 + target.monthValue - 1
        val ordinalDistance = maxOf(targetOrdinal - startOrdinal, 0L)
        var monthIndex = (ordinalDistance / interval) * interval
        for (attempt in 0 until 120_000) {
            val ordinal = startOrdinal + monthIndex
            if (ordinal > 9_999L * 12 + 11) return null
            val year = (ordinal / 12).toInt()
            val month = (ordinal % 12).toInt() + 1
            val candidates = if (patterns.isEmpty()) {
                listOfNotNull(validDate(year, month, startDate.dayOfMonth))
            } else {
                patternDates(year, month, patterns)
            }
            val found = candidates.firstOrNull { it >= target && it >= startDate }
            if (found != null) return found
            monthIndex = try {
                Math.addExact(monthIndex, interval)
            } catch (e: ArithmeticException) {
                return null
            }
        }
        throw ScheduleRecurrenceException("occurrence limit exceeded")
    }

    private fun nextYearlyOccurrence(target: LocalDate): LocalDate? {
        val yearDistance = maxOf(target.year.toLong() - startDate.year, 0L)
        val yearOffset = (yearDistance / interval) * interval
        var year = startDate.year.toLong() + yearOffset
        for (attempt in 0 until 10_000) {
            if (year > 9_999) return null
            val candidate = validDate(year.toInt(), startDate.monthValue, startDate.dayOfMonth)
            if (candidate != null && candidate >= target) return candidate
            year = try {
                Math.addExact(year, interval)
            } catch (e: ArithmeticException) {
                return null
            }
        }
        throw ScheduleRecurrenceException("occurrence limit exceeded")
    }

    private fun dayPatterns(): List<SchedulePattern> = patterns.filter { it is SchedulePattern.DayOfMonth }

    private fun weekdayPatterns(): List<SchedulePattern> = patterns.filter { it is SchedulePattern.Weekday }

    private fun isWithinEnding(naturalDayId: String): Boolean {
        val limit = ending
        return when (limit) {
            is ScheduleEnding.Never -> true
            is ScheduleEnding.OnDate -> naturalDayId <= limit.dayId
            is ScheduleEnding.AfterOccurrences -> {
                val natural = parseDay(naturalDayId) ?: return false
                if (frequency == ScheduleFrequency.MONTHLY) {
                    val dayOnly = dayPatterns()
                    val weekdayOnly = weekdayPatterns()
                    if (dayOnly.isNotEmpty() && weekdayOnly.isNotEmpty()) {
                        val dayOrdinal = occurrenceOrdinal(natural, dayOnly)
                        val weekdayOrdinal = occurrenceOrdinal(natural, weekdayOnly)
                        return (dayOrdinal != null && dayOrdinal <= limit.count) ||
                            (weekdayOrdinal != null && weekdayOrdinal <= limit.count)
                    }
                }
                val ordinal = occurrenceOrdinal(natural, null) ?: return false
                ordinal <= limit.count
            }
        }
    }

    private fun occurrenceOrdinal(natural: LocalDate, monthlyPatterns: List<SchedulePattern>?): Long? {
        return when (frequency) {
            ScheduleFrequency.DAILY, ScheduleFrequency.WEEKLY -> {
                val distance = ChronoUnit.DAYS.between(startDate, natural)
                val step = linearStep()
                if (distance < 0 || distance % step != 0L) null else distance / step + 1
            }
            ScheduleFrequency.MONTHLY -> {
                val startOrdinal = startDate.year.toLong() * 12 + startDate.monthValue - 1
                val naturalOrdinal = natural.year.toLong() * 12 + natural.monthValue - 1
                val effective = monthlyPatterns ?: patterns
                var monthOrdinal = startOrdinal
                var occurrenceCount = 0L
                while (monthOrdinal <= naturalOrdinal) {
                    val year = (monthOrdinal / 12).toInt()
                    val month = (monthOrdinal % 12).toInt() + 1
                    val candidates = if (effective.isEmpty()) {
                        listOfNotNull(validDate(year, month, startDate.dayOfMonth))
                    } else {
                        patternDates(year, month, effective)
                    }
                    for (candidate in candidates) {
                        if (candidate >= startDate && candidate <= natural) {
                            occurrenceCount += 1
                            if (candidate == natural) return occurrenceCount
                        }
                    }
                    monthOrdinal = try {
                        Math.addExact(monthOrdinal, interval)
                    } catch (e: ArithmeticException) {
                        throw ScheduleRecurrenceException("occurrence limit exceeded")
                    }
                }
                null
            }
            ScheduleFrequency.YEARLY -> {
                var year = startDate.year.toLong()
                var occurrenceCount = 0L
                while (year <= natural.year) {
                    val candidate = validDate(year.toInt(), startDate.monthValue, startDate.dayOfMonth)
                    if (candidate != null && candidate >= startDate && candidate <= natural) {
                        occurrenceCount += 1
                        if (candidate == natural) return occurrenceCount
                    }
                    year = try {
                        Math.addExact(year, interval)
                    } catch (e: ArithmeticException) {
                        throw ScheduleRecurrenceException("occurrence limit exceeded")
                    }
                }
                null
            }
        }
    }

    private fun splitMonthlyRulesHaveRemaining(natural: LocalDate, count: Long): Boolean {
        if (frequency != ScheduleFrequency.MONTHLY) return false
        val dayOnly = dayPatterns()
        val weekdayOnly = weekdayPatterns()
        if (dayOnly.isEmpty() || weekdayOnly.isEmpty()) return false
        if (monthlyOccurrenceCount(natural, dayOnly, count) < count) return true
        return monthlyOccurrenceCount(natural, weekdayOnly, count) < count
    }

    private fun monthlyOccurrenceCount(natural: LocalDate, selected: List<SchedulePattern>, limit: Long): Long {
        var monthOrdinal = startDate.year.toLong() * 12 + startDate.monthValue - 1
        val naturalOrdinal = natural.year.toLong() * 12 + natural.monthValue - 1
        var occurrenceCount = 0L
        while (monthOrdinal <= naturalOrdinal) {
            val year = (monthOrdinal / 12).toInt()
            val month = (monthOrdinal % 12).toInt() + 1
            for (candidate in patternDates(year, month, selected)) {
                if (candidate >= startDate && candidate <= natural) {
                    occurrenceCount += 1
                    if (occurrenceCount >= limit) return occurrenceCount
                }
            }
            monthOrdinal = try {
                Math.addExact(monthOrdinal, interval)
            } catch (e: ArithmeticException) {
                throw ScheduleRecurrenceException("occurrence limit exceeded")
            }
        }
        return occurrenceCount
    }

    companion object {
        private fun isValidPattern(pattern: SchedulePattern): Boolean = when (pattern) {
            is SchedulePattern.DayOfMonth -> pattern.day != 0 && pattern.day in -31..31
            is SchedulePattern.Weekday -> pattern.ordinal != 0 && pattern.ordinal in -5..5
        }

        fun calendarWeekday(date: LocalDate): Int = date.dayOfWeek.value % 7 + 1

        private fun patternDates(year: Int, month: Int, patterns: List<SchedulePattern>): List<LocalDate> {
            val values = patterns.mapNotNull { pattern ->
                when (pattern) {
                    is SchedulePattern.DayOfMonth -> {
                        val days = YearMonth.of(year, month).lengthOfMonth()
                        val day = if (pattern.day > 0) pattern.day else days + pattern.day + 1
                        validDate(year, month, day)
                    }
                    is SchedulePattern.Weekday -> nthWeekday(pattern.weekday.calendarWeekday, pattern.ordinal, year, month)
                }
            }
            return values.distinct().sorted()
        }

        private fun nthWeekday(weekday: Int, ordinal: Int, year: Int, month: Int): LocalDate? {
            val first = validDate(year, month, 1) ?: return null
            val count = YearMonth.of(year, month).lengthOfMonth()
            if (ordinal > 0) {
                val offset = (weekday - calendarWeekday(first) + 7) % 7
                return validDate(year, month, 1 + offset + (ordinal - 1) * 7)
            }
            val last = validDate(year, month, count) ?: return null
            val offset = (calendarWeekday(last) - weekday + 7) % 7
            return validDate(year, month, count - offset + (ordinal + 1) * 7)
        }

        private fun validDate(year: Int, month: Int, day: Int): LocalDate? = try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }

        /** Strict `YYYY-MM-DD`, years 1 through 9999. */
        fun parseDay(dayId: String): LocalDate? {
            val parts = dayId.split("-")
            if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val day = parts[2].toIntOrNull() ?: return null
            if (year !in 1..9_999) return null
            return validDate(year, month, day)
        }

        fun dayId(date: LocalDate): String =
            String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)

        /** Port of `ScheduleRuleProjection.recurrence(from:)`: a schedule-style date condition value. */
        fun fromRule(value: RuleValue): ScheduleRecurrence {
            val obj = (value as? RuleValue.Obj)?.values ?: throw ScheduleRecurrenceException("invalid frequency")
            val start = obj["start"]?.string ?: throw ScheduleRecurrenceException("invalid frequency")
            val frequency = obj["frequency"]?.string?.lowercase()?.let { ScheduleFrequency.fromRaw(it) }
                ?: throw ScheduleRecurrenceException("invalid frequency")
            val rawInterval = obj["interval"]
            val interval = if (rawInterval != null) {
                val parsed = rawInterval.integer
                if (parsed == null || parsed <= 0) throw ScheduleRecurrenceException("invalid interval")
                parsed
            } else {
                1L
            }
            val rawSkip = obj["skipWeekend"]
            val skipWeekend = if (rawSkip != null) {
                rawSkip.bool ?: throw ScheduleRecurrenceException("invalid skip weekend")
            } else {
                false
            }
            val rawAdjustment = obj["weekendSolveMode"]
            val adjustmentText = when {
                rawAdjustment != null -> rawAdjustment.string ?: throw ScheduleRecurrenceException("invalid weekend adjustment")
                skipWeekend -> throw ScheduleRecurrenceException("invalid weekend adjustment")
                else -> "after"
            }
            val adjustment = WeekendAdjustment.fromRaw(adjustmentText)
                ?: throw ScheduleRecurrenceException("invalid weekend adjustment")
            return ScheduleRecurrence(
                startDayId = start,
                frequency = frequency,
                interval = interval,
                patterns = decodePatterns(obj["patterns"]),
                skipWeekend = skipWeekend,
                weekendAdjustment = adjustment,
                ending = decodeEnding(obj),
            )
        }

        private fun decodePatterns(value: RuleValue?): List<SchedulePattern> {
            if (value == null) return emptyList()
            val raw = (value as? RuleValue.Arr)?.values ?: throw ScheduleRecurrenceException("invalid pattern")
            return raw.map { entry ->
                val pattern = (entry as? RuleValue.Obj)?.values ?: throw ScheduleRecurrenceException("invalid pattern")
                val type = pattern["type"]?.string ?: throw ScheduleRecurrenceException("invalid pattern")
                val integer = pattern["value"]?.integer ?: throw ScheduleRecurrenceException("invalid pattern")
                if (integer < Int.MIN_VALUE || integer > Int.MAX_VALUE) throw ScheduleRecurrenceException("invalid pattern")
                if (type == "day") {
                    SchedulePattern.DayOfMonth(integer.toInt())
                } else {
                    val weekday = ScheduleWeekday.fromRaw(type) ?: throw ScheduleRecurrenceException("invalid pattern")
                    SchedulePattern.Weekday(weekday, integer.toInt())
                }
            }
        }

        private fun decodeEnding(obj: Map<String, RuleValue>): ScheduleEnding {
            val rawMode = obj["endMode"]
            val mode = if (rawMode != null) {
                rawMode.string ?: throw ScheduleRecurrenceException("invalid ending")
            } else {
                "never"
            }
            return when (mode) {
                "", "never" -> ScheduleEnding.Never
                "after_n_occurrences" -> {
                    val count = obj["endOccurrences"]?.integer ?: throw ScheduleRecurrenceException("invalid ending")
                    ScheduleEnding.AfterOccurrences(count)
                }
                "on_date" -> {
                    val day = obj["endDate"]?.string ?: throw ScheduleRecurrenceException("invalid ending")
                    ScheduleEnding.OnDate(day)
                }
                else -> throw ScheduleRecurrenceException("invalid ending")
            }
        }

        /**
         * A date condition whose value is a schedule-style recurrence. `is` means the recurrence
         * occurs on this day and `isapprox` means within two days either side. The weekend
         * adjustment is not applied on this path.
         */
        fun matchesCondition(dayText: String, operation: String, recurrence: RuleValue): Boolean {
            if (operation != "is" && operation != "isapprox") return false
            return try {
                val parsed = fromRule(recurrence)
                val date = parseDay(dayText) ?: return false
                val radius = if (operation == "isapprox") 2L else 0L
                val first = date.minusDays(radius)
                val last = date.plusDays(radius)
                val next = parsed.nextOccurrence(dayId(first), applyWeekendAdjustment = false) ?: return false
                next <= dayId(last)
            } catch (e: Exception) {
                false
            }
        }
    }
}
