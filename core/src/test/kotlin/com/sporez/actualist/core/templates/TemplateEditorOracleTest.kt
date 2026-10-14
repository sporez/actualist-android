package com.sporez.actualist.core.templates

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Template editor oracle (`Templates/editor-cases.json`). Actualist's
 * `BudgetTemplateEditorOracleTests` replays four families through the editor's draft model
 * (`BudgetTemplateDefinition`, `BudgetTemplateDraft`, `BudgetTemplateAuthoringValidation`), which this
 * port does not have: normalization, type transitions and authoring validation are `notYetPorted`.
 *
 * The fourth family, legacy normalization preserving aggregate demand, is replayed against the engine
 * alone: the fixture's `expected` JSON stands in for the draft model's normalized encoding, so the engine
 * must plan the same amounts for a legacy definition and for its normalized form, in each currency.
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

    private fun ids(key: String): List<String> =
        TemplateOracleFixture.editorCases[key]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun everyEditorCaseIsReplayedOrNotYetPorted() {
        val normalizations = ids("normalizations")
        val transitions = ids("transitions")
        val validations = ids("validations")
        assertEquals(20, normalizations.size)
        assertEquals(11, transitions.size)
        assertEquals(13, validations.size)
        assertTrue(normalizations.containsAll(legacyDemandIds))

        val all = normalizations.map { "normalization:$it" } + transitions.map { "transition:$it" } + validations.map { "validation:$it" }
        val replayed = legacyDemandIds.map { "normalization:$it" }.toSet()
        // The editor draft model (normalization encoder, retyping reducer, authoring validator) is not ported.
        val notYetPorted = all.filter { it !in replayed }.toSet()
        assertEquals(all.toSet(), replayed + notYetPorted)
        assertTrue(replayed.intersect(notYetPorted).isEmpty())
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun legacyNormalizationPreservesAggregateDemand() {
        val samples = TemplateOracleFixture.editorCases["normalizations"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["id"]!!.jsonPrimitive.content in legacyDemandIds }
        assertEquals(legacyDemandIds.size, samples.size)
        for (currency in listOf(TemplateCurrency.USD, TemplateCurrency.JPY, TemplateCurrency.NONE)) {
            val engine = TemplateEngine(currency)
            for (sample in samples) {
                val id = sample["id"]!!.jsonPrimitive.content
                val amounts = listOf(sample["input"]!!.jsonPrimitive.content, sample["expected"]!!.jsonPrimitive.content).map { json ->
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
                assertEquals("$id / ${currency.code}", amounts[0], amounts[1])
            }
        }
    }
}
