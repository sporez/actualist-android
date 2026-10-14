package com.sporez.actualist.core.importer

import com.sporez.actualist.core.schedules.OracleValue
import com.sporez.actualist.core.schedules.ReplayMismatch
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

/**
 * The BankSync oracle contract: the shared world rows, and each case's setup, post-mapping candidates,
 * result and after-state. Port of Actualist `BankSyncOracleFixture`.
 */
object BankSyncOracleFixture {
    const val FIXTURE_TODAY = "2026-10-15"
    const val FIXTURE_TIME_ZONE = "America/New_York"
    const val PINNED_TAG = "v26.10.0"
    const val PINNED_COMMIT = "2bebdbaae0b701120a8cad9d56aafd64fa04c577"

    private fun resource(name: String): JsonObject {
        val stream = BankSyncOracleFixture::class.java.getResourceAsStream("/oracle/BankSync/$name")
            ?: throw IllegalStateException("missing oracle fixture $name")
        return Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    val contract: JsonObject by lazy { resource("bank-sync-contract.json") }
    val manifest: JsonObject by lazy { resource("bank-sync-manifest.json") }
    val worldTables: JsonObject by lazy { contract["worldTables"]!!.jsonObject }

    /** A duplicate case id throws, like Swift's loader. */
    val cases: Map<String, JsonObject> by lazy {
        val map = LinkedHashMap<String, JsonObject>()
        for (entry in contract["cases"]!!.jsonArray) {
            val id = entry.jsonObject["id"]!!.jsonPrimitive.content
            check(id !in map) { "duplicate case id $id" }
            map[id] = entry.jsonObject
        }
        map
    }

    fun entry(caseId: String): JsonObject = cases[caseId] ?: throw IllegalStateException("unknown case $caseId")

    /** Cases only the upstream oracle can run, from the manifest's `oracleOnly` list. */
    val oracleOnlyIds: Set<String> by lazy {
        manifest["oracleOnly"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    }

    val manifestCaseIds: List<String> by lazy {
        manifest["fixtures"]!!.jsonArray.first().jsonObject["caseIDs"]!!.jsonArray.map { it.jsonPrimitive.content }
    }
}

/** JSON reading helpers for the fixture. */
object OracleJson {
    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    fun bool(element: JsonElement?): Boolean? = (element as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull

    fun long(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        return primitive.doubleOrNull?.toLong()
    }

    fun objects(element: JsonElement?): List<JsonObject> = (element as? JsonArray)?.map { it.jsonObject } ?: emptyList()

    fun rows(element: JsonElement?): List<Map<String, OracleValue>> =
        objects(element).map { row -> row.entries.associate { entry -> entry.key to OracleValue.fromJson(entry.value) } }

    fun ids(element: JsonElement?): Set<String> = objects(element).mapNotNull { string(it["id"]) }.toSet()
}

/**
 * One side of the comparison: the rows and payees after the call, with every id the call minted replaced by
 * a name derived from the row's content, so the two sides need no shared numbering.
 */
object BankSyncOracleState {
    /** Fields compared per transaction. `raw_synced_data` is excluded (Actualist keeps no provider blob). */
    val transactionFields = listOf(
        "account", "date", "amount", "payee", "imported_payee", "notes", "category", "cleared", "reconciled",
        "imported_id", "schedule", "is_parent", "is_child", "parent_id", "tombstone", "transfer_id",
        "starting_balance_flag", "error",
    )
    private val referenceFields = setOf("id", "transfer_id", "parent_id")
    private val flagFields = setOf("is_parent", "is_child", "starting_balance_flag", "tombstone", "reconciled")

    private class Staged(val id: String, val row: Map<String, OracleValue>, val signature: String)

    /**
     * @param transactions public-named rows, ids as the side produced them.
     * @param payees `id`, `name`, `transfer_acct`, `tombstone` rows.
     * @param rules `id`, `tombstone` rows.
     * @param baselineTransactionIds rows present before the call; every other row is new.
     * @param baselinePayeeIds payees present before the call; every other payee is new.
     */
    fun canonical(
        transactions: List<Map<String, OracleValue>>,
        payees: List<Map<String, OracleValue>>,
        rules: List<Map<String, OracleValue>>,
        baselineTransactionIds: Set<String>,
        baselinePayeeIds: Set<String>,
    ): OracleValue.Obj {
        val payeeNames = HashMap<String, String>()
        val payeeRows = LinkedHashMap<String, OracleValue>()
        for (payee in payees) {
            val id = (payee["id"] as? OracleValue.Str)?.value ?: continue
            var mapped = id
            val name = (payee["name"] as? OracleValue.Str)?.value
            if (id !in baselinePayeeIds && name != null) {
                mapped = "new-payee:$name"
                payeeNames[id] = mapped
            }
            val row = LinkedHashMap<String, OracleValue>()
            for (key in listOf("name", "transfer_acct", "tombstone")) row[key] = payee[key] ?: OracleValue.Null
            payeeRows[mapped] = OracleValue.Obj(row)
        }

        // A new row is named by its content, with ids stripped, in sorted order.
        val staged = ArrayList<Staged>()
        for (transaction in transactions) {
            val id = (transaction["id"] as? OracleValue.Str)?.value ?: continue
            val row = LinkedHashMap<String, OracleValue>()
            for (key in transactionFields) {
                val value = transaction[key] ?: OracleValue.Null
                row[key] = if (key in flagFields && value == OracleValue.Null) OracleValue.Num(0.0) else value
            }
            val payee = (row["payee"] as? OracleValue.Str)?.value
            if (payee != null) payeeNames[payee]?.let { row["payee"] = OracleValue.Str(it) }
            // Identity first (opening-balance flag, provider id, account), so a differing amount or date does
            // not shift the names of the other new rows.
            val identity = listOf("starting_balance_flag", "imported_id", "account")
            val signature = (identity + transactionFields.filter { it !in referenceFields && it !in identity })
                .joinToString("|") { "$it=${(row[it] ?: OracleValue.Null).canonical}" }
            staged.add(Staged(id, row, signature))
        }
        val names = HashMap<String, String>()
        val fresh = staged.filter { it.id !in baselineTransactionIds }
            .sortedWith(compareBy<Staged>({ it.signature }, { it.id }))
        for ((index, item) in fresh.withIndex()) names[item.id] = "new-${index + 1}"

        val transactionObject = LinkedHashMap<String, OracleValue>()
        for (item in staged) {
            val row = LinkedHashMap(item.row)
            for (key in listOf("transfer_id", "parent_id")) {
                val reference = (row[key] as? OracleValue.Str)?.value
                if (reference != null) names[reference]?.let { row[key] = OracleValue.Str(it) }
            }
            transactionObject[names[item.id] ?: item.id] = OracleValue.Obj(row)
        }
        val ruleObject = LinkedHashMap<String, OracleValue>()
        for (rule in rules) {
            val id = (rule["id"] as? OracleValue.Str)?.value ?: continue
            ruleObject[id] = rule["tombstone"] ?: OracleValue.Null
        }
        return OracleValue.Obj(
            mapOf(
                "transactions" to OracleValue.Obj(transactionObject),
                "payees" to OracleValue.Obj(payeeRows),
                "rules" to OracleValue.Obj(ruleObject),
            ),
        )
    }
}

/**
 * Both sides are compared in full, as Actualist's `OracleComparison` does: every key of either object,
 * arrays by position, numbers exactly. The fixture uses no placeholders.
 */
object BankSyncComparison {
    fun mismatches(expected: OracleValue, actual: OracleValue, path: String = ""): List<ReplayMismatch> {
        val result = ArrayList<ReplayMismatch>()
        compare(expected, actual, path, result)
        return result
    }

    private fun compare(expected: OracleValue, actual: OracleValue, path: String, out: MutableList<ReplayMismatch>) {
        if (expected is OracleValue.Obj && actual is OracleValue.Obj) {
            for (key in (expected.fields.keys + actual.fields.keys).toSortedSet()) {
                val childPath = if (path.isEmpty()) key else "$path.$key"
                val expectedField = expected.fields[key]
                val actualField = actual.fields[key]
                if (expectedField != null && actualField != null) {
                    compare(expectedField, actualField, childPath, out)
                } else if (expectedField != null) {
                    out.add(ReplayMismatch(childPath, expectedField.canonical, "missing"))
                } else if (actualField != null) {
                    out.add(ReplayMismatch(childPath, "missing", actualField.canonical))
                }
            }
        } else if (expected is OracleValue.Arr && actual is OracleValue.Arr) {
            if (expected.values.size != actual.values.size) {
                out.add(ReplayMismatch(path, "${expected.values.size} items", "${actual.values.size} items"))
                return
            }
            for (index in expected.values.indices) {
                compare(expected.values[index], actual.values[index], "$path[$index]", out)
            }
        } else if (expected != actual) {
            out.add(ReplayMismatch(path, expected.canonical, actual.canonical))
        }
    }

    fun report(caseId: String, mismatches: List<ReplayMismatch>): String =
        mismatches.joinToString("\n") { "$caseId ${it.path}: expected ${it.expected}, actual ${it.actual}" }
}
