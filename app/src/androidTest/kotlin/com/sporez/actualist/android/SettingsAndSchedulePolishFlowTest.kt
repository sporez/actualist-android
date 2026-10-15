package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
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

/** Schedule validation messages, the template apply preview's per-category detail and the diagnostic report, on the demo budget. */
@RunWith(AndroidJUnit4::class)
class SettingsAndSchedulePolishFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

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

    private fun textShown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun anOccurrenceCountOfZeroShowsTheValidationMessage() {
        openDemo()
        openSettingsPage("show-schedules", "schedules-screen")
        compose.waitForTag("schedule-add")
        compose.onNodeWithTag("schedule-add").performClick()
        compose.waitForTag("schedule-dialog")
        compose.onNodeWithTag("schedule-name").performTextInput("Synthetic Count")
        compose.onNodeWithTag("schedule-account").performClick()
        compose.waitForTag("pick-checking")
        compose.onNodeWithTag("pick-checking").performClick()
        compose.onNodeWithTag("schedule-amount").performScrollTo().performTextInput("12.00")
        runCatching { compose.onNodeWithTag("schedule-end-count").performScrollTo() }
        compose.waitForTag("schedule-end-count")
        compose.onNodeWithTag("schedule-end-count").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForTag("schedule-end-value")
        compose.onNodeWithTag("schedule-end-value").performScrollTo().performTextInput("0")
        compose.waitOrCapture("count-message") { textShown("Enter a number of occurrences greater than zero.") }
        compose.screenshot("schedule-count-validation")
    }

    @Test
    fun theTemplateApplyPreviewShowsEachCategoryWithItsFundingStatus() {
        openDemo()
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-dining"))
        compose.onNodeWithTag("category-dining").performClick()
        compose.waitForTag("category-template")
        compose.onNodeWithTag("category-template").performClick()
        compose.waitForTag("template-editor")
        compose.waitForTag("template-amount")
        compose.onNodeWithTag("template-amount").performScrollTo().performTextInput("80.00")
        compose.onNodeWithTag("template-save").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("month-next").performClick()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("templates-open")
        compose.onNodeWithTag("templates-open").performClick()
        compose.waitForTag("templates-fill-empty")
        compose.waitOrCapture("preview-row") { compose.onAllNodesWithTag("templates-preview-row-dining").fetchSemanticsNodes().isNotEmpty() }
        compose.waitOrCapture("preview-status") { textShown("Fully funded", substring = true) }
        compose.screenshot("template-apply-preview-detail")
    }

    @Test
    fun theDiagnosticReportListsThePendingCount() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("show-diagnostics").performScrollTo().performClick()
        compose.waitForTag("diagnostics-text")
        compose.waitOrCapture("pending-line") { textShown("Pending local messages:", substring = true) }
        compose.waitOrCapture("background-section") { textShown("[Background Run History]", substring = true) }
    }
}
