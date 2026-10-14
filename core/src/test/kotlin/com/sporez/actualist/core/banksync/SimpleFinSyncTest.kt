package com.sporez.actualist.core.banksync

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalDate

/** Bank sync against a fake Actual server on loopback, with synthetic SimpleFIN-shaped JSON only. */
class SimpleFinSyncTest {
    private class Recorded(val path: String, val token: String?, val accept: String?, val body: String)

    private val today = LocalDate.parse("2026-10-15")
    private val clock = TestClock()

    private val requests = ArrayList<Recorded>()
    private var statusCode = 200
    private var statusBody = """{"status":"ok","data":{"configured":true}}"""
    private var accountsBody = """
        {"status":"ok","data":{"accounts":[
          {"id":"remote-1","name":"Checking","balance":100.00,"currency":"USD","org":{"name":"Synthetic Bank","domain":"bank.example","id":"org-1"}},
          {"id":"remote-2","name":"Savings","balance":"5.50","currency":"USD","org":{"name":"Synthetic Bank","domain":"bank.example","id":"org-1"}}
        ]}}
    """.trimIndent()
    private var transactionsBody = DOWNLOAD
    private var transactionsCode = 200
    private var respondWithRedirect = false
    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = String(exchange.requestBody.readBytes(), Charsets.UTF_8)
            requests.add(Recorded(exchange.requestURI.path, exchange.requestHeaders.getFirst("X-ACTUAL-TOKEN"), exchange.requestHeaders.getFirst("Accept"), body))
            if (respondWithRedirect) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                val (code, text) = when (exchange.requestURI.path) {
                    "/simplefin/status" -> statusCode to statusBody
                    "/simplefin/accounts" -> 200 to accountsBody
                    "/simplefin/transactions" -> transactionsCode to transactionsBody
                    else -> 404 to ""
                }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
        }
        start()
    }
    private val client = SimpleFinServerClient("http://127.0.0.1:${http.address.port}")

    @After
    fun stop() {
        http.stop(0)
    }

    private fun database(): BudgetDatabase {
        val database = TestDatabases.starter(clock = clock)
        database.sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES ('acc', 'Checking', 0, 0, 0)")
        database.sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES ('acc-2', 'Savings', 0, 0, 0)")
        database.sql.execute("INSERT INTO payees (id, name, tombstone) VALUES ('pay-store', 'Synthetic Market', 0)")
        return database
    }

    private fun sync(database: BudgetDatabase): SimpleFinSync {
        var counter = 0
        return SimpleFinSync(database, client, "session-token", clock) { "gen-${++counter}-${System.nanoTime()}" }
    }

    private fun linked(database: BudgetDatabase): SimpleFinSync {
        val sync = sync(database)
        sync.link("acc", sync.remoteAccounts().first())
        return sync
    }

    private fun cell(database: BudgetDatabase, column: String) = database.sql.queryString("SELECT $column FROM accounts WHERE id = 'acc'")

    // region Transport

    @Test
    fun requestsAreJsonPostsWithTheSessionTokenAndPositionalArrays() {
        database().use { database ->
            val sync = linked(database)
            sync.sync("acc", today)
            val status = requests[0]
            assertEquals("/simplefin/status", status.path)
            assertEquals("session-token", status.token)
            assertEquals("application/json", status.accept)
            assertEquals("{}", status.body)
            val transactions = requests.first { it.path == "/simplefin/transactions" }
            assertEquals("session-token", transactions.token)
            val body = Json.parseToJsonElement(transactions.body).jsonObject
            assertEquals(listOf("remote-1"), body["accountId"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf(today.minusDays(89).toString()), body["startDate"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun remoteAccountsDecodeTheRawServerShape() {
        database().use { database ->
            val accounts = sync(database).remoteAccounts()
            assertEquals(listOf("remote-1", "remote-2"), accounts.map { it.accountId })
            assertEquals("100.00", accounts[0].balance)
            assertEquals("5.50", accounts[1].balance)
            assertEquals("Synthetic Bank", accounts[0].orgName)
            assertEquals("bank.example", accounts[0].orgDomain)
            assertEquals("org-1", accounts[0].orgId)
            assertEquals("USD", accounts[0].currency)
        }
    }

    @Test
    fun aServerWithoutTheRoutesOrATokenIsCannotSyncNotAnError() {
        database().use { database ->
            val sync = sync(database)
            statusCode = 404
            assertEquals(SimpleFinServerSupport.UNSUPPORTED, sync.serverSupport())
            try {
                sync.remoteAccounts()
                fail("expected NotConfigured")
            } catch (expected: SimpleFinException.NotConfigured) {
                // 404 means the server does not host SimpleFIN
            }
            statusCode = 200
            statusBody = """{"status":"ok","data":{"configured":false}}"""
            assertEquals(SimpleFinServerSupport.NOT_CONFIGURED, sync.serverSupport())
            statusBody = """{"configured":true}"""
            assertEquals(SimpleFinServerSupport.CONFIGURED, sync.serverSupport())
            statusBody = """{"status":"ok","data":{"configured":false}}"""
            sync.link("acc", SimpleFinRemoteAccount("remote-1", "Checking", orgDomain = "bank.example"))
            try {
                sync.sync("acc", today)
                fail("expected NotConfigured")
            } catch (expected: SimpleFinException.NotConfigured) {
                assertTrue(requests.none { it.path == "/simplefin/transactions" })
            }
        }
    }

    @Test
    fun errorResponsesSurfaceAsTypedErrorsAndRecordAFailedStatus() {
        database().use { database ->
            val sync = linked(database)
            transactionsCode = 500
            transactionsBody = ""
            try {
                sync.sync("acc", today)
                fail("expected HttpStatus")
            } catch (error: SimpleFinException.HttpStatus) {
                assertEquals(500, error.status)
            }
            assertEquals("failed", cell(database, "bank_sync_status"))
            assertNull(cell(database, "last_sync"))

            transactionsCode = 401
            try {
                sync.sync("acc", today)
                fail("expected AuthenticationFailed")
            } catch (expected: SimpleFinException.AuthenticationFailed) {
                // the session token was refused
            }

            transactionsCode = 200
            transactionsBody = """{"status":"error","reason":"unauthorized"}"""
            try {
                sync.sync("acc", today)
                fail("expected AuthenticationFailed")
            } catch (expected: SimpleFinException.AuthenticationFailed) {
                // the envelope error rides on a 200
            }

            transactionsBody = """{"status":"error","reason":"internal-error","details":"boom"}"""
            try {
                sync.sync("acc", today)
                fail("expected ServerRejected")
            } catch (error: SimpleFinException.ServerRejected) {
                assertEquals("internal-error", error.reason)
            }

            transactionsBody = "not json"
            try {
                sync.sync("acc", today)
                fail("expected InvalidResponse")
            } catch (expected: SimpleFinException.InvalidResponse) {
                // undecodable body
            }
            assertEquals(0L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
        }
    }

    @Test
    fun redirectsAndInsecureOrUnreachableServersAreRefused() {
        database().use { database ->
            respondWithRedirect = true
            try {
                sync(database).serverSupport()
                fail("expected RedirectRefused")
            } catch (expected: SimpleFinException.RedirectRefused) {
                // redirects are never followed
            }
        }
        try {
            SimpleFinServerClient("http://example.com")
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinException.InsecureUrl) {
            // plain http is loopback only
        }
        try {
            SimpleFinServerClient("not a url")
            fail("expected InvalidUrl")
        } catch (expected: SimpleFinException.InvalidUrl) {
            // unparseable
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        try {
            SimpleFinServerClient("http://127.0.0.1:$closedPort").status("t")
            fail("expected Unreachable")
        } catch (expected: SimpleFinException.Unreachable) {
            // nothing is listening
        }
    }

    // endregion

    // region Link

    @Test
    fun linkWritesTheCellsActualistWritesAndSharesTheBankRow() {
        database().use { database ->
            val sync = sync(database)
            val remotes = sync.remoteAccounts()
            sync.link("acc", remotes[0])
            sync.link("acc-2", remotes[1])
            assertEquals("remote-1", cell(database, "account_id"))
            assertEquals("simpleFin", cell(database, "account_sync_source"))
            val bank = cell(database, "bank")
            assertEquals(bank, database.sql.queryString("SELECT bank FROM accounts WHERE id = 'acc-2'"))
            assertEquals(1L, database.sql.queryLong("SELECT COUNT(*) FROM banks"))
            assertEquals("bank.example", database.sql.queryString("SELECT bank_id FROM banks WHERE id = ?", bank))
            assertEquals("Synthetic Bank", database.sql.queryString("SELECT name FROM banks WHERE id = ?", bank))
            assertEquals(listOf("acc", "acc-2"), sync.linkedAccounts().map { it.id })
            assertTrue(sync.linkedAccounts().all { it.isSimpleFin })

            // The same bank id under another name is a distinct bank row.
            sync.link("acc-2", remotes[1].copy(orgName = "Other Name"))
            assertEquals(2L, database.sql.queryLong("SELECT COUNT(*) FROM banks"))

            try {
                sync.link("acc", SimpleFinRemoteAccount("x", "X"))
                fail("expected MissingBankId")
            } catch (expected: SimpleFinException.MissingBankId) {
                // no domain or org id to key the bank on
            }

            sync.unlink("acc")
            assertNull(cell(database, "account_id"))
            assertNull(cell(database, "bank"))
            assertNull(cell(database, "account_sync_source"))
            try {
                sync.sync("acc", today)
                fail("expected NotLinked")
            } catch (expected: SimpleFinException.NotLinked) {
                // unlinked accounts do not sync
            }
        }
    }

    // endregion

    // region Sync

    @Test
    fun theFirstSyncAddsTransactionsAndAnOpeningBalanceAndASecondDoesNotDuplicate() {
        database().use { database ->
            val sync = linked(database)
            val first = sync.sync("acc", today)
            val result = first.accounts.single()
            assertEquals(2, result.addedCount)
            assertEquals(0, result.updatedCount)
            assertTrue(result.openingBalanceAdded)
            assertEquals(BankSyncStatus.OK, result.status)
            assertEquals("Added 2 transactions · Added 1 opening balance", first.text())

            // Current balance 100.00 less the two downloads (-15.00, -7.00) leaves 122.00 on the earliest day.
            val amounts = database.sql.query("SELECT amount, date FROM transactions WHERE acct = 'acc' ORDER BY date, amount").map { it.long("amount") to it.long("date") }
            assertEquals(listOf(-1_500L to 20261010L, 12_200L to 20261010L, -700L to 20261012L), amounts)
            assertEquals(10_000L, database.sql.queryLong("SELECT balance_current FROM accounts WHERE id = 'acc'"))
            assertEquals("ok", cell(database, "bank_sync_status"))
            assertEquals(clock.nowMillis().toString(), cell(database, "last_sync"))

            // Mapped fields: provider id, payee, hash-escaped notes, and pending staying uncleared.
            val market = database.sql.queryOne("SELECT financial_id, notes, cleared, imported_description, description FROM transactions WHERE financial_id = 'sf-1'")!!
            assertEquals("memo ##1", market.string("notes"))
            assertEquals(1L, market.long("cleared"))
            assertEquals("Synthetic Market", market.string("imported_description"))
            assertEquals("pay-store", market.string("description"))
            assertEquals(0L, database.sql.queryLong("SELECT cleared FROM transactions WHERE financial_id = 'sf-2'"))
            assertEquals("Corner Cafe", database.sql.queryString("SELECT name FROM payees WHERE id = (SELECT description FROM transactions WHERE financial_id = 'sf-2')"))

            val second = sync.sync("acc", today).accounts.single()
            assertEquals(0, second.addedCount)
            assertEquals(0, second.updatedCount)
            assertEquals(false, second.openingBalanceAdded)
            assertEquals(3L, database.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE acct = 'acc'"))
            // The lookback now starts at the oldest local row, not the 90 day default.
            val lastBody = Json.parseToJsonElement(requests.last { it.path == "/simplefin/transactions" }.body).jsonObject
            assertEquals(listOf("2026-10-10"), lastBody["startDate"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun anAccountDownloadErrorRecordsItsStatusAndKeepsBalanceAndLastSync() {
        database().use { database ->
            val sync = linked(database)
            sync.sync("acc", today)
            val balance = cell(database, "balance_current")
            val lastSync = cell(database, "last_sync")
            clock.advance(60_000)
            transactionsBody = """{"status":"ok","data":{"remote-1":{"error_type":"ITEM","error_code":"RATE_LIMIT_EXCEEDED"}}}"""
            val summary = sync.sync("acc", today)
            val result = summary.accounts.single()
            assertEquals(BankSyncStatus.RATE_LIMIT_EXCEEDED, result.status)
            assertEquals("RATE_LIMIT_EXCEEDED", result.errorCode)
            assertEquals(1, summary.errors.size)
            assertEquals("No accounts synced. 1 skipped.", summary.text())
            assertEquals("rate-limit-exceeded", cell(database, "bank_sync_status"))
            assertEquals(balance, cell(database, "balance_current"))
            assertEquals(lastSync, cell(database, "last_sync"))
            assertEquals(3L, database.sql.queryLong("SELECT COUNT(*) FROM transactions WHERE acct = 'acc'"))

            // An account absent from the response is ACCOUNT_MISSING.
            transactionsBody = """{"status":"ok","data":{}}"""
            assertEquals(BankSyncStatus.ACCOUNT_MISSING, sync.sync("acc", today).accounts.single().status)
            assertEquals("account-missing", cell(database, "bank_sync_status"))

            // A whole-request error applies to accounts with no error of their own.
            transactionsBody = """{"status":"ok","data":{"error_type":"AUTH","error_code":"INVALID_ACCESS_TOKEN"}}"""
            assertEquals(BankSyncStatus.REAUTH_REQUIRED, sync.sync("acc", today).accounts.single().status)

            // Batch errors keyed by account id fill in for an account with no entry.
            transactionsBody = """{"status":"ok","data":{"errors":{"remote-1":[{"error_type":"X","error_code":"TIMED_OUT"}]}}}"""
            assertEquals(BankSyncStatus.TIMED_OUT, sync.sync("acc", today).accounts.single().status)
        }
    }

    @Test
    fun unreadableRowsAbortTheWholeRunAndSaveNothing() {
        database().use { database ->
            val sync = linked(database)
            transactionsBody = DOWNLOAD.replace("-15.00", "fifteen")
            try {
                sync.sync("acc", today)
                fail("expected UnresolvedProblems")
            } catch (error: SimpleFinException.UnresolvedProblems) {
                assertEquals(listOf("Unreadable amount"), error.problems.map { it.message })
            }
            assertEquals(0L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
            assertNull(cell(database, "last_sync"))
            assertNull(cell(database, "bank_sync_status"))

            // A budget without a currency accepts any code of the same scale (as Actualist's
            // BudgetCurrency.accepts); a zero-decimal currency is a mismatch.
            transactionsBody = DOWNLOAD.replace("\"currency\":\"USD\"", "\"currency\":\"JPY\"")
            try {
                sync.sync("acc", today)
                fail("expected UnresolvedProblems")
            } catch (error: SimpleFinException.UnresolvedProblems) {
                assertEquals("Currency mismatch (JPY bank transaction, none budget)", error.problems.first().message)
            }
            assertEquals(0L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
        }
    }

    @Test
    fun syncAllUsesOneBatchedRequestAndSkipsClosedAndUnlinkedAccounts() {
        database().use { database ->
            val sync = sync(database)
            val remotes = sync.remoteAccounts()
            sync.link("acc", remotes[0])
            sync.link("acc-2", remotes[1])
            database.sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES ('acc-3', 'Old', 0, 1, 0)")
            transactionsBody = """
                {"status":"ok","data":{
                  "remote-1":{"transactions":{"all":[{"transactionId":"sf-1","date":"2026-10-10","transactionAmount":{"amount":"-15.00","currency":"USD"},"payeeName":"Synthetic Market","booked":true}]},"balances":[{"balanceAmount":{"amount":"85.00","currency":"USD"}}]},
                  "remote-2":{"transactions":{"all":[]},"startingBalance":550}
                }}
            """.trimIndent()
            val summary = sync.syncAll(today)
            assertEquals(2, summary.accounts.size)
            assertEquals(1, requests.count { it.path == "/simplefin/transactions" })
            val body = Json.parseToJsonElement(requests.first { it.path == "/simplefin/transactions" }.body).jsonObject
            assertEquals(listOf("remote-1", "remote-2"), body["accountId"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(2, (body["startDate"] as JsonArray).size)
            // The empty account takes its opening balance from the account list (5.50), not startingBalance.
            assertEquals(550L, database.sql.queryLong("SELECT balance_current FROM accounts WHERE id = 'acc-2'"))
            assertEquals(550L, database.sql.queryLong("SELECT amount FROM transactions WHERE acct = 'acc-2'"))
            assertEquals(2, summary.openingBalancesAdded)
            assertEquals("Added 1 transaction · Added 2 opening balances", summary.text())
        }
    }

    @Test
    fun theTransactionsRequestJsonKeepsArraysForASingleAccount() {
        val body = JsonObject(
            mapOf(
                "accountId" to JsonArray(listOf(JsonPrimitive("a"))),
                "startDate" to JsonArray(listOf(JsonPrimitive("2026-07-18"))),
            ),
        ).toString()
        assertEquals("""{"accountId":["a"],"startDate":["2026-07-18"]}""", body)
    }

    // endregion

    private companion object {
        const val DOWNLOAD = """{"status":"ok","data":{"remote-1":{"transactions":{"all":[
            {"transactionId":"sf-1","date":"2026-10-10","transactionAmount":{"amount":"-15.00","currency":"USD"},"payeeName":"Synthetic Market","notes":"memo #1","booked":true},
            {"transactionId":"sf-2","date":"2026-10-12","transactionAmount":{"amount":"-7.00","currency":"USD"},"payeeName":"Corner Cafe","booked":false}
          ]},"balances":[{"balanceAmount":{"amount":"100.00","currency":"USD"}}],"startingBalance":10000}}}"""
    }
}
