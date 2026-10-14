package com.sporez.actualist.core.query

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.Sql
import com.sporez.actualist.core.rules.RuleCondition
import com.sporez.actualist.core.rules.RuleConditionJoin
import com.sporez.actualist.core.rules.RuleJson
import com.sporez.actualist.core.rules.RuleValue
import com.sporez.actualist.core.transactions.LedgerReads
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

/** One compared field that differs between the oracle and the replay. */
class ReplayMismatch(val path: String, val expected: String, val actual: String) {
    /** The path without array indexes: how divergences are registered. */
    val pathWithoutIndexes: String get() = path.replace(Regex("\\[\\d+\\]"), "")
    override fun toString(): String = "$path: expected $expected, actual $actual"
}

/**
 * Replays the Transaction Query oracle (Q1 conditions, Q2 saved-filter creation, Q3 split families
 * and the split modes) through the query and saved-filter paths on a starter-schema budget seeded
 * from the fixture world, as Actualist's `TransactionQueryOracleReplay` does.
 *
 * Conditions go the way a stored saved filter does: the fixture condition becomes the stored JSON,
 * [SavedFilter.project] turns it into query conditions and [TransactionQueries.query] runs them over
 * every account. Compared per condition or search case: the individually matching ids, the grouped
 * roots, the displayed ids and the attached context ids. Compared per condition case: the saved
 * filter created from it (outcome, stored rows, the name, conditions and join cells written). Q2
 * cases compare the outcome class (created, duplicate name, duplicate conditions), the stored rows
 * and the cells written. Split-mode cases read all, inline, none and grouped mode, with each
 * family's children and unmatched ids, and the account balance where the fixture records it.
 *
 * Not compared: Actual's compiled AQL filters (no counterpart), the refusal text, the order of
 * written cells and the clock, and the `tombstone` cell this port also writes on create (the stored
 * row is compared, including `tombstone`). Unmatched ids are compared to the attached context ids
 * and, in the split-mode cases, to the family rows that the `all` read did not match.
 */
object TransactionQueryReplaySupport {
    const val FILTER_ID = "uuid-1"

    val queryCaseIds = listOf(
        "q1-date-is-day",
        "q1-date-isapprox-day",
        "q1-date-gt-exclusive-lower",
        "q1-date-gte-inclusive-lower",
        "q1-date-lt-exclusive-upper",
        "q1-date-lte-inclusive-upper",
        "q1-account-is",
        "q1-account-is-not",
        "q1-account-one-of",
        "q1-account-not-one-of",
        "q1-payee-is-mapped-id",
        "q1-payee-is-not-null",
        "q1-payee-one-of",
        "q1-payee-not-one-of",
        "q1-payee-is-null",
        "q1-category-is-mapped-id",
        "q1-category-is-not-null",
        "q1-category-one-of",
        "q1-category-not-one-of",
        "q1-category-is-null-uncategorized",
        "q3-date-family-grouping",
        "q3-account-family-grouping",
        "q3-payee-child-selects-family",
        "q3-category-child-selects-family",
        "q3-free-text-grouped-versus-physical",
    )

    val savedFilterCaseIds = listOf(
        "q2-exact-condition-equivalence",
        "q2-reordered-condition-equivalence",
        "q2-duplicate-condition-multiplicity",
        "q2-number-versus-string-value",
        "q2-options-key-order",
        "q2-single-condition-and-versus-or",
        "q2-multiple-condition-and-versus-or-control",
        "q2-duplicate-live-name-precedes-condition-check",
    )

    val splitModeCaseIds = listOf(
        "split-modes-two-child-family",
        "child-note-search",
        "dead-parent-excludes-orphan",
        "flag-disagreement-parent-id-without-is-child",
        "default-order",
    )

    /** Manifest oracle-only cases: operators this app's filters do not support (user decision 2026-10-11). */
    val oracleOnlyCaseIds = listOf(
        "q1-date-month-option",
        "q1-date-year-option",
        "q1-account-contains-name",
        "q1-account-matches-name",
        "q1-account-does-not-contain-name",
        "q1-account-on-budget",
        "q1-account-off-budget",
        "q1-payee-contains-name",
        "q1-payee-matches-name",
        "q1-payee-does-not-contain-name",
        "q1-category-contains-name",
        "q1-category-matches-name",
        "q1-category-does-not-contain-name",
    )

