package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Authoring on the demo budget: a rule with a "cleared is true" condition and a "set amount" action,
 * the confirmation before a rule is deleted, and a schedule with an amount range.
 */
@RunWith(AndroidJUnit4::class)
class RuleAndScheduleAuthoringFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private val clearedCondition = "cleared is true"

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

    /** Whether the rules list shows the new rule's condition, scrolling the lazy list to it. */
    private fun clearedRuleListed(): Boolean =
        compose.onAllNodesWithTag("rules-screen").fetchSemanticsNodes().isNotEmpty() &&
            runCatching { compose.onNodeWithTag("rules-screen").performScrollToNode(hasText(clearedCondition, substring = true)) }.isSuccess &&
            compose.onAllNodes(hasText(clearedCondition, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** Adds "cleared is true -> set amount -12.50" through the editor and waits for it in the list. */
    private fun createClearedRule() {
        openSettingsPage("show-rules", "rule-add")
        compose.onNodeWithTag("rule-add").performClick()
        compose.waitForTag("rule-editor")

        compose.onNodeWithTag("rule-condition-0-field").performScrollTo().performClick()
        compose.waitForTag("picker-search")
        compose.onNodeWithTag("picker-search").performTextInput("Cleared")
        compose.waitForTag("pick-cleared")
        compose.onNodeWithTag("pick-cleared").performClick()
        compose.waitForTag("rule-condition-0-value")

        compose.onNodeWithTag("rule-action-0-field").performScrollTo().performClick()
        compose.waitForTag("pick-amount")
        compose.onNodeWithTag("pick-amount").performClick()
        compose.waitForTag("rule-action-0-text")
        compose.onNodeWithTag("rule-action-0-text").performScrollTo().performTextInput("-12.50")

        compose.onNodeWithTag("rule-save").performScrollTo().performClick()
        compose.waitOrCapture("cleared-rule-listed") { clearedRuleListed() }
    }

    @Test
    fun aClearedConditionAndSetAmountActionAreSavedAndListed() {
        openDemo()
        createClearedRule()
        compose.waitOrCapture("set-amount-listed") { compose.onAllNodes(hasText("set amount", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("40-cleared-rule")
    }

    @Test
    fun deletingARuleAsksFirst() {
        openDemo()
        createClearedRule()
        compose.onNode(hasText(clearedCondition, substring = true)).performClick()
        compose.waitForTag("rule-editor")

        // Cancel keeps the rule and the editor.
        compose.onNodeWithTag("rule-delete").performScrollTo().performClick()
        compose.waitForTag("rule-delete-confirm")
        compose.onNodeWithTag("rule-delete-cancel").performClick()
        compose.waitForGone("rule-delete-confirm")
        compose.onNodeWithTag("rule-editor").assertExists()

        // Confirming deletes it.
        compose.onNodeWithTag("rule-delete").performScrollTo().performClick()
        compose.waitForTag("rule-delete-confirm")
        compose.onNodeWithTag("rule-delete-confirm").performClick()
        compose.waitForGone("rule-editor")
        compose.waitOrCapture("rule-deleted") {
            compose.onAllNodesWithTag("rules-screen").fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithTag("rules-screen").performScrollToNode(hasText(clearedCondition, substring = true)) }.isFailure
        }
    }

    @Test
    fun aScheduleWithAnAmountRangeIsSavedAndDescribed() {
        openDemo()
        openSettingsPage("show-schedules", "schedules-screen")
        compose.waitForTag("schedule-add")
        compose.onNodeWithTag("schedule-add").performClick()
        compose.waitForTag("schedule-dialog")
        compose.onNodeWithTag("schedule-name").performTextInput("Synthetic Range")
        compose.onNodeWithTag("schedule-account").performClick()
        compose.waitForTag("pick-checking")
        compose.onNodeWithTag("pick-checking").performClick()

        // The amount-type chips sit in a horizontal row inside the dialog's scrolling column: invoke directly.
        runCatching { compose.onNodeWithTag("schedule-type-range").performScrollTo() }
        compose.onNodeWithTag("schedule-type-range").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForTag("schedule-amount-upper")
        compose.onNodeWithTag("schedule-amount").performScrollTo().performTextInput("40.00")
        compose.onNodeWithTag("schedule-amount-upper").performScrollTo().performTextInput("20.00")
        runCatching { compose.onNodeWithTag("schedule-frequency-ONCE").performScrollTo() }
        compose.onNodeWithTag("schedule-frequency-ONCE").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("schedule-save").performClick()
        compose.waitForGone("schedule-dialog")

        // Search narrows the list to the new schedule; its card shows the range as "low – high".
        val row = SemanticsMatcher("a schedule row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("schedule-open-") == true
        }
        compose.onNodeWithTag("schedule-search").performTextInput("Synthetic Range")
        compose.waitOrCapture("range-schedule-listed") { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.waitOrCapture("range-described") { compose.onAllNodes(hasText("–", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("41-range-schedule")

        // The detail page describes it the same way.
        compose.onAllNodes(row)[0].performClick()
        compose.waitForTag("schedule-detail")
        compose.waitOrCapture("detail-range") { compose.onAllNodes(hasText("–", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("schedule-detail-close").performClick()
        compose.waitForGone("schedule-detail")
    }
}
