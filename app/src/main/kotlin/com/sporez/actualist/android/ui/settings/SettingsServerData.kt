package com.sporez.actualist.android.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.android.ui.AppViewModel
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.demo.DemoBudget
import com.sporez.actualist.core.server.ServerFailover
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.findSettingsActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Actualist's confirmation copy for every action that discards a local budget copy (erase,
 * delete, reimport): states how many local changes the server has not confirmed.
 */
fun localDataLossMessage(base: String, pendingChangeCount: Int): String {
    if (pendingChangeCount <= 0) return "$base Your server data is not changed."
    val subject = if (pendingChangeCount == 1) "1 local change has" else "$pendingChangeCount local changes have"
    return "$base Warning: $subject not been confirmed by the server and will be permanently lost."
}

/** The number of local changes of [session] the server has not confirmed yet. */
@Composable
fun rememberPendingChanges(session: BudgetSession): Int {
    val revision by session.revision.collectAsState()
    val status by session.syncStatus.collectAsState()
    var count by remember { mutableStateOf(0) }
    LaunchedEffect(revision, status) {
        count = runCatching { session.read { database.pendingLocalMessageCount() } }.getOrDefault(0)
    }
    return count
}

/** Actualist's connection status (Connected, Checking, Offline, Needs Attention). */
enum class ConnectionState(val label: String) {
    Connected("Connected"),
    Checking("Checking"),
    Offline("Offline"),
    NeedsAttention("Needs attention"),
    NotSynced("Not synced yet"),
}

fun connectionState(needsReauthentication: Boolean, status: SyncStatus): ConnectionState = when {
    needsReauthentication -> ConnectionState.NeedsAttention
    status is SyncStatus.Failed -> ConnectionState.Offline
    status == SyncStatus.Syncing -> ConnectionState.Checking
    status is SyncStatus.Synced -> ConnectionState.Connected
    status is SyncStatus.Idle && status.lastSyncedAtMillis != null -> ConnectionState.Connected
    else -> ConnectionState.NotSynced
}

