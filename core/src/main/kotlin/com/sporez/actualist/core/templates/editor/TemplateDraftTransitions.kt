package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.templates.TemplateCalendar
import java.time.LocalDate

/** How a Schedule or Average entry adjusts its amount (`BudgetTemplateAdjustmentMode`). */
enum class TemplateAdjustmentMode(val title: String) {
    NONE("No adjustment"),
    FIXED("Fixed amount"),
    PERCENT("Percentage"),
}

/** Whether a percentage adjustment raises or lowers the amount (`BudgetTemplateAdjustmentDirection`). */
enum class TemplateAdjustmentDirection(val title: String) {
    INCREASE("Increase"),
    DECREASE("Decrease"),
}

/**
 * Per-entry editor transitions, ported from Actualist's `BudgetTemplateDraftEditor` and
 * `BudgetTemplateEditorCalendar`. Each returns a new entry; none touches unrelated fields.
 */
object TemplateDraftTransitions {
    const val ALL_INCOME = "all income"
    const val AVAILABLE_FUNDS = "available funds"

    // region Kinds

    /** Kinds that may be added next to [entries]: singleton kinds only when absent. */
    fun addableKinds(entries: List<TemplateDraftEntry>): List<TemplateEditorKind> {
        val present = entries.map { it.kind }.toSet()
        return TemplateEditorKind.values().filter { !it.isSingleton || it !in present }
    }

    /** Kinds entry [index] may become: singleton kinds only when no other entry holds them. */
    fun typeChangeKinds(entries: List<TemplateDraftEntry>, index: Int): List<TemplateEditorKind> {
        val others = entries.filterIndexed { i, _ -> i != index }.map { it.kind }.toSet()
        return TemplateEditorKind.values().filter { !it.isSingleton || it !in others }
    }

    // endregion

    // region Calendar

    /** Sunday is 1 and Saturday is 7, as Actualist's Gregorian calendar numbers weekdays. */
    fun weekday(dayId: String): Int? {
        val date = TemplateCalendar.validatedDate(dayId) ?: return null
        return date.dayOfWeek.value % 7 + 1
    }

    fun dayId(dayId: String, movingToWeekday: Int): String? {
        if (movingToWeekday !in 1..7) return null
        val date = TemplateCalendar.validatedDate(dayId) ?: return null
        val current = date.dayOfWeek.value % 7 + 1
        return TemplateCalendar.dayId(date.plusDays((movingToWeekday - current).toLong()))
    }

    fun weekdayName(weekday: Int): String = when (weekday) {
        1 -> "Sunday"
        2 -> "Monday"
        3 -> "Tuesday"
        4 -> "Wednesday"
        5 -> "Thursday"
        6 -> "Friday"
        7 -> "Saturday"
        else -> ""
    }

    /** The earliest periodic or target date among [entries], else the first day of [today]'s month. */
    fun defaultWeeklyStart(entries: List<TemplateDraftEntry>, today: LocalDate): String {
        val candidates = entries.mapNotNull { entry ->
            when (entry) {
                is TemplateDraftEntry.MonthlyFixed -> entry.starting.takeIf { TemplateCalendar.validatedDate(it) != null }
                is TemplateDraftEntry.DateTarget -> entry.month.takeIf { validMonth(it) }?.let { "$it-01" }
                else -> null
            }
        }
        return candidates.minOrNull() ?: TemplateDefinition.firstDayOfCurrentMonth(today)
    }

    fun validMonth(text: String): Boolean = TemplateCalendar.validatedDate("${text.trim()}-01") != null && text.trim().length == 7

    // endregion

    // region Save by Date

    fun setRepeats(entry: TemplateDraftEntry.DateTarget, repeats: Boolean): TemplateDraftEntry.DateTarget =
        if (repeats) entry.copy(repeatInterval = entry.repeatInterval ?: 1) else entry.copy(repeatInterval = null, annual = false)

    fun setAnnual(entry: TemplateDraftEntry.DateTarget, annual: Boolean): TemplateDraftEntry.DateTarget =
        if (entry.repeatInterval == null) entry else entry.copy(annual = annual)

    fun setEarlySpending(entry: TemplateDraftEntry.DateTarget, enabled: Boolean): TemplateDraftEntry.DateTarget =
        if (enabled) entry.copy(isSpend = true, fromMonth = entry.fromMonth ?: entry.month) else entry.copy(isSpend = false, fromMonth = null)

