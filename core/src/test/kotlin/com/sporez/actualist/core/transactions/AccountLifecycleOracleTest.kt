package com.sporez.actualist.core.transactions

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Replays the 16 AccountLifecycle oracle cases Actualist replays (Actual v26.10.0's
 * `account-update`, `account-reopen`, `account-close` and SimpleFIN unlink) through
 * [AccountLifecycle], comparing each operation's CRDT cells as a multiset, as Actualist's
 * `AccountLifecycleOracleFixtureTests` does.
 *
 * Not compared: the `undoMessages`/`afterUndo` observations (this port has no action log)
 * and the schedule case's automatic-posting service messages (the posting service is not
 * driven here). Excused: OP-22, rename trims and skips unchanged names (Actualist, intended).
 * Not replayed, as in Actualist: forced delete and the GoCardless remote-removal cases.
 */
@RunWith(Parameterized::class)
class AccountLifecycleOracleTest(private val caseId: String) {
    companion object {
        private val fixture: JsonObject by lazy {
            Json.parseToJsonElement(
                AccountLifecycleOracleTest::class.java.getResourceAsStream("/oracle/AccountLifecycle/account-lifecycle-oracle.json")!!
                    .bufferedReader().use { it.readText() },
            ).jsonObject
        }
        private val cases by lazy { fixture["cases"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject } }

        val replayed = listOf(
            "rename-open-history", "rename-closed", "rename-handler-input-boundary", "reopen-repeat-history",
            "close-empty-history", "close-zero", "close-positive-on-to-on-history", "close-negative-on-to-on",
            "close-on-to-off-hidden-category", "close-off-to-on", "close-off-to-off", "close-self-transfer-refusal",
            "close-split-balance", "unlink-simplefin", "close-unlink-undo-boundary", "schedule-close-reopen-eligibility",
        )
        val notReplayed = setOf(
            "forced-delete-simple", "forced-delete-split-transfer-graph", "unlink-gocardless-last-reference",
            "unlink-gocardless-shared-bank", "unlink-gocardless-without-token", "unlink-gocardless-remote-failure",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun caseIds(): List<String> = replayed

        /** 2026-09-27T12:00:00Z: the fixture's day; its closing transfers carry this `sort_order`. */
        const val NOW = 1_790_510_400_000L
        val today: LocalDate = LocalDate.of(2026, 9, 27)
    }

    private fun canonical(value: JsonElement?): String = when {
        value == null || value is JsonNull -> "0:"
        value.jsonPrimitive.isString -> "S:" + value.jsonPrimitive.content
        else -> "N:" + (value.jsonPrimitive.longOrNull?.toString() ?: value.jsonPrimitive.content)
    }

    private fun expected(observed: JsonObject, key: String): List<String> = (observed[key] as? JsonArray).orEmpty().map {
        val m = it.jsonObject
        "${m["dataset"]!!.jsonPrimitive.content}/${m["row"]!!.jsonPrimitive.content}/${m["column"]!!.jsonPrimitive.content}=${canonical(m["value"])}"
    }

    private fun cells(drafts: List<CellDraft>) = drafts.map { "${it.dataset}/${it.row}/${it.column}=${it.value.serialized}" }

    private fun BudgetDatabase.account(id: String, name: String = "Synthetic $id", offBudget: Boolean = false, closed: Boolean = false, sort: Int = 16384) {
        sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone, sort_order) VALUES (?, ?, ?, ?, 0, ?)", id, name, offBudget, closed, sort)
        sql.execute("INSERT INTO payees (id, name, transfer_acct, tombstone) VALUES (?, '', ?, 0)", "transfer-$id", id)
    }

    private fun BudgetDatabase.row(id: String, account: String, amount: Long, isParent: Boolean = false, parent: String? = null) {
        sql.execute(
            "INSERT INTO transactions (id, acct, amount, date, isParent, isChild, parent_id, tombstone, cleared, sort_order) VALUES (?, ?, ?, 20260901, ?, ?, ?, 0, 1, 0)",
            id, account, amount, isParent, parent != null, parent,
        )
    }

    /** Commits like production and returns the drafts, or an empty list when refused. */
    private fun run(database: BudgetDatabase, plan: () -> List<CellDraft>): List<String> = try {
        cells(database.commitLocal { plan().let { it to it } }.outcome)
    } catch (e: LocalFirstException) {
        emptyList()
    }

