package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.HistoryPresenter
import com.sporez.actualist.core.store.HistoryReview
import com.sporez.actualist.core.store.HistoryRow
import kotlinx.coroutines.launch

/** One loaded History row with the review that undoing or redoing it would show, when it is the next step. */
private class HistoryEntryModel(val row: HistoryRow, val undone: Boolean, val summary: String, val review: HistoryReview?)

/** The undo or redo awaiting confirmation. */
private class PendingStep(val undo: Boolean, val review: HistoryReview)

/**
 * This session's edits, newest first. Like Actualist, undo is last-in-first-out: only the newest
 * applied row offers Undo (after a review of what changes back), and the newest undone row offers
 * Redo. The core refuses a step whose rows were edited since.
 */
@Composable
fun HistoryScreen(session: BudgetSession) {
    val revision by session.revision.collectAsState()
    val undoState by session.undoState.collectAsState()
    var items by remember { mutableStateOf<List<HistoryEntryModel>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingStep?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) {
        items = session.read {
            val presenter = HistoryPresenter(database)
            val details = history.details()
            val summaries = history.items()
            val nextUndo = details.indexOfFirst { !it.undone }
            val nextRedo = details.indexOfLast { it.undone }
            details.mapIndexed { index, detail ->
                val review = when (index) {
                    nextUndo -> presenter.review(detail.batches, undoing = true)
                    nextRedo -> presenter.review(detail.batches, undoing = false)
                    else -> null
                }
                HistoryEntryModel(presenter.row(detail.batches), detail.undone, summaries[index].summary, review)
            }
        }
    }
    fun review(undo: Boolean) {
        val target = if (undo) items.firstOrNull { !it.undone } else items.lastOrNull { it.undone }
        val shown = target?.review ?: return
        message = null
        pending = PendingStep(undo, shown)
    }
    fun confirm(step: PendingStep) {
        pending = null
        scope.launch {
            message = try {
                if (step.undo) session.undo() else session.redo()
                null
            } catch (error: Exception) {
                error.message ?: "That could not be undone."
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("history-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { review(undo = true) }, enabled = undoState.canUndo, modifier = Modifier.testTag("history-undo")) { Text("Undo") }
                OutlinedButton(onClick = { review(undo = false) }, enabled = undoState.canRedo, modifier = Modifier.testTag("history-redo")) { Text("Redo") }
            }
        }
        message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-message")) } }
        if (items.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("No Actions Yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Assignments, moves, transactions, and other changes you make on this device appear here. History is kept until the budget closes, as in Actual.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        itemsIndexed(items) { index, entry ->
            val muted = if (entry.undone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            SectionCard(Modifier.testTag("history-item-$index")) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.row.title, style = MaterialTheme.typography.bodyLarge, color = muted)
                        if (entry.row.detail.isNotEmpty()) {
                            Text(entry.row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.undone) Text("Undone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    entry.row.amountText?.let { amount ->
                        Text(amount, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.testTag("history-amount-$index"))
                    }
                }
            }
        }
    }
    pending?.let { step -> HistoryUndoReview(step, onConfirm = { confirm(step) }, onCancel = { pending = null }) }
}

/** Actualist's undo review: what changes back, from the current value to the proposed one. */
@Composable
private fun HistoryUndoReview(step: PendingStep, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val verb = if (step.undo) "Undo" else "Redo"
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("history-review"),
        title = { Text("$verb Action") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.review.title, style = MaterialTheme.typography.bodyMedium)
                step.review.entries.take(12).forEachIndexed { index, entry ->
                    Column(Modifier.testTag("history-review-entry-$index")) {
                        Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${entry.current} → ${entry.proposed}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (step.review.entries.size > 12) {
                    Text("and ${step.review.entries.size - 12} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("history-review-confirm")) { Text(verb) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("history-review-cancel")) { Text("Cancel") } },
    )
}
