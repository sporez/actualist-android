package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.android.ui.components.LocalMoneyFormat
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.money.MoneyExpression

/**
 * Slider range and detent for Move Money (Actualist's BudgetMoveMoneySliderMetrics): the track is 125%
 * of the paying side's available amount, never less than the typed amount, or 10.00 when both are zero.
 * Dragging up to the available amount holds there for a short stretch before the slider passes it.
 */
internal object MoveMoneySliderMetrics {
    private const val ZERO_AVAILABLE_MAXIMUM = 1_000L

    fun maximumAmount(baseline: Long, current: Long): Long {
        val scaled = (maxOf(0L, baseline) * 5 + 3) / 4
        val candidate = maxOf(scaled, maxOf(0L, current))
        return if (candidate > 0L) candidate else ZERO_AVAILABLE_MAXIMUM
    }

    /** The amount a drag to [proposed] lands on: held at [detent] for the next 4% of the track. */
    fun apply(proposed: Long, detent: Long, maximum: Long): Long {
        val value = maxOf(0L, proposed)
        if (detent <= 0L) return value
        val hold = maxOf(1L, maximum / 25)
        return if (value > detent && value <= detent + hold) detent else value
    }
}

/** One counterparty of a move (null is To Budget) with the amount typed for it. */
private data class MoveLeg(val counterpartyId: String?, val amountText: String)

/**
 * Actualist's Move Money for an envelope category: pick the other side (a category or To Budget), an amount
 * (a sum like `20+5` works), switch the direction, and see every balance after the move before confirming.
 * "Select Another" adds more counterparties, each with its own amount; all legs are written together,
 * so the whole move is one undo step.
 */
@Composable
internal fun MoveMoneyDialog(category: BudgetMonthCategory, screen: BudgetScreenState, viewModel: BudgetViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    // Out of this category (to the other sides), or into it.
    var outgoing by rememberSaveable(category.id) { mutableStateOf(true) }
    val legs = remember(category.id) { mutableStateListOf(MoveLeg(null, "")) }
    // Which leg the picker is choosing for; legs.size means a new one ("Select Another").
    var pickingFor by remember { mutableStateOf<Int?>(null) }
    val format = LocalMoneyFormat.current
    val others = screen.month.categoryGroups.filter { !it.isIncome }.flatMap { group -> group.categories.map { it to group } }.filter { it.first.id != category.id }

    fun balanceOf(id: String?): Long = if (id == null) screen.month.toBudget else others.firstOrNull { it.first.id == id }?.first?.balance ?: 0L
    fun nameOf(id: String?): String = if (id == null) "To Budget" else others.firstOrNull { it.first.id == id }?.first?.name ?: "To Budget"
    fun amountOf(leg: MoveLeg): Long? = MoneyExpression.evaluate(leg.amountText)?.takeIf { it > 0 }

    val amounts = legs.map { amountOf(it) }
    val canMove = amounts.isNotEmpty() && amounts.all { it != null }
    val total = amounts.sumOf { it ?: 0L }
    val focusedAfter = category.balance + if (outgoing) -total else total
    val usedIds = legs.map { it.counterpartyId }.toSet()
    val canAddAnother = (others.map { it.first.id } + listOf<String?>(null)).any { it !in usedIds }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (outgoing) "Move from ${category.name}" else "Move to ${category.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("move-money-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (outgoing) "From" else "To", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("move-focused-name"))
                        Text(money(category.balance), style = MoneyTextStyle)
                    }
                    IconButton(onClick = { outgoing = !outgoing }, modifier = Modifier.testTag("move-swap")) {
                        Icon(Icons.Filled.SwapVert, contentDescription = "Switch move money direction")
                    }
                }
                Text(
                    if (outgoing) "Move to" else "Move from",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("move-direction"),
                )
                legs.forEachIndexed { index, leg ->
                    // The first leg keeps the original tags; later ones are suffixed with their index.
                    val suffix = if (index == 0) "" else "-$index"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pickingFor = index }, modifier = Modifier.weight(1f).testTag("move-counterparty$suffix")) {
                            Text("${nameOf(leg.counterpartyId)} · ${money(balanceOf(leg.counterpartyId))}", maxLines = 1)
                        }
                        if (legs.size > 1) {
                            IconButton(onClick = { legs.removeAt(index) }, modifier = Modifier.testTag("move-leg-remove-$index")) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove this destination")
                            }
                        }
                    }
                    AmountField(leg.amountText, { legs[index] = leg.copy(amountText = it) }, label = "Amount", tag = "move-amount-field$suffix", expressions = true)
                    // The payer's available amount is the detent; the track runs 125% of it so a move can pass it.
                    val detent = if (outgoing) category.balance else balanceOf(leg.counterpartyId)
                    val current = amountOf(leg) ?: 0L
                    val maximum = MoveMoneySliderMetrics.maximumAmount(detent, current)
                    val overshooting = detent > 0L && current > detent
                    Slider(
                        value = current.coerceAtMost(maximum).toFloat(),
                        onValueChange = { raw ->
                            val snapped = MoveMoneySliderMetrics.apply(raw.toLong(), detent, maximum)
                            legs[index] = leg.copy(amountText = if (snapped == 0L) "" else format.editText(snapped))
                        },
                        valueRange = 0f..maximum.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = if (overshooting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            activeTrackColor = if (overshooting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.testTag("move-slider$suffix"),
                    )
                    if (overshooting) {
                        Text("Past the available amount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("move-past-available$suffix"))
                    }
                }
                if (canAddAnother) {
                    TextButton(onClick = { pickingFor = legs.size }, modifier = Modifier.testTag("move-add-another")) { Text("Select Another") }
                }
                HorizontalDivider()
                Text("After Moving", style = MaterialTheme.typography.titleSmall)
                MoveResultRow(category.name, if (canMove) money(focusedAfter) else "—", "move-result-focused")
                legs.forEachIndexed { index, leg ->
                    val amount = amounts[index]
                    val after = balanceOf(leg.counterpartyId) + if (outgoing) (amount ?: 0L) else -(amount ?: 0L)
                    MoveResultRow(nameOf(leg.counterpartyId), if (amount != null) money(after) else "—", "move-result-counterparty${if (index == 0) "" else "-$index"}")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canMove) {
                        viewModel.moveAll(
                            legs.mapIndexed { index, leg ->
                                val amount = amounts[index] ?: 0L
                                if (outgoing) Triple<String?, String?, Long>(category.id, leg.counterpartyId, amount) else Triple<String?, String?, Long>(leg.counterpartyId, category.id, amount)
                            },
                        )
                    }
                    onDone()
                },
                enabled = canMove,
                modifier = Modifier.testTag("move-confirm"),
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    pickingFor?.let { target ->
        // A counterparty already used by another leg is not offered again.
        val takenElsewhere = legs.filterIndexed { index, _ -> index != target }.map { it.counterpartyId }.toSet()
        val all = listOf(PickerOption(null, "To Budget", money(screen.month.toBudget))) +
            others.map { (c, g) -> PickerOption(c.id, c.name, "${g.name} · ${money(c.balance)}") }
        PickerDialog(
            title = if (outgoing) "Move to" else "Move from",
            options = all.filter { it.id !in takenElsewhere },
            onPick = { option ->
                if (target >= legs.size) legs.add(MoveLeg(option.id, "")) else legs[target] = legs[target].copy(counterpartyId = option.id)
                pickingFor = null
            },
            onDismiss = { pickingFor = null },
        )
    }
}

@Composable
private fun MoveResultRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MoneyTextStyle.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag(tag))
    }
}
