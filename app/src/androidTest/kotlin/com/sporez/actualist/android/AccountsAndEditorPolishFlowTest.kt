package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The close review's consequences, the Budget's add-transaction button, and the split editor's collapse. */
@RunWith(AndroidJUnit4::class)
class AccountsAndEditorPolishFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun theCloseReviewListsTheConsequences() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-menu-checking"))
        compose.onNodeWithTag("account-menu-checking").performClick()
        compose.waitForTag("account-close-checking")
        compose.onNodeWithTag("account-close-checking").performClick()
        compose.waitForTag("close-account-dialog")
        compose.waitForTag("close-consequence-transactions")
        compose.waitForTag("close-consequence-balance")
        compose.onNodeWithTag("close-destination").performClick()
        compose.waitForTag("pick-savings")
        compose.onNodeWithTag("pick-savings").performClick()
        compose.waitForTag("close-consequence-destination")
        compose.waitOrCapture("transfer-and-close") { compose.onAllNodes(hasText("Transfer and Close"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theBudgetsAddTransactionButtonOpensTheEditor() {
        openDemo()
        compose.waitForTag("budget-add-transaction")
        compose.onNodeWithTag("budget-add-transaction").performClick()
        compose.waitForTag("transaction-editor")
    }

    @Test
    fun removingSplitRowsDownToOneCollapsesToANormalTransaction() {
        openDemo()
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("add-transaction").performClick()
        compose.waitForTag("transaction-editor")
        compose.onNodeWithTag("editor-amount").performTextInput("10.00")
        compose.onNodeWithTag("editor-split-toggle").performScrollTo().performClick()
        compose.waitForTag("split-amount-0")
        compose.waitForTag("split-delete-0")
        compose.onNodeWithTag("split-delete-0").performScrollTo().performClick()
        compose.waitForGone("split-amount-0")
        compose.waitForTag("editor-category")
        compose.waitOrCapture("split-toggle-back") { compose.onAllNodes(hasText("Split across categories")).fetchSemanticsNodes().isNotEmpty() }
    }
}
