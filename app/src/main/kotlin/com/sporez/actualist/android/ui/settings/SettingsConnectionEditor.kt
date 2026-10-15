package com.sporez.actualist.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.core.server.ServerConnectionSecurity
import com.sporez.actualist.core.server.ServerFailover
import kotlinx.coroutines.launch

/** The unencrypted-connection warning for [url], shown when it is plain http to a local-network host; nothing otherwise. */
@Composable
fun LocalHttpWarning(url: String, modifier: Modifier = Modifier) {
    val warning = ServerConnectionSecurity.warningFor(ServerFailover.normalize(url)) ?: return
    Text(
        warning,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag("local-http-warning"),
    )
}

/**
 * Settings, Server card: the saved address with the local-network warning, and Actualist's Save
 * Connection (edit the address and password, test the connection, then save).
 */
@Composable
fun SettingsConnectionEditor(server: ServerConnection, serverUrl: String) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    LocalHttpWarning(serverUrl)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Server address", Modifier.weight(1f))
        TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-connection")) { Text("Edit") }
    }
    if (editing) {
        var url by remember { mutableStateOf(serverUrl) }
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val rejection = ServerFailover.rejection(url)
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text("Connection") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; error = null },
                        label = { Text("Server URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().testTag("connection-url-field"),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Server password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().testTag("connection-password-field"),
                    )
                    Text(
                        "Leave the password empty to keep the current sign-in when the address is the same. Your password is not stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LocalHttpWarning(url)
                    (error ?: rejection)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connection-error"))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && rejection == null && url.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                // Saved headers belong to one origin; a different address starts without them.
                                val savedHeaders = server.savedCustomHeaders()
                                val parsed = if (savedHeaders != null && ServerHeaderScope.sameOrigin(savedHeaders.first, ServerFailover.normalize(url))) {
                                    HeaderDraft.validatedFromText(savedHeaders.second)
                                } else {
                                    emptyList()
                                }
                                server.saveConnection(url, password, parsed)
                                editing = false
                            } catch (failure: Exception) {
                                error = failure.message ?: "The connection could not be saved."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("connection-save"),
                ) { Text(if (busy) "Checking" else "Save Connection") }
            },
            dismissButton = { TextButton(onClick = { editing = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}
