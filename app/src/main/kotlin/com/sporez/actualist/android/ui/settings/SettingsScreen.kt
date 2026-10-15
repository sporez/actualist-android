package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.data.ThemeMode
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    session: BudgetSession,
    settings: AppSettings,
    server: ServerConnection,
    onConnectServer: () -> Unit,
    onCloseBudget: () -> Unit,
    onResetDemo: () -> Unit,
    onShowRules: () -> Unit,
    onShowPayees: () -> Unit,
    onShowSchedules: () -> Unit,
    onShowHistory: () -> Unit = {},
    onShowBankSync: () -> Unit = {},
) {
    val current by settings.settings.collectAsState()
    val syncStatus by session.syncStatus.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).testTag("settings-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Budget", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(session.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("settings-budget-name"))
                Text(syncDescription(syncStatus), modifier = Modifier.testTag("sync-status"))
                SettingsSecurityRow(server, session)
                SettingsBudgetPicker(session.budgetId, onConnectServer)
                if (!session.isDemo) SettingsSyncRows(syncStatus)
                if (!session.isDemo) {
                    Button(onClick = { scope.launch { server.sync(session) } }, enabled = syncStatus !is SyncStatus.Syncing, modifier = Modifier.testTag("sync-now")) { Text("Sync now") }
                } else {
                    Text("Demo data is synthetic and never leaves this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onResetDemo, modifier = Modifier.testTag("reset-demo")) { Text("Reset demo budget") }
                }
                Row {
                    TextButton(onClick = onShowRules, modifier = Modifier.testTag("show-rules")) { Text("Rules") }
                    TextButton(onClick = onShowPayees, modifier = Modifier.testTag("show-payees")) { Text("Payees") }
                    TextButton(onClick = onShowSchedules, modifier = Modifier.testTag("show-schedules")) { Text("Schedules") }
                    TextButton(onClick = onCloseBudget, modifier = Modifier.testTag("close-budget")) { Text("Close budget") }
                }
                Row {
                    TextButton(onClick = onShowHistory, modifier = Modifier.testTag("show-history")) { Text("History (undo and redo)") }
                    TextButton(onClick = onShowBankSync, modifier = Modifier.testTag("show-bank-sync")) { Text("Bank sync") }
                }
                ExportBudgetButton(session)
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (server.isSignedIn) {
                    Text(current.serverUrl ?: "", modifier = Modifier.testTag("server-url"))
                    SettingsConnectionEditor(server, current.serverUrl ?: "")
                    if (!session.isDemo) SettingsConnectionStatus(server, session)
                    SettingsCustomHeaders(server, current.serverUrl ?: "")
                    SettingsFallbackServer(server, current.serverUrl ?: "")
                    Row {
                        TextButton(onClick = onConnectServer) { Text("Open another budget") }
                        SettingsSignInAgain(server, session)
                    }
                    SettingsSignOut(server, session)
                } else {
                    Text("Not connected. Connect to your own Actual server to sync a real budget.", style = MaterialTheme.typography.bodyMedium)
                    if (current.serverUrl != null) {
                        val secrets = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.sporez.actualist.android.ActualistApplication).container.secrets
                        SettingsCredentialsUnavailable(secrets) { settings.update { it.copy() } }
                        SettingsSignInAgain(server, session)
                    }
                    OutlinedButton(onClick = onConnectServer, modifier = Modifier.testTag("connect-server")) { Text("Connect to a server") }
                }
            }
        }
        SettingsAppearanceCard(settings)
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("Privacy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("App switcher", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                for (mode in com.sporez.actualist.android.data.AppSwitcherPrivacy.entries) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current.appSwitcherPrivacy == mode, onClick = { settings.update { it.copy(appSwitcherPrivacy = mode) } }, modifier = Modifier.testTag("privacy-${mode.name.lowercase()}"))
                        Column {
                            Text(mode.title)
                            Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use sample values")
                        Text("Show made-up amounts instead of real ones, for screenshots", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = current.sampleValues, onCheckedChange = { value -> settings.update { it.copy(sampleValues = value) } }, modifier = Modifier.testTag("sample-values"))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                com.sporez.actualist.android.notifications.NewTransactionAlertsSetting()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                com.sporez.actualist.android.sync.BackgroundBankSyncSetting()
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                com.sporez.actualist.android.automation.AutomationSetting()
            }
        }
        BudgetDisplayCard(settings)
        SettingsBudgetDataCard(session, settings, server)
        RolloverAllCard(session)
        SettingsEraseCard(session)
        SettingsAboutCard(session, settings, server.isSignedIn)
        SettingsSupportCard(session, settings, server.isSignedIn)
    }
}

