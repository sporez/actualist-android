package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.core.csv.CsvImportReview
import com.sporez.actualist.core.csv.CsvReviewRow
import com.sporez.actualist.core.importer.ImportDecisionKind

/** Where a CSV import is: nothing, reading the file, reviewing its rows, writing, or failed. */
sealed interface CsvImportState {
    data object Idle : CsvImportState
    data object Loading : CsvImportState
    data class Reviewing(val review: CsvImportReview, val included: Set<Int>) : CsvImportState
    data object Submitting : CsvImportState
    data class Failed(val message: String) : CsvImportState
}

/** What a row of the review would do, in Actualist's words. */
fun csvOutcomeTitle(kind: ImportDecisionKind): String = when (kind) {
    ImportDecisionKind.ADDED -> "New row"
    ImportDecisionKind.UPDATED -> "Update existing row"
    ImportDecisionKind.UNCHANGED -> "Duplicate, unchanged"
    ImportDecisionKind.SKIPPED_DELETED -> "Matches a deleted row"
    ImportDecisionKind.SKIPPED_BY_RULE -> "Skipped by a rule"
}

/** The one-line count summary under the review's title. */
fun csvImportSummary(review: CsvImportReview, included: Set<Int>): String {
    val kept = review.rows.count { it.selectable && it.index in included }
    return listOfNotNull(
        "$kept of ${review.rows.count { it.selectable }} rows will be imported",
        review.unchanged.takeIf { it > 0 }?.let { "$it already present" },
        review.skipped.takeIf { it > 0 }?.let { "$it skipped" },
    ).joinToString(" · ")
}

/** Actualist's "Import CSV" review: columns as read, each row as new, updated or a duplicate, then Import. */
@Composable
fun CsvImportReviewDialog(
    state: CsvImportState,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    when (state) {
        CsvImportState.Idle -> {}
        CsvImportState.Loading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Reading file") },
            text = { Text("Checking the CSV rows against this account…") },
            confirmButton = {},
            modifier = Modifier.testTag("csv-import-loading"),
        )
        CsvImportState.Submitting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Importing") },
            text = { Text("The selected rows are being saved together…") },
            confirmButton = {},
        )
        is CsvImportState.Failed -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Couldn't import") },
            text = { Text(state.message, modifier = Modifier.testTag("csv-import-failed")) },
            confirmButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("csv-import-close")) { Text("Close") } },
        )
        is CsvImportState.Reviewing -> {
            val review = state.review
            val kept = review.rows.count { it.selectable && it.index in state.included }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text("Import CSV") },
                text = {
                    Column(Modifier.testTag("csv-import-review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Review the rows before anything is added to this account.", style = MaterialTheme.typography.bodySmall)
                        Text(csvImportSummary(review, state.included), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("csv-import-summary"))
                        Text(
                            review.columns.joinToString(" · ") { column ->
                                val where = column.header ?: column.position?.let { "column ${it + 1}" } ?: "not in file"
                                "${column.field}: $where"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("csv-import-columns"),
                        )
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            itemsIndexed(review.rows, key = { _, row -> row.index }) { _, row ->
                                CsvReviewRowItem(row, row.index in state.included, onToggle)
                                HorizontalDivider()
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onConfirm, enabled = kept > 0, modifier = Modifier.testTag("csv-import-confirm")) {
                        Text(if (kept == 1) "Import 1 row" else "Import $kept rows")
                    }
                },
                dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("csv-import-cancel")) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun CsvReviewRowItem(row: CsvReviewRow, included: Boolean, onToggle: (Int) -> Unit) {
    val base = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("csv-import-row-${row.index}")
    Row(
        if (row.selectable) base.clickable { onToggle(row.index) } else base,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row.selectable) Checkbox(checked = included, onCheckedChange = { onToggle(row.index) })
        Column(Modifier.weight(1f)) {
            Text(csvOutcomeTitle(row.kind), style = MaterialTheme.typography.labelLarge)
            Text(
                listOf(row.row.date.toString(), row.row.payeeName.ifBlank { "No payee" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(money(row.row.amount), style = MaterialTheme.typography.bodyMedium)
    }
}

/** What an export would contain, with the ways to save or share it. */
@Composable
fun CsvExportReviewDialog(
    scope: String,
    count: Int?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export CSV") },
        text = {
            Column(Modifier.testTag("csv-export-review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (count == null) "Counting rows…" else "$count rows from $scope",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("csv-export-summary"),
                )
                Text(
                    "Account, Date, Payee, Notes, Category_Group, Category, Amount, Split_Amount, Cleared",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Text starting with = + - or @ is prefixed with an apostrophe so spreadsheets do not run it as a formula.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShare, enabled = count != null, modifier = Modifier.testTag("csv-export-share")) { Text("Share CSV…") }
                TextButton(onClick = onSave, enabled = count != null, modifier = Modifier.testTag("csv-export-save")) { Text("Save to file…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("csv-export-cancel")) { Text("Cancel") } },
    )
}
