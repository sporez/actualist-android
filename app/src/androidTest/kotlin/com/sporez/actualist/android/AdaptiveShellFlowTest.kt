package com.sporez.actualist.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The adaptive shell: a side rail on wide windows, and the budget picker in Settings. */
@RunWith(AndroidJUnit4::class)
class AdaptiveShellFlowTest {
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
    fun wideWindowsNavigateThroughTheRail() {
        openDemo()
        compose.onNodeWithTag("nav-rail").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitForTag("nav-rail")
            compose.onNodeWithTag("bottom-bar").assertDoesNotExist()
            compose.screenshot("34-nav-rail")
            compose.onNodeWithTag("tab-spending").performClick()
            compose.waitForTag("transaction-list")
            compose.onNodeWithTag("tab-accounts").performClick()
            compose.waitForTag("budget-accounts")
            compose.onNodeWithTag("rail-settings").performClick()
            compose.waitForTag("settings-screen")
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        compose.waitForTag("settings-screen")
    }

    @Test
    fun budgetPickerListsTheDemoAndKeepsItOpen() {
        openDemo()
        compose.onNodeWithTag("open-settings").performClick()
        compose.waitForTag("change-budget")
        compose.onNodeWithTag("change-budget").performClick()
        compose.waitForTag("budget-picker")
        compose.waitForTag("picker-demo")
        compose.waitForTag("picker-server")
        compose.screenshot("35-budget-picker")
        compose.onNodeWithTag("picker-demo").performClick()
        compose.waitForGone("budget-picker")
        compose.waitForTag("settings-screen")
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
    }
}
