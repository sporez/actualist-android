package com.sporez.actualist.core.server

import com.sporez.actualist.core.TestClock
import com.sporez.actualist.core.TestDatabases
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.budget.BudgetWrites
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import com.sporez.actualist.core.sync.SyncValue
import com.sporez.actualist.core.transactions.LedgerReads
import com.sporez.actualist.core.transactions.TransactionDraft
import com.sporez.actualist.core.transactions.TransactionQuery
import com.sporez.actualist.core.transactions.TransactionWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Against a real, disposable Actual server (`actualbudget/actual-server:26.10.0`, the oracle's
 * version), started by `.github/workflows/real-server.yml` with a synthetic password. Skipped
 * unless `ACTUAL_TEST_SERVER_URL` is set. Never point this at a server holding real data.
 */
class RealActualServerTest {
    private val url: String? = System.getenv("ACTUAL_TEST_SERVER_URL")
    private val password: String = System.getenv("ACTUAL_TEST_SERVER_PASSWORD") ?: ""

    @Test
    fun createsSyncsAndConvergesThroughARealActualServer() {
        assumeTrue("set ACTUAL_TEST_SERVER_URL to run against a disposable Actual server", url != null)
        val client = ActualServerClient(url!!)
        assertTrue(client.loginMethods().contains("password"))
        assertThrows(ServerException.WrongPassword::class.java) { client.login("definitely-not-the-password") }
        val token = client.login(password)
        assertThrows(ServerException.AuthenticationFailed::class.java) { client.listUserFiles("not-a-token") }

        // Create and register a new budget, as the app's "Create a new budget" does.
        val dir = TestDatabases.tempFile().parentFile
        val fileId = UUID.randomUUID().toString()
        val name = "Synthetic CI Budget ${fileId.take(8)}"
        val creatorFile = File(dir, "creator.sqlite")
        NewBudget.createFile(TestDatabases.driver, creatorFile.absolutePath)
        val groupId = client.uploadUserFile(token, fileId, name, null, NewBudget.archive(creatorFile, fileId, name))
            ?: client.userFileInfo(token, fileId)?.groupId
        assertTrue("the server assigned no group id", !groupId.isNullOrEmpty())
        val listed = client.listUserFiles(token).single { it.fileId == fileId }
        assertEquals(name, listed.name)
        assertEquals(groupId, listed.groupId)
        assertNull(listed.encryptKeyId)

        // A second device downloads the registered file.
        val phoneFile = File(dir, "phone.sqlite")
        BudgetImporter.install(client.downloadUserFile(token, fileId), phoneFile, TestDatabases.driver)

        val clock = TestClock(System.currentTimeMillis())
        val configuration = SyncConfiguration(fileId, groupId, null, null)
        BudgetDatabase.open(TestDatabases.driver, creatorFile.absolutePath, "c1c1c1c1c1c1c1c1", clock).use { creator ->
            BudgetDatabase.open(TestDatabases.driver, phoneFile.absolutePath, "p2p2p2p2p2p2p2p2", clock).use { phone ->
                val creatorSync = SyncEngine(creator, client, configuration, clock)
                val phoneSync = SyncEngine(phone, client, configuration, clock)
                val food = creator.sql.queryString("SELECT id FROM categories WHERE name = 'Food'")!!
                val today = LocalDate.now()
                val month = "%04d-%02d".format(java.util.Locale.ROOT, today.year, today.monthValue)

                // Creator: an account, a transaction and a budget assignment.
                creator.commitLocal(
                    listOf(
                        CellDraft("accounts", "acct-ci", "name", SyncValue.text("Synthetic CI Checking")),
                        CellDraft("accounts", "acct-ci", "offbudget", SyncValue.bool(false)),
                        CellDraft("accounts", "acct-ci", "closed", SyncValue.bool(false)),
                        CellDraft("payees", "xfer-acct-ci", "transfer_acct", SyncValue.text("acct-ci")),
                        CellDraft("payee_mapping", "xfer-acct-ci", "targetId", SyncValue.text("xfer-acct-ci")),
                    ),
                )
                TransactionWrites(creator, clock).create(TransactionDraft(accountId = "acct-ci", date = today, amount = -4_200, payeeName = "Synthetic Grocer", categoryId = food))
                BudgetWrites(creator).assign(month, food, 30_000)
                val pushed = creatorSync.sync(token)
                assertTrue(pushed.uploaded > 0)
                assertEquals(0, pushed.pendingAfter)

                // Phone pulls everything.
                clock.advance(1_000)
                phoneSync.sync(token)
                assertEquals(30_000L, BudgetReads(phone).fetchBudgetMonth(month).category(food)!!.budgeted)
                assertEquals(listOf(-4_200L), LedgerReads(phone).transactions(TransactionQuery(accountId = "acct-ci")).map { it.amount })

                // Concurrent offline edits to the same cell: the later write wins on both sides.
                clock.advance(1_000)
                BudgetWrites(creator).assign(month, food, 35_000)
                clock.advance(1_000)
                BudgetWrites(phone).assign(month, food, 50_000)
                phoneSync.sync(token)
                clock.advance(1_000)
                creatorSync.sync(token)
                clock.advance(1_000)
                phoneSync.sync(token)
                assertEquals(50_000L, BudgetReads(creator).fetchBudgetMonth(month).category(food)!!.budgeted)
                assertEquals(50_000L, BudgetReads(phone).fetchBudgetMonth(month).category(food)!!.budgeted)
                assertEquals(creator.merkleTrie().hash, phone.merkleTrie().hash)
                assertEquals(0, creator.pendingLocalMessageCount())
                assertEquals(0, phone.pendingLocalMessageCount())
            }
        }
    }
}
