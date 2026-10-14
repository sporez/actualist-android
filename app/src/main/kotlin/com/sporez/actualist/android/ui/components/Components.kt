package com.sporez.actualist.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.core.money.Money

/** When true, amounts render as bullets (Actualist's privacy mode). */
val LocalHideAmounts = compositionLocalOf { false }

@Composable
fun money(minorUnits: Long): String = if (LocalHideAmounts.current) "•••••" else Money.format(minorUnits)

/** A plain amount, colored by sign when [colored]. */
@Composable
fun MoneyText(minorUnits: Long, modifier: Modifier = Modifier, colored: Boolean = false, style: androidx.compose.ui.text.TextStyle = MoneyTextStyle) {
    val colors = LocalMoneyColors.current
    val color = when {
        !colored -> MaterialTheme.colorScheme.onSurface
        minorUnits > 0 -> colors.positive
        minorUnits < 0 -> colors.negative
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(money(minorUnits), modifier = modifier, color = color, style = style, maxLines = 1)
}

/** Actualist's available-balance pill: green when funded, grey at zero, red when overspent. */
@Composable
fun BalancePill(minorUnits: Long, modifier: Modifier = Modifier, carryover: Boolean = false) {
    val colors = LocalMoneyColors.current
    val (background, foreground) = when {
        minorUnits > 0 -> colors.positiveContainer to colors.onPositiveContainer
        minorUnits < 0 -> colors.negativeContainer to colors.onNegativeContainer
        else -> colors.neutralContainer to colors.onNeutralContainer
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .background(background, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(money(minorUnits), color = foreground, style = MoneyTextStyle, maxLines = 1)
        }
        // Actualist's carryover badge: a small arrow on the pill's top trailing corner.
        if (carryover) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(13.dp)
                    .background(background, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.ChevronRight,
                    contentDescription = "Rolls over",
                    tint = foreground,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

/** A rounded grouped card, as Actualist's list sections. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = { Column { content() } },
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageBox(title: String, message: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        action?.let { (label, onClick) -> Button(onClick = onClick, modifier = Modifier.padding(top = 16.dp)) { Text(label) } }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = modifier.fillMaxWidth()) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

val Transparent = Color.Transparent
