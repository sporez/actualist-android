package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.ScheduleEnding
import com.sporez.actualist.core.rules.ScheduleFrequency
import com.sporez.actualist.core.rules.SchedulePattern
import com.sporez.actualist.core.rules.ScheduleRecurrence
import com.sporez.actualist.core.rules.ScheduleRecurrenceException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val LAST_OCCURRENCE_ITERATION_LIMIT = 20_000

/** A canonical text form of every recurrence field, used for equality and replay summaries. */
fun ScheduleRecurrence.signature(): String {
    val patternText = patterns.joinToString(",") { pattern ->
        when (pattern) {
            is SchedulePattern.DayOfMonth -> "day:${pattern.day}"
            is SchedulePattern.Weekday -> "${pattern.weekday.raw}:${pattern.ordinal}"
        }
    }
    val endText = when (val limit = ending) {
        is ScheduleEnding.Never -> "never"
        is ScheduleEnding.AfterOccurrences -> "after:${limit.count}"
        is ScheduleEnding.OnDate -> "on:${limit.dayId}"
    }
    return "[start=$startDayId,frequency=${frequency.raw},interval=$interval,patterns=[$patternText]," +
        "skipWeekend=$skipWeekend,adjustment=${weekendAdjustment.raw},ending=$endText]"
}

/**
 * The final occurrence of an ending recurrence, or null when it never ends or the end precedes
 * the first occurrence. Mirrors Actual's `getNextDate` fallback (`occurrences({ reverse: true,
 * take: 1 })`), including the weekend move applied to that date.
 */
fun ScheduleRecurrence.lastOccurrence(applyWeekendAdjustment: Boolean = true): String? {
    if (ending is ScheduleEnding.Never) return null
    val start = ScheduleRecurrence.parseDay(startDayId) ?: return null
    val natural: LocalDate? = when (frequency) {
        ScheduleFrequency.DAILY, ScheduleFrequency.WEEKLY -> lastLinearOccurrence(start)
        ScheduleFrequency.MONTHLY, ScheduleFrequency.YEARLY -> lastIteratedOccurrence()
    }
    if (natural == null) return null
    val display = if (applyWeekendAdjustment) skippedWeekend(natural) else natural
    return ScheduleRecurrence.dayId(display)
}

/**
 * Port of Actual's `getNextDateAfter` (at most seven attempts). A weekend move can map a later
 * occurrence back onto [afterDayId] or earlier, so the search restarts past each natural
 * occurrence until a displayed date is strictly later.
 */
fun ScheduleRecurrence.nextDateAfter(afterDayId: String): String? {
    val after = ScheduleRecurrence.parseDay(afterDayId) ?: throw ScheduleRecurrenceException("invalid start date")
    var start = after.minusDays(1)
    for (attempt in 0 until 7) {
        val startId = ScheduleRecurrence.dayId(start)
        val natural = occurrenceOrLast(startId, false) ?: return null
        if (natural < startId) return null
        val naturalDate = ScheduleRecurrence.parseDay(natural) ?: return null
        val next = occurrenceOrLast(startId, true)
        if (next != null && next > afterDayId) return next
        start = naturalDate.plusDays(1)
    }
    return null
}

/** `nextOccurrence` falling back to the final occurrence, like Actual's `getNextDate`. */
fun ScheduleRecurrence.occurrenceOrLast(fromDayId: String, adjustWeekend: Boolean): String? =
    nextOccurrence(fromDayId, adjustWeekend) ?: lastOccurrence(adjustWeekend)

private fun ScheduleRecurrence.linearStep(): Long = if (frequency == ScheduleFrequency.WEEKLY) interval * 7 else interval

private fun ScheduleRecurrence.lastLinearOccurrence(start: LocalDate): LocalDate? {
    val step = linearStep()
    val index: Long = when (val limit = ending) {
        is ScheduleEnding.Never -> return null
        is ScheduleEnding.AfterOccurrences -> limit.count - 1
        is ScheduleEnding.OnDate -> {
            val end = ScheduleRecurrence.parseDay(limit.dayId) ?: return null
            val distance = ChronoUnit.DAYS.between(start, end)
            if (distance < 0) return null
            distance / step
        }
    }
    val offset = try {
        Math.multiplyExact(index, step)
    } catch (e: ArithmeticException) {
        return null
    }
    return try {
        start.plusDays(offset)
    } catch (e: DateTimeException) {
        null
    }
}

