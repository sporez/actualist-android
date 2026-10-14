package com.sporez.actualist.core.importer

import java.time.DateTimeException
import java.time.LocalDate

/** Calendar-day helpers over Actual's compact `YYYYMMDD` day ids. No instant, no time zone. */
object ImportDays {
    /** The calendar day of a `YYYYMMDD` id, or null when it is not eight digits or not a real date. */
    fun date(dayId: String): LocalDate? {
        if (dayId.length != 8 || !dayId.all { it.isDigit() }) return null
        return try {
            LocalDate.of(dayId.substring(0, 4).toInt(), dayId.substring(4, 6).toInt(), dayId.substring(6, 8).toInt())
        } catch (error: DateTimeException) {
            null
        }
    }

    /** Whole days since 1970-01-01 of a `YYYYMMDD` id, or null when it does not parse. */
    fun epochDay(dayId: String): Long? = date(dayId)?.toEpochDay()

    fun dayId(date: LocalDate): String =
        String.format(java.util.Locale.ROOT, "%04d%02d%02d", date.year, date.monthValue, date.dayOfMonth)

    /** The ISO `YYYY-MM-DD` form of a `YYYYMMDD` id. */
    fun iso(dayId: String): String = "${dayId.take(4)}-${dayId.drop(4).take(2)}-${dayId.takeLast(2)}"

    /**
     * Calendar-based read window around downloaded days: the reconciler owns the exact plus or minus
     * 7-day check, this query bound only has to include every eligible row across month and year
     * boundaries and leap days.
     */
    fun window(candidateDayIds: List<String>): LongRange {
        val first = candidateDayIds.minOrNull()?.let(::date)
        val last = candidateDayIds.maxOrNull()?.let(::date)
        if (first == null || last == null) return 0L..99_999_999L
        return dayId(first.minusDays(7)).toLong()..dayId(last.plusDays(7)).toLong()
    }
}
