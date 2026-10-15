package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.sync.ServerConnection
import kotlinx.coroutines.launch

/** Actualist's "Budget Actions" menu: History, Schedules, Notes, Templates and Settings. */
@Composable
internal fun BudgetActionsMenu(
    onHistory: () -> Unit,
    onSchedules: () -> Unit,
    onNotes: () -> Unit,
    onTemplates: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("budget-actions")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Budget Actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("History") }, onClick = { open = false; onHistory() }, modifier = Modifier.testTag("action-history"))
            DropdownMenuItem(text = { Text("Schedules") }, onClick = { open = false; onSchedules() }, modifier = Modifier.testTag("action-schedules"))
            DropdownMenuItem(text = { Text("Notes") }, onClick = { open = false; onNotes() }, modifier = Modifier.testTag("action-notes"))
            DropdownMenuItem(text = { Text("Templates") }, onClick = { open = false; onTemplates() }, modifier = Modifier.testTag("action-templates"))
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onSettings() }, modifier = Modifier.testTag("action-settings"))
        }
    }
}

/** The shown month's note: rendered as Markdown, edited as text, with the "Supported Markdown" help. */
@Composable
internal fun MonthNoteDialog(month: String, note: String?, viewModel: BudgetViewModel, onDismiss: () -> Unit) {
    var text by rememberSaveable(month) { mutableStateOf(note.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes · ${monthTitle(month)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("month-note-dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MarkdownHelp()
                OutlinedTextField(text, { text = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().testTag("month-note-field"))
                if (text.isNotBlank()) MarkdownText(text, Modifier.testTag("month-note-preview"))
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveMonthNote(month, text); onDismiss() }, modifier = Modifier.testTag("month-note-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown when sync cannot run because this device no longer has the encrypted budget's key (Actualist's unlock sheet). */
@Composable
internal fun EncryptedBudgetUnlockDialog(viewModel: BudgetViewModel, server: ServerConnection, onDismiss: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Unlock encrypted budget") },
        text = {
            Column(Modifier.testTag("unlock-sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "This budget is encrypted and this device does not have its key. Enter the budget's password to sync it again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("unlock-password"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        val unlocked = viewModel.unlock(server, password)
                        working = false
                        if (unlocked) onDismiss()
                    }
                },
                modifier = Modifier.testTag("unlock-confirm"),
            ) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}
