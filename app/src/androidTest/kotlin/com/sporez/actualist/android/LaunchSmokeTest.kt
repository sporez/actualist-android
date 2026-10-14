package com.sporez.actualist.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(FreshAppState()).around(compose)

    @Test
    fun appLaunchesWithoutCredentialsOrServer() {
        compose.waitForTag("onboarding")
        compose.onNodeWithText("Explore the demo budget").assertExists()
        compose.onNodeWithTag("onboarding-connect").assertExists()
    }
}
