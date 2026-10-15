package com.sporez.actualist.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sporez.actualist.android.data.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Actualist's wording: the Sample Values hold guard and the Select Transactions mode. */
@RunWith(AndroidJUnit4::class)
class CopyParityFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(testTimeout()).around(FreshAppState()).around(compose).around(FailureScreenshot())

    @Test
    fun holdIsRefusedWhileSampleValuesIsOn() {
        val settings = (compose.activity.application as ActualistApplication).container.settings
        try {
            compose.waitForTag("onboarding")
            compose.enterDemo()
            compose.waitForTag("to-budget-banner")
            settings.update { it.copy(sampleValues = true) }
            compose.onNodeWithTag("to-budget-banner").performClick()
            compose.waitForTag("to-budget-sheet")
            compose.onNodeWithTag("hold-button").performScrollTo().performClick()
            compose.waitForTag("hold-sample-values")
            compose.onNode(hasText("Turn off Sample Values to hold or release money.")).assertExists()
            compose.onNodeWithTag("hold-confirm").assertIsNotEnabled()
        } finally {
            settings.update { Settings() }
        }
    }

    @Test
    fun selectTransactionsStartsSelectionMode() {
        compose.waitForTag("onboarding")
        compose.enterDemo()
        compose.waitForTag("to-budget-banner")
        compose.onNodeWithTag("tab-spending").performClick()
        compose.waitForTag("transaction-list")
        compose.onNodeWithTag("select-transactions").performClick()
        compose.waitForTag("selection-bar")
        compose.onNodeWithTag("selection-count").assertExists()
        compose.onNode(hasText("Select Transactions")).assertExists()
        compose.onNodeWithTag("batch-cancel").performClick()
        compose.waitForGone("selection-bar")
    }
}
