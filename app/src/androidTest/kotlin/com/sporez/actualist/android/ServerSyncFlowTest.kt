package com.sporez.actualist.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.server.SyncTransport
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import com.sporez.actualist.core.sync.SyncValue
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * End to end on the device: sign in to a loopback Actual server through the UI, download a
 * synthetic budget, edit it, sync, and check the server received the change. Exercises the
 * real OkHttp stack, the network security config, the Keystore-backed token store, the
 * importer and the sync engine together.
 */
@RunWith(AndroidJUnit4::class)
class ServerSyncFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val server = LoopbackActualServer()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(FreshAppState()).around(compose)

    @After
    fun stop() = server.close()

    /** A budget "uploaded" by another device: synced to the server, then zipped. */
    private fun publishBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "loopback-source.sqlite").apply { delete() }
        BudgetDatabase.createStarter(BundledSQLiteDriver(), file.absolutePath, "source0000000001").use { db ->
            db.commitLocal(
                listOf(
                    CellDraft("accounts", "acct-checking", "name", SyncValue.text("Loopback Checking")),
                    CellDraft("accounts", "acct-checking", "offbudget", SyncValue.bool(false)),
                    CellDraft("accounts", "acct-checking", "closed", SyncValue.bool(false)),
                    CellDraft("payees", "xfer-checking", "transfer_acct", SyncValue.text("acct-checking")),
                    CellDraft("category_groups", "grp", "name", SyncValue.text("Loopback Group")),
                    CellDraft("categories", "cat", "name", SyncValue.text("Loopback Groceries")),
                    CellDraft("categories", "cat", "cat_group", SyncValue.text("grp")),
                    CellDraft("category_mapping", "cat", "transferId", SyncValue.text("cat")),
                ),
            )
            val transport = SyncTransport { body, _ -> server.sync(body) }
            SyncEngine(db, transport, SyncConfiguration("loopback-file", "loopback-group", null, null)).sync(LoopbackActualServer.TOKEN)
        }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("db.sqlite"))
            out.write(file.readBytes())
            out.putNextEntry(ZipEntry("metadata.json"))
            out.write("{}".toByteArray())
        }
        server.budgetZip = zip.toByteArray()
    }

    @Test
    fun signsInDownloadsEditsAndSyncsAgainstALoopbackServer() {
        publishBudget()
        val seeded = server.storedCount
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(server.url)
        compose.onNodeWithTag("server-password-field").performTextInput("wrong")
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitForTag("server-error")
        compose.onNodeWithTag("server-password-field").performTextClearance()
        compose.onNodeWithTag("server-password-field").performTextInput(LoopbackActualServer.PASSWORD)
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Synthetic Budget").fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("20-server-budgets")
        compose.onNodeWithText("Loopback Synthetic Budget").performClick()

        compose.waitForTag("bottom-bar", 60_000)
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Checking").fetchSemanticsNodes().isNotEmpty() }

        // Edit locally, then sync from the top bar.
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transactions-screen")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("7.25")
        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Loopback Bakery")
        compose.onNodeWithTag("picker-create").performClick()
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("sync-button").performClick()
        compose.waitUntil(30_000) { server.storedValues.any { it == "payees/name=S:Loopback Bakery" } }
        compose.waitUntil(30_000) { server.storedValues.any { it == "transactions/amount=N:-725" } }
        assertTrue(server.storedCount > seeded)

        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Synced", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("21-synced-settings")
        assertTrue(server.requests.any { it == "POST /sync/sync" })
    }

    @Test
    fun createsANewBudgetOnTheServer() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(server.url)
        compose.onNodeWithTag("server-password-field").performTextInput(LoopbackActualServer.PASSWORD)
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitForTag("new-budget-name", 30_000)
        compose.onNodeWithTag("new-budget-name").performTextInput("Synthetic New Budget")
        compose.onNodeWithTag("new-budget-create").performClick()
        compose.waitForTag("bottom-bar", 60_000)
        assertTrue(server.uploadedNames.values.contains("Synthetic New Budget"))
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Usual Expenses").fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("22-new-budget")
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Synced", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun backgroundSyncPullsAnotherDevicesChangeIntoTheOpenBudget() {
        publishBudget()
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(server.url)
        compose.onNodeWithTag("server-password-field").performTextInput(LoopbackActualServer.PASSWORD)
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Synthetic Budget").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Loopback Synthetic Budget").performClick()
        compose.waitForTag("bottom-bar", 60_000)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Groceries").fetchSemanticsNodes().isNotEmpty() }

        // Another device adds a category and syncs it to the server.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "loopback-source.sqlite")
        BudgetDatabase.open(BundledSQLiteDriver(), source.absolutePath, "source0000000001").use { db ->
            db.commitLocal(
                listOf(
                    CellDraft("categories", "cat-bg", "name", SyncValue.text("Background Category")),
                    CellDraft("categories", "cat-bg", "cat_group", SyncValue.text("grp")),
                    CellDraft("category_mapping", "cat-bg", "transferId", SyncValue.text("cat-bg")),
                ),
            )
            SyncEngine(db, SyncTransport { body, _ -> server.sync(body) }, SyncConfiguration("loopback-file", "loopback-group", null, null))
                .sync(LoopbackActualServer.TOKEN)
        }

        // The worker WorkManager runs in the background syncs it into the session the UI has open.
        val worker = androidx.work.testing.TestListenableWorkerBuilder<com.sporez.actualist.android.sync.BackgroundSyncWorker>(context.applicationContext).build()
        val result = kotlinx.coroutines.runBlocking { worker.doWork() }
        org.junit.Assert.assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Background Category").fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("23-background-sync")
    }

    @Test
    fun linksAndSyncsASimpleFinAccountThroughTheServer() {
        publishBudget()
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(server.url)
        compose.onNodeWithTag("server-password-field").performTextInput(LoopbackActualServer.PASSWORD)
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Synthetic Budget").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Loopback Synthetic Budget").performClick()
        compose.waitForTag("bottom-bar", 60_000)
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Loopback Checking").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Loopback Checking").performClick()
        compose.waitForTag("manage-account")
        compose.onNodeWithTag("manage-account").performClick()
        compose.waitForTag("bank-link", 30_000)
        compose.onNodeWithTag("bank-link").performClick()
        compose.waitForTag("pick-remote-1", 30_000)
        compose.onNodeWithTag("pick-remote-1").performClick()
        compose.waitForGone("pick-remote-1")
        compose.onNodeWithTag("manage-account").performClick()
        compose.waitForTag("bank-sync", 30_000)
        compose.screenshot("24-bank-sync")
        compose.onNodeWithTag("bank-sync").performClick()
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Synthetic Bank Market", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(server.simpleFinRequests.single().contains("remote-1"))
        compose.screenshot("25-bank-synced")
    }

    @Test
    fun signsInWithOpenIdThroughTheBrowserRedirect() {
        publishBudget()
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(server.url)
        compose.onNodeWithTag("server-openid").performClick()
        compose.waitUntil(30_000) { server.openIdReturnUrl != null }
        val returnUrl = server.openIdReturnUrl!!
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(returnUrl, returnUrl.startsWith("${context.packageName}://localhost/openid/"))

        fun deliver(uri: String) = context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .setPackage(context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // What the identity provider's redirect does once the user signs in there.
        deliver("$returnUrl/openid-cb?token=${LoopbackActualServer.TOKEN}")
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Loopback Synthetic Budget").fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("26-openid-signed-in")
        // The nonce was single use: replaying the redirect is refused.
        deliver("$returnUrl/openid-cb?token=${LoopbackActualServer.TOKEN}")
        compose.waitForTag("server-error", 30_000)
    }
}
