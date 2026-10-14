package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Month and date helpers in Actual's encodings: months are `YYYY-MM` ids or packed
 * `YYYYMM` integers, transaction dates are packed `YYYYMMDD` integers.
 */
object Months {
    private val validYears = 1900..9999

    fun monthInt(month: String): Int = month.replace("-", "").toIntOrNull() ?: 0

    fun monthId(packed: Int): String = id(packed / 100, packed % 100)

    fun id(year: Int, month: Int): String = "%04d-%02d".format(java.util.Locale.ROOT, year, month)

    fun shifted(packed: Int, offset: Int): Int {
        val year = packed / 100
        val month = packed % 100
        val zeroBased = year * 12 + (month - 1) + offset
        return (Math.floorDiv(zeroBased, 12)) * 100 + Math.floorMod(zeroBased, 12) + 1
    }

    fun shifted(month: String, offset: Int): String = monthId(shifted(monthInt(month), offset))

    fun next(packed: Int): Int = if (packed % 100 == 12) (packed / 100 + 1) * 100 + 1 else packed + 1

    fun previous(month: String): String = shifted(month, -1)

    /**
     * Canonical `YYYY-MM` for `YYYY-MM`, `YYYY/MM`, `YYYY.MM` (with trailing content) or a
     * leading `YYYYMM` digit run; null when out of range. Port of `YearMonth(parsing:)`.
     */
    fun canonical(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('-', '/', '.').filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val year = parts[0].toIntOrNull()
            val month = parts[1].toIntOrNull()
            if (year != null && month != null && year in validYears && month in 1..12) return id(year, month)
        }
        val digits = trimmed.takeWhile { it.isDigit() }
        if (digits.length < 6) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        return if (year in validYears && month in 1..12) id(year, month) else null
    }

    /** Packed `YYYYMM` for a `YYYY-MM` month, validated. */
    fun actualMonthValue(month: String): Int {
        val compact = month.trim().replace("-", "")
        val value = compact.toIntOrNull()
        if (compact.length != 6 || value == null || value / 100 !in validYears || value % 100 !in 1..12) {
            throw LocalFirstException.InvalidLocalWrite("invalid month")
        }
        return value
    }

    fun current(zone: ZoneId = ZoneId.systemDefault(), nowMillis: Long = System.currentTimeMillis()): String {
        val date = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return id(date.year, date.monthValue)
    }

    fun packedDate(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    fun packedDate(text: String): Int {
        val date = parseDate(text) ?: throw LocalFirstException.InvalidLocalWrite("invalid date")
        return packedDate(date)
    }

    fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (error: Exception) {
        null
    }

    fun dateFromPacked(packed: Long): LocalDate? {
        val year = (packed / 10_000).toInt()
        val month = ((packed / 100) % 100).toInt()
        val day = (packed % 100).toInt()
        return try {
            LocalDate.of(year, month, day)
        } catch (error: Exception) {
            null
        }
    }

    fun dateId(packed: Long): String = dateFromPacked(packed)?.toString() ?: packed.toString()
}

/** Actualist `normalizedMonthExpression`: `YYYY-MM` from a packed month, packed date or text. */
fun normalizedMonthSql(column: String): String {
    val text = "CAST($column AS TEXT)"
    return "CASE WHEN length($text) = 6 THEN substr($text, 1, 4) || '-' || substr($text, 5, 2) " +
        "WHEN length($text) = 8 THEN substr($text, 1, 4) || '-' || substr($text, 5, 2) " +
        "ELSE substr($text, 1, 7) END"
}

/** Actualist `normalizedDateExpression`: `YYYY-MM-DD` from a packed date or text. */
fun normalizedDateSql(column: String): String {
    val text = "CAST($column AS TEXT)"
    return "CASE WHEN length($text) = 8 THEN substr($text, 1, 4) || '-' || substr($text, 5, 2) || '-' || substr($text, 7, 2) " +
        "ELSE $text END"
}
