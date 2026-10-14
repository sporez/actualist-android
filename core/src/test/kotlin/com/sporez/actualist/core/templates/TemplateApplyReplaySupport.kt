package com.sporez.actualist.core.templates

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.schedules.OracleValue
import com.sporez.actualist.core.sync.SyncTimestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

/**
 * Replays Apply Templates oracle cases through [BudgetTemplates.applyForCurrentMonth], as
 * Actualist's `TemplateApplyOracleReplay` does through its production apply path. The fixture's
 * `currentMonth` is passed explicitly; neither the machine clock nor the machine zone is read.
 */
object TemplateApplyReplaySupport {
    /** Input tables, parents before children. Each is cleared of starter rows and refilled from the case. */
    private val seedTables = listOf(
        "accounts", "category_groups", "categories", "category_mapping", "payees",
        "rules", "schedules", "schedules_next_date", "transactions",
        "zero_budgets", "reflect_budgets", "zero_budget_months", "notes", "preferences",
    )

    private val persistedColumns = listOf("id", "amount", "carryover", "category", "goal", "long_goal", "month")

    /** 2026-10-15T00:00:00Z, the instant the local clock mints change timestamps from. */
    private val fixtureNow: Long = SyncTimestamp.wallTimeMillis("2026-10-15T00:00:00.000Z")!!

    /** `tapply-named-stale-note-goal-def`: its stored definition is stale against the note, so the apply is refused (OP-25). */
    const val STALE_REFUSAL_CASE_ID = "tapply-named-stale-note-goal-def"

    class ReplayResult(
        val expected: OracleValue,
        val actual: OracleValue,
        /** CRDT messages the apply produced. Zero for a no-op. Not compared. */
        val messageCount: Int,
        /** The production refusal, if the apply threw. The state is still compared. */
        val refusal: String?,
    )

    private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    private fun JsonObject.intOrNull(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.toDoubleOrNull()?.toLong()
    }

    /**
     * A category whose stored definition is null while its live note carries a template directive and
     * the oracle's definition is non-null. Production never regenerates that definition from the note at
     * apply (OP-24).
     */
    fun hasNoteOnlyTemplate(entry: JsonObject): Boolean {
        val input = entry["input"]!!.jsonObject
        val expected = entry["expected"]!!.jsonObject
        val noteById = HashMap<String, String>()
        for (note in input.objects("notes")) {
            val id = TemplateOracleFixture.text(note["id"]) ?: continue
            noteById[id] = TemplateOracleFixture.text(note["note"]) ?: ""
        }
        val oracleIds = definitionsById(expected).keys
        return input.objects("categories").any { category ->
            if (category.intOrNull("tombstone") != 0L) return@any false
            if (!TemplateOracleFixture.isNull(category["goal_def"])) return@any false
            val id = TemplateOracleFixture.text(category["id"]) ?: return@any false
            if (id !in oracleIds) return@any false
            val note = noteById[id] ?: return@any false
            note.contains("#template") || note.contains("#goal")
        }
    }

    fun isUpToDate(entry: JsonObject): Boolean {
        val notification = entry["expected"]!!.jsonObject["notification"] as? JsonObject
        return TemplateOracleFixture.text(notification?.get("message")) == "templates-up-to-date"
    }

    private fun definitionsById(expected: JsonObject): Map<String, JsonElement> {
        val result = LinkedHashMap<String, JsonElement>()
        for (definition in expected.objects("goalDefinitions")) {
            val id = TemplateOracleFixture.text(definition["id"]) ?: continue
            val goalDef = definition["goalDef"]
            if (goalDef == null || goalDef is JsonNull) continue
            result[id] = goalDef
        }
        return result
    }

