package com.sporez.actualist.core.importer

import com.sporez.actualist.core.schedules.ReplayMismatch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Replays the BankSync oracle contract (Actual v26.10.0 `reconcileTransactions` and `simpleFinBatchSync`)
 * through the production import reconcile, as Actualist's `BankSyncOracleReplayTests` does. Every replayed
 * case compares the plan classification, the rows, payees and rules after the call, and, for CSV, the
 * candidates production built.
 *
 * Mismatches a registered divergence accounts for are split off by exact case and path; every other mismatch
 * stays strict, so a case with a divergence still compares all its other fields. A registered divergence
 * must still be observed: a case that no longer diverges fails until its excuse is removed.
 */
@RunWith(Parameterized::class)
class BankSyncOracleReplayTest(private val caseId: String) {
    companion object {
        /** Cases only the upstream oracle can run (the manifest's `oracleOnly`): 19. */
        private val oracleOnly: Set<String> get() = BankSyncOracleFixture.oracleOnlyIds

        /**
         * Cases not replayed yet: none. Every case outside the manifest's oracle-only list is replayed,
         * including the CSV, bank and simplefin kinds.
         */
        private val notYetPorted: Set<String> = emptySet()

        private val replayed: List<String> = BankSyncOracleCaseIds.replayed.filter { it !in notYetPorted }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = replayed

        // Known divergences (config/parity/known-divergences.json, domain bank-sync-matching).

        /** OP-58: a matched off-budget row never takes a category, where the oracle fills it. */
        private val op58Paths: Map<String, Set<String>> = mapOf(
            "named-offbudget-match-rule-category" to setOf("transactions.ex-1.category"),
        )

        /** OP-59: the opening balance is derived from planned inserts, where the oracle subtracts the raw download. */
        private val op59Paths: Map<String, Set<String>> = mapOf(
            "named-opening-balance-pending-skipped" to setOf("transactions.new-2.amount"),
            "named-opening-balance-rule-deletes-row" to setOf("transactions.new-2.amount"),
            "named-opening-balance-rule-changes-amount" to setOf("transactions.new-2.amount"),
        )

        /** OP-60: the opening balance takes the earliest download day, where the oracle takes the last row's date. */
        private val op60Paths: Map<String, Set<String>> = mapOf(
            "named-opening-balance-date-from-last-row" to setOf("transactions.new-3.date"),
        )

        /** OP-61: a deleted row's id is skipped, where the oracle matches and rewrites the deleted row. */
        private val op61Paths: Map<String, Set<String>> = mapOf(
            "gen-imported-id-004" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-1.imported_payee", "transactions.ex-1.payee"),
            "gen-imported-id-030" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-1.imported_payee"),
            "gen-imported-id-032" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-1.imported_payee", "transactions.ex-1.payee"),
            "gen-imported-id-035" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-1.imported_payee"),
            "gen-imported-id-039" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-1.imported_payee"),
            "named-commit" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-del.imported_payee", "transactions.ex-del.payee"),
            "named-preview" to setOf("result.skippedDeleted", "result.updated"),
            "named-reimport-deleted-pref-false-csv" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-del.imported_payee", "transactions.ex-del.payee"),
            "named-reimport-deleted-option-false" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-del.imported_payee", "transactions.ex-del.payee"),
            "named-reimport-deleted-simplefin-false" to setOf("result.skippedDeleted", "result.updated", "transactions.ex-del.imported_payee"),
        )

        /** OP-62: children of an id-matched split parent are reserved, where the pinned oracle lets a download match one. */
        private val op62Paths: Map<String, Set<String>> = mapOf(
            "named-split-child-collision-with-parent-claimed" to setOf(
                "result.addedCount", "result.updated", "transactions.new-1", "transactions.sp-1.imported_payee", "transactions.sp-1.payee",
            ),
        )

        /** OP-66: a padded provider payee name is created trimmed, where the oracle keeps the spaces. */
        private val op66Paths: Map<String, Set<String>> = mapOf(
            "gen-payee-name-009" to setOf(
                "transactions.new-1.payee", "transactions.new-2.payee", "payees.new-payee:  padded name  ", "payees.new-payee:padded name",
            ),
            "named-rule-imported-payee-simplefin" to setOf(
                "transactions.new-1.payee", "payees.new-payee:  WHOLE FOODS #123  ", "payees.new-payee:WHOLE FOODS #123",
            ),
        )

        /** OP-67: an equal-distance tie picks the first stored row, where the oracle picks another. */
        private val op67Paths: Map<String, Set<String>> = mapOf(
            "gen-multi-013" to setOf("transactions.ex-3.imported_id", "transactions.ex-3.imported_payee", "transactions.ex-4.imported_id", "transactions.ex-4.imported_payee"),
            "gen-multi-029" to setOf("result.updated[0]", "transactions.ex-1.imported_payee", "transactions.ex-1.payee", "transactions.ex-3.imported_payee", "transactions.ex-3.payee"),
            "gen-multi-044" to setOf("result.updated[1]", "transactions.ex-2.imported_payee", "transactions.ex-3.imported_id", "transactions.ex-3.imported_payee", "transactions.ex-3.payee"),
            "gen-multi-045" to setOf("result.ignored", "result.updated", "transactions.ex-4.imported_id", "transactions.ex-4.imported_payee", "transactions.ex-4.payee"),
            "gen-multi-052" to setOf(
                "result.updated[0]", "transactions.ex-1.imported_id", "transactions.ex-1.imported_payee", "transactions.ex-1.payee",
                "transactions.ex-2.imported_id", "transactions.ex-2.imported_payee", "transactions.ex-2.payee",
            ),
            "gen-multi-055" to setOf("result.ignored", "result.updated", "transactions.ex-4.imported_payee"),
        )

        /** OP-68: a duplicated provider id matches a different row than the oracle's. */
        private val op68Paths: Map<String, Set<String>> = mapOf(
            "gen-imported-id-008" to setOf(
                "result.updated[0]", "result.updated[1]", "transactions.ex-1.imported_payee", "transactions.ex-2.imported_payee", "transactions.ex-2.payee",
            ),
        )

        private val divergences: List<Pair<String, Map<String, Set<String>>>> = listOf(
            "OP-58" to op58Paths,
            "OP-59" to op59Paths,
            "OP-60" to op60Paths,
            "OP-61" to op61Paths,
            "OP-62" to op62Paths,
            "OP-66" to op66Paths,
            "OP-67" to op67Paths,
            "OP-68" to op68Paths,
        )
    }

