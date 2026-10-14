package com.sporez.actualist.core.commands

import com.sporez.actualist.core.LocalFirstException
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

/** How a registered divergence shows up in a case, as in Actualist's replay. */
enum class DivergenceKind {
    /** Actualist refuses where Actual merges or clones; the refusal writes nothing. */
    REFUSED_WHERE_ACTUAL_ALLOWS,

    /** Both refuse, but Actual's non-atomic handler clears transfer links before refusing. */
    ACTUAL_WRITES_BEFORE_REFUSING,

    /** Actualist blocks the undo after a later edit; Actual's undo overwrites it. */
    UNDO_BLOCKED_AFTER_LATER_EDIT,
}

/**
 * Replays the Transaction Command oracle (duplicate D1/D2 and merge M1 to M4, Actual v26.10.0)
 * through [TransactionCommands] on a starter-schema budget seeded from the fixture rows, as
 * Actualist's `TransactionCommandOracleReplayTests` does.
 *
 * Compared per case: allowed versus refused; for a merge the kept row; every comparable column of
 * every row after the command; and, for a duplicate, the live rule count. Not compared, exactly as
 * in the Swift replay: `sort_order` (Actual's is its frozen clock, 123456789; this port allocates
 * from the commit clock), the refusal text, and the CRDT message lists (Actual repeats cells,
 * Actualist writes only changed cells). Minted ids are compared by structure: a new row is named
 * by its own content, and its parent or transfer link by the linked row's content.
 *
 * NOT YET PORTED: the `undo` half of every case. Actualist undoes a command through its action
 * log, which this port does not have, so every comparison under the fixture's `undo` key, and the
 * `conflict` edit that precedes the undo, is skipped. Two cases exist only to test undo
 * (M4-undo-conflict-after-merge, M4-undo-restores-both-graphs); their merge step is replayed in
 * full, their undo is the part that is not ported.
 */
