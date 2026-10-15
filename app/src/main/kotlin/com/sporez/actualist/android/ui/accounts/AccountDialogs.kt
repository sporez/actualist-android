package com.sporez.actualist.android.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.LocalSampleValues
import com.sporez.actualist.android.ui.components.NotesHiddenMessage
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.AccountCloseAction
import com.sporez.actualist.core.transactions.AccountCloseBlocker
import com.sporez.actualist.core.transactions.AccountCloseReview
import com.sporez.actualist.core.transactions.CategoryOption

/** A one-field rename dialog for an account or an account group. */
@Composable
internal fun NameDialog(title: String, initial: String, fieldTag: String, saveTag: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(fieldTag)) },
        confirmButton = {
            TextButton(
                onClick = { onSave(name); onDismiss() },
                enabled = name.isNotBlank() && name.trim() != initial,
                modifier = Modifier.testTag(saveTag),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The account's note, editable from the Accounts list (hidden while Sample Values is on). */
@Composable
internal fun AccountNotesDialog(account: Account, viewModel: AccountsViewModel, onDismiss: () -> Unit) {
    val sample = LocalSampleValues.current
    var original by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(account.id) {
        if (!sample) {
            original = viewModel.accountNote(account.id)
            text = original.orEmpty()
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes · ${account.name}") },
        text = {
            if (sample) {
                NotesHiddenMessage()
            } else if (loaded) {
                OutlinedTextField(text, { text = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("account-notes-field"))
            } else {
                Text("Reading the note…")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.saveAccountNote(account.id, text); onDismiss() },
                enabled = !sample && loaded && text.trim() != original.orEmpty(),
                modifier = Modifier.testTag("account-notes-save"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Closes the account: a balance moves to another account (with a category when it leaves the budget), as Actual does. */
@Composable
internal fun AccountCloseDialog(account: Account, viewModel: AccountsViewModel, onDismiss: () -> Unit) {
    var destinationId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<AccountCloseReview?>(null) }
    var choices by remember { mutableStateOf<Pair<List<Account>, List<CategoryOption>>?>(null) }
    LaunchedEffect(destinationId, categoryId) {
        review = viewModel.closeReview(account.id, destinationId, categoryId)
        if (choices == null) choices = viewModel.closeChoices()
    }
    val r = review
    val accountChoices = choices?.first.orEmpty().filter { it.id != account.id }
    val categoryChoices = choices?.second.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ${account.name}") },
        text = {
            Column(Modifier.testTag("close-account-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (r?.action) {
                    AccountCloseAction.DeleteEmpty -> Text("No transactions: closing deletes this account.", style = MaterialTheme.typography.bodySmall)
                    AccountCloseAction.CloseAtZero -> Text("The balance is zero.", style = MaterialTheme.typography.bodySmall)
                    else -> if (r != null && r.balance != 0L) {
                        Text("Move the ${Money.format(r.balance)} balance to:", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { picking = "account" }, modifier = Modifier.testTag("close-destination")) {
                            Text(accountChoices.firstOrNull { it.id == destinationId }?.name ?: "Choose an account")
                        }
                        if (r.blockers.contains(AccountCloseBlocker.CATEGORY_REQUIRED) || categoryId != null) {
                            TextButton(onClick = { picking = "category" }, modifier = Modifier.testTag("close-category")) {
                                Text(categoryChoices.firstOrNull { it.id == categoryId }?.name ?: "Choose a category")
                            }
                        }
                    }
                }
                r?.blockers?.firstOrNull { it != AccountCloseBlocker.DESTINATION_REQUIRED }?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.closeAccount(account.id, destinationId, categoryId); onDismiss() },
                enabled = r?.action != null,
                modifier = Modifier.testTag("close-account"),
            ) { Text(if (r?.action == AccountCloseAction.DeleteEmpty) "Delete account" else "Close account", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (picking) {
        "account" -> PickerDialog(
            title = "Move balance to",
            options = accountChoices.map { PickerOption(it.id, it.name, if (it.offBudget) "Off budget" else "On budget") },
            onPick = { destinationId = it.id; picking = null },
            onDismiss = { picking = null },
        )
        "category" -> PickerDialog(
            title = "Category",
            options = categoryChoices.map { PickerOption(it.id, it.name, it.groupName) },
            searchable = true,
            onPick = { categoryId = it.id; picking = null },
            onDismiss = { picking = null },
        )
    }
}
