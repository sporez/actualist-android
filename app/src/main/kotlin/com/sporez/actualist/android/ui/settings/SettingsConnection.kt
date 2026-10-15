package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Actualist's "Last Synced" and "Pending Sync" rows. */
@Composable
fun SettingsSyncRows(status: SyncStatus) {
    val lastSynced = when (status) {
        is SyncStatus.Idle -> status.lastSyncedAtMillis
        is SyncStatus.Synced -> status.atMillis
        else -> null
    }
    val pending = when (status) {
        is SyncStatus.Idle -> status.pendingChanges
        is SyncStatus.Failed -> status.pendingChanges
        else -> 0
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Last synced")
        Text(
            lastSynced?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "Never",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("last-synced"),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Pending sync")
        Text(
            if (pending == 0) "Nothing waiting" else "$pending change(s)",
            color = if (pending == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("pending-sync"),
        )
    }
}

/** Edit the custom HTTP headers sent to the signed-in server (Actualist's Custom Headers), tested before they are saved. */
@Composable
fun SettingsCustomHeaders(server: ServerConnection, serverUrl: String) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(server.savedCustomHeaders()) }
    var editing by remember { mutableStateOf(false) }
    val count = saved?.second?.lines()?.count { it.isNotBlank() } ?: 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Custom headers")
            Text(
                if (count == 0) "None" else "$count header(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("custom-headers-summary"),
            )
        }
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-custom-headers")) { Text("Edit") }
    }
    if (editing) {
        var rows by remember { mutableStateOf(HeaderDraft.fromText(saved?.second ?: "")) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val addressChanged = saved?.first?.let { !ServerHeaderScope.sameOrigin(it, serverUrl) } ?: false
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text("Custom headers") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(serverUrl, style = MaterialTheme.typography.bodySmall)
                    if (addressChanged) {
                        Text(
                            "The server address changed. These saved headers are not being sent to this server until you save them again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Headers are sent with requests to this server. Values are stored securely on this device. Remove every header to stop sending them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HeaderRowsEditor(rows) { rows = it; error = null }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("custom-headers-error")) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                server.saveCustomHeadersTested(serverUrl, HeaderDraft.validated(rows))
                                saved = server.savedCustomHeaders()
                                editing = false
                            } catch (failure: Exception) {
                                error = failure.message ?: "Those headers are not valid."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("custom-headers-save"),
                ) { Text(if (busy) "Checking" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

/** Whether two server addresses share an origin, which is what scopes saved headers. */
object ServerHeaderScope {
    fun sameOrigin(saved: String, current: String): Boolean = runCatching {
        com.sporez.actualist.core.server.EndpointHeaders(saved, emptyList()).appliesToUrl(current)
    }.getOrDefault(false)
}
