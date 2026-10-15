package com.sporez.actualist.android

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.data.DisplaySize
import com.sporez.actualist.android.data.NamedTheme
import com.sporez.actualist.android.data.Settings
import com.sporez.actualist.android.ui.components.sampleMinorUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Appearance, sample values, and the in-app diagnostic report. */
@RunWith(AndroidJUnit4::class)
class SettingsAppearanceFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    private fun openDemo() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
    }

    private fun scrollTo(tag: String) {
        compose.waitForTag(tag)
        compose.onNodeWithTag(tag).performScrollTo()
    }

    @Test
    fun switchesThemeDisplaySizeSampleValuesAndShowsDiagnostics() {
        val settings = (compose.activity.application as ActualistApplication).container.settings
        try {
            openDemo()
            compose.onNodeWithTag("open-settings").performClick()
            compose.waitForTag("settings-screen")

            scrollTo("named-theme-ember_amber")
            compose.onNodeWithTag("named-theme-ember_amber").performClick()
            compose.waitOrCapture("named-theme") { settings.settings.value.namedTheme == NamedTheme.EMBER_AMBER }
            scrollTo("named-theme-ember_amber")
            compose.onNodeWithTag("named-theme-ember_amber").assertIsSelected()
            // System/Dark/Light keeps working and clears the named theme.
            scrollTo("theme-dark")
            compose.onNodeWithTag("theme-dark").performClick()
            compose.waitOrCapture("theme-dark") { settings.settings.value.namedTheme == null }
            compose.onNodeWithTag("theme-dark").assertIsSelected()

            scrollTo("display-size-large")
            compose.onNodeWithTag("display-size-large").performClick()
            compose.waitOrCapture("display-size") { settings.settings.value.displaySize == DisplaySize.LARGE }
            compose.waitForTag("settings-screen")
            scrollTo("display-size-label")
            compose.onNodeWithTag("display-size-label").assertExists()

            scrollTo("sample-values")
            compose.onNodeWithTag("sample-values").performClick()
            compose.waitOrCapture("sample-on") { settings.settings.value.sampleValues }
            compose.onNodeWithTag("sample-values").performClick()
            compose.waitOrCapture("sample-off") { !settings.settings.value.sampleValues }

            scrollTo("show-diagnostics")
            compose.onNodeWithTag("show-diagnostics").performClick()
            compose.waitForTag("diagnostics-text")
            compose.onNode(hasTestTag("diagnostics-text") and hasText("Actualist Diagnostic Report", substring = true)).assertExists()
            compose.onNodeWithTag("diagnostics-share").assertExists()
        } finally {
            settings.update { Settings() }
        }
    }

    @Test
    fun sampleValuesKeepSignAndZero() {
        assertEquals(0L, sampleMinorUnits(0L))
        assertTrue(sampleMinorUnits(12_345L) > 0)
        assertTrue(sampleMinorUnits(-12_345L) < 0)
        assertEquals(sampleMinorUnits(777L), sampleMinorUnits(777L))
    }
}
