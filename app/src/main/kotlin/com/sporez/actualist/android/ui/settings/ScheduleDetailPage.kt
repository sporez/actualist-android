package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sporez.actualist.core.rules.ScheduleEnding
import com.sporez.actualist.core.schedules.ScheduleEditorModel
import com.sporez.actualist.core.schedules.ScheduleListItem
import com.sporez.actualist.core.schedules.ScheduleListPresentation

/** The management actions a schedule detail page offers; each opens the matching review or editor. */
internal class ScheduleDetailActions(
    val onEdit: () -> Unit,
    val onPost: () -> Unit,
    val onSkip: () -> Unit,
    val onComplete: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * A schedule's detail page (Actualist `ScheduleDetailView`): status, state, next date, repeat rule,
 * weekend and ending behavior, upcoming window and the transaction it posts, with a Manage Schedule
 * menu. It covers the screen like a page; Close returns to the list.
 */
@Composable
internal fun ScheduleDetailPage(
    item: ScheduleListItem,
    load: suspend () -> ScheduleEditorModel?,
    actions: ScheduleDetailActions,
    onDismiss: () -> Unit,
) {
    var model by remember { mutableStateOf<ScheduleEditorModel?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) { model = load() }
    val dates = ScheduleListPresentation.upcomingDates(model?.dateRule?.recurrence, item.nextDate)
    val hasActions = item.canEdit || item.canPost || item.canSkip || item.canComplete || item.canDelete
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("schedule-detail-close")) { Text("Close") }
                    Text(
                        "Schedule Details",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Box {
                        if (hasActions) {
                            TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("schedule-manage")) { Text("Manage Schedule") }
                        } else {
                            Text("", Modifier.padding(horizontal = 48.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (item.canEdit) ManageItem("Edit Schedule", "schedule-manage-edit") { menuOpen = false; actions.onEdit() }
                            if (item.canPost) ManageItem("Review Post", "schedule-manage-post") { menuOpen = false; actions.onPost() }
                            if (item.canSkip) ManageItem("Skip Next Date", "schedule-manage-skip") { menuOpen = false; actions.onSkip() }
                            if (item.canComplete) ManageItem("Mark Completed", "schedule-manage-complete") { menuOpen = false; actions.onComplete() }
                            if (item.canDelete) ManageItem("Delete Schedule", "schedule-manage-delete") { menuOpen = false; actions.onDelete() }
                        }
                    }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("schedule-detail"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(item.name, style = MaterialTheme.typography.headlineSmall)
                    Text(scheduleAmountText(item) ?: "Unavailable", style = MaterialTheme.typography.titleLarge)
                    Text("Schedule", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    DetailLine("Status", ScheduleListPresentation.statusLabel(item.status))
                    DetailLine("State", if (item.completed) "Completed" else "Active")
                    DetailLine("Next date", item.nextDate ?: "None")
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
                        DetailLine("Upcoming window", ScheduleListPresentation.upcomingWindowText(model?.customUpcomingLength, model?.defaultUpcomingLength ?: "7"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text("Transaction", style = MaterialTheme.typography.titleSmall)
                    DetailLine("Amount", scheduleAmountText(item) ?: "Unavailable")
                    DetailLine("Account", ScheduleListPresentation.accountText(item) ?: "None")
                    DetailLine("Payee", ScheduleListPresentation.payeeText(item) ?: "No payee")
                    DetailLine("Automatic posting", if (item.postsTransaction) "Yes" else "No")
                    Text("Upcoming dates", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    if (dates.isEmpty()) {
                        Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        for (date in dates) Text(date, modifier = Modifier.testTag("schedule-detail-date-$date"))
                    }
                    model?.unsupportedMessages?.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        if (hasActions) "Viewing this schedule does not change it. Management actions need a separate review."
                        else "This schedule is read-only in Actualist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ManageItem(label: String, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.testTag(tag))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, modifier = Modifier.weight(0.6f))
    }
}
