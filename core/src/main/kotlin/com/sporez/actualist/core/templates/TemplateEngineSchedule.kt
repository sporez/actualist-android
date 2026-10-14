package com.sporez.actualist.core.templates

import com.sporez.actualist.core.templates.TemplateEngine.Category
import com.sporez.actualist.core.templates.TemplateEngine.ResolvedSchedule
import java.time.LocalDate

// region Schedule

internal fun TemplateEngine.computeScheduleAmount(
    entries: List<TemplateEntry>,
    monthValue: Int,
    category: Category,
    alreadyBudgeted: Long,
): Long {
    val instances = scheduleInstances(entries, monthValue, category)
    val payMonthOf = instances.filter { isPayMonthOf(it) }
    val sinking = instances.filter { !isPayMonthOf(it) }.sortedBy { it.nextDate }

    var totalPayMonthOf = 0L
    for (schedule in payMonthOf) totalPayMonthOf = TemplateMath.add(totalPayMonthOf, schedule.monthlyRepeatingTarget)
    var totalSinking = 0L
    for (schedule in sinking) totalSinking = TemplateMath.add(totalSinking, schedule.monthlyRepeatingTarget)
    val totalRequired = TemplateMath.add(totalSinking, totalPayMonthOf)
    var totalSinkingBase = 0.0
    for (schedule in sinking) totalSinkingBase += monthlyBaseContribution(schedule)
    val balance = TemplateMath.add(category.fromLastMonth, alreadyBudgeted)
    val lastMonthGoal = category.lastMonthGoal
    val subMonthlyCount = instances.count { isSubMonthly(it) }

    if (balance >= totalRequired ||
        (lastMonthGoal < totalRequired && lastMonthGoal != 0L && balance >= lastMonthGoal && subMonthlyCount > 0)
    ) {
        return TemplateMath.actualRound(totalPayMonthOf.toDouble() + totalSinkingBase)
    }

    val sinkingContribution = sinkingContributionTotal(sinking, category.fromLastMonth)
    if (sinking.isEmpty()) {
        return TemplateMath.subtract(
            TemplateMath.actualRound(totalPayMonthOf.toDouble() + sinkingContribution),
            category.fromLastMonth,
        )
    }
    return TemplateMath.actualRound(totalPayMonthOf.toDouble() + sinkingContribution)
}

private fun scheduleInstances(entries: List<TemplateEntry>, monthValue: Int, category: Category): List<ResolvedSchedule> {
    val instances = mutableListOf<ResolvedSchedule>()
    for (entry in entries) {
        if (entry.type != "schedule") continue
        val key = entry.scheduleLookupKey
        val resolved = (if (key != null) category.resolvedSchedules[key] else null)
            ?: throw UnsupportedTemplateException(entry.missingScheduleReason)
        if (resolved.completed || resolved.monthsUntil < 0) continue
        var instance = resolved
        if (instance.isRepeating && !instance.completed) instance = withMonthlyRepeatingTarget(instance, monthValue)
        instances.add(instance)
    }
    return instances
}

private fun withMonthlyRepeatingTarget(schedule: ResolvedSchedule, monthValue: Int): ResolvedSchedule {
    val recurrence = schedule.recurrence ?: return schedule
    val monthStart = TemplateCalendar.monthStartDate(monthValue)
    val firstId = recurrence.nextOccurrence(TemplateCalendar.dayId(monthStart), false) ?: return schedule
    var cursor = TemplateCalendar.validatedDate(firstId) ?: throw UnsupportedTemplateException("schedule recurrence")

    fun displayDate(date: LocalDate): LocalDate = if (recurrence.skipWeekend) recurrence.skippedWeekend(date) else date

    // Actual totals repeating occurrences through the month containing the schedule's next
    // occurrence, not merely through the budget month. A future yearly bill therefore keeps one
    // target for sinking-fund math.
    val firstDisplayDate = displayDate(cursor)
    val occurrenceMonthEnd = firstDisplayDate.withDayOfMonth(1).plusMonths(1)
    val sign = if (schedule.monthlyRepeatingTarget >= 0) 1L else -1L
    val magnitude = TemplateMath.magnitude(schedule.amount)
    var total = 0L
    var guardCount = 0
    while (true) {
        val display = displayDate(cursor)
        if (!display.isBefore(occurrenceMonthEnd)) break
        total = TemplateMath.add(total, magnitude)
        val next = cursor.plusDays(1)
        val advancedId = recurrence.nextOccurrence(TemplateCalendar.dayId(next), false) ?: break
        val advanced = TemplateCalendar.validatedDate(advancedId) ?: break
        if (advanced == cursor) break
        cursor = advanced
        guardCount += 1
        if (guardCount > 400) throw UnsupportedTemplateException("schedule recurrence")
    }
    return schedule.withMonthlyRepeatingTarget(sign * total)
}

