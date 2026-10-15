package com.sporez.actualist.android

import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The Report Explorer's five reports, interval, range, filter sheet, and the Reports screen's card order. */
@RunWith(AndroidJUnit4::class)
class ReportExplorerFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun openReports() {
        compose.onNodeWithTag("tab-reports").performClick()
        compose.waitForTag("reports-list")
    }

    private fun waitSelected(tag: String) {
        compose.waitOrCapture("selected-$tag") {
            compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Chips sit in horizontally scrolling rows: bring each into view before tapping it. */
    private fun click(tag: String) {
        // Range presets live in a LazyRow: scroll the row to the chip so it is placed, not just composed.
        if (tag.startsWith("explorer-range-")) compose.onNodeWithTag("explorer-ranges").performScrollToNode(hasTestTag(tag))
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun explorerSwitchesReportTypeIntervalRangeAndFilters() {
        openDemo()
        openReports()
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("open-explorer"))
        click("open-explorer")
        compose.waitForTag("explorer-chart-CASH_FLOW")
        waitSelected("explorer-interval-MONTH")

        // Every report type draws its own card.
        for (metric in listOf("NET_WORTH", "SPENDING", "BUDGET_OVERVIEW")) {
            click("explorer-metric-$metric")
            compose.waitForTag("explorer-chart-$metric")
            compose.waitForTag("explorer-primary-total")
        }
        click("explorer-metric-SPENDING_AVERAGE")
        compose.waitForTag("explorer-chart-SPENDING_AVERAGE")
        compose.waitForTag("explorer-average-month")
        click("explorer-average-previous")
        compose.waitForTag("explorer-chart-SPENDING_AVERAGE")

        // Interval and range presets on Cash Flow.
        click("explorer-metric-CASH_FLOW")
        compose.waitForTag("explorer-chart-CASH_FLOW")
        click("explorer-interval-DAY")
        waitSelected("explorer-interval-DAY")
        click("explorer-range-Last 3 Months")
        waitSelected("explorer-range-Last 3 Months")
        compose.waitForTag("explorer-chart-CASH_FLOW")

        // A custom range opens the Material date pickers.
        click("explorer-range-Custom Range")
        compose.waitForTag("explorer-custom-start")
        click("explorer-custom-start")
        compose.waitForTag("explorer-date-ok")
        click("explorer-date-ok")
        compose.waitForGone("explorer-date-ok")
        waitSelected("explorer-range-Custom Range")

        // The filter sheet: off-budget on shows a count, Reset Filters clears it.
        click("explorer-filters")
        compose.waitForTag("explorer-filter-sheet")
        click("filter-off-budget")
        click("filter-apply")
        compose.waitForGone("explorer-filter-sheet")
        compose.waitOrCapture("filters-count") { compose.onAllNodes(hasText("Filters (1)")).fetchSemanticsNodes().isNotEmpty() }
        click("explorer-filters")
        compose.waitForTag("explorer-filter-sheet")
        click("filter-reset")
        click("filter-apply")
        compose.waitForGone("explorer-filter-sheet")
        compose.waitOrCapture("filters-cleared") { compose.onAllNodes(hasText("Filters (1)")).fetchSemanticsNodes().isEmpty() }
        compose.waitForTag("explorer-totals")
    }

    private fun orderedTags(vararg tags: String): List<String> =
        compose.onAllNodes(SemanticsMatcher("one of the cards") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag) in tags
        }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun rowY(id: String): Float =
        compose.onNodeWithTag("report-order-row-$id").fetchSemanticsNode().positionInRoot.y

    @Test
    fun reportCardsReorderAndReset() {
        openDemo()
        openReports()
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("report-order-edit"))
        click("report-order-edit")
        compose.waitForTag("report-order-list")
        compose.onNodeWithTag("report-order-reset").performScrollTo()
        click("report-order-reset")
        assertTrue(rowY("net-worth") < rowY("cash-flow"))

        // Net Worth down one place puts Cash Flow first.
        compose.onNodeWithTag("report-order-down-net-worth").performScrollTo()
        click("report-order-down-net-worth")
        compose.waitOrCapture("net-worth-moved") { rowY("net-worth") > rowY("cash-flow") }
        click("report-order-done")
        compose.waitForGone("report-order-list")
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("report-cash-flow"))
        assertEquals("report-cash-flow", orderedTags("report-net-worth", "report-cash-flow").first())

        // Reset Report Order restores the default.
        compose.onNodeWithTag("reports-list").performScrollToNode(hasTestTag("report-order-edit"))
        click("report-order-edit")
        compose.waitForTag("report-order-list")
        compose.waitOrCapture("order-persisted") { rowY("net-worth") > rowY("cash-flow") }
        compose.onNodeWithTag("report-order-reset").performScrollTo()
        click("report-order-reset")
        compose.waitOrCapture("order-reset") { rowY("net-worth") < rowY("cash-flow") }
        click("report-order-done")
    }
}
