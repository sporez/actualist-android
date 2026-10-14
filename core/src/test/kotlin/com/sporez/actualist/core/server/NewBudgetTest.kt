package com.sporez.actualist.core.server

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.sync.FakeActualServer
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

/** A new budget is built like Actual's, registered with upload-user-file, and then syncs. */
class NewBudgetTest {
    @Test
    fun theStarterFileHasActualsMigrationsCategoriesAndDashboard() {
        val file = File(TestDatabases.tempFile().parentFile, "new.sqlite")
        var n = 0
        NewBudget.createFile(TestDatabases.driver, file.absolutePath) { "id-${++n}" }
        BudgetDatabase.open(TestDatabases.driver, file.absolutePath, "phone00000000001", TestClock()).use { db ->
            assertEquals(60L, db.sql.queryLong("SELECT COUNT(*) FROM __migrations__"))
            assertEquals(1_788_468_782_000L, db.sql.queryLong("SELECT MAX(id) FROM __migrations__"))
            assertEquals(12L, db.sql.queryLong("SELECT COUNT(*) FROM dashboard"))
            assertEquals(listOf("Usual Expenses", "Investments and Savings", "Income"), db.sql.query("SELECT name FROM category_groups ORDER BY sort_order").map { it.string("name") })
            assertEquals(7L, db.sql.queryLong("SELECT COUNT(*) FROM categories"))
            assertEquals(7L, db.sql.queryLong("SELECT COUNT(*) FROM category_mapping"))
            assertEquals(0L, db.messageCount())
            // The new budget works with the ported engine straight away.
            BudgetWrites(db).assign("2026-10", db.sql.queryString("SELECT id FROM categories WHERE name = 'Food'")!!, 25_000)
            assertEquals(25_000L, BudgetReads(db).fetchBudgetMonth("2026-10").totalBudgeted)
        }
    }

    @Test
    fun registersWithTheServerAndSyncs() {
        val sync = FakeActualServer(fileId = "new-file", groupId = "group-new")
        var uploaded = ByteArray(0)
        val headers = HashMap<String, String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes()
            val reply: ByteArray = when (exchange.requestURI.path) {
                "/sync/upload-user-file" -> {
                    uploaded = body
                    for (name in listOf("X-ACTUAL-TOKEN", "X-ACTUAL-FILE-ID", "X-ACTUAL-NAME", "X-ACTUAL-FORMAT", "X-ACTUAL-GROUP-ID")) {
                        exchange.requestHeaders.getFirst(name)?.let { headers[name] = it }
                    }
                    """{"status":"ok","groupId":"group-new"}""".toByteArray()
                }
                "/sync/download-user-file" -> uploaded
                "/sync/sync" -> sync.transport.sync(body, exchange.requestHeaders.getFirst("X-ACTUAL-TOKEN"))
                else -> ByteArray(0)
            }
            exchange.sendResponseHeaders(200, if (reply.isEmpty()) -1 else reply.size.toLong())
            if (reply.isNotEmpty()) exchange.responseBody.use { it.write(reply) } else exchange.close()
        }
        http.start()
        try {
            val client = ActualServerClient("http://127.0.0.1:${http.address.port}")
            val dir = TestDatabases.tempFile().parentFile
            val file = File(dir, "created.sqlite")
            NewBudget.createFile(TestDatabases.driver, file.absolutePath)
            val name = "Ünïcode & Budget 2026"
            val groupId = client.uploadUserFile(FakeActualServer.TOKEN, "new-file", name, null, NewBudget.archive(file, "new-file", name))
            assertEquals("group-new", groupId)
            assertEquals("new-file", headers["X-ACTUAL-FILE-ID"])
            assertEquals("2", headers["X-ACTUAL-FORMAT"])
            assertEquals(null, headers["X-ACTUAL-GROUP-ID"])
            assertEquals(name, URLDecoder.decode(headers.getValue("X-ACTUAL-NAME"), "UTF-8"))
            assertEquals("%C3%9Cn%C3%AFcode%20%26%20Budget%202026", headers["X-ACTUAL-NAME"])

            // Another device downloads the registered file and both sync through the server.
            val other = File(dir, "other.sqlite")
            BudgetImporter.install(client.downloadUserFile(FakeActualServer.TOKEN, "new-file"), other, TestDatabases.driver)
            val clock = TestClock()
            BudgetDatabase.open(TestDatabases.driver, file.absolutePath, "creator000000001", clock).use { creator ->
                BudgetDatabase.open(TestDatabases.driver, other.absolutePath, "phone00000000002", clock).use { phone ->
                    val food = creator.sql.queryString("SELECT id FROM categories WHERE name = 'Food'")!!
                    BudgetWrites(creator).assign("2026-10", food, 40_000)
                    val configuration = SyncConfiguration("new-file", groupId, null, null)
                    assertEquals(0, SyncEngine(creator, client, configuration, clock).sync(FakeActualServer.TOKEN).pendingAfter)
                    clock.advance(1_000)
                    SyncEngine(phone, client, configuration, clock).sync(FakeActualServer.TOKEN)
                    assertEquals(40_000L, BudgetReads(phone).fetchBudgetMonth("2026-10").category(food)!!.budgeted)
                    assertTrue(sync.storedTimestamps.isNotEmpty())
                }
            }
        } finally {
            http.stop(0)
        }
    }
}
