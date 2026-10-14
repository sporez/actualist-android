package com.sporez.actualist.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.test.platform.app.InstrumentationRegistry
import com.sporez.actualist.android.data.Settings
import org.junit.rules.ExternalResource
import java.io.File

/** Every UI test starts from a fresh install state: no selected budget, no demo file. */
class FreshAppState : ExternalResource() {
    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as ActualistApplication).container
        container.settings.update { Settings() }
        // A previous test's activity may not have finished cleanly: drop its sessions before
        // deleting the files they point at.
        container.sessions.closeAll()
        container.library.list().forEach { container.library.delete(it.id) }
        container.secrets.clear()
    }
}

fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 60_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/** [waitUntil] that saves a screenshot and logs the semantics tree before failing. */
fun ComposeTestRule.waitOrCapture(name: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
    try {
        waitUntil(timeoutMillis, condition)
    } catch (error: Throwable) {
        runCatching { screenshot("failure-$name") }
        runCatching { onRoot().printToLog("failure-$name") }
        throw error
    }
}

fun ComposeTestRule.waitForGone(tag: String, timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
}

/**
 * Saves the screen as a PNG the emulator workflow pulls as an artifact. Compose's own render
 * of the view is used when only the activity window is showing; with a dialog or sheet open
 * (several roots, where `captureToImage` fails) the display is captured through UiAutomation.
 */
fun ComposeTestRule.screenshot(name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = runCatching { onRoot().captureToImage().asAndroidBitmap() }.getOrNull()
        ?: instrumentation.uiAutomation.takeScreenshot()
        ?: return
    save(name, bitmap)
}

/** The display as the system composites it, for comparison with [screenshot]. */
fun ComposeTestRule.displayScreenshot(name: String) {
    waitForIdle()
    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { save(name, it) }
}

private fun save(name: String, bitmap: Bitmap) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
