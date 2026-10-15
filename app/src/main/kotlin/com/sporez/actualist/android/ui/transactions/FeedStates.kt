@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Pull-to-refresh around a feed: [onRefresh] runs while the indicator shows. */
@Composable
fun FeedPullToRefresh(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            if (!refreshing) {
                scope.launch {
                    refreshing = true
                    try {
                        onRefresh()
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        modifier = modifier.testTag("feed-refresh"),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** The line at the end of a feed with nothing older to load (Actualist's "Beginning of history"). */
@Composable
fun FeedEndMarker() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(
            "Beginning of history",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("feed-end"),
        )
    }
}

/** A feed that failed to load: the reason and Actualist's "Retry" (or "Retry Search" while searching). */
@Composable
fun FeedLoadError(message: String, searching: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp).testTag("feed-error"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry, modifier = Modifier.testTag("feed-retry")) { Text(if (searching) "Retry Search" else "Retry") }
    }
}

/** Shown in place of the saved filters when they could not be read. */
@Composable
fun SavedFiltersUnavailable(message: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).testTag("saved-filters-unavailable")) {
        Text("Saved Filters Unavailable", style = MaterialTheme.typography.labelLarge)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
