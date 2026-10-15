package com.sporez.actualist.android

import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Schedule management on the demo budget: the review before a skip, and a schedule's own upcoming window. */
@RunWith(AndroidJUnit4::class)
class ScheduleAutomationFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openSchedules() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-schedules").performClick()
        compose.waitForTag("schedules-screen")
        compose.waitForTag("schedule-post-demo-schedule-streaming")
    }

    private fun hasTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun hasVisibleText(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Lazy rows may be off screen, so scroll to the node with retries until it is composed. */
    private fun scrollToRow(tag: String) {
        compose.waitOrCapture("scroll-$tag") {
            runCatching { compose.onNodeWithTag("schedules-screen").performScrollToNode(hasTestTag(tag)) }
            hasTag(tag)
        }
    }

    @Test
    fun skipShowsAReviewAndOnlyWritesOnConfirm() {
        openSchedules()
        val skip = "schedule-skip-demo-schedule-rent"
        scrollToRow(skip)
        compose.onNodeWithTag(skip).performClick()
        compose.waitForTag("schedule-review")
        compose.waitOrCapture("skip-review-consequence") { hasVisibleText("No transaction is posted") }

        // Cancel leaves the schedule alone.
        compose.onNodeWithTag("schedule-review-cancel").performClick()
        compose.waitForGone("schedule-review")
        compose.waitOrCapture("skip-not-written") { !hasVisibleText("Skipped to the next date") }

        scrollToRow(skip)
        compose.onNodeWithTag(skip).performClick()
        compose.waitForTag("schedule-review")
        compose.onNodeWithTag("schedule-review-confirm").performClick()
        compose.waitForGone("schedule-review")
        compose.waitOrCapture("skip-written") { hasVisibleText("Skipped to the next date") }
    }

    @Test
    fun editsASchedulesUpcomingWindow() {
        openSchedules()
        val edit = "schedule-edit-demo-schedule-rent"
        scrollToRow(edit)
        compose.onNodeWithTag(edit).performClick()
        compose.waitForTag("schedule-edit-dialog")
        compose.waitForTag("schedule-edit-upcoming-14")
        // A chip in a horizontal row inside the dialog's scrolling column; Save is in the button row.
        runCatching { compose.onNodeWithTag("schedule-edit-upcoming-14").performScrollTo() }
        compose.onNodeWithTag("schedule-edit-upcoming-14").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNodeWithTag("schedule-edit-save").performClick()
        compose.waitForGone("schedule-edit-dialog")
        compose.waitOrCapture("edit-saved") { hasVisibleText("Updated") }

        val open = "schedule-open-demo-schedule-rent"
        scrollToRow(open)
        compose.onNodeWithTag(open).performClick()
        compose.waitForTag("schedule-detail")
        compose.waitOrCapture("detail-upcoming-window") { hasVisibleText("14 days") }
        compose.onNodeWithTag("schedule-detail-close").performClick()
        compose.waitForGone("schedule-detail")
    }
}
