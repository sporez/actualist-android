package com.sporez.actualist.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Actualist's idle window for opening a budget before it says the open is taking too long. */
const val BUDGET_OPEN_STALL_MILLIS = 60_000L

const val BUDGET_OPEN_STALL_MESSAGE =
    "Opening this budget is taking too long. Check your connection to the Actual server and try again."

/**
 * Actualist's open watchdog: while [active], a minute with no end shows [BUDGET_OPEN_STALL_MESSAGE]
 * and a Cancel button that runs [onCancel] (which should cancel the coroutine doing the open).
 */
@Composable
fun BudgetOpenWatchdog(active: Boolean, onCancel: () -> Unit, stallMillis: Long = BUDGET_OPEN_STALL_MILLIS) {
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        stalled = false
        if (!active) return@LaunchedEffect
        delay(stallMillis)
        stalled = true
    }
    if (active && stalled) {
        Column(Modifier.fillMaxWidth().testTag("budget-open-stalled"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(BUDGET_OPEN_STALL_MESSAGE, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("budget-open-cancel")) { Text("Cancel") }
        }
    }
}
