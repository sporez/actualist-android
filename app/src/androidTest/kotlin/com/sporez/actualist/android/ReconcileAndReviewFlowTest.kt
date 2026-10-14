package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The multi-step reconcile flow, a batch action's before → after review, and the split editor's
 * Fill Remaining, on the synthetic demo budget.
 */
@RunWith(AndroidJUnit4::class)
class ReconcileAndReviewFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose)

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    private fun openSavingsReconcile() {
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("account-savings").performClick()
        compose.waitForTag("account-balance")
        compose.onNodeWithTag("reconcile").performClick()
        compose.waitForTag("reconcile-balance")
        compose.waitOrCapture("reconcile-cleared-loaded") { compose.onAllNodes(hasText("Cleared balance: …")).fetchSemanticsNodes().isEmpty() }
    }

    private val rowMatcher = SemanticsMatcher("a transaction row") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith("transaction-") && tag !in setOf("transaction-list", "transaction-search")
    }

    @Test
    fun reconcilesInSteps() {
        openDemo()
        openSavingsReconcile()
        compose.onNodeWithTag("reconcile-balance").performTextInput("20000.00")
        compose.waitForTag("reconcile-difference")
        compose.onNodeWithTag("reconcile-continue").performClick()

        // Review: the difference is open, so the next move is the adjustment, not the lock.
        compose.waitForTag("reconcile-adjust")
        compose.waitForTag("reconcile-uncleared")
        compose.onNodeWithTag("reconcile-adjust").performClick()

        // Preview what will be created before creating it.
        compose.waitForTag("reconcile-preview-amount")
        compose.waitForTag("reconcile-preview-notes")
        compose.onAllNodes(hasText("Reconciliation balance adjustment")).fetchSemanticsNodes().let { check(it.isNotEmpty()) }
        compose.onNodeWithTag("reconcile-adjust-confirm").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Adjustment added")).fetchSemanticsNodes().isNotEmpty() }

        // Back on review, now balanced: lock the cleared transactions.
        compose.waitForTag("reconcile-finish")
        compose.onNodeWithTag("reconcile-finish").performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Reconciled and locked")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForGone("reconcile-dialog")
        compose.waitOrCapture("adjustment-row") { compose.onAllNodes(hasText("Reconciliation balance adjustment", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun editsTheBalanceAndExitsWithoutLocking() {
        openDemo()
        openSavingsReconcile()
        compose.onNodeWithTag("reconcile-balance").performTextInput("1.00")
        compose.onNodeWithTag("reconcile-continue").performClick()
        compose.waitForTag("reconcile-edit")
        compose.onNodeWithTag("reconcile-edit").performClick()
        compose.waitForTag("reconcile-balance")
        compose.onNodeWithTag("reconcile-continue").performClick()
        compose.waitForTag("reconcile-exit")
        compose.onNodeWithTag("reconcile-exit").performClick()
        compose.waitForGone("reconcile-dialog")
        check(compose.onAllNodes(hasText("Reconciled and locked")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun reviewsABatchBeforeChangingAnything() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.waitUntil(30_000) { compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size >= 2 }
        compose.onAllNodes(rowMatcher)[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForTag("selection-bar")
        compose.onAllNodes(rowMatcher)[1].performClick()
        compose.waitOrCapture("two-selected") { compose.onAllNodes(hasText("2 selected")).fetchSemanticsNodes().isNotEmpty() }

        // Cancelling the review changes nothing and keeps the selection.
        compose.onNodeWithTag("batch-delete").performClick()
        compose.waitForTag("batch-review")
        compose.waitForTag("batch-review-hint")
        compose.onNodeWithTag("batch-review-cancel").performClick()
        compose.waitForGone("batch-review")
        compose.waitForTag("selection-bar")

        // Clear then unclear: the unclear review shows each row's before → after.
        compose.onNodeWithTag("batch-clear").performClick()
        compose.waitForTag("batch-review")
        compose.onNodeWithTag("batch-review-confirm").performClick()
        compose.waitForGone("selection-bar")
        compose.onAllNodes(rowMatcher)[0].performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForTag("selection-bar")
        compose.onAllNodes(rowMatcher)[1].performClick()
        compose.waitOrCapture("two-selected-again") { compose.onAllNodes(hasText("2 selected")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("batch-unclear").performClick()
        compose.waitForTag("batch-review")
        compose.waitOrCapture("unclear-effect") { compose.onAllNodes(hasText("Cleared: Cleared → Uncleared")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("batch-review-confirm").performClick()
        compose.waitForGone("selection-bar")
        compose.waitOrCapture("batch-message") { compose.onAllNodes(hasText("Uncleared 2", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun fillsTheRemainingSplitAndRemovesAnEntry() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("10.00")
        compose.onNodeWithTag("editor-split-toggle").performScrollTo().performClick()
        compose.waitForTag("split-amount-0")
        compose.onNodeWithTag("split-amount-0").performScrollTo().performTextInput("4.00")
        compose.waitOrCapture("left") { compose.onAllNodes(hasText("\$6.00 left")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("split-fill-remaining").performScrollTo().performClick()
        compose.waitOrCapture("filled") { compose.onAllNodes(hasText("\$0.00 Remaining")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("split-amount-1").performScrollTo().assertTextContains("6.00")
        compose.waitForGone("split-fill-remaining")

        // A third entry can be removed again.
        compose.onNodeWithTag("split-add").performScrollTo().performClick()
        compose.waitForTag("split-delete-2")
        compose.onNodeWithTag("split-delete-2").performScrollTo().performClick()
        compose.waitForGone("split-delete-2")
        check(compose.onAllNodes(hasTestTag("split-amount-2")).fetchSemanticsNodes().isEmpty())
    }
}
