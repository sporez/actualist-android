package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.templates.TemplateCalendar
import com.sporez.actualist.core.templates.TemplateEngine
import com.sporez.actualist.core.templates.UnsupportedTemplateException
import java.time.LocalDate

enum class TemplateCadence(val raw: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year"),
    ;

    companion object {
        fun fromRaw(raw: String?): TemplateCadence? = values().firstOrNull { it.raw == raw }
    }
}

enum class TemplateLimitPeriod(val raw: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
    MONTHLY("monthly"),
    ;

    companion object {
        fun fromRaw(raw: String?): TemplateLimitPeriod? = values().firstOrNull { it.raw == raw }
    }
}

/** A Schedule or Average modifier: a signed fixed amount or a percentage. Port of `BudgetTemplateAdjustment`. */
sealed class TemplateAdjustment {
    abstract val value: Double
    abstract val type: String

    data class Fixed(override val value: Double) : TemplateAdjustment() {
        override val type: String get() = "fixed"
    }

    data class Percent(override val value: Double) : TemplateAdjustment() {
        override val type: String get() = "percent"
    }
}

/** Legacy nested cap metadata retained for Copy and Remainder definitions (`BudgetTemplateUpToHold`). */
data class TemplateUpToHold(
    val amount: Double,
    val hold: Boolean,
    val period: String,
    val start: String? = null,
)

/** The editor's categories of template, in the order the editor lists them (`BudgetTemplateKind`). */
enum class TemplateEditorKind(val raw: String, val title: String, val isSingleton: Boolean) {
    MONTHLY_FIXED("monthlyFixed", "Fixed Amount", false),
    DATE_TARGET("dateTarget", "Save by Date", false),
    PERCENTAGE("percentage", "Percentage", false),
    BALANCE_LIMIT("balanceLimit", "Balance Limit", true),
    REFILL("refill", "Refill", true),
    COPY("copy", "Copy Previous Month", false),
    AVERAGE("average", "Average", false),
    SCHEDULE("schedule", "Cover Schedule", false),
    REMAINDER("remainder", "Remainder", true),
    GOAL("goal", "Goal", true),
    ;

    fun makeDraft(today: LocalDate): TemplateDraftEntry = when (this) {
        MONTHLY_FIXED -> TemplateDraftEntry.monthlyFixed(today = today)
        DATE_TARGET -> TemplateDraftEntry.dateTarget(today = today)
        PERCENTAGE -> TemplateDraftEntry.percentage()
        BALANCE_LIMIT -> TemplateDraftEntry.balanceLimit()
        REFILL -> TemplateDraftEntry.refill()
        COPY -> TemplateDraftEntry.copyPrevious()
        AVERAGE -> TemplateDraftEntry.average()
        SCHEDULE -> TemplateDraftEntry.schedule()
        REMAINDER -> TemplateDraftEntry.remainder()
        GOAL -> TemplateDraftEntry.goal()
    }

    companion object {
        fun fromRaw(raw: String?): TemplateEditorKind? = values().firstOrNull { it.raw == raw }
    }
}

private fun normalizedDescription(value: String?): String? =
    if (value == null || value.trim().isEmpty()) null else value

private fun defaultDateTargetMonth(today: LocalDate): String {
    val currentMonth = today.year * 100 + today.monthValue
    val targetMonth = try {
        TemplateCalendar.shiftedMonth(currentMonth, 12)
    } catch (error: UnsupportedTemplateException) {
        currentMonth
    }
    return TemplateCalendar.monthId(targetMonth)
}

/**
 * One entry of a category's template as the editor edits it: a typed, editor-owned representation of an
 * Actual template definition. Port of Actualist `BudgetTemplateDraft`. These carry authoring metadata such as
 * [description]; the apply engine's `TemplateEntry` stays a separate calculation model.
 */
sealed class TemplateDraftEntry {
    abstract val description: String?

