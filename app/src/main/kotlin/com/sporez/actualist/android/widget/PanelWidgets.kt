package com.sporez.actualist.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.sporez.actualist.android.ActualistApplication
import com.sporez.actualist.core.money.Money

private fun money(panels: WidgetPanels, value: Long) = if (panels.hideAmounts) "•••" else Money.format(value)

@Composable
private fun Title(palette: WidgetPalette, text: String) {
    Text(text, style = TextStyle(color = palette.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    Spacer(GlanceModifier.height(6.dp))
}

@Composable
private fun ListRow(
    palette: WidgetPalette,
    left: String,
    right: String,
    rightColor: androidx.glance.unit.ColorProvider,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(left, style = TextStyle(color = palette.text, fontSize = 13.sp), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text(right, style = TextStyle(color = rightColor, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
    }
}

private suspend fun loadPanels(context: Context, accountIds: List<String>? = null): WidgetPanels? {
    val container = (context.applicationContext as ActualistApplication).container
    return runCatching { WidgetPanelData.load(context, container, accountIds = accountIds) }.getOrNull()
}

private fun paletteOf(panels: WidgetPanels?) = WidgetPalette(panels?.dark ?: true, panels?.named)

class AccountBalancesWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val panels = loadPanels(context, chosenIds(context, id))
        provideContent {
            val palette = paletteOf(panels)
            WidgetFrame(context, palette, "accounts") {
                if (panels == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    Title(palette, "Account balances")
                    if (panels.accounts.isEmpty()) Text("No accounts", style = TextStyle(color = palette.muted, fontSize = 13.sp))
                    panels.accounts.take(currentSizeClass().accountRows).forEach {
                        ListRow(
                            palette,
                            it.name,
                            money(panels, it.balance),
                            if (it.balance < 0) palette.danger else palette.text,
                            GlanceModifier.clickable(widgetOpenAction(context, "account/${it.id}")),
                        )
                    }
                }
            }
        }
    }
}

class RecentTransactionsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = WidgetResponsiveSizes

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val panels = loadPanels(context)
        provideContent {
            val palette = paletteOf(panels)
            WidgetFrame(context, palette, "spending") {
                if (panels == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    Title(palette, "Recent transactions")
                    if (panels.recent.isEmpty()) Text("No transactions", style = TextStyle(color = palette.muted, fontSize = 13.sp))
                    panels.recent.take(currentSizeClass().transactionRows).forEach {
                        ListRow(
                            palette,
                            "${it.payee}  ${it.date}",
                            money(panels, it.amount),
                            if (it.amount < 0) palette.text else palette.positive,
                            GlanceModifier.clickable(widgetOpenAction(context, "account/${it.accountId}")),
                        )
                    }
                }
            }
        }
    }
}

class NeedsAttentionWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val panels = loadPanels(context)
        provideContent {
            val palette = paletteOf(panels)
            WidgetFrame(context, palette, "budget") {
                if (panels == null) {
                    Text("Open Actualist to choose a budget", style = TextStyle(color = palette.text, fontSize = 14.sp))
                } else {
                    val attention = panels.attention
                    Title(palette, "Needs attention")
                    if (attention.issueCount == 0) {
                        Text("All caught up", style = TextStyle(color = palette.positive, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        Text("No overspent categories or uncategorized transactions.", style = TextStyle(color = palette.muted, fontSize = 12.sp))
                    } else {
                        if (attention.overspent.isNotEmpty()) {
                            Text("${attention.overspent.size} overspent", style = TextStyle(color = palette.danger, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                            attention.overspent.take(3).forEach { ListRow(palette, it.name, money(panels, it.balance), palette.danger) }
                            Spacer(GlanceModifier.height(6.dp))
                        }
                        if (attention.uncategorizedCount > 0) {
                            Text("${attention.uncategorizedCount} uncategorized", style = TextStyle(color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

class AccountBalancesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AccountBalancesWidget()
}

class RecentTransactionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentTransactionsWidget()
}

class NeedsAttentionWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NeedsAttentionWidget()
}
