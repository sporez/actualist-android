package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Split lines with their own payee and notes (saved and reopened), the category picker's
 * available-balance chips and selection mark, and the feed's cleared marker, on the synthetic demo budget.
 */
@RunWith(AndroidJUnit4::class)
class SplitsAndMarkersFlowTest {
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

    private fun tagPrefix(prefix: String) = SemanticsMatcher("a tag starting with $prefix") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private val rowMatcher = SemanticsMatcher("a transaction row") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith("transaction-") && tag !in setOf("transaction-list", "transaction-search", "transaction-editor")
    }

    private fun hasUnmerged(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSplitLineKeepsItsPayeeAndNotesWhenReopened() {
        openSpending()
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("30.00")
        compose.onNodeWithTag("editor-split-toggle").performScrollTo().performClick()
        compose.waitForTag("split-amount-0")
        compose.onNodeWithTag("split-amount-0").performScrollTo().performTextInput("10.00")
        compose.onNodeWithTag("split-amount-1").performScrollTo().performTextInput("20.00")

        // The first line gets a payee of its own and a note.
        compose.onNodeWithTag("split-payee-0").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Split Line Payee")
        compose.waitForTag("picker-create")
        compose.onNodeWithTag("picker-create").performClick()
        compose.waitOrCapture("line-payee-set") {
            compose.onAllNodes(hasTestTag("split-payee-0") and hasText("Split Line Payee", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("split-notes-0").performScrollTo().performTextInput("first note")
        compose.onNodeWithTag("split-category-0").performScrollTo().performClick()
        compose.waitForTag("pick-groceries")
        compose.onNodeWithTag("pick-groceries").performClick()
        compose.onNodeWithTag("split-category-1").performScrollTo().performClick()
        // The grouped picker is long; search so the row is composed.
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Dining")
        compose.waitForTag("pick-dining")
        compose.onNodeWithTag("pick-dining").performClick()

        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForGone("transaction-editor")

        // The feed shows the split under its children's payee; reopening it shows the line's payee and note.
        compose.waitOrCapture("split-row") { hasUnmerged(hasText("Split Line Payee")) }
        compose.onNode(hasText("Split Line Payee"), useUnmergedTree = true).performClick()
        compose.waitForTag("transaction-editor")
        compose.waitForTag("split-payee-0")
        // Children come back in the ledger's order, so the line may be either one.
        compose.waitOrCapture("reopened-line-payee") {
            (0..1).any { index ->
                compose.onAllNodes(hasTestTag("split-payee-$index") and hasText("Split Line Payee", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitOrCapture("reopened-line-note") {
            (0..1).any { index ->
                compose.onAllNodes(hasTestTag("split-notes-$index") and hasText("first note", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun theCategoryPickerShowsBalanceChipsAndMarksTheSelection() {
        openSpending()
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-category").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.waitOrCapture("balance-chip") { hasUnmerged(tagPrefix("category-balance-")) }
        compose.waitForTag("pick-groceries")
        compose.onNodeWithTag("pick-groceries").performClick()

        compose.onNodeWithTag("editor-category").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Groceries")
        compose.waitOrCapture("selected-mark") { hasUnmerged(hasTestTag("category-selected-groceries")) }
    }

    @Test
    fun aClearedRowShowsItsMarker() {
        openSpending()
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("5.00")
        compose.onNodeWithTag("editor-payee").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Marker Test Payee")
        compose.waitForTag("picker-create")
        compose.onNodeWithTag("picker-create").performClick()
        compose.onNodeWithTag("editor-cleared").performScrollTo().performClick()
        compose.onNodeWithTag("editor-save").performClick()
        compose.waitForGone("transaction-editor")

        compose.onNodeWithTag("transaction-search").performTextInput("Marker Test Payee")
        // Searching narrows the feed to that one row, which carries the cleared marker.
        compose.waitOrCapture("marker-row") {
            compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size == 1 && hasUnmerged(tagPrefix("cleared-marker-"))
        }
    }
}
