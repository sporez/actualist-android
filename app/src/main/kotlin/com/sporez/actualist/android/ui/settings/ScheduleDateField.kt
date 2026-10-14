@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A schedule's first date: the typed `YYYY-MM-DD` field (kept, tagged [tag], as the test-friendly
 * path) with a calendar button ("[tag]-pick") that opens a Material date picker.
 */
@Composable
internal fun ScheduleDateField(text: String, onTextChange: (String) -> Unit, tag: String, label: String = "First date (YYYY-MM-DD)") {
    var picking by remember { mutableStateOf(false) }
    val parsed = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text,
            onTextChange,
            label = { Text(label) },
            singleLine = true,
            isError = parsed == null,
            modifier = Modifier.weight(1f).testTag(tag),
        )
        TextButton(onClick = { picking = true }, modifier = Modifier.testTag("$tag-pick")) { Text("Pick") }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parsed ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            onTextChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                        }
                        picking = false
                    },
                    modifier = Modifier.testTag("$tag-pick-ok"),
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}
