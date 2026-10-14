package com.sporez.actualist.android

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The template editor's weekly and early-spending options, the apply modes and the templates browser, on the demo budget. */
@RunWith(AndroidJUnit4::class)
class TemplateEditorFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose)

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    private fun openTemplateEditor(categoryTag: String) {
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag(categoryTag))
        compose.onNodeWithTag(categoryTag).performClick()
        compose.waitForTag("category-template")
        compose.onNodeWithTag("category-template").performClick()
        compose.waitForTag("template-editor")
    }

    private fun scrolledClick(tag: String) {
        compose.waitForTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun scrolledInput(tag: String, text: String) {
        compose.waitForTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun saveWeeklyWithEarlySpending() {
        openTemplateEditor("category-dining")
        scrolledInput("template-amount", "25.00")
        scrolledClick("template-cadence-week")
        scrolledClick("template-add-entry")
        compose.waitForTag("template-kind-dateTarget-1")
        scrolledClick("template-kind-dateTarget-1")
        scrolledInput("template-amount-1", "600.00")
        scrolledClick("template-early-spending-1")
        compose.waitForTag("template-spend-start-1")
        compose.onNodeWithTag("template-save").performClick()
        compose.waitForGone("category-sheet")
    }

    @Test
    fun weeklyTemplateWithEarlySpendingIsSavedAndReopened() {
        openDemo()
        saveWeeklyWithEarlySpending()
        openTemplateEditor("category-dining")
        // The early-spending start only shows when the option was stored.
        compose.waitForTag("template-spend-start-1")
        compose.onNodeWithTag("template-cadence-week").performScrollTo().assertExists()
        compose.screenshot("template-weekly-early-spending")
    }

    @Test
    fun fillEmptyAppliesTheTemplateAndOverwriteIsAChoice() {
        openDemo()
        openTemplateEditor("category-dining")
        scrolledInput("template-amount", "80.00")
        compose.onNodeWithTag("template-save").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("month-next").performClick()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("templates-open")
        compose.onNodeWithTag("templates-open").performClick()
        compose.waitForTag("templates-fill-empty")
        compose.waitOrCapture("fill-empty-preview") { compose.onAllNodes(hasText("$0.00 → $80.00", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("templates-overwrite").performClick()
        compose.onNodeWithTag("templates-fill-empty").performClick()
        compose.waitOrCapture("fill-empty-again") { compose.onAllNodes(hasText("$0.00 → $80.00", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("templates-apply").performClick()
        compose.waitOrCapture("fill-empty-applied") { compose.onAllNodes(hasText("$80.00")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun browserListsCategoriesWithTemplates() {
        openDemo()
        openTemplateEditor("category-dining")
        scrolledInput("template-amount", "80.00")
        compose.onNodeWithTag("template-save").performClick()
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("templates-browse")
        compose.onNodeWithTag("templates-browse").performClick()
        compose.waitForTag("templates-browser")
        compose.waitOrCapture("browser-row") { exists("template-row-dining") }
        compose.onNodeWithTag("templates-add").performClick()
        compose.waitForIdle()
        // A category that already has a template is not offered again.
        assertTrue(!exists("pick-dining"))
        compose.screenshot("template-browser")
    }
}
