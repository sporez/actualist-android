package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
 * Category management on the demo budget: rename, hide and unhide through "show hidden",
 * move down within a group, and delete with a transfer destination.
 */
@RunWith(AndroidJUnit4::class)
class CategoryManagementFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun openCategory(id: String) {
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-$id"))
        compose.onNodeWithTag("category-$id").performClick()
        compose.waitForTag("category-sheet")
    }

    private fun openManage() {
        compose.onNodeWithTag("category-manage-button").performScrollTo()
        compose.onNodeWithTag("category-manage-button").performClick()
        compose.waitForTag("category-manage")
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun renamesACategory() {
        openDemo()
        openCategory("dining")
        openManage()
        compose.onNodeWithTag("manage-rename").performClick()
        compose.waitForTag("rename-field")
        compose.onNodeWithTag("rename-field").performTextClearance()
        compose.onNodeWithTag("rename-field").performTextInput("Eating Out")
        compose.onNodeWithTag("rename-save").performClick()
        compose.waitForGone("category-sheet")
        compose.waitOrCapture("renamed") { compose.onAllNodesWithText("Eating Out").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun hidesACategoryThenUnhidesItFromShowHidden() {
        openDemo()
        openCategory("personal_care")
        compose.onNodeWithTag("hide-category").performScrollTo()
        compose.onNodeWithTag("hide-category").performClick()
        compose.waitForGone("category-sheet")
        compose.waitOrCapture("hidden-away") { compose.onAllNodesWithTag("category-personal_care").fetchSemanticsNodes().isEmpty() }

        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("show-hidden-switch"))
        compose.onNodeWithTag("show-hidden-switch").performClick()
        openCategory("personal_care")
        compose.onNodeWithTag("unhide-category").performScrollTo()
        compose.onNodeWithTag("unhide-category").performClick()
        compose.waitForGone("category-sheet")

        // Back to hiding hidden categories: an unhidden one stays.
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("show-hidden-switch"))
        compose.onNodeWithTag("show-hidden-switch").performClick()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-personal_care"))
        compose.waitOrCapture("unhidden") { compose.onAllNodesWithTag("category-personal_care").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun movesACategoryDownWithinItsGroup() {
        openDemo()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-entertainment"))
        assertTrue(top("category-dining") < top("category-entertainment"))
        openCategory("dining")
        openManage()
        compose.onNodeWithTag("manage-move-down").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-entertainment"))
        compose.waitOrCapture("moved-down") { top("category-entertainment") < top("category-dining") }
    }

    @Test
    fun deletesACategoryTransferringItsActivity() {
        openDemo()
        openCategory("shopping")
        openManage()
        compose.onNodeWithTag("manage-delete").performClick()
        // Shopping has transactions, so a destination must be chosen.
        compose.waitForTag("pick-groceries")
        compose.onNodeWithTag("pick-groceries").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-personal_care"))
        compose.waitOrCapture("deleted") { compose.onAllNodesWithTag("category-shopping").fetchSemanticsNodes().isEmpty() }
    }
}
