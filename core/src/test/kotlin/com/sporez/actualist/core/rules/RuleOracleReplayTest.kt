package com.sporez.actualist.core.rules

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.Sql
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

/**
 * Replays Actualist's pinned Rules oracle (Actual v26.10.0 `runRules`) through this port's
 * rule engine, as Actualist's `RuleOracleReplayTests` does through `previewRules`. Each case
 * seeds the fixture's upstream rows into the starter schema, then every run builds a transaction
 * from the oracle input and compares the whole normalized result with the oracle output.
 *
 * Compared: account, date, amount, payee, notes, category, cleared, schedule, deleted and the
 * split children (amount, category, payee, notes, sort order). Not compared, exactly as in the
 * Swift replay: `imported_payee`, `reconciled`, `_ruleErrors`, created payees, `is_parent`,
 * `error`, child ids, and the `evaluated` and `applied` lists. A run is not replayed when its
 * input leaves `payee`, `category` or `account` absent, or carries `transfer` or `parent`,
 * because production transaction drafts cannot express those shapes.
 *
 * Actualist registers no rules divergences, so none are excused here.
 */
@RunWith(Parameterized::class)
class RuleOracleReplayTest(private val caseId: String) {
    companion object {
        private const val CONTRACT_CASE_COUNT = 269
        private const val REPLAYED_CASE_COUNT = 245
        private val today: LocalDate = LocalDate.parse("2026-10-15")

        private fun resource(name: String): JsonObject {
            val text = RuleOracleReplayTest::class.java.getResourceAsStream("/oracle/Rules/$name")!!
                .bufferedReader().use { it.readText() }
            return Json.parseToJsonElement(text).jsonObject
        }

        private val contract: JsonObject by lazy { resource("rules-contract.json") }
        private val manifest: JsonObject by lazy { resource("rules-manifest.json") }
        private val cases: Map<String, JsonObject> by lazy {
            val map = LinkedHashMap<String, JsonObject>()
            for (entry in contract["cases"]!!.jsonArray) map[entry.jsonObject["id"]!!.jsonPrimitive.content] = entry.jsonObject
            map
        }

        /** Cases only the upstream oracle can run: Swift fail-closes or does not return the field. */
        private val oracleOnly = setOf(
            "formula-balance-of",
            "balance-variable-in-templates-and-formulas",
            "formula-errors-and-non-numeric",
            "template-edge-values",
            "payee-name-creates-or-reuses-payee",
            "set-cleared-and-reconciled",
            "split-parent-fields-in-formula",
            "gen-action-set-payee-name-new",
            "gen-action-set-payee-name-existing",
            "gen-action-set-reconciled",
            "gen-action-set-imported-payee",
            "gen-action-formula-notes",
            "gen-action-formula-amount",
            "gen-action-formula-amount-negate",
            "gen-action-formula-date-today",
            "gen-action-formula-balance-of-id",
            "gen-action-formula-balance-of-name",
            "gen-action-formula-cleared",
            "gen-action-formula-category-name",
            "gen-action-template-notes",
            "gen-action-template-amount",
            "gen-action-template-date",
            "gen-action-template-regex",
            "gen-action-template-cleared",
        )

        /** Rules divergences Actualist registers: none. */
        private val knownDivergences = emptySet<String>()

        /** Features not ported yet: none, formulas and split actions are ported. */
        private val notYetPorted = emptySet<String>()

        private val replayed: List<String> by lazy {
            cases.keys.filter { it !in oracleOnly && it !in knownDivergences && it !in notYetPorted }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = replayed

        /** Input tables, parents before children. Each is cleared of starter rows and refilled. */
        private val seedTables = listOf(
            "accounts", "category_groups", "categories", "category_mapping", "payees", "payee_mapping",
            "rules", "schedules", "schedules_next_date", "transactions",
        )
    }

    @Test
    fun partitionAccountsForEveryContractCase() {
        // One parameterized instance runs the whole-contract checks.
        if (caseId != replayed.first()) return
        val manifestIds = manifest["fixtures"]!!.jsonArray.first().jsonObject["caseIDs"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("contract ids match the manifest", manifestIds, cases.keys.toList())
        assertEquals(CONTRACT_CASE_COUNT, cases.size)
        val manifestOracleOnly = manifest["oracleOnly"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals("oracle-only ids match the manifest", manifestOracleOnly.toSet(), oracleOnly)
        val partitions = listOf(replayed.toSet(), oracleOnly, knownDivergences, notYetPorted)
        for (first in partitions.indices) {
            for (second in first + 1 until partitions.size) {
                assertTrue("partitions $first and $second overlap", partitions[first].intersect(partitions[second]).isEmpty())
            }
        }
        val accounted = partitions.flatMap { it }.toSet()
        assertEquals("every contract case is replayed, oracle-only, divergent or not yet ported", cases.keys, accounted)
        assertEquals(REPLAYED_CASE_COUNT, replayed.size)
        assertTrue(knownDivergences.isEmpty() && notYetPorted.isEmpty())
        assertEquals("v26.10.0", manifest["actual"]!!.jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("2026-10-15", manifest["today"]!!.jsonPrimitive.content)
        assertEquals("America/New_York", manifest["timeZone"]!!.jsonPrimitive.content)
    }

    @Test
    fun replaysRuleCase() {
        val entry = cases.getValue(caseId)
        val input = entry["input"]!!.jsonObject
        val runs = entry["runs"]!!.jsonArray.map { it.jsonObject }
        val payees = input["payees"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject }

        TestDatabases.starter(clock = TestClock()).use { database ->
            database.sql.transaction { seed(database.sql, input) }
            var counter = 0
            val engine = RuleEngine(database) { "child-${++counter}" }
            var replayedRuns = 0
            val failures = mutableListOf<String>()
            for (run in runs) {
                val label = run["label"]!!.jsonPrimitive.content
                val transaction = run["transaction"]!!.jsonObject
                if (!isReplayable(transaction)) continue
                val result = engine.run(makeInput(transaction, payees), today)
                replayedRuns += 1
                val expected = expectedLines(run["output"]!!.jsonObject)
                val actual = actualLines(result)
                if (expected != actual) {
                    failures += "$caseId / $label\n  expected: ${expected.joinToString(" | ")}\n  actual:   ${actual.joinToString(" | ")}"
                }
            }
            assertTrue("$caseId replayed no runs", replayedRuns > 0)
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        }
    }

    // region Replay helpers

    /**
     * A production draft always carries its payee, category and account (null when unset), and
     * never `transfer` or `parent`, so runs shaped otherwise cannot be expressed.
     */
    private fun isReplayable(transaction: JsonObject): Boolean =
        listOf("payee", "category", "account").all { transaction.containsKey(it) } &&
            !transaction.containsKey("transfer") && !transaction.containsKey("parent")

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.optDouble(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    private fun JsonObject.optFlag(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.booleanOrNull ?: false

    private fun makeInput(transaction: JsonObject, payees: Map<String, JsonObject>): RuleTransactionInput {
        val payeeId = transaction.stringOrNull("payee")
        val payee = payeeId?.let { payees[it] }
        return RuleTransactionInput(
            accountId = transaction.stringOrNull("account")!!,
            date = LocalDate.parse(transaction.stringOrNull("date")!!),
            amount = transaction.optDouble("amount")?.toLong() ?: 0L,
            payeeId = payeeId,
            payeeName = payee?.stringOrNull("name") ?: "",
            categoryId = transaction.stringOrNull("category"),
            notes = transaction.stringOrNull("notes"),
            cleared = transaction.optFlag("cleared"),
            isTransfer = payee?.stringOrNull("transfer_acct") != null,
            importedPayee = transaction.stringOrNull("imported_payee"),
            sortOrder = transaction.optDouble("sort_order"),
            reconciled = transaction.optFlag("reconciled"),
            scheduleId = transaction.stringOrNull("schedule"),
        )
    }

    private fun number(value: Double?): String {
        if (value == null) return "null"
        val whole = value == Math.rint(value) && Math.abs(value) < 1e15
        return "n:" + if (whole) value.toLong().toString() else value.toString()
    }

    private fun text(value: String?): String = if (value == null) "null" else "s:$value"

    private fun element(source: JsonObject, key: String): String {
        val value = source[key]
        if (value == null || value is JsonNull) return "null"
        val primitive = value.jsonPrimitive
        if (primitive.isString) return "s:${primitive.content}"
        primitive.booleanOrNull?.let { return "n:" + (if (it) 1 else 0) }
        return number(primitive.doubleOrNull)
    }

    /** The oracle output reduced to the compared fields, in a fixed order. */
    private fun expectedLines(output: JsonObject): List<String> {
        val lines = mutableListOf<String>()
        for (key in listOf("account", "date", "amount", "payee", "notes", "category", "cleared", "schedule")) {
            lines += "$key=${element(output, key)}"
        }
        lines += "deleted=n:${if (output["tombstone"]?.jsonPrimitive?.longOrNull == 1L) 1 else 0}"
        val children = (output["subtransactions"] as? JsonArray) ?: JsonArray(emptyList())
        for ((index, child) in children.withIndex()) {
            val split = child.jsonObject
            lines += "split$index=" + listOf("amount", "category", "payee", "notes", "sort_order")
                .joinToString(",") { element(split, it) }
        }
        return lines
    }

    private fun actualLines(result: RuleRunResult): List<String> {
        val lines = mutableListOf<String>()
        lines += "account=${text(result.accountId)}"
        lines += "date=${text(result.date.toString())}"
        lines += "amount=${number(result.amount.toDouble())}"
        lines += "payee=${text(result.payeeId)}"
        lines += "notes=${text(result.notes)}"
        lines += "category=${text(result.categoryId)}"
        lines += "cleared=n:${if (result.cleared) 1 else 0}"
        lines += "schedule=${text(result.scheduleId)}"
        lines += "deleted=n:${if (result.deletesTransaction) 1 else 0}"
        for ((index, split) in result.splits.withIndex()) {
            lines += "split$index=" + listOf(
                number(split.amount.toDouble()), text(split.categoryId), text(split.payeeId),
                text(split.notes), number(split.sortOrder),
            ).joinToString(",")
        }
        return lines
    }

    // endregion

    // region Seeding

    private fun seed(sql: Sql, input: JsonObject) {
        for (table in seedTables.reversed()) sql.execute("DELETE FROM ${Sql.quoted(table)}")
        for (table in seedTables) {
            val rows = (input[table] as? JsonArray) ?: continue
            for (row in rows) {
                val fields = row.jsonObject
                val columns = fields.keys.sorted()
                val statement = "INSERT INTO ${Sql.quoted(table)} (${columns.joinToString(", ") { Sql.quoted(it) }}) " +
                    "VALUES (${columns.joinToString(", ") { "?" }})"
                sql.execute(statement, *columns.map { databaseValue(fields.getValue(it)) }.toTypedArray())
            }
        }
    }

    private fun databaseValue(value: JsonElement): Any? {
        if (value is JsonNull) return null
        val primitive = value.jsonPrimitive
        if (primitive.isString) return primitive.content
        // Booleans are stored as 0 or 1; integers stay integers; everything else is a real.
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        return primitive.longOrNull ?: primitive.doubleOrNull
    }

    // endregion
}
