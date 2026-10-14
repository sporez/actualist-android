package com.sporez.actualist.core.templates.editor

import com.sporez.actualist.core.templates.TemplateCalendar
import com.sporez.actualist.core.templates.TemplateEngine
import com.sporez.actualist.core.templates.UnsupportedTemplateException
import java.time.LocalDate

data class TemplateScheduleOption(val id: String, val name: String)

data class TemplateIncomeOption(val id: String, val name: String, val isAvailable: Boolean = true)

/** What the pickers offer: the schedules and income categories a template may reference, and "today". */
data class TemplateAuthoringContext(
    val today: LocalDate = LocalDate.now(),
    val schedules: List<TemplateScheduleOption> = emptyList(),
    val incomeCategories: List<TemplateIncomeOption> = emptyList(),
)

sealed class TemplateAuthoringIssue {
    abstract val message: String

    data class InvalidEntry(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Template ${index + 1} has an invalid value."
    }

    data class DuplicateType(val kind: TemplateEditorKind) : TemplateAuthoringIssue() {
        override val message: String get() = "Only one ${kind.title} template is allowed in this category."
    }

    data object DuplicateSpend : TemplateAuthoringIssue() {
        override val message: String get() = "Only one early-spending template is allowed in this category."
    }

    data object RefillRequiresLimit : TemplateAuthoringIssue() {
        override val message: String get() = "Refill needs a Balance Limit."
    }

    data object LimitRequiresContributor : TemplateAuthoringIssue() {
        override val message: String get() = "Balance Limit needs a contributing template."
    }

    data object MultipleLimits : TemplateAuthoringIssue() {
        override val message: String get() = "Only one Balance Limit is allowed in this category."
    }

    data class ScheduleNotFound(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Choose an available schedule."
    }

    data class PercentageSourceNotFound(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Choose an available income source."
    }

    data class PercentageConflict(val previous: Boolean, val source: String) : TemplateAuthoringIssue() {
        override val message: String
            get() = "Percentage templates for ${if (source.isEmpty()) "this source" else source} cannot total more than 100%."
    }

    data object SchedulePriorityMismatch : TemplateAuthoringIssue() {
        override val message: String get() = "Schedule and Save by Date templates must use the same priority."
    }

    data class TargetMonthMissing(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Choose a valid target month."
    }

    data class TargetMonthPast(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "A one-time target month must not be in the past."
    }

    data class SpendStartMissing(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Enter a month to start early spending."
    }

    data class SpendStartAfterTarget(val index: Int) : TemplateAuthoringIssue() {
        override val message: String get() = "Early spending must start on or before the target month."
    }
}

/**
 * Pure list-level validation shared by the editor state and the definition write boundary. Port of
 * `BudgetTemplateAuthoringValidation`. It never mutates entries or decides persisted money values.
 */
