package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** History shows a rich row for an assignment and asks for a review before undoing it. */
@RunWith(AndroidJUnit4::class)
class HistoryFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun anAssignmentReadsRichlyAndUndoAsksForReview() {
        openDemo()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-vacation"))
        compose.onNodeWithTag("category-vacation").performClick()
        compose.waitForTag("category-sheet")
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("350.00")
        compose.onNodeWithTag("assign-save").performClick()
        compose.waitForGone("category-sheet")

        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("show-history")
        compose.onNodeWithTag("show-history").performClick()
        compose.waitForTag("history-item-0")
        compose.waitOrCapture("history-rich-row") {
            compose.onAllNodesWithText("Assigned", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithText("Vacation", substring = true).fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithTag("history-undo").performClick()
        compose.waitForTag("history-review")
        compose.waitForTag("history-review-entry-0")
        // Cancelling leaves the assignment in place.
        compose.onNodeWithTag("history-review-cancel").performClick()
        compose.waitForGone("history-review")
        compose.waitForTag("history-undo")

        compose.onNodeWithTag("history-undo").performClick()
        compose.waitForTag("history-review")
        compose.onNodeWithTag("history-review-confirm").performClick()
        compose.waitForGone("history-review")
        compose.waitOrCapture("history-undone") {
            compose.onAllNodesWithText("Undone").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
