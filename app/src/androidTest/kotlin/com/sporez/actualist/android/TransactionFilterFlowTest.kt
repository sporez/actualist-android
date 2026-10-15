package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The Spending feed's filter sheet: apply conditions without saving (Match Any over two payees),
 * reset, save as a filter, then rename and delete it (with a confirmation) from Saved Filters.
 */
@RunWith(AndroidJUnit4::class)
class TransactionFilterFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun hasText(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("test tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun openSheet() {
        compose.onNodeWithTag("filter-new").performClick()
        compose.waitForTag("filter-sheet")
    }

    private fun choosePayee(vararg names: String) {
        compose.onNodeWithTag("filter-select-payee").performScrollTo().performClick()
        for (name in names) {
            compose.waitForTag("filter-option-$name")
            compose.onNodeWithTag("filter-option-$name").performClick()
        }
        compose.onNodeWithTag("filter-options-done").performClick()
        compose.waitForGone("filter-options-done")
    }

    @Test
    fun applyResetSaveRenameAndDeleteAFilter() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")

        // Match Any over two payees, applied without saving.
        openSheet()
        compose.onNodeWithTag("filter-join-any").performScrollTo().performClick()
        choosePayee("Fresh Market", "Bistro Nove")
        compose.onNodeWithTag("filter-apply").performScrollTo().performClick()
        compose.waitForGone("filter-sheet")
        compose.waitOrCapture("filter-applied") { hasText("Filters (1)") }
        compose.waitOrCapture("filter-applied-list") { hasText("Fresh Market") || hasText("Bistro Nove") }
        assertTrue(!hasTag("filter-manage"))

        // The sheet reopens on the applied conditions, then reset clears them.
        openSheet()
        compose.onNodeWithTag("filter-select-payee").performScrollTo()
        assertTrue(hasText("Fresh Market, Bistro Nove") || hasText("Bistro Nove, Fresh Market"))
        compose.onNodeWithTag("filter-cancel").performScrollTo().performClick()
        compose.waitForGone("filter-sheet")
        compose.onNodeWithTag("filter-reset-chip").performClick()
        compose.waitOrCapture("filter-reset") { hasText("More Filters") }

        // Save a filter from the sheet.
        openSheet()
        choosePayee("Fresh Market")
        compose.onNodeWithTag("filter-name").performScrollTo().performTextInput("Groceries Run")
        compose.onNodeWithTag("filter-save").performScrollTo().performClick()
        compose.waitForGone("filter-sheet")
        compose.waitOrCapture("filter-saved") { hasText("Groceries Run") }

        // Rename it.
        compose.onNodeWithTag("filter-manage").performClick()
        compose.waitForTag("saved-filters-list")
        compose.onNode(tagStartsWith("filter-rename-")).performClick()
        compose.waitForTag("filter-rename-field")
        compose.onNodeWithTag("filter-rename-field").performTextClearance()
        compose.onNodeWithTag("filter-rename-field").performTextInput("Weekly Food")
        compose.onNodeWithTag("filter-rename-confirm").performClick()
        compose.waitOrCapture("filter-renamed") { hasText("Weekly Food") && !hasText("Groceries Run") }

        // Delete it, after the confirmation.
        compose.onNode(tagStartsWith("filter-delete-")).performClick()
        compose.waitForTag("filter-delete-confirm")
        assertTrue(hasText("Delete Saved Filter?"))
        assertTrue(hasText("This removes Weekly Food from your saved filters."))
        compose.onNodeWithTag("filter-delete-confirm").performClick()
        compose.waitOrCapture("filter-deleted") { !hasText("Weekly Food") && hasText("No Saved Filters") }
        compose.onNodeWithTag("saved-filters-done").performClick()
        compose.waitForGone("saved-filters-done")
    }
}
