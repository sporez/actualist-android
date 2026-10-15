package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.transactions.ReconciledIntent
import com.sporez.actualist.core.transactions.ReconciledMutationPresentation
import com.sporez.actualist.core.transactions.ReconciledScope
import com.sporez.actualist.core.transactions.TransactionRow

/**
 * Actualist's trailing swipe "Delete": swiping a row to the left asks [onRequestDelete]
 * (which opens the confirmation) and the row springs back; nothing is deleted by the swipe itself.
 */
@Composable
fun SwipeToDeleteRow(rowId: String, enabled: Boolean, onRequestDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) onRequestDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        modifier = Modifier.testTag("swipe-row-$rowId"),
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("Delete", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelLarge)
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
    }
}

/** The row's overflow menu: Actualist's context actions for a feed row. */
@Composable
fun RowActionsMenu(rowId: String, canConvert: Boolean, onConvertToSchedule: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp).testTag("row-menu-$rowId")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Transaction actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canConvert) {
                DropdownMenuItem(
                    text = { Text("Convert to Schedule…") },
                    onClick = {
                        open = false
                        onConvertToSchedule()
                    },
                    modifier = Modifier.testTag("row-convert-schedule"),
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    open = false
                    onDelete()
                },
                modifier = Modifier.testTag("row-delete"),
            )
        }
    }
}

/**
 * Actualist's delete confirmation, naming the payee. A reconciled row gets the reconciled
 * warning instead (the same one the editor shows).
 */
@Composable
fun DeleteTransactionDialog(
    payee: String,
    reconciled: Boolean,
    loadScope: suspend () -> ReconciledScope?,
    onConfirm: (unlockReconciled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (reconciled) {
        var scope by remember { mutableStateOf<ReconciledScope?>(null) }
        LaunchedEffect(Unit) { scope = runCatching { loadScope() }.getOrNull() }
        ReconciledGuardDialog(
            presentation = ReconciledMutationPresentation.make(
                intent = ReconciledIntent.DELETE,
                targetReconciledCount = scope?.targetIds?.size ?: 1,
                includesPairedTransfer = scope?.pairedIds?.isNotEmpty() == true,
                targetRequiresUnlock = scope?.targetIds?.isNotEmpty() == true,
            ),
            onConfirm = { onConfirm(true) },
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("delete-transaction-dialog"),
        title = { Text("Delete Transaction?") },
        text = { Text("Delete ${payee.ifBlank { "this transaction" }}?") },
        confirmButton = {
            TextButton(onClick = { onConfirm(false) }, modifier = Modifier.testTag("delete-transaction-confirm")) {
                Text("Delete Transaction", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("delete-transaction-cancel")) { Text("Cancel") } },
    )
}
