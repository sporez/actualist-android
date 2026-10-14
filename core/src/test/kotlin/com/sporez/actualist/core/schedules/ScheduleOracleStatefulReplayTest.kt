package com.sporez.actualist.core.schedules

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Stateful Schedules cases. `replaysScheduleSteps` seeds the fixture's upstream rows before each
 * step and runs the production operation; `replaysScheduleCreation` replays `create` for each
 * setup schedule. See [ScheduleStatefulReplay]. Mismatches a registered divergence accounts for
 * are split off by exact path; every other mismatch stays a strict failure.
 *
 * The two oracle-only cases (`advance-sync-failed`, `set-next-date-plain`) are named in the
 * fixture manifest and are not replayed.
 */
@RunWith(Parameterized::class)
class ScheduleOracleStatefulReplayTest(private val caseId: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = ScheduleOracleFixture.statefulIds

        // Known divergences (config/parity/known-divergences.json), by exact mismatch path.

        /** OP-51: skipping a one-time schedule is refused, where Actual leaves it unchanged. */
        private val op51Paths: Map<String, Set<String>> = mapOf(
            "skip-one-time" to setOf("step0(skip).completes"),
        )

        /**
         * OP-52: a schedule created on a day starts its next-date search at that day, where Actual
         * starts at the day before in a time zone behind UTC.
         */
        private val op52Paths: Map<String, Set<String>> = mapOf(
            "create-negative-offset-timezone" to setOf(
                "created.nextDates.schedule-s1.base_next_date",
                "created.nextDates.schedule-s1.local_next_date",
            ),
        )

        /** OP-53: creating a schedule on a closed account is refused, where Actual accepts it. */
        private val op53Paths: Map<String, Set<String>> = mapOf(
            "advance-closed-account-skipped" to setOf("created.completes"),
        )
    }

    /** Splits [mismatches] into those not excused and those the divergence table names for this case. */
    private fun take(mismatches: List<ReplayMismatch>, table: Map<String, Set<String>>): Pair<List<ReplayMismatch>, List<ReplayMismatch>> {
        val paths = table[caseId] ?: return mismatches to emptyList()
        return mismatches.filter { it.path !in paths } to mismatches.filter { it.path in paths }
    }

    @Test
    fun replaysScheduleSteps() {
        val results = ScheduleStatefulReplay.replaySteps(ScheduleOracleFixture.entry(caseId))
        assertTrue("$caseId replayed no steps", results.isNotEmpty())
        val (remaining, op51) = take(results.flatMap { it.mismatches }, op51Paths)
        assertTrue(ReplayComparison.report(caseId, remaining), remaining.isEmpty())
        if (op51.isNotEmpty()) println("$caseId: OP-51 excused ${op51.size} mismatch(es): ${op51.joinToString()}")
    }

    @Test
    fun replaysScheduleCreation() {
        val results = ScheduleStatefulReplay.replayCreation(ScheduleOracleFixture.entry(caseId))
        val (afterOp52, op52) = take(results.flatMap { it.mismatches }, op52Paths)
        val (remaining, op53) = take(afterOp52, op53Paths)
        assertTrue(ReplayComparison.report(caseId, remaining), remaining.isEmpty())
        if (op52.isNotEmpty()) println("$caseId: OP-52 excused ${op52.size} mismatch(es): ${op52.joinToString()}")
        if (op53.isNotEmpty()) println("$caseId: OP-53 excused ${op53.size} mismatch(es): ${op53.joinToString()}")
    }
}