    // endregion

    // region Balance Limit

    fun setLimitPeriod(entry: TemplateDraftEntry.BalanceLimit, period: TemplateLimitPeriod, weeklyStart: String): TemplateDraftEntry.BalanceLimit {
        val start = if (period == TemplateLimitPeriod.WEEKLY && entry.start == null) weeklyStart else entry.start
        return entry.copy(period = period, start = start)
    }

    fun limitWeekday(entry: TemplateDraftEntry.BalanceLimit, fallbackStart: String): Int =
        weekday(entry.start ?: fallbackStart) ?: 1

    fun setLimitWeekday(entry: TemplateDraftEntry.BalanceLimit, weekday: Int, fallbackStart: String): TemplateDraftEntry.BalanceLimit {
        val start = entry.start?.takeIf { TemplateCalendar.validatedDate(it) != null } ?: fallbackStart
        val updated = dayId(start, weekday) ?: return entry
        return entry.copy(start = updated)
    }

    // endregion

    // region Adjustment

    fun adjustmentMode(entry: TemplateDraftEntry): TemplateAdjustmentMode = when (entry.modifierAdjustment) {
        null -> TemplateAdjustmentMode.NONE
        is TemplateAdjustment.Fixed -> TemplateAdjustmentMode.FIXED
        is TemplateAdjustment.Percent -> TemplateAdjustmentMode.PERCENT
    }

    fun adjustmentDirection(entry: TemplateDraftEntry): TemplateAdjustmentDirection {
        val adjustment = entry.modifierAdjustment
        return if (adjustment is TemplateAdjustment.Percent && adjustment.value < 0) {
            TemplateAdjustmentDirection.DECREASE
        } else {
            TemplateAdjustmentDirection.INCREASE
        }
    }

    fun setAdjustmentMode(entry: TemplateDraftEntry, mode: TemplateAdjustmentMode): TemplateDraftEntry {
        if (adjustmentMode(entry) == mode) return entry
        val current = entry.modifierAdjustment?.value
        val updated: TemplateAdjustment? = when (mode) {
            TemplateAdjustmentMode.NONE -> null
            TemplateAdjustmentMode.FIXED -> TemplateAdjustment.Fixed(current ?: 10.0)
            TemplateAdjustmentMode.PERCENT -> TemplateAdjustment.Percent(current ?: 10.0)
        }
        return entry.updatingModifierAdjustment(updated)
    }

    fun setAdjustmentDirection(entry: TemplateDraftEntry, direction: TemplateAdjustmentDirection): TemplateDraftEntry {
        val current = entry.modifierAdjustment as? TemplateAdjustment.Percent ?: return entry
        val magnitude = Math.abs(current.value)
        val signed = if (direction == TemplateAdjustmentDirection.DECREASE) -magnitude else magnitude
        return entry.updatingModifierAdjustment(TemplateAdjustment.Percent(signed))
    }

    /** Sets the adjustment's magnitude, keeping the percent direction (a fixed amount takes the sign typed). */
    fun setAdjustmentValue(entry: TemplateDraftEntry, value: Double): TemplateDraftEntry = when (val current = entry.modifierAdjustment) {
        is TemplateAdjustment.Fixed -> entry.updatingModifierAdjustment(TemplateAdjustment.Fixed(value))
        is TemplateAdjustment.Percent -> {
            val negative = current.value < 0
            entry.updatingModifierAdjustment(TemplateAdjustment.Percent(if (negative) -Math.abs(value) else Math.abs(value)))
        }
        null -> entry
    }

    // endregion

    // region Percentage and Schedule

    fun setPercentagePrevious(entry: TemplateDraftEntry.Percentage, previous: Boolean): TemplateDraftEntry.Percentage {
        val clearsFunds = previous && entry.sourceCategory.trim().lowercase() == AVAILABLE_FUNDS
        return entry.copy(previous = previous, sourceCategory = if (clearsFunds) "" else entry.sourceCategory)
    }

    fun setSchedule(entry: TemplateDraftEntry.Schedule, option: TemplateScheduleOption): TemplateDraftEntry.Schedule =
        entry.copy(scheduleId = option.id, name = option.name)

    // endregion
}
