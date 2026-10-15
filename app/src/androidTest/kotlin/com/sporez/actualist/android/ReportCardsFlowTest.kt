package com.sporez.actualist.android

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The "This Month", "3-Month Average" and "Transaction Calendar" report cards on the demo budget. */
@RunWith(AndroidJUnit4::class)
class ReportCardsFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun showCard(tag: String, shot: String) {
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag(tag))
        compose.waitForTag(tag)
        compose.screenshot(shot)
    }

    @Test
    fun reportsShowTheComparisonAverageAndCalendarCards() {
        openDemo()
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")

        showCard("report-month-comparison", "reports-month-comparison")
        compose.waitForTag("month-comparison-variance")
        showCard("report-three-month-average", "reports-three-month-average")
        compose.waitForTag("three-month-variance")
        showCard("report-transaction-calendar", "reports-transaction-calendar")
        compose.waitOrCapture("calendar-days") {
            compose.onAllNodesWithTagCount("calendar-month-") > 0
        }

        // The cards follow the month switcher.
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("reports-previous"))
        compose.onNodeWithTag("reports-previous").performClick()
        showCard("report-transaction-calendar", "reports-transaction-calendar-previous-month")
        showCard("report-month-comparison", "reports-month-comparison-previous-month")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(prefix: String): Int =
        onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
            },
        ).fetchSemanticsNodes().size
}
