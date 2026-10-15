package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.core.schedules.ScheduleAmountMode

/**
 * A schedule's amount: outflow or inflow, Exact / About / Range, and the amount (two amounts, "to",
 * for a range). Tags: "[tag]-outflow", "[tag]-inflow", "[tag]-type-exact|about|range", "[tag]-amount"
 * and "[tag]-amount-upper".
 */
@Composable
internal fun ScheduleAmountFields(
    mode: ScheduleAmountMode,
    onMode: (ScheduleAmountMode) -> Unit,
    outflow: Boolean,
    onOutflow: (Boolean) -> Unit,
    firstText: String,
    onFirst: (String) -> Unit,
    secondText: String,
    onSecond: (String) -> Unit,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = outflow, onClick = { onOutflow(true) }, label = { Text("Outflow") }, modifier = Modifier.testTag("$tag-outflow"))
            FilterChip(selected = !outflow, onClick = { onOutflow(false) }, label = { Text("Inflow") }, modifier = Modifier.padding(start = 6.dp).testTag("$tag-inflow"))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((option, label) in listOf(ScheduleAmountMode.EXACT to "Exact", ScheduleAmountMode.ABOUT to "About", ScheduleAmountMode.RANGE to "Range")) {
                FilterChip(
                    selected = mode == option,
                    onClick = { onMode(option) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 4.dp).testTag("$tag-type-${option.name.lowercase()}"),
                )
            }
        }
        AmountField(firstText, onFirst, label = if (mode == ScheduleAmountMode.RANGE) "From" else "Amount", tag = "$tag-amount")
        if (mode == ScheduleAmountMode.RANGE) {
            AmountField(secondText, onSecond, label = "To", tag = "$tag-amount-upper")
        } else if (mode == ScheduleAmountMode.ABOUT) {
            Text(
                "Matches amounts within 7.5% of this one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One-time schedules: the transaction must be on the date, or around it (Actual's `is` / `isapprox`). Tags "[tag]-date-match-is|isapprox". */
@Composable
internal fun ScheduleDateMatchChips(selected: String, onSelect: (String) -> Unit, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Date match", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for ((op, label) in listOf("is" to "On date", "isapprox" to "About this date")) {
                FilterChip(
                    selected = selected == op,
                    onClick = { onSelect(op) },
                    label = { Text(label) },
                    modifier = Modifier.padding(end = 4.dp).testTag("$tag-date-match-$op"),
                )
            }
        }
    }
}
