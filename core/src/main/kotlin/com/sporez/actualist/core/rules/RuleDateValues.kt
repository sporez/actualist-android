package com.sporez.actualist.core.rules

import java.time.LocalDate

/** How exactly a rule's "date is" condition matches: a whole year, a month, or one day. */
enum class RuleDatePrecision { YEAR, MONTH, DAY }

/**
 * The text a rule date condition stores (`YYYY`, `YYYY-MM` or `YYYY-MM-DD`) and how a calendar pick
 * maps to it. Only the "is" operator keeps a year or month; every other operator needs a full day.
 */
object RuleDateValues {
    private val year = Regex("^\\d{4}$")
    private val month = Regex("^\\d{4}-\\d{2}$")
    private val day = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun precision(text: String): RuleDatePrecision? {
        val trimmed = text.trim()
        return when {
            year.matches(trimmed) -> RuleDatePrecision.YEAR
            month.matches(trimmed) -> RuleDatePrecision.MONTH
            day.matches(trimmed) -> RuleDatePrecision.DAY
            else -> null
        }
    }

    /** The first day the text covers, or null when it is not a real year, month or day. */
    fun anchor(text: String): LocalDate? {
        val trimmed = text.trim()
        val full = when (precision(trimmed)) {
            RuleDatePrecision.YEAR -> "$trimmed-01-01"
            RuleDatePrecision.MONTH -> "$trimmed-01"
            RuleDatePrecision.DAY -> trimmed
            null -> return null
        }
        return try {
            LocalDate.parse(full)
        } catch (e: Exception) {
            null
        }
    }

    fun format(date: LocalDate, precision: RuleDatePrecision): String {
        val yearText = date.year.toString().padStart(4, '0')
        val monthText = date.monthValue.toString().padStart(2, '0')
        return when (precision) {
            RuleDatePrecision.YEAR -> yearText
            RuleDatePrecision.MONTH -> "$yearText-$monthText"
            RuleDatePrecision.DAY -> date.toString()
        }
    }

    /** The same date at another precision; empty when [text] holds no date. */
    fun convert(text: String, precision: RuleDatePrecision): String = anchor(text)?.let { format(it, precision) } ?: ""

    /** Whether [precision] may be chosen for [op]: only "is" matches a year or a month. */
    fun allowsPartial(op: String): Boolean = op == "is"
}
