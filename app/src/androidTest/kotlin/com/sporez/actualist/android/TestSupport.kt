package com.sporez.actualist.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
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
        container.library.list().forEach { container.library.delete(it.id) }
        container.secrets.clear()
    }
}

fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 60_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeTestRule.waitForGone(tag: String, timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
}

/** Saves the current screen as a PNG the emulator workflow pulls as an artifact. */
fun SemanticsNodeInteractionsProvider.screenshot(name: String) {
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