    data class MonthlyFixed(
        val amount: Double,
        val priority: Int,
        val starting: String,
        val cadence: TemplateCadence = TemplateCadence.MONTH,
        val interval: Int = 1,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class DateTarget(
        val amount: Double,
        val month: String,
        val priority: Int,
        val repeatInterval: Int?,
        val annual: Boolean,
        val fromMonth: String?,
        val isSpend: Boolean,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Percentage(
        val percent: Double,
        val sourceCategory: String,
        val previous: Boolean,
        val priority: Int,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class BalanceLimit(
        val amount: Double,
        val hold: Boolean,
        val period: TemplateLimitPeriod,
        val start: String? = null,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Refill(
        val priority: Int,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Copy(
        val lookBack: Int,
        val priority: Int,
        /** Actual retains this legacy field but ignores it when applying Copy. */
        val legacyLimit: TemplateUpToHold? = null,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Average(
        val numMonths: Int,
        val priority: Int,
        val adjustment: TemplateAdjustment? = null,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Schedule(
        val name: String,
        val scheduleId: String?,
        val priority: Int,
        val full: Boolean = false,
        val adjustment: TemplateAdjustment? = null,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Remainder(
        val weight: Double,
        val legacyLimit: TemplateUpToHold? = null,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    data class Goal(
        val amount: Double,
        override val description: String? = null,
    ) : TemplateDraftEntry()

    val kind: TemplateEditorKind
        get() = when (this) {
            is MonthlyFixed -> TemplateEditorKind.MONTHLY_FIXED
            is DateTarget -> TemplateEditorKind.DATE_TARGET
            is Percentage -> TemplateEditorKind.PERCENTAGE
            is BalanceLimit -> TemplateEditorKind.BALANCE_LIMIT
            is Refill -> TemplateEditorKind.REFILL
            is Copy -> TemplateEditorKind.COPY
            is Average -> TemplateEditorKind.AVERAGE
            is Schedule -> TemplateEditorKind.SCHEDULE
            is Remainder -> TemplateEditorKind.REMAINDER
            is Goal -> TemplateEditorKind.GOAL
        }

    val modifierAdjustment: TemplateAdjustment?
        get() = when (this) {
            is Average -> adjustment
            is Schedule -> adjustment
            else -> null
        }

    fun updatingModifierAdjustment(adjustment: TemplateAdjustment?): TemplateDraftEntry = when (this) {
        is Average -> copy(adjustment = adjustment)
        is Schedule -> copy(adjustment = adjustment)
        else -> this
    }

    fun updatingDescription(description: String?): TemplateDraftEntry {
        val normalized = normalizedDescription(description)
        return when (this) {
            is MonthlyFixed -> copy(description = normalized)
            is DateTarget -> copy(description = normalized)
            is Percentage -> copy(description = normalized)
            is BalanceLimit -> copy(description = normalized)
            is Refill -> copy(description = normalized)
            is Copy -> copy(description = normalized)
            is Average -> copy(description = normalized)
            is Schedule -> copy(description = normalized)
            is Remainder -> copy(description = normalized)
            is Goal -> copy(description = normalized)
        }
    }

    /**
     * Unrelated type changes use the pinned reducer's defaults; historical mode changes alone (Copy and
     * Average) retain their count and priority. Notes survive both.
     */
    fun retyped(to: TemplateEditorKind, today: LocalDate): TemplateDraftEntry {
        if (kind == to) return this
        val next: TemplateDraftEntry = when {
            this is Copy && to == TemplateEditorKind.AVERAGE -> average(numMonths = lookBack, priority = priority)
            this is Average && to == TemplateEditorKind.COPY -> copyPrevious(lookBack = numMonths, priority = priority)
            else -> to.makeDraft(today)
        }
        return next.updatingDescription(description)
    }

    val showsContribution: Boolean
        get() = this !is Goal && this !is BalanceLimit

    /**
     * Whether this entry can be encoded as a supported editor definition. Cross-entry rules belong to
     * [TemplateAuthoringValidation].
     */
    val isComplete: Boolean
        get() = when (this) {
            is MonthlyFixed ->
                amount.isFinite() &&
                    TemplateEngine.Bounds.signedAmountOk(amount) &&
                    priority in TemplateEngine.Bounds.priority &&
                    interval in TemplateEngine.Bounds.periodInterval &&
                    TemplateCalendar.validatedDate(starting) != null
            is DateTarget ->
                amount.isFinite() &&
                    TemplateEngine.Bounds.signedAmountOk(amount) &&
                    priority in TemplateEngine.Bounds.priority &&
                    (repeatInterval?.let { it in TemplateEngine.Bounds.repeatInterval } ?: true)
            is Percentage ->
                percent > 0 &&
                    TemplateEngine.Bounds.percentageOk(percent) &&
                    priority in TemplateEngine.Bounds.priority &&
                    sourceCategory.trim().isNotEmpty()
            is BalanceLimit ->
                amount.isFinite() &&
                    TemplateEngine.Bounds.nonnegativeAmountOk(amount) &&
                    limitStartComplete(start, period)
            is Refill -> priority in TemplateEngine.Bounds.priority
            is Copy ->
                lookBack in TemplateEngine.Bounds.lookBack &&
                    priority in TemplateEngine.Bounds.priority &&
                    (legacyLimit?.let { upToHoldComplete(it) } ?: true)
            is Average ->
                numMonths in TemplateEngine.Bounds.numMonths &&
                    priority in TemplateEngine.Bounds.priority &&
                    isValidAdjustment(adjustment)
            is Schedule -> {
                val hasId = (scheduleId ?: "").trim().isNotEmpty()
                val hasName = name.trim().isNotEmpty()
                (hasId || hasName) &&
                    priority in TemplateEngine.Bounds.priority &&
                    isValidAdjustment(adjustment)
            }
            is Remainder ->
                weight.isFinite() &&
                    TemplateEngine.Bounds.weightOk(weight) &&
                    (legacyLimit?.let { upToHoldComplete(it) } ?: true)
            is Goal -> amount.isFinite() && TemplateEngine.Bounds.signedAmountOk(amount)
        }

    companion object {
        const val DEFAULT_PRIORITY = 1

        fun monthlyFixed(
            amount: Double = 100.0,
            priority: Int = DEFAULT_PRIORITY,
            today: LocalDate = LocalDate.now(),
            description: String? = null,
        ): TemplateDraftEntry = MonthlyFixed(
            amount = amount,
            priority = priority,
            starting = TemplateDefinition.firstDayOfCurrentMonth(today),
            description = normalizedDescription(description),
        )

        fun dateTarget(
            amount: Double = 1_200.0,
            month: String? = null,
            priority: Int = DEFAULT_PRIORITY,
            repeatInterval: Int? = 1,
            annual: Boolean = true,
            fromMonth: String? = null,
            isSpend: Boolean? = null,
            description: String? = null,
            today: LocalDate = LocalDate.now(),
        ): TemplateDraftEntry = DateTarget(
            amount = amount,
            month = month ?: defaultDateTargetMonth(today),
            priority = priority,
            repeatInterval = repeatInterval,
            annual = annual,
            fromMonth = fromMonth,
            isSpend = isSpend ?: (fromMonth != null),
            description = normalizedDescription(description),
        )

        fun percentage(
            percent: Double = 15.0,
            sourceCategory: String = "all income",
            previous: Boolean = false,
            priority: Int = DEFAULT_PRIORITY,
            description: String? = null,
        ): TemplateDraftEntry = Percentage(percent, sourceCategory, previous, priority, normalizedDescription(description))

        fun balanceLimit(
            amount: Double = 500.0,
            hold: Boolean = false,
            period: TemplateLimitPeriod = TemplateLimitPeriod.MONTHLY,
            start: String? = null,
            description: String? = null,
        ): TemplateDraftEntry = BalanceLimit(amount, hold, period, start, normalizedDescription(description))

        fun refill(
            priority: Int = DEFAULT_PRIORITY,
            description: String? = null,
        ): TemplateDraftEntry = Refill(priority, normalizedDescription(description))

        fun copyPrevious(
            lookBack: Int = 1,
            priority: Int = DEFAULT_PRIORITY,
            legacyLimit: TemplateUpToHold? = null,
            description: String? = null,
        ): TemplateDraftEntry = Copy(lookBack, priority, legacyLimit, normalizedDescription(description))

        fun average(
            numMonths: Int = 3,
            priority: Int = DEFAULT_PRIORITY,
            adjustment: TemplateAdjustment? = null,
            description: String? = null,
        ): TemplateDraftEntry = Average(numMonths, priority, adjustment, normalizedDescription(description))

        fun schedule(
            name: String = "",
            scheduleId: String? = null,
            priority: Int = DEFAULT_PRIORITY,
            full: Boolean = false,
            adjustment: TemplateAdjustment? = null,
            description: String? = null,
        ): TemplateDraftEntry = Schedule(name, scheduleId, priority, full, adjustment, normalizedDescription(description))

        fun remainder(
            weight: Double = 1.0,
            legacyLimit: TemplateUpToHold? = null,
            description: String? = null,
        ): TemplateDraftEntry = Remainder(weight, legacyLimit, normalizedDescription(description))

        fun goal(
            amount: Double = 1_000.0,
            description: String? = null,
        ): TemplateDraftEntry = Goal(amount, normalizedDescription(description))

        fun isValidAdjustment(value: TemplateAdjustment?): Boolean {
            if (value == null) return true
            if (!value.value.isFinite()) return false
            return when (value) {
                is TemplateAdjustment.Fixed -> TemplateEngine.Bounds.signedAmountOk(value.value)
                is TemplateAdjustment.Percent -> value.value > -100 && value.value <= 1_000
            }
        }

        private fun limitStartComplete(value: String?, period: TemplateLimitPeriod): Boolean {
            if (period == TemplateLimitPeriod.WEEKLY) {
                return value != null && TemplateCalendar.validatedDate(value) != null
            }
            return value == null || TemplateCalendar.validatedDate(value) != null
        }

        private fun upToHoldComplete(value: TemplateUpToHold): Boolean {
            val start = value.start
            val startValid = start != null && TemplateCalendar.validatedDate(start) != null
            return value.amount.isFinite() &&
                TemplateEngine.Bounds.nonnegativeAmountOk(value.amount) &&
                TemplateLimitPeriod.fromRaw(value.period) != null &&
                (start == null || startValid) &&
                (value.period != TemplateLimitPeriod.WEEKLY.raw || startValid)
        }
    }
}

/** A category's whole template as the editor holds it: the entries in list order (`[BudgetTemplateDraft]`). */
data class TemplateEditorDraft(val entries: List<TemplateDraftEntry>) {
    val isEmpty: Boolean get() = entries.isEmpty()
}
