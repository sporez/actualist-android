@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.launch

/**
 * Pull-to-refresh around a whole screen (Budget, Accounts, Reports, History): [onRefresh] runs while the
 * indicator shows. [tag] names the refresh container for tests; its state description is "Refreshing" while it runs.
 */
@Composable
fun ScreenPullToRefresh(
    tag: String,
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
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
        modifier = modifier.testTag(tag).semantics { stateDescription = if (refreshing) "Refreshing" else "Idle" },
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
