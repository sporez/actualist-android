package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.ScheduleRecurrence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Accounts for every case of the Schedules contract, like Actualist's
 * `ScheduleOracleReplayTests.manifestPartitionsEveryContractCase`: each case is replayed,
 * oracle-only (named in the manifest), or listed in `notYetPorted`, and the fixture is the pinned
 * oracle's.
 */
class ScheduleOracleManifestTest {
    @Test
    fun partitionsEveryContractCase() {
        val contractIds = ScheduleOracleFixture.cases.keys
        val manifestIds = ScheduleOracleFixture.manifest["fixtures"]!!.jsonArray.single().jsonObject["caseIDs"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("manifest case ids match the contract", manifestIds.toSet(), contractIds.toSet())
        assertEquals("manifest case ids are unique", manifestIds.size, manifestIds.toSet().size)

        val replayed = ScheduleOracleFixture.recurrenceIds + ScheduleOracleFixture.statusIds + ScheduleOracleFixture.statefulIds
        val oracleOnly = ScheduleOracleFixture.oracleOnlyIds
        val notYetPorted = ScheduleOracleFixture.notYetPorted
        val problems = mutableListOf<String>()
        for (id in contractIds) {
            val homes = listOf(id in replayed, id in oracleOnly, id in notYetPorted).count { it }
            if (homes != 1) problems.add("$id is accounted for $homes times")
        }
        for (id in replayed + oracleOnly + notYetPorted) {
            if (id !in contractIds) problems.add("$id is not in the contract")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())

        val counts = ScheduleOracleFixture.manifest["familyCounts"]!!.jsonObject
        for (family in listOf("recurrence", "status", "stateful")) {
            assertEquals(
                "$family family size",
                counts[family]!!.jsonPrimitive.content.toInt(),
                ScheduleOracleFixture.idsOfFamily(family).size,
            )
        }
        assertEquals(counts["recurrence"]!!.jsonPrimitive.content.toInt(), ScheduleOracleFixture.recurrenceIds.size)
        assertEquals(counts["status"]!!.jsonPrimitive.content.toInt(), ScheduleOracleFixture.statusIds.size)
        // The manifest's oracle-only cases are all stateful ones.
        assertEquals(
            counts["stateful"]!!.jsonPrimitive.content.toInt() - oracleOnly.size,
            ScheduleOracleFixture.statefulIds.size,
        )
    }

    @Test
    fun fixtureUsesTheExpectedZoneAndDay() {
        assertEquals(ScheduleOracleFixture.timeZone.id, ScheduleOracleFixture.manifest["timeZone"]!!.jsonPrimitive.content)
        assertEquals(ScheduleOracleFixture.FIXTURE_TODAY, ScheduleOracleFixture.manifest["today"]!!.jsonPrimitive.content)
        assertEquals("v26.10.0", ScheduleOracleFixture.manifest["actual"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
    }
}

/**
 * Replays `getNextDate` (with and without weekend skipping) and `getNextDateAfter` through the
 * production schedule code. The condition goes through `ScheduleRuleProjection.dateRuleFrom`, the
 * decoder production applies to stored rules; `getNextDate` is `ScheduleRuleMutation.initialNextDate`
 * and `getNextDateAfter` is `ScheduleRuleMutation.nextDateAfterSkip`. Errors are not compared by
 * wording: an oracle error and a null result both mean "no date", and a production throw or null
 * is the same. The oracle's `occurrences` and `upcoming` fields have no production counterpart.
 */
@RunWith(Parameterized::class)
class ScheduleRecurrenceOracleReplayTest(private val caseId: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = ScheduleOracleFixture.recurrenceIds
    }

    private class QueryResult(val label: String, val expected: OracleValue, val actual: OracleValue) {
        val mismatches: List<ReplayMismatch> get() = ReplayComparison.mismatches(expected, actual, label)
    }

    @Test
    fun replaysCase() {
        val entry = ScheduleOracleFixture.entry(caseId)
        val results = replay(entry)
        assertTrue("$caseId replayed no queries", results.isNotEmpty())
        val mismatches = results.flatMap { it.mismatches }
        assertTrue(ReplayComparison.report(caseId, mismatches), mismatches.isEmpty())
    }

    private fun replay(entry: JsonObject): List<QueryResult> {
        val conditionJson = entry["condition"]!!.jsonObject
        val condition = RuleJson.decodeConditions("[$conditionJson]")?.singleOrNull()
            ?: throw IllegalStateException("$caseId: condition does not decode")
        val rule = ScheduleRuleProjection.dateRuleFrom(condition)
        val recurrence = rule.recurrence
        val skipsWeekend = (conditionJson["value"] as? JsonObject)?.get("skipWeekend")?.jsonPrimitive?.booleanOrNull == true

        val results = mutableListOf<QueryResult>()
        for (query in entry["queries"]!!.jsonArray.map { it.jsonObject }) {
            val from = query["from"]!!.jsonPrimitive.content

            fun expectedDate(key: String): OracleValue {
                val outcome = query[key]!!.jsonObject
                return OracleValue.string(ScheduleOracleFixture.string(outcome["value"]))
            }

            fun result(key: String, actual: String?) =
                QueryResult("$from.$key", expectedDate(key), OracleValue.string(actual))

            results.add(result("nextDate", nextDate(rule, from, true)))
            results.add(result("nextDateNoSkipWeekend", nextDate(rule, from, false)))
            // A one-time schedule cannot be skipped in production, so only recurrences compare the
            // skip date. The skip date starts from a schedule's current next date, which a
            // weekend-skipping schedule never has on a Saturday or Sunday, so those `from` days are
            // not reachable inputs and are not compared.
            if (recurrence != null && !(skipsWeekend && isWeekend(from))) {
                val skipped = try {
                    ScheduleRuleMutation.nextDateAfterSkip(recurrence, from)
                } catch (e: Exception) {
                    null
                }
                results.add(result("nextDateAfter", skipped))
            }
        }
        return results
    }

    private fun isWeekend(dayId: String): Boolean {
        val date = ScheduleRecurrence.parseDay(dayId) ?: return false
        val weekday = ScheduleRecurrence.calendarWeekday(date)
        return weekday == 1 || weekday == 7
    }

    private fun nextDate(rule: ScheduleDateRule, from: String, adjustingWeekend: Boolean): String? = when (rule) {
        is ScheduleDateRule.OneTime -> rule.dayId
        is ScheduleDateRule.Unavailable -> null
        is ScheduleDateRule.Recurring -> try {
            if (adjustingWeekend) {
                ScheduleRuleMutation.initialNextDate(rule, from)
            } else {
                rule.recurrenceValue.nextOccurrence(from, false) ?: rule.recurrenceValue.lastOccurrence(false)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Replays `getStatus` and `getUpcomingDays` through `ScheduleStatus.resolve` and
 * `ScheduleUpcomingLength.days`: exactly the calls the schedule read path makes for each schedule
 * (with the stored `upcomingLength`, or "7" when no preference is stored).
 */
@RunWith(Parameterized::class)
class ScheduleStatusOracleReplayTest(private val caseId: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = ScheduleOracleFixture.statusIds
    }

    @Test
    fun replaysCase() {
        val entry = ScheduleOracleFixture.entry(caseId)
        val today = entry["today"]!!.jsonPrimitive.content
        val length = entry["upcomingLength"]?.jsonPrimitive?.contentOrNull ?: "7"
        val mismatches = mutableListOf<ReplayMismatch>()

        val oracleDays = entry["upcomingDays"]!!.jsonObject
        mismatches.addAll(
            ReplayComparison.mismatches(
                OracleValue.fromJson(oracleDays["value"]),
                OracleValue.number(ScheduleUpcomingLength.days(length, today)),
                "upcomingDays",
            ),
        )
        val rows = entry["rows"]!!.jsonArray.map { it.jsonObject }
        assertTrue("$caseId has no rows", rows.isNotEmpty())
        for ((index, row) in rows.withIndex()) {
            val nextDate = row["nextDate"]!!.jsonPrimitive.content
            val completed = row["completed"]?.jsonPrimitive?.booleanOrNull ?: false
            val hasTrans = row["hasTrans"]?.jsonPrimitive?.booleanOrNull ?: false
            val status = ScheduleStatus.resolve(nextDate, completed, hasTrans, today, length)
            mismatches.addAll(
                ReplayComparison.mismatches(
                    OracleValue.fromJson(row["status"]),
                    OracleValue.Str(status.raw),
                    "row$index[$nextDate ${if (completed) "completed" else "open"}/${if (hasTrans) "paid" else "unpaid"}].status",
                ),
            )
        }
        assertTrue(ReplayComparison.report(caseId, mismatches), mismatches.isEmpty())
    }
}