/**
 * Walks natural occurrences so split monthly rules keep Actual's per-family counting. A bounded
 * walk beyond the limit reports no last occurrence.
 */
private fun ScheduleRecurrence.lastIteratedOccurrence(): LocalDate? {
    var cursor = startDayId
    var last: String? = null
    for (attempt in 0 until LAST_OCCURRENCE_ITERATION_LIMIT) {
        val next = nextOccurrence(cursor, false)
        if (next == null) return last?.let { ScheduleRecurrence.parseDay(it) }
        last = next
        val nextDate = ScheduleRecurrence.parseDay(next) ?: return null
        cursor = ScheduleRecurrence.dayId(nextDate.plusDays(1))
    }
    return null
}

/** Actualist `ScheduleUpcomingLength`: Actual's `getUpcomingDays`. */
object ScheduleUpcomingLength {
    fun days(value: String, today: String): Long {
        val todayDate = ScheduleRecurrence.parseDay(today) ?: return 7
        if (value == "currentMonth") return maxOf(todayDate.lengthOfMonth() - todayDate.dayOfMonth, 0).toLong()
        if (value == "oneMonth") return todayDate.lengthOfMonth().toLong()
        // Swift split(separator: "-", maxSplits: 1) with empty subsequences omitted.
        val dash = value.indexOf('-')
        val components = if (dash < 0) listOf(value) else listOf(value.substring(0, dash), value.substring(dash + 1))
        val parts = components.filter { it.isNotEmpty() }
        if (parts.size == 2) {
            val count = parts[0].toLongOrNull()
            if (count != null) {
                val safeCount = maxOf(count, 1L)
                when (parts[1]) {
                    "day" -> return safeCount
                    "week" -> return try {
                        Math.multiplyExact(safeCount, 7L)
                    } catch (e: ArithmeticException) {
                        7L
                    }
                    "month", "year" -> {
                        val months: Long = if (parts[1] == "year") {
                            try {
                                Math.multiplyExact(safeCount, 12L)
                            } catch (e: ArithmeticException) {
                                return 7L
                            }
                        } else {
                            safeCount
                        }
                        val monthStart = todayDate.withDayOfMonth(1)
                        val future = try {
                            monthStart.plusMonths(months)
                        } catch (e: DateTimeException) {
                            return 7L
                        }
                        val span = ChronoUnit.DAYS.between(monthStart, future)
                        return if (span == Long.MAX_VALUE) 7L else span + 1
                    }
                    else -> return 7L
                }
            }
        }
        return value.toLongOrNull() ?: 7L
    }
}

enum class ScheduleAdvancementAction {
    POST_SCHEDULED_DATE,
    ADVANCE_RECURRING,

    /** Paid recurring schedule that does not post: next date is recomputed from today. */
    RECOMPUTE_RECURRING,
    COMPLETE_ONE_TIME,
    STOP,
}

/**
 * Entry action for one schedule occurrence, matching pinned Actual `advanceSchedulesService`
 * before its posting loop. Dates are plain `YYYY-MM-DD` strings.
 */
object ScheduleOccurrencePlanner {
    fun action(
        postsTransaction: Boolean,
        isRecurring: Boolean,
        nextDate: String,
        status: ScheduleStatus,
        accountAvailable: Boolean,
        today: String,
    ): ScheduleAdvancementAction {
        if (!accountAvailable || nextDate.isEmpty()) return ScheduleAdvancementAction.STOP

        if (postsTransaction &&
            (status != ScheduleStatus.PAID || isRecurring) &&
            (status == ScheduleStatus.PAID || status == ScheduleStatus.DUE || status == ScheduleStatus.MISSED)
        ) {
            if (status == ScheduleStatus.PAID) {
                return if (nextDate == today) ScheduleAdvancementAction.STOP else ScheduleAdvancementAction.ADVANCE_RECURRING
            }
            return ScheduleAdvancementAction.POST_SCHEDULED_DATE
        }

        if (status == ScheduleStatus.PAID && isRecurring) return ScheduleAdvancementAction.RECOMPUTE_RECURRING

        if (status == ScheduleStatus.PAID && !isRecurring && nextDate < today) return ScheduleAdvancementAction.COMPLETE_ONE_TIME

        return ScheduleAdvancementAction.STOP
    }
}
