package com.sporez.actualist.android.ui.budget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.money
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.core.budget.TrackingSummary

/**
 * Actualist's savings banner for a tracking budget: the planned savings of the current and later months
 * ("Projected Savings"), or what an earlier month actually saved ("Saved" / "Overspent"), with its income and expenses.
 */
@Composable
fun BudgetSavingsBanner(summary: TrackingSummary, month: String, currentMonth: String) {
    val projected = month >= currentMonth
    val title = if (projected) "Projected Savings" else if (summary.actualSavings < 0) "Overspent" else "Saved"
    val amount = if (projected) summary.plannedSavings else summary.actualSavings
    val income = if (projected) summary.budgetedIncome else summary.receivedIncome
    val expenses = if (projected) summary.budgetedExpenses else -summary.expenseActivity
    val colors = LocalMoneyColors.current
    val tint = when {
        amount < 0 -> colors.negative
        amount == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> colors.positive
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("budget-savings-summary")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(money(amount), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = tint, modifier = Modifier.testTag("savings-amount"))
            Row(Modifier.fillMaxWidth()) {
                Text("Income ${money(income)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Expenses ${money(expenses)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
