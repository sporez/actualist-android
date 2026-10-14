package com.sporez.actualist.core.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Replays the Transaction Query oracle contract (Q1 conditions, Q2 saved filters, Q3 split families
 * and the split modes) through the query and saved-filter paths, as Actualist's
 * `TransactionQueryOracleReplayTests` does. The compared and excluded fields are documented on
 * [TransactionQueryReplaySupport]. The partition test accounts for every fixture case.
 *
 * Two registered divergences are excused by exact field path only, and the test checks the divergence
 * is still there: OP-78 (free-text search returns the matching child alone, where Actual groups it
 * to the parent) and OP-79 (the all-rows read hides children of a deleted or missing parent).
 */
@RunWith(Parameterized::class)
class TransactionQueryOracleReplayTest(private val caseId: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = TransactionQueryReplaySupport.replayedCaseIds
    }

    @Test
    fun replaysCase() {
        val support = TransactionQueryReplaySupport
        val entry = support.cases.getValue(caseId)
        val expected: JsonElement
        val actual: JsonElement
        when (caseId) {
            in support.queryCaseIds -> {
                expected = support.expectedQuery(entry)
                actual = support.replayQuery(entry, support.world(entry["world"]!!.jsonPrimitive.content))
            }
            in support.savedFilterCaseIds -> {
                expected = support.expectedFilterCase(entry)
                actual = support.replayFilterCase(entry)
            }
            in support.splitModeCaseIds -> {
                expected = support.expectedSplitModes(entry)
                actual = support.replaySplitModes(entry, support.world(entry["world"]!!.jsonPrimitive.content))
            }
            else -> throw IllegalStateException("case $caseId is not replayed")
        }
        assertReplay(expected, actual)
    }

    private fun assertReplay(expected: JsonElement, actual: JsonElement) {
        val support = TransactionQueryReplaySupport
        val mismatches = support.compare(expected, actual)
        val divergence = support.divergences[caseId]
        if (divergence == null) {
            assertTrue(support.report(caseId, mismatches), mismatches.isEmpty())
            return
        }

        val (op, paths) = divergence
        val known = mismatches.filter { it.pathWithoutIndexes in paths }
        val strict = mismatches.filter { mismatch -> known.none { it === mismatch } }
        assertTrue(support.report(caseId, strict), strict.isEmpty())
        // The divergence must still exist: an empty set means the registration is stale.
        assertTrue("$caseId: divergence $op no longer reproduces; remove its registration", known.isNotEmpty())
        when (op) {
            "OP-78" -> assertEquals(
                JsonArray(listOf<JsonElement>(JsonPrimitive("family-target"))),
                actual.jsonObject["rootIDs"],
            )
            "OP-79" -> assertEquals(
                JsonArray(listOf<JsonElement>(JsonPrimitive("simple"))),
                actual.jsonObject["results"]!!.jsonObject["all"]!!.jsonObject["ids"],
            )
            else -> throw IllegalStateException("divergence $op has no positive check")
        }
    }
}

/** Accounts for every fixture case: replayed, oracle-only or a registered divergence. */
class TransactionQueryOraclePartitionTest {
    private val support = TransactionQueryReplaySupport

    @Test
    fun fixtureMatchesThePinnedOracle() {
        val oracle = support.contract["oracle"]!!.jsonObject
        assertEquals("v26.10.0", oracle["tag"]!!.jsonPrimitive.content)
        assertEquals("2bebdbaae0b701120a8cad9d56aafd64fa04c577", oracle["commit"]!!.jsonPrimitive.content)
        val actual = support.manifest["actual"]!!.jsonObject
        assertEquals("v26.10.0", actual["tag"]!!.jsonPrimitive.content)
        assertEquals("2bebdbaae0b701120a8cad9d56aafd64fa04c577", actual["commit"]!!.jsonPrimitive.content)
    }

    @Test
    fun partitionsEveryTransactionQueryFixtureCase() {
        val fixtureIds = support.cases.keys.toList()
        val manifestIds = support.manifest["fixtures"]!!.jsonArray.first().jsonObject["caseIDs"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("contract ids match the manifest", manifestIds, fixtureIds)
        assertEquals(51, fixtureIds.size)

        val manifestOracleOnly = support.manifest["oracleOnly"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["reason"]!!.jsonPrimitive.content
        }
        assertEquals("oracle-only ids match the manifest", manifestOracleOnly.keys, support.oracleOnlyCaseIds.toSet())
        for ((id, reason) in manifestOracleOnly) {
            assertEquals("oracle-only case $id has an unexpected reason", TransactionQueryReplaySupport.ORACLE_ONLY_REASON, reason)
        }

        val replayed = support.replayedCaseIds
        assertEquals(25, support.queryCaseIds.size)
        assertEquals(8, support.savedFilterCaseIds.size)
        assertEquals(5, support.splitModeCaseIds.size)
        assertEquals(38, replayed.size)
        assertEquals("a case is replayed twice", replayed.size, replayed.toSet().size)
        assertTrue("a case is both replayed and oracle-only", replayed.toSet().intersect(support.oracleOnlyCaseIds.toSet()).isEmpty())
        assertEquals(
            "every case is replayed or oracle-only",
            fixtureIds.toSet(),
            replayed.toSet() + support.oracleOnlyCaseIds.toSet(),
        )
        assertTrue(support.divergences.keys.all { it in replayed })
        assertEquals(setOf("q3-free-text-grouped-versus-physical", "dead-parent-excludes-orphan"), support.divergences.keys)
    }

    /** The oracle-only operators must come out of the projection as unsupported, never as a silent query. */
    @Test
    fun oracleOnlyConditionsProjectAsUnsupported() {
        for (id in support.oracleOnlyCaseIds) {
            val entry: JsonObject = support.cases.getValue(id)
            val condition = entry["condition"]!!.jsonObject
            val raw = SavedFilterJson.encodeValue(
                com.sporez.actualist.core.rules.RuleValue.fromJson(JsonArray(listOf<JsonElement>(condition))),
            )
            val projected = SavedFilter.project(
                "replay", "replay", raw, entry["conditionsOp"]!!.jsonPrimitive.content, false,
            )
            assertTrue("$id should be unsupported but is ${projected.compatibility}", !projected.isSupported)
            assertTrue(projected.queryConditions == null && projected.queryJoin == null)
        }
    }
}