/** The "Security" row of Budget data: encrypted or not, and whether its key is on this device. */
@Composable
fun SettingsSecurityRow(server: ServerConnection, session: BudgetSession) {
    val status by session.syncStatus.collectAsState()
    val security = remember(session.budgetId, status) { server.securityOf(session.budgetId) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Security")
        Text(
            security.label,
            color = if (security == ServerConnection.BudgetSecurity.Locked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("budget-security"),
        )
    }
    if (security == ServerConnection.BudgetSecurity.Locked) {
        Text(
            "The encryption key is not on this device. Enter the budget password on the Budget screen to unlock it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Actualist's connection status row, with "used fallback" and the last sync error. */
@Composable
fun SettingsConnectionStatus(server: ServerConnection, session: BudgetSession) {
    val status by session.syncStatus.collectAsState()
    val needsReauthentication by server.needsReauthentication.collectAsState()
    val usedFallback by server.usedFallback.collectAsState()
    val state = connectionState(needsReauthentication, status)
    val color = when (state) {
        ConnectionState.Connected -> Color(0xFF2E9E5B)
        ConnectionState.Offline, ConnectionState.NeedsAttention -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Connection")
        Text(
            if (state == ConnectionState.Connected && usedFallback) "${state.label} · used fallback" else state.label,
            color = color,
            modifier = Modifier.testTag("connection-status"),
        )
    }
    (status as? SyncStatus.Failed)?.let {
        Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("connection-error"))
    }
}

/** Actualist's fallback server: shows the address and edits it, with optional custom headers for it. */
@Composable
fun SettingsFallbackServer(server: ServerConnection, primaryUrl: String) {
    var saved by remember { mutableStateOf(server.fallbackUrl) }
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Fallback server")
            Text(
                saved ?: "None",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("fallback-server-summary"),
            )
        }
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-fallback-server")) { Text("Edit") }
    }
    if (editing) {
        var url by remember { mutableStateOf(saved ?: "") }
        var headers by remember { mutableStateOf(server.savedFallbackHeaders() ?: "") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Fallback server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "The fallback server is tried automatically when the primary server can't be reached, for example a VPN address when you're away from home Wi-Fi. Leave empty to remove it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("Fallback server URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("fallback-server-field"),
                    )
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it; error = null },
                        label = { Text("Custom headers for it (optional)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag("fallback-server-headers"),
                    )
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fallback-server-error"))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val normalized = ServerFailover.normalize(url)
                        val same = normalized.isNotEmpty() && normalized.equals(ServerFailover.normalize(primaryUrl), ignoreCase = true)
                        val failure = if (same) "This is the same address as the primary server." else server.saveFallback(url, headers)
                        if (failure == null) {
                            saved = server.fallbackUrl
                            editing = false
                        } else {
                            error = failure
                        }
                    },
                    modifier = Modifier.testTag("fallback-server-save"),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

/** "Sign in again" from Settings (Actualist's reauthentication), reusing the saved server address. */
@Composable
fun SettingsSignInAgain(server: ServerConnection, session: BudgetSession) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("settings-sign-in-again")) { Text("Sign in again") }
    if (open) {
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Sign in again") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(server.serverUrl ?: "", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Server password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("settings-reauth-password"),
                    )
                    Text(
                        "Your password is not stored. Actualist keeps a sync token and only needs the password again to reconnect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && password.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                server.signIn(server.serverUrl ?: throw IllegalStateException("No server is saved."), password)
                                open = false
                                if (!session.isDemo) server.sync(session)
                            } catch (failure: Exception) {
                                error = failure.message ?: "Sign-in failed."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("settings-reauth-submit"),
                ) { Text("Sign in") }
            },
            dismissButton = { TextButton(onClick = { open = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

/** Sign out, confirmed with the number of local changes the server has not received. */
@Composable
fun SettingsSignOut(server: ServerConnection, session: BudgetSession) {
    val pending = rememberPendingChanges(session)
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, modifier = Modifier.testTag("sign-out")) { Text("Sign out") }
    if (confirming) {
        val base = "Signing out removes this device's session. Your server data is not changed."
        val message = if (pending > 0) {
            val subject = if (pending == 1) "1 local change has" else "$pending local changes have"
            "$base Warning: $subject not been confirmed by the server. They stay on this device but will not sync until you sign in again."
        } else {
            base
        }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Sign out?") },
            text = { Text(message, modifier = Modifier.testTag("sign-out-message")) },
            confirmButton = {
                TextButton(onClick = { confirming = false; server.signOut() }, modifier = Modifier.testTag("sign-out-confirm")) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

private fun totalPendingChanges(container: AppContainer, session: BudgetSession): Int =
    container.library.list().sumOf { budget ->
        if (budget.id == session.budgetId) {
            runCatching { session.database.pendingLocalMessageCount() }.getOrDefault(0)
        } else {
            container.library.pendingChangeCount(budget.id)
        }
    }

/**
 * Actualist's "Disconnect & Erase Local Data" (and "Exit Demo Mode" in demo mode): removes the token,
 * headers, encryption keys and local budget files, then returns to onboarding.
 */
@Composable
fun SettingsEraseCard(session: BudgetSession) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as ActualistApplication).container }
    val activity = remember(context) { context.findSettingsActivity() }
    val app = remember(activity) { activity?.let { ViewModelProvider(it)[AppViewModel::class.java] } }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(0) }
    val demo = session.isDemo
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Danger zone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (demo) {
                    "Removes the demo budget from this device and returns to the start screen."
                } else {
                    "Removes the sync token, cached encryption keys, imported budget files, and local selections from this device. Your server data is not changed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        pending = if (demo) 0 else withContext(Dispatchers.IO) { totalPendingChanges(container, session) }
                        confirming = true
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("erase-local-data"),
            ) { Text(if (demo) "Exit demo mode" else "Disconnect & erase local data") }
        }
    }
    if (confirming && app != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (demo) "Exit Demo Mode?" else "Disconnect & Erase Local Data?") },
            text = {
                Text(
                    if (demo) {
                        "Actualist will remove the demo budget and return to onboarding."
                    } else {
                        localDataLossMessage(
                            "Actualist will remove the sync token, cached encryption keys, imported budget files, and local selections from this device.",
                            pending,
                        )
                    },
                    modifier = Modifier.testTag("erase-message"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        // Leave the budget screens first, then close any held session and delete the files.
                        app.closeBudget()
                        container.sessions.closeAll()
                        if (demo) container.server.deleteLocalBudget(DemoBudget.FILE_ID) else container.server.eraseLocalData()
                    },
                    modifier = Modifier.testTag("erase-confirm"),
                ) { Text(if (demo) "Exit Demo Mode" else "Disconnect & Erase") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
