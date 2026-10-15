package com.sporez.actualist.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag

/** Width, in dp, from which the shell swaps the bottom bar for a side rail (as the budget's side-by-side months). */
const val WIDE_SHELL_BREAKPOINT_DP = 840

/** One destination in the shell's navigation. */
data class ShellDestination(val route: String, val label: String, val icon: ImageVector)

@Composable
fun isWideShell(): Boolean = LocalConfiguration.current.screenWidthDp >= WIDE_SHELL_BREAKPOINT_DP

/** The wide layout's counterpart to the bottom bar: the same tabs, plus Settings, down the leading edge. */
@Composable
fun AdaptiveNavigationRail(
    destinations: List<ShellDestination>,
    currentRoute: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    NavigationRail(Modifier.testTag("nav-rail")) {
        for (destination in destinations) {
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
                modifier = Modifier.testTag("tab-${destination.route}"),
            )
        }
        NavigationRailItem(
            selected = false,
            onClick = onOpenSettings,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("Settings") },
            modifier = Modifier.testTag("rail-settings"),
        )
    }
}
