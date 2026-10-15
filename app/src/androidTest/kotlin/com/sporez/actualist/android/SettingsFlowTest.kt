package com.sporez.actualist.android

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Settings parity with Actualist: app-switcher privacy, rollover for every category, and launcher shortcuts. */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.onNodeWithTag("open-demo").performClick()
        compose.waitForTag("to-budget-banner")
    }

    @Test
    fun setsAppSwitcherPrivacyAndRolloverForAllCategories() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("privacy-when_backgrounded").performScrollTo().performClick()
        compose.onNodeWithTag("privacy-when_backgrounded").assertIsSelected()
        // Back to Off, so later screenshots in this process are not affected.
        compose.onNodeWithTag("privacy-off").performScrollTo().performClick()
        compose.onNodeWithTag("rollover-all").performScrollTo()
        compose.waitOrCapture("rollover-loaded") { compose.onAllNodes(hasTestTag("rollover-all-detail") and hasText("categories", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rollover-all").performClick()
        compose.waitForTag("rollover-all-on")
        compose.onNodeWithTag("rollover-all-on").performClick()
        compose.waitOrCapture("rollover-all-on") { compose.onAllNodes(hasTestTag("rollover-all-detail") and hasText("On for all", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.screenshot("40-settings-privacy-rollover")
        compose.onNodeWithTag("rollover-all").performClick()
        compose.waitForTag("rollover-all-off")
        compose.onNodeWithTag("rollover-all-off").performClick()
        compose.waitOrCapture("rollover-all-off") { compose.onAllNodes(hasTestTag("rollover-all-detail") and hasText("Off for all", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aLauncherShortcutOpensItsScreen() {
        openDemo()
        // What MainActivity does with a shortcut's intent extra.
        AppShortcuts.pending.value = "reports"
        compose.waitForTag("reports-screen")
        AppShortcuts.pending.value = "transaction-new"
        compose.waitForTag("editor-amount")
    }
}
