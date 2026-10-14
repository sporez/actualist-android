package com.sporez.actualist.core.budget

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private fun resource(path: String): JsonObject =
    Json.parseToJsonElement(HoldOracleReplayTest::class.java.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }).jsonObject

/**
 * Replays Actualist's pinned Holds oracle (14 replayable cases of Actual v26.10.0's hold
 * handlers) through this port's hold review, drafts and commit, as Actualist's
 * `HoldOracleReplayTests` does: outcome, committed cells and seven fields per month.
 */
@RunWith(Parameterized::class)
class HoldOracleReplayTest(private val caseId: String) {
    companion object {
        private val cases by lazy { resource("/oracle/Holds/hold-contract.json")["cases"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject } }

        /** Not replayed by Actualist either: not reachable from its UI, or tracking-only. */
        private val notReplayed = setOf(
            "hold-handler-replaces-automatic-income-hold-when-called-directly",
            "hold-in-tracking-budget-is-not-offered",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds() = cases.keys.filter { it !in notReplayed }

        private val stateFields = listOf("toBudget", "fromLastMonth", "totalIncome", "totalBudgeted", "manualHeldAmount", "automaticHeldAmount", "heldAmount")
    }

    private fun JsonObject.long(key: String) = (this[key] as JsonPrimitive).let { it.longOrNull ?: it.doubleOrNull!!.toLong() }
    private fun JsonObject.bool(key: String) = (this[key] as JsonPrimitive).booleanOrNull!!

    private data class Action(val hold: Long?, val month: String, val outcome: Boolean?, val cells: List<String>, val stateAfter: List<JsonObject>)

    private fun cells(step: JsonObject): List<String> = (step["cells"] as? kotlinx.serialization.json.JsonArray)?.map {
        val cell = it.jsonObject
        "${cell["dataset"]!!.jsonPrimitive.content}/${cell["row"]!!.jsonPrimitive.content}/${cell["column"]!!.jsonPrimitive.content}=${cell["serializedValue"]!!.jsonPrimitive.content}"
    } ?: emptyList()

    private fun plan(steps: List<JsonObject>): List<Action> {
        val actions = mutableListOf<Action>()
        var index = 0
        while (index < steps.size) {
            val step = steps[index]
            if ((step["kind"] as? JsonPrimitive)?.content == "apply-messages") {
                val buffered = cells(step).first { it.startsWith("zero_budget_months/") && it.contains("/buffered=") }
                val month = buffered.split('/')[1]
                actions += Action(buffered.substringAfter("=N:").toLong(), month, null, cells(step), step["stateAfter"]!!.jsonArray.map { it.jsonObject })
                index++
                continue
            }
            val handler = step["handler"]!!.jsonPrimitive.content
            val next = steps.getOrNull(index + 1)
            if (handler == "budget/reset-income-carryover" && next != null && (next["handler"] as? JsonPrimitive)?.content == "budget/hold-for-next-month") {
                val args = next["args"]!!.jsonObject
                val committed = next.bool("returned")
                actions += Action(args.long("amount"), args["month"]!!.jsonPrimitive.content, committed, if (committed) cells(step) + cells(next) else emptyList(), next["stateAfter"]!!.jsonArray.map { it.jsonObject })
                index += 2
                continue
            }
            require(handler == "budget/reset-income-carryover" || handler == "budget/reset-hold") { "$handler is not replayed" }
            actions += Action(null, step["args"]!!.jsonObject["month"]!!.jsonPrimitive.content, null, cells(step), step["stateAfter"]!!.jsonArray.map { it.jsonObject })
            index++
        }
        return actions
    }

    private fun seed(database: BudgetDatabase, setup: JsonObject) {
        require(setup["budgetType"]!!.jsonPrimitive.content == "envelope")
        val sql = database.sql
        sql.transaction {
            sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES ('checking', 'Synthetic Checking', 0, 0, 0, 1)")
            sql.execute("INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES ('income-group', 'Synthetic Income', 1, 0, 0, 1)")
            sql.execute("INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES ('expense-group', 'Synthetic Expenses', 0, 0, 0, 2)")
            sql.execute("INSERT INTO categories (id, name, cat_group, is_income, hidden, tombstone, sort_order) VALUES ('salary', 'Synthetic Salary', 'income-group', 1, 0, 0, 1)")
            sql.execute("INSERT INTO categories (id, name, cat_group, is_income, hidden, tombstone, sort_order) VALUES ('groceries', 'Synthetic Groceries', 'expense-group', 0, 0, 0, 2)")
            sql.execute("INSERT INTO category_mapping (id, transferId) VALUES ('salary', 'salary'), ('groceries', 'groceries')")
            for ((id, date, key) in listOf(Triple("income-june", 20260601, "juneIncome"), Triple("income-july", 20260701, "julyIncome"), Triple("income-august", 20260801, "augustIncome"))) {
                val amount = setup.long(key)
                if (amount != 0L) sql.execute("INSERT INTO transactions (id, acct, date, amount, category, tombstone, isParent, isChild, sort_order) VALUES (?, 'checking', ?, ?, 'salary', 0, 0, 0, 0)", id, date, amount)
            }
            val groceries = setup.long("julyGroceriesBudget")
            if (groceries != 0L) sql.execute("INSERT INTO zero_budgets (id, month, category, amount, carryover) VALUES ('202607-groceries', 202607, 'groceries', ?, 0)", groceries)
            if (setup.bool("julyIncomeCarryover")) sql.execute("INSERT INTO zero_budgets (id, month, category, amount, carryover) VALUES ('202607-salary', 202607, 'salary', 0, 1)")
            if (setup.bool("augustIncomeCarryover")) sql.execute("INSERT INTO zero_budgets (id, month, category, amount, carryover) VALUES ('202608-salary', 202608, 'salary', 0, 1)")
            val buffer = setup.long("julyManualBuffer")
            if (buffer != 0L) sql.execute("INSERT INTO zero_budget_months (id, buffered) VALUES ('2026-07', ?)", buffer)
        }
    }

    private fun snapshot(database: BudgetDatabase, months: List<String>, prefix: String): Map<String, String> {
        val reads = BudgetReads(database)
        val values = LinkedHashMap<String, String>()
        for (month in months) {
            val budget = reads.fetchBudgetMonth(month)
            val review = reads.holdReview(month)
            val fields = mapOf(
                "toBudget" to budget.toBudget, "fromLastMonth" to budget.fromLastMonth, "totalIncome" to budget.totalIncome,
                "totalBudgeted" to -budget.totalBudgeted, "manualHeldAmount" to review.manualHeldAmount,
                "automaticHeldAmount" to review.automaticHeldAmount, "heldAmount" to review.heldAmount,
            )
            for ((field, value) in fields) values["$prefix$month.$field"] = value.toString()
        }
        return values
    }

    private fun expectedState(states: List<JsonObject>, prefix: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (state in states) {
            val month = state["month"]!!.jsonPrimitive.content
            for (field in stateFields) {
                val element = state[field]
                values["$prefix$month.$field"] = if (element == null || element is JsonNull) "null" else element.jsonPrimitive.let { it.longOrNull?.toString() ?: it.content }
            }
        }
        return values
    }

    @Test
    fun replaysHoldCase() {
        val entry = cases.getValue(caseId)
        val before = entry["before"]!!.jsonArray.map { it.jsonObject }
        val months = before.map { it["month"]!!.jsonPrimitive.content }
        TestDatabases.starter(nodeId = "hold-oracle", clock = TestClock()).use { database ->
            seed(database, entry["setup"]!!.jsonObject)
            val writes = BudgetWrites(database)
            val reads = BudgetReads(database)
            val expected = LinkedHashMap(expectedState(before, "before."))
            val actual = LinkedHashMap(snapshot(database, months, "before."))
            plan(entry["steps"]!!.jsonArray.map { it.jsonObject }).forEachIndexed { index, action ->
                val prefix = "action$index."
                var drafts = emptyList<CellDraft>()
                var committed = true
                try {
                    database.commitLocal {
                        val review = reads.holdReview(action.month)
                        drafts = writes.holdDrafts(review, if (action.hold != null) HoldCommand.Hold(action.hold) else HoldCommand.Reset)
                        drafts to Unit
                    }
                } catch (error: LocalFirstException) {
                    committed = false
                    drafts = emptyList()
                }
                action.outcome?.let {
                    expected["${prefix}outcome"] = it.toString()
                    actual["${prefix}outcome"] = committed.toString()
                }
                expected.putAll(action.cells.groupBy { it.substringBefore('=') }.mapKeys { "${prefix}cell.${it.key}" }.mapValues { e -> e.value.map { it.substringAfter('=') }.sorted().joinToString("|") })
                actual.putAll(drafts.map { "${it.dataset}/${it.row}/${it.column}=${it.value.serialized}" }.groupBy { it.substringBefore('=') }.mapKeys { "${prefix}cell.${it.key}" }.mapValues { e -> e.value.map { it.substringAfter('=') }.sorted().joinToString("|") })
                expected.putAll(expectedState(action.stateAfter, prefix))
                actual.putAll(snapshot(database, months, prefix))
            }
            val mismatches = (expected.keys + actual.keys).distinct().filter { expected[it] != actual[it] }.map { "$it: expected ${expected[it]}, actual ${actual[it]}" }
            assertTrue("$caseId\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
        }
    }
}

/** Actual's tracking-budget category recurrence (`tracking.ts`), 36 pinned contract cases. */
class TrackingContractTest {
    @Test
    fun trackingCategoryBalancesMatchTheContract() {
        val contract = resource("/oracle/Tracking/contract.json")
        val cases = contract["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(36, cases.size)
        for (case in cases) {
            fun long(key: String) = case[key]!!.jsonPrimitive.longOrNull!!
            val value = BudgetCalculation.category(
                table = BudgetTable.TRACKING,
                isIncome = case["isIncome"]!!.jsonPrimitive.booleanOrNull!!,
                budgeted = long("budgeted"),
                activity = long("activity"),
                carryover = false,
                previous = CategoryValue(balance = long("previousBalance"), carryover = case["previousCarryover"]!!.jsonPrimitive.booleanOrNull!!),
            )
            assertEquals(case.toString(), long("balance"), value.balance)
        }
    }
}
