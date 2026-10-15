package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Budget screen extras on the demo budget: hold preview, move money direction, amount arithmetic and Markdown notes. */
@RunWith(AndroidJUnit4::class)
class BudgetExtrasFlowTest {
    private val fresh = FreshAppState()
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(fresh).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    private fun cents(text: String): Long = text.replace("$", "").replace(",", "").toBigDecimal().movePointRight(2).toLong()

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun openVacation() {
        compose.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("category-vacation"))
        compose.onNodeWithTag("category-vacation").performClick()
        compose.waitForTag("category-sheet")
    }

    @Test
    fun holdShowsTheResultBeforeConfirming() {
        openDemo()
        compose.onNodeWithTag("to-budget-banner").performClick()
        compose.waitForTag("to-budget-sheet")
        compose.onNodeWithTag("to-budget-amount-field").performTextInput("1+1")
        compose.waitForTag("to-budget-amount-field-result")
        click("hold-button")
        compose.waitForTag("hold-sheet")
        compose.waitForTag("hold-result-held")
        val available = cents(textOf("hold-current-available"))
        assertTrue("the demo month has money to hold", available >= 200)
        assertEquals(available - 200, cents(textOf("hold-result-available")))
        assertEquals("$2.00", textOf("hold-result-held"))
        compose.screenshot("extras-hold-preview")
        click("hold-confirm")
        compose.waitForGone("hold-sheet")
        compose.waitOrCapture("held") { compose.onAllNodes(androidx.compose.ui.test.hasText("Held $2.00")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun moveMoneyDirectionSwapsTheResultingBalances() {
        openDemo()
        openVacation()
        compose.onNodeWithTag("move-button").performScrollTo()
        click("move-button")
        compose.waitForTag("move-money-sheet")
        assertEquals("Move to", textOf("move-direction"))
        compose.onNodeWithTag("move-amount-field").performTextInput("10+5")
        compose.waitForTag("move-result-focused")
        val outgoing = cents(textOf("move-result-focused"))
        click("move-swap")
        compose.waitOrCapture("swapped") { textOf("move-direction") == "Move from" }
        val incoming = cents(textOf("move-result-focused"))
        assertEquals(3000L, incoming - outgoing)
        compose.screenshot("extras-move-swapped")
        click("move-confirm")
        compose.waitForGone("move-money-sheet")
    }

    @Test
    fun amountFieldsEvaluateArithmetic() {
        openDemo()
        openVacation()
        compose.onNodeWithTag("assign-field").performTextClearance()
        compose.onNodeWithTag("assign-field").performTextInput("120+35.5")
        compose.waitForTag("assign-field-result")
        compose.onNodeWithTag("assign-field-result", useUnmergedTree = true).assertTextContains("155.50", substring = true)
        click("assign-save")
        compose.waitForGone("category-sheet")
        compose.onNodeWithTag("category-vacation").performClick()
        compose.waitForTag("category-sheet")
        compose.onNodeWithTag("assign-field").assertTextContains("155.50")
    }

    @Test
    fun notesRenderMarkdown() {
        openDemo()
        openVacation()
        compose.waitForTag("category-notes")
        compose.onNodeWithTag("category-notes").performScrollTo()
        compose.onNodeWithTag("category-notes").performTextInput("**Bold** and *soft* note")
        compose.onNodeWithTag("category-notes-rendered").performScrollTo()
        compose.onNodeWithTag("category-notes-rendered").assertTextEquals("Bold and soft note")
        compose.onNodeWithTag("notes-markdown-help").performScrollTo()
        click("notes-markdown-help")
        compose.waitForTag("notes-markdown-examples")
        compose.screenshot("extras-markdown-note")
        assertNotEquals("", textOf("category-notes-rendered"))
    }
}
