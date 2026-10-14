package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.UndoHistory
import kotlinx.coroutines.launch

/** This session's edits, newest first, with undo and redo (Actualist's History, in memory as in Actual). */
@Composable
fun HistoryScreen(session: BudgetSession) {
    val revision by session.revision.collectAsState()
    val undoState by session.undoState.collectAsState()
    var items by remember { mutableStateOf<List<UndoHistory.Item>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(revision) { items = session.historyItems() }
    fun step(undo: Boolean) {
        scope.launch {
            message = try {
                if (undo) session.undo() else session.redo()
                null
            } catch (error: Exception) {
                error.message ?: "That could not be undone."
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("history-screen"), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step(undo = true) }, enabled = undoState.canUndo, modifier = Modifier.testTag("history-undo")) { Text("Undo") }
                OutlinedButton(onClick = { step(undo = false) }, enabled = undoState.canRedo, modifier = Modifier.testTag("history-redo")) { Text("Redo") }
            }
        }
        message?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-message")) } }
        if (items.isEmpty()) {
            item { Text("No edits in this session yet. History is kept until the budget closes, as in Actual.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        itemsIndexed(items) { index, entry ->
            SectionCard(Modifier.testTag("history-item-$index")) {
                Column(Modifier.padding(14.dp)) {
                    Text(entry.summary, style = MaterialTheme.typography.bodyLarge)
                    if (entry.undone) Text("Undone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
