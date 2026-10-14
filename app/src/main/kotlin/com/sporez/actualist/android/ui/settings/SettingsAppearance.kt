package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.data.DisplaySize
import com.sporez.actualist.android.data.NamedTheme
import com.sporez.actualist.android.data.ThemeMode
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.android.ui.theme.namedPalette

/** Actualist's Appearance settings: theme (System/Dark/Light or a named theme), display size, amount colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceCard(settings: AppSettings) {
    val current by settings.settings.collectAsState()
    SectionCard {
        Column(Modifier.padding(16.dp)) {
            Text("Appearance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (mode in ThemeMode.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = current.namedTheme == null && current.themeMode == mode,
                        onClick = { settings.update { it.copy(themeMode = mode, namedTheme = null) } },
                        modifier = Modifier.testTag("theme-${mode.name.lowercase()}"),
                    )
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Named themes", style = MaterialTheme.typography.bodyLarge)
            for (theme in NamedTheme.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = current.namedTheme == theme,
                        onClick = { settings.update { it.copy(namedTheme = theme) } },
                        modifier = Modifier.testTag("named-theme-${theme.name.lowercase()}"),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(theme.title)
                    }
                    ThemePreviewStrip(theme)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Display size", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Scales text across the app: ${current.displaySize.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("display-size-label"),
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (size in DisplaySize.entries) {
                    FilterChip(
                        selected = current.displaySize == size,
                        onClick = { settings.update { it.copy(displaySize = size) } },
                        label = { Text(size.title, maxLines = 1) },
                        modifier = Modifier.testTag("display-size-${size.name.lowercase()}"),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Green income amounts")
                    Text("Show positive amounts in green", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = current.greenIncomeAmounts,
                    onCheckedChange = { value -> settings.update { it.copy(greenIncomeAmounts = value) } },
                    modifier = Modifier.testTag("green-income-amounts"),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide amounts")
                    Text("Show bullets instead of money, for screenshots", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = current.hideAmounts, onCheckedChange = { value -> settings.update { it.copy(hideAmounts = value) } }, modifier = Modifier.testTag("hide-amounts"))
            }
        }
    }
}

/** Actualist's ThemePreviewStrip: the theme's background, surface, control and accent side by side. */
@Composable
fun ThemePreviewStrip(theme: NamedTheme, modifier: Modifier = Modifier) {
    val palette = namedPalette(theme)
    Row(modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (color in listOf(palette.background, palette.surface, palette.control, palette.accent)) {
            Box(Modifier.size(width = 18.dp, height = 18.dp).background(color, RoundedCornerShape(4.dp)))
        }
    }
}
