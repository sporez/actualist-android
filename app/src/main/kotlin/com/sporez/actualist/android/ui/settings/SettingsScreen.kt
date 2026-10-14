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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (server.isSignedIn) {
                    Text(current.serverUrl ?: "", modifier = Modifier.testTag("server-url"))
                    Row {
                        TextButton(onClick = onConnectServer) { Text("Open another budget") }
                        TextButton(onClick = { server.signOut() }, modifier = Modifier.testTag("sign-out")) { Text("Sign out") }
                    }
                } else {
                    Text("Not connected. Connect to your own Actual server to sync a real budget.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onConnectServer, modifier = Modifier.testTag("connect-server")) { Text("Connect to a server") }
                }
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (mode in ThemeMode.entries) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current.themeMode == mode, onClick = { settings.update { it.copy(themeMode = mode) } }, modifier = Modifier.testTag("theme-${mode.name.lowercase()}"))
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide amounts")
                        Text("Show bullets instead of money, for screenshots", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = current.hideAmounts, onCheckedChange = { value -> settings.update { it.copy(hideAmounts = value) } }, modifier = Modifier.testTag("hide-amounts"))
                }
            }
        }
        SectionCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("About", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Actualist for Android — experimental port of Actualist (GPLv3). Not a release.")
                Text("Compatible with Actual Budget's sync protocol. Actual is MIT licensed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
