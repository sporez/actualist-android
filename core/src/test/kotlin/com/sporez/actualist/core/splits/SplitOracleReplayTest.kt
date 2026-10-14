package com.sporez.actualist.core.splits

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.sporez.actualist.core.schedules.OracleValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Replays each Splits oracle fixture (Actual v26.10.0) through the production split family ops or
 * the rule-split executor, as Actualist's `SplitOracleReplayTests` and
 * `ActualCoreSplitOracleFixtureTests` do. Every manifest case is replayed, oracle-only, excused or
 * not yet ported; a case in none of those sets fails the partition test.
 *
 * `known-divergences.json` registers no divergences for the splits domain, so nothing is excused.
 * `grouped-query-cases.json` is not a replay input in Actualist either: it is covered by the
 * transaction-query oracle replay.
 */
class SplitOracleReplayTest {
    companion object {
        private const val FAMILY_FILE = "family-transformations.json"
        private const val RULE_FILE = "split-rule-cases.json"
        private const val MUTATION_FILE = "mutation-cases.json"

        private val familyCaseIds = listOf(
            "make-child-inheritance-and-null-overrides",
            "recalculate-exact-mismatch-and-mixed-sign",
            "low-level-split-conversion-nulls-parent-payee-and-starts-with-error",
            "split-conversion-zero-sort-order",
            "update-conversion-materializes-exact-family",
            "parent-payee-update-preserves-child-override",
            "child-delete-recalculates-parent-error",
            "final-child-delete-collapses-parent",
        )

        private val ruleCaseIds = listOf(
            "index-zero-whole-transaction-and-one-based-children",
            "negative-half-percent-rounding",
            "positive-half-percent-rounding",
            "multiple-negative-remainders-adjust-highest-index",
            "fixed-before-percent-before-remainder",
            "formula-and-remainder",
            "effective-child-input-is-not-split",
        )

        private val mutationCaseIds = listOf(
            "add-child-appends-zero-remainder",
            "child-amount-update-recalculates-error",
            "update-conversion-materializes-exact-family",
            "child-amount-changed-to-zero",
            "parent-date-account-cleared-propagate",
            "parent-payee-update-preserves-child-override",
            "child-delete-recalculates-parent-error",
            "final-child-delete-collapses-parent",
        )

        /** Cases excused by an exact known divergence. None are registered for splits. */
        private val excused: Set<String> = emptySet()

        /** Cases not replayed yet. Empty: every non-oracle-only case is replayed. */
        private val notYetPorted: Set<String> = emptySet()
    }

