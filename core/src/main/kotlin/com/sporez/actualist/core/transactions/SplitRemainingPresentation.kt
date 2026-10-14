package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.money.Money

/**
 * Left/over copy for a split's remainder (Actualist's `SplitRemainingPresentation`). The remainder is
 * the parent minus the entries; Actual web flips it for expense parents so a negative remainder on a
 * spend reads as left, not over.
 */
object SplitRemainingPresentation {
    fun displayedCents(remaining: Long, parentSignedAmount: Long): Long = if (parentSignedAmount > 0) remaining else -remaining

    fun statusText(displayedCents: Long, format: (Long) -> String = { Money.format(it) }): String = when {
        displayedCents == 0L -> "${format(0)} Remaining"
        displayedCents > 0 -> "${format(displayedCents)} left"
        else -> "${format(-displayedCents)} over"
    }

    fun statusText(remaining: Long, parentSignedAmount: Long, format: (Long) -> String = { Money.format(it) }): String =
        statusText(displayedCents(remaining, parentSignedAmount), format)
}
