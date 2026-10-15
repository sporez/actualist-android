package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reports.ReportOrderStore
import com.sporez.actualist.core.reports.ReportCardOrder

private val chartTitles = mapOf(
    "net-worth" to "Net Worth",
    "cash-flow" to "Cash Flow",
    "month-comparison" to "This Month",
    "spending" to "Spending by Category",
    "budget-comparison" to "Budget vs Spent",
    "three-month-average" to "3-Month Average",
    "transaction-calendar" to "Transaction Calendar",
)

/** Settings → Reports → Chart Order: the Reports screen's card order, from the same store (Actualist's ReportsSettingsView). */
@Composable
fun SettingsReportOrder() {
    val context = LocalContext.current.applicationContext
    val store = remember { ReportOrderStore(context) }
    var open by rememberSaveable { mutableStateOf(false) }
    var order by remember { mutableStateOf(store.load()) }
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Text("Reports", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chart order")
                    Text("Change the order of cards on the Reports screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { order = store.load(); open = true }, modifier = Modifier.testTag("settings-chart-order")) { Text("Edit") }
            }
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Chart Order") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("settings-chart-order-list")) {
                    order.forEachIndexed { index, id ->
                        Row(Modifier.fillMaxWidth().testTag("settings-chart-order-row-$id"), verticalAlignment = Alignment.CenterVertically) {
                            Text(chartTitles[id] ?: id, Modifier.weight(1f))
                            TextButton(
                                onClick = { order = ReportCardOrder.move(order, id, -1); store.save(order) },
                                enabled = index > 0,
                                modifier = Modifier.testTag("settings-chart-order-up-$id"),
                            ) { Text("Up") }
                            TextButton(
                                onClick = { order = ReportCardOrder.move(order, id, 1); store.save(order) },
                                enabled = index < order.lastIndex,
                                modifier = Modifier.testTag("settings-chart-order-down-$id"),
                            ) { Text("Down") }
                        }
                    }
                    TextButton(
                        onClick = { store.reset(); order = store.load() },
                        enabled = order != ReportCardOrder.defaults,
                        modifier = Modifier.testTag("settings-chart-order-reset"),
                    ) { Text("Reset Report Order") }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }, modifier = Modifier.testTag("settings-chart-order-done")) { Text("Done") } },
        )
    }
}
