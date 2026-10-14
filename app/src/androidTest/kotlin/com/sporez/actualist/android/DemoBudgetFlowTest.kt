package com.sporez.actualist.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end UI flows on the demo budget: launch without credentials, open the demo, visit
 * every main screen (with screenshots), assign money, add a transaction through the editor,
 * and survive activity recreation.
 */
@RunWith(AndroidJUnit4::class)
class DemoBudgetFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(fresh).around(compose)

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.screenshot("00-onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    private fun toBudgetText(): String =
        compose.onNodeWithTag("to-budget-amount").fetchSemanticsNode().config
            .let { config -> config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } }

    @Test
    fun launchesWithoutCredentialsAndShowsEveryMainScreen() {
        openDemo()
        compose.onNodeWithTag("budget-screen").assertIsDisplayed()
        compose.onNodeWithText("Essentials").assertExists()
        compose.screenshot("01-budget")

        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onAllNodesWithText("Fresh Market", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        compose.screenshot("02-spending")

        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithText("Everyday Checking").assertExists()
        compose.onNodeWithText("Car Loan").assertExists()
        compose.screenshot("03-accounts")

        compose.onNodeWithTag("account-checking").performClick()
        compose.waitForTag("account-balance")
        compose.screenshot("04-account-checking")
        compose.onNodeWithTag("nav-back").performClick()

        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("report-net-worth")
        compose.onNodeWithTag("report-cash-flow").assertExists()
        compose.screenshot("05-reports")

        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithText("Local demo · changes stay on this device").assertExists()
        compose.screenshot("06-settings")
    }

    @Test
    fun assigningMoneyChangesToBudget() {
        openDemo()
        val before = toBudgetText()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-vacation"))
        compose.onNodeWithTag("category-vacation").performClick()
        compose.waitForTag("category-sheet")
        compose.screenshot("07-category-sheet")
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("350.00")
        compose.onNodeWithTag("assign-save").performClick()
        compose.waitForGone("category-sheet")
        compose.waitUntil(30_000) { toBudgetText() != before }
        assertNotEquals(before, toBudgetText())
        compose.screenshot("08-after-assign")
    }

    @Test
    fun addsATransactionThroughTheEditor() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("12.34")
        compose.onNodeWithTag("editor-payee").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Synthetic Corner Cafe")
        compose.onNodeWithTag("picker-create").performClick()
        compose.onNodeWithTag("editor-category").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Dining")
        compose.onNodeWithTag("pick-dining").performClick()
        compose.screenshot("09-editor")
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForTag("transaction-list")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Synthetic Corner Cafe").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("-$12.34")).fetchSemanticsNodes().isNotEmpty())
        compose.screenshot("10-after-add")
    }

    @Test
    fun theOpenBudgetSurvivesActivityRecreation() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.activityRule.scenario.recreate()
        // The selected budget is persisted, so recreation reopens it instead of onboarding.
        compose.waitForTag("bottom-bar")
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
        // The selection is persisted, which is what reopens the budget after process death.
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("actualist_settings", android.content.Context.MODE_PRIVATE)
        org.junit.Assert.assertEquals(com.sporez.actualist.core.demo.DemoBudget.FILE_ID, prefs.getString("selected_budget", null))
    }
}