    @Test
    fun replaysCase() {
        val entry = cases.getValue(caseId)
        val observed = entry["observed"]!!.jsonObject
        val input = entry["input"]!!.jsonObject
        var ids = 1
        TestDatabases.starter(clock = TestClock(NOW)).use { db ->
            val lifecycle = AccountLifecycle(db, TestClock(NOW)) { "id${++ids}" }
            val actual = LinkedHashMap<String, List<String>>()
            fun balanceCase(sourceOff: Boolean, destOff: Boolean, balance: Long, category: Pair<String, Boolean>? = null) {
                db.account("source", offBudget = sourceOff)
                db.account("destination", offBudget = destOff, sort = 32768)
                db.row("balance-row", "source", balance)
                category?.let { (id, hidden) ->
                    db.sql.execute("INSERT INTO category_groups (id, name, is_income, hidden, tombstone, sort_order) VALUES ('g', 'Synthetic', 0, 0, 0, 1)")
                    db.sql.execute("INSERT INTO categories (id, name, is_income, cat_group, hidden, tombstone, sort_order) VALUES (?, 'Synthetic', 0, 'g', ?, 0, 1)", id, hidden)
                }
                actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", "destination", category?.first, today) }
            }
            when (caseId) {
                "rename-open-history", "rename-closed" -> {
                    val account = input["account"]!!.jsonObject
                    db.account("source", account["name"]!!.jsonPrimitive.content, closed = caseId == "rename-closed")
                    actual["operationMessages"] = run(db) { lifecycle.renameDrafts("source", input["name"]!!.jsonPrimitive.content) }
                }
                "rename-handler-input-boundary" -> {
                    db.account("source", "Original")
                    db.account("peer", "Duplicate", sort = 32768)
                    actual["operationMessages"] = input["submittedNames"]!!.jsonArray.flatMap { name ->
                        run(db) { lifecycle.renameDrafts("source", name.jsonPrimitive.content) }
                    }
                }
                "reopen-repeat-history" -> {
                    db.account("source", "Closed", closed = true)
                    actual["firstMessages"] = run(db) { lifecycle.reopenDrafts("source") }
                    // Stand-in for History undo, which this port does not have.
                    db.sql.execute("UPDATE accounts SET closed = 1 WHERE id = 'source'")
                    actual["repeatMessages"] = (1..2).flatMap { run(db) { lifecycle.reopenDrafts("source") } }
                }
                "close-empty-history" -> {
                    db.account("source", "Empty")
                    actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", null, null, today) }
                }
                "close-zero" -> {
                    db.account("source")
                    db.row("zero-row", "source", 0)
                    actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", null, null, today) }
                }
                "close-positive-on-to-on-history" -> balanceCase(false, false, 4_250)
                "close-negative-on-to-on" -> balanceCase(false, false, -2_300)
                "close-on-to-off-hidden-category" -> balanceCase(false, true, 3_100, "hidden-category" to true)
                "close-off-to-on" -> balanceCase(true, false, 1_700)
                "close-off-to-off" -> balanceCase(true, true, 900)
                "close-self-transfer-refusal" -> {
                    db.account("source", "Self")
                    db.row("row", "source", 500)
                    actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", "source", null, today) }
                    assertTrue(lifecycle.review("source", "source", null).blockers.contains(AccountCloseBlocker.DESTINATION_IS_SOURCE))
                }
                "close-split-balance" -> {
                    db.account("source")
                    db.account("destination", sort = 32768)
                    db.row("split-parent", "source", 1_000, isParent = true)
                    db.row("split-child-a", "source", 400, parent = "split-parent")
                    db.row("split-child-b", "source", 600, parent = "split-parent")
                    db.row("ordinary-row", "source", 300)
                    actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", "destination", null, today) }
                }
                "unlink-simplefin", "close-unlink-undo-boundary" -> {
                    db.account("source", "SimpleFIN")
                    db.sql.execute(
                        "UPDATE accounts SET account_id = 'remote', bank = 'bank-1', balance_current = 1, balance_available = 1, balance_limit = 0, " +
                            "account_sync_source = 'simpleFin', bank_sync_status = 'ok', last_sync = 'synthetic-last-sync' WHERE id = 'source'",
                    )
                    if (caseId == "unlink-simplefin") {
                        actual["operationMessages"] = run(db) { lifecycle.unlinkDrafts("source") }
                        assertEquals("synthetic-last-sync", db.sql.queryString("SELECT last_sync FROM accounts WHERE id = 'source'"))
                    } else {
                        db.row("zero-row", "source", 0)
                        actual["operationMessages"] = run(db) { lifecycle.closeDrafts("source", null, null, today) }
                    }
                }
                "schedule-close-reopen-eligibility" -> {
                    db.account("source")
                    db.row("zero-row", "source", 0)
                    actual["closeMessages"] = run(db) { lifecycle.closeDrafts("source", null, null, today) }
                    actual["reopenMessages"] = run(db) { lifecycle.reopenDrafts("source") }
                }
                else -> error("unhandled $caseId")
            }
            assertTrue("$caseId replayed nothing", actual.isNotEmpty())
            val problems = mutableListOf<String>()
            for ((key, cellsActual) in actual) {
                val remaining = cellsActual.toMutableList()
                val missing = expected(observed, key).filter { !remaining.remove(it) }
                val excused = if (caseId == "rename-handler-input-boundary") {
                    // OP-22: Actualist trims and skips unchanged names; only "Original" variants may be missing.
                    missing.filter { it.substringAfter("=S:").trim() == "Original" }
                } else {
                    emptyList()
                }
                (missing - excused.toSet()).forEach { problems += "$key missing $it" }
                remaining.forEach { problems += "$key extra $it" }
                if (caseId == "rename-handler-input-boundary") assertEquals("OP-22 no longer shows; update the excuse", 2, excused.size)
            }
            assertTrue("$caseId\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    @Test
    fun everyOracleCaseIsReplayedOrListed() {
        if (caseId != replayed.first()) return
        assertEquals(cases.keys, (replayed + notReplayed).toSet())
        assertEquals(22, cases.size)
    }
}
