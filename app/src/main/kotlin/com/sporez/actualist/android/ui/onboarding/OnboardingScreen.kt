package com.sporez.actualist.android.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.data.LocalBudget
import com.sporez.actualist.android.sync.ServerConnection
import com.sporez.actualist.android.ui.components.ErrorBanner
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.server.RemoteFile
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    localBudgets: List<LocalBudget>,
    onDemo: () -> Unit,
    onOpenLocal: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp).testTag("onboarding"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Text("Actualist", style = MaterialTheme.typography.displaySmall)
        Text("A fast, local-first client for Actual Budget. Experimental Android build.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDemo, modifier = Modifier.fillMaxWidth().testTag("open-demo")) { Text("Explore the demo budget") }
        Text("No account or server needed. All demo data is synthetic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth().testTag("onboarding-connect")) { Text("Connect to an Actual server") }
        val downloaded = localBudgets.filter { !it.isDemo }
        if (downloaded.isNotEmpty()) {
            Text("On this device", style = MaterialTheme.typography.titleMedium)
            SectionCard {
                for (budget in downloaded) {
                    Text(budget.name, Modifier.fillMaxWidth().clickable { onOpenLocal(budget.id) }.padding(16.dp).testTag("local-${budget.id}"))
                }
            }
        }
    }
}

/**
 * Sign in to a self-hosted Actual server and download one budget. Only https URLs are
 * accepted (http only for this device), and the password is used once and never stored.
 */
@Composable
fun ServerScreen(server: ServerConnection, onDownloaded: (LocalBudget) -> Unit, onCancel: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("https://") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var encrypted by remember { mutableStateOf<RemoteFile?>(null) }
    var budgetPassword by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf(server.savedCustomHeaders()?.second.orEmpty()) }

    fun load() {
        scope.launch {
            busy = true
            error = null
            try {
                files = server.remoteBudgets()
            } catch (e: Exception) {
                error = e.message ?: "Could not list budgets."
            }
            busy = false
        }
    }

    fun download(file: RemoteFile, secret: String?) {
        scope.launch {
            busy = true
            error = null
            try {
                onDownloaded(server.download(file, secret))
            } catch (e: Exception) {
                error = e.message ?: "The budget could not be downloaded."
            }
            busy = false
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val openId by server.openId.collectAsState()
    LaunchedEffect(openId) {
        when (val outcome = openId) {
            ServerConnection.OpenIdOutcome.SignedIn -> {
                server.consumeOpenId()
                error = null
                load()
            }
            is ServerConnection.OpenIdOutcome.Failed -> {
                server.consumeOpenId()
                error = outcome.message
            }
            null -> {}
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("server-screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect to Actual", style = MaterialTheme.typography.headlineSmall)
        Text("Use your own server. Never point this experimental build at a budget you cannot afford to re-download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { ErrorBanner(it, Modifier.testTag("server-error")) }
        if (!server.isSignedIn) {
            OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("server-url-field"))
            OutlinedTextField(
                headersText,
                { headersText = it },
                label = { Text("Custom headers (optional), Name: value per line") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("server-custom-headers"),
            )
            OutlinedTextField(password, { password = it }, label = { Text("Server password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("server-password-field"))
            Button(
                enabled = !busy && url.length > 8 && password.isNotEmpty(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            server.saveCustomHeaders(url, headersText)
                            server.signIn(url, password)
                            password = ""
                            busy = false
                            load()
                        } catch (e: Exception) {
                            error = e.message ?: "Sign-in failed."
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("server-sign-in"),
            ) { Text("Sign in") }
            OutlinedButton(
                enabled = !busy && url.length > 8,
                onClick = {
                    scope.launch {
                        error = null
                        try {
                            server.saveCustomHeaders(url, headersText)
                            val authorize = server.beginOpenId(url, context.packageName)
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(authorize)))
                        } catch (e: android.content.ActivityNotFoundException) {
                            error = "No browser is available to finish OpenID sign-in."
                        } catch (e: Exception) {
                            error = e.message ?: "OpenID sign-in could not start."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("server-openid"),
            ) { Text("Sign in with OpenID") }
        } else if (files == null && !busy) {
            Button(onClick = { load() }, modifier = Modifier.testTag("server-list")) { Text("Show budgets") }
        }
        if (server.isSignedIn) {
            var newName by rememberSaveable { mutableStateOf("") }
            var newPassword by remember { mutableStateOf("") }
            val resolver = context.contentResolver
            val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                resolver.openInputStream(uri)?.use { input ->
                                    // Refuse implausibly large files (the importer also caps the extracted database).
                                    val data = input.readBytes()
                                    if (data.size > 512 * 1024 * 1024) throw IllegalArgumentException("The file is too large.")
                                    data
                                } ?: throw IllegalArgumentException("The file could not be read.")
                            }
                            val name = newName.trim().ifEmpty { com.sporez.actualist.core.server.PortableBudget.budgetName(bytes) ?: "Imported budget" }
                            onDownloaded(server.importBudget(bytes, name, newPassword.ifEmpty { null }))
                        } catch (e: Exception) {
                            error = e.message ?: "The budget could not be imported."
                        }
                        busy = false
                    }
                }
            }
            OutlinedTextField(newName, { newName = it }, label = { Text("New budget name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("new-budget-name"))
            OutlinedTextField(newPassword, { newPassword = it }, label = { Text("Encryption password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("new-budget-password"))
            OutlinedButton(
                enabled = !busy && newName.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            onDownloaded(server.createBudget(newName, newPassword.ifEmpty { null }))
                        } catch (e: Exception) {
                            error = e.message ?: "The budget could not be created."
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("new-budget-create"),
            ) { Text("Create a new budget on this server") }
            OutlinedButton(
                enabled = !busy,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth().testTag("import-budget-zip"),
            ) { Text("Import a budget ZIP as a new budget") }
        }
        if (busy) CircularProgressIndicator()
        files?.let { list ->
            if (list.isEmpty()) Text("No budgets on this server.")
            SectionCard {
                for (file in list) {
                    Column(Modifier.fillMaxWidth().clickable(enabled = !busy) { if (file.encryptKeyId != null) encrypted = file else download(file, null) }.padding(16.dp)) {
                        Text(file.name)
                        if (file.encryptKeyId != null) Text("End-to-end encrypted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        encrypted?.let { file ->
            OutlinedTextField(budgetPassword, { budgetPassword = it }, label = { Text("Encryption password for ${file.name}") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = { download(file, budgetPassword) }, enabled = budgetPassword.isNotEmpty() && !busy) { Text("Unlock and download") }
        }
        TextButton(onClick = onCancel, modifier = Modifier.testTag("server-cancel")) { Text("Cancel") }
    }
}
