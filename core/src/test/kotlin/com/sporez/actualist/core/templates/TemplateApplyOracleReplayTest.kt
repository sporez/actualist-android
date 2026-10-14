package com.sporez.actualist.core.templates

import com.sporez.actualist.core.schedules.ReplayComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Accounts for every case of the Apply Templates contract, like Actualist's
 * `TemplateApplyOracleReplayTests.manifestPartitionsEveryContractCase`: each case is replayed,
 * oracle-only (named in the manifest) or listed in `notYetPorted`, and the fixture is the pinned oracle's.
 */
class TemplateApplyManifestTest {
    @Test
    fun partitionsEveryContractCase() {
        val contractIds = TemplateOracleFixture.cases.keys
        val manifestIds = TemplateOracleFixture.manifestCaseIds
        assertEquals("manifest case ids match the contract", manifestIds.toSet(), contractIds.toSet())
        assertEquals("manifest case ids are unique", manifestIds.size, manifestIds.toSet().size)
        assertEquals(135, contractIds.size)

        val replayed = TemplateOracleFixture.replayedIds
        val oracleOnly = TemplateOracleFixture.oracleOnlyIds
        val notYetPorted = TemplateOracleFixture.notYetPorted
        val problems = mutableListOf<String>()
        for (id in contractIds) {
            val homes = listOf(id in replayed, id in oracleOnly, id in notYetPorted).count { it }
            if (homes != 1) problems.add("$id is accounted for $homes times")
        }
        for (id in oracleOnly + notYetPorted) {
            if (id !in contractIds) problems.add("$id is not in the contract")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())

        // Actualist replays exactly these ids; so does this port.
        assertEquals(TemplateOracleFixture.swiftReplayedIds.toSet(), replayed.toSet())
        assertEquals(TemplateOracleFixture.swiftReplayedIds.size, replayed.size)
    }

    @Test
    fun fixtureIsPinnedToTheOracleBuild() {
        val actual = TemplateOracleFixture.manifest["actual"] as kotlinx.serialization.json.JsonObject
        val editor = TemplateOracleFixture.editorManifest
        assertEquals(actual["tag"], editor["tag"])
        assertEquals(actual["commit"], editor["commit"])
        assertEquals(actual["packageVersion"], editor["packageVersion"])
        assertEquals("2026-10", TemplateOracleFixture.currentMonth)
    }
}

/**
 * Replays the Apply Templates oracle contract through [BudgetTemplates]. Test A runs each case as the
 * fixture states it. Test B seeds each note-only definition from the oracle first, so the apply engine
 * runs on its own.
 *
 * Known divergences, excused by exact case id and required to still diverge (as Swift's
 * `withKnownIssue` does):
 *  - OP-24 (intended): a category with a null stored `goal_def` but a `#template` or `#goal` note gets
 *    no budget at apply, where the pinned Actual regenerates the definition from the note first. Test A
 *    only; Test B seeds the definition and matches.
 *  - OP-25 (intended): a stored definition that no longer matches its note refuses the whole apply
 *    (`tapply-named-stale-note-goal-def`). Both tests.
 */
@RunWith(Parameterized::class)
class TemplateApplyOracleReplayTest(private val caseId: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = TemplateOracleFixture.replayedIds
    }

    private fun detail(mismatches: List<com.sporez.actualist.core.schedules.ReplayMismatch>, refusal: String?): String {
        val lines = mutableListOf(ReplayComparison.report(caseId, mismatches))
        if (refusal != null) lines.add("$caseId production refused the apply: $refusal")
        return lines.joinToString("\n")
    }

    @Test
    fun replaysTemplateApplyCase() {
        val entry = TemplateOracleFixture.entry(caseId)
        val replay = TemplateApplyReplaySupport.replay(entry, TemplateOracleFixture.currentMonth, seedGoalDefsFromOracle = false)
        val mismatches = ReplayComparison.mismatches(replay.expected, replay.actual, "")
        val report = detail(mismatches, replay.refusal)

        if (caseId == TemplateApplyReplaySupport.STALE_REFUSAL_CASE_ID) {
            assertTrue("OP-25 no longer reproduces for $caseId; remove its exception", mismatches.isNotEmpty())
            assertTrue("$caseId should be refused as stale", replay.refusal != null)
        } else if (TemplateApplyReplaySupport.hasNoteOnlyTemplate(entry)) {
            assertTrue("OP-24 no longer reproduces for $caseId; remove its exception", mismatches.isNotEmpty())
        } else {
            assertTrue(report, mismatches.isEmpty())
        }
        if (TemplateApplyReplaySupport.isUpToDate(entry)) {
            assertEquals("$caseId is up to date but produced messages", 0, replay.messageCount)
        }
    }

    @Test
    fun replaysTemplateEngineWithStoredDefinitions() {
        val entry = TemplateOracleFixture.entry(caseId)
        val replay = TemplateApplyReplaySupport.replay(entry, TemplateOracleFixture.currentMonth, seedGoalDefsFromOracle = true)
        val mismatches = ReplayComparison.mismatches(replay.expected, replay.actual, "")
        val report = detail(mismatches, replay.refusal)
        if (caseId == TemplateApplyReplaySupport.STALE_REFUSAL_CASE_ID) {
            assertTrue("OP-25 no longer reproduces for $caseId; remove its exception", mismatches.isNotEmpty())
        } else {
            assertTrue(report, mismatches.isEmpty())
        }
    }
}