    @Test
    fun partitionAccountsForEveryContractCase() {
        // One parameterized instance runs the whole-contract checks.
        if (caseId != replayed.first()) return
        val fixtureIds = BankSyncOracleFixture.cases.keys
        assertEquals("contract ids match the manifest", BankSyncOracleFixture.manifestCaseIds, fixtureIds.toList())
        assertEquals(484, fixtureIds.size)
        assertEquals(19, oracleOnly.size)
        val partitions = listOf(replayed.toSet(), oracleOnly, notYetPorted)
        for (first in partitions.indices) {
            for (second in first + 1 until partitions.size) {
                assertTrue("partitions $first and $second overlap", partitions[first].intersect(partitions[second]).isEmpty())
            }
        }
        assertEquals(
            "every contract case is replayed, oracle-only or not yet ported",
            fixtureIds,
            partitions.flatMap { it }.toSet(),
        )
        assertEquals("replayed list has no duplicates", replayed.size, replayed.toSet().size)
        assertEquals(465, replayed.size)
        // A divergence is registered only for a replayed case.
        for ((op, table) in divergences) {
            assertTrue("$op names a case that is not replayed", replayed.containsAll(table.keys))
        }
    }

    @Test
    fun fixtureMatchesThePinnedOracleAndEnvironment() {
        if (caseId != replayed.first()) return
        val manifest = BankSyncOracleFixture.manifest
        val actual = manifest["actual"]!!.jsonObject
        assertEquals(BankSyncOracleFixture.PINNED_TAG, actual["tag"]!!.jsonPrimitive.content)
        assertEquals(BankSyncOracleFixture.PINNED_COMMIT, actual["commit"]!!.jsonPrimitive.content)
        assertEquals(BankSyncOracleFixture.FIXTURE_TIME_ZONE, manifest["timeZone"]!!.jsonPrimitive.content)
        assertEquals(BankSyncOracleFixture.FIXTURE_TODAY, manifest["today"]!!.jsonPrimitive.content)
        val oracle = BankSyncOracleFixture.contract["oracle"]!!.jsonObject
        assertEquals(BankSyncOracleFixture.PINNED_TAG, oracle["tag"]!!.jsonPrimitive.content)
        assertEquals(BankSyncOracleFixture.PINNED_COMMIT, oracle["commit"]!!.jsonPrimitive.content)
        val environment = BankSyncOracleFixture.contract["environment"]!!.jsonObject
        assertEquals(BankSyncOracleFixture.FIXTURE_TIME_ZONE, environment["timeZone"]!!.jsonPrimitive.content)
        assertEquals(BankSyncOracleFixture.FIXTURE_TODAY, environment["today"]!!.jsonPrimitive.content)
    }

    @Test
    fun replaysBankSyncCase() {
        val comparison = BankSyncOracleReplay.replay(BankSyncOracleFixture.entry(caseId))

        var remaining: List<ReplayMismatch> = comparison.mismatches
        for ((op, table) in divergences) {
            val paths = table[caseId] ?: continue
            val known = remaining.filter { it.path in paths }
            remaining = remaining.filter { it.path !in paths }
            assertTrue(
                "$op is registered for $caseId but the case no longer diverges: remove the excuse",
                known.isNotEmpty(),
            )
        }
        assertTrue(BankSyncComparison.report(caseId, remaining), remaining.isEmpty())
    }
}
