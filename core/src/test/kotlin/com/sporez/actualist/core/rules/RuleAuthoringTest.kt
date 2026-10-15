package com.sporez.actualist.core.rules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RuleAuthoringTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun newConditionFieldsAndActionsRoundTrip() {
        val draft = RuleDraft(
            stage = RuleStage.NORMAL,
            join = RuleConditionJoin.AND,
            conditions = listOf(
                RuleCondition("cleared", "is", RuleValue.Bool(true), "boolean"),
                RuleCondition("reconciled", "is", RuleValue.Bool(false), "boolean"),
                RuleCondition("payee_name", "oneOf", RuleValue.Arr(listOf(RuleValue.Text("Corner Bakery"), RuleValue.Text("Deli"))), "string"),
                RuleCondition("imported_payee", "notOneOf", RuleValue.Arr(listOf(RuleValue.Text("ACME 12"))), "string"),
                RuleCondition("category_group", "is", RuleValue.Text("group-bills"), "id"),
            ),
            actions = listOf(
                RuleAction("set", "amount", RuleValue.Num(-1250.0), "number"),
                RuleAction("set", "date", RuleValue.Text("2026-10-01"), "date"),
                RuleAction("set", "account", RuleValue.Text("checking"), "id"),
            ),
        )
        assertTrue(draft.canRoundTripAndEvaluate)
        assertTrue(RuleDraft(RuleStage.POST, RuleConditionJoin.OR, draft.conditions, listOf(RuleAction("delete-transaction"))).canRoundTripAndEvaluate)
        // Notes have no "one of": Actual refuses it, so the draft cannot be written.
        assertFalse(RuleCondition("notes", "oneOf", RuleValue.Arr(listOf(RuleValue.Text("a"))), "string").canRoundTripAndEvaluate)
    }

    @Test
    fun deleteAndSetActionsAreStoredAndReadBack() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val draft = RuleDraft(
                stage = RuleStage.NORMAL,
                join = RuleConditionJoin.AND,
                conditions = listOf(RuleCondition("cleared", "is", RuleValue.Bool(true), "boolean")),
                actions = listOf(RuleAction("set", "amount", RuleValue.Num(-500.0), "number"), RuleAction("delete-transaction")),
            )
            val (id, messages) = RuleWrites(database) { "rule-authored" }.create(draft)
            assertEquals("rule-authored", id)
            val stored = messages.associate { it.column to it.serializedValue }
            assertEquals("""S:[{"field":"cleared","op":"is","type":"boolean","value":true}]""", stored["conditions"])
            assertEquals("""S:[{"field":"amount","op":"set","type":"number","value":-500},{"op":"delete-transaction","value":null}]""", stored["actions"])
            assertEquals(draft, RuleEngine(database).fetchRules().first { it.id == id }.draft)
        }
    }

    @Test
    fun clearedAndPayeeNameConditionsMatch() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val draft = RuleDraft(
                stage = RuleStage.NORMAL,
                join = RuleConditionJoin.AND,
                conditions = listOf(
                    RuleCondition("cleared", "is", RuleValue.Bool(true), "boolean"),
                    RuleCondition("payee_name", "oneOf", RuleValue.Arr(listOf(RuleValue.Text("Corner Bakery"))), "string"),
                ),
                actions = listOf(RuleAction("set", "notes", RuleValue.Text("Seen"), "string")),
            )
            RuleWrites(database) { "rule-cleared" }.create(draft)
            val engine = RuleEngine(database)
            val matching = engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -650, payeeName = "Corner Bakery", cleared = true), day)
            assertEquals("Seen", matching.notes)
            val uncleared = engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -650, payeeName = "Corner Bakery", cleared = false), day)
            assertTrue(uncleared.notes != "Seen")
        }
    }

    @Test
    fun dateValuesKeepTheirPrecision() {
        assertEquals(RuleDatePrecision.YEAR, RuleDateValues.precision("2026"))
        assertEquals(RuleDatePrecision.MONTH, RuleDateValues.precision("2026-03"))
        assertEquals(RuleDatePrecision.DAY, RuleDateValues.precision("2026-03-09"))
        assertNull(RuleDateValues.precision("2026-3"))
        assertEquals(LocalDate.of(2026, 3, 1), RuleDateValues.anchor("2026-03"))
        assertNull(RuleDateValues.anchor("2026-13"))
        val picked = LocalDate.of(2026, 3, 9)
        assertEquals("2026", RuleDateValues.format(picked, RuleDatePrecision.YEAR))
        assertEquals("2026-03", RuleDateValues.format(picked, RuleDatePrecision.MONTH))
        assertEquals("2026-03-09", RuleDateValues.format(picked, RuleDatePrecision.DAY))
        assertEquals("2026-03-01", RuleDateValues.convert("2026-03", RuleDatePrecision.DAY))
        assertEquals("", RuleDateValues.convert("", RuleDatePrecision.DAY))
        assertTrue(RuleDateValues.allowsPartial("is"))
        assertFalse(RuleDateValues.allowsPartial("gt"))
        assertTrue(RuleCondition("date", "is", RuleValue.Text("2026-03"), "date").canRoundTripAndEvaluate)
        assertFalse(RuleCondition("date", "gt", RuleValue.Text("2026-03"), "date").canRoundTripAndEvaluate)
    }
}
