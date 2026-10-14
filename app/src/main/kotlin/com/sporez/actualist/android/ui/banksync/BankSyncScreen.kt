package com.sporez.actualist.android.ui.banksync

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.core.banksync.BankSyncRunLine
import com.sporez.actualist.core.banksync.BankSyncRunReport
import com.sporez.actualist.core.banksync.SimpleFinLinkedAccount
import com.sporez.actualist.core.banksync.SimpleFinRemoteAccount
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private class BankRow(val account: Account, val link: SimpleFinLinkedAccount?)

/**
 * Bank Sync: every account with its SimpleFIN link state, Sync All with per-account result lines, a persisted
 * "Last result", and per-account Sync / Link / Unlink (Actualist's BankSyncView).
 */
@Composable
fun BankSyncScreen(session: BudgetSession, server: ServerConnection) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bank_sync_runs", Context.MODE_PRIVATE) }
    val revision by session.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<BankRow>>(emptyList()) }
    var results by remember { mutableStateOf<List<BankSyncRunLine>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var lastTime by remember { mutableStateOf(prefs.getLong("last_time", 0L)) }
    var lastOutcome by remember { mutableStateOf(prefs.getString("last_outcome", null)) }
    var message by remember { mutableStateOf<String?>(null) }
    var remotes by remember { mutableStateOf<List<SimpleFinRemoteAccount>?>(null) }
    var linking by remember { mutableStateOf<Account?>(null) }
    var setupToken by remember { mutableStateOf("") }
    var needsDevice by remember { mutableStateOf(false) }

    val available = server.isSignedIn && !session.isDemo

    suspend fun reload() {
        if (!available) return
        rows = runCatching {
            session.read {
                val linked = server.simpleFin(database, clock).linkedAccounts().filter { it.isSimpleFin }.associateBy { it.id }
                ledger.accounts(includeClosed = false).map { BankRow(it, linked[it.id]) }
            }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(revision, available) { reload() }

    fun record(lines: List<BankSyncRunLine>) {
        val now = System.currentTimeMillis()
        val outcome = BankSyncRunReport.outcome(lines)
        prefs.edit().putLong("last_time", now).putString("last_outcome", outcome).apply()
        lastTime = now
        lastOutcome = outcome
    }

    suspend fun syncOne(row: BankRow): BankSyncRunLine = try {
        val summary = session.syncing { server.simpleFin(database, clock).sync(row.account.id) }
        BankSyncRunReport.line(row.account.id, row.account.name, summary)
    } catch (error: Exception) {
        BankSyncRunReport.failure(row.account.id, row.account.name, error.message)
    }

    fun syncAll() {
        val targets = rows.filter { it.link != null }
        scope.launch {
            running = true
            results = emptyList()
            val lines = ArrayList<BankSyncRunLine>()
            for (row in targets) {
                lines.add(syncOne(row))
                results = lines.toList()
            }
            record(lines)
            running = false
            reload()
        }
    }

    fun syncSingle(row: BankRow) {
        scope.launch {
            running = true
            val line = syncOne(row)
            results = listOf(line)
            record(listOf(line))
            running = false
            reload()
        }
    }

    fun unlink(row: BankRow) {
        scope.launch {
            message = try {
                session.write { server.simpleFin(database, clock).unlink(row.account.id) }
                "Unlinked ${row.account.name}"
            } catch (error: Exception) {
                error.message ?: "The account could not be unlinked."
            }
            reload()
        }
    }

    fun beginLink(account: Account) {
        scope.launch {
            message = null
            linking = account
            try {
                remotes = session.read { server.simpleFin(database, clock).remoteAccounts() }
                needsDevice = false
            } catch (error: Exception) {
                message = error.message ?: "The server cannot reach SimpleFIN."
                needsDevice = !server.hasSimpleFinBridge
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("bank-sync-screen"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Bank Sync", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp)) }
        if (!available) {
            item {
                Text(
                    if (session.isDemo) "Bank sync needs a budget on your Actual server. The demo budget is local only, so there is nothing to link."
                    else "Sign in to your Actual server and open a server budget to link accounts to SimpleFIN.",
                    modifier = Modifier.testTag("bank-sync-empty"),
                )
            }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last result", style = MaterialTheme.typography.labelLarge)
                val outcome = lastOutcome
                Text(
                    if (outcome == null || lastTime == 0L) "No bank sync has run yet."
                    else "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastTime))} · $outcome",
                    modifier = Modifier.testTag("bank-sync-last-result"),
                )
                Button(
                    onClick = { syncAll() },
                    enabled = !running && rows.any { it.link != null },
                    modifier = Modifier.testTag("bank-sync-all"),
                ) { Text(if (running) "Syncing…" else "Sync All") }
            }
        }
        message?.let { text ->
            item { Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("bank-sync-message")) }
        }
        if (needsDevice) {
            item {
                Column {
                    OutlinedTextField(
                        setupToken,
                        { setupToken = it },
                        label = { Text("SimpleFIN setup token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("simplefin-setup-token"),
                    )
                    TextButton(
                        enabled = setupToken.isNotBlank() && linking != null,
                        onClick = {
                            scope.launch {
                                try {
                                    server.connectSimpleFinBridge(setupToken)
                                    remotes = session.read { server.simpleFin(database, clock).remoteAccounts() }
                                    message = null
                                    needsDevice = false
                                } catch (error: Exception) {
                                    message = error.message ?: "SimpleFIN could not be connected."
                                }
                            }
                        },
                        modifier = Modifier.testTag("simplefin-connect-device"),
                    ) { Text("Connect SimpleFIN on this device") }
                }
            }
        }
        if (results.isNotEmpty()) {
            item { Text("Results", style = MaterialTheme.typography.labelLarge) }
            items(results, key = { "result-" + it.accountId }) { line ->
                Column(modifier = Modifier.testTag("bank-sync-result-" + line.accountName)) {
                    Text(line.accountName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Text("Accounts", style = MaterialTheme.typography.labelLarge) }
        if (rows.isEmpty()) item { Text("No accounts.") }
        items(rows, key = { it.account.id }) { row ->
            Row(modifier = Modifier.fillMaxWidth().testTag("bank-sync-account-" + row.account.name)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.account.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (row.link != null) "Linked to SimpleFIN" else "Not linked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.link != null) {
                    TextButton(onClick = { syncSingle(row) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-sync-" + row.account.name)) { Text("Sync") }
                    TextButton(onClick = { unlink(row) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-unlink-" + row.account.name)) { Text("Unlink") }
                } else {
                    TextButton(onClick = { beginLink(row.account) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-link-" + row.account.name)) { Text("Link") }
                }
            }
        }
        item { Row(modifier = Modifier.padding(bottom = 16.dp)) {} }
    }

    remotes?.let { list ->
        PickerDialog(
            title = if (list.isEmpty()) "No SimpleFIN accounts on this server" else "SimpleFIN account",
            options = list.map { PickerOption(it.accountId, it.name, listOfNotNull(it.orgName ?: it.institution, it.balance).joinToString(" · ")) },
            onPick = { option ->
                val remote = list.firstOrNull { it.accountId == option.id }
                val target = linking
                remotes = null
                linking = null
                if (remote != null && target != null) {
                    scope.launch {
                        message = try {
                            session.write { server.simpleFin(database, clock).link(target.id, remote) }
                            "Linked ${target.name} to ${remote.name}"
                        } catch (error: Exception) {
                            error.message ?: "The account could not be linked."
                        }
                        reload()
                    }
                }
            },
            onDismiss = { remotes = null; linking = null },
        )
    }
}
