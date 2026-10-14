package com.sporez.actualist.core.templates

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.budget.Months
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Month and date arithmetic for template math, as Actualist `BudgetTemplateCalendar`. Months are
 * packed `YYYYMM` integers; days are `LocalDate`s (the civil `YYYY-MM-DD` day Actual works in).
 */
object TemplateCalendar {
    val year = 1..9_999
    val periodInterval = 1..1_200

    private fun outOfRange(): Nothing = throw UnsupportedTemplateException("template month is outside the supported range")

    fun validMonth(month: String?): Boolean {
        if (month == null) return false
        return try {
            parseMonth(month)
            true
        } catch (error: UnsupportedTemplateException) {
            false
        }
    }

    fun parseMonth(month: String): Int {
        val compact = month.trim().replace("-", "")
        val value = if (compact.length == 6) compact.toIntOrNull() else null
        if (value == null) throw UnsupportedTemplateException("invalid by template")
        return validatedMonth(value)
    }

    fun validatedMonth(month: Int): Int {
        val y = month / 100
        val m = month % 100
        if (y !in year || m !in 1..12) outOfRange()
        return month
    }

    private fun monthOrdinal(month: Int): Int {
        val valid = validatedMonth(month)
        return (valid / 100 - 1) * 12 + (valid % 100 - 1)
    }

    fun monthDistance(from: Int, to: Int): Int = monthOrdinal(to) - monthOrdinal(from)

    fun shiftedMonth(month: Int, offset: Int): Int {
        val shifted = monthOrdinal(month).toLong() + offset.toLong()
        if (shifted < 0 || shifted >= year.last.toLong() * 12) outOfRange()
        val y = (shifted / 12 + 1).toInt()
        val m = (shifted % 12 + 1).toInt()
        return y * 100 + m
    }

    fun monthId(month: Int): String = Months.monthId(month)

    fun daysInMonth(monthValue: Int): Int {
        validatedMonth(monthValue)
        return YearMonth.of(monthValue / 100, monthValue % 100).lengthOfMonth()
    }

