package com.sporez.actualist.android

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.core.budget.BudgetReads
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.server.ActualServerClient
import com.sporez.actualist.core.server.BudgetImporter
import com.sporez.actualist.core.sync.SyncConfiguration
import com.sporez.actualist.core.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The app against a real, disposable Actual server (`actualbudget/actual-server:26.10.0`) that
 * the emulator job starts and exposes on the device's loopback with `adb reverse`. Signs in
 * through the UI, creates a budget, assigns money and syncs; then a second "device" downloads
 * the budget from the server and must see the assignment. Skipped without the server arguments.
 */
@RunWith(AndroidJUnit4::class)
class RealServerDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(FreshAppState()).around(compose)

    private val arguments = InstrumentationRegistry.getArguments()
    private val url: String? = arguments.getString("actualServerUrl")
    private val password: String = arguments.getString("actualServerPassword") ?: ""

    @Test
    fun createsAssignsAndSyncsABudgetOnARealActualServer() {
        assumeTrue("run by the emulator job with a disposable Actual server", url != null)
        val name = "Synthetic Device Budget ${UUID.randomUUID().toString().take(6)}"
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("onboarding-connect").performClick()
        compose.waitForTag("server-url-field")
        compose.onNodeWithTag("server-url-field").performTextClearance()
        compose.onNodeWithTag("server-url-field").performTextInput(url!!)
        compose.onNodeWithTag("server-password-field").performTextInput(password)
        compose.onNodeWithTag("server-sign-in").performClick()
        compose.waitForTag("new-budget-name", 30_000)
        compose.onNodeWithTag("new-budget-name").performTextInput(name)
        compose.onNodeWithTag("new-budget-create").performClick()
        compose.waitForTag("bottom-bar", 60_000)

        // Assign money to the starter "Food" category, then sync from the top bar.
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Food").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("budget-list").performScrollToNode(hasText("Food"))
        compose.onAllNodesWithText("Food")[0].performClick()
        compose.waitForTag("category-sheet")
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("123.45")
        compose.onNodeWithTag("assign-save").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("sync-button").performClick()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Synced", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("27-real-server-synced")

        // A second device downloads the budget from the real server and syncs it.
        val client = ActualServerClient(url)
        val token = client.login(password)
        val remote = client.listUserFiles(token).single { it.name == name }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "real-server-second-device.sqlite").apply { delete() }
        BudgetImporter.install(client.downloadUserFile(token, remote.fileId), file, BundledSQLiteDriver())
        BudgetDatabase.open(BundledSQLiteDriver(), file.absolutePath, "d2d2d2d2d2d2d2d2").use { other ->
            val engine = SyncEngine(other, client, SyncConfiguration(remote.fileId, remote.groupId, null, null))
            val food = other.sql.queryString("SELECT id FROM categories WHERE name = 'Food' AND tombstone = 0")!!
            val today = LocalDate.now()
            val month = "%04d-%02d".format(java.util.Locale.ROOT, today.year, today.monthValue)
            fun budgeted() = BudgetReads(other).fetchBudgetMonth(month).category(food)!!.budgeted
            // The app's upload may still be in flight; poll the real server for up to 30 seconds.
            var attempts = 0
            do {
                engine.sync(token)
                if (budgeted() == 12_345L) break
                Thread.sleep(1_000)
            } while (++attempts < 30)
            assertEquals(12_345L, budgeted())
        }
        client.deleteUserFile(token, remote.fileId)
    }
}