    /**
     * Replays one case. [seedGoalDefsFromOracle] gives each note-only category the oracle's expected
     * definition as its stored `goal_def`, so the apply engine runs on its own.
     */
    fun replay(entry: JsonObject, currentMonth: String, seedGoalDefsFromOracle: Boolean): ReplayResult {
        val input = entry["input"]!!.jsonObject
        val operation = entry["operation"]!!.jsonObject
        val expectedFields = entry["expected"]!!.jsonObject
        val month = operation["month"]!!.jsonPrimitive.content
        val mode = if (operation["kind"]!!.jsonPrimitive.content == "overwrite") TemplateApplyMode.OVERWRITE else TemplateApplyMode.FILL_EMPTY
        val oracleDefinitions = if (seedGoalDefsFromOracle) definitionsById(expectedFields) else emptyMap()
        val monthValue = Months.actualMonthValue(month)
        val cleared = seedTables.filter { input[it] != null }

        val clock = TestClock(fixtureNow)
        TestDatabases.starter(nodeId = "node1", clock = clock).use { database ->
            seed(database, input, monthValue, oracleDefinitions, cleared)

            var messageCount = 0
            var refusal: String? = null
            try {
                val templates = BudgetTemplates(database, clock, ZoneId.of("UTC"))
                messageCount = templates.applyForCurrentMonth(month, currentMonth, null, mode).messageCount
            } catch (error: UnsupportedTemplateException) {
                refusal = error.reason
            } catch (error: LocalFirstException) {
                refusal = error.toString()
            }

            var expected = expectedValue(expectedFields, input)
            var actual = snapshot(database, month, monthValue)
            if (seedGoalDefsFromOracle) {
                expected = removingTemplateSettings(expected)
                actual = removingTemplateSettings(actual)
            }
            return ReplayResult(expected, actual, messageCount, refusal)
        }
    }

    // region Seeding

    private fun seed(
        database: BudgetDatabase,
        input: JsonObject,
        monthValue: Int,
        oracleDefinitions: Map<String, JsonElement>,
        cleared: List<String>,
    ) {
        val sql = database.sql
        sql.transaction {
            for (table in cleared.reversed()) sql.execute("DELETE FROM $table")
            for (table in seedTables) {
                for (row in input.objects(table)) {
                    val values = LinkedHashMap<String, JsonElement>(row)
                    val id = TemplateOracleFixture.text(row["id"])
                    val definition = if (table == "categories" && id != null) oracleDefinitions[id] else null
                    if (definition != null && TemplateOracleFixture.isNull(row["goal_def"])) {
                        values["goal_def"] = JsonPrimitive(definition.toString())
                        // UI-managed categories are outside the note staleness guard, which is a separate
                        // note-parser comparison; this replay isolates the apply engine.
                        values["template_settings"] = JsonPrimitive("{\"source\":\"ui\"}")
                    }
                    val columns = values.keys.sorted()
                    val placeholders = columns.joinToString(", ") { "?" }
                    val args = columns.map { sqlValue(values.getValue(it), "$table.$it") }.toTypedArray()
                    sql.execute("INSERT INTO $table (${columns.joinToString(", ")}) VALUES ($placeholders)", *args)
                }
            }
            for ((statement, args) in zeroBudgetRows(input, monthValue)) sql.execute(statement, *args)
        }
    }

    /**
     * The oracle's harness creates a zero budget row for every live expense category when it loads the
     * sheet (upstream `createAllBudgets`), before the apply runs. Seed the same rows for the operation
     * month wherever the case supplies none, in the table its `budgetType` preference names.
     */
    private fun zeroBudgetRows(input: JsonObject, monthValue: Int): List<Pair<String, Array<Any?>>> {
        val tracking = input.objects("preferences").any {
            TemplateOracleFixture.text(it["id"]) == "budgetType" && TemplateOracleFixture.text(it["value"]) == "tracking"
        }
        val table = if (tracking) "reflect_budgets" else "zero_budgets"
        val supplied = input.objects(table).mapNotNull { row ->
            if (row.intOrNull("month") == monthValue.toLong()) TemplateOracleFixture.text(row["category"]) else null
        }.toSet()
        val expenseCategories = input.objects("categories").mapNotNull { category ->
            if (category.intOrNull("tombstone") == 0L && category.intOrNull("is_income") == 0L) TemplateOracleFixture.text(category["id"]) else null
        }
        return expenseCategories.filter { it !in supplied }.map { category ->
            val args: Array<Any?> = arrayOf("$monthValue-$category", category, monthValue.toLong())
            "INSERT INTO $table (id, amount, carryover, category, goal, long_goal, month) VALUES (?, 0, 0, ?, NULL, NULL, ?)" to args
        }
    }

    private fun sqlValue(element: JsonElement, column: String): Any? {
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw IllegalStateException("$column is not a scalar value")
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        val double = primitive.content.toDouble()
        val long = double.toLong()
        return if (long.toDouble() == double) long else double
    }

    // endregion

    // region Reading

