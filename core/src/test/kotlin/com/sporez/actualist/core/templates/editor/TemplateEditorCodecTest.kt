package com.sporez.actualist.core.templates.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class TemplateEditorCodecTest {
    private val today = LocalDate.of(2026, 9, 4)

    private val periodicJson =
        "[{\"directive\":\"template\",\"type\":\"periodic\",\"amount\":400,\"period\":{\"period\":\"month\",\"amount\":1}," +
            "\"starting\":\"2026-09-01\",\"priority\":3}]"

    @Test
    fun encodesFixedAmountWithSortedKeysAndNoFraction() {
        val entries = TemplateDefinition.normalizedDrafts(periodicJson, today)
        assertEquals(
            "[{\"amount\":400,\"directive\":\"template\",\"period\":{\"amount\":1,\"period\":\"month\"}," +
                "\"priority\":3,\"starting\":\"2026-09-01\",\"type\":\"periodic\"}]",
            TemplateDefinition.encode(entries),
        )
    }

    @Test
    fun encodesNullPriorityForGoalAndKeepsFractions() {
        val goal = TemplateDraftEntry.goal(amount = 1000.0, description = "Goal note")
        assertEquals(
            "[{\"amount\":1000,\"description\":\"Goal note\",\"directive\":\"goal\",\"priority\":null,\"type\":\"goal\"}]",
            TemplateDefinition.encode(listOf(goal)),
        )
        val percentage = TemplateDraftEntry.percentage(percent = 15.5, sourceCategory = "Salary", previous = true)
        assertEquals(
            "[{\"category\":\"Salary\",\"directive\":\"template\",\"percent\":15.5,\"previous\":true," +
                "\"priority\":1,\"type\":\"percentage\"}]",
            TemplateDefinition.encode(listOf(percentage)),
        )
    }

    @Test
    fun whitespaceOnlyNotesAreOmittedAndRealNotesRoundTrip() {
        val blank = TemplateDraftEntry.refill(description = "   ")
        assertNull(blank.description)
        assertFalse(TemplateDefinition.encode(listOf(blank)).contains("description"))

        val noted = TemplateDraftEntry.refill(description = "  First line\nSecond line — café  ")
        val encoded = TemplateDefinition.encode(listOf(noted))
        assertEquals(listOf(noted), TemplateDefinition.normalizedDrafts(encoded, today))
    }

    @Test
    fun unknownFieldsKeepTheCategoryViewOnly() {
        val json = "[{\"directive\":\"template\",\"type\":\"refill\",\"priority\":1,\"future\":true}]"
        assertNull(TemplateDefinition.drafts(json, today))
        assertFalse(TemplateDefinition.isEditorEditableJson(json, today))
        try {
            TemplateDefinition.normalizedDrafts(json, today)
            fail("expected an unsupported field")
        } catch (error: TemplateDefinitionParseFailure.UnsupportedField) {
            assertEquals("refill.future", error.field)
        }
        val nested = "[{\"directive\":\"template\",\"type\":\"periodic\",\"amount\":400," +
            "\"period\":{\"period\":\"month\",\"amount\":1,\"future\":true},\"priority\":1}]"
        assertNull(TemplateDefinition.drafts(nested, today))
    }

    @Test
    fun blankAndEmptyDocumentsAreEmptyDrafts() {
        assertEquals(emptyList<TemplateDraftEntry>(), TemplateDefinition.drafts(null, today))
        assertEquals(emptyList<TemplateDraftEntry>(), TemplateDefinition.drafts("null", today))
        assertEquals(emptyList<TemplateDraftEntry>(), TemplateDefinition.drafts("[]", today))
        assertNull(TemplateDefinition.drafts("{", today))
    }

    @Test
    fun historicalModeChangesKeepCountAndPriority() {
        val copy = TemplateDraftEntry.copyPrevious(lookBack = 5, priority = 3, description = "History")
        val average = copy.retyped(TemplateEditorKind.AVERAGE, today)
        assertEquals(TemplateDraftEntry.average(numMonths = 5, priority = 3, description = "History"), average)
        assertEquals(copy, average.retyped(TemplateEditorKind.COPY, today))
        assertNotNull(copy.retyped(TemplateEditorKind.GOAL, today).description)
    }

    @Test
    fun legacyNestedCapBecomesStandaloneLimit() {
        val json = "[{\"directive\":\"template\",\"type\":\"simple\",\"monthly\":400,\"priority\":1," +
            "\"limit\":{\"amount\":100,\"hold\":false,\"period\":\"monthly\"}}]"
        val entries = TemplateDefinition.normalizedDrafts(json, today)
        assertEquals(2, entries.size)
        assertTrue(entries[0] is TemplateDraftEntry.BalanceLimit)
        assertTrue(entries[1] is TemplateDraftEntry.MonthlyFixed)
    }
}
