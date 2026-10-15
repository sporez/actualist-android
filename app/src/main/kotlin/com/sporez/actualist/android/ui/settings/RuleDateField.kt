@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.rules.RuleDatePrecision
import com.sporez.actualist.core.rules.RuleDateValues
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A rule date condition's value: a calendar pick shown on a button tagged [tag]. With the "is"
 * operator the pick can stand for the whole month or year (chips "[tag]-precision-day|month|year"),
 * as Actual's `YYYY-MM` and `YYYY` matches; other operators need a full day.
 */
@Composable
internal fun RuleDateValueField(text: String, op: String, onTextChange: (String) -> Unit, tag: String, enabled: Boolean) {
    var picking by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(RuleDatePrecision.DAY) }
    val partialOk = RuleDateValues.allowsPartial(op)
    val precision = if (partialOk) RuleDateValues.precision(text) ?: chosen else RuleDatePrecision.DAY
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (partialOk) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for ((option, label) in listOf(RuleDatePrecision.DAY to "Day", RuleDatePrecision.MONTH to "Month", RuleDatePrecision.YEAR to "Year")) {
                    FilterChip(
                        selected = precision == option,
                        onClick = {
                            chosen = option
                            if (RuleDateValues.anchor(text) != null) onTextChange(RuleDateValues.convert(text, option))
                        },
                        enabled = enabled,
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 4.dp).testTag("$tag-precision-${option.name.lowercase()}"),
                    )
                }
            }
        }
        OutlinedButton(onClick = { picking = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Text(text.ifBlank { "Choose date…" })
        }
        Text(
            if (partialOk) "Pick a day, then choose Month or Year to match the whole month or year." else "Pick a calendar day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (RuleDateValues.anchor(text) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            onTextChange(RuleDateValues.format(date, precision))
                        }
                        picking = false
                    },
                    modifier = Modifier.testTag("$tag-ok"),
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
