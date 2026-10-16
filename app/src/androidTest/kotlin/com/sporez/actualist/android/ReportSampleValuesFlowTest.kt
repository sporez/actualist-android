package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.data.Settings
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Sample values on: the Reports cards, the Report Explorer and its filter sheet show made-up amounts and names
 * (Actualist's `ReportExplorerUITests` privacy test), and the account names in the filter sheet are replaced.
 */
@RunWith(AndroidJUnit4::class)
class ReportSampleValuesFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun text(tag: String): String? =
        runCatching {
            compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        }.getOrNull()

    @Test
    fun reportsAndExplorerShowSampleValues() {
        val settings = (compose.activity.application as ActualistApplication).container.settings
        try {
            compose.waitForTag("onboarding")
            compose.enterDemo()
            compose.waitForTag("to-budget-banner")
            compose.onNodeWithTag("tab-reports").performClick()
            compose.waitForTag("reports-list")
            // The amount is merged into its clickable card, so scroll to the card's item and read the unmerged tree.
            compose.onNodeWithTag("reports-list").performScrollToKey("cash-flow")
            compose.waitOrCapture("real-income") { text("cash-flow-income") != null }
            val realIncome = text("cash-flow-income")

            settings.update { it.copy(sampleValues = true) }
            compose.waitOrCapture("sample-income") { val shown = text("cash-flow-income"); shown != null && shown != realIncome }
            assertNotEquals(realIncome, text("cash-flow-income"))

            compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("open-explorer"))
            compose.onNodeWithTag("open-explorer").performClick()
            compose.waitForTag("explorer-totals")
            compose.waitOrCapture("explorer-total") { text("explorer-primary-total") != null }

            compose.onNodeWithTag("explorer-filters").performClick()
            compose.waitForTag("explorer-filter-sheet")
            compose.onAllNodes(hasText("Everyday Checking", substring = true)).assertCountEquals(0)
        } finally {
            settings.update { Settings() }
        }
    }
}
