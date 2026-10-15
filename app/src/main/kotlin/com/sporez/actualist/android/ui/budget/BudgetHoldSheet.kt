package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.AmountField
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.budget.HoldReview
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.money.MoneyExpression

/**
 * Actualist's Hold for Next Month review: what can be held, an amount (a sum like `100+25` works), and the
 * "After Holding" To Budget and held amounts before anything is confirmed.
 */
@Composable
internal fun HoldDialog(screen: BudgetScreenState, initialText: String, viewModel: BudgetViewModel, onDone: () -> Unit, onDismiss: () -> Unit) {
    var review by remember { mutableStateOf<HoldReview?>(null) }
    var reviewLoaded by remember { mutableStateOf(false) }
    var reviewRetry by remember { mutableStateOf(0) }
    val appSettings = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.sporez.actualist.android.ActualistApplication).container.settings
    val sampleValues by appSettings.settings.collectAsState()
    var text by rememberSaveable { mutableStateOf(initialText) }
    var confirmingRelease by remember { mutableStateOf(false) }
    LaunchedEffect(screen.month.month, screen.month.toBudget, screen.month.forNextMonth, reviewRetry) {
        review = viewModel.holdReview()
        reviewLoaded = true
    }
    val nextMonth = monthTitle(Months.shifted(screen.month.month, 1))
    val current = review
    val amount = MoneyExpression.evaluate(text)
    val holdable = current?.holdableAmount ?: 0L
    val canHold = current != null && amount != null && amount > 0 && holdable > 0
    // Actual holds no more than is holdable.
    val holding = if (canHold && amount != null) minOf(amount, holdable) else 0L
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hold for Next Month") },
        text = {
            if (sampleValues.sampleValues) {
                Column(Modifier.testTag("hold-sample-values"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sample Values", style = MaterialTheme.typography.titleMedium)
                    Text(SAMPLE_VALUES_HOLD_MESSAGE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (current == null && !reviewLoaded) {
                CircularProgressIndicator()
            } else if (current == null) {
                Column(Modifier.testTag("hold-not-loaded"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The review is not loaded.")
                    TextButton(onClick = { reviewLoaded = false; reviewRetry += 1 }, modifier = Modifier.testTag("hold-try-again")) { Text("Try Again") }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("hold-sheet"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(monthTitle(screen.month.month), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HoldRow("To Budget", money(holdable), "hold-current-available")
                    HoldRow("Currently Held for $nextMonth", money(current.heldAmount), "hold-current-held")
                    HorizontalDivider()
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Amount to Hold", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(
                            onClick = { text = Money.format(maxOf(0L, holdable), symbol = "", grouping = false) },
                            enabled = holdable > 0,
                            modifier = Modifier.testTag("hold-use-all"),
                        ) { Text("Use All") }
                    }
                    AmountField(text, { text = it }, label = "Amount", tag = "hold-amount-field", expressions = true)
                    Text(
                        if (current.isAutomaticHold) "This month has an automatic income hold. Holding an amount replaces it for this month."
                        else if (holdable <= 0) "There is no unassigned money to hold in this month. You can still release an existing hold."
                        else "Set aside unassigned money for $nextMonth. Bank balances and category assignments won't change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text("After Holding", style = MaterialTheme.typography.titleSmall)
                    HoldRow("To Budget", if (canHold) money(holdable - holding) else "—", "hold-result-available")
                    HoldRow("Held for $nextMonth", if (canHold) money(current.manualHeldAmount + holding) else "—", "hold-result-held")
                    if (current.heldAmount != 0L) {
                        TextButton(
                            onClick = { confirmingRelease = true },
                            modifier = Modifier.testTag("release-hold"),
                        ) { Text(if (current.isAutomaticHold) "Disable current auto hold" else "Release ${money(current.heldAmount)} hold") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.hold(holding); onDone() },
                enabled = canHold && !sampleValues.sampleValues,
                modifier = Modifier.testTag("hold-confirm"),
            ) { Text("Hold ${if (canHold) money(holding) else ""}".trim()) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("hold-close")) { Text("Close") } },
    )
    val releasing = review
    if (confirmingRelease && releasing != null) {
        AlertDialog(
            onDismissRequest = { confirmingRelease = false },
            modifier = Modifier.testTag("release-hold-alert"),
            title = { Text(releaseHoldTitle(releasing) + "?") },
            text = {
                Text(
                    releaseHoldMessage(
                        releasing,
                        held = money(releasing.heldAmount),
                        manual = money(releasing.manualHeldAmount),
                        automatic = money(releasing.automaticHeldAmount),
                        month = monthTitle(screen.month.month),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingRelease = false; viewModel.releaseHold(); onDone() },
                    modifier = Modifier.testTag("release-hold-confirm"),
                ) { Text(if (releasing.automaticHeldAmount == 0L) "Release All" else releaseHoldTitle(releasing)) }
            },
            dismissButton = { TextButton(onClick = { confirmingRelease = false }, modifier = Modifier.testTag("release-hold-cancel")) { Text("Cancel") } },
        )
    }
}

internal const val SAMPLE_VALUES_HOLD_MESSAGE = "Turn off Sample Values to hold or release money."

@Composable
private fun HoldRow(label: String, value: String, tag: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MoneyTextStyle.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.testTag(tag))
    }
}

/** Actualist's "Release …?" alert wording for a hold: manual-only, automatic-only, or both. */
internal fun releaseHoldTitle(review: HoldReview): String = when {
    review.manualHeldAmount != 0L && review.automaticHeldAmount != 0L -> "Reset Manual Hold"
    review.isAutomaticHold -> "Disable Current Auto Hold"
    else -> "Release Held Money"
}

internal fun releaseHoldMessage(review: HoldReview, held: String, manual: String, automatic: String, month: String): String = when {
    review.manualHeldAmount != 0L && review.automaticHeldAmount != 0L ->
        "Reset the manual hold of $manual? The automatic hold of $automatic will take its place. You can then disable that current auto hold separately."
    review.isAutomaticHold -> "Release $held back to $month? Automatic holds for future months stay enabled."
    else -> "Release the full $held held for next month back to $month?"
}