private fun isPayMonthOf(schedule: ResolvedSchedule): Boolean {
    if (schedule.full) return true
    val frequency = schedule.frequency
    if ((frequency == "monthly" || frequency == null) && schedule.interval == 1L && schedule.monthsUntil == 0) return true
    if (frequency == "weekly" && schedule.interval <= 4) return true
    if (frequency == "daily" && schedule.interval <= 31) return true
    return false
}

private fun isSubMonthly(schedule: ResolvedSchedule): Boolean = schedule.frequency == "weekly" || schedule.frequency == "daily"

private fun monthlyBaseContribution(schedule: ResolvedSchedule): Double {
    val target = schedule.monthlyRepeatingTarget.toDouble()
    val interval = maxOf(schedule.interval, 1L).toDouble()
    return when (schedule.frequency) {
        "yearly" -> target / interval / 12
        "monthly" -> target / interval
        "weekly" -> target / maxOf(weeklyIntervalMonths(schedule), 1).toDouble()
        "daily" -> target / maxOf(dailyIntervalMonths(schedule), 1).toDouble()
        else -> target / interval
    }
}

private fun weeklyIntervalMonths(schedule: ResolvedSchedule): Int {
    val intervalDays = try {
        Math.multiplyExact(schedule.interval, 7L)
    } catch (error: ArithmeticException) {
        return 1
    }
    val date = TemplateCalendar.validatedDate(schedule.nextDate) ?: return 1
    val previous = try {
        date.minusDays(intervalDays)
    } catch (error: java.time.DateTimeException) {
        return 1
    }
    val months = TemplateCalendar.wholeMonthsBetween(previous, date)
    return if (months == 0) 1 else months
}

private fun dailyIntervalMonths(schedule: ResolvedSchedule): Int {
    if (schedule.interval == Long.MIN_VALUE) return 1
    val date = TemplateCalendar.validatedDate(schedule.nextDate) ?: return 1
    val previous = try {
        date.minusDays(schedule.interval)
    } catch (error: java.time.DateTimeException) {
        return 1
    }
    val months = TemplateCalendar.wholeMonthsBetween(previous, date)
    return if (months == 0) 1 else months
}

private fun sinkingContributionTotal(schedules: List<ResolvedSchedule>, lastMonthBalance: Long): Double {
    var remainder = 0L
    var total = 0.0
    for ((index, schedule) in schedules.withIndex()) {
        remainder = TemplateMath.subtract(schedule.monthlyRepeatingTarget, if (index == 0) lastMonthBalance else remainder)
        if (remainder >= 0) {
            total += remainder.toDouble() / (schedule.monthsUntil + 1).toDouble()
            remainder = 0
        } else {
            remainder = TemplateMath.magnitude(remainder)
        }
    }
    return total
}

// endregion

// region Spend

