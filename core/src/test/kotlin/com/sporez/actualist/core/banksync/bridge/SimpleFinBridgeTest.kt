package com.sporez.actualist.core.banksync.bridge

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.banksync.BankSyncStatus
import com.sporez.actualist.core.banksync.SimpleFinException
import com.sporez.actualist.core.banksync.SimpleFinServerSupport
import com.sporez.actualist.core.banksync.SimpleFinSync
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64

/** The direct SimpleFIN bridge against a fake bridge on loopback, with synthetic data only. */
class SimpleFinBridgeTest {
    private class Recorded(val method: String, val path: String, val query: String?, val authorization: String?, val bodyLength: Int)

    private val today = LocalDate.parse("2026-10-15")
    private val clock = TestClock()
    private val requests = ArrayList<Recorded>()

    private var claimCode = 200
    private var claimBody: String? = null
    private var accountsCode = 200
    private var accountsBody = ACCOUNTS
    private var redirect = false

    private val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            requests.add(
                Recorded(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization"), body.size),
            )
            if (redirect) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:1/elsewhere")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                val (code, text) = when (exchange.requestURI.path) {
                    "/claim" -> claimCode to (claimBody ?: "http://user:secret@127.0.0.1:${address.port}/bridge")
                    "/bridge/accounts" -> accountsCode to accountsBody
                    else -> 404 to ""
                }
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
            }
        }
        start()
    }
    private val port get() = http.address.port

    @After
    fun stop() {
        http.stop(0)
    }

    private fun token(url: String): String = Base64.getEncoder().encodeToString(url.toByteArray())

    private fun credentials() = SimpleFinBridgeCredentials("http://127.0.0.1:$port/bridge".toHttpUrl(), "user", "secret")

    private fun transport() = SimpleFinBridgeTransport(credentials(), allowLoopbackHttp = true, now = { 1_800_000_000L })

    private fun seconds(day: String): Long = LocalDate.parse(day).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    // region Setup token and access key

    @Test
    fun setupTokenDecodesToTheClaimUrlAcceptingUrlSafeBase64AndMissingPadding() {
        val url = SimpleFinBridgeCredentialsCodec.claimUrl(token("https://bridge.example/auth/link"))
        assertEquals("https://bridge.example/auth/link", url.toString())
        val standard = Base64.getEncoder().encodeToString("https://bridge.example/auth/>>>???".toByteArray())
        val urlSafe = standard.replace('+', '-').replace('/', '_').trimEnd('=')
        assertTrue(urlSafe.contains('-') || urlSafe.contains('_'))
        assertEquals(SimpleFinBridgeCredentialsCodec.claimUrl(standard), SimpleFinBridgeCredentialsCodec.claimUrl(urlSafe))
    }

    @Test
    fun setupTokenRefusesJunkAndNonHttps() {
        try {
            SimpleFinBridgeCredentialsCodec.claimUrl("not a token")
            fail("expected InvalidSetupToken")
        } catch (expected: SimpleFinBridgeException.InvalidSetupToken) {
            // not base64
        }
        try {
            SimpleFinBridgeCredentialsCodec.claimUrl(token("http://bridge.example/auth/link"))
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinBridgeException.InsecureUrl) {
            // https only
        }
        try {
            // Loopback http is refused too unless the test-only flag is set, as in Actualist.
            SimpleFinBridgeCredentialsCodec.claimUrl(token("http://127.0.0.1:1/claim"))
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinBridgeException.InsecureUrl) {
            // https only
        }
        assertEquals("http://127.0.0.1:1/claim", SimpleFinBridgeCredentialsCodec.claimUrl(token("http://127.0.0.1:1/claim"), allowLoopbackHttp = true).toString())
    }

    @Test
    fun accessKeysParseAtSignsAndColonsInThePassword() {
        val at = SimpleFinBridgeCredentialsCodec.decode("https://user:p@ss@bridge.example/user")
        assertEquals("user", at.username)
        assertEquals("p@ss", at.password)
        assertEquals("https://bridge.example/user", at.baseUrl.toString())
        val colon = SimpleFinBridgeCredentialsCodec.decode("https://user:a:b:c@bridge.example/user")
        assertEquals("a:b:c", colon.password)
    }

    @Test
    fun accessKeysRefuseInsecureAndIncompleteForms() {
        for (bad in listOf("http://user:pass@bridge.example/user", "ftp://user:pass@bridge.example/user")) {
            try {
                SimpleFinBridgeCredentialsCodec.decode(bad)
                fail("expected InsecureUrl")
            } catch (expected: SimpleFinBridgeException.InsecureUrl) {
                // https only
            }
        }
        for (bad in listOf("https://bridge.example/user", "https://noseparator@bridge.example/user", "https://:pw@bridge.example/user", "https://user:@bridge.example/user")) {
            try {
                SimpleFinBridgeCredentialsCodec.decode(bad)
                fail("expected InvalidAccessUrl for $bad")
            } catch (expected: SimpleFinBridgeException.InvalidAccessUrl) {
                // missing credential parts
            }
        }
    }

    @Test
    fun codecRoundTripsAndNothingPrintsTheSecret() {
        val stored = "https://user:p@ss:word@bridge.example:8443/user/v1"
        val credentials = SimpleFinBridgeCredentialsCodec.decode(stored)
        assertEquals(stored, SimpleFinBridgeCredentialsCodec.encode(credentials))
        assertEquals(credentials, SimpleFinBridgeCredentialsCodec.decode(SimpleFinBridgeCredentialsCodec.encode(credentials)))
        assertFalse(credentials.toString().contains("p@ss"))
        assertFalse(credentials.toString().contains("bridge.example"))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:p@ss:word".toByteArray()), credentials.basicAuthorization())
    }

    // endregion

    // region Claim

    @Test
    fun claimPostsAnEmptyBodyAndReturnsTheParsedCredentials() {
        val credentials = SimpleFinBridgeClaim.claim(token("http://127.0.0.1:$port/claim"), allowLoopbackHttp = true)
        assertEquals("user", credentials.username)
        assertEquals("secret", credentials.password)
        assertEquals("http://127.0.0.1:$port/bridge", credentials.baseUrl.toString().trimEnd('/'))
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/claim", request.path)
        assertEquals(0, request.bodyLength)
    }

    @Test
    fun claimOutcomesMapToTypedErrors() {
        val setup = token("http://127.0.0.1:$port/claim")
        claimCode = 403
        claimBody = ""
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected AlreadyClaimed")
        } catch (expected: SimpleFinBridgeException.AlreadyClaimed) {
            // 403
        }
        claimCode = 200
        claimBody = "Forbidden"
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected AlreadyClaimed")
        } catch (expected: SimpleFinBridgeException.AlreadyClaimed) {
            // 200 with a plain-text rejection
        }
        claimCode = 500
        claimBody = "boom"
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected UnexpectedStatus")
        } catch (error: SimpleFinBridgeException.UnexpectedStatus) {
            assertEquals(500, error.status)
        }
        claimCode = 200
        claimBody = "http://user:secret@bridge.example/user"
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinBridgeException.InsecureUrl) {
            // the access URL itself must be https
        }
        claimBody = "x".repeat(70 * 1024)
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected ResponseTooLarge")
        } catch (expected: SimpleFinBridgeException.ResponseTooLarge) {
            // 64 KiB cap
        }
        redirect = true
        try {
            SimpleFinBridgeClaim.claim(setup, allowLoopbackHttp = true)
            fail("expected RedirectRefused")
        } catch (expected: SimpleFinBridgeException.RedirectRefused) {
            // never followed
        }
    }

    @Test
    fun claimingOverPlainHttpIsRefusedBeforeAnyRequest() {
        try {
            SimpleFinBridgeClaim.claim(token("http://127.0.0.1:$port/claim"))
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinBridgeException.InsecureUrl) {
            assertTrue(requests.isEmpty())
        }
    }

    // endregion

    // region Transport

    @Test
    fun statusIsConfiguredAndNeedsNoNetwork() {
        assertEquals(SimpleFinServerSupport.CONFIGURED, transport().status("ignored"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun accountsUseBalancesOnlyWithBasicAuthAndMapIntoRemoteAccounts() {
        val accounts = transport().accounts("ignored")!!
        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/bridge/accounts", request.path)
        assertEquals("balances-only=1", request.query)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray()), request.authorization)
        assertEquals(listOf("remote-1", "remote-2"), accounts.map { it.accountId })
        assertEquals("100.00", accounts[0].balance)
        assertEquals("5.5", accounts[1].balance)
        assertEquals("Synthetic Bank", accounts[0].orgName)
        assertEquals("bank.example", accounts[0].orgDomain)
        assertEquals("org-1", accounts[0].orgId)
        assertEquals("USD", accounts[0].currency)
        // No domain: the org id stands in, as in Actualist.
        assertEquals("org-2", accounts[1].orgDomain)
    }

    @Test
    fun transactionsSendPendingAndTheOldestStartDateAndMapRows() {
        val start1 = "2026-07-18"
        val start2 = "2026-10-11"
        accountsBody = """{"errors":["Connection to Synthetic Bank may need attention."],"accounts":[
            {"id":"remote-1","name":"Checking","balance":"100.00","currency":"USD","org":{"name":"Other Bank","domain":"other.example","id":"org-9"},
             "transactions":[
               {"id":"sf-1","posted":$NOON_OCT_10,"amount":"-15.00","description":"MARKET 123","payee":"Synthetic Market","extra":{"notes":"memo #1"}},
               {"id":"sf-old","posted":$NOON_JAN_1,"amount":"-1.00","description":"too old"}],
             "pending":[
               {"id":"sf-1","posted":$NOON_OCT_10,"amount":"-15.00","description":"duplicate of the booked row"},
               {"id":"sf-2","posted":0,"transacted_at":$NOON_OCT_12,"amount":-7.5,"description":"Corner Cafe","pending":true}]},
            {"id":"remote-2","name":"Savings","balance":"5.50","currency":"USD","org":{"name":"Synthetic Bank","id":"org-1"},
             "transactions":[
               {"posted":$NOON_OCT_1,"amount":"-1.00","description":"before its start day"},
               {"posted":$NOON_OCT_12,"amount":"-2.00","description":"no id"},
               {"id":"sf-3","posted":$NOON_OCT_12,"amount":"-3.00","description":"kept"}]}
        ]}"""
        val response = transport().transactions("ignored", listOf("remote-1", "remote-2", "gone"), listOf(start1, start2, start1))!!
        val request = requests.single()
        assertEquals("/bridge/accounts", request.path)
        assertEquals("pending=1&start-date=${seconds(start1)}&end-date=1800000000", request.query)

        val first = response.downloads.getValue("remote-1")
        // sf-1 posted before nothing filters it; the pending row duplicates nothing; sf-old predates the start day.
        assertEquals(listOf("sf-1", "sf-2"), first.transactions.map { it.id })
        val posted = first.transactions[0]
        assertEquals(seconds("2026-10-10"), posted.dateUnixSeconds)
        assertEquals("-15.00", posted.amount)
        assertEquals("USD", posted.currency)
        assertEquals("Synthetic Market", posted.payeeName)
        assertEquals("memo #1", posted.notes)
        assertNull(posted.booked)
        val pending = first.transactions[1]
        assertEquals(false, pending.booked)
        assertEquals(seconds("2026-10-12"), pending.dateUnixSeconds)
        assertEquals("Corner Cafe", pending.payeeName)
        assertEquals("-7.5", pending.amount)
        assertEquals("100.00", first.currentBalance!!.amount)
        assertEquals("USD", first.currentBalance!!.currency)
        assertNull(first.errorCode)

        // remote-2 starts 2026-10-11: its older row is dropped; a row with no id is kept.
        val second = response.downloads.getValue("remote-2")
        assertEquals(listOf(null, "sf-3"), second.transactions.map { it.id })
        assertEquals("ACCOUNT_NEEDS_ATTENTION", second.errorCode)

        assertEquals("ACCOUNT_MISSING", response.downloads.getValue("gone").errorCode)
    }

    @Test
    fun bridgeErrorListsAndMissingTransactionsBecomeAccountErrors() {
        accountsBody = """{"errors":[],"errlist":[{"code":"auth.expired","msg":"x","account_id":"remote-1"}],"accounts":[
            {"id":"remote-1","name":"Checking","currency":"USD","balance":"1","transactions":[]},
            {"id":"remote-2","name":"Savings","currency":"USD","balance":"2"}]}"""
        val response = transport().transactions("t", listOf("remote-1", "remote-2"), listOf("2026-07-18", "2026-07-18"))!!
        assertEquals("auth.expired", response.downloads.getValue("remote-1").errorCode)
        assertEquals("ACCOUNT_MISSING", response.downloads.getValue("remote-2").errorCode)
    }

    @Test
    fun httpStatusesRedirectsAndBadPayloadsMapToExistingErrors() {
        accountsCode = 403
        try {
            transport().accounts("t")
            fail("expected AuthenticationFailed")
        } catch (expected: SimpleFinException.AuthenticationFailed) {
            // key revoked
        }
        accountsCode = 402
        try {
            transport().accounts("t")
            fail("expected HttpStatus")
        } catch (error: SimpleFinException.HttpStatus) {
            assertEquals(402, error.status)
        }
        accountsCode = 200
        accountsBody = "not json"
        try {
            transport().accounts("t")
            fail("expected InvalidResponse")
        } catch (expected: SimpleFinException.InvalidResponse) {
            // undecodable
        }
        accountsBody = ACCOUNTS
        try {
            SimpleFinBridgeTransport(credentials(), allowLoopbackHttp = true, maximumResponseBytes = 100).accounts("t")
            fail("expected ResponseTooLarge")
        } catch (expected: SimpleFinException.ResponseTooLarge) {
            // capped
        }
        redirect = true
        try {
            transport().accounts("t")
            fail("expected RedirectRefused")
        } catch (expected: SimpleFinException.RedirectRefused) {
            // never followed
        }
    }

    @Test
    fun insecureAndUnreachableBridgesAreRefused() {
        try {
            SimpleFinBridgeTransport(credentials())
            fail("expected InsecureUrl")
        } catch (expected: SimpleFinException.InsecureUrl) {
            // plain http needs the loopback flag
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        val dead = SimpleFinBridgeCredentials("http://127.0.0.1:$closedPort/bridge".toHttpUrl(), "u", "p")
        try {
            SimpleFinBridgeTransport(dead, allowLoopbackHttp = true).accounts("t")
            fail("expected Unreachable")
        } catch (expected: SimpleFinException.Unreachable) {
            // nothing is listening
        }
    }

    // endregion

    // region Full sync

    @Test
    fun simpleFinSyncRunsOverTheBridge() {
        TestDatabases.starter(clock = clock).use { database ->
            database.sql.execute("INSERT INTO accounts (id, name, offbudget, closed, tombstone) VALUES ('acc', 'Checking', 0, 0, 0)")
            var counter = 0
            val sync = SimpleFinSync(database, transport(), "ignored", clock) { "gen-${++counter}-${System.nanoTime()}" }
            assertEquals(SimpleFinServerSupport.CONFIGURED, sync.serverSupport())
            sync.link("acc", sync.remoteAccounts().first())
            accountsBody = SYNC_ACCOUNTS

            val first = sync.sync("acc", today)
            val result = first.accounts.single()
            assertEquals(2, result.addedCount)
            assertTrue(result.openingBalanceAdded)
            assertEquals(BankSyncStatus.OK, result.status)
            // Current balance 100.00 less the two downloads (-15.00, -7.00) leaves 122.00 on the earliest day.
            val amounts = database.sql.query("SELECT amount, date FROM transactions WHERE acct = 'acc' ORDER BY date, amount").map { it.long("amount") to it.long("date") }
            assertEquals(listOf(-1_500L to 20261010L, 12_200L to 20261010L, -700L to 20261012L), amounts)

            val again = sync.sync("acc", today)
            assertEquals(0, again.accounts.single().addedCount)
            assertEquals(3L, database.sql.queryLong("SELECT COUNT(*) FROM transactions"))
            val last = requests.last()
            assertTrue(last.query!!.startsWith("pending=1&start-date="))
            assertEquals("Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray()), last.authorization)
        }
    }

    // endregion

    private companion object {
        val ACCOUNTS = """{"errors":[],"accounts":[
            {"id":"remote-1","name":"Checking","balance":100.00,"currency":"USD","balance-date":1,"org":{"name":"Synthetic Bank","domain":"bank.example","id":"org-1"}},
            {"id":"remote-2","name":"Savings","balance":5.5,"currency":"USD","org":{"name":"Synthetic Bank","id":"org-2"}}
        ]}"""

        const val NOON_OCT_10 = 1791633600L
        const val NOON_OCT_12 = 1791806400L
        const val NOON_OCT_1 = 1790856000L
        const val NOON_JAN_1 = 1767268800L

        val SYNC_ACCOUNTS = """{"errors":[],"accounts":[
            {"id":"remote-1","name":"Checking","balance":"100.00","currency":"USD","org":{"name":"Synthetic Bank","domain":"bank.example","id":"org-1"},
             "transactions":[
               {"id":"sf-1","posted":$NOON_OCT_10,"amount":"-15.00","description":"MARKET 123","payee":"Synthetic Market","extra":{"notes":"memo #1"}},
               {"id":"sf-old","posted":1700000000,"amount":"-1.00","description":"too old"}],
             "pending":[
               {"id":"sf-2","posted":0,"transacted_at":$NOON_OCT_12,"amount":"-7.00","description":"Corner Cafe","pending":true}]}
        ]}"""
    }
}
