package com.sporez.actualist.android.ui.transactions

import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.transactions.Reconciliation
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the reconcile steps need to know about the account, read fresh whenever a step changes the ledger. */
data class ReconcileInfo(
    val accountName: String,
    val workingBalance: Long,
    val clearedBalance: Long,
    val unclearedCount: Int,
    val lastReconciled: Long?,
)

private enum class ReconcileStep { ENTER, REVIEW, PREVIEW }

private const val BALANCE_PROMPT = "Enter the current balance of your bank account that you want to reconcile with:"

/**
 * Actualist's multi-step reconciliation: enter the statement balance, review the difference
 * against the cleared balance, preview and create the adjustment (or edit the balance again),
 * then lock the cleared transactions. "Exit reconciliation" leaves without locking anything.
 */
@Composable
fun ReconcileDialog(viewModel: TransactionsViewModel, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf<ReconcileInfo?>(null) }
    var balanceText by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(ReconcileStep.ENTER) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { info = viewModel.reconcileInfo() }
    val loaded = info
    val bank = Money.parse(balanceText)
    val difference = if (bank != null && loaded != null) bank - loaded.clearedBalance else null
    val clearedText = "Cleared balance: " + (loaded?.let { Money.format(it.clearedBalance) } ?: "…")

    fun refreshAfter(action: suspend () -> Boolean, next: ReconcileStep?) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = action()
            if (ok) {
                info = viewModel.reconcileInfo()
                if (next != null) step = next
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reconcile-dialog"),
        title = { Text(if (step == ReconcileStep.ENTER) "Reconcile account" else "Reconciling ${loaded?.accountName.orEmpty()}".trim()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (step) {
                    ReconcileStep.ENTER -> {
                        Text(BALANCE_PROMPT, style = MaterialTheme.typography.bodySmall)
                        Text(clearedText, modifier = Modifier.testTag("reconcile-cleared"))
                        loaded?.let {
                            Text(
                                it.lastReconciled?.let { millis -> "Last reconciled ${java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}" } ?: "Not yet reconciled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("reconcile-last"),
                            )
                        }
                        AmountField(balanceText, { balanceText = it }, label = "Bank statement balance", tag = "reconcile-balance")
                        difference?.let {
                            Text(
                                if (it == 0L) "Balanced. Finishing locks every cleared transaction." else "Difference ${Money.format(it)} can be added as a cleared adjustment.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("reconcile-difference"),
                            )
                        }
                    }
                    ReconcileStep.REVIEW -> {
                        Line("Statement balance", bank?.let { Money.format(it) } ?: "…", "reconcile-target")
                        Line("Cleared balance", loaded?.let { Money.format(it.clearedBalance) } ?: "…", "reconcile-cleared-review")
                        loaded?.let { Line("Uncleared transactions", it.unclearedCount.toString(), "reconcile-uncleared") }
                        Line(
                            "Difference",
                            difference?.let { Money.format(it) } ?: "Unavailable",
                            "reconcile-difference",
                        )
                        Text(
                            when (difference) {
                                null -> ""
                                0L -> "Balanced. Lock transactions to mark every cleared transaction reconciled."
                                else -> "Create a reconciliation transaction to close the difference, or edit the statement balance."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReconcileStep.PREVIEW -> {
                        Text("This transaction will be created, cleared, dated today:", style = MaterialTheme.typography.bodySmall)
                        Line("Notes", Reconciliation.ADJUSTMENT_NOTE, "reconcile-preview-notes")
                        Line("Amount", difference?.let { Money.format(it) } ?: "…", "reconcile-preview-amount")
                        Line("Date", LocalDate.now().toString(), "reconcile-preview-date")
                        Line("Status", "Cleared", "reconcile-preview-status")
                        Text(
                            "The budget's rules can still change its payee or category. You can undo this in Budget → History.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (step) {
                ReconcileStep.ENTER -> TextButton(
                    enabled = bank != null && loaded != null,
                    onClick = { step = ReconcileStep.REVIEW },
                    modifier = Modifier.testTag("reconcile-continue"),
                ) { Text("Continue") }
                ReconcileStep.REVIEW -> when {
                    difference == null -> {}
                    difference == 0L -> TextButton(
                        enabled = !busy,
                        onClick = { viewModel.lockReconciled(bank!!); onDismiss() },
                        modifier = Modifier.testTag("reconcile-finish"),
                    ) { Text("Lock transactions") }
                    else -> TextButton(enabled = !busy, onClick = { step = ReconcileStep.PREVIEW }, modifier = Modifier.testTag("reconcile-adjust")) {
                        Text("Create reconciliation transaction")
                    }
                }
                ReconcileStep.PREVIEW -> TextButton(
                    enabled = !busy,
                    onClick = { refreshAfter({ viewModel.addReconcileAdjustment(bank!!) }, ReconcileStep.REVIEW) },
                    modifier = Modifier.testTag("reconcile-adjust-confirm"),
                ) { Text("Create") }
            }
        },
        dismissButton = {
            Row {
                when (step) {
                    ReconcileStep.ENTER -> TextButton(onClick = onDismiss, modifier = Modifier.testTag("reconcile-cancel")) { Text("Cancel") }
                    ReconcileStep.REVIEW -> {
                        TextButton(onClick = { step = ReconcileStep.ENTER }, modifier = Modifier.testTag("reconcile-edit")) { Text("Edit balance") }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("reconcile-exit")) { Text("Exit reconciliation") }
                    }
                    ReconcileStep.PREVIEW -> {
                        TextButton(onClick = { step = ReconcileStep.REVIEW }, modifier = Modifier.testTag("reconcile-preview-back")) { Text("Back") }
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("reconcile-exit")) { Text("Exit reconciliation") }
                    }
                }
            }
        },
    )
}

@Composable
private fun Line(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
    }
}
