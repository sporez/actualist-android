package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.core.transactions.BatchReview
import com.sporez.actualist.core.transactions.BatchReviewPlanner
import com.sporez.actualist.core.transactions.BatchReviewRow
import com.sporez.actualist.core.transactions.BatchRowStatus

/** One transaction in a merge review, in the order the user selected it. */
data class MergeInputView(
    val id: String,
    val label: String,
    val title: String,
    val context: String,
    val amount: Long,
    val outcome: String?,
    val detail: String,
)

/** What a merge would do (Actualist's `TransactionMergeReviewDisplay`); [blocked] explains why it cannot run. */
data class MergeReviewState(
    val inputs: List<MergeInputView>,
    val keptLabel: String?,
    val droppedLabel: String?,
    val reconciledCount: Int,
    val blocked: String?,
)

private const val MERGE_SUBTITLE = "Input 1 is the first transaction you selected. Input 2 is the second."

/** Before → after review of a batch action; nothing is written until the user confirms. */
@Composable
fun BatchReviewDialog(review: BatchReview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("batch-review"),
        title = { Text(review.title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryLine("Selected transactions", review.selectedCount.toString())
                SummaryLine("Rows changing", review.changedCount.toString())
                if (review.skippedCount > 0) SummaryLine("Skipped", review.skippedCount.toString())
                if (review.needsReconciledConfirmation) {
                    Text(
                        review.reconciledMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("batch-review-reconciled"),
                    )
                }
                HorizontalDivider()
                review.rows.forEach { ReviewRow(it) }
                Text(
                    BatchReviewPlanner.HISTORY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("batch-review-hint"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = review.rows.isNotEmpty(), modifier = Modifier.testTag("batch-review-confirm")) {
                Text(review.confirmationTitle)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("batch-review-cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReviewRow(row: BatchReviewRow) {
    Column(Modifier.fillMaxWidth().testTag("batch-review-row-${row.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(row.payee, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            MoneyText(row.amount, colored = true)
        }
        if (row.context.isNotBlank()) Text(row.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            row.status.label,
            style = MaterialTheme.typography.labelMedium,
            color = when (row.status) {
                BatchRowStatus.WILL_CHANGE -> MaterialTheme.colorScheme.primary
                BatchRowStatus.NEEDS_CONFIRMATION -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        row.explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        row.effects.forEach { effect ->
            Text("${effect.title}: ${effect.before} → ${effect.after}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** Which of the two selected transactions a merge keeps, and what happens to the other one. */
@Composable
fun MergeReviewDialog(review: MergeReviewState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("merge-review"),
        title = { Text("Review Merge Transactions") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MERGE_SUBTITLE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                review.blocked?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("merge-review-blocked"))
                }
                if (review.blocked == null) {
                    review.keptLabel?.let { SummaryLine("Kept", it) }
                    review.droppedLabel?.let { SummaryLine("Dropped", it) }
                    if (review.reconciledCount > 0) {
                        Text(
                            "${review.reconciledCount} reconciled transaction${if (review.reconciledCount == 1) " is" else "s are"} connected to this merge.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                review.inputs.forEach { input ->
                    Column(Modifier.fillMaxWidth().testTag("merge-review-input-${input.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(input.label + (input.outcome?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth()) {
                            Text(input.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            MoneyText(input.amount, colored = true)
                        }
                        if (input.context.isNotBlank()) Text(input.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(input.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    BatchReviewPlanner.HISTORY_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("merge-review-hint"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = review.blocked == null, modifier = Modifier.testTag("merge-review-confirm")) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("merge-review-cancel")) { Text("Cancel") } },
    )
}

/** Actualist's confirmation before a change touches a reconciled transaction. */
@Composable
fun ReconciledGuardDialog(
    presentation: com.sporez.actualist.core.transactions.ReconciledMutationPresentation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("reconciled-guard"),
        title = { Text(presentation.title) },
        text = { Text(presentation.message) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("reconciled-guard-confirm")) { Text(presentation.confirmationTitle) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("reconciled-guard-cancel")) { Text("Cancel") } },
    )
}
