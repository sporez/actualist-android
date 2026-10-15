package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Multi-destination Move Money, the category month screen, and the Accounts list's collapsible sections
 * and group header menu, on the demo budget.
 */
@RunWith(AndroidJUnit4::class)
class CategoryMonthAndAccountsFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun openCategory(id: String) {
        compose.waitOrCapture("category-$id") {
            runCatching { compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-$id")) }.isSuccess
        }
        compose.onNodeWithTag("category-$id").performClick()
        compose.waitForTag("category-sheet")
    }

    private fun openAccounts() {
        openDemo()
        compose.onNodeWithTag("tab-accounts").performClick()
        compose.waitForTag("budget-accounts")
    }

    /** A node whose test tag starts with [prefix] and does not start with any of [except]. */
    private fun tagStarts(prefix: String, vararg except: String) = SemanticsMatcher("tag starts with $prefix") { node ->
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        tag != null && tag.startsWith(prefix) && except.none { tag.startsWith(it) }
    }

    @Test
    fun movesMoneyToSeveralDestinationsInOneMove() {
        openDemo()
        openCategory("vacation")
        compose.onNodeWithTag("move-button").performScrollTo()
        click("move-button")
        compose.waitForTag("move-money-sheet")
        // The first destination is To Budget; "Select Another" adds Groceries with its own amount.
        compose.onNodeWithTag("move-amount-field").performTextInput("10")
        click("move-add-another")
        compose.waitForTag("pick-groceries")
        compose.onNodeWithTag("pick-groceries").performClick()
        compose.waitForTag("move-amount-field-1")
        compose.onNodeWithTag("move-amount-field-1").performTextInput("5")
        compose.waitForTag("move-result-counterparty-1")
        compose.screenshot("move-several-destinations")
        click("move-confirm")
        compose.waitForGone("move-money-sheet")
        // One write, so one message about the whole move.
        compose.waitOrCapture("moved-message") {
            runCatching { compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("budget-message")) }.isSuccess
        }
        compose.waitForTag("budget-message")
        compose.onNodeWithTag("budget-message").assertTextContains("Moved in 2 parts")
    }

    @Test
    fun categoryMonthScreenShowsTheMonthAndTogglesRollover() {
        openDemo()
        openCategory("groceries")
        click("category-transactions")
        compose.waitForTag("category-month-summary")
        compose.waitForTag("category-month-budgeted")
        compose.waitForTag("category-month-spent")
        compose.waitForTag("category-month-remaining")
        compose.onNodeWithTag("category-month-rollover").performClick()
        compose.waitOrCapture("rollover-on") {
            compose.onAllNodes(hasTestTag("category-month-rollover") and isOn()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.screenshot("category-month")
    }

    @Test
    fun collapsesAndExpandsAnAccountsSection() {
        openAccounts()
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("toggle-budget-accounts"))
        compose.onNodeWithTag("toggle-budget-accounts").performClick()
        compose.waitForGone("budget-accounts")
        compose.onNodeWithTag("toggle-budget-accounts").performClick()
        compose.waitForTag("budget-accounts")
    }

    @Test
    fun groupHeaderMenuRenamesAddsAnAccountAndDeletes() {
        openAccounts()
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("manage-groups"))
        compose.onNodeWithTag("manage-groups").performClick()
        compose.waitForTag("groups-dialog")
        compose.onNodeWithTag("group-name").performTextInput("Synthetic Cash")
        compose.onNodeWithTag("group-create").performClick()
        compose.waitOrCapture("group-created") { compose.onAllNodes(hasText("Synthetic Cash")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Done").performClick()
        compose.waitForGone("groups-dialog")

        // An empty group still shows, with a header menu.
        val groupMenu = tagStarts("group-menu-")
        compose.onNodeWithTag("accounts-list").performScrollToNode(groupMenu)
        compose.waitOrCapture("group-menu") { compose.onAllNodes(groupMenu).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(groupMenu)[0].performClick()
        compose.waitOrCapture("rename-item") { compose.onAllNodes(tagStarts("group-menu-rename-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tagStarts("group-menu-rename-"))[0].performClick()
        compose.waitForTag("group-rename-field")
        compose.onNodeWithTag("group-rename-field").performTextClearance()
        compose.onNodeWithTag("group-rename-field").performTextInput("Renamed Cash")
        compose.onNodeWithTag("group-rename-save").performClick()
        compose.waitOrCapture("renamed") { compose.onAllNodes(hasText("Renamed Cash")).fetchSemanticsNodes().isNotEmpty() }

        // "Add Account" on the empty group lists the accounts that can move in.
        val addAccount = tagStarts("group-add-account-")
        compose.onNodeWithTag("accounts-list").performScrollToNode(addAccount)
        compose.onAllNodes(addAccount)[0].performClick()
        val candidate = tagStarts("group-add-", "group-add-account-")
        compose.waitOrCapture("add-candidates") { compose.onAllNodes(candidate).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(candidate)[0].performClick()
        compose.waitOrCapture("no-longer-empty") { compose.onAllNodes(tagStarts("group-empty-")).fetchSemanticsNodes().isEmpty() }

        // Delete Group asks first; the group's accounts stay.
        compose.onNodeWithTag("accounts-list").performScrollToNode(groupMenu)
        compose.onAllNodes(groupMenu)[0].performClick()
        compose.waitOrCapture("delete-item") { compose.onAllNodes(tagStarts("group-menu-delete-")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tagStarts("group-menu-delete-"))[0].performClick()
        compose.waitForTag("group-delete-confirm")
        compose.onNodeWithTag("group-delete-confirm").performClick()
        compose.waitOrCapture("group-deleted") { compose.onAllNodes(hasText("Renamed Cash")).fetchSemanticsNodes().isEmpty() }
    }
}