fun syncDescription(status: SyncStatus): String = when (status) {
    SyncStatus.LocalOnly -> "Local demo · changes stay on this device"
    SyncStatus.Syncing -> "Syncing…"
    is SyncStatus.Idle -> buildString {
        append(if (status.pendingChanges == 0) "Up to date locally" else "${status.pendingChanges} change(s) waiting to upload")
        status.lastSyncedAtMillis?.let { append(" · last synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))}") }
    }
    is SyncStatus.Synced -> "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.atMillis))} · ${status.uploaded} sent, ${status.applied} received"
    is SyncStatus.Failed -> "Sync failed: ${status.message}" + if (status.pendingChanges > 0) " · ${status.pendingChanges} change(s) kept locally" else ""
}


/** Actualist's Budget display preferences: totals, rollover badges, month swiping and how many months show on wide screens. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDisplayCard(settings: AppSettings) {
    val current by settings.settings.collectAsState()
    @Composable
    fun toggle(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
        }
    }
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Budget display", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            toggle("Show total assigned", "Shows the month's assigned total under the title", current.showTotalAssigned, "show-total-assigned") { value ->
                settings.update { it.copy(showTotalAssigned = value) }
            }
            toggle("Hide carryover arrows", "No rollover badge on available amounts", current.hideCarryoverArrows, "hide-carryover-arrows") { value ->
                settings.update { it.copy(hideCarryoverArrows = value) }
            }
            toggle("Swipe between months", "Swipe sideways on the budget to change month", current.swipeBetweenMonths, "swipe-between-months") { value ->
                settings.update { it.copy(swipeBetweenMonths = value) }
            }
            toggle("Include rollover in alerts", "Count rollover categories in overspent alerts and the cover review", current.includeRolloverInAlerts, "include-rollover-in-alerts") { value ->
                settings.update { it.copy(includeRolloverInAlerts = value) }
            }
            HorizontalDivider()
            Text("Months shown on wide screens")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (count in 0..5) {
                    androidx.compose.material3.FilterChip(
                        selected = current.monthsShown == count,
                        onClick = { settings.update { it.copy(monthsShown = count) } },
                        label = { Text(if (count == 0) "Auto" else "$count") },
                        modifier = Modifier.testTag("months-shown-$count"),
                    )
                }
            }
        }
    }
}

/** Exports the open budget as a ZIP (a consistent, sanitized snapshot), as Actualist's Export does. */
@Composable
private fun ExportBudgetButton(session: BudgetSession) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = try {
                    val bytes = session.read {
                        com.sporez.actualist.core.server.PortableBudget.exportArchive(
                            database, name, androidx.sqlite.driver.bundled.BundledSQLiteDriver(), java.io.File(context.cacheDir, "portable-export"),
                        )
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("The file could not be written.")
                    }
                    "Exported ${bytes.size / 1024} KB"
                } catch (error: Exception) {
                    error.message ?: "The budget could not be exported."
                }
            }
        }
    }
    TextButton(onClick = { launcher.launch("${session.name}.zip") }, modifier = Modifier.testTag("export-budget")) { Text("Export budget (ZIP)") }
    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export-status")) }
}

/**
 * Actualist's "Rollover All Overspending": carryover on or off for every expense category,
 * hidden ones included, from this month forward.
 */
@Composable
private fun RolloverAllCard(session: BudgetSession) {
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    val month = remember { java.time.YearMonth.now().toString() }
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        counts = runCatching {
            session.read {
                val categories = budget.fetchBudgetMonth(month).categoryGroups.filter { !it.isIncome }.flatMap { it.categories }
                categories.count { it.carryover } to categories.size
            }
        }.getOrNull()
    }
    val (enabled, total) = counts ?: (0 to 0)
    val detail = when {
        total == 0 -> "No expense categories"
        enabled == total -> "On for all $total categories"
        enabled == 0 -> "Off for all $total categories"
        else -> "On for $enabled of $total categories"
    }
    SectionCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rollover all overspending")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("rollover-all-detail"))
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            Switch(checked = total > 0 && enabled == total, onCheckedChange = { confirming = true }, enabled = counts != null && total > 0, modifier = Modifier.testTag("rollover-all"))
        }
    }
    if (confirming) {
        fun applyAll(carryover: Boolean) {
            confirming = false
            scope.launch {
                error = runCatching { session.write { budgetWrites.setAllExpenseCarryover(carryover, month, month) } }.exceptionOrNull()?.let { it.message ?: "Rollover could not be changed." }
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Set rollover for all categories?") },
            text = { Text("Changes $total expense categories from ${com.sporez.actualist.android.ui.budget.monthTitle(month)} forward, including hidden categories. Existing transactions and assignments are not deleted.") },
            confirmButton = {
                TextButton(onClick = { applyAll(true) }, enabled = enabled < total, modifier = Modifier.testTag("rollover-all-on")) { Text("Turn on for all") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { applyAll(false) }, enabled = enabled > 0, modifier = Modifier.testTag("rollover-all-off")) { Text("Turn off for all") }
                    TextButton(onClick = { confirming = false }) { Text("Cancel") }
                }
            },
        )
    }
}
