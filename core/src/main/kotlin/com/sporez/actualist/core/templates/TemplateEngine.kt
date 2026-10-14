package com.sporez.actualist.core.templates

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.rules.ScheduleRecurrence

/**
 * The budget template engine: validates `goal_def` entries and computes per-category amounts for a
 * month. Port of Actualist `BudgetTemplateEngine` and its extensions (the compute, schedule,
 * average, spend, remainder, goal and validation files). Pure: all database reads happen in
 * [TemplateInputs] and arrive here as [Category] and [MonthSources].
 */
class TemplateEngine(val currency: TemplateCurrency = TemplateCurrency.USD) {
    class ResolvedSchedule(
        val name: String,
        val amount: Long,
        val nextDate: String,
        val monthsUntil: Int,
        val interval: Long,
        val frequency: String?,
        val completed: Boolean,
        val full: Boolean,
        val isRepeating: Boolean,
        val recurrence: ScheduleRecurrence?,
        val monthlyRepeatingTarget: Long,
    ) {
        fun withMonthlyRepeatingTarget(target: Long) = ResolvedSchedule(
            name, amount, nextDate, monthsUntil, interval, frequency, completed, full, isRepeating, recurrence, target,
        )
    }

    class Category(
        val entries: List<TemplateEntry>,
        val fromLastMonth: Long,
        val copiedBudgetedByLookBack: Map<Int, Long>,
        val isIncome: Boolean = false,
        val activityByMonth: Map<Int, Long> = emptyMap(),
        val firstRelevantMonth: Int? = null,
        val budgetedByMonth: Map<Int, Long> = emptyMap(),
        val leftoverByMonth: Map<Int, Long> = emptyMap(),
        val spentByMonth: Map<Int, Long> = emptyMap(),
        val resolvedSchedules: Map<String, ResolvedSchedule> = emptyMap(),
        val lastMonthGoal: Long = 0,
        val previouslyBudgeted: Long = 0,
    ) {
        val isGoalOnly: Boolean get() = entries.isGoalOnly
    }

    data class MonthSources(
        val totalIncomeByLookBack: Map<Int, Long> = emptyMap(),
        val incomeActivityByCategoryId: Map<String, Map<Int, Long>> = emptyMap(),
        val incomeCategoryIds: Set<String> = emptySet(),
        val incomeCategoryIdByLocalizedName: Map<String, String> = emptyMap(),
        val incomeCategoryIdsInOrder: List<String> = emptyList(),
        val incomeCategoryNamesById: Map<String, String> = emptyMap(),
        val hiddenIncomeCategoryIds: Set<String> = emptySet(),
        val activeScheduleNames: Set<String> = emptySet(),
        val activeScheduleIds: Set<String> = emptySet(),
    )

    data class Write(val categoryId: String, val amount: Long, val goal: Long? = null, val longGoal: Long? = null)

    data class ComputePlan(
        val writes: List<Write>,
        val leftover: Long,
        val contributions: Map<String, List<Long>>,
        /** Demand before the available-funds clamp, captured in the same pass that produces [writes]. */
        val evaluatedDemandByCategory: Map<String, Long>,
        val evaluatedDemand: Long,
        /** Positive demand lost at the priority available-funds clamp. */
        val clampShortfallByCategory: Map<String, Long>,
        val clampShortfall: Long,
    )

    object Bounds {
        const val SIGNED_TEMPLATE_AMOUNT = 1_000_000_000.0
        const val NONNEGATIVE_AMOUNT = 1_000_000_000.0
        const val PERCENTAGE = 100.0
        const val WEIGHT = 1_000_000.0
        val priority = 0..1_000
        val periodInterval = TemplateCalendar.periodInterval
        val repeatInterval = 1..1_200
        val lookBack = 0..1_200
        val numMonths = 1..1_200
        const val MAXIMUM_ENTRIES_PER_CATEGORY = 1_000

        fun signedAmountOk(value: Double) = value >= -SIGNED_TEMPLATE_AMOUNT && value <= SIGNED_TEMPLATE_AMOUNT
        fun nonnegativeAmountOk(value: Double) = value >= 0.0 && value <= NONNEGATIVE_AMOUNT
        fun percentageOk(value: Double) = value >= 0.0 && value <= PERCENTAGE
        fun weightOk(value: Double) = value >= 0.0 && value <= WEIGHT
    }

