package com.sporez.actualist.core.money

/**
 * Integer minor-unit money, as Actual stores it (cents for USD). Formatting and parsing
 * never go through floating point, so no amount is ever rounded silently.
 */
object Money {
    /**
     * `$1,234.56`, `-$0.05`, or the open budget's own style (`1.234,56 €`) through [MoneyFormat.current].
     * An explicit [symbol] (empty for none) or [decimals] overrides that part; `symbol = ""` with
     * `grouping = false` is the text of an edit field.
     */
    fun format(minorUnits: Long, symbol: String? = null, decimals: Int? = null, grouping: Boolean = true): String =
        MoneyFormat.current.format(minorUnits, symbol, decimals, grouping)

    /** Signed format with an explicit `+` for inflows. */
    fun formatSigned(minorUnits: Long, symbol: String? = null): String =
        if (minorUnits > 0) "+" + format(minorUnits, symbol) else format(minorUnits, symbol)

    /**
     * Parses user input such as `12`, `12.5`, `$1,234.56`, `-4.99` or `(4.99)` into minor units, with the
     * separators of [MoneyFormat.current]. More fraction digits than [decimals] (default: the budget's
     * scale) is an error, never a rounding.
     */
    fun parse(text: String, decimals: Int? = null): Long? = MoneyFormat.current.parse(text, decimals)

    /** Inputs above a trillion dollars are refused; they are typos, not budgets. */
    const val MAXIMUM_INPUT = 100_000_000_000_000L
}
