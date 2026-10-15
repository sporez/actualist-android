@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.android.data.LocalBudget
import com.sporez.actualist.android.ui.AppViewModel
import com.sporez.actualist.core.demo.DemoBudget

private fun Context.findActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Actualist's "Change budget": a button that opens a sheet of the budgets on this device,
 * with the demo budget and a way to open another budget from the server.
 */
@Composable
fun SettingsBudgetPicker(currentBudgetId: String, onOpenServer: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    // The activity's own instance, the one that owns the open session.
    val app: AppViewModel = viewModel(viewModelStoreOwner = activity)
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("change-budget")) { Text("Change budget") }
    if (!open) return
    val container = (context.applicationContext as ActualistApplication).container
    var budgets by remember { mutableStateOf(container.library.list().filter { !it.isDemo }) }
    var deleting by remember { mutableStateOf<LocalBudget?>(null) }
    var deletingPending by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("budget-picker"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Change budget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text("On this device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            for (budget in budgets) {
                val selected = budget.id == currentBudgetId
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selected) "${budget.name} · open" else budget.name,
                        modifier = Modifier.weight(1f).clickable {
                            open = false
                            if (!selected) app.openBudget(budget.id)
                        }.padding(horizontal = 20.dp, vertical = 14.dp).testTag("picker-budget-${budget.id}"),
                    )
                    TextButton(
                        onClick = {
                            deletingPending = if (selected) {
                                runCatching { (app.state.value as? com.sporez.actualist.android.ui.AppState.Open)?.session?.database?.pendingLocalMessageCount() }.getOrNull() ?: 0
                            } else {
                                container.library.pendingChangeCount(budget.id)
                            }
                            deleting = budget
                        },
                        modifier = Modifier.testTag("picker-delete-${budget.id}"),
                    ) { Text("Delete") }
                }
            }
            Text(
                if (currentBudgetId == DemoBudget.FILE_ID) "Demo budget · open" else "Demo budget",
                modifier = Modifier.fillMaxWidth().clickable {
                    open = false
                    if (currentBudgetId != DemoBudget.FILE_ID) app.openDemo()
                }.padding(horizontal = 20.dp, vertical = 14.dp).testTag("picker-demo"),
            )
            Text(
                "Open another budget",
                modifier = Modifier.fillMaxWidth().clickable {
                    open = false
                    onOpenServer()
                }.padding(horizontal = 20.dp, vertical = 14.dp).testTag("picker-server"),
            )
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    localDataLossMessage("Actualist will delete this budget and its saved encryption key from this device.", deletingPending),
                    modifier = Modifier.testTag("picker-delete-message"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        if (target.id == currentBudgetId) {
                            // Leave the budget screens first, then close the held session and delete the files.
                            open = false
                            app.closeBudget()
                            container.sessions.closeAll()
                        }
                        container.server.deleteLocalBudget(target.id)
                        budgets = container.library.list().filter { !it.isDemo }
                    },
                    modifier = Modifier.testTag("picker-delete-confirm"),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