    class LimitState(val limitAmount: Long, val fromLastMonth: Long, val holdsExcess: Boolean) {
        val isInitiallyMet: Boolean get() = fromLastMonth >= limitAmount

        fun initialBudgetedAmount(): Long {
            if (!isInitiallyMet || holdsExcess) return 0
            return TemplateMath.subtract(limitAmount, fromLastMonth)
        }

        fun releasedExcess(): Long = maxOf(0L, TemplateMath.subtract(0, initialBudgetedAmount()))
    }

    sealed class PercentageSource {
        object AllIncome : PercentageSource()
        object AvailableFunds : PercentageSource()
        data class IncomeCategory(val id: String) : PercentageSource()
    }

    class ByTarget(val amount: Long, val monthsRemaining: Int, val repeatPeriod: Int?)

    /** Decodes and validates a `goal_def`; null when it holds no actionable entry. */
    fun decodeSupportedEntries(json: String): List<TemplateEntry>? {
        val entries = TemplateEntryDecoder.decode(json)
        for (entry in entries) validateDirective(entry)
        validateOneGoal(entries)
        validateOneSpend(entries)
        val actionable = entries.filter { it.setsBudget || it.isGoal }
        if (actionable.isEmpty()) return null
        if (actionable.size > Bounds.MAXIMUM_ENTRIES_PER_CATEGORY) throw UnsupportedTemplateException("too many template entries")
        for (entry in actionable) validate(entry)
        validateInteractions(actionable.filter { it.setsBudget })
        return actionable
    }

    fun validate(entries: List<TemplateEntry>, monthValue: Int) {
        TemplateCalendar.validatedMonth(monthValue)
        for (entry in entries) {
            if (entry.type == "by") resolvedByTarget(entry, monthValue)
        }
    }

    fun periodicAmount(entry: TemplateEntry, monthValue: Int): Long {
        val amount = entry.amount
        val periodAmount = entry.period?.amount
        val period = entry.period?.period
        if (amount == null || periodAmount == null || period == null || periodAmount !in Bounds.periodInterval) {
            throw UnsupportedTemplateException("periodic")
        }
        val amountInMinorUnits = amountToMinorUnits(amount)
        val monthStart = TemplateCalendar.monthStartDate(monthValue)
        val nextMonthStart = TemplateCalendar.nextMonthStartDate(monthValue)
        val starting = entry.starting?.trim()
        val startingDate = if (!starting.isNullOrEmpty()) {
            TemplateCalendar.validatedDate(starting) ?: throw UnsupportedTemplateException("periodic start date")
        } else {
            monthStart
        }
        val count = TemplateCalendar.periodicOccurrenceCount(startingDate, monthStart, nextMonthStart, periodAmount, period)
        return TemplateMath.multiply(amountInMinorUnits, count.toLong())
    }

    fun sourceMonthValue(monthValue: Int, lookBack: Int): Int {
        if (lookBack !in Bounds.lookBack) throw UnsupportedTemplateException("copy")
        return TemplateCalendar.shiftedMonth(monthValue, -lookBack)
    }

    /** Actual uses `Math.round(amount * 10**decimalPlaces)`; JS rounds ties toward +Infinity. */
    fun amountToMinorUnits(amount: Double): Long {
        if (!amount.isFinite() || !Bounds.signedAmountOk(amount)) throw LocalFirstException.NumericValueOutOfRange()
        val scaled = amount * decimalScale(currency.decimalPlaces)
        if (!scaled.isFinite()) throw LocalFirstException.NumericValueOutOfRange()
        return TemplateMath.actualRound(scaled)
    }

    fun removeFractionLikeActual(minorUnits: Long): Long {
        if (!currency.hideFraction || currency.decimalPlaces <= 0) return minorUnits
        val integerScale = integerScale(currency.decimalPlaces)
        val display = minorUnits.toDouble() / integerScale.toDouble()
        val roundedDisplay = TemplateMath.actualRound(display)
        return TemplateMath.multiply(roundedDisplay, integerScale)
    }

    fun limitState(category: Category, monthValue: Int): LimitState? {
        val limits = category.entries.mapNotNull { effectiveLimit(it) }
        if (limits.isEmpty()) return null
        val limit = limits.singleOrNull()
        val amount = limit?.amount
        if (limit == null || amount == null) {
            throw UnsupportedTemplateException("only one up-to limit is supported per category")
        }
        return LimitState(limitAmount(limit, monthValue, amount), category.fromLastMonth, limit.hold == true)
    }

