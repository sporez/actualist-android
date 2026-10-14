package com.sporez.actualist.android.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.android.ui.AppViewModel
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.server.RemoteFile
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Actualist's Budget Data settings: Reimport Budget and the default account for new transactions. */
@Composable
fun SettingsBudgetDataCard(session: BudgetSession, settings: AppSettings, server: ServerConnection) {
    val current by settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity()
    val app = remember(activity) { activity?.let { ViewModelProvider(it)[AppViewModel::class.java] } }
    val notice by (app?.notice ?: remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }).collectAsState()
    val revision by session.revision.collectAsState()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<RemoteFile?>(null) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        accounts = runCatching { session.read { ledger.accounts(includeClosed = false) } }.getOrDefault(emptyList())
    }
    val defaultName = accounts.firstOrNull { it.id == current.defaultAccountId }?.name ?: "Automatic"
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Budget data", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Default account")
                    Text("Preselected for new transactions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("default-account")) { Text(defaultName) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Automatic") },
                            onClick = { settings.update { it.copy(defaultAccountId = null) }; menuOpen = false },
                            modifier = Modifier.testTag("default-account-auto"),
                        )
                        for (account in accounts) {
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = { settings.update { it.copy(defaultAccountId = account.id) }; menuOpen = false },
                                modifier = Modifier.testTag("default-account-${account.id}"),
                            )
                        }
                    }
                }
            }
            if (!session.isDemo && server.isSignedIn && app != null) {
                Text("Reimport budget", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Deletes the local copy of this budget and downloads a fresh one from the server. Changes not yet synced are lost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        error = null
                        loading = true
                        scope.launch {
                            try {
                                remote = server.remoteBudgets().firstOrNull { it.fileId == session.budgetId }
                                    ?: throw IllegalStateException("This budget was not found on the server.")
                                password = ""
                                confirming = true
                            } catch (failure: Exception) {
                                error = failure.message ?: "The server could not be reached."
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.testTag("reimport-budget"),
                ) { Text("Reimport budget") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reimport-notice")) }
        }
    }
    val target = remote
    if (confirming && target != null && app != null) {
        val encrypted = target.encryptKeyId != null
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reimport ${target.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The local copy is replaced with the server's. Unsynced changes on this device are lost.")
                    if (encrypted) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Budget password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("reimport-password"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        app.reimportBudget(target, if (encrypted) password else null)
                    },
                    enabled = !encrypted || password.isNotEmpty(),
                    modifier = Modifier.testTag("reimport-confirm"),
                ) { Text("Reimport") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
