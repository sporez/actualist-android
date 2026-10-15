package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Account ordering and creation on the demo budget. */
@RunWith(AndroidJUnit4::class)
class AccountsFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openAccounts() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun movesAnAccountDownAndAddsAnOffBudgetAccount() {
        openAccounts()
        scrollTo("account-menu-checking")
        compose.onNodeWithTag("account-menu-checking").performClick()
        compose.waitForTag("account-move-down-checking")
        compose.onNodeWithTag("account-move-down-checking").performClick()
        compose.waitOrCapture("account-moved") { compose.onAllNodes(hasText("Moved account")).fetchSemanticsNodes().isNotEmpty() }

        scrollTo("add-account")
        compose.onNodeWithTag("add-account").performClick()
        compose.waitForTag("account-name")
        compose.onNodeWithTag("account-name").performTextInput("Synthetic Brokerage")
        compose.onNodeWithTag("account-kind-offbudget").performClick()
        compose.onNodeWithTag("account-create").performClick()
        compose.waitOrCapture("account-added") { compose.onAllNodes(hasText("Synthetic Brokerage")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodes(hasText("Synthetic Brokerage")).fetchSemanticsNodes().isNotEmpty())
    }
}