    private fun limitAmount(limit: TemplateLimit, monthValue: Int, baseAmount: Double): Long {
        val base = amountToMinorUnits(baseAmount)
        return when (limit.period) {
            "monthly" -> base
            "daily" -> TemplateMath.multiply(base, TemplateCalendar.daysInMonth(monthValue).toLong())
            "weekly" -> {
                val start = limit.start ?: throw UnsupportedTemplateException("weekly limit requires a start date (YYYY-MM-DD)")
                TemplateMath.multiply(base, TemplateCalendar.weeklyLimitOccurrenceCount(start, monthValue).toLong())
            }
            else -> throw UnsupportedTemplateException("only daily, weekly, and monthly up-to limits are supported")
        }
    }

    fun computeEntryAmount(
        entry: TemplateEntry,
        monthValue: Int,
        currentMonthValue: Int,
        category: Category,
        limitState: LimitState?,
        availableFunds: Long,
        monthSources: MonthSources,
    ): Long = when (entry.type) {
        "simple" -> {
            val monthly = entry.monthly
            if (monthly != null) {
                amountToMinorUnits(monthly)
            } else {
                if (limitState == null) throw UnsupportedTemplateException("simple without monthly amount")
                TemplateMath.subtract(limitState.limitAmount, limitState.fromLastMonth)
            }
        }
        "periodic" -> periodicAmount(entry, monthValue)
        "copy" -> {
            val lookBack = entry.lookBack
            val copied = if (lookBack != null && lookBack in Bounds.lookBack) category.copiedBudgetedByLookBack[lookBack] else null
            copied ?: throw UnsupportedTemplateException("copy")
        }
        "limit" -> 0L
        "refill" -> {
            if (limitState == null) throw UnsupportedTemplateException("refill without up-to limit")
            TemplateMath.subtract(limitState.limitAmount, limitState.fromLastMonth)
        }
        "average" -> computeAverageAmount(entry, monthValue, currentMonthValue, category)
        "percentage" -> computePercentageAmount(entry, availableFunds, monthSources)
        "spend" -> computeSpendAmount(entry, monthValue, category)
        else -> throw UnsupportedTemplateException(entry.type)
    }

    private fun computePercentageAmount(entry: TemplateEntry, availableFunds: Long, monthSources: MonthSources): Long {
        val percent = entry.percentageAmount
        if (percent == null || !Bounds.percentageOk(percent)) throw UnsupportedTemplateException("percentage")
        val monthlyIncome = percentageIncome(entry, availableFunds, monthSources)
        return maxOf(0L, TemplateMath.actualRound(monthlyIncome.toDouble() * (percent / 100)))
    }

    fun resolvePercentageSource(entry: TemplateEntry, monthSources: MonthSources): PercentageSource {
        val raw = entry.sourceCategory?.trim() ?: ""
        if (raw.isEmpty()) throw UnsupportedTemplateException("percentage")
        val lowered = raw.lowercase()
        if (lowered == "all income") return PercentageSource.AllIncome
        if (lowered == "available funds") return PercentageSource.AvailableFunds
        if (raw in monthSources.incomeCategoryIds) return PercentageSource.IncomeCategory(raw)
        monthSources.incomeCategoryIdByLocalizedName[lowered]?.let { return PercentageSource.IncomeCategory(it) }
        throw UnsupportedTemplateException("Category \"$raw\" is not found in available income categories")
    }

    private fun percentageIncome(entry: TemplateEntry, availableFunds: Long, monthSources: MonthSources): Long {
        val lookBack = if (entry.previous == true) 1 else 0
        return when (val source = resolvePercentageSource(entry, monthSources)) {
            is PercentageSource.AllIncome -> monthSources.totalIncomeByLookBack[lookBack] ?: 0L
            is PercentageSource.AvailableFunds -> availableFunds
            is PercentageSource.IncomeCategory -> monthSources.incomeActivityByCategoryId[source.id]?.get(lookBack) ?: 0L
        }
    }

