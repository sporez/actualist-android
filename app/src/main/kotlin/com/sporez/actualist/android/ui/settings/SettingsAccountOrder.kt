package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.accountgroups.AccountGroups
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import kotlinx.coroutines.launch

private data class AccountOrderBucket(val key: String, val title: String, val groupName: String?, val accounts: List<Account>)

private data class AccountOrderState(val accounts: List<Account>, val groupOf: Map<String, String?>, val groupNames: Map<String, String>)

/** Settings → Account Order: the same account order the Accounts screen edits, in one list (Actualist's SettingsAccountOrderSheet). */
@Composable
fun SettingsAccountOrder(session: BudgetSession) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Account Order")
                Text("Reorder the accounts shown in the Accounts screen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { open = true }, modifier = Modifier.testTag("settings-account-order")) { Text("Edit") }
        }
    }
    if (open) AccountOrderDialog(session) { open = false }
}

@Composable
private fun AccountOrderDialog(session: BudgetSession, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val revision by session.revision.collectAsState()
    var reload by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<AccountOrderState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision, reload) {
        state = runCatching {
            session.read {
                val groups = AccountGroups(database, clock)
                AccountOrderState(
                    accounts = ledger.accounts(),
                    groupOf = runCatching { groups.accountGroupIds() }.getOrDefault(emptyMap()),
                    groupNames = runCatching { groups.groups().associate { it.id to it.name } }.getOrDefault(emptyMap()),
                )
            }
        }.getOrNull()
    }
    fun move(id: String, delta: Int) {
        scope.launch {
            error = runCatching {
                session.write { AccountGroups(database, clock).moveAccountBy(id, delta) }
            }.exceptionOrNull()?.let { it.message ?: "The account could not be moved." }
            reload += 1
        }
    }
    val loaded = state
    val buckets = if (loaded == null) {
        emptyList()
    } else {
        loaded.accounts.groupBy { account ->
            Triple(account.closed, account.offBudget, if (account.closed) null else loaded.groupOf[account.id])
        }.map { (key, accounts) ->
            val title = when {
                key.first -> "Closed"
                key.second -> "Off Budget"
                else -> "On Budget"
            }
            AccountOrderBucket("${key.first}-${key.second}-${key.third}", title, key.third?.let { loaded.groupNames[it] }, accounts)
        }.sortedBy { bucket -> if (bucket.title == "On Budget") 0 else if (bucket.title == "Off Budget") 1 else 2 }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account Order") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("settings-account-order-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    loaded == null -> Text("Loading accounts")
                    buckets.isEmpty() -> Text("No accounts loaded.", modifier = Modifier.testTag("settings-account-order-empty"))
                    else -> for (bucket in buckets) {
                        Text(
                            listOfNotNull(bucket.title, bucket.groupName).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        bucket.accounts.forEachIndexed { index, account ->
                            Row(Modifier.fillMaxWidth().testTag("settings-account-order-row-${account.id}"), verticalAlignment = Alignment.CenterVertically) {
                                Text(account.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { move(account.id, -1) }, enabled = index > 0, modifier = Modifier.testTag("settings-account-order-up-${account.id}")) { Text("Up") }
                                TextButton(onClick = { move(account.id, 1) }, enabled = index < bucket.accounts.lastIndex, modifier = Modifier.testTag("settings-account-order-down-${account.id}")) { Text("Down") }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings-account-order-done")) { Text("Done") } },
    )
}
