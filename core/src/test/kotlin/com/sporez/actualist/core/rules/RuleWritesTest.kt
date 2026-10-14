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
    fun preStageNotesActionsAndIsNotRoundTripAndRun() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val draft = RuleDraft(
                stage = RuleStage.PRE,
                join = RuleConditionJoin.AND,
                conditions = listOf(
                    RuleCondition("imported_payee", "contains", RuleValue.Text("bakery"), "string"),
                    RuleCondition("notes", "isNot", RuleValue.Text("skip"), "string"),
                ),
                actions = listOf(
                    RuleAction("prepend-notes", null, RuleValue.Text("[bakery] ")),
                    RuleAction("append-notes", null, RuleValue.Text(" #food")),
                ),
            )
            val (id, messages) = RuleWrites(database) { "rule-pre" }.create(draft)
            val stored = messages.associate { it.column to it.serializedValue }
            // As Actual stores them: notes actions carry no field or type.
            assertEquals("""S:[{"op":"prepend-notes","value":"[bakery] "},{"op":"append-notes","value":" #food"}]""", stored["actions"])
            assertEquals("S:pre", stored["stage"])
            val engine = RuleEngine(database)
            assertEquals(draft, engine.fetchRules().first { it.id == id }.draft)
            val hit = engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -650, importedPayee = "CORNER BAKERY", notes = "Bread"), day)
            assertEquals("[bakery] Bread #food", hit.notes)
            val miss = engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -650, importedPayee = "CORNER BAKERY", notes = "skip"), day)
            assertTrue(id !in miss.appliedRuleIds)
        }
    }

    @Test
    fun amountBetweenRoundTripsAndMatchesEitherOrder() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val range = RuleValue.Obj(mapOf("num1" to RuleValue.Num(-1_000.0), "num2" to RuleValue.Num(-5_000.0)))
            val draft = RuleDraft(
                stage = RuleStage.NORMAL,
                join = RuleConditionJoin.AND,
                conditions = listOf(RuleCondition("amount", "isbetween", range, "number")),
                actions = listOf(RuleAction("set", "notes", RuleValue.Text("Mid-size"), "string")),
            )
            val (id, messages) = RuleWrites(database) { "rule-between" }.create(draft)
            assertEquals(
                """S:[{"field":"amount","op":"isbetween","type":"number","value":{"num1":-1000,"num2":-5000}}]""",
                messages.associate { it.column to it.serializedValue }["conditions"],
            )
            val engine = RuleEngine(database)
            assertEquals(draft, engine.fetchRules().first { it.id == id }.draft)
            assertEquals("Mid-size", engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -5_000), day).notes)
            assertTrue(id !in engine.run(RuleTransactionInput(accountId = "checking", date = day, amount = -5_001), day).appliedRuleIds)
        }
    }

    @Test
    fun oneOfAndApproximateConditionsRoundTripAndMatch() {
        DemoBudget.create(TestDatabases.driver, TestDatabases.tempFile().absolutePath, day, TestClock()).use { database ->
            val draft = RuleDraft(
                stage = RuleStage.NORMAL,
                join = RuleConditionJoin.AND,
                conditions = listOf(
                    RuleCondition("account", "oneOf", RuleValue.Arr(listOf(RuleValue.Text("checking"), RuleValue.Text("credit"))), "id"),
                    RuleCondition("amount", "isapprox", RuleValue.Num(-5_000.0), "number"),
                ),
                actions = listOf(RuleAction("set", "notes", RuleValue.Text("Approximately fifty"), "string")),
            )
            val (id, _) = RuleWrites(database) { "rule-oneof" }.create(draft)
            val engine = RuleEngine(database)
            assertEquals(draft, engine.fetchRules().first { it.id == id }.draft)
            val hit = engine.run(RuleTransactionInput(accountId = "credit", date = day, amount = -5_100), day)
            assertEquals("Approximately fifty", hit.notes)
            val miss = engine.run(RuleTransactionInput(accountId = "savings", date = day, amount = -5_000), day)
            assertTrue(id !in miss.appliedRuleIds)
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
