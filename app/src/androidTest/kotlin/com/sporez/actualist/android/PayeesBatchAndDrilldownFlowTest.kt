package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Payees multi-select (batch favorite), the contributing-transactions drilldown from the Report Explorer, and
 * pull-to-refresh on the Budget screen, all on the demo budget.
 */
@RunWith(AndroidJUnit4::class)
class PayeesBatchAndDrilldownFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun selectingTwoPayeesFavoritesBoth() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-payees").performClick()
        compose.waitForTag("payees-screen")

        compose.waitForTag("payees-select")
        compose.onNodeWithTag("payees-select").performClick()
        compose.waitForTag("payees-batch-bar")
        for (id in listOf("cafe", "store")) {
            compose.onNodeWithTag("payees-screen").performScrollToNode(hasTestTag("payee-$id"))
            compose.waitForTag("payee-check-$id")
            compose.onNodeWithTag("payee-$id").performClick()
        }
        compose.onNodeWithTag("payees-screen").performScrollToNode(hasTestTag("payees-batch-bar"))
        compose.waitOrCapture("two-selected") {
            compose.onAllNodes(hasText("2 selected"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.screenshot("40-payees-selected")
        compose.onNodeWithTag("payees-batch-favorite").performClick()
        compose.waitOrCapture("favorited-two") {
            compose.onAllNodes(hasText("Favorited 2 payees"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // Leaving select mode offers Undo for the batch.
        compose.onNodeWithTag("payees-screen").performScrollToNode(hasTestTag("payees-select"))
        compose.onNodeWithTag("payees-select").performClick()
        compose.waitForTag("payees-undo")
    }

    @Test
    fun drilldownListsCountedTransactions() {
        openDemo()
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("open-explorer"))
        compose.onNodeWithTag("open-explorer").performClick()
        compose.waitForTag("explorer-totals")
        compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("report-drilldown-button"))
        compose.onNodeWithTag("report-drilldown-button").performClick()
        compose.waitForTag("drilldown-screen")
        compose.waitForTag("drilldown-count")
        compose.waitOrCapture("counted-rows") {
            compose.onAllNodes(tagStartsWith("drilldown-counted-"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("drilldown-empty"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "The demo budget has transactions in the default range, so the drilldown lists counted rows.",
            compose.onAllNodes(tagStartsWith("drilldown-counted-"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.screenshot("41-drilldown")
    }

    @Test
    fun pullToRefreshOnBudgetRunsWithoutError() {
        openDemo()
        compose.waitForTag("budget-refresh")
        compose.onNodeWithTag("budget-refresh").performTouchInput { swipeDown(startY = height * 0.2f, endY = height * 0.9f) }
        // The demo has no server, so the refresh is a no-op: the budget stays and the indicator settles.
        compose.waitOrCapture("refresh-settled") {
            compose.onAllNodes(hasTestTag("budget-refresh") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Idle"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForTag("to-budget-banner")
    }
}
