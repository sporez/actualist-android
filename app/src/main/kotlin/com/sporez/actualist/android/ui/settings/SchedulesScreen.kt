package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sporez.actualist.android.ui.Loadable
import com.sporez.actualist.android.ui.MessageState
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.LoadingBox
import com.sporez.actualist.android.ui.components.MessageBox
import com.sporez.actualist.android.ui.components.MoneyText
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.reloading
import com.sporez.actualist.core.schedules.ScheduleListItem
import com.sporez.actualist.core.schedules.ScheduleStatus
import com.sporez.actualist.core.schedules.Schedules
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class SchedulesViewModel(private val session: BudgetSession) : ViewModel() {
    val messages = MessageState()
    private fun today() = LocalDate.now().toString()
    val schedules = reloading(session, MutableStateFlow(Unit)) { Schedules(database, clock).list(today()) }

    fun post(item: ScheduleListItem) = write("Posted ${item.name}") { Schedules(database, clock).postNow(item.id, today()) }
    fun skip(item: ScheduleListItem) = write("Skipped to the next date") { Schedules(database, clock).skipNextDate(item.id) }
    fun complete(item: ScheduleListItem) = write("Completed") { Schedules(database, clock).complete(item.id) }
    fun delete(item: ScheduleListItem) = write("Deleted ${item.name}") { Schedules(database, clock).delete(item.id) }

    private fun write(done: String, block: BudgetSession.() -> Any?) {
        viewModelScope.launch {
            try {
                session.write { block() }
                messages.show(done)
            } catch (error: Exception) {
                messages.show(error.message ?: "The schedule could not be changed.")
            }
        }
    }
}

@Composable
fun SchedulesScreen(viewModel: SchedulesViewModel) {
    val state by viewModel.schedules.collectAsState()
    val message by viewModel.messages.message.collectAsState()
    when (val current = state) {
        Loadable.Loading -> LoadingBox()
        is Loadable.Failed -> ErrorBanner(current.message, Modifier.padding(16.dp))
        is Loadable.Ready -> if (current.value.isEmpty()) {
            MessageBox("No schedules", "Schedules created in Actual appear here.")
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("schedules-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("schedules-message")) } }
                items(current.value, key = { it.id }) { item -> ScheduleCard(item, viewModel) }
            }
        }
    }
}

@Composable
private fun ScheduleCard(item: ScheduleListItem, viewModel: SchedulesViewModel) {
    SectionCard(Modifier.testTag("schedule-${item.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                // Actual shows an approximate amount as "~$X" and only a between-amount as a range.
                when {
                    item.isApproximate && item.amountMinorUnits != null -> Row {
                        Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(item.amountMinorUnits!!, colored = true)
                    }
                    item.amountLower != null && item.amountUpper != null && item.amountLower != item.amountUpper ->
                        Text("${money(item.amountLower!!)} – ${money(item.amountUpper!!)}")
                    item.amountMinorUnits != null -> MoneyText(item.amountMinorUnits!!, colored = true)
                }
            }
            Text(
                listOfNotNull(item.payeeName, item.accountName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${item.recurrenceDescription} · next ${item.nextDate ?: "none"}", style = MaterialTheme.typography.bodySmall)
            Text(
                statusLabel(item.status),
                style = MaterialTheme.typography.labelLarge,
                color = when (item.status) {
                    ScheduleStatus.MISSED -> MaterialTheme.colorScheme.error
                    ScheduleStatus.DUE, ScheduleStatus.UPCOMING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag("schedule-status-${item.id}"),
            )
            Row {
                if (item.canPost) TextButton(onClick = { viewModel.post(item) }, modifier = Modifier.testTag("schedule-post-${item.id}")) { Text("Post now") }
                if (item.canSkip) TextButton(onClick = { viewModel.skip(item) }, modifier = Modifier.testTag("schedule-skip-${item.id}")) { Text("Skip") }
                if (item.canComplete) TextButton(onClick = { viewModel.complete(item) }) { Text("Complete") }
                if (item.canDelete) TextButton(onClick = { viewModel.delete(item) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun statusLabel(status: ScheduleStatus) = when (status) {
    ScheduleStatus.COMPLETED -> "Completed"
    ScheduleStatus.PAID -> "Paid"
    ScheduleStatus.DUE -> "Due today"
    ScheduleStatus.UPCOMING -> "Upcoming"
    ScheduleStatus.MISSED -> "Missed"
    ScheduleStatus.SCHEDULED -> "Scheduled"
}
