package com.sporez.actualist.core.schedules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Replays the stateful Schedules cases through the operations the app calls, as Actualist's
 * `ScheduleStatefulReplay` does through `BudgetDatabase`.
 *
 * Steps: before every step the fixture's snapshot of the state before it (rows written by Actual's
 * own `createSchedule` and earlier steps) is seeded into the starter database, so a divergence in
 * one step cannot carry into the next. Then the matching production operation runs
 * (`advance`, `skipNextDate`, `update`, `postNow`) with the step's day as `today` and its local noon in
 * the fixture zone as `now`, and the database projection is compared with the step's `after`
 * snapshot. A `payNextDate` step is an oracle input (a transaction inserted for the schedule), so
 * only its snapshot is re-read. Creation: `create` replays each setup schedule against a fresh
 * database and the result is compared with the `before` snapshot.
 *
 * Not compared: transaction `id` (the replay's ids are its own), `sort_order` and `raw_synced_data`
 * (derived from the clock and the sync source), and the oracle-only `preview` rows.
 */
object ScheduleStatefulReplay {
    class Comparison(val label: String, val expected: OracleValue, val actual: OracleValue) {
        val mismatches: List<ReplayMismatch> get() = ReplayComparison.mismatches(expected, actual, label)
    }

    private val transactionColumns = listOf(
        "acct", "amount", "date", "description", "schedule", "cleared", "category", "notes", "tombstone",
        "isParent", "isChild", "parent_id", "reconciled", "starting_balance_flag", "financial_id",
        "imported_description", "transferred_id", "error", "pending", "type", "location",
    )
    private val scheduleColumns = listOf("completed", "posts_transaction", "name", "custom_upcoming_length", "tombstone")
    private val seededTables = listOf(
        "accounts", "payees", "payee_mapping", "preferences", "rules", "schedules", "schedules_next_date",
        "schedules_json_paths", "transactions",
    )

    private fun newDatabase(): Pair<BudgetDatabase, TestClock> {
        val clock = TestClock(ScheduleOracleFixture.noonMillis(ScheduleOracleFixture.FIXTURE_TODAY))
        return TestDatabases.starter(nodeId = "node1", clock = clock) to clock
    }

    // region Steps

    fun replaySteps(entry: JsonObject): List<Comparison> {
        val setup = entry["setup"]!!.jsonObject
        val before = entry["before"]!!.jsonObject
        val steps = entry["steps"]!!.jsonArray.map { it.jsonObject }
        val (database, clock) = newDatabase()
        return database.use {
            val schedules = Schedules(database, clock)
            seed(database, setup, before)
            val comparisons = mutableListOf<Comparison>()
            var previous = before
            for ((index, step) in steps.withIndex()) {
                val after = step["after"]!!.jsonObject
                val args = step["args"]!!.jsonObject
                val op = step["op"]!!.jsonPrimitive.content
                val today = step["today"]!!.jsonPrimitive.content
                val label = "step$index($op)"
                // Every step starts from the oracle's state before it, so one divergence cannot
                // cascade into the steps after it.
                seed(database, setup, if (op == "payNextDate") after else previous)
                var refusals: List<String> = emptyList()
                var failure: String? = null
                try {
                    // A `payNextDate` step is an oracle input; its rows are already part of the
                    // snapshot, so there is no production operation to run.
                    if (op != "payNextDate") refusals = run(op, args, setup, schedules, today)
                } catch (e: Exception) {
                    failure = "threw $e"
                }
                comparisons.add(
                    Comparison(
                        label,
                        OracleValue.Obj(mapOf("completes" to OracleValue.Str("yes"), "refusals" to OracleValue.Str("none"))),
                        OracleValue.Obj(
                            mapOf(
                                "completes" to OracleValue.Str(failure ?: "yes"),
                                "refusals" to OracleValue.Str(if (refusals.isEmpty()) "none" else refusals.joinToString("; ")),
                            ),
                        ),
                    ),
                )
                val expected = expectedProjection(after).toMutableMap()
                val stepError = step["error"]
                expected["error"] = if (stepError == null || stepError is JsonNull) OracleValue.Null else OracleValue.Str("error")
                val actualFields = projection(database, schedules, today).toMutableMap()
                actualFields["error"] = OracleValue.Null
                comparisons.add(Comparison(label, OracleValue.Obj(expected), OracleValue.Obj(actualFields)))
                previous = after
            }
            comparisons
        }
    }

    private fun run(op: String, args: JsonObject, setup: JsonObject, schedules: Schedules, today: String): List<String> {
        val now = ScheduleOracleFixture.noonMillis(today)
        val scheduleKey = ScheduleOracleFixture.string(args["schedule"])
        val scheduleId = scheduleKey?.let { "schedule-$it" }
        var refusals: List<String> = emptyList()
        when (op) {
            "advance" -> {
                val result = schedules.advance(today, null, now)
                refusals = result.refusals.map { "${it.scheduleId} ${it.occurrenceDayId} ${it.refusal}" }
            }
            "skip" -> {
                schedules.skipNextDate(required(scheduleId), now)
            }
            "setNextDate" -> {
                // Only the two replayable combinations reach here; see `stepOracleOnlyReason` in cases.mjs.
                if (args["reset"]?.jsonPrimitive?.booleanOrNull == true) {
                    schedules.update(required(scheduleId), ScheduleEditFields(resetNextDate = true), today, now)
                } else {
                    schedules.skipNextDate(required(scheduleId), now)
                }
            }
            "update" -> {
                val definition = setupSchedule(setup, required(scheduleKey))
                var fields = ScheduleEditFields()
                val date = args["date"]
                if (date != null) {
                    val operation = ScheduleOracleFixture.string(definition["dateOp"]) ?: "is"
                    val condition = ruleCondition("date", operation, date)
                    fields = fields.copy(dateRule = ScheduleOptionalChange.Assign(ScheduleRuleProjection.dateRuleFrom(condition)))
                }
                val amount = int(args["amount"])
                if (amount != null) {
                    val draft: ScheduleAmountDraft = if (ScheduleOracleFixture.string(definition["amountOp"]) == "isapprox") {
                        ScheduleAmountDraft.Approximate(amount)
                    } else {
                        ScheduleAmountDraft.Exact(amount)
                    }
                    fields = fields.copy(amount = ScheduleOptionalChange.Assign(draft))
                }
                fields = fields.copy(resetNextDate = args["resetNextDate"]?.jsonPrimitive?.booleanOrNull ?: false)
                schedules.update(required(scheduleId), fields, today, now)
            }
            "post" -> {
                val postsToday = args["todayDate"]?.jsonPrimitive?.booleanOrNull ?: false
                schedules.postNow(required(scheduleId), today, postsToday, now)
            }
            else -> throw IllegalStateException("unsupported step $op")
        }
        return refusals
    }

    private fun required(value: String?): String = value ?: throw IllegalStateException("step has no schedule")

    // endregion

    // region Creation

    fun replayCreation(entry: JsonObject): List<Comparison> {
        val setup = entry["setup"]!!.jsonObject
        val before = entry["before"]!!.jsonObject
        val (database, clock) = newDatabase()
        return database.use {
            val schedules = Schedules(database, clock)
            seed(database, setup, before, includingSchedules = false)

            val ids = referencedIds(setup, before)
            val definitions = setup["schedules"]!!.jsonArray.map { it.jsonObject }
                .sortedBy { ScheduleOracleFixture.string(it["createdOn"]) ?: "" }
            val failures = mutableListOf<String>()
            for (schedule in definitions) {
                val key = schedule["key"]!!.jsonPrimitive.content
                val createdOn = schedule["createdOn"]!!.jsonPrimitive.content
                val condition = ruleCondition("date", ScheduleOracleFixture.string(schedule["dateOp"]) ?: "is", schedule["date"] ?: JsonNull)
                val payeeKey = ScheduleOracleFixture.string(schedule["payeeKey"])
                val command = ScheduleCreateCommand(
                    identity = ScheduleCreateIdentity("schedule-$key", "rule-$key", "next-$key"),
                    name = ScheduleOracleFixture.string(schedule["name"]),
                    definition = ScheduleDefinitionDraft(
                        accountId = required(ids.first[schedule["accountKey"]!!.jsonPrimitive.content]),
                        payeeMappingId = payeeKey?.let { ids.second[it] },
                        amount = amountDraft(schedule),
                        dateRule = ScheduleRuleProjection.dateRuleFrom(condition),
                    ),
                    postsTransaction = schedule["postsTransaction"]?.jsonPrimitive?.booleanOrNull ?: false,
                    customUpcomingLength = ScheduleOracleFixture.string(schedule["customUpcomingLength"]),
                    asOfDayId = createdOn,
                )
                try {
                    schedules.create(command, ScheduleOracleFixture.noonMillis(createdOn))
                } catch (e: ScheduleMutationCommandException) {
                    failures.add("$key threw $e")
                }
            }
            // A refused creation leaves nothing to project, so only the refusal is compared.
            if (failures.isNotEmpty()) {
                return@use listOf(
                    Comparison(
                        "created",
                        OracleValue.Obj(mapOf("completes" to OracleValue.Str("yes"))),
                        OracleValue.Obj(mapOf("completes" to OracleValue.Str(failures.joinToString("; ")))),
                    ),
                )
            }
            val actual = creationFields(projection(database, schedules, ScheduleOracleFixture.FIXTURE_TODAY))
            val expected = creationFields(expectedProjection(before))
            listOf(Comparison("created", OracleValue.Obj(expected), OracleValue.Obj(actual)))
        }
    }

    private fun amountDraft(schedule: JsonObject): ScheduleAmountDraft = when (ScheduleOracleFixture.string(schedule["amountOp"])) {
        "isapprox" -> ScheduleAmountDraft.Approximate(int(schedule["amount"]) ?: 0L)
        "isbetween" -> {
            val range = schedule["amount"]!!.jsonObject
            ScheduleAmountDraft.Range(int(range["num1"]) ?: 0L, int(range["num2"]) ?: 0L)
        }
        else -> ScheduleAmountDraft.Exact(int(schedule["amount"]) ?: 0L)
    }

    private fun int(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private fun ruleCondition(field: String, op: String, value: JsonElement?): RuleCondition =
        RuleCondition(field, op, RuleValue.fromJson(value ?: JsonNull))

    private fun setupSchedule(setup: JsonObject, key: String): JsonObject =
        setup["schedules"]!!.jsonArray.map { it.jsonObject }.firstOrNull { ScheduleOracleFixture.string(it["key"]) == key }
            ?: throw IllegalStateException("no setup schedule $key")

    // endregion

    // region Projections

    private fun transactionOrder(value: OracleValue): String {
        val fields = (value as? OracleValue.Obj)?.fields ?: return ""
        return listOf("date", "schedule", "amount", "acct", "description")
            .joinToString("|") { (fields[it] ?: OracleValue.Null).canonical }
    }

    private fun expectedProjection(after: JsonObject): Map<String, OracleValue> {
        val fields = LinkedHashMap<String, OracleValue>()
        fields["schedules"] = keyed(after["schedules"], "id", scheduleColumns)
        fields["nextDates"] = keyed(after["nextDates"], "schedule_id", listOf("local_next_date", "base_next_date", "localIsEffective"))
        val effective = LinkedHashMap<String, OracleValue>()
        for (row in after["effective"]!!.jsonArray.map { it.jsonObject }) {
            effective[row["id"]!!.jsonPrimitive.content] = OracleValue.Obj(
                mapOf(
                    "nextDate" to OracleValue.fromJson(row["nextDate"]),
                    "completed" to OracleValue.fromJson(row["completed"]),
                    "status" to OracleValue.fromJson(row["status"]),
                ),
            )
        }
        fields["effective"] = OracleValue.Obj(effective)
        val transactions = after["transactions"]!!.jsonArray.map { it.jsonObject }.map { row ->
            OracleValue.Obj(transactionColumns.associateWith { OracleValue.fromJson(row[it]) }) as OracleValue
        }
        fields["transactions"] = OracleValue.Arr(transactions.sortedBy { transactionOrder(it) })
        fields["rules"] = ruleSummaries(
            after["rules"]!!.jsonArray.map { it.jsonObject }.map { rule ->
                objects(rule["conditions"]) to objects(rule["actions"])
            },
        )
        return fields
    }

    private fun projection(database: BudgetDatabase, schedules: Schedules, today: String): Map<String, OracleValue> {
        val sql = database.sql
        val scheduleList = scheduleColumns.joinToString(", ")
        val transactionList = transactionColumns.joinToString(", ")
        val scheduleRows = sql.query("SELECT id, $scheduleList FROM schedules ORDER BY id")
        val nextDateRows = sql.query(
            """
            SELECT schedule_id, local_next_date, base_next_date,
                   (local_next_date_ts = base_next_date_ts) AS localIsEffective
            FROM schedules_next_date ORDER BY schedule_id
            """.trimIndent(),
        )
        val transactions = sql.query("SELECT $transactionList FROM transactions").map { row ->
            OracleValue.Obj(transactionColumns.associateWith { OracleValue.fromDatabase(row[it]) }) as OracleValue
        }
        val rules = sql.query("SELECT conditions, actions FROM rules WHERE tombstone = 0").map { row ->
            parseArray(row.string("conditions")) to parseArray(row.string("actions"))
        }
        val fields = LinkedHashMap<String, OracleValue>()
        fields["schedules"] = keyedRows(scheduleRows.map { (it.string("id") ?: "") to scheduleColumns.associateWith { column -> OracleValue.fromDatabase(it[column]) } })
        fields["nextDates"] = keyedRows(
            nextDateRows.map {
                (it.string("schedule_id") ?: "") to listOf("local_next_date", "base_next_date", "localIsEffective")
                    .associateWith { column -> OracleValue.fromDatabase(it[column]) }
            },
        )
        val loaded = schedules.loaded(today)
        val effective = LinkedHashMap<String, OracleValue>()
        for (detail in loaded.detailsById.values) {
            effective[detail.id] = OracleValue.Obj(
                mapOf(
                    "nextDate" to OracleValue.string(detail.effectiveNextDate),
                    "completed" to OracleValue.Num(if (detail.completed) 1.0 else 0.0),
                    "status" to OracleValue.Str(detail.status.raw),
                ),
            )
        }
        fields["effective"] = OracleValue.Obj(effective)
        fields["transactions"] = OracleValue.Arr(transactions.sortedBy { transactionOrder(it) })
        fields["rules"] = ruleSummaries(rules)
        return fields
    }

    /**
     * Creation compares the stored schedule fields (not `completed`, which creation cannot set),
     * the next-date rows and the rule summaries. Status and transactions depend on a later "today".
     */
    private fun creationFields(projected: Map<String, OracleValue>): Map<String, OracleValue> {
        val schedules = LinkedHashMap<String, OracleValue>()
        val rows = (projected["schedules"] as? OracleValue.Obj)?.fields
        if (rows != null) {
            for ((id, row) in rows) {
                val columns = (row as? OracleValue.Obj)?.fields ?: continue
                schedules[id] = OracleValue.Obj(columns.filterKeys { it != "completed" })
            }
        }
        return mapOf(
            "schedules" to OracleValue.Obj(schedules),
            "nextDates" to (projected["nextDates"] ?: OracleValue.Null),
            "rules" to (projected["rules"] ?: OracleValue.Null),
        )
    }

    private fun objects(element: JsonElement?): List<JsonObject> =
        (element as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun parseArray(json: String?): List<JsonObject> {
        if (json == null) return emptyList()
        return try {
            objects(Json.parseToJsonElement(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun keyed(rows: JsonElement?, idKey: String, columns: List<String>): OracleValue {
        val result = LinkedHashMap<String, OracleValue>()
        for (row in objects(rows)) {
            result[row[idKey]!!.jsonPrimitive.content] = OracleValue.Obj(columns.associateWith { OracleValue.fromJson(row[it]) })
        }
        return OracleValue.Obj(result)
    }

    private fun keyedRows(rows: List<Pair<String, Map<String, OracleValue>>>): OracleValue {
        val result = LinkedHashMap<String, OracleValue>()
        for ((id, columns) in rows) result[id] = OracleValue.Obj(columns)
        return OracleValue.Obj(result)
    }

    /**
     * One summary per schedule rule: the payee and account references, and the date and amount
     * conditions decoded the way production decodes them (key order, `type` and default fields do
     * not matter).
     */
    private fun ruleSummaries(rules: List<Pair<List<JsonObject>, List<JsonObject>>>): OracleValue {
        val result = LinkedHashMap<String, OracleValue>()
        for ((conditions, actions) in rules) {
            val link = actions.firstOrNull { ScheduleOracleFixture.string(it["op"]) == "link-schedule" } ?: continue
            val scheduleId = ScheduleOracleFixture.string(link["value"]) ?: continue
            val summary = LinkedHashMap<String, OracleValue>()
            for (condition in conditions) {
                val field = ScheduleOracleFixture.string(condition["field"]) ?: ""
                val decoded = ruleCondition(field, ScheduleOracleFixture.string(condition["op"]) ?: "", condition["value"])
                when (field) {
                    "payee", "description" -> summary["payee"] = OracleValue.fromJson(condition["value"])
                    "account", "acct" -> summary["account"] = OracleValue.fromJson(condition["value"])
                    "amount" -> summary["amount"] = OracleValue.Str(ScheduleRuleProjection.amountFrom(decoded).canonical)
                    "date" -> summary["date"] = OracleValue.Str(ScheduleRuleProjection.dateRuleFrom(decoded).signature)
                    else -> Unit
                }
            }
            result[scheduleId] = OracleValue.Obj(summary)
        }
        return OracleValue.Obj(result)
    }

    // endregion

    // region Seeding

    /** Account and payee ids as the fixture normalized them, found through each schedule's rule. */
    private fun referencedIds(setup: JsonObject, before: JsonObject): Pair<Map<String, String>, Map<String, String>> {
        val schedules = objects(before["schedules"])
        val rules = objects(before["rules"])
        val accounts = LinkedHashMap<String, String>()
        val payees = LinkedHashMap<String, String>()
        for (definition in objects(setup["schedules"])) {
            val key = definition["key"]!!.jsonPrimitive.content
            val row = schedules.firstOrNull { ScheduleOracleFixture.string(it["id"]) == "schedule-$key" } ?: continue
            val rule = rules.firstOrNull { ScheduleOracleFixture.string(it["id"]) == ScheduleOracleFixture.string(row["rule"]) } ?: continue
            for (condition in objects(rule["conditions"])) {
                val value = ScheduleOracleFixture.string(condition["value"])
                when (ScheduleOracleFixture.string(condition["field"])) {
                    "acct", "account" -> if (value != null) accounts[definition["accountKey"]!!.jsonPrimitive.content] = value
                    "description", "payee" -> {
                        val payeeKey = ScheduleOracleFixture.string(definition["payeeKey"])
                        if (payeeKey != null && value != null) payees[payeeKey] = value
                    }
                    else -> Unit
                }
            }
        }
        return accounts to payees
    }

    private class SeedRow(val sql: String, val values: List<Any?>)

    private fun seed(database: BudgetDatabase, setup: JsonObject, before: JsonObject, includingSchedules: Boolean = true) {
        val ids = referencedIds(setup, before)
        val rows = mutableListOf<SeedRow>()

        fun insert(table: String, row: Map<String, Any?>) {
            val columns = row.keys.sorted()
            rows.add(
                SeedRow(
                    "INSERT INTO $table (${columns.joinToString(", ")}) VALUES (${columns.joinToString(", ") { "?" }})",
                    columns.map { row[it] },
                ),
            )
        }

        fun jsonRow(row: JsonObject, table: String, overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            for ((column, value) in row) {
                if (column in overrides) continue
                result[column] = sqlValue(value, "$table.$column")
            }
            result.putAll(overrides)
            return result
        }

        for ((index, account) in objects(setup["accounts"]).withIndex()) {
            val id = ids.first[account["key"]!!.jsonPrimitive.content] ?: continue
            insert(
                "accounts",
                mapOf(
                    "id" to id,
                    "name" to (ScheduleOracleFixture.string(account["name"]) ?: ""),
                    "offbudget" to (if (account["offbudget"]?.jsonPrimitive?.booleanOrNull == true) 1L else 0L),
                    "closed" to (if (account["closed"]?.jsonPrimitive?.booleanOrNull == true) 1L else 0L),
                    "tombstone" to 0L,
                    "sort_order" to index.toLong(),
                ),
            )
        }
        for (payee in objects(setup["payees"])) {
            val id = ids.second[payee["key"]!!.jsonPrimitive.content] ?: continue
            insert("payees", mapOf("id" to id, "name" to (ScheduleOracleFixture.string(payee["name"]) ?: ""), "tombstone" to 0L))
            insert("payee_mapping", mapOf("id" to id, "targetId" to id))
        }
        ScheduleOracleFixture.string(setup["upcomingLength"])?.let {
            insert("preferences", mapOf("id" to "upcomingScheduledTransactionLength", "value" to it))
        }
        if (includingSchedules) {
            for (rule in objects(before["rules"])) {
                insert(
                    "rules",
                    jsonRow(
                        rule,
                        "rules",
                        mapOf(
                            "conditions" to jsonText(rule["conditions"]),
                            "actions" to jsonText(rule["actions"]),
                        ),
                    ),
                )
            }
            for (schedule in objects(before["schedules"])) insert("schedules", jsonRow(schedule, "schedules"))
            for (next in objects(before["nextDates"])) {
                val localEffective = next["localIsEffective"]?.jsonPrimitive?.booleanOrNull ?: true
                val row = jsonRow(JsonObject(next.filterKeys { it != "localIsEffective" }), "schedules_next_date")
                    .toMutableMap()
                row["base_next_date_ts"] = 1000L
                row["local_next_date_ts"] = if (localEffective) 1000L else 999L
                insert("schedules_next_date", row)
            }
            for (path in objects(before["paths"])) insert("schedules_json_paths", jsonRow(path, "schedules_json_paths"))
            for (transaction in objects(before["transactions"])) insert("transactions", jsonRow(transaction, "transactions"))
        }

        val sql = database.sql
        sql.transaction {
            for (table in seededTables) sql.execute("DELETE FROM $table")
            for (row in rows) sql.execute(row.sql, *row.values.toTypedArray())
        }
    }

    private fun jsonText(value: JsonElement?): String =
        ScheduleJson.encode((value as? JsonArray)?.toList() ?: emptyList())

    /** A fixture value as a bound SQLite argument: text, a whole number as Long, a fraction as Double. */
    private fun sqlValue(value: JsonElement?, column: String): Any? {
        if (value == null || value is JsonNull) return null
        if (value !is JsonPrimitive) throw IllegalStateException("unsupported value in $column")
        if (value.isString) return value.content
        val flag = value.booleanOrNull
        if (flag != null) return if (flag) 1L else 0L
        val text = value.content
        val isFraction = text.contains('.') || text.contains('e') || text.contains('E')
        return if (isFraction) text.toDouble() else text.toLong()
    }

    // endregion
}
