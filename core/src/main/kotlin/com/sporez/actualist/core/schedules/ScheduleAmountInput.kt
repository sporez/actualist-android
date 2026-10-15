package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.money.Money

/** How a schedule's amount matches: exactly, within Actual's 7.5% window, or between two amounts. */
enum class ScheduleAmountMode { EXACT, ABOUT, RANGE }

/**
 * The schedule editor's amount fields as Actual stores them: exact is `is`, about is `isapprox`
 * and a range is `isbetween` with `num1` below `num2`. The editor types magnitudes and picks a sign,
 * so a range is two magnitudes with one sign.
 */
object ScheduleAmountInput {
    private fun signed(magnitude: Long, outflow: Boolean): Long = Math.abs(magnitude).let { if (outflow) -it else it }

    /** The amount to write, or null while the fields are incomplete (zero amounts and one-point ranges are refused). */
    fun draft(mode: ScheduleAmountMode, outflow: Boolean, firstText: String, secondText: String): ScheduleAmountDraft? {
        val first = Money.parse(firstText)?.let { signed(it, outflow) } ?: return null
        return when (mode) {
            ScheduleAmountMode.EXACT -> if (first == 0L) null else ScheduleAmountDraft.Exact(first)
            ScheduleAmountMode.ABOUT -> if (first == 0L) null else ScheduleAmountDraft.Approximate(first)
            ScheduleAmountMode.RANGE -> {
                val second = Money.parse(secondText)?.let { signed(it, outflow) } ?: return null
                if (first == second) null else ScheduleAmountDraft.Range(minOf(first, second), maxOf(first, second))
            }
        }
    }

    fun mode(amount: ScheduleAmount): ScheduleAmountMode? = when (amount) {
        is ScheduleAmount.Exact -> ScheduleAmountMode.EXACT
        is ScheduleAmount.Approximate -> ScheduleAmountMode.ABOUT
        is ScheduleAmount.Range -> ScheduleAmountMode.RANGE
        ScheduleAmount.Unavailable -> null
    }

    /** Whether the editor can show [amount]: a range that crosses zero has no single sign. */
    fun isEditable(amount: ScheduleAmount): Boolean = when (amount) {
        is ScheduleAmount.Range -> (amount.lower >= 0 && amount.upper >= 0) || (amount.lower <= 0 && amount.upper <= 0)
        ScheduleAmount.Unavailable -> false
        else -> true
    }

    /** Whether the amount is an outflow (negative). */
    fun isOutflow(amount: ScheduleAmount): Boolean = when (amount) {
        is ScheduleAmount.Exact -> amount.amount < 0
        is ScheduleAmount.Approximate -> amount.amount < 0
        is ScheduleAmount.Range -> amount.lower < 0 || amount.upper < 0
        ScheduleAmount.Unavailable -> true
    }

    /** The field texts for [amount]: smaller magnitude first, as plain decimals. */
    fun texts(amount: ScheduleAmount): Pair<String, String> {
        fun text(value: Long) = Money.format(Math.abs(value), symbol = "", grouping = false)
        return when (amount) {
            is ScheduleAmount.Exact -> text(amount.amount) to ""
            is ScheduleAmount.Approximate -> text(amount.amount) to ""
            is ScheduleAmount.Range -> {
                val low = minOf(Math.abs(amount.lower), Math.abs(amount.upper))
                val high = maxOf(Math.abs(amount.lower), Math.abs(amount.upper))
                text(low) to text(high)
            }
            ScheduleAmount.Unavailable -> "" to ""
        }
    }
}