    private fun replayAll(fileName: String, caseIds: List<String>) {
        val cases = SplitFixtures.cases(fileName)
        val failures = mutableListOf<String>()
        for (caseId in caseIds) {
            val entry = cases[caseId] ?: throw IllegalStateException("$fileName has no case $caseId")
            val actual = SplitOracleReplay.replay(entry["input"]!!.jsonObject)
            val expected = OracleValue.fromJson(entry["expected"])
            val comparison = SplitComparison()
            comparison.compare(SplitOracleReplay.normalized(expected), SplitOracleReplay.normalized(actual))
            if (comparison.mismatches.isNotEmpty()) failures.add(SplitComparison.report(caseId, comparison.mismatches))
        }
        assertTrue("$fileName:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun replaysFamilyTransformations() = replayAll(FAMILY_FILE, familyCaseIds)

    @Test
    fun replaysSplitRules() = replayAll(RULE_FILE, ruleCaseIds)

    @Test
    fun replaysSplitMutations() = replayAll(MUTATION_FILE, mutationCaseIds)

    @Test
    fun partitionsEverySplitsFixtureCase() {
        val files = listOf(FAMILY_FILE to familyCaseIds, RULE_FILE to ruleCaseIds, MUTATION_FILE to mutationCaseIds)
        for ((fileName, replayed) in files) {
            val fixtureIds = SplitFixtures.cases(fileName).keys
            val manifestIds = SplitFixtures.manifestCaseIds(fileName)
            val oracleOnly = SplitFixtures.oracleOnly(fileName)
            assertEquals("$fileName: fixture case ids differ from manifest caseIDs", manifestIds, fixtureIds)
            assertEquals("$fileName: a replayed case id is listed twice", replayed.size, replayed.toSet().size)
            val replayedSet = replayed.toSet()
            assertTrue("$fileName: replayed and oracle-only ids overlap", replayedSet.intersect(oracleOnly.keys).isEmpty())
            assertEquals(
                "$fileName: replayed, oracle-only, excused and not-yet-ported ids do not cover the manifest",
                manifestIds,
                replayedSet + oracleOnly.keys + excused.filter { it in manifestIds } + notYetPorted.filter { it in manifestIds },
            )
            for ((id, reason) in oracleOnly) assertTrue("$fileName: oracle-only case $id has no reason", reason.isNotBlank())
        }
    }

    @Test
    fun manifestPinsActualAndEveryCommittedFixtureHash() {
        val manifest = SplitFixtures.manifest
        assertEquals(1, manifest["schemaVersion"]!!.jsonPrimitive.content.toInt())
        val actual = manifest["actual"]!!.jsonObject
        assertEquals("v26.10.0", actual["tag"]!!.jsonPrimitive.content)
        assertEquals("2bebdbaae0b701120a8cad9d56aafd64fa04c577", actual["commit"]!!.jsonPrimitive.content)
        assertEquals("26.10.0", actual["packageVersion"]!!.jsonPrimitive.content)
        assertEquals("integer minor units", manifest["amountUnits"]!!.jsonPrimitive.content)
        assertEquals("JavaScript Math.round: ties toward positive infinity", manifest["rounding"]!!.jsonPrimitive.content)
        assertTrue(manifest["sourceFiles"]!!.jsonArray.size >= 10)

        for (fixture in manifest["fixtures"]!!.jsonArray) {
            val entry = fixture.jsonObject
            val fileName = entry["path"]!!.jsonPrimitive.content.substringAfterLast('/')
            val digest = MessageDigest.getInstance("SHA-256").digest(SplitFixtures.bytes(fileName))
            val hex = digest.joinToString("") { byte -> String.format(java.util.Locale.ROOT, "%02x", byte) }
            assertEquals("$fileName hash", entry["sha256"]!!.jsonPrimitive.content, hex)
            val root = SplitFixtures.resource(fileName)
            assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
            assertEquals(actual["commit"]!!.jsonPrimitive.content, root["oracle"]!!.jsonObject["commit"]!!.jsonPrimitive.content)
            assertEquals(actual["packageVersion"]!!.jsonPrimitive.content, root["oracle"]!!.jsonObject["packageVersion"]!!.jsonPrimitive.content)
            val ids = (root["cases"] as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertEquals("$fileName case count", entry["caseCount"]!!.jsonPrimitive.content.toInt(), ids.size)
            assertEquals("$fileName duplicate case ids", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun ruleVectorsLockOneBasedTargetsAndJavaScriptTieRounding() {
        val cases = SplitFixtures.cases(RULE_FILE)

        fun childAmounts(id: String): List<Long> =
            cases.getValue(id)["expected"]!!.jsonObject["children"]!!.jsonArray
                .map { it.jsonObject["amount"]!!.jsonPrimitive.content.toDouble().toLong() }

        val indexes = cases.getValue("index-zero-whole-transaction-and-one-based-children")
        val splitIndexes = indexes["actions"]!!.jsonArray.mapNotNull {
            it.jsonObject["options"]?.jsonObject?.get("splitIndex")?.jsonPrimitive?.content?.toIntOrNull()
        }
        assertEquals(listOf(0, 1, 1, 2, 2), splitIndexes)
        assertEquals(listOf(-4_000L, -6_000L), childAmounts("index-zero-whole-transaction-and-one-based-children"))
        assertEquals(listOf(-2L, -3L), childAmounts("negative-half-percent-rounding"))
        assertEquals(listOf(3L, 2L), childAmounts("positive-half-percent-rounding"))
        assertEquals(listOf(30_000L, 70_000L), childAmounts("formula-and-remainder"))
        val childInput = cases.getValue("effective-child-input-is-not-split")["expected"]!!.jsonObject
        assertEquals("true", childInput["parent"]!!.jsonObject["isChild"]!!.jsonPrimitive.content)
        assertTrue(childInput["children"]!!.jsonArray.isEmpty())
    }
}
