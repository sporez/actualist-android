package com.sporez.actualist.android.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.AccountWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AccountsState(
    val accounts: List<Account>,
    val groups: List<com.sporez.actualist.core.accountgroups.AccountGroup> = emptyList(),
    val groupOf: Map<String, String?> = emptyMap(),
)

class AccountsViewModel(private val session: BudgetSession) : ViewModel() {
    val messages = MessageState()
    val accounts = reloading(session, MutableStateFlow(Unit)) {
        val groups = com.sporez.actualist.core.accountgroups.AccountGroups(database, clock)
        AccountsState(
            accounts = ledger.accounts(),
            groups = runCatching { groups.groups() }.getOrDefault(emptyList()),
            groupOf = runCatching { groups.accountGroupIds() }.getOrDefault(emptyMap()),
        )
    }

    private fun groupWrite(done: String, block: com.sporez.actualist.core.accountgroups.AccountGroups.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { com.sporez.actualist.core.accountgroups.AccountGroups(database, clock).block() }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The account group could not be changed.")
            }
        }
    }

    fun createGroup(name: String) = groupWrite("Added group ${name.trim()}") { create(name) }

    fun renameGroup(id: String, name: String) = groupWrite("Renamed group") { rename(id, name) }

    fun deleteGroup(id: String) = groupWrite("Deleted group") { delete(id) }

    fun moveAccount(id: String, delta: Int) = groupWrite("Moved account") { moveAccountBy(id, delta) }

    fun moveAccountToGroup(id: String, groupId: String?) =
        groupWrite(if (groupId == null) "Removed from group" else "Moved to group") { reorderAccount(id, null, setGroup = true, groupId = groupId) }

    /** One step up (delta -1) or down (+1) among the groups, as Actualist's group menu. */
    fun moveGroup(groups: List<com.sporez.actualist.core.accountgroups.AccountGroup>, id: String, delta: Int) {
        val index = groups.indexOfFirst { it.id == id }
        val to = index + delta
        if (index < 0 || to < 0 || to >= groups.size) return
        val before = if (delta < 0) groups[to].id else groups.getOrNull(to + 1)?.id
        groupWrite("Moved group") { moveGroup(id, before) }
    }

    fun create(name: String, offBudget: Boolean, balance: Long) {
        viewModelScope.launch {
            try {
                session.write { AccountWrites(database, clock).create(name, offBudget, balance, LocalDate.now()) }
                messages.show("Added $name")
            } catch (error: Exception) {
                messages.show(error.message ?: "The account could not be added.")
            }
        }
    }
}

@Composable
fun AccountsScreen(viewModel: AccountsViewModel, onOpenAccount: (Account) -> Unit) {
    // Balances are projected to sample values together (the net worth adds up), so money() must not hash them again.
    androidx.compose.runtime.CompositionLocalProvider(com.sporez.actualist.android.ui.components.LocalValuesProjected provides com.sporez.actualist.android.ui.components.LocalSampleValues.current) {
        AccountsScreenContent(viewModel, onOpenAccount)
    }
}

@Composable
private fun AccountsScreenContent(viewModel: AccountsViewModel, onOpenAccount: (Account) -> Unit) {
    val sample = com.sporez.actualist.android.ui.components.LocalSampleValues.current
    val state by viewModel.accounts.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    var managingGroups by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("accounts-screen")) {
        when (val current = state) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
            is Loadable.Ready -> {
                val accounts = current.value.accounts.let { real -> if (sample) com.sporez.actualist.core.privacy.SampleValues.accounts(real) else real }
                val groupOf = current.value.groupOf
                val grouped = current.value.groups.map { group -> group to accounts.filter { !it.closed && groupOf[it.id] == group.id } }
                val groupedIds = grouped.flatMap { (_, members) -> members.map { it.id } }.toSet()
                val onBudget = accounts.filter { !it.offBudget && !it.closed && it.id !in groupedIds }
                val offBudget = accounts.filter { it.offBudget && !it.closed && it.id !in groupedIds }
                val closed = accounts.filter { it.closed }
                LazyColumn(Modifier.testTag("accounts-list"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    message?.let { item { Text(it, Modifier.testTag("accounts-message").clickable { viewModel.messages.clear() }, color = MaterialTheme.colorScheme.primary) } }
                    item { NetWorthHeader(accounts.filter { !it.closed }.sumOf { it.balance }) }
                    // Account groups (Actual 26.10) come first, each with its open member accounts.
                    for ((group, members) in grouped) section(group.name, "group-${group.id}", members, onOpenAccount, viewModel, current.value.groups, group.id)
                    section("Budget Accounts", "budget-accounts", onBudget, onOpenAccount, viewModel, current.value.groups, null)
                    section("Off Budget", "off-budget-accounts", offBudget, onOpenAccount, viewModel, current.value.groups, null)
                    if (closed.isNotEmpty()) {
                        item {
                            TextButton(onClick = { showClosed = !showClosed }, modifier = Modifier.testTag("toggle-closed")) {
                                Text(if (showClosed) "Hide closed (${closed.size})" else "Closed (${closed.size})")
                            }
                        }
                        if (showClosed) section("Closed", "closed-accounts", closed, onOpenAccount, viewModel, current.value.groups, null)
                    }
                    item {
                        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().testTag("add-account")) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Account")
                        }
                    }
                    item {
                        TextButton(onClick = { managingGroups = true }, modifier = Modifier.testTag("manage-groups")) { Text("Account groups…") }
                    }
                }
            }
        }
    }
    if (managingGroups) {
        val groups = (state as? Loadable.Ready)?.value?.groups.orEmpty()
        GroupsDialog(groups, viewModel) { managingGroups = false }
    }
    if (adding) AddAccountDialog(onDismiss = { adding = false }) { name, off, balance ->
        adding = false
        viewModel.create(name, off, balance)
    }
}

