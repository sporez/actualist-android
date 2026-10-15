package com.sporez.actualist.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.ui.reports.ReportOrderStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Settings account order and chart order, and the schedule editor's no-change message. */
@RunWith(AndroidJUnit4::class)
class FinalGapsBFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openSettings() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
    }

    private fun scrollTo(tag: String) {
        compose.waitForTag(tag)
        compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun hasTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun rowTop(tag: String): Float? =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top

    @Test
    fun accountOrderSheetMovesAnAccount() {
        openSettings()
        scrollTo("settings-account-order")
        compose.onNodeWithTag("settings-account-order").performClick()
        compose.waitForTag("settings-account-order-list")
        compose.waitForTag("settings-account-order-row-checking")
        val before = rowTop("settings-account-order-row-checking") ?: error("checking row missing")
        compose.onNodeWithTag("settings-account-order-down-checking").performClick()
        compose.waitOrCapture("account-order-moved") { (rowTop("settings-account-order-row-checking") ?: before) > before }
        compose.onNodeWithTag("settings-account-order-done").performClick()
        compose.waitForGone("settings-account-order-list")
    }

    @Test
    fun chartOrderOpensAndResets() {
        openSettings()
        val store = ReportOrderStore(compose.activity.applicationContext)
        scrollTo("settings-chart-order")
        compose.onNodeWithTag("settings-chart-order").performClick()
        compose.waitForTag("settings-chart-order-list")
        compose.onNodeWithTag("settings-chart-order-up-cash-flow").performClick()
        compose.waitOrCapture("chart-order-moved") { store.load().first() == "cash-flow" }
        compose.onNodeWithTag("settings-chart-order-reset").performClick()
        compose.waitOrCapture("chart-order-reset") { store.load().first() == "net-worth" }
        assertEquals("net-worth", store.load().first())
        compose.onNodeWithTag("settings-chart-order-done").performClick()
        compose.waitForGone("settings-chart-order-list")
    }

    @Test
    fun scheduleEditorRefusesASaveWithNoChange() {
        openSettings()
        scrollTo("show-schedules")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedules-screen")
        val edit = "schedule-edit-demo-schedule-rent"
        compose.waitOrCapture("edit-row") {
            runCatching { compose.onNodeWithTag("schedules-screen").performScrollToNode(androidx.compose.ui.test.hasTestTag(edit)) }
            hasTag(edit)
        }
        compose.onNodeWithTag(edit).performClick()
        compose.waitForTag("schedule-edit-dialog")
        // The rule loads asynchronously; its start field appears once the editor knows the schedule.
        compose.waitForTag("schedule-edit-start")
        compose.onNodeWithTag("schedule-edit-save").performClick()
        compose.waitForTag("schedule-edit-no-change")
        compose.onNodeWithTag("schedule-edit-dialog").assertExists()
    }
}
