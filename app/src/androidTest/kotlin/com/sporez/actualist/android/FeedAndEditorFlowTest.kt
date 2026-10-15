package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The transaction feed and editor confirmations on the demo budget: swipe-to-delete with
 * Actualist's confirmation, the discard-changes prompt, "Convert to Schedule" from a row's
 * menu, and the category picker's search with no match.
 */
@RunWith(AndroidJUnit4::class)
class FeedAndEditorFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openSpending() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
    }

    private val rowMatcher = SemanticsMatcher("a transaction row") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith("transaction-") && tag !in setOf("transaction-list", "transaction-search", "transaction-editor")
    }

    private fun hasNode(matcher: SemanticsMatcher): Boolean = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun hasTag(tag: String): Boolean = hasNode(hasTestTag(tag))

    @Test
    fun swipeToDeleteAsksFirstAndDeletes() {
        openSpending()
        compose.waitOrCapture("rows") { hasNode(rowMatcher) }
        val before = compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size
        compose.onAllNodes(rowMatcher)[0].performTouchInput { swipeLeft() }
        compose.waitOrCapture("delete-dialog") { hasTag("delete-transaction-dialog") }
        // Cancel keeps the row.
        compose.onNodeWithTag("delete-transaction-cancel").performClick()
        compose.waitOrCapture("dialog-gone") { !hasTag("delete-transaction-dialog") }
        assertEquals(before, compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size)

        // The lazy list refills its viewport after a delete, so follow the swiped row itself.
        val swipedTag = compose.onAllNodes(rowMatcher).fetchSemanticsNodes()[0].config[SemanticsProperties.TestTag]
        compose.onAllNodes(rowMatcher)[0].performTouchInput { swipeLeft() }
        compose.waitOrCapture("delete-dialog-again") { hasTag("delete-transaction-dialog") }
        compose.onNodeWithTag("delete-transaction-confirm").performClick()
        compose.waitOrCapture("row-deleted") { !hasTag(swipedTag) }
    }

    @Test
    fun leavingAnEditedEditorAsksToDiscard() {
        openSpending()
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        // Nothing edited: closing leaves at once.
        compose.onNodeWithTag("editor-close").performClick()
        compose.waitOrCapture("closed-clean") { !hasTag("transaction-editor") }

        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("12.34")
        compose.onNodeWithTag("editor-close").performClick()
        compose.waitOrCapture("discard-dialog") { hasTag("discard-dialog") }
        compose.onNodeWithTag("discard-keep").performClick()
        compose.waitOrCapture("kept-editing") { !hasTag("discard-dialog") && hasTag("transaction-editor") }
        compose.onNodeWithTag("editor-close").performClick()
        compose.waitOrCapture("discard-dialog-again") { hasTag("discard-dialog") }
        compose.onNodeWithTag("discard-confirm").performClick()
        compose.waitOrCapture("discarded") { !hasTag("transaction-editor") }
    }

    @Test
    fun convertToScheduleFromARow() {
        openSpending()
        compose.waitOrCapture("menus") {
            compose.onAllNodes(hasTestTagPrefix("row-menu-"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasTestTagPrefix("row-menu-"), useUnmergedTree = true)[0].performClick()
        compose.waitOrCapture("convert-item") { hasTag("row-convert-schedule") }
        compose.onNodeWithTag("row-convert-schedule").performClick()
        compose.waitOrCapture("schedule-review") { hasTag("schedule-review") }
        compose.onNodeWithTag("schedule-review-create").performClick()
        compose.waitOrCapture("review-closed") { !hasTag("schedule-review") }
        compose.waitOrCapture("created-message") { hasTag("transactions-message") }
    }

    @Test
    fun categoryPickerSearchesAndShowsNoMatch() {
        openSpending()
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-category").performClick()
        compose.waitForTag("picker-search")
        compose.waitOrCapture("group-headers") { compose.onAllNodes(hasTestTagPrefix("header-category-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("picker-search").performTextInput("zzzqqq")
        compose.waitOrCapture("no-match") { hasTag("picker-empty") }
    }

    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("a tag starting with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
