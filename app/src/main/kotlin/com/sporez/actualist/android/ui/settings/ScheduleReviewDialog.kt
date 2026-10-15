package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.schedules.ScheduleEditorModel
import com.sporez.actualist.core.schedules.ScheduleListItem
import com.sporez.actualist.core.schedules.ScheduleListPresentation
import com.sporez.actualist.core.schedules.ScheduleReviewPresentation

/** The schedule management steps that change a schedule and so get a review first (Actualist `ScheduleManagementSheet`). */
internal enum class ScheduleManageKind(val title: String, val confirm: String) {
    SKIP("Skip next date", "Skip"),
    COMPLETE("Complete schedule", "Complete"),
    DELETE("Delete schedule", "Delete"),
}

/** The pending post, if any, shown inside [SchedulePostReviewDialog]. */
internal sealed interface SchedulePostState {
    data object Idle : SchedulePostState
    data object Syncing : SchedulePostState
    data object Posting : SchedulePostState
    data class Failed(val message: String) : SchedulePostState
}

/**
 * Shows what a skip, complete or delete will do (amount, account, next date and what changes) and
 * only writes once confirmed.
 */
@Composable
internal fun ScheduleManageReviewDialog(
    kind: ScheduleManageKind,
    item: ScheduleListItem,
    load: suspend () -> ScheduleEditorModel?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var model by remember { mutableStateOf<ScheduleEditorModel?>(null) }
    LaunchedEffect(item.id) { model = load() }
    val skipTo = ScheduleReviewPresentation.nextDateAfterSkip(model?.dateRule?.recurrence, item.nextDate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kind.title}: ${item.name}") },
        text = {
            Column(Modifier.testTag("schedule-review"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                scheduleAmountText(item)?.let { Text("Amount: $it") }
                item.accountName?.let { Text("Account: $it") }
                item.payeeName?.let { Text("Payee: $it") }
                Text("Status: ${ScheduleListPresentation.statusLabel(item.status)}")
                Text("Next date: ${item.nextDate ?: "none"}")
                val consequence = when (kind) {
                    ScheduleManageKind.SKIP ->
                        if (skipTo != null) "No transaction is posted. The next date moves to $skipTo."
                        else "No transaction is posted. The schedule moves to its next date."
                    ScheduleManageKind.COMPLETE -> "The schedule is marked completed and stops showing as upcoming. No transaction is posted."
                    ScheduleManageKind.DELETE -> "The schedule is removed. Transactions it already posted stay."
                }
                Text(
                    consequence,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (kind == ScheduleManageKind.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("schedule-review-consequence"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("schedule-review-confirm")) {
                Text(kind.confirm, color = if (kind == ScheduleManageKind.DELETE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("schedule-review-cancel")) { Text("Cancel") }
        },
    )
}
