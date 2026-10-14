package com.sporez.actualist.core.templates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The Apply Templates and Template editor oracle fixtures, copied unchanged from Actualist. */
object TemplateOracleFixture {
    /** The fixture's `syntheticData.currentMonth`: the "today" every apply case replays against. */
    val currentMonth: String by lazy { contract["syntheticData"]!!.jsonObject["currentMonth"]!!.jsonPrimitive.content }

    private fun resource(path: String): JsonObject {
        val stream = TemplateOracleFixture::class.java.getResourceAsStream(path)
            ?: throw IllegalStateException("missing oracle fixture $path")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    val contract: JsonObject by lazy { resource("/oracle/TemplateApply/template-apply-contract.json") }
    val manifest: JsonObject by lazy { resource("/oracle/TemplateApply/template-apply-manifest.json") }
    val editorCases: JsonObject by lazy { resource("/oracle/Templates/editor-cases.json") }
    val editorManifest: JsonObject by lazy { resource("/oracle/Templates/template-editor-manifest.json") }

    val cases: Map<String, JsonObject> by lazy {
        val map = LinkedHashMap<String, JsonObject>()
        for (entry in contract["cases"]!!.jsonArray) map[entry.jsonObject["id"]!!.jsonPrimitive.content] = entry.jsonObject
        map
    }

    fun entry(caseId: String): JsonObject = cases[caseId] ?: throw IllegalStateException("no fixture case $caseId")

    /** The manifest's `caseIDs` for the contract file. */
    val manifestCaseIds: List<String> by lazy {
        val fixtures = manifest["fixtures"]!!.jsonArray.map { it.jsonObject }
        val fixture = fixtures.first { it["path"]!!.jsonPrimitive.content.endsWith("/template-apply-contract.json") }
        fixture["caseIDs"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    /** Cases only the upstream oracle can run, from the manifest's `oracleOnly` list (none in this manifest). */
    val oracleOnlyIds: Set<String> by lazy {
        val fixtures = manifest["fixtures"]!!.jsonArray.map { it.jsonObject }
        val fixture = fixtures.first { it["path"]!!.jsonPrimitive.content.endsWith("/template-apply-contract.json") }
        val entries = (fixture["oracleOnly"] ?: manifest["oracleOnly"]) as? JsonArray ?: JsonArray(emptyList())
        entries.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    }

    /** Apply cases not replayed yet. Empty: all 135 contract cases are replayed. */
    val notYetPorted: Set<String> = emptySet()

    /** The replayed list, as Actualist's `TemplateApplyOracleReplayTests.replayedCaseIDs` states it. */
    val swiftReplayedIds: List<String> by lazy {
        val named = listOf(
            "ended-schedule", "deleted-category", "priority-clamp", "average-missing-months", "yearly-sinking-fund",
            "schedule-by-id", "stale-note-goal-def", "tracking-budget", "jpy-currency", "leap-year-periodic",
            "year-boundary-by", "overwrite-existing-budget", "apply-skips-budgeted", "no-templates", "orphan-goal-reset",
        ).map { "tapply-named-$it" }
        val families = listOf("fixed", "priority", "schedule", "average-copy", "percent", "remainder", "goal-by", "limit-refill")
        val states = listOf("above", "below", "exact", "zero", "negative")
        named + families.flatMap { family -> states.flatMap { state -> (1..3).map { "tapply-generated-$family-$state-$it" } } }
    }

    val replayedIds: List<String> by lazy { cases.keys.filter { it !in oracleOnlyIds && it !in notYetPorted } }

    /** A JSON element as the oracle's text: `null` for JSON null. */
    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun isNull(element: JsonElement?): Boolean = element == null || element is JsonNull
}