    fun dayId(date: LocalDate): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)

    fun validatedDate(text: String): LocalDate? {
        val normalized = text.trim()
        val parts = normalized.split('-').filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (y !in year) return null
        val date = try {
            LocalDate.of(y, m, d)
        } catch (error: DateTimeException) {
            return null
        }
        return if (dayId(date) == normalized) date else null
    }

    fun monthStartDate(monthValue: Int): LocalDate {
        val text = monthId(validatedMonth(monthValue)) + "-01"
        return validatedDate(text) ?: throw UnsupportedTemplateException("periodic month")
    }

    fun nextMonthStartDate(monthValue: Int): LocalDate = monthStartDate(monthValue).plusMonths(1)

    /** Foundation's whole-month difference: the largest n with `from + n months <= to`. */
    fun wholeMonthsBetween(from: LocalDate, to: LocalDate): Int {
        var n = ChronoUnit.MONTHS.between(from, to).toInt()
        while (!from.plusMonths((n + 1).toLong()).isAfter(to)) n++
        while (n > 0 && from.plusMonths(n.toLong()).isAfter(to)) n--
        return n
    }

    fun weeklyLimitOccurrenceCount(startDayId: String, monthValue: Int): Int {
        val start = validatedDate(startDayId)
            ?: throw UnsupportedTemplateException("weekly limit requires a start date (YYYY-MM-DD)")
        val monthStart = monthStartDate(monthValue)
        val nextMonthStart = nextMonthStartDate(monthValue)
        val first = if (!start.isBefore(monthStart)) {
            start
        } else {
            val daysToTarget = ChronoUnit.DAYS.between(start, monthStart)
            val index = (daysToTarget + 6) / 7
            start.plusDays(index * 7)
        }
        if (!first.isBefore(nextMonthStart)) return 0
        val daysToEnd = ChronoUnit.DAYS.between(first, nextMonthStart)
        if (daysToEnd <= 0) return 0
        return ((daysToEnd - 1) / 7 + 1).toInt()
    }

    fun shiftedPeriodicDate(startDay: String, amount: Int, period: String): String {
        val date = if (amount in periodInterval) validatedDate(startDay) else null
        if (date == null) throw UnsupportedTemplateException("periodic start date")
        val shifted = try {
            when (period) {
                "day" -> date.plusDays(amount.toLong())
                "week" -> date.plusDays(amount.toLong() * 7)
                "month" -> date.plusMonths(amount.toLong())
                "year" -> date.plusYears(amount.toLong())
                else -> throw UnsupportedTemplateException("periodic $period")
            }
        } catch (error: DateTimeException) {
            throw UnsupportedTemplateException("periodic date")
        }
        val shiftedId = dayId(shifted)
        if (validatedDate(shiftedId) == null || !shifted.isAfter(date)) throw UnsupportedTemplateException("periodic date")
        return shiftedId
    }

    fun periodicOccurrenceCount(
        start: LocalDate,
        monthStart: LocalDate,
        nextMonthStart: LocalDate,
        interval: Int,
        period: String,
    ): Int {
        if (interval !in periodInterval) throw UnsupportedTemplateException("periodic interval")
        if (!start.isBefore(nextMonthStart)) return 0
        when (period) {
            "day", "week" -> {
                val step = if (period == "week") interval.toLong() * 7 else interval.toLong()
                val firstIndex = if (start.isBefore(monthStart)) {
                    val daysToMonth = ChronoUnit.DAYS.between(start, monthStart)
                    (daysToMonth + step - 1) / step
                } else {
                    0L
                }
                val daysToEnd = ChronoUnit.DAYS.between(start, nextMonthStart)
                if (daysToEnd <= 0) return 0
                val lastIndex = (daysToEnd - 1) / step
                if (lastIndex < firstIndex) return 0
                return (lastIndex - firstIndex + 1).toInt()
            }
            "month", "year" -> {
                val firstIndex = firstCalendarOccurrenceIndex(monthStart, start, interval, period)
                val occurrence = periodicOccurrenceDate(start, firstIndex, interval, period)
                return if (occurrence.isBefore(nextMonthStart)) 1 else 0
            }
            else -> throw UnsupportedTemplateException("periodic $period")
        }
    }

    private fun firstCalendarOccurrenceIndex(target: LocalDate, start: LocalDate, interval: Int, period: String): Long {
        if (!start.isBefore(target)) return 0
        val distance = if (period == "year") {
            (target.year - start.year).toLong()
        } else {
            (target.year - start.year).toLong() * 12 + (target.monthValue - start.monthValue)
        }
        var index = maxOf(0L, distance / interval)
        var occurrence = periodicOccurrenceDate(start, index, interval, period)
        if (occurrence.isBefore(target)) {
            index += 1
            occurrence = periodicOccurrenceDate(start, index, interval, period)
        }
        if (occurrence.isBefore(target)) throw UnsupportedTemplateException("periodic date")
        return index
    }

    private fun periodicOccurrenceDate(start: LocalDate, index: Long, interval: Int, period: String): LocalDate {
        val amount = Math.multiplyExact(interval.toLong(), index)
        val occurrence = try {
            if (period == "year") start.plusYears(amount) else start.plusMonths(amount)
        } catch (error: DateTimeException) {
            throw UnsupportedTemplateException("periodic date")
        }
        if (validatedDate(dayId(occurrence)) == null) throw UnsupportedTemplateException("periodic date")
        return occurrence
    }
}

/** Checked 64-bit arithmetic for template amounts, throwing the project's numeric-range error. */
internal object TemplateMath {
    fun add(lhs: Long, rhs: Long): Long = try {
        Math.addExact(lhs, rhs)
    } catch (error: ArithmeticException) {
        throw LocalFirstException.NumericValueOutOfRange()
    }

    fun subtract(lhs: Long, rhs: Long): Long = try {
        Math.subtractExact(lhs, rhs)
    } catch (error: ArithmeticException) {
        throw LocalFirstException.NumericValueOutOfRange()
    }

    fun multiply(lhs: Long, rhs: Long): Long = try {
        Math.multiplyExact(lhs, rhs)
    } catch (error: ArithmeticException) {
        throw LocalFirstException.NumericValueOutOfRange()
    }

    /** `abs(Long.MIN_VALUE)` overflows; reject it instead. */
    fun magnitude(value: Long): Long {
        if (value == Long.MIN_VALUE) throw LocalFirstException.NumericValueOutOfRange()
        return Math.abs(value)
    }

    /** Actual's `Math.round`: ties toward +Infinity, i.e. floor(x + 0.5). */
    fun actualRound(amount: Double): Long {
        if (!amount.isFinite() || amount < -9.223372036854775808E18 || amount >= 9.223372036854775808E18) {
            throw LocalFirstException.NumericValueOutOfRange()
        }
        val rounded = Math.floor(amount + 0.5)
        if (rounded < -9.223372036854775808E18 || rounded >= 9.223372036854775808E18) {
            throw LocalFirstException.NumericValueOutOfRange()
        }
        return rounded.toLong()
    }
}