    /**
     * The compared fields after the apply. Balance is To Budget for envelope budgets and planned
     * savings for tracking budgets. Goal fields and persisted rows come from the stored budget table.
     */
    private fun snapshot(database: BudgetDatabase, month: String, monthValue: Int): OracleValue {
        val reads = BudgetReads(database)
        val budgetMonth = reads.fetchBudgetMonth(month)
        val table = if (budgetMonth.trackingSummary == null) BudgetTable.ENVELOPE else BudgetTable.TRACKING
        val balance = budgetMonth.trackingSummary?.plannedSavings ?: budgetMonth.toBudget
        val sql = database.sql

        val storedRows: List<Map<String, OracleValue>> = sql.query(
            "SELECT id, amount, carryover, category, goal, long_goal, month FROM ${table.tableName} WHERE month = ?",
            monthValue.toLong(),
        ).map { row -> persistedColumns.associateWith { OracleValue.fromDatabase(row[it]) } }

        val categories: List<OracleValue> = budgetMonth.categoryGroups
            .filter { !it.isIncome }
            .flatMap { it.categories }
            .map { category ->
                val stored = storedRows.firstOrNull { it["category"] == OracleValue.Str(category.id) }
                OracleValue.Obj(
                    mapOf(
                        "id" to OracleValue.Str(category.id),
                        "budget" to OracleValue.Num(category.budgeted.toDouble()),
                        "goal" to (stored?.get("goal") ?: OracleValue.Null),
                        "longGoal" to (stored?.get("long_goal") ?: OracleValue.Null),
                    ),
                )
            }

        val goalDefinitions: List<OracleValue> = sql.query("SELECT id, goal_def, template_settings FROM categories ORDER BY id").map { row ->
            OracleValue.Obj(
                mapOf(
                    "id" to OracleValue.fromDatabase(row["id"]),
                    "goalDef" to parsedJson(row.string("goal_def")),
                    "templateSettings" to parsedJson(row.string("template_settings")),
                ),
            )
        }

        return OracleValue.Obj(
            mapOf(
                "balance" to OracleValue.Num(balance.toDouble()),
                "categories" to OracleValue.Arr(categories),
                "persistedBudgets" to OracleValue.Arr(storedRows.map { OracleValue.Obj(it) }),
                "goalDefinitions" to OracleValue.Arr(goalDefinitions),
            ),
        )
    }

    private fun parsedJson(text: String?): OracleValue =
        if (text == null) OracleValue.Null else OracleValue.fromJson(Json.parseToJsonElement(text))

    /** Removes `templateSettings` from each goal definition, where the replay seeds some categories as UI-managed. */
    private fun removingTemplateSettings(value: OracleValue): OracleValue {
        val fields = (value as? OracleValue.Obj)?.fields ?: return value
        val definitions = (fields["goalDefinitions"] as? OracleValue.Arr)?.values ?: return value
        val stripped = definitions.map { definition ->
            val entry = (definition as? OracleValue.Obj)?.fields
            if (entry == null) definition else OracleValue.Obj(entry - "templateSettings")
        }
        return OracleValue.Obj(fields + ("goalDefinitions" to OracleValue.Arr(stripped)))
    }

    // endregion

    // region Expected

    /**
     * The oracle's fields in the same shape as [snapshot]. Its sheet names each category `category`, so
     * it is renamed to `id`. A deleted category is not on the budget sheet, so its budget cells are left
     * out of `categories`; its goal definition is still compared in `goalDefinitions`.
     */
    private fun expectedValue(expected: JsonObject, input: JsonObject): OracleValue {
        val deleted = input.objects("categories").mapNotNull { category ->
            if (category.intOrNull("tombstone") == 1L) TemplateOracleFixture.text(category["id"]) else null
        }.toSet()
        val categories = expected.objects("categories").mapNotNull { category ->
            val id = TemplateOracleFixture.text(category["category"])
            if (id == null || id in deleted) {
                null
            } else {
                val renamed = LinkedHashMap<String, JsonElement>(category)
                renamed.remove("category")
                renamed["id"] = JsonPrimitive(id)
                OracleValue.fromJson(JsonObject(renamed))
            }
        }
        return OracleValue.Obj(
            mapOf(
                "balance" to OracleValue.fromJson(expected["balance"]),
                "categories" to OracleValue.Arr(categories),
                "persistedBudgets" to OracleValue.fromJson(expected["persistedBudgets"] ?: JsonArray(emptyList())),
                "goalDefinitions" to OracleValue.fromJson(expected["goalDefinitions"] ?: JsonArray(emptyList())),
            ),
        )
    }

    // endregion
}
