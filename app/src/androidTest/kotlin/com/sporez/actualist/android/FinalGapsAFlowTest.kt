package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Editor split rows survive a rotation, the envelope category picker offers "To Budget" instead of the
 * income categories, and an older History row explains why it cannot be undone yet.
 */
@RunWith(AndroidJUnit4::class)
class FinalGapsAFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun openNewTransaction() {
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
    }

    @Test
    fun splitRowsSurviveARotation() {
        openDemo()
        openNewTransaction()
        compose.onNodeWithTag("editor-amount").performTextInput("30.00")
        compose.onNodeWithTag("editor-split-toggle").performScrollTo().performClick()
        compose.waitForTag("split-amount-0")
        compose.onNodeWithTag("split-amount-0").performScrollTo().performTextInput("10.00")
        compose.onNodeWithTag("split-amount-1").performScrollTo().performTextInput("20.00")
        compose.onNodeWithTag("split-notes-1").performScrollTo().performTextInput("kept note")

        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitOrCapture("split-rows-after-rotation") {
                compose.onAllNodes(hasTestTag("split-amount-1") and hasText("20.00")).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodes(hasTestTag("split-amount-0") and hasText("10.00")).fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodes(hasTestTag("split-notes-1") and hasText("kept note")).fetchSemanticsNodes().isNotEmpty())
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
    }

    @Test
    fun theEnvelopePickerOffersToBudgetInsteadOfIncomeCategories() {
        openDemo()
        openNewTransaction()
        compose.onNodeWithTag("editor-category").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.waitOrCapture("to-budget-option") {
            compose.onAllNodes(hasText("To Budget")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Paycheck")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anOlderHistoryRowExplainsWhyItCannotBeUndoneYet() {
        openDemo()
        for (amount in listOf("350.00", "360.00")) {
            compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-vacation"))
            compose.onNodeWithTag("category-vacation").performClick()
            compose.waitForTag("category-sheet")
            compose.onNodeWithTag("assign-field").performTextClearance()
            compose.onNodeWithTag("assign-field").performTextInput(amount)
            compose.onNodeWithTag("assign-save").performClick()
            compose.waitForGone("category-sheet")
        }
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("show-history")
        compose.onNodeWithTag("show-history").performClick()
        compose.waitForTag("history-item-1")
        compose.waitOrCapture("history-reason") {
            compose.onAllNodes(hasTestTag("history-reason-1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodes(hasText("Undo the newest action before this one."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("history-reason-0"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
