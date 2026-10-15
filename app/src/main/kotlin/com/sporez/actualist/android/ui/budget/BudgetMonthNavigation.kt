package com.sporez.actualist.android.ui.budget

import com.sporez.actualist.android.ui.components.Dates
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sporez.actualist.core.budget.Months
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Actualist's "Choose budget month": a year with previous and next arrows over a grid of its
 * twelve months, and Today to jump to the current month.
 */
@Composable
fun MonthChooserDialog(selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val current = Months.current()
    var year by remember { mutableIntStateOf(selected.take(4).toIntOrNull() ?: current.take(4).toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose budget month") },
        text = {
            Column(Modifier.testTag("month-chooser"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { year -= 1 }, modifier = Modifier.testTag("month-chooser-previous-year")) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous year")
                    }
                    Text(
                        "$year",
                        Modifier.weight(1f).testTag("month-chooser-year"),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { year += 1 }, modifier = Modifier.testTag("month-chooser-next-year")) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next year")
                    }
                }
                for (rowStart in listOf(1, 4, 7, 10)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (number in rowStart until rowStart + 3) {
                            val id = Months.id(year, number)
                            val label = Dates.shortMonthName(number)
                            val modifier = Modifier.weight(1f).testTag("month-pick-$id")
                            if (id == selected) {
                                FilledTonalButton(onClick = { onPick(id) }, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1) }
                            } else {
                                OutlinedButton(onClick = { onPick(id) }, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                                    Text(label, maxLines = 1, color = if (id == current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }, modifier = Modifier.testTag("month-chooser-today")) { Text("Today") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Actualist's month swipe: a sideways drag past about a third of a phone's width, mostly
 * horizontal, goes to the previous month (rightward) or the next (leftward). Does nothing when
 * [enabled] is false, so the gesture can sit behind a preference.
 */
@Composable
fun Modifier.monthSwipe(enabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit): Modifier {
    if (!enabled) return this
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    return pointerInput(Unit) {
        val threshold = 96.dp.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                if (total >= threshold) previous() else if (total <= -threshold) next()
                total = 0f
            },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                total += amount
            },
        )
    }
}
