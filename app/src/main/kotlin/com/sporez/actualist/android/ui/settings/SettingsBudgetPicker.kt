@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sporez.actualist.android.ActualistApplication
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
    val budgets = remember { container.library.list().filter { !it.isDemo } }
    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).testTag("budget-picker"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Change budget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text("On this device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            for (budget in budgets) {
                val selected = budget.id == currentBudgetId
                Text(
                    if (selected) "${budget.name} · open" else budget.name,
                    modifier = Modifier.fillMaxWidth().clickable {
                        open = false
                        if (!selected) app.openBudget(budget.id)
                    }.padding(horizontal = 20.dp, vertical = 14.dp).testTag("picker-budget-${budget.id}"),
                )
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
}
