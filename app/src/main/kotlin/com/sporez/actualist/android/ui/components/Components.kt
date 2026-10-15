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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.theme.LocalMoneyColors
import com.sporez.actualist.android.ui.theme.MoneyTextStyle
import com.sporez.actualist.android.ui.theme.spacing
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.privacy.SampleValues

/** When true, amounts render as bullets (Actualist's privacy mode). */
val LocalHideAmounts = compositionLocalOf { false }

/** When true, amounts are replaced by made-up ones, on screen only (Actualist's "Use Sample Values"). */
val LocalSampleValues = compositionLocalOf { false }

/**
 * True where the content below was already projected to sample values by [com.sporez.actualist.core.privacy.SampleValues]
 * (so totals add up); [money] then prints the amount as it is instead of hashing it a second time.
 */
val LocalValuesProjected = compositionLocalOf { false }

/** When false, positive amounts in [MoneyText] use the normal text colour instead of green. */
val LocalGreenIncomeAmounts = compositionLocalOf { true }

/**
 * A stable made-up amount for [minorUnits]: the sign is kept, the size is a pseudo-random value
 * under 500.00 derived from the real one, and zero stays zero.
 */
fun sampleMinorUnits(minorUnits: Long): Long {
    if (minorUnits == 0L) return 0L
    var h = minorUnits * -7046029254386353131L
    h = h xor (h ushr 29)
    h *= -4658895280553007687L
    h = h xor (h ushr 32)
    val magnitude = (Math.floorMod(h, 49_900L)) + 100L
    return if (minorUnits < 0) -magnitude else magnitude
}

@Composable
fun money(minorUnits: Long): String = when {
    LocalHideAmounts.current -> "•••••"
    LocalSampleValues.current && !LocalValuesProjected.current -> Money.format(sampleMinorUnits(minorUnits))
    else -> Money.format(minorUnits)
}

/** A plain amount, colored by sign when [colored]. */
@Composable
fun MoneyText(minorUnits: Long, modifier: Modifier = Modifier, colored: Boolean = false, style: androidx.compose.ui.text.TextStyle = MoneyTextStyle) {
    val colors = LocalMoneyColors.current
    val color = when {
        !colored -> MaterialTheme.colorScheme.onSurface
        minorUnits > 0 -> if (LocalGreenIncomeAmounts.current) colors.positive else MaterialTheme.colorScheme.onSurface
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
                .padding(horizontal = spacing(8.dp), vertical = spacing(3.dp)),
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
        modifier.fillMaxSize().padding(spacing(32.dp)),
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
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(spacing(12.dp)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = spacing(16.dp), vertical = spacing(12.dp)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        trailing()
    }
}

/** Actualist's note placeholder while Sample Values is on (`EntityNotesView` privacy content). */
@Composable
fun NotesHiddenMessage(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(spacing(16.dp)).testTag("notes-hidden"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(SampleValues.NOTES_HIDDEN_TITLE, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(SampleValues.NOTES_HIDDEN_DETAIL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = spacing(6.dp)))
    }
}

val Transparent = Color.Transparent
