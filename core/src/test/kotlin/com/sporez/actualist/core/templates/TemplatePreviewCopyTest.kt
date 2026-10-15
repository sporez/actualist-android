package com.sporez.actualist.core.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplatePreviewCopyTest {
    private fun category(demand: Long, shortfall: Long, current: Long, proposed: Long, goalOnly: Boolean = false) = TemplateCategoryPreview(
        categoryId = "c",
        name = "Groceries",
        current = current,
        proposed = proposed,
        perTemplate = listOf(proposed),
        evaluatedDemand = demand,
        shortfall = shortfall,
        isGoalOnlyUpdate = goalOnly,
        goalBefore = null,
        goalAfter = null,
        metric = TemplateMetric(TemplateMetricKind.AVAILABLE, current, proposed),
    )

    @Test
    fun priorityText() {
        assertNull(TemplatePreviewCopy.priorityText(emptyList()))
        assertEquals("Priority 1", TemplatePreviewCopy.priorityText(listOf(1, 1)))
        assertEquals("Priorities 0, 1, 9", TemplatePreviewCopy.priorityText(listOf(9, 0, 1)))
    }

    @Test
    fun statusText() {
        assertEquals("Fully funded", TemplatePreviewCopy.status(category(100, 0, 0, 100)))
        assertEquals("Partially funded", TemplatePreviewCopy.status(category(100, 40, 0, 60)))
        assertEquals("Unfunded", TemplatePreviewCopy.status(category(100, 100, 0, 0)))
        assertEquals("Goal update", TemplatePreviewCopy.status(category(0, 0, 0, 0, goalOnly = true)))
        assertEquals("Updated", TemplatePreviewCopy.status(category(0, 0, 5, 0)))
    }

    @Test
    fun noOpExplanations() {
        val base = TemplatePreview(month = "2026-10", mode = TemplateApplyMode.FILL_EMPTY)
        assertEquals("No eligible templates were found for this month.", TemplatePreviewCopy.noOpExplanation(base))
        assertEquals("Assignments already match their template targets.", TemplatePreviewCopy.noOpExplanation(base.copy(hasEligibleTemplates = true)))
        assertEquals("No funds are available for the remaining template targets.", TemplatePreviewCopy.noOpExplanation(base.copy(stillNeeded = 5)))
        assertNull(TemplatePreviewCopy.noOpExplanation(base.copy(assigned = 10)))
    }

    @Test
    fun releasedTitle() {
        assertEquals("Reduced", TemplatePreviewCopy.releasedTitle(true))
        assertEquals("Released", TemplatePreviewCopy.releasedTitle(false))
    }
}
