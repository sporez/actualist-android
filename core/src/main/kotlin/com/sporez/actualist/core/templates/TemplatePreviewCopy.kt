package com.sporez.actualist.core.templates

/** The wording of the template apply preview (Actualist's BudgetTemplateApplyPreviewDisplay). */
object TemplatePreviewCopy {
    fun releasedTitle(isTracking: Boolean): String = if (isTracking) "Reduced" else "Released"

    fun priorityText(levels: List<Int>): String? {
        val sorted = levels.toSortedSet().toList()
        if (sorted.isEmpty()) return null
        if (sorted.size == 1) return "Priority ${sorted[0]}"
        return "Priorities " + sorted.joinToString(", ")
    }

    fun status(category: TemplateCategoryPreview): String {
        if (category.evaluatedDemand <= 0L) {
            return if (category.isGoalOnlyUpdate) "Goal update" else if (category.current == category.proposed) "No assignment change" else "Updated"
        }
        if (category.shortfall == 0L) return "Fully funded"
        return if (category.current == category.proposed) "Unfunded" else "Partially funded"
    }

    /** True when nothing would be assigned or released and funds ran short. */
    fun hasNoFundsWarning(preview: TemplatePreview): Boolean =
        preview.assigned == 0L && preview.released == 0L && !preview.hasNonMoneyUpdates && preview.stillNeeded > 0L

    /** Why an apply would change nothing, or null when it changes something. */
    fun noOpExplanation(preview: TemplatePreview): String? {
        if (preview.assigned != 0L || preview.released != 0L || preview.hasNonMoneyUpdates) return null
        if (hasNoFundsWarning(preview)) return "No funds are available for the remaining template targets."
        return if (preview.hasEligibleTemplates) {
            "Assignments already match their template targets."
        } else {
            "No eligible templates were found for this month."
        }
    }
}