@Composable
private fun NetWorthHeader(total: Long) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
        Text("Net worth", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyText(total, colored = true, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("net-worth"))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    tag: String,
    accounts: List<Account>,
    onOpen: (Account) -> Unit,
    viewModel: AccountsViewModel,
    groups: List<com.sporez.actualist.core.accountgroups.AccountGroup>,
    groupId: String?,
) {
    if (accounts.isEmpty()) return
    item(key = tag) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.padding(horizontal = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                MoneyText(accounts.sumOf { it.balance })
            }
            SectionCard(Modifier.testTag(tag)) {
                accounts.forEachIndexed { index, account ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(account) }.padding(horizontal = 16.dp, vertical = 13.dp).testTag("account-${account.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when {
                                account.offBudget -> Icons.Filled.Savings
                                account.balance < 0 -> Icons.Filled.CreditCard
                                else -> Icons.Filled.AccountBalance
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(account.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        MoneyText(account.balance, colored = true)
                        AccountMenu(account, index > 0, index < accounts.lastIndex, groups, groupId, viewModel)
                    }
                    if (index < accounts.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** Move Up / Move Down within the section, plus Move to Group / Remove from Group, as Actualist's context menu. */
@Composable
private fun AccountMenu(
    account: Account,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    groups: List<com.sporez.actualist.core.accountgroups.AccountGroup>,
    groupId: String?,
    viewModel: AccountsViewModel,
) {
    var open by remember { mutableStateOf(false) }
    val destinations = if (account.closed) emptyList() else groups.filter { it.id != groupId }
    if (!canMoveUp && !canMoveDown && destinations.isEmpty() && groupId == null) {
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).testTag("account-menu-${account.id}")) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Account actions", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (canMoveUp) {
            DropdownMenuItem(text = { Text("Move Up") }, onClick = { open = false; viewModel.moveAccount(account.id, -1) }, modifier = Modifier.testTag("account-move-up-${account.id}"))
        }
        if (canMoveDown) {
            DropdownMenuItem(text = { Text("Move Down") }, onClick = { open = false; viewModel.moveAccount(account.id, 1) }, modifier = Modifier.testTag("account-move-down-${account.id}"))
        }
        for (group in destinations) {
            DropdownMenuItem(
                text = { Text("Move to ${group.name}") },
                onClick = { open = false; viewModel.moveAccountToGroup(account.id, group.id) },
                modifier = Modifier.testTag("account-to-group-${account.id}-${group.id}"),
            )
        }
        if (groupId != null) {
            DropdownMenuItem(text = { Text("Remove from Group") }, onClick = { open = false; viewModel.moveAccountToGroup(account.id, null) }, modifier = Modifier.testTag("account-ungroup-${account.id}"))
        }
    }
}

@Composable
private fun AddAccountDialog(onDismiss: () -> Unit, onCreate: (String, Boolean, Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    var offBudget by rememberSaveable { mutableStateOf(false) }
    val amount = if (balance.isBlank()) 0L else Money.parse(balance)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("account-name"))
                AmountField(balance, { balance = it }, label = "Current balance", tag = "account-balance")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = !offBudget, onClick = { offBudget = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.testTag("account-kind-budget")) { Text("Budget") }
                    SegmentedButton(selected = offBudget, onClick = { offBudget = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.testTag("account-kind-offbudget")) { Text("Off Budget") }
                }
                Text(
                    if (offBudget) "Tracked outside the budget." else "Included in the budget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, offBudget, amount ?: 0) }, enabled = name.isNotBlank() && amount != null, modifier = Modifier.testTag("account-create")) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/** Create, rename and delete account groups; deleting a group ungroups its accounts, as in Actual. */
@Composable
private fun GroupsDialog(groups: List<com.sporez.actualist.core.accountgroups.AccountGroup>, viewModel: AccountsViewModel, onDismiss: () -> Unit) {
    var newName by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<com.sporez.actualist.core.accountgroups.AccountGroup?>(null) }
    var renameText by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Account groups") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("groups-dialog")) {
                if (groups.isEmpty()) Text("No groups yet. Groups collect accounts under one heading.", style = MaterialTheme.typography.bodySmall)
                for (group in groups) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (renaming?.id == group.id) {
                            OutlinedTextField(renameText, { renameText = it }, singleLine = true, modifier = Modifier.weight(1f).testTag("group-rename-field"))
                            TextButton(onClick = { viewModel.renameGroup(group.id, renameText); renaming = null }, enabled = renameText.isNotBlank()) { Text("Save") }
                        } else {
                            Text(group.name, Modifier.weight(1f))
                            if (groups.first().id != group.id) TextButton(onClick = { viewModel.moveGroup(groups, group.id, -1) }, modifier = Modifier.testTag("group-up-${group.id}")) { Text("Up") }
                            if (groups.last().id != group.id) TextButton(onClick = { viewModel.moveGroup(groups, group.id, 1) }, modifier = Modifier.testTag("group-down-${group.id}")) { Text("Down") }
                            TextButton(onClick = { renaming = group; renameText = group.name }) { Text("Rename") }
                            TextButton(onClick = { viewModel.deleteGroup(group.id) }, modifier = Modifier.testTag("group-delete-${group.id}")) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                OutlinedTextField(newName, { newName = it }, label = { Text("New group name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("group-name"))
                TextButton(onClick = { viewModel.createGroup(newName); newName = "" }, enabled = newName.isNotBlank(), modifier = Modifier.testTag("group-create")) { Text("Add group") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
