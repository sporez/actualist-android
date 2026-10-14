package com.sporez.actualist.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Integer minor-unit money, as Actual stores it (cents for USD). Formatting and parsing
 * never go through floating point, so no amount is ever rounded silently.
 */
object Money {
    /** `$1,234.56`, `-$0.05`. */
    fun format(minorUnits: Long, symbol: String = "$", decimals: Int = 2, grouping: Boolean = true): String {
        val negative = minorUnits < 0
        val magnitude = if (minorUnits == Long.MIN_VALUE) BigDecimal(minorUnits).negate() else BigDecimal(kotlin.math.abs(minorUnits))
        val scaled = magnitude.movePointLeft(decimals).setScale(decimals, RoundingMode.UNNECESSARY)
        val plain = scaled.toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = if (decimals > 0) "." + plain.substringAfter('.') else ""
        val grouped = if (grouping) whole.reversed().chunked(3).joinToString(",").reversed() else whole
        return (if (negative) "-" else "") + symbol + grouped + fraction
    }

    /** Signed format with an explicit `+` for inflows. */
    fun formatSigned(minorUnits: Long, symbol: String = "$"): String =
        if (minorUnits > 0) "+" + format(minorUnits, symbol) else format(minorUnits, symbol)

    /**
     * Parses user input such as `12`, `12.5`, `$1,234.56`, `-4.99` or `(4.99)` into minor
     * units. More fraction digits than [decimals] is an error, never a rounding.
     */
    fun parse(text: String, decimals: Int = 2): Long? {
        var cleaned = text.trim().replace(",", "").replace("$", "").replace(" ", "")
        var negative = false
        if (cleaned.startsWith("(") && cleaned.endsWith(")")) {
            negative = true
            cleaned = cleaned.substring(1, cleaned.length - 1)
        }
        if (cleaned.startsWith("-")) {
            negative = !negative
            cleaned = cleaned.substring(1)
        } else if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1)
        }
        if (cleaned.isEmpty() || !cleaned.matches(Regex("^\\d*(\\.\\d*)?$")) || cleaned == ".") return null
        val value = try {
            BigDecimal(if (cleaned.startsWith(".")) "0$cleaned" else cleaned)
        } catch (error: NumberFormatException) {
            return null
        }
        if (value.scale() > decimals) return null
        return try {
            val minor = value.movePointRight(decimals).longValueExact()
            if (minor > MAXIMUM_INPUT) null else if (negative) -minor else minor
        } catch (error: ArithmeticException) {
            null
        }
    }

    /** Inputs above a trillion dollars are refused; they are typos, not budgets. */
    const val MAXIMUM_INPUT = 100_000_000_000_000L
}
