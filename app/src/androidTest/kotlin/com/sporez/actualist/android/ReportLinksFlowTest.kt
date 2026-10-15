package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A Reports card opens the Explorer on its own report (Cash Flow here), and the Explorer's
 * transaction list pages in more rows and opens a row in the editor. Needs the "explorer" route
 * to accept the card preset (explorer?card=cash-flow).
 */
@RunWith(AndroidJUnit4::class)
class ReportLinksFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun shownCount(): Int {
        val node = compose.onAllNodesWithTag("explorer-rows-count").fetchSemanticsNodes().firstOrNull() ?: return -1
        val text = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: return -1
        return Regex("Showing (\\d+) of").find(text)?.groupValues?.get(1)?.toInt() ?: -1
    }

    @Test
    fun cashFlowCardOpensTheExplorerAndItsTransactionsOpenTheEditor() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")

        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("report-cash-flow"))
        compose.onNodeWithTag("report-cash-flow").performClick()

        // The Explorer opens on Cash Flow, monthly, for the card's range.
        compose.waitForTag("explorer-chart-CASH_FLOW")
        compose.waitOrCapture("cash-flow-selected") {
            compose.onAllNodes(hasTestTag("explorer-metric-CASH_FLOW") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }

        // The widest range has the most transactions to page through.
        compose.onNodeWithTag("explorer-ranges").performScrollToNode(hasTestTag("explorer-range-Last 6 Months"))
        compose.onNodeWithTag("explorer-range-Last 6 Months").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForTag("explorer-chart-CASH_FLOW")

        compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("explorer-rows-count"))
        compose.waitOrCapture("rows-counted") { shownCount() >= 0 }
        val before = shownCount()
        if (compose.onAllNodesWithTag("explorer-load-more").fetchSemanticsNodes().isNotEmpty() ||
            runCatching { compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("explorer-load-more")) }.isSuccess
        ) {
            compose.onNodeWithTag("explorer-load-more").performSemanticsAction(SemanticsActions.OnClick)
            // Loading more scrolls the count line away; bring it back before reading it.
            compose.waitOrCapture("more-rows") {
                runCatching { compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("explorer-rows-count")) }
                shownCount() > before
            }
            assertTrue(shownCount() > before)
        }

        // A row opens its transaction in the editor.
        compose.onNodeWithTag("explorer-screen").performScrollToNode(hasTestTag("explorer-row"))
        compose.onAllNodesWithTag("explorer-row")[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForTag("transaction-editor")
    }
}
