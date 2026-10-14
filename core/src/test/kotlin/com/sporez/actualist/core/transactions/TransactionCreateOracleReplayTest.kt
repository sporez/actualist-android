package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncValue
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
import java.time.LocalDate

/**
 * Replays Actualist's pinned Transactions oracle (Actual v26.10.0 manual, transfer, import
 * and starting-balance creation) through this port's transaction writers, comparing the
 * exact CRDT cells as a multiset, as Actualist's `TransactionCreateOracleReplayTests` does.
 * `sort_order` compares by presence (a clock in Actual). The only excused difference is
 * Actualist's intended OP-86 (category placement on two transfer shapes).
 */
@RunWith(Parameterized::class)
class TransactionCreateOracleReplayTest(private val caseId: String) {
    companion object {
        private val contract: JsonObject by lazy {
            val text = TransactionCreateOracleReplayTest::class.java
                .getResourceAsStream("/oracle/Transactions/transactions-contract.json")!!
                .bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject
        }
        private val cases by lazy { contract["cases"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject } }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = cases.keys.toList()

        private val op86Cases = setOf("transfer-on-to-on-category-supplied", "transfer-off-to-on")

        private const val WORLD_SQL = """
            DELETE FROM transactions; DELETE FROM payee_mapping; DELETE FROM payees;
            DELETE FROM category_mapping; DELETE FROM categories; DELETE FROM category_groups;
            DELETE FROM accounts;
            INSERT INTO category_groups (id, name, is_income, sort_order, tombstone) VALUES
                ('group-oracle', 'Oracle', 0, 16384, 0), ('group-income', 'Income', 1, 32768, 0);
            INSERT INTO categories (id, name, is_income, cat_group, sort_order, tombstone) VALUES
                ('category-food', 'Food', 0, 'group-oracle', 16384, 0),
                ('category-income', 'Income', 1, 'group-income', 16384, 0),
                ('category-starting', 'Starting Balances', 1, 'group-income', 32768, 0);
            INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES
                ('acct-main', 'Main', 0, 0, 0, 16384), ('acct-target', 'Target', 0, 0, 0, 32768),
                ('acct-off-a', 'Off budget A', 1, 0, 0, 49152), ('acct-off-b', 'Off budget B', 1, 0, 0, 65536),
                ('acct-new', 'New account', 0, 0, 0, 81920);
            INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES
                ('payee-regular', 'Merchant', NULL, 0),
                ('payee-to-main', 'Transfer to Main', 'acct-main', 0),
                ('payee-to-target', 'Transfer to Target', 'acct-target', 0),
                ('payee-to-off-a', 'Transfer to Off A', 'acct-off-a', 0),
                ('payee-to-off-b', 'Transfer to Off B', 'acct-off-b', 0);
            INSERT INTO payee_mapping (id, targetId) SELECT id, id FROM payees;
        """
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun canonical(value: SyncValue, column: String): String = when (value) {
        SyncValue.Null -> "null"
        is SyncValue.Integer -> if (column == "sort_order") "present" else "n:${value.value}"
        is SyncValue.Real -> if (column == "sort_order") "present" else "n:${SyncValue.formatNumber(value.value)}"
        is SyncValue.Text -> "s:${value.value}"
    }

    private fun canonicalJson(element: kotlinx.serialization.json.JsonElement?, column: String): String {
        if (element == null || element is JsonNull) return "null"
        val primitive = element.jsonPrimitive
        if (primitive.isString) return if (column == "sort_order" && primitive.content == "<sort-order>") "present" else "s:${primitive.content}"
        val number = primitive.doubleOrNull!!
        return "n:${SyncValue.formatNumber(number)}"
    }

    @Test
    fun replaysCreateCase() {
        val entry = cases.getValue(caseId)
        val input = entry["input"]!!.jsonObject
        val expected = entry["expected"]!!.jsonArray.map {
            val cell = it.jsonObject
            val column = cell["column"]!!.jsonPrimitive.content
            "${cell["dataset"]!!.jsonPrimitive.content} ${cell["row"]!!.jsonPrimitive.content} $column = ${canonicalJson(cell["value"], column)}"
        }
        val clock = TestClock()
        var counter = 0
        val aliases = HashMap<String, String>()
        TestDatabases.starter(clock = clock).use { database ->
            database.sql.transaction { database.sql.executeScript(WORLD_SQL) }
            val writes = TransactionWrites(database, clock) { "generated-${++counter}" }
            val drafts: List<CellDraft> = try {
                plan(entry["kind"]!!.jsonPrimitive.content, input, writes, aliases)
            } catch (error: LocalFirstException) {
                emptyList()
            }
            // The plan must also commit cleanly through the real write path.
            if (drafts.isNotEmpty()) database.commitLocal(drafts)
            val actual = drafts.map { draft ->
                val value = canonical(draft.value, draft.column).let { text ->
                    if (text.startsWith("s:")) aliases[text.removePrefix("s:")]?.let { "s:$it" } ?: text else text
                }
                "${draft.dataset} ${aliases[draft.row] ?: draft.row} ${draft.column} = $value"
            }
            val remaining = actual.toMutableList()
            val missing = mutableListOf<String>()
            for (cell in expected) if (!remaining.remove(cell)) missing += cell
            val differences = missing.map { "missing: $it" } + remaining.map { "extra: $it" }
            val (op86, strict) = differences.partition {
                caseId in op86Cases && it.contains(" category = ") && !it.endsWith("= null")
            }
            assertTrue("$caseId\n" + strict.joinToString("\n"), strict.isEmpty())
            if (op86.isNotEmpty()) println("$caseId: ${op86.size} OP-86 cell(s) excused")
        }
    }

    private fun plan(kind: String, input: JsonObject, writes: TransactionWrites, aliases: MutableMap<String, String>): List<CellDraft> {
        val txn = "txn-new"
        aliases[txn] = txn
        fun draft(isTransfer: Boolean) = TransactionDraft(
            accountId = input.string("account")!!,
            date = LocalDate.parse(input.string("date")!!),
            amount = input.long("amount") ?: 0,
            payeeId = input.string("payee"),
            payeeName = input.string("newPayeeName") ?: "",
            categoryId = input.string("category"),
            notes = input.string("notes"),
            cleared = input["cleared"]?.jsonPrimitive?.booleanOrNull ?: false,
            isTransfer = isTransfer,
        )
        fun resolve(d: TransactionDraft): Pair<String?, List<CellDraft>> {
            val resolved = writes.resolvePayeeForDraft(d)
            if (resolved.second.isNotEmpty()) aliases[resolved.first!!] = "payee-new"
            return resolved
        }
        return when (kind) {
            "manual" -> {
                var d = draft(false)
                if (writes.accountOffBudget(d.accountId)) d = d.copy(categoryId = null)
                val (payeeId, payeeDrafts) = resolve(d)
                payeeDrafts + writes.simpleDrafts(d, txn, payeeId)
            }
            "transfer" -> {
                val d = draft(true)
                val (payeeId, payeeDrafts) = resolve(d)
                val plan = writes.transferDrafts(d, txn, payeeId!!)
                aliases[plan.pairedTransactionId] = "txn-paired"
                payeeDrafts + plan.drafts
            }
            "import" -> {
                val row = input["transaction"]!!.jsonObject
                val accountId = input.string("account")!!
                val offBudget = writes.accountOffBudget(accountId)
                val payeeName = row.string("payee_name")
                val d = TransactionDraft(
                    accountId = accountId,
                    date = LocalDate.parse(row.string("date")!!),
                    amount = row.long("amount") ?: 0,
                    payeeName = payeeName ?: "",
                    categoryId = if (offBudget) null else row.string("category"),
                    notes = row.string("notes"),
                    // CSV and bank imports default to cleared.
                    cleared = row["cleared"]?.jsonPrimitive?.booleanOrNull ?: true,
                    importedPayee = row.string("imported_payee") ?: payeeName,
                    importedId = row.string("imported_id"),
                    sortOrder = 1_000.0,
                )
                var payeeId: String? = null
                var payeeDrafts = emptyList<CellDraft>()
                if (!payeeName.isNullOrBlank()) {
                    val resolved = writes.resolveOrCreatePayeeDrafts(null, payeeName)
                    payeeId = resolved.first
                    payeeDrafts = resolved.second
                    if (payeeDrafts.isNotEmpty()) aliases[payeeId] = "payee-new"
                }
                payeeDrafts + writes.simpleDrafts(d, txn, payeeId)
            }
            "starting-balance" -> {
                val dollars = input["balance"]!!.jsonPrimitive.doubleOrNull!!
                val offBudget = input["offBudget"]?.jsonPrimitive?.booleanOrNull ?: false
                val drafts = writes.openingBalanceDrafts(
                    txn, "acct-new", Math.round(dollars * 100), LocalDate.parse(input.string("date")!!), onBudget = !offBudget, sortOrder = 1_000.0,
                )
                drafts.firstOrNull { it.dataset == "payees" }?.let { aliases[it.row] = "payee-new" }
                drafts
            }
            else -> error("unknown kind $kind")
        }
    }

    @Test
    fun contractHasTwentyOneCases() {
        if (caseId != caseIds().first()) return
        assertEquals(21, cases.size)
    }
}
