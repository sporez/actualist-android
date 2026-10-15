package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.core.rules.ScheduleFrequency
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val startFormat = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

/** The recurrences "Make schedule" offers. */
val scheduleConversionFrequencies: List<Pair<ScheduleFrequency, String>> = listOf(
    ScheduleFrequency.WEEKLY to "Every week",
    ScheduleFrequency.MONTHLY to "Every month",
    ScheduleFrequency.YEARLY to "Every year",
)

/** Actualist's make-a-schedule review: what the schedule will be, before it is created. */
@Composable
fun ScheduleConversionReviewDialog(
    defaultName: String,
    amount: Long,
    accountName: String,
    payeeName: String,
    start: LocalDate,
    onConfirm: (name: String, frequency: ScheduleFrequency) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var frequency by rememberSaveable { mutableStateOf(ScheduleFrequency.MONTHLY.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("schedule-review"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Review the schedule before it is created. The transaction stays as it is.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("schedule-review-name"))
                ReviewLine("Amount", money(amount) + " (approximate)", "schedule-review-amount")
                ReviewLine("Account", accountName, "schedule-review-account")
                ReviewLine("Payee", payeeName.ifBlank { "No payee" }, "schedule-review-payee")
                ReviewLine("Starts", start.format(startFormat), "schedule-review-start")
                Text("Repeats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((option, label) in scheduleConversionFrequencies) {
                    Row(
                        Modifier.fillMaxWidth().clickable { frequency = option.name }.testTag("schedule-review-freq-${option.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = frequency == option.name, onClick = { frequency = option.name })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), ScheduleFrequency.valueOf(frequency)) },
                modifier = Modifier.testTag("schedule-review-create"),
            ) { Text("Create schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("schedule-review-cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun ReviewLine(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(tag))
    }
}
