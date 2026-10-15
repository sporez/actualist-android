package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.sporez.actualist.android.ui.components.PickerDialog
import com.sporez.actualist.android.ui.components.PickerOption
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.money.MoneyExpression

/**
 * Actualist's Move Money for an envelope category: pick the other side (a category or To Budget), an amount
 * (a sum like `20+5` works), switch the direction, and see both sides' balances after the move before confirming.
 */
@Composable
internal fun MoveMoneyDialog(category: BudgetMonthCategory, screen: BudgetScreenState, viewModel: BudgetViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    var amountText by rememberSaveable(category.id) { mutableStateOf("") }
    // Out of this category (to the other side), or into it.
    var outgoing by rememberSaveable(category.id) { mutableStateOf(true) }
    // The other side: null is To Budget.
    var counterpartyId by rememberSaveable(category.id) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val others = screen.month.categoryGroups.filter { !it.isIncome }.flatMap { group -> group.categories.map { it to group } }.filter { it.first.id != category.id }
    val counterparty = counterpartyId?.let { id -> others.firstOrNull { it.first.id == id }?.first }
    val counterpartyName = counterparty?.name ?: "To Budget"
    val counterpartyBalance = counterparty?.balance ?: screen.month.toBudget
    val amount = MoneyExpression.evaluate(amountText)
    val canMove = amount != null && amount > 0
    val shown = if (canMove && amount != null) amount else 0L
    val focusedAfter = category.balance + if (outgoing) -shown else shown
    val counterpartyAfter = counterpartyBalance + if (outgoing) shown else -shown
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
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag("move-counterparty")) {
                    Text("$counterpartyName · ${money(counterpartyBalance)}", maxLines = 1)
                }
                AmountField(amountText, { amountText = it }, label = "Amount", tag = "move-amount-field", expressions = true)
                HorizontalDivider()
                Text("After Moving", style = MaterialTheme.typography.titleSmall)
                MoveResultRow(category.name, if (canMove) money(focusedAfter) else "—", "move-result-focused")
                MoveResultRow(counterpartyName, if (canMove) money(counterpartyAfter) else "—", "move-result-counterparty")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (amount != null) {
                        val other = counterparty?.id
                        viewModel.move(if (outgoing) category.id else other, if (outgoing) other else category.id, amount)
                    }
                    onDone()
                },
                enabled = canMove,
                modifier = Modifier.testTag("move-confirm"),
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (picking) {
        PickerDialog(
            title = if (outgoing) "Move to" else "Move from",
            options = listOf(PickerOption(null, "To Budget", money(screen.month.toBudget))) + others.map { (c, g) -> PickerOption(c.id, c.name, "${g.name} · ${money(c.balance)}") },
            onPick = { option ->
                counterpartyId = option.id
                picking = false
            },
            onDismiss = { picking = false },
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
