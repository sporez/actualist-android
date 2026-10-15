package com.sporez.actualist.android.ui.banksync

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.core.banksync.BankSyncCopy
import com.sporez.actualist.core.banksync.BankSyncRunLine
import com.sporez.actualist.core.banksync.BankSyncRunReport
import com.sporez.actualist.core.banksync.BankSyncMatchDetails
import com.sporez.actualist.core.banksync.SimpleFinLinkedAccount
import com.sporez.actualist.core.banksync.SimpleFinRemoteAccount
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private class BankRow(val account: Account, val link: SimpleFinLinkedAccount?) {
    /** Only SimpleFIN links are sync targets; other providers' links stay read-only. */
    val syncable: Boolean get() = link?.isSimpleFin == true
}

private fun providerName(source: String): String = when (source) {
    "goCardless" -> "GoCardless"
    "pluggyai" -> "Pluggy.ai"
    "akahu" -> "Akahu"
    "enableBanking" -> "Enable Banking"
    "simpleFin" -> "SimpleFIN"
    else -> source
}

private fun accountDetail(row: BankRow, nowMillis: Long): String {
    val link = row.link
    return when {
        link == null -> "Not linked"
        !link.isSimpleFin -> "Linked to ${providerName(link.syncSource)}"
        else -> BankSyncCopy.lastSyncText(link.lastSync, true, nowMillis)
    }
}

/** One account's result: counts, problems, opening balance and an expandable list of saved match changes. */
@Composable
private fun ResultRow(line: BankSyncRunLine) {
    var expanded by remember(line.accountId) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().testTag("bank-sync-result-" + line.accountName), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.accountName, style = MaterialTheme.typography.bodyMedium)
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (line.failed) {
            Text(line.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            line.statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        } else {
            if (line.addedCount > 0) Text("${line.addedCount} added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("bank-sync-added-" + line.accountName))
            if (line.updatedCount > 0) Text("${line.updatedCount} matched", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("bank-sync-matched-" + line.accountName))
            if (line.unchangedCount > 0) Text("${line.unchangedCount} unchanged", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("bank-sync-unchanged-" + line.accountName))
            if (line.addedCount == 0 && line.matchedCount == 0 && !line.openingBalanceAdded) Text(line.text, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (line.problems.isNotEmpty()) {
            Text("${line.problems.size} problems", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            BankSyncCopy.problemSummary(line.problems)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        if (line.openingBalanceAdded) Text("Opening balance added", style = MaterialTheme.typography.bodySmall, color = muted)
        if (line.matchDetails.isNotEmpty()) {
            val count = line.matchDetails.size
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("bank-sync-details-toggle-" + line.accountName)) {
                Text(if (count == 1) "Changes saved for 1 match" else "Changes saved for $count matches")
            }
            if (expanded) {
                line.matchDetails.forEachIndexed { index, match ->
                    if (index > 0) HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(match.title, style = MaterialTheme.typography.bodySmall)
                            Text(BankSyncMatchDetails.dayText(match.dayId), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                        MoneyText(match.amount)
                    }
                    match.changes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
                }
            }
        }
    }
}

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
    var sheetAccountId by remember { mutableStateOf<String?>(null) }

    val available = server.isSignedIn && !session.isDemo

    suspend fun reload() {
        if (!available) return
        rows = runCatching {
            session.read {
                val linked = server.simpleFin(database, clock).linkedAccounts().associateBy { it.id }
                ledger.accounts(includeClosed = false).map { BankRow(it, linked[it.id]) }
            }
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(revision, available) { reload() }

    fun record(lines: List<BankSyncRunLine>, source: String = "Sync All") {
        val now = System.currentTimeMillis()
        val outcome = BankSyncRunReport.outcome(lines)
        prefs.edit().putLong("last_time", now).putString("last_outcome", outcome).putString("last_source", source).apply()
        lastTime = now
        lastOutcome = outcome
    }

    suspend fun syncOne(row: BankRow): BankSyncRunLine = try {
        val summary = session.syncing { server.simpleFin(database, clock).sync(row.account.id) }
        BankSyncRunReport.line(row.account.id, row.account.name, summary)
    } catch (error: Exception) {
        BankSyncRunReport.failureOf(row.account.id, row.account.name, error)
    }

    fun syncAll() {
        val targets = rows.filter { it.syncable }
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
            record(listOf(line), "Sync")
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
                if (outcome != null && lastTime != 0L) {
                    // Actualist's "Sync All · 2h ago".
                    val source = prefs.getString("last_source", null) ?: "Sync All"
                    Text(
                        "$source · ${BankSyncCopy.ageText(lastTime, System.currentTimeMillis())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("bank-sync-last-caption"),
                    )
                }
                Button(
                    onClick = { syncAll() },
                    enabled = !running && rows.any { it.syncable },
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
            items(results, key = { "result-" + it.accountId }) { line -> ResultRow(line) }
        }
        item { Text("Accounts", style = MaterialTheme.typography.labelLarge) }
        if (rows.isEmpty()) item { Text("No accounts.") }
        items(rows, key = { it.account.id }) { row ->
            Row(modifier = Modifier.fillMaxWidth().clickable { sheetAccountId = row.account.id }.testTag("bank-sync-account-" + row.account.name)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.account.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        accountDetail(row, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("bank-sync-account-detail-" + row.account.name),
                    )
                    val statusText = if (row.syncable) BankSyncCopy.statusText(row.link?.bankSyncStatus) else null
                    if (statusText != null) {
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("bank-sync-account-status-" + row.account.name),
                        )
                    }
                }
                if (row.syncable) {
                    TextButton(onClick = { syncSingle(row) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-sync-" + row.account.name)) { Text("Sync") }
                    TextButton(onClick = { unlink(row) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-unlink-" + row.account.name)) { Text("Unlink") }
                } else if (row.link == null) {
                    TextButton(onClick = { beginLink(row.account) }, enabled = !running, modifier = Modifier.testTag("bank-sync-account-link-" + row.account.name)) { Text("Link") }
                }
            }
        }
        item { Row(modifier = Modifier.padding(bottom = 16.dp)) {} }
    }

    rows.firstOrNull { it.account.id == sheetAccountId }?.let { row ->
        val link = row.link
        AlertDialog(
            onDismissRequest = { sheetAccountId = null },
            title = { Text(row.account.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("bank-sync-account-sheet")) {
                    if (link == null) {
                        Text("Not linked. Link this account to a bank account, then use Sync All to download and save transactions.")
                    } else {
                        Text("Bank account: ${link.remoteAccountId}")
                        Text("Last synced: " + BankSyncCopy.lastSyncText(link.lastSync, true, System.currentTimeMillis()).removePrefix("Synced "))
                        BankSyncCopy.statusText(link.bankSyncStatus)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(
                            BankSyncCopy.linkedFooter(link.isSimpleFin) + if (link.isSimpleFin) "" else " " + BankSyncCopy.otherProviderUnlink(providerName(link.syncSource)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                if (link == null) {
                    TextButton(onClick = { sheetAccountId = null; beginLink(row.account) }, enabled = !running, modifier = Modifier.testTag("bank-sync-sheet-link")) { Text("Link Bank Account") }
                } else if (link.isSimpleFin) {
                    TextButton(onClick = { sheetAccountId = null; unlink(row) }, enabled = !running, modifier = Modifier.testTag("bank-sync-sheet-unlink")) {
                        Text("Unlink Bank Account", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { sheetAccountId = null }) { Text("Close") } },
        )
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
