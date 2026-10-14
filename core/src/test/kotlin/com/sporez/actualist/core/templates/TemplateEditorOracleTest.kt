package com.sporez.actualist.core.templates

import com.sporez.actualist.core.templates.editor.TemplateAuthoringContext
import com.sporez.actualist.core.templates.editor.TemplateAuthoringValidation
import com.sporez.actualist.core.templates.editor.TemplateDefinition
import com.sporez.actualist.core.templates.editor.TemplateEditorKind
import com.sporez.actualist.core.templates.editor.TemplateIncomeOption
import com.sporez.actualist.core.templates.editor.TemplateScheduleOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Template editor oracle (`Templates/editor-cases.json`). Replays the four families of Actualist's
 * `BudgetTemplateEditorOracleTests`: normalization, type transitions and authoring validation through the
 * editor draft model ([TemplateDefinition], `TemplateDraftEntry`, [TemplateAuthoringValidation]), and legacy
 * normalization preserving aggregate demand through the engine in each currency.
 *
 * Swift excuses no editor case, so [excused] is empty; a case that is neither replayed nor excused fails
 * the partition test.
 */
class TemplateEditorOracleTest {
    private val legacyDemandIds = setOf(
        "simple-missing-monthly-cap",
        "simple-zero-cap-plus-fixed",
        "simple-monthly-cap-order",
        "periodic-nested-cap",
        "remainder-nested-cap",
        "copy-inert-cap",
    )

    /** Case ids (prefixed with the family) that cannot be replayed, with the reason. Swift excuses none. */
    private val excused: Map<String, String> = emptyMap()

    private val today = TemplateCalendar.validatedDate(TemplateOracleFixture.editorCases["today"]!!.jsonPrimitive.content)!!

    private fun cases(key: String): List<JsonObject> =
        TemplateOracleFixture.editorCases[key]!!.jsonArray.map { it.jsonObject }

    private fun text(case: JsonObject, key: String): String = case[key]!!.jsonPrimitive.content

    private fun ids(key: String): List<String> = cases(key).map { text(it, "id") }

    /** Replays every normalization case; returns the ids replayed. */
    private fun replayNormalizations(): List<String> {
        val replayed = mutableListOf<String>()
        for (case in cases("normalizations")) {
            val id = text(case, "id")
            val actual = TemplateDefinition.normalizedDrafts(text(case, "input"), today)
            val expected = TemplateDefinition.normalizedDrafts(text(case, "expected"), today)
            assertEquals(id, expected, actual)
            val encoded = TemplateDefinition.encode(actual)
            assertEquals("$id reopen", expected, TemplateDefinition.normalizedDrafts(encoded, today))
            replayed.add(id)
        }
        return replayed
    }

    /** Replays every type-transition case; returns the ids replayed. */
    private fun replayTransitions(): List<String> {
        val replayed = mutableListOf<String>()
        for (case in cases("transitions")) {
            val id = text(case, "id")
            val input = TemplateDefinition.normalizedDrafts(text(case, "input"), today).first()
            val kind = TemplateEditorKind.fromRaw(text(case, "kind"))
            assertNotNull("$id kind", kind)
            val expected = TemplateDefinition.normalizedDrafts(text(case, "expected"), today).first()
            assertEquals(id, expected, input.retyped(kind!!, today))
            replayed.add(id)
        }
        return replayed
    }

    /** Replays every authoring-validation case; returns the ids replayed. */
    private fun replayValidations(): List<String> {
        val context = TemplateAuthoringContext(
            today = today,
            schedules = listOf(TemplateScheduleOption("rent", "Rent")),
            incomeCategories = listOf(TemplateIncomeOption("income-1", "Salary")),
        )
        val replayed = mutableListOf<String>()
        for (case in cases("validations")) {
            val id = text(case, "id")
            val entries = TemplateDefinition.normalizedDrafts(text(case, "input"), today)
            val valid = case["valid"]!!.jsonPrimitive.content.toBooleanStrict()
            val issues = TemplateAuthoringValidation.issues(entries, context)
            assertEquals("$id: $issues", valid, issues.isEmpty())
            replayed.add(id)
        }
        return replayed
    }

    @Test
    fun everyEditorCaseIsReplayedOrExcused() {
        val normalizations = ids("normalizations")
        val transitions = ids("transitions")
        val validations = ids("validations")
        assertEquals(20, normalizations.size)
        assertEquals(11, transitions.size)
        assertEquals(13, validations.size)
        assertTrue(normalizations.containsAll(legacyDemandIds))

        val all = normalizations.map { "normalization:$it" } + transitions.map { "transition:$it" } + validations.map { "validation:$it" }
        assertEquals(all.size, all.toSet().size)

        val replayed = replayNormalizations().map { "normalization:$it" } +
            replayTransitions().map { "transition:$it" } +
            replayValidations().map { "validation:$it" }
        assertEquals(all, replayed)
        assertTrue(replayed.toSet().intersect(excused.keys).isEmpty())
        assertEquals(all.toSet(), replayed.toSet() + excused.keys)
        assertTrue("every excuse names a real case", all.containsAll(excused.keys))
    }

    @Test
    fun normalizationMatchesPinnedMigrationIncludingNotesAndZeroCaps() {
        assertEquals(ids("normalizations"), replayNormalizations())
    }

    @Test
    fun typeTransitionsMatchPinnedReducerAndRetainNotes() {
        assertEquals(ids("transitions"), replayTransitions())
    }

    @Test
    fun authoringRulesMatchPinnedWebValidator() {
        assertEquals(ids("validations"), replayValidations())
    }

    @Test
    fun legacyNormalizationPreservesAggregateDemand() {
        val samples = cases("normalizations").filter { text(it, "id") in legacyDemandIds }
        assertEquals(legacyDemandIds.size, samples.size)
        for (currency in listOf(TemplateCurrency.USD, TemplateCurrency.JPY, TemplateCurrency.NONE)) {
            val engine = TemplateEngine(currency)
            for (sample in samples) {
                val id = text(sample, "id")
                val normalized = TemplateDefinition.encode(TemplateDefinition.normalizedDrafts(text(sample, "input"), today))
                val amounts = listOf(text(sample, "input"), normalized, text(sample, "expected")).map { json ->
                    val entries = engine.decodeSupportedEntries(json)
                    assertNotNull("$id / ${currency.code} has actionable entries", entries)
                    val plan = engine.computePlan(
                        categories = mapOf(
                            "category" to TemplateEngine.Category(
                                entries = entries!!,
                                fromLastMonth = 25,
                                copiedBudgetedByLookBack = mapOf(1 to 400L),
                            ),
                        ),
                        orderedCategoryIds = listOf("category"),
                        monthValue = 202609,
                        availableBudget = 50_000,
                        skipAvailableClamp = true,
                    )
                    plan.writes.map { it.amount }
                }
                assertEquals("$id / ${currency.code} input vs normalized", amounts[0], amounts[1])
                assertEquals("$id / ${currency.code} input vs expected", amounts[0], amounts[2])
            }
        }
    }
}
