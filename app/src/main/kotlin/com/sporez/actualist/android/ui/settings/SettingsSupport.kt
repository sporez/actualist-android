package com.sporez.actualist.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.store.BudgetSession
import kotlinx.coroutines.launch

/**
 * The public source repository of this app, used for the bug report and privacy policy links
 * (`<url>/issues/new` and `<url>/blob/main/PRIVACY.md`). Blank hides the links; set it when the
 * repository address is decided.
 */
const val SUPPORT_REPOSITORY_URL = ""

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}

/** Actualist's Support page: copy or share the diagnostic report, bug report and privacy links, and what the report excludes. */
@Composable
fun SettingsSupportCard(session: BudgetSession, settings: AppSettings, hasServer: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val repository = SUPPORT_REPOSITORY_URL.trimEnd('/')
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Support", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "This build is experimental. If something goes wrong, include a diagnostic report with your bug report so the app and sync state can be investigated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    scope.launch {
                        val report = buildDiagnosticReport(context, session, settings, hasServer || current.serverUrl != null)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Actualist diagnostic report", report))
                        copied = true
                    }
                },
                modifier = Modifier.testTag("copy-diagnostics"),
            ) { Text(if (copied) "Diagnostic report copied" else "Copy diagnostic report") }
            if (repository.isNotEmpty()) {
                TextButton(onClick = { openLink(context, "$repository/issues/new") }, modifier = Modifier.testTag("bug-report")) { Text("Submit a bug report") }
                TextButton(onClick = { openLink(context, "$repository/blob/main/PRIVACY.md") }, modifier = Modifier.testTag("privacy-policy")) { Text("Privacy policy") }
            }
            Text(
                "Reports exclude credentials, server addresses, identifiers, names, budget contents, transaction details, and financial amounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("support-privacy-note"),
            )
        }
    }
}