@RunWith(Parameterized::class)
class TransactionCommandOracleReplayTest(private val caseId: String) {
    companion object {
        /** Swift's `TransactionCommandOracleReplay.now`, in milliseconds. */
        private const val NOW_MILLIS = 1_789_344_000_000L

        private fun resource(name: String): JsonObject {
            val text = TransactionCommandOracleReplayTest::class.java
                .getResourceAsStream("/oracle/TransactionCommand/$name")!!
                .bufferedReader().use { it.readText() }
            return Json.parseToJsonElement(text).jsonObject
        }

        val contract: JsonObject by lazy { resource("transaction-command-contract.json") }
        val manifest: JsonObject by lazy { resource("transaction-command-manifest.json") }
        val cases: Map<String, JsonObject> by lazy {
            val map = LinkedHashMap<String, JsonObject>()
            for (entry in contract["cases"]!!.jsonArray) map[entry.jsonObject["id"]!!.jsonPrimitive.content] = entry.jsonObject
            map
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = replayedCaseIds

        /** Every fixture case; the handler step of each one is replayed and compared. */
        val replayedCaseIds: List<String> = listOf(
            "D1-simple-root",
            "D1-split-root-two-children",
            "D1-split-child-alone",
            "D1-parent-and-child",
            "D1-transfer-root",
            "D2-import-metadata-root",
            "D2-split-metadata-parent-only",
            "D2-sort-order-clone",
            "D2-transfer-pair-clone",
            "D2-split-error-clone",
            "D2-duplicate-does-not-request-learning",
            "M1-imported-over-manual-forward",
            "M1-imported-over-manual-reverse",
            "M1-imported-payee-over-manual",
            "M1-earlier-date-forward",
            "M1-earlier-date-reverse",
            "M1-equal-import-equal-date-forward",
            "M1-equal-import-equal-date-reverse",
            "M2-neither-split",
            "M2-keep-simple-drop-split",
            "M2-keep-split-drop-simple",
            "M2-both-splits-two-children",
            "M2-split-zero-children",
            "M2-split-one-child",
            "M2-selected-child-vs-simple",
            "M2-selected-children-same-parent",
            "M2-conflicting-split-families",
            "M3-neither-transfer",
            "M3-one-transfer-on-budget",
            "M3-one-transfer-off-budget",
            "M3-two-transfers-same-destination",
            "M3-two-transfers-different-destination",
            "M3-paired-reconciled",
            "M3-partial-transfer-pair",
            "M3-malformed-transfer-pair",
            "M4-atomic-forward-graph",
            "M4-atomic-reject-invalid-second-pair",
            "M4-undo-conflict-after-merge",
            "M4-undo-restores-both-graphs",
        )

        /**
         * Cases whose point is the undo, which needs the action log this port lacks. Their merge
         * step is still replayed above; only the undo comparison is not ported.
         */
        val notYetPorted: Set<String> = setOf("M4-undo-conflict-after-merge", "M4-undo-restores-both-graphs")

        /** Actualist's registered divergences for this domain (known-divergences.json, OP-70 to OP-76). */
        val divergences: Map<String, Pair<String, DivergenceKind>> = mapOf(
            "D2-split-error-clone" to ("OP-70" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M2-selected-child-vs-simple" to ("OP-71" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M2-selected-children-same-parent" to ("OP-71" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M2-split-zero-children" to ("OP-72" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M2-conflicting-split-families" to ("OP-72" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M3-partial-transfer-pair" to ("OP-73" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M3-malformed-transfer-pair" to ("OP-73" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M2-keep-split-drop-simple" to ("OP-74" to DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS),
            "M4-atomic-reject-invalid-second-pair" to ("OP-75" to DivergenceKind.ACTUAL_WRITES_BEFORE_REFUSING),
            "M4-undo-conflict-after-merge" to ("OP-76" to DivergenceKind.UNDO_BLOCKED_AFTER_LATER_EDIT),
        )

        /** Fixture key to physical `transactions` column. */
        private val transactionColumns: List<Pair<String, String>> = listOf(
            "id" to "id", "is_parent" to "isParent", "is_child" to "isChild", "account" to "acct",
            "category_mapping_id" to "category", "amount" to "amount", "payee_mapping_id" to "description",
            "notes" to "notes", "date" to "date", "imported_id" to "financial_id", "type" to "type",
            "location" to "location", "error" to "error", "imported_payee" to "imported_description",
            "starting_balance_flag" to "starting_balance_flag", "transfer_id" to "transferred_id",
            "sort_order" to "sort_order", "tombstone" to "tombstone", "cleared" to "cleared",
            "pending" to "pending", "parent_id" to "parent_id", "schedule" to "schedule",
            "reconciled" to "reconciled", "raw_synced_data" to "raw_synced_data",
        )

        private val uncomparableKeys = setOf("sort_order")

        private val referenceTables: List<Triple<String, String, Map<String, String>>> = listOf(
            Triple("accounts", "accounts", emptyMap()),
            Triple("categoryGroups", "category_groups", emptyMap()),
            Triple("categories", "categories", emptyMap()),
            Triple("categoryMappings", "category_mapping", mapOf("category_id" to "transferId")),
            Triple("payees", "payees", emptyMap()),
            Triple("payeeMappings", "payee_mapping", mapOf("payee_id" to "targetId")),
            Triple("schedules", "schedules", emptyMap()),
        )
    }

    private data class Observed(val outcome: String, val kept: String?, val rows: List<Map<String, Any?>>, val liveRules: Long?)

    @Test
    fun replaysTransactionCommandCase() {
        val environment = contract["environment"]!!.jsonObject
        assertEquals("UTC", environment["timeZone"]!!.jsonPrimitive.content)

        val entry = cases.getValue(caseId)
        val input = entry["input"]!!.jsonObject["transactions"]!!.jsonArray
        val seeded: Set<String> = input.mapNotNull { (it.jsonObject["id"] as? JsonPrimitive)?.content }.toSet()
        val expected = expected(entry, seeded)
        val actual = replay(entry, input, seeded)
        val mismatches = compare(entry, expected, actual)

        val divergence = divergences[caseId]
        if (divergence == null) {
            assertTrue("$caseId\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
            return
        }
        val seededRows = canonical(fixtureRows(input), seeded)
        val known: List<String> = when (divergence.second) {
            DivergenceKind.REFUSED_WHERE_ACTUAL_ALLOWS -> {
                assertEquals("ok", expected.outcome)
                assertEquals("refused", actual.outcome)
                assertEquals(seededRows, actual.rows)
                mismatches
            }
            DivergenceKind.ACTUAL_WRITES_BEFORE_REFUSING -> {
                assertEquals("refused", expected.outcome)
                assertEquals("refused", actual.outcome)
                assertEquals(seededRows, actual.rows)
                val transferLinks = Regex("""^rows\[\d+]\.transfer_id.*""")
                mismatches.filter { transferLinks.matches(it) }
            }
            DivergenceKind.UNDO_BLOCKED_AFTER_LATER_EDIT -> {
                // Only the undo diverges, and the undo is not ported: the merge step stays strict.
                assertEquals("ok", entry["undo"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
                emptyList()
            }
        }
        val strict = mismatches.filter { it !in known }
        assertTrue("$caseId (${divergence.first})\n" + strict.joinToString("\n"), strict.isEmpty())
    }

    // region Expected

    private fun expected(entry: JsonObject, seeded: Set<String>): Observed {
        val handler = entry["handler"]!!.jsonObject
        val afterHandler = entry["afterHandler"]!!.jsonObject
        val rows = canonical(fixtureRows(afterHandler["transactions"]!!.jsonArray), seeded)
        if (handler["outcome"]!!.jsonPrimitive.content != "ok") return Observed("refused", null, rows, null)
        val kept = (handler["result"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val liveRules = (entry["rules"] as? JsonObject)?.get("afterHandler")?.jsonArray?.size?.toLong()
        return Observed("ok", kept, rows, liveRules)
    }

    private fun fixtureRows(array: JsonArray): List<Map<String, Any?>> = array.map { element ->
        val row = element.jsonObject
        val fields = LinkedHashMap<String, Any?>()
        for ((key, _) in transactionColumns) {
            if (key in uncomparableKeys) continue
            fields[key] = jsonValue(row[key] ?: JsonNull)
        }
        fields
    }

    // endregion

    // region Replay

    private fun replay(entry: JsonObject, input: JsonArray, seeded: Set<String>): Observed {
        val clock = TestClock(NOW_MILLIS)
        var counter = 0
        return TestDatabases.starter("node1", clock).use { database ->
            seed(database.sql, input)
            val commands = TransactionCommands(database, clock) { "uuid-${++counter}" }
            val selected = entry["selected"]!!.jsonArray.map { it.jsonPrimitive.content }

            var kept: String? = null
            val outcome = try {
                if (caseId.startsWith("D")) {
                    commands.duplicate(selected)
                } else {
                    val preview = commands.previewMerge(selected[0], selected[1])
                    // Actual merges reconciled rows without asking; Actualist asks first, so the
                    // replay answers the confirmation. That is a UI step, not a refusal.
                    kept = commands.merge(selected[0], selected[1], allowReconciled = preview.reconciledIds.isNotEmpty()).keeperId
                }
                "ok"
            } catch (refusal: LocalFirstException.InvalidLocalWrite) {
                "refused"
            }

            val rows = canonical(readRows(database.sql), seeded)
            val liveRules = database.sql.queryLong("SELECT COUNT(*) FROM rules WHERE COALESCE(tombstone, 0) = 0") ?: 0L
            if (outcome != "ok") {
                Observed("refused", null, rows, null)
            } else {
                Observed("ok", kept, rows, if (entry["rules"] != null) liveRules else null)
            }
        }
    }

    private fun readRows(sql: Sql): List<Map<String, Any?>> = sql.query("SELECT * FROM transactions").map { row ->
        val fields = LinkedHashMap<String, Any?>()
        for ((key, column) in transactionColumns) {
            if (key in uncomparableKeys) continue
            fields[key] = normalize(row[column])
        }
        fields
    }

    private fun seed(sql: Sql, rows: JsonArray) {
        val referenceData = contract["referenceData"]!!.jsonObject
        sql.transaction {
            sql.executeScript(
                "DELETE FROM transactions; DELETE FROM rules; DELETE FROM schedules; DELETE FROM payee_mapping; " +
                    "DELETE FROM payees; DELETE FROM category_mapping; DELETE FROM categories; " +
                    "DELETE FROM category_groups; DELETE FROM accounts;",
            )
            for ((key, table, rename) in referenceTables) {
                val reference = (referenceData[key] as? JsonArray) ?: JsonArray(emptyList())
                for (row in reference) insert(sql, row.jsonObject, table, rename)
            }
            val physical = transactionColumns.toMap()
            for (row in rows) insert(sql, row.jsonObject, "transactions", physical)
        }
    }

    private fun insert(sql: Sql, row: JsonObject, table: String, rename: Map<String, String>) {
        val keys = row.keys.sorted()
        val columns = keys.joinToString(", ") { Sql.quoted(rename[it] ?: it) }
        val marks = keys.joinToString(", ") { "?" }
        val values: List<Any?> = keys.map { jsonValue(row.getValue(it)) }
        sql.execute("INSERT INTO $table ($columns) VALUES ($marks)", *values.toTypedArray())
    }

    // endregion

    // region Comparison

    private fun jsonValue(element: JsonElement): Any? {
        if (element is JsonNull) return null
        val primitive = element.jsonPrimitive
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return if (it) 1L else 0L }
        return normalize(primitive.longOrNull ?: primitive.doubleOrNull)
    }

    private fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is Int -> value.toLong()
        is Double -> if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong() else value
        else -> value
    }

    private fun render(row: Map<String, Any?>): String =
        row.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
            "$key=" + when (value) {
                null -> "null"
                is String -> "\"$value\""
                else -> value.toString()
            }
        }

    /** Rows sorted by content with new ids and their links replaced by the content they point at. */
    private fun canonical(rows: List<Map<String, Any?>>, seeded: Set<String>): List<Map<String, Any?>> {
        val byId = HashMap<String, Map<String, Any?>>()
        for (row in rows) (row["id"] as? String)?.let { byId[it] = row }
        val linkKeys = setOf("id", "parent_id", "transfer_id")
        fun signature(row: Map<String, Any?>): String = render(row.filterKeys { it !in linkKeys })
        fun newTarget(value: Any?): Map<String, Any?>? {
            val id = value as? String ?: return null
            if (id in seeded) return null
            return byId[id]
        }
        fun link(value: Any?): Any? = newTarget(value)?.let { "new[" + signature(it) + "]" } ?: value
        val named = rows.map { row ->
            // A tombstoned row's links are not compared: they are unobservable, and Actual clears
            // or keeps them on dead rows depending on the handler and on undo.
            val dead = row["tombstone"] == 1L
            val fields = LinkedHashMap<String, Any?>(row)
            fields["id"] = newTarget(row["id"])?.let { "new[" + signature(it) + "]" } ?: row["id"]
            fields["parent_id"] = if (dead) null else link(row["parent_id"])
            fields["transfer_id"] = if (dead) null else link(row["transfer_id"])
            fields
        }
        return named.sortedBy { render(it) }
    }

    private fun compare(entry: JsonObject, expected: Observed, actual: Observed): List<String> {
        val mismatches = mutableListOf<String>()
        fun differ(path: String, want: Any?, got: Any?) {
            if (want != got) mismatches += "$path: expected $want, actual $got"
        }
        differ("outcome", expected.outcome, actual.outcome)
        differ("kept", expected.kept, actual.kept)
        differ("rows.count", expected.rows.size, actual.rows.size)
        for (index in 0 until minOf(expected.rows.size, actual.rows.size)) {
            val want = expected.rows[index]
            val got = actual.rows[index]
            for (key in (want.keys + got.keys).sorted()) differ("rows[$index].$key", want[key], got[key])
        }
        if (entry["rules"] != null && expected.outcome == "ok" && actual.outcome == "ok") {
            differ("rules.afterHandler", expected.liveRules, actual.liveRules)
        }
        return mismatches
    }

    // endregion
}

/** Every fixture case is replayed, excused as Actualist excuses it, or listed as not yet ported. */
class TransactionCommandOracleManifestTest {
    @Test
    fun partitionsEveryFixtureCase() {
        val fixtureIds = TransactionCommandOracleReplayTest.cases.keys.toList()
        val manifestIds = TransactionCommandOracleReplayTest.manifest["fixtures"]!!.jsonArray.single()
            .jsonObject["caseIDs"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(39, fixtureIds.size)
        assertEquals(manifestIds, fixtureIds)

        val replayed = TransactionCommandOracleReplayTest.replayedCaseIds
        assertEquals("replayed ids must be unique", replayed.size, replayed.toSet().size)
        val excused = TransactionCommandOracleReplayTest.divergences.keys
        val notYetPorted = TransactionCommandOracleReplayTest.notYetPorted
        val unaccounted = fixtureIds.filter { it !in replayed && it !in excused && it !in notYetPorted }
        assertTrue("unaccounted cases: $unaccounted", unaccounted.isEmpty())
        val unknown = (replayed + excused + notYetPorted).filter { it !in fixtureIds }
        assertTrue("unknown cases: $unknown", unknown.isEmpty())
        // Excused and not-yet-ported cases still replay their handler step in full.
        assertTrue((excused + notYetPorted).all { it in replayed })
        println(
            "transaction-command oracle: ${replayed.size} replayed (handler step), ${excused.size} excused as Actualist excuses them, " +
                "${notYetPorted.size} undo-only cases not yet ported; undo comparisons skipped for every case",
        )
    }
}