object TemplateAuthoringValidation {
    fun issues(entries: List<TemplateDraftEntry>, context: TemplateAuthoringContext): List<TemplateAuthoringIssue> {
        val issues = mutableListOf<TemplateAuthoringIssue>()
        if (entries.size > TemplateEngine.Bounds.MAXIMUM_ENTRIES_PER_CATEGORY) {
            issues.add(TemplateAuthoringIssue.InvalidEntry(entries.size))
        }

        entries.forEachIndexed { index, entry ->
            val missingScheduleReference = entry is TemplateDraftEntry.Schedule &&
                (entry.scheduleId ?: "").trim().isEmpty() &&
                entry.name.trim().isEmpty()
            if (!entry.isComplete && !missingScheduleReference) {
                issues.add(TemplateAuthoringIssue.InvalidEntry(index))
            }
            when (entry) {
                is TemplateDraftEntry.Schedule -> {
                    if (!hasSchedule(entry, context)) issues.add(TemplateAuthoringIssue.ScheduleNotFound(index))
                    if (percentAdjustmentOutOfRange(entry.adjustment)) issues.add(TemplateAuthoringIssue.InvalidEntry(index))
                }
                is TemplateDraftEntry.Percentage -> {
                    if (!hasPercentageSource(entry.sourceCategory, entry.previous, context)) {
                        issues.add(TemplateAuthoringIssue.PercentageSourceNotFound(index))
                    }
                }
                is TemplateDraftEntry.DateTarget -> validateTarget(entry, index, context, issues)
                is TemplateDraftEntry.Average -> {
                    if (percentAdjustmentOutOfRange(entry.adjustment)) issues.add(TemplateAuthoringIssue.InvalidEntry(index))
                }
                else -> Unit
            }
            Unit
        }

        val singletonKinds = mutableSetOf<TemplateEditorKind>()
        for (entry in entries) {
            if (entry.kind.isSingleton && !singletonKinds.add(entry.kind)) {
                issues.add(TemplateAuthoringIssue.DuplicateType(entry.kind))
            }
        }

        val limitCount = entries.count { it is TemplateDraftEntry.BalanceLimit }
        if (limitCount > 1) issues.add(TemplateAuthoringIssue.MultipleLimits)
        val hasContributor = entries.any { it !is TemplateDraftEntry.Goal && it !is TemplateDraftEntry.BalanceLimit }
        if (entries.any { it is TemplateDraftEntry.Refill } && limitCount != 1) {
            issues.add(TemplateAuthoringIssue.RefillRequiresLimit)
        }
        if (limitCount == 1 && !hasContributor) issues.add(TemplateAuthoringIssue.LimitRequiresContributor)

        val scheduleAndTargetPriorities = entries.mapNotNull { entry ->
            when {
                entry is TemplateDraftEntry.Schedule -> entry.priority
                entry is TemplateDraftEntry.DateTarget && !entry.isSpend -> entry.priority
                else -> null
            }
        }
        if (scheduleAndTargetPriorities.toSet().size > 1) issues.add(TemplateAuthoringIssue.SchedulePriorityMismatch)

        if (entries.count { it is TemplateDraftEntry.DateTarget && it.isSpend } > 1) {
            issues.add(TemplateAuthoringIssue.DuplicateSpend)
        }

        val percentageTotals = LinkedHashMap<Pair<Boolean, String>, Double>()
        for (entry in entries) {
            if (entry !is TemplateDraftEntry.Percentage) continue
            val key = entry.previous to entry.sourceCategory.lowercase()
            percentageTotals[key] = (percentageTotals[key] ?: 0.0) + entry.percent
        }
        for ((key, total) in percentageTotals) {
            if (total > 100) issues.add(TemplateAuthoringIssue.PercentageConflict(key.first, key.second))
        }
        return issues
    }

    fun isValid(entries: List<TemplateDraftEntry>, context: TemplateAuthoringContext): Boolean =
        issues(entries, context).isEmpty()

    private fun percentAdjustmentOutOfRange(adjustment: TemplateAdjustment?): Boolean {
        if (adjustment !is TemplateAdjustment.Percent) return false
        val amount = adjustment.value
        return !(amount > -100 && amount <= 1_000)
    }

    private fun hasSchedule(value: TemplateDraftEntry.Schedule, context: TemplateAuthoringContext): Boolean {
        val scheduleId = value.scheduleId
        if (scheduleId != null && scheduleId.trim().isNotEmpty()) {
            return context.schedules.any { it.id == scheduleId }
        }
        val name = value.name.trim()
        return name.isNotEmpty() && context.schedules.any { it.name == name }
    }

    private fun hasPercentageSource(source: String, previous: Boolean, context: TemplateAuthoringContext): Boolean {
        val trimmed = source.trim()
        val lowered = trimmed.lowercase()
        if (lowered == "all income") return true
        if (lowered == "available funds") return !previous
        return context.incomeCategories.any { it.id == trimmed || it.name.lowercase() == lowered }
    }

    private fun parseMonthOrNull(text: String): Int? = try {
        TemplateCalendar.parseMonth(text)
    } catch (error: UnsupportedTemplateException) {
        null
    }

    private fun validateTarget(
        value: TemplateDraftEntry.DateTarget,
        index: Int,
        context: TemplateAuthoringContext,
        issues: MutableList<TemplateAuthoringIssue>,
    ) {
        val target = parseMonthOrNull(value.month)
        if (target == null) {
            issues.add(TemplateAuthoringIssue.TargetMonthMissing(index))
            return
        }
        val todayMonth = context.today.year * 100 + context.today.monthValue
        if (target < todayMonth && value.repeatInterval == null) {
            issues.add(TemplateAuthoringIssue.TargetMonthPast(index))
        }
        if (value.isSpend) {
            val from = value.fromMonth?.let { parseMonthOrNull(it) }
            if (from == null) {
                issues.add(TemplateAuthoringIssue.SpendStartMissing(index))
                return
            }
            if (from > target) issues.add(TemplateAuthoringIssue.SpendStartAfterTarget(index))
        }
    }
}
