package com.sporez.actualist.android

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * The payees page (search, add, unused filter, delete, a payee's rules) and the rules screens
 * (search, duplicate, the editor's match preview), all on the demo budget.
 */
@RunWith(AndroidJUnit4::class)
class PayeesAndRulesFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun openSettingsPage(tag: String, readyTag: String) {
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag(tag).performClick()
        compose.waitForTag(readyTag)
    }

    /** The node's text, or "" while it is not shown (the preview is briefly idle as conditions change). */
    private fun textOf(tag: String): String =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: ""

    @Test
    fun payeesPageSearchesAddsAndDeletesAnUnusedPayee() {
        openDemo()
        openSettingsPage("show-payees", "payees-screen")

        compose.onNodeWithTag("payees-search").performTextInput("Daily Grind")
        compose.waitForTag("payee-cafe")
        compose.waitForGone("payee-store")
        compose.onNodeWithTag("payees-search").performTextInput(" no such payee")
        compose.waitForTag("payees-empty")
        compose.screenshot("30-payees-search")

        // Add a payee: it starts unused, so the Unused filter appears and the payee can be deleted.
        compose.onNodeWithTag("payee-add").performScrollTo().performClick()
        compose.waitForTag("payee-new-name")
        compose.onNodeWithTag("payee-new-name").performTextInput("Synthetic Corner Shop")
        compose.onNodeWithTag("payee-new-save").performClick()
        compose.waitForGone("payee-new-name")
        compose.onNodeWithTag("payees-screen").performScrollToNode(hasTestTag("payees-search"))
        compose.waitForTag("payees-search")
        // Search for the new payee only.
        compose.onNodeWithTag("payees-search").performTextClearance()
        compose.onNodeWithTag("payees-search").performTextInput("Synthetic Corner")
        compose.waitOrCapture("new-payee-listed") { compose.onAllNodes(hasText("Synthetic Corner Shop")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Synthetic Corner Shop").performClick()
        compose.waitForTag("payee-delete")
        compose.onNodeWithTag("payee-delete").performScrollTo().performClick()
        compose.waitForTag("payee-delete-confirm")
        compose.onNodeWithTag("payee-delete-confirm").performClick()
        compose.waitOrCapture("payee-deleted") { compose.onAllNodes(hasText("Synthetic Corner Shop")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aPayeeListsTheRulesThatReferenceIt() {
        openDemo()
        openSettingsPage("show-payees", "payees-screen")
        compose.onNodeWithTag("payees-search").performTextInput("Daily Grind")
        compose.waitForTag("payee-cafe")
        compose.onNodeWithTag("payee-cafe").performClick()
        compose.waitForTag("payee-rules")
        // A payee in use and referenced by a rule cannot be deleted.
        compose.waitForTag("payee-delete-blocked")
        compose.onNodeWithTag("payee-rules").performScrollTo().performClick()
        compose.waitForTag("payee-rules-list")
        compose.waitOrCapture("payee-rule-text") { compose.onAllNodes(hasText("payee is Daily Grind", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForTag("payee-rule-add")
        compose.screenshot("31-payee-rules")
    }

    @Test
    fun rulesAreSearchableAndDuplicable() {
        openDemo()
        openSettingsPage("show-rules", "rules-screen")
        compose.waitForTag("rule-demo-rule-cafe")
        compose.onNodeWithTag("rules-search").performTextInput("QuickFuel")
        compose.waitForGone("rule-demo-rule-cafe")
        compose.waitForTag("rule-demo-rule-fuel")
        compose.onNodeWithTag("rule-duplicate-demo-rule-fuel").performScrollTo().performClick()
        compose.onNodeWithTag("rules-screen").performScrollToNode(hasTestTag("rules-message"))
        compose.waitOrCapture("rule-duplicated") { compose.onAllNodes(hasText("Rule duplicated")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitOrCapture("two-fuel-rules") {
            compose.onAllNodesWithTag("rules-screen").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("payee is QuickFuel", substring = true)).fetchSemanticsNodes().size >= 2
        }
    }

    @Test
    fun theRuleEditorPreviewsExistingMatches() {
        openDemo()
        openSettingsPage("show-rules", "rule-add")
        compose.onNodeWithTag("rule-add").performClick()
        compose.waitForTag("rule-editor")
        // Nothing to match until the condition is complete.
        compose.waitForTag("rule-matches-idle")
        compose.onNodeWithTag("rule-condition-0-value").performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Daily Grind")
        compose.onNodeWithTag("pick-cafe").performClick()
        compose.waitForTag("rule-matches-count")
        compose.onNodeWithTag("rule-matches-count").performScrollTo()
        compose.waitOrCapture("matches-counted") { textOf("rule-matches-count").firstOrNull()?.isDigit() == true }
        assertTrue(textOf("rule-matches-count").endsWith("transactions"))
        compose.screenshot("32-rule-matches")

        // A condition no transaction meets: the preview says so.
        compose.onNodeWithTag("rule-add-condition").performScrollTo().performClick()
        compose.onNodeWithTag("rule-condition-1-field").performScrollTo().performClick()
        compose.onNodeWithTag("pick-notes").performClick()
        compose.onNodeWithTag("rule-condition-1-text").performScrollTo().performTextInput("zzz-no-such-note")
        compose.waitOrCapture("no-matches") { textOf("rule-matches-count") == "No matching transactions" }
    }
}
