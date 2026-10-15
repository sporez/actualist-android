package com.sporez.actualist.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.data.Settings
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Sample values on: the budget shows made-up amounts, notes are hidden and a note write is refused. */
@RunWith(AndroidJUnit4::class)
class SampleValuesFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun toBudgetText(): String? =
        runCatching {
            compose.onNodeWithTag("to-budget-amount").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        }.getOrNull()

    @Test
    fun sampleValuesChangeAmountsHideNotesAndBlockWrites() {
        val settings = (compose.activity.application as ActualistApplication).container.settings
        try {
            compose.waitForTag("onboarding")
            compose.enterDemo()
            compose.waitForTag("to-budget-banner")
            compose.waitOrCapture("real-amount") { toBudgetText() != null }
            val real = toBudgetText()

            settings.update { it.copy(sampleValues = true) }
            compose.waitOrCapture("sample-amount") { val shown = toBudgetText(); shown != null && shown != real }
            assertNotEquals(real, toBudgetText())

            compose.onNodeWithTag("budget-actions").performClick()
            compose.waitForTag("action-notes")
            compose.onNodeWithTag("action-notes").performClick()
            compose.waitForTag("notes-hidden")
            compose.onNode(hasText("Notes are hidden while Sample Values is on.")).assertExists()
            compose.onNode(hasText("Turn off Sample Values to view or edit this note.")).assertExists()
            compose.onNodeWithTag("month-note-save").assertIsNotEnabled()
        } finally {
            settings.update { Settings() }
        }
    }
}
