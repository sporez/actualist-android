package com.sporez.actualist.android.automation

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The "Allow automation" choice, kept in its own preferences file. Off by default, like Actualist's "Allow Shortcuts & Siri" gate. */
object AutomationPrefs {
    private const val FILE = "automation"
    private const val ALLOWED = "allowed"

    fun isAllowed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ALLOWED, false)

    fun setAllowed(context: Context, allowed: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ALLOWED, allowed).apply()
    }
}

/** Settings row: lets Tasker, Routines and other apps read balances and log transactions through the automation intents. */
@Composable
fun AutomationSetting() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AutomationPrefs.isAllowed(context)) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("automation-setting"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Allow Automation")
            Text(
                "Tasker, Routines and other apps can read balances and log transactions in the selected budget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = allowed,
            onCheckedChange = {
                allowed = it
                AutomationPrefs.setAllowed(context, it)
            },
            modifier = Modifier.testTag("automation-toggle"),
        )
    }
}