    fun computeByAmount(entries: List<TemplateEntry>, monthValue: Int, fromLastMonth: Long): Long {
        val targets = entries.map { resolvedByTarget(it, monthValue) }
        val shortestTarget = targets.minOfOrNull { it.monthsRemaining } ?: return 0
        var totalNeeded = 0L
        for (target in targets) {
            val repeatPeriod: Int? = target.repeatPeriod
            if (target.monthsRemaining > shortestTarget && repeatPeriod != null) {
                val remainingRepeatMonths = repeatPeriod - target.monthsRemaining + shortestTarget
                totalNeeded = TemplateMath.add(
                    totalNeeded,
                    TemplateMath.actualRound(
                        target.amount.toDouble() / repeatPeriod.toDouble() * remainingRepeatMonths.toDouble(),
                    ),
                )
            } else if (target.monthsRemaining > shortestTarget) {
                totalNeeded = TemplateMath.add(
                    totalNeeded,
                    TemplateMath.actualRound(
                        target.amount.toDouble() / (target.monthsRemaining + 1).toDouble() * (shortestTarget + 1).toDouble(),
                    ),
                )
            } else {
                totalNeeded = TemplateMath.add(totalNeeded, target.amount)
            }
        }
        return TemplateMath.actualRound(
            TemplateMath.subtract(totalNeeded, fromLastMonth).toDouble() / (shortestTarget + 1).toDouble(),
        )
    }

    fun resolvedByTarget(entry: TemplateEntry, monthValue: Int): ByTarget {
        val amount = entry.amount
        val monthText = entry.month
        val initialTargetMonth = if (amount != null && monthText != null) {
            try {
                TemplateCalendar.parseMonth(monthText)
            } catch (error: UnsupportedTemplateException) {
                null
            }
        } else {
            null
        }
        if (amount == null || initialTargetMonth == null) throw UnsupportedTemplateException("invalid by template")

        val repeatPeriod: Int? = repeatPeriodMonths(entry, "invalid by repeat interval")
        var targetMonth: Int = initialTargetMonth
        var monthsRemaining = TemplateCalendar.monthDistance(monthValue, targetMonth)
        if (monthsRemaining < 0 && repeatPeriod != null) {
            val overdueMonths = -monthsRemaining
            val adjusted = overdueMonths + (repeatPeriod - 1)
            val cycles = adjusted / repeatPeriod
            val shift = try {
                Math.multiplyExact(repeatPeriod, cycles)
            } catch (error: ArithmeticException) {
                throw UnsupportedTemplateException("invalid by repeat interval")
            }
            targetMonth = TemplateCalendar.shiftedMonth(targetMonth, shift)
            monthsRemaining = TemplateCalendar.monthDistance(monthValue, targetMonth)
        }
        if (monthsRemaining < 0) {
            throw UnsupportedTemplateException("by target month ${TemplateCalendar.monthId(initialTargetMonth)} has passed")
        }
        return ByTarget(amountToMinorUnits(amount), monthsRemaining, repeatPeriod)
    }

    /** `annual` repeats every `repeatInterval` years (default 1), otherwise every `repeatInterval` months. */
    internal fun repeatPeriodMonths(entry: TemplateEntry, overflowMessage: String): Int? {
        if (entry.annual == true) {
            val years = entry.repeatInterval ?: 1
            return try {
                Math.multiplyExact(years, 12)
            } catch (error: ArithmeticException) {
                throw UnsupportedTemplateException(overflowMessage)
            }
        }
        return entry.repeatInterval
    }

    companion object {
        fun participatesInPriority(entry: TemplateEntry): Boolean =
            entry.directive == "template" && entry.type != "remainder" && entry.type != "limit"

        // Actual's checkLimit() only treats limits on simple, periodic, remainder, and standalone
        // type == "limit" as active. A copy template may carry a `limit` field; Actual ignores it.
        fun effectiveLimit(entry: TemplateEntry): TemplateLimit? = when (entry.type) {
            "limit" -> entry.standaloneLimit
            "simple", "periodic", "remainder" -> entry.limit
            else -> null
        }

        fun hasEffectiveLimit(entry: TemplateEntry): Boolean = effectiveLimit(entry) != null

        fun decimalScale(decimalPlaces: Int): Double {
            if (decimalPlaces !in 0..15) throw LocalFirstException.NumericValueOutOfRange()
            return Math.pow(10.0, decimalPlaces.toDouble())
        }

        fun integerScale(decimalPlaces: Int): Long {
            if (decimalPlaces < 0) throw LocalFirstException.NumericValueOutOfRange()
            var scale = 1L
            repeat(decimalPlaces) { scale = TemplateMath.multiply(scale, 10) }
            return scale
        }
    }
}
