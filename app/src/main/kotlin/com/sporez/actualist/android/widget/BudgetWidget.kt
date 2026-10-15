package com.sporez.actualist.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.MainActivity
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.money.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the widget shows: the selected budget's To Budget this month and its net worth. */
data class WidgetSummary(val budgetName: String, val month: String, val toBudget: Long, val netWorth: Long, val hideAmounts: Boolean, val dark: Boolean = true, val named: com.sporez.actualist.android.data.NamedTheme? = null)

object WidgetData {
    /** Reads through the session hub, so an open budget is shared rather than opened twice. */
    suspend fun load(container: AppContainer, today: LocalDate = LocalDate.now(), context: Context? = null): WidgetSummary? = withContext(Dispatchers.IO) {
        val settings = container.settings.settings.value
        val id = settings.selectedBudgetId ?: return@withContext null
        val session = container.sessions.acquire(id) { container.library.open(id) ?: error("The selected budget is no longer on this device.") }
        try {
            val month = Months.id(today.year, today.monthValue)
            session.read {
                WidgetSummary(
                    budgetName = name,
                    month = month,
                    toBudget = budget.fetchBudgetMonth(month).toBudget,
                    netWorth = ledger.accounts().filter { !it.closed }.sumOf { it.balance },
                    hideAmounts = settings.hideAmounts,
                    dark = settings.namedTheme?.dark ?: context?.let { WidgetPalette.resolve(it, settings.themeMode).dark } ?: (settings.themeMode != com.sporez.actualist.android.data.ThemeMode.LIGHT),
                    named = settings.namedTheme,
                )
            }
        } finally {
            container.sessions.release(session)
        }
    }

    /** Redraws every placed widget; cheap when none is placed. */
    suspend fun refresh(context: Context) {
        Widgets.updateAll(context)
    }
}

class BudgetWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ActualistApplication).container
        val summary = runCatching { WidgetData.load(container, context = context) }.getOrNull()
        provideContent { Content(summary) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(summary: WidgetSummary?) {
        val palette = WidgetPalette(summary?.dark ?: true, summary?.named)
        val text = palette.text
        val muted = palette.muted
        fun amount(value: Long) = if (summary?.hideAmounts == true) "•••" else Money.format(value)
        Column(
            GlanceModifier.fillMaxSize().background(palette.background).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (summary == null) {
                Text("Open Actualist to choose a budget", style = TextStyle(color = text, fontSize = 14.sp))
                return@Column
            }
            Text(summary.budgetName, style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
            Spacer(GlanceModifier.height(6.dp))
            Text("To Budget", style = TextStyle(color = muted, fontSize = 12.sp))
            Text(amount(summary.toBudget), style = TextStyle(color = text, fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(6.dp))
            Text("Net worth ${amount(summary.netWorth)}", style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
        }
    }
}

class BudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidget()
}