    const val ORACLE_ONLY_REASON = "feature gap: operator not supported by Actualist filters (user decision 2026-10-11)"

    val replayedCaseIds: List<String> get() = queryCaseIds + savedFilterCaseIds + splitModeCaseIds

    /** Field paths (index suffixes dropped) that a registered divergence accounts for, by case. */
    val divergences: Map<String, Pair<String, Set<String>>> = mapOf(
        "q3-free-text-grouped-versus-physical" to ("OP-78" to setOf("rootIDs", "displayedIDs", "contextIDs", "unmatchedIDs")),
        "dead-parent-excludes-orphan" to ("OP-79" to setOf("results.all.ids")),
    )

    private val worldTables = listOf(
        "accounts", "payees", "payee_mapping", "category_groups", "categories", "category_mapping", "transactions",
    )

    private val clearedTables = listOf(
        "transactions", "rules", "schedules", "payee_mapping", "payees",
        "category_mapping", "categories", "category_groups", "accounts", "transaction_filters",
    )

    // region Fixture access

    private fun resource(name: String): JsonObject {
        val stream = TransactionQueryReplaySupport::class.java.getResourceAsStream("/oracle/TransactionQuery/$name")
            ?: throw IllegalStateException("missing fixture $name")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    val contract: JsonObject by lazy { resource("transaction-query-contract.json") }
    val manifest: JsonObject by lazy { resource("transaction-query-manifest.json") }

    val cases: Map<String, JsonObject> by lazy {
        val map = LinkedHashMap<String, JsonObject>()
        for (entry in contract["cases"]!!.jsonArray) map[entry.jsonObject["id"]!!.jsonPrimitive.content] = entry.jsonObject
        map
    }

    fun world(name: String): JsonObject = contract["worlds"]!!.jsonObject[name]!!.jsonObject

    // endregion

    // region Json helpers

    fun str(value: String): JsonElement = JsonPrimitive(value)

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun sortedIds(ids: Collection<String>): JsonElement = JsonArray(ids.sorted().map { str(it) })

    private fun orderedIds(ids: List<String>): JsonElement = JsonArray(ids.map { str(it) })

    private fun strings(element: JsonElement?): List<String> = element!!.jsonArray.map { it.jsonPrimitive.content }

    private fun jsonOf(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun canonical(element: JsonElement): String = SavedFilterJson.encodeValue(RuleValue.fromJson(element))

    /** The conditions JSON as a filter stores it (sorted keys, compact). */
    private fun rawConditions(conditions: List<JsonElement>): String = canonical(JsonArray(conditions))

    private fun decodeConditions(raw: String): List<RuleCondition> =
        RuleJson.decodeConditions(raw) ?: throw IllegalStateException("conditions do not decode: $raw")

    // endregion

    // region Comparison

    /** Compares the whole result; arrays compare by position, a length difference is reported at the array's path. */
    fun compare(expected: JsonElement, actual: JsonElement): List<ReplayMismatch> {
        val mismatches = mutableListOf<ReplayMismatch>()
        compareValue("", expected, actual, mismatches)
        return mismatches
    }

    private fun child(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun compareValue(path: String, expected: JsonElement?, actual: JsonElement?, out: MutableList<ReplayMismatch>) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                for (key in (expected.keys + actual.keys).sorted()) {
                    val left = expected[key]
                    val right = actual[key]
                    if (left == null || right == null) {
                        out.add(ReplayMismatch(child(path, key), left?.toString() ?: "<absent>", right?.toString() ?: "<absent>"))
                    } else {
                        compareValue(child(path, key), left, right, out)
                    }
                }
            }
            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    out.add(ReplayMismatch(path, "$expected", "$actual"))
                }
                for (index in 0 until minOf(expected.size, actual.size)) {
                    compareValue("$path[$index]", expected[index], actual[index], out)
                }
            }
            expected is JsonPrimitive && actual is JsonPrimitive ->
                if (!primitiveEqual(expected, actual)) out.add(ReplayMismatch(path, "$expected", "$actual"))
            else -> out.add(ReplayMismatch(path, "$expected", "$actual"))
        }
    }

    /** Null equals null; a string never equals a number or boolean; numbers compare as doubles. */
    private fun primitiveEqual(left: JsonPrimitive, right: JsonPrimitive): Boolean {
        if (left is JsonNull || right is JsonNull) return left is JsonNull && right is JsonNull
        if (left.isString != right.isString) return false
        if (left.isString) return left.content == right.content
        val leftFlag = left.booleanOrNull
        val rightFlag = right.booleanOrNull
        if (leftFlag != null || rightFlag != null) return leftFlag == rightFlag
        val leftNumber = left.doubleOrNull
        val rightNumber = right.doubleOrNull
        return leftNumber != null && rightNumber != null && leftNumber == rightNumber
    }

    fun report(caseId: String, mismatches: List<ReplayMismatch>): String =
        "$caseId: ${mismatches.size} mismatch(es)\n" + mismatches.joinToString("\n") { "  $it" }

    // endregion

    // region Condition and family cases

    fun expectedQuery(entry: JsonObject): JsonElement {
        val physical = entry["physical"]!!.jsonObject
        val grouped = entry["grouped"]!!.jsonObject
        val fields = queryFields(
            physical = strings(physical["ids"]),
            roots = strings(grouped["rootIDs"]),
            displayed = strings(grouped["displayedIDs"]),
            context = strings(grouped["contextIDs"]),
            unmatched = strings(grouped["unmatchedIDs"]),
        ).toMutableMap()
        (entry["filter"] as? JsonObject)?.let { fields["filter"] = expectedFilter(it) }
        return JsonObject(fields)
    }

    fun replayQuery(entry: JsonObject, world: JsonObject): JsonElement {
        val database = open(world, emptyList())
        return database.use {
            val queries = TransactionQueries(database) { FILTER_ID }
            var filterRun: Pair<List<RuleCondition>, RuleConditionJoin>? = null
            val page: QueryPage
            val search = entry.stringOrNull("search")
            if (search != null) {
                page = queries.query(text = search)
            } else {
                val condition = entry["condition"]!!.jsonObject
                val joinName = entry.string("conditionsOp")
                val raw = rawConditions(listOf(condition))
                val projected = SavedFilter.project("replay", "replay", raw, joinName, false)
                val queryConditions = projected.queryConditions
                val queryJoin = projected.queryJoin
                if (!projected.isSupported || queryConditions == null || queryJoin == null) {
                    return JsonObject(mapOf("unsupported" to str(projected.compatibility.toString())))
                }
                page = queries.query(conditions = queryConditions, join = queryJoin)
                filterRun = decodeConditions(raw) to (RuleConditionJoin.fromRaw(joinName) ?: RuleConditionJoin.AND)
            }

            val roots = page.transactions.mapNotNull { it.id }
            val displayed = page.transactions.flatMap { listOf(it) + it.subtransactions }.mapNotNull { it.id }
            val fields = queryFields(
                physical = page.matchingTransactionIds.toList(),
                roots = roots,
                displayed = displayed,
                context = page.attachedContextTransactionIds.toList(),
                unmatched = page.attachedContextTransactionIds.toList(),
            ).toMutableMap()
            val run = filterRun
            if (entry["filter"] != null && run != null) {
                fields["filter"] = createFilter(entry.string("id"), run.first, run.second, emptyList())
            }
            JsonObject(fields)
        }
    }

    private fun queryFields(
        physical: List<String>,
        roots: List<String>,
        displayed: List<String>,
        context: List<String>,
        unmatched: List<String>,
    ): Map<String, JsonElement> = mapOf(
        "physical" to sortedIds(physical),
        "rootIDs" to sortedIds(roots),
        "displayedIDs" to sortedIds(displayed),
        "contextIDs" to sortedIds(context),
        "unmatchedIDs" to sortedIds(unmatched),
    )

    // endregion

    // region Saved filters

    fun expectedFilterCase(entry: JsonObject): JsonElement = expectedFilter(entry["filter"]!!.jsonObject)

    fun replayFilterCase(entry: JsonObject): JsonElement {
        val candidate = entry["candidate"]!!.jsonObject
        val seeded = entry["seededFilters"]!!.jsonArray.map { it.jsonObject }
        val conditions = decodeConditions(rawConditions(candidate["conditions"]!!.jsonArray.toList()))
        return createFilter(
            candidate.string("name"),
            conditions,
            RuleConditionJoin.fromRaw(candidate.string("conditionsOp")) ?: RuleConditionJoin.AND,
            seeded,
        )
    }

    private fun expectedFilter(filter: JsonObject): JsonElement {
        val rows = filter["rows"]!!.jsonArray.map { element ->
            val row = element.jsonObject
            JsonObject(
                mapOf(
                    "id" to (row["id"] ?: JsonNull),
                    "name" to (row["name"] ?: JsonNull),
                    "conditions" to (row["conditions"] ?: JsonNull),
                    "conditionsOp" to (row["conditionsOp"] ?: JsonNull),
                    "tombstone" to (row["tombstone"] ?: JsonNull),
                ),
            ) as JsonElement
        }
        val messages = filter["messages"]!!.jsonArray.map { element ->
            val message = element.jsonObject
            val column = message.string("column")
            JsonObject(
                mapOf(
                    "dataset" to str(message.string("dataset")),
                    "row" to str(message.string("row")),
                    "column" to str(column),
                    "value" to messageValue(column, message.string("value")),
                ),
            ) as JsonElement
        }
        return JsonObject(
            mapOf(
                "outcome" to str(filter.string("outcome")),
                "rows" to JsonArray(rows.sortedBy { it.jsonObject["id"]?.toString() ?: "" }),
                "messages" to JsonArray(messages.sortedBy { canonical(it) }),
            ),
        )
    }

    private fun createFilter(
        name: String,
        conditions: List<RuleCondition>,
        join: RuleConditionJoin,
        seeded: List<JsonObject>,
    ): JsonElement {
        val database = open(JsonObject(emptyMap()), seeded)
        return database.use {
            val before = database.pendingLocalMessages().map { it.message.timestamp }.toSet()
            val queries = TransactionQueries(database) { FILTER_ID }
            val outcome: String = try {
                queries.createFilter(name, conditions, join)
                "created"
            } catch (error: Exception) {
                val text = error.message ?: ""
                when {
                    text.contains("already a saved filter named") -> "duplicate-name"
                    text.contains("already saved as") -> "duplicate-condition"
                    else -> "error: $text"
                }
            }

            val rows = database.sql.query("SELECT id, name, conditions, conditions_op, tombstone FROM transaction_filters")
                .map { row ->
                    val raw = row.string("conditions")
                    val parsed: JsonElement = if (raw == null) JsonNull else Json.parseToJsonElement(raw)
                    JsonObject(
                        mapOf(
                            "id" to jsonOf(row["id"]),
                            "name" to jsonOf(row["name"]),
                            "conditions" to parsed,
                            "conditionsOp" to jsonOf(row["conditions_op"]),
                            "tombstone" to jsonOf(row["tombstone"]),
                        ),
                    ) as JsonElement
                }
            val messages = database.pendingLocalMessages()
                .map { it.message }
                .filter { it.timestamp !in before && it.column != "tombstone" }
                .map { message ->
                    val text = if (message.serializedValue.startsWith("S:")) message.serializedValue.substring(2) else message.serializedValue
                    JsonObject(
                        mapOf(
                            "dataset" to str(message.dataset),
                            "row" to str(message.row),
                            "column" to str(message.column),
                            "value" to messageValue(message.column, text),
                        ),
                    ) as JsonElement
                }
            JsonObject(
                mapOf(
                    "outcome" to str(outcome),
                    "rows" to JsonArray(rows.sortedBy { it.jsonObject["id"]?.toString() ?: "" }),
                    "messages" to JsonArray(messages.sortedBy { canonical(it) }),
                ),
            )
        }
    }

    /** The `conditions` cell is JSON text; the others are plain strings. Compared by parsed content. */
    private fun messageValue(column: String, text: String): JsonElement =
        if (column == "conditions") Json.parseToJsonElement(text) else str(text)

    // endregion

    // region Split modes

    fun expectedSplitModes(entry: JsonObject): JsonElement {
        val fields = mutableMapOf<String, JsonElement>("results" to (entry["results"] ?: JsonNull))
        entry["balances"]?.let { fields["balances"] = it }
        return JsonObject(fields)
    }

    fun replaySplitModes(entry: JsonObject, world: JsonObject): JsonElement {
        val database = open(world, emptyList())
        return database.use {
            val queries = TransactionQueries(database) { FILTER_ID }
            val account = entry.stringOrNull("account")
            val search = entry.stringOrNull("search")
            fun ids(page: TransactionPage): JsonElement = orderedIds(page.transactions.mapNotNull { it.id })
            val all = queries.page(accountId = account, matching = search, splits = SplitMode.ALL)
            val inlined = queries.page(accountId = account, matching = search, splits = SplitMode.INLINE)
            val none = queries.page(accountId = account, matching = search, splits = SplitMode.NONE)
            val grouped = queries.page(accountId = account, matching = search, splits = SplitMode.GROUPED)
            val matched = all.transactions.mapNotNull { it.id }.toSet()
            val families = grouped.transactions.map { root ->
                val children = root.subtransactions.mapNotNull { it.id }
                val unmatched =
                    if (search == null) emptyList() else (listOfNotNull(root.id) + children).filter { it !in matched }
                JsonObject(
                    mapOf(
                        "id" to str(root.id ?: ""),
                        "childIDs" to orderedIds(children),
                        "unmatchedIDs" to orderedIds(unmatched),
                    ),
                ) as JsonElement
            }
            val fields = mutableMapOf<String, JsonElement>(
                "results" to JsonObject(
                    mapOf(
                        "all" to JsonObject(mapOf("ids" to ids(all))),
                        "inline" to JsonObject(mapOf("ids" to ids(inlined))),
                        "none" to JsonObject(mapOf("ids" to ids(none))),
                        "grouped" to JsonObject(mapOf("ids" to ids(grouped), "families" to JsonArray(families))),
                    ),
                ),
            )
            val expectedBalances = entry["balances"] as? JsonObject
            if (expectedBalances != null) {
                val balances = LedgerReads(database).accounts().associate { it.id to it.balance }
                val actual = LinkedHashMap<String, JsonElement>()
                for (key in expectedBalances.keys) actual[key] = JsonPrimitive((balances[key] ?: 0L).toDouble())
                fields["balances"] = JsonObject(actual)
            }
            JsonObject(fields)
        }
    }

    // endregion

    // region Seeding

    /** A starter-schema budget cleared of its starter rows and seeded from the fixture world and filters. */
    fun open(world: JsonObject, filters: List<JsonObject>): BudgetDatabase {
        val database = TestDatabases.starter(clock = TestClock())
        val sql = database.sql
        sql.transaction {
            for (table in clearedTables) {
                if (database.tableExists(table)) sql.execute("DELETE FROM ${Sql.quoted(table)}")
            }
            for (table in worldTables) {
                val rows = (world[table] as? JsonArray) ?: continue
                for (row in rows) insert(sql, table, row.jsonObject.mapValues { databaseValue(it.value) })
            }
            for (filter in filters) {
                val row = LinkedHashMap<String, Any?>()
                row["id"] = databaseValue(filter["id"] ?: JsonNull)
                row["name"] = databaseValue(filter["name"] ?: JsonNull)
                row["conditions"] = rawConditions(filter["conditions"]!!.jsonArray.toList())
                row["conditions_op"] = databaseValue(filter["conditionsOp"] ?: JsonNull)
                row["tombstone"] = databaseValue(filter["tombstone"] ?: JsonNull)
                insert(sql, "transaction_filters", row)
            }
        }
        return database
    }

    private fun insert(sql: Sql, table: String, row: Map<String, Any?>) {
        val keys = row.keys.sorted()
        val statement = "INSERT INTO ${Sql.quoted(table)} (${keys.joinToString(", ") { Sql.quoted(it) }}) " +
            "VALUES (${keys.joinToString(", ") { "?" }})"
        sql.execute(statement, *keys.map { row[it] }.toTypedArray())
    }

    private fun databaseValue(value: JsonElement): Any? {
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        return primitive.longOrNull ?: primitive.doubleOrNull
    }

    // endregion
}