internal fun TemplateEngine.computeSpendAmount(entry: TemplateEntry, monthValue: Int, category: Category): Long {
    val amount = entry.amount
    val toMonthId = entry.month
    val fromMonthId = entry.fromMonth
    val parsedTo = if (toMonthId != null) tryParseMonth(toMonthId) else null
    val parsedFrom = if (fromMonthId != null) tryParseMonth(fromMonthId) else null
    if (amount == null || parsedTo == null || parsedFrom == null) throw UnsupportedTemplateException("invalid spend template")
    var toMonth: Int = parsedTo
    var fromMonth: Int = parsedFrom

    val repeatPeriod = repeatPeriodMonths(entry, "invalid spend repeat interval")

    var monthsRemaining = TemplateCalendar.monthDistance(monthValue, toMonth)
    if (repeatPeriod != null && monthsRemaining < 0) {
        while (monthsRemaining < 0) {
            toMonth = TemplateCalendar.shiftedMonth(toMonth, repeatPeriod)
            fromMonth = TemplateCalendar.shiftedMonth(fromMonth, repeatPeriod)
            monthsRemaining = TemplateCalendar.monthDistance(monthValue, toMonth)
        }
    }
    if (monthsRemaining < 0) return 0L

    var alreadyBudgeted = category.fromLastMonth
    var cursor = fromMonth
    var isFirstMonth = true
    while (TemplateCalendar.monthDistance(cursor, monthValue) > 0) {
        if (isFirstMonth) {
            val leftover = category.leftoverByMonth[cursor] ?: 0L
            val spent = category.spentByMonth[cursor] ?: 0L
            alreadyBudgeted = TemplateMath.subtract(leftover, spent)
            isFirstMonth = false
        } else {
            alreadyBudgeted = TemplateMath.add(alreadyBudgeted, category.budgetedByMonth[cursor] ?: 0L)
        }
        cursor = TemplateCalendar.shiftedMonth(cursor, 1)
    }

    val target = amountToMinorUnits(amount)
    return TemplateMath.actualRound(
        TemplateMath.subtract(target, alreadyBudgeted).toDouble() / (monthsRemaining + 1).toDouble(),
    )
}

private fun tryParseMonth(text: String): Int? = try {
    TemplateCalendar.parseMonth(text)
} catch (error: UnsupportedTemplateException) {
    null
}

// endregion

// region Average

internal fun TemplateEngine.computeAverageAmount(
    entry: TemplateEntry,
    monthValue: Int,
    currentMonthValue: Int,
    category: Category,
): Long {
    val numMonths = entry.numMonths
    if (numMonths == null || numMonths !in TemplateEngine.Bounds.numMonths) throw UnsupportedTemplateException("average")

    val months = averageHistoryMonths(monthValue, currentMonthValue, numMonths, category.firstRelevantMonth)
    if (months.isEmpty()) return 0L

    var sum = 0L
    for (month in months) sum = TemplateMath.add(sum, category.activityByMonth[month] ?: 0L)

    // Actual `getCategoryAverage` rounds the signed mean, then `runAverage` flips only a negative
    // (spending) mean into a budget need.
    var average = TemplateMath.actualRound(sum.toDouble() / months.size.toDouble()).toDouble()
    if (average < 0) average *= -1

    val adjustment = entry.adjustment
    val adjustmentType = entry.adjustmentType
    if (adjustment != null && adjustmentType != null) {
        when (adjustmentType) {
            "percent" -> average = (1 + adjustment / 100) * average
            "fixed" -> average += amountToMinorUnits(adjustment).toDouble()
            else -> Unit
        }
    }
    return TemplateMath.actualRound(average)
}

internal fun averageHistoryMonths(targetMonth: Int, currentMonth: Int, maxMonths: Int, firstRelevantMonth: Int?): List<Int> {
    val startMonth = averageStartMonth(targetMonth, currentMonth)
    // Actual caps first-activity at the start month. History that only exists after that date is
    // treated as no bound and the full window is used.
    val historyStart = if (firstRelevantMonth != null && firstRelevantMonth <= startMonth) firstRelevantMonth else null

    val months = mutableListOf<Int>()
    var cursor = startMonth
    for (step in 0 until maxMonths) {
        if (historyStart != null && cursor < historyStart) break
        months.add(cursor)
        cursor = TemplateCalendar.shiftedMonth(cursor, -1)
    }
    return months
}

internal fun averageStartMonth(targetMonth: Int, currentMonth: Int): Int {
    val previousMonth = TemplateCalendar.shiftedMonth(targetMonth, -1)
    if (previousMonth >= currentMonth) return TemplateCalendar.shiftedMonth(currentMonth, -1)
    return previousMonth
}

// endregion
