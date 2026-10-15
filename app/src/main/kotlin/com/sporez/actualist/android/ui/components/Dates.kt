package com.sporez.actualist.android.ui.components

import android.text.format.DateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display date formats that follow the device region (Actualist's `ActualDateDisplay`). In en-US they
 * read "Wed, Oct 14, 2026" and "Oct 2026"; elsewhere the order and names change. Stored dates stay ISO.
 */
object Dates {
    private fun formatter(skeleton: String): DateTimeFormatter {
        val locale = Locale.getDefault()
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** Weekday, month, day and year: "Wed, Oct 14, 2026". */
    fun dayWithWeekday(): DateTimeFormatter = formatter("yMMMEd")

    /** Month and year: "Oct 2026". */
    fun monthYear(): DateTimeFormatter = formatter("yMMM")

    /** The standalone short month name: "Oct". */
    fun shortMonthName(month: Int): String =
        java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, Locale.getDefault())
}
