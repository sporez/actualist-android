package com.sporez.actualist.core.server

import com.sporez.actualist.core.LocalFirstException
import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.ActualCrypto
import com.sporez.actualist.core.sync.EncryptionContext
import com.sporez.actualist.core.sync.FakeActualServer
import com.sporez.actualist.core.sync.SyncEngine
import com.sporez.actualist.core.sync.SyncValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The HTTP client against a local mock Actual server (JDK `HttpServer` on loopback):
 * login, file listing, key retrieval, encrypted download and import, then sync through
 * the real `/sync/sync` endpoint into [FakeActualServer]. Synthetic data only.
 */
class ServerIntegrationTest {
    private lateinit var http: HttpServer
    private lateinit var baseUrl: String
    private val sync = FakeActualServer(fileId = "remote-file", groupId = "remote-group")
    private val clock = TestClock()
    private val password = "synthetic-password-1"
    private val salt = "c3ludGhldGljLXNhbHQ="
    private val key = EncryptionContext("key-1", ActualCrypto.deriveKey(password, salt))
    private var uploadedFile: ByteArray = ByteArray(0)
    private var encryptMetaJson = ""
    val recorded = mutableListOf<String>()

    private fun HttpExchange.respond(status: Int, body: ByteArray, contentType: String = "application/json") {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) } else close()
    }

    @Before
    fun start() {
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val token = exchange.requestHeaders.getFirst("X-ACTUAL-TOKEN")
            val body = exchange.requestBody.readBytes()
            recorded += "${exchange.requestMethod} $path ${exchange.requestHeaders.getFirst("Content-Type") ?: ""}"
            when {
                path == "/account/login-methods" -> exchange.respond(200, """{"status":"ok","methods":[{"method":"password","active":1,"displayName":"Password"},{"method":"openid","active":0}]}""".toByteArray())
                path == "/account/login" -> {
                    val good = String(body).contains("\"password\":\"server-password\"")
                    if (good) exchange.respond(200, """{"status":"ok","data":{"token":"${FakeActualServer.TOKEN}"}}""".toByteArray())
                    else exchange.respond(400, """{"status":"error","reason":"invalid-password"}""".toByteArray())
                }
                token != FakeActualServer.TOKEN -> exchange.respond(401, """{"status":"error","reason":"unauthorized"}""".toByteArray())
                path == "/sync/list-user-files" -> exchange.respond(
                    200,
                    """{"status":"ok","data":[{"deleted":0,"fileId":"remote-file","groupId":"remote-group","name":"Synthetic Budget","encryptKeyId":"key-1","encryptMeta":$encryptMetaJson},{"deleted":1,"fileId":"gone","groupId":"g","name":"Deleted"}]}""".toByteArray(),
                )
                path == "/sync/user-get-key" -> {
                    val sealed = ActualCrypto.encrypt("test".toByteArray(), key)
                    val test = """{"value":"${b64(sealed.data)}","meta":{"keyId":"key-1","algorithm":"aes-256-gcm","iv":"${b64(sealed.iv)}","authTag":"${b64(sealed.authTag)}"}}"""
                    exchange.respond(200, """{"status":"ok","data":{"id":"key-1","salt":"$salt","test":${jsonString(test)}}}""".toByteArray())
                }
                path == "/sync/download-user-file" -> {
                    assertEquals("remote-file", exchange.requestHeaders.getFirst("X-ACTUAL-FILE-ID"))
                    exchange.respond(200, uploadedFile, "application/octet-stream")
                }
                path == "/sync/sync" -> try {
                    exchange.respond(200, sync.transport.sync(body, token), "application/actual-sync")
                } catch (error: Exception) {
                    exchange.respond(500, ByteArray(0))
                }
                path == "/redirect/sync/sync" -> {
                    exchange.responseHeaders.add("Location", "https://example.com/sync/sync")
                    exchange.respond(302, ByteArray(0))
                }
                path == "/reset/sync/sync" -> exchange.respond(400, "file-has-reset".toByteArray(), "text/plain")
                else -> exchange.respond(404, ByteArray(0))
            }
        }
        http.start()
        baseUrl = "http://127.0.0.1:${http.address.port}"
    }

    @After
    fun stop() = http.stop(0)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun jsonString(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** A budget "uploaded" by another client: a zip of its db.sqlite, sealed with the budget key. */
    private fun publishRemoteBudget(): BudgetDatabase {
        val source = TestDatabases.starter(nodeId = "remote0000000001", clock = clock)
        source.commitLocal(
            listOf(
                CellDraft("accounts", "acct-1", "name", SyncValue.text("Synthetic Checking")),
                CellDraft("accounts", "acct-1", "offbudget", SyncValue.bool(false)),
            ),
        )
        // The uploading client synced first, as Actual does before an upload.
        SyncEngine(source, sync.transport, sync.configuration(key), clock).sync(FakeActualServer.TOKEN)
        // A stale local outbox in the upload must never be adopted by the importer.
        source.sql.execute(
            "INSERT INTO actualist_outbox (timestamp, dataset, row, column, value, base_timestamp, created_at) " +
                "VALUES ('2026-10-15T12:00:00.000Z-0000-stale', 'accounts', 'acct-1', 'name', 'S:Evil', '', '')",
        )
        assertEquals(1, source.pendingLocalMessageCount())
        val path = source.sql.queryOne("PRAGMA database_list")!!.string("file")!!
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("metadata.json"))
            out.write("{}".toByteArray())
            out.putNextEntry(ZipEntry("db.sqlite"))
            out.write(File(path).readBytes())
        }
        val sealed = ActualCrypto.encrypt(zip.toByteArray(), key)
        uploadedFile = sealed.data
        encryptMetaJson = """{"keyId":"key-1","algorithm":"aes-256-gcm","iv":"${b64(sealed.iv)}","authTag":"${b64(sealed.authTag)}"}"""
        return source
    }

    @Test
    fun loginListDownloadImportAndSyncEndToEnd() {
        val remote = publishRemoteBudget()
        val client = ActualServerClient(baseUrl)
        assertEquals(listOf("password"), client.loginMethods())
        assertThrows(ServerException.WrongPassword::class.java) { client.login("nope") }
        val token = client.login("server-password")

        val files = client.listUserFiles(token)
        assertEquals(listOf("remote-file"), files.map { it.fileId })
        val file = files.single()
        assertEquals("remote-group", file.groupId)
        assertTrue(file.encryptMeta != null)

        val context = BudgetImporter.validateUserKey(client.userKey(token, file.fileId), password)
        assertThrows(LocalFirstException.InvalidEncryptionPassword::class.java) {
            BudgetImporter.validateUserKey(client.userKey(token, file.fileId), "wrong")
        }

        val downloaded = client.downloadUserFile(token, file.fileId)
        assertThrows(LocalFirstException.EncryptedBudgetRequiresPassword::class.java) { BudgetImporter.decryptIfNeeded(downloaded, file, null) }
        val zip = BudgetImporter.decryptIfNeeded(downloaded, file, context)
        val target = File(TestDatabases.tempFile().parentFile, "imported.sqlite")
        BudgetImporter.install(zip, target, TestDatabases.driver)

        BudgetDatabase.open(TestDatabases.driver, target.absolutePath, "phone00000000001", clock).use { local ->
            assertEquals("Synthetic Checking", local.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
            assertEquals(0, local.pendingLocalMessageCount())
            assertFalse(local.tableExists("actualist_outbox"))

            // Another device changes the budget through the server.
            val other = TestDatabases.starter(nodeId = "other00000000001", clock = clock)
            clock.advance(1_000)
            other.commitLocal(listOf(CellDraft("accounts", "acct-1", "name", SyncValue.text("Renamed Elsewhere"))))
            SyncEngine(other, client, sync.configuration(context), clock).sync(token)

            // This device edits offline, then syncs over HTTP.
            clock.advance(1_000)
            local.commitLocal(listOf(CellDraft("accounts", "acct-2", "name", SyncValue.text("Phone Savings"))))
            val result = SyncEngine(local, client, sync.configuration(context), clock).sync(token)
            assertEquals(1, result.uploaded)
            assertEquals("Renamed Elsewhere", local.sql.queryString("SELECT name FROM accounts WHERE id = 'acct-1'"))
            assertEquals(0, local.pendingLocalMessageCount())
            assertEquals(4, sync.storedTimestamps.size)
            assertTrue(recorded.any { it.startsWith("POST /sync/sync application/actual-sync") })
        }
        remote.close()
    }

    @Test
    fun authenticationRedirectsAndSyncRejectionsAreTyped() {
        val client = ActualServerClient(baseUrl)
        assertThrows(ServerException.AuthenticationFailed::class.java) { client.listUserFiles("expired") }
        assertThrows(ServerException.RedirectRefused::class.java) { ActualServerClient("$baseUrl/redirect").sync(ByteArray(0), FakeActualServer.TOKEN) }
        val rejected = assertThrows(ServerException.SyncRejected::class.java) { ActualServerClient("$baseUrl/reset").sync(ByteArray(0), FakeActualServer.TOKEN) }
        assertEquals("file-has-reset", rejected.reason)
    }

    @Test
    fun plainHttpIsOnlyAcceptedForLoopback() {
        assertThrows(ServerException.InsecureUrl::class.java) { ActualServerClient("http://budget.example.com") }
        assertThrows(ServerException.InsecureUrl::class.java) { ActualServerClient("http://192.0.2.10:5006") }
        assertThrows(ServerException.InvalidUrl::class.java) { ActualServerClient("not a url") }
        assertThrows(ServerException.InvalidUrl::class.java) { ActualServerClient("https://user:pass@budget.example.com") }
        assertEquals("https://budget.example.com/actual", ActualServerClient("https://budget.example.com/actual/").baseUrl.toString())
    }

    @Test
    fun malformedArchivesAreRejected() {
        val target = File(TestDatabases.tempFile().parentFile, "bad.sqlite")
        assertThrows(LocalFirstException.InvalidDownloadedBudget::class.java) {
            BudgetImporter.install("not a zip".toByteArray(), target, TestDatabases.driver)
        }
        val noDb = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("other.sqlite")); it.write(1) }
        }.toByteArray()
        assertThrows(LocalFirstException.InvalidDownloadedBudget::class.java) { BudgetImporter.install(noDb, target, TestDatabases.driver) }
        val notSqlite = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("db.sqlite")); it.write("garbage".toByteArray()) }
        }.toByteArray()
        assertThrows(LocalFirstException.InvalidDownloadedBudget::class.java) { BudgetImporter.install(notSqlite, target, TestDatabases.driver) }
        assertFalse(target.exists())
    }
}
