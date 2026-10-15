package com.sporez.actualist.android

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.server.SyncTransport
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import com.sporez.actualist.core.sync.SyncValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Settings that need a server or local files: the fallback server (validation and failover), the
 * security row, Sign In Again, deleting a downloaded budget, and Exit Demo Mode.
 */
@RunWith(AndroidJUnit4::class)
class SettingsServerFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val server = LoopbackActualServer()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    @After
    fun stop() = server.close()

    private val container get() = (compose.activity.application as ActualistApplication).container

    private fun scrollTo(tag: String) {
        compose.waitForTag(tag)
        compose.onNodeWithTag(tag).performScrollTo()
    }

    /** A synthetic budget "uploaded" by another device, as ServerSyncFlowTest does. */
    private fun publishBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "loopback-source-settings.sqlite").apply { delete() }
        BudgetDatabase.createStarter(BundledSQLiteDriver(), file.absolutePath, "source0000000002").use { db ->
            db.commitLocal(
                listOf(
                    CellDraft("accounts", "acct-checking", "name", SyncValue.text("Loopback Checking")),
                    CellDraft("accounts", "acct-checking", "offbudget", SyncValue.bool(false)),
                    CellDraft("accounts", "acct-checking", "closed", SyncValue.bool(false)),
                    CellDraft("payees", "xfer-checking", "transfer_acct", SyncValue.text("acct-checking")),
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

    private fun signInAndOpenBudget() {
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
    }

    private fun openSettings() {
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
    }

    @Test
    fun validatesAndSavesTheFallbackServerThenFailsOverToItWhenThePrimaryIsDown() {
        signInAndOpenBudget()
        openSettings()

        scrollTo("edit-fallback-server")
        compose.onNodeWithTag("edit-fallback-server").performClick()
        compose.waitForTag("fallback-server-field")
        // Plain http to a remote host is refused, as for the primary.
        compose.onNodeWithTag("fallback-server-field").performTextInput("http://fallback.example")
        compose.onNodeWithTag("fallback-server-save").performClick()
        compose.waitForTag("fallback-server-error")
        // The primary's own address is not a fallback.
        compose.onNodeWithTag("fallback-server-field").performTextClearance()
        compose.onNodeWithTag("fallback-server-field").performTextInput(server.url)
        compose.onNodeWithTag("fallback-server-save").performClick()
        compose.waitForTag("fallback-server-error")
        compose.onNodeWithTag("fallback-server-field").performTextClearance()
        compose.onNodeWithTag("fallback-server-field").performTextInput("https://fallback.example")
        compose.onNodeWithTag("fallback-server-save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("fallback-server-field")).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("fallback-server-summary").assertTextEquals("https://fallback.example")
        assertEquals("https://fallback.example", container.settings.settings.value.fallbackServerUrl)

        // The primary becomes unreachable; the fallback is the live loopback server.
        assertNull(container.server.saveFallback(server.url, ""))
        container.settings.update { it.copy(serverUrl = "http://127.0.0.1:1") }
        scrollTo("sync-now")
        compose.onNodeWithTag("sync-now").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasTestTag("connection-status") and hasText("used fallback", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(server.requests.any { it == "POST /sync/sync" })
        compose.screenshot("30-fallback-used")
    }

    @Test
    fun showsTheSecurityRowForTheDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        openSettings()
        scrollTo("budget-security")
        compose.onNodeWithTag("budget-security").assertTextEquals("Not encrypted")
    }

    @Test
    fun signInAgainInSettingsRenewsTheSession() {
        signInAndOpenBudget()
        openSettings()
        scrollTo("settings-sign-in-again")
        compose.onNodeWithTag("settings-sign-in-again").performClick()
        compose.waitForTag("settings-reauth-password")
        compose.onNodeWithTag("settings-reauth-password").performTextInput(LoopbackActualServer.PASSWORD)
        compose.onNodeWithTag("settings-reauth-submit").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("settings-reauth-password")).fetchSemanticsNodes().isEmpty() }
        assertTrue(server.requests.count { it == "POST /account/login" } >= 2)
    }

    @Test
    fun signOutWarnsAndThenForgetsTheSession() {
        signInAndOpenBudget()
        openSettings()
        scrollTo("sign-out")
        compose.onNodeWithTag("sign-out").performClick()
        compose.waitForTag("sign-out-message")
        compose.onNodeWithTag("sign-out-confirm").performClick()
        compose.waitUntil(10_000) { !container.server.isSignedIn }
    }

    @Test
    fun deletesADownloadedBudgetFromTheOnDeviceList() {
        signInAndOpenBudget()
        openSettings()
        scrollTo("close-budget")
        compose.onNodeWithTag("close-budget").performClick()
        compose.waitForTag("local-loopback-file")
        assertNotNull(container.library.metadata("loopback-file"))
        compose.onNodeWithTag("local-delete-loopback-file").performClick()
        compose.waitForTag("local-delete-message")
        compose.onNodeWithTag("local-delete-confirm").performClick()
        compose.waitForGone("local-loopback-file")
        assertNull(container.library.metadata("loopback-file"))
        assertTrue(!container.library.databaseFile("loopback-file").exists())
    }

    @Test
    fun disconnectAndEraseRemovesTheTokenAndEveryLocalBudget() {
        signInAndOpenBudget()
        openSettings()
        scrollTo("erase-local-data")
        compose.onNodeWithTag("erase-local-data").performClick()
        compose.waitForTag("erase-message")
        compose.onNodeWithTag("erase-confirm").performClick()
        compose.waitForTag("onboarding")
        assertTrue(!container.server.isSignedIn)
        assertNull(container.settings.settings.value.serverUrl)
        assertTrue(container.library.list().isEmpty())
    }

    @Test
    fun exitDemoModeRemovesTheDemoBudget() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        openSettings()
        scrollTo("erase-local-data")
        compose.onNodeWithTag("erase-local-data").performClick()
        compose.waitForTag("erase-message")
        compose.onNodeWithTag("erase-confirm").performClick()
        compose.waitForTag("onboarding")
        assertNull(container.library.metadata(DemoBudget.FILE_ID))
    }
}
