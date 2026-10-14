package com.sporez.actualist.core.rules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.transactions.PayeeWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RuleMatchesTest {
    private val day = LocalDate.of(2026, 10, 15)

    private fun draft(vararg conditions: RuleCondition, join: RuleConditionJoin = RuleConditionJoin.AND) = RuleDraft(
        stage = RuleStage.NORMAL,
        join = join,
        conditions = conditions.toList(),
        actions = listOf(RuleAction("set", "category", RuleValue.Text("dining"), "id")),
    )

    @Test
    fun payeeConditionCountsEveryTransactionOfThePayee() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val expected = PayeeWrites(database).usage().first { it.id == "cafe" }.transactionCount
            assertTrue(expected > 3)
            val preview = RuleMatches(database).matching(draft(RuleCondition("payee", "is", RuleValue.Text("cafe"), "id")), limit = 3)
            assertEquals(expected, preview.totalCount)
            assertEquals(3, preview.transactions.size)
            assertTrue(preview.transactions.all { it.payeeName == "Daily Grind" })
            // Newest first.
            assertEquals(preview.transactions.map { it.date }.sortedDescending(), preview.transactions.map { it.date })
        }
    }

    @Test
    fun conditionsJoinAndNarrowOrWiden() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val matches = RuleMatches(database)
            val cafe = RuleCondition("payee", "is", RuleValue.Text("cafe"), "id")
            val gas = RuleCondition("payee", "is", RuleValue.Text("gasstation"), "id")
            val cafeCount = matches.matching(draft(cafe)).totalCount
            val gasCount = matches.matching(draft(gas)).totalCount
            assertEquals(cafeCount + gasCount, matches.matching(draft(cafe, gas, join = RuleConditionJoin.OR), limit = 1).totalCount)
            assertEquals(0, matches.matching(draft(cafe, gas)).totalCount)
            // Only outflows of the cafe.
            val outflow = RuleCondition("amount", "lt", RuleValue.Num(0.0), "number")
            val inflow = RuleCondition("amount", "gt", RuleValue.Num(0.0), "number")
            val zero = RuleCondition("amount", "is", RuleValue.Num(0.0), "number")
            assertEquals(
                cafeCount,
                matches.matching(draft(cafe, outflow)).totalCount + matches.matching(draft(cafe, inflow)).totalCount + matches.matching(draft(cafe, zero)).totalCount,
            )
        }
    }

    @Test
    fun emptyConditionsMatchNothingAndLimitZeroKeepsTheCount() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val matches = RuleMatches(database)
            assertEquals(0, matches.matching(draft()).totalCount)
            val all = matches.matching(draft(RuleCondition("amount", "lt", RuleValue.Num(0.0), "number")), limit = 0)
            assertTrue(all.totalCount > 0)
            assertTrue(all.transactions.isEmpty())
        }
    }

    @Test
    fun payeesAreReferencedByTheirRules() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val rules = RuleEngine(database).fetchRules()
            assertTrue(RulePayees.rulesFor(rules, "cafe").isNotEmpty())
            assertTrue(RulePayees.rulesFor(rules, "no-such-payee").isEmpty())
        }
    }
}
