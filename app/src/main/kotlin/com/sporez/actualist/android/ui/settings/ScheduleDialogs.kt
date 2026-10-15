package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.core.rules.ScheduleEnding
import com.sporez.actualist.core.schedules.ScheduleEditorModel
import com.sporez.actualist.core.schedules.ScheduleListItem
import com.sporez.actualist.core.schedules.ScheduleListPresentation
import com.sporez.actualist.core.schedules.ScheduleUpcomingWindow

/** The amount text of a schedule row: "~$X" for approximate, a range for between, else the amount. */
@Composable
internal fun scheduleAmountText(item: ScheduleListItem): String? = when {
    item.isApproximate && item.amountMinorUnits != null -> "~" + money(item.amountMinorUnits!!)
    item.amountLower != null && item.amountUpper != null && item.amountLower != item.amountUpper ->
        "${money(item.amountLower!!)} – ${money(item.amountUpper!!)}"
    item.amountMinorUnits != null -> money(item.amountMinorUnits!!)
    else -> null
}

/**
 * The review before a schedule posts: the transaction date is the scheduled date by default, or
 * today (Actualist `SchedulePostingReviewView`). [onConfirm] receives true for "Post today".
 */
@Composable
internal fun SchedulePostReviewDialog(
    item: ScheduleListItem,
    today: String,
    onConfirm: (postsToday: Boolean) -> Unit,
    onDismiss: () -> Unit,
    state: SchedulePostState = SchedulePostState.Idle,
    syncsFirst: Boolean = false,
) {
    val busy = state is SchedulePostState.Syncing || state is SchedulePostState.Posting
    var postsToday by rememberSaveable { mutableStateOf(false) }
    val scheduled = item.nextDate
    val early = postsToday && scheduled != null && today < scheduled
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post ${item.name}") },
        text = {
            Column(Modifier.testTag("schedule-post-review"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                scheduleAmountText(item)?.let { Text("Amount: $it") }
                item.accountName?.let { Text("Account: $it") }
                item.payeeName?.let { Text("Payee: $it") }
                Text("Status: ${ScheduleListPresentation.statusLabel(item.status)}")
                Text("Transaction date", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Row {
                    FilterChip(
                        selected = !postsToday,
                        onClick = { postsToday = false },
                        label = { Text("Scheduled date · ${scheduled ?: "none"}") },
                        modifier = Modifier.padding(end = 4.dp).testTag("schedule-post-date-scheduled"),
                    )
                }
                Row {
                    FilterChip(
                        selected = postsToday,
                        onClick = { postsToday = true },
                        label = { Text("Post today · $today") },
                        modifier = Modifier.testTag("schedule-post-date-today"),
                    )
                }
                if (syncsFirst) {
                    Text(
                        "Your budget syncs first, so a payment another device already added isn't added twice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("schedule-post-sync-notice"),
                    )
                }
                when (state) {
                    SchedulePostState.Syncing -> Text("Syncing before post…", modifier = Modifier.testTag("schedule-post-progress"))
                    SchedulePostState.Posting -> Text("Posting…", modifier = Modifier.testTag("schedule-post-progress"))
                    is SchedulePostState.Failed -> Column(Modifier.testTag("schedule-post-failure")) {
                        Text("Transaction Not Posted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    SchedulePostState.Idle -> Unit
                }
                if (early) {
                    Text(
                        "The transaction will be dated today. The schedule may still show $scheduled as upcoming.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("schedule-post-early-notice"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(postsToday) }, enabled = !busy, modifier = Modifier.testTag("schedule-post-confirm")) {
                Text(if (state is SchedulePostState.Failed) "Try Again" else "Post transaction")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("schedule-post-cancel")) { Text("Cancel") }
        },
    )
}

/** A schedule's details: amount, account, payee, repeat rule and its next dates (Actualist `ScheduleDetailView`). */
@Composable
internal fun ScheduleDetailDialog(
    item: ScheduleListItem,
    load: suspend () -> ScheduleEditorModel?,
    onDismiss: () -> Unit,
) {
    var model by remember { mutableStateOf<ScheduleEditorModel?>(null) }
    LaunchedEffect(item.id) { model = load() }
    val dates = ScheduleListPresentation.upcomingDates(model?.dateRule?.recurrence, item.nextDate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("schedule-detail"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailLine("Amount", scheduleAmountText(item) ?: "Unavailable")
                DetailLine("Status", ScheduleListPresentation.statusLabel(item.status))
                DetailLine("Account", item.accountName ?: "None")
                DetailLine("Payee", item.payeeName ?: "None")
                DetailLine("Repeats", item.recurrenceDescription)
                model?.dateRule?.recurrence?.let { recurrence ->
                    DetailLine("Weekend", if (recurrence.skipWeekend) "Moves off weekends" else "No change")
                    DetailLine(
                        "Ends",
                        when (val ending = recurrence.ending) {
                            is ScheduleEnding.Never -> "Never"
                            is ScheduleEnding.AfterOccurrences -> "After ${ending.count} times"
                            is ScheduleEnding.OnDate -> "On ${ending.dayId}"
                        },
                    )
                }
                if (model != null) {
                    DetailLine("Upcoming window", ScheduleUpcomingWindow.label(model?.customUpcomingLength))
                }
                DetailLine("Posts automatically", if (item.postsTransaction) "Yes" else "No")
                Text("Upcoming dates", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                if (dates.isEmpty()) {
                    Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for (date in dates) Text(date, modifier = Modifier.testTag("schedule-detail-date-$date"))
                }
                model?.unsupportedMessages?.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("schedule-detail-close")) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, modifier = Modifier.weight(0.6f))
    }
}
