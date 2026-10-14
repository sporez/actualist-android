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

    @Test
    fun encryptedBudgetsRoundTripThroughARealActualServer() {
        assumeTrue("set ACTUAL_TEST_SERVER_URL to run against a disposable Actual server", url != null)
        val client = ActualServerClient(url!!)
        val token = client.login(password)
        val budgetPassword = "synthetic-budget-password-${UUID.randomUUID().toString().take(6)}"
        val dir = TestDatabases.tempFile().parentFile
        val fileId = UUID.randomUUID().toString()
        val name = "Synthetic Encrypted ${fileId.take(8)}"
        val creatorFile = File(dir, "enc-creator.sqlite")
        NewBudget.createFile(TestDatabases.driver, creatorFile.absolutePath)
        val keys = NewBudget.RegistrationKeys.make(budgetPassword, NewBudget.archive(creatorFile, fileId, name))
        val groupId = client.uploadUserFile(token, fileId, name, null, keys.encryptedArchive, keys.encryptMeta)
            ?: client.userFileInfo(token, fileId)?.groupId
        client.createUserKey(token, fileId, keys.keyId, keys.salt, keys.testContent)

        // The server now reports the file as encrypted, and the key test proves the password.
        val remote = client.userFileInfo(token, fileId)!!
        assertEquals(keys.keyId, remote.encryptKeyId)
        val serverKey = client.userKey(token, fileId)
        assertThrows(com.sporez.actualist.core.LocalFirstException.InvalidEncryptionPassword::class.java) {
            BudgetImporter.validateUserKey(serverKey, "wrong-password")
        }
        val context = BudgetImporter.validateUserKey(serverKey, budgetPassword)
        assertTrue(context.keyData.contentEquals(keys.keyData))

        // A second device downloads, decrypts and installs the archive.
        val phoneFile = File(dir, "enc-phone.sqlite")
        val download = client.downloadUserFile(token, fileId)
        BudgetImporter.install(BudgetImporter.decryptIfNeeded(download, remote, context), phoneFile, TestDatabases.driver)

        val clock = TestClock(System.currentTimeMillis())
        val configuration = SyncConfiguration(fileId, groupId, keys.keyId, context)
        BudgetDatabase.open(TestDatabases.driver, creatorFile.absolutePath, "e1e1e1e1e1e1e1e1", clock).use { creator ->
            BudgetDatabase.open(TestDatabases.driver, phoneFile.absolutePath, "e2e2e2e2e2e2e2e2", clock).use { phone ->
                val savings = creator.sql.queryString("SELECT id FROM categories WHERE name = 'Savings'")!!
                val today = LocalDate.now()
                val month = "%04d-%02d".format(java.util.Locale.ROOT, today.year, today.monthValue)
                BudgetWrites(creator).assign(month, savings, 12_345)
                creator.commitLocal(listOf(CellDraft("notes", "budget-$month", "note", SyncValue.text("Synthetic encrypted note"))))
                SyncEngine(creator, client, configuration, clock).sync(token)
                clock.advance(1_000)
                SyncEngine(phone, client, configuration, clock).sync(token)
                assertEquals(12_345L, BudgetReads(phone).fetchBudgetMonth(month).category(savings)!!.budgeted)
                assertEquals("Synthetic encrypted note", BudgetReads(phone).monthNote(month))
                assertEquals(creator.merkleTrie().hash, phone.merkleTrie().hash)
                // Without the key the messages cannot be read.
                val outsider = File(dir, "enc-outsider.sqlite")
                BudgetImporter.install(BudgetImporter.decryptIfNeeded(download, remote, context), outsider, TestDatabases.driver)
                BudgetDatabase.open(TestDatabases.driver, outsider.absolutePath, "e3e3e3e3e3e3e3e3", clock).use { other ->
                    assertThrows(com.sporez.actualist.core.LocalFirstException.EncryptedBudgetRequiresPassword::class.java) {
                        SyncEngine(other, client, SyncConfiguration(fileId, groupId, keys.keyId, null), clock).sync(token)
                    }
                }
            }
        }
        client.deleteUserFile(token, fileId)
        assertTrue(client.listUserFiles(token).none { it.fileId == fileId })
    }

    @Test
    fun simpleFinRoutesAnswerAsAnUnconfiguredServer() {
        assumeTrue("set ACTUAL_TEST_SERVER_URL to run against a disposable Actual server", url != null)
        val token = ActualServerClient(url!!).login(password)
        TestDatabases.starter(clock = TestClock()).use { db ->
            // The disposable server has no SimpleFIN token: status says so, and the accounts route
            // refuses without contacting SimpleFIN.
            val sync = com.sporez.actualist.core.banksync.SimpleFinSync(db, com.sporez.actualist.core.banksync.SimpleFinServerClient(url), token)
            assertEquals(com.sporez.actualist.core.banksync.SimpleFinServerSupport.NOT_CONFIGURED, sync.serverSupport())
            assertThrows(com.sporez.actualist.core.banksync.SimpleFinException::class.java) { sync.remoteAccounts() }
            val unauthenticated = com.sporez.actualist.core.banksync.SimpleFinSync(db, com.sporez.actualist.core.banksync.SimpleFinServerClient(url), "not-a-token")
            assertThrows(Exception::class.java) { unauthenticated.serverSupport() }
        }
    }
}
