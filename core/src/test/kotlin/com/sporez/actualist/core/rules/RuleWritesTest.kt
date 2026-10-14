package com.sporez.actualist.core.rules

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.demo.DemoBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RuleWritesTest {
    private val day = LocalDate.of(2026, 10, 15)

    @Test
    fun rulesRoundTripThroughStorageAndRun() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val writes = RuleWrites(database) { "rule-new" }
            val draft = RuleDraft(
                stage = RuleStage.NORMAL,
                join = RuleConditionJoin.AND,
                conditions = listOf(
                    RuleCondition("imported_payee", "contains", RuleValue.Text("bakery"), "string"),
                    RuleCondition("amount", "lt", RuleValue.Num(-100.0), "number"),
                ),
                actions = listOf(
                    RuleAction("set", "category", RuleValue.Text("groceries"), "id"),
                    RuleAction("set", "notes", RuleValue.Text("Bread"), "string"),
                ),
            )
            val (id, messages) = writes.create(draft)
            assertEquals("rule-new", id)
            val stored = messages.associate { it.column to it.serializedValue }
            assertEquals(
                """S:[{"field":"imported_payee","op":"contains","type":"string","value":"bakery"},{"field":"amount","op":"lt","type":"number","value":-100}]""",
                stored["conditions"],
            )
            assertEquals("""S:[{"field":"category","op":"set","type":"id","value":"groceries"},{"field":"notes","op":"set","type":"string","value":"Bread"}]""", stored["actions"])
            assertEquals("0:", stored["stage"])
            assertEquals("S:and", stored["conditions_op"])
            assertEquals("N:0", stored["tombstone"])

            val engine = RuleEngine(database)
            assertEquals(draft, engine.fetchRules().first { it.id == id }.draft)
            val result = engine.run(
                RuleTransactionInput(accountId = "checking", date = day, amount = -650, payeeName = "Corner Bakery", importedPayee = "CORNER BAKERY 42"),
                day,
            )
            assertEquals("groceries", result.categoryId)
            assertEquals("Bread", result.notes)
            assertTrue(id in result.appliedRuleIds)

            writes.update(id, draft.copy(actions = listOf(RuleAction("set", "category", RuleValue.Text("dining"), "id"))))
            assertEquals("dining", RuleEngine(database).run(RuleTransactionInput(accountId = "checking", date = day, amount = -650, importedPayee = "bakery"), day).categoryId)

            writes.delete(id)
            assertTrue(RuleEngine(database).fetchRules().none { it.id == id })
        }
    }

    @Test
    fun rulesTheEngineCannotEvaluateAreRefused() {
        TestDatabases.starter(clock = TestClock()).use { database ->
            val bad = RuleDraft(RuleStage.NORMAL, RuleConditionJoin.AND, listOf(RuleCondition("notes", "contains", RuleValue.Text(""), "string")), listOf(RuleAction("set", "notes", RuleValue.Text("x"), "string")))
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { RuleWrites(database).create(bad) }
            assertThrows(LocalFirstException.InvalidLocalWrite::class.java) { RuleWrites(database).delete("missing") }
        }
    }
}
