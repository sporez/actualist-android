package com.sporez.actualist.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.sporez.actualist.android.sync.BackgroundSyncRecord
import com.sporez.actualist.android.ui.components.SectionCard
import com.sporez.actualist.core.server.DiagnosticRedactor
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant

private const val LICENCES_TEXT = """Actualist for Android is an experimental port of Actualist (https://github.com/sporez/actualist), licensed under the GNU General Public License v3.0. This project uses the same license (see LICENSE).

Actual Budget (https://github.com/actualbudget/actual) is licensed under the MIT License, Copyright James Long. Actualist's storage and sync behaviour is compatible with it. The starter database schema, migration list, default dashboard definitions, title-case tables and parity oracle fixtures are derived from Actual v26.10.0. The CI workflows run the published actualbudget/actual-server image as a disposable test server; it is not redistributed.

The MIT License: Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files, to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, subject to the above copyright notice and this permission notice being included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

Full notices: THIRD_PARTY_NOTICES.md in the source repository."""

fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "Unknown"

private fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

/**
 * A shareable plain-text report modelled on Actualist's diagnostic report. It holds no
 * credentials, server address, budget name, transaction detail or amount; the budget is
 * identified only by a short hash.
 */
suspend fun buildDiagnosticReport(context: Context, session: BudgetSession, settings: AppSettings, hasServer: Boolean): String {
    val prefs = settings.settings.value
    val accounts = runCatching { session.read { ledger.accounts(includeClosed = true) } }.getOrNull()
    val status = when (val s = session.syncStatus.value) {
        SyncStatus.LocalOnly -> "local only"
        SyncStatus.Syncing -> "syncing"
        is SyncStatus.Idle -> "idle, ${s.pendingChanges} pending, last synced ${if (s.lastSyncedAtMillis != null) "yes" else "never"}"
        is SyncStatus.Synced -> "synced, ${s.uploaded} sent, ${s.applied} received"
        is SyncStatus.Failed -> "failed, ${s.pendingChanges} pending"
    }
    val pending = runCatching { session.read { database.pendingLocalMessageCount() } }.getOrNull()
    val sensitive = listOfNotNull(prefs.serverUrl, prefs.fallbackServerUrl, session.budgetId) +
        listOfNotNull(prefs.serverUrl, prefs.fallbackServerUrl).mapNotNull { runCatching { Uri.parse(it).host }.getOrNull() }
    val redactor = DiagnosticRedactor(sensitive)
    val lastError = (session.syncStatus.value as? SyncStatus.Failed)?.message
    val runs = BackgroundSyncRecord.history(context)
    val backgroundLines = if (runs.isEmpty()) {
        listOf("No retained background runs")
    } else {
        runs.mapIndexed { index, run ->
            "${index + 1}. started ${Instant.ofEpochMilli(run.startedAt)} | completed ${Instant.ofEpochMilli(run.completedAt)} | result ${run.outcome} | ${redactor.redact(run.message)}"
        }
    }
    val skipped = runs.firstOrNull()?.takeIf { it.outcome == "skipped" }?.let { "Skipped: ${redactor.redact(it.message)}" }
    return (listOf(
        "Actualist Diagnostic Report",
        "Generated: ${Instant.now()}",
        "Privacy: This report excludes credentials, server addresses, names, budget contents, transaction details and amounts.",
        "",
        "[Application]",
        "Version: ${appVersionName(context)}",
        "Package: ${context.packageName}",
        "",
        "[Device]",
        "Model: ${Build.MANUFACTURER} ${Build.MODEL}",
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "",
        "[Session]",
        "Budget ID hash: ${shortHash(session.budgetId)}",
        "Demo budget: ${if (session.isDemo) "yes" else "no"}",
        "Server configured: ${if (hasServer) "yes" else "no"}",
        "Fallback server configured: ${if (prefs.fallbackServerUrl != null) "yes" else "no"}",
        "Sync status: $status",
        "Accounts: ${accounts?.size ?: "unknown"} (${accounts?.count { it.closed } ?: 0} closed)",
        "Last app error: ${redactor.redact(lastError)}",
        "",
        "[Local Store]",
        "Budget open: yes",
        "Pending local messages: ${pending ?: "unknown"}",
        "",
        "[Background Sync]",
        "Total background runs: ${BackgroundSyncRecord.total(context)}",
        "Retained runs: ${runs.size}",
        "",
        "[Background Run History]",
    ) + backgroundLines + listOfNotNull(skipped) + listOf(
        "",
        "[Preferences]",
        "Theme: ${prefs.namedTheme?.name ?: prefs.themeMode.name}",
        "Display size: ${prefs.displaySize.name}",
        "Green income amounts: ${prefs.greenIncomeAmounts}",
        "Sample values: ${prefs.sampleValues}",
        "Hide amounts: ${prefs.hideAmounts}",
        "App switcher privacy: ${prefs.appSwitcherPrivacy.name}",
        "Show total assigned: ${prefs.showTotalAssigned}",
        "Hide carryover arrows: ${prefs.hideCarryoverArrows}",
    )).joinToString("\n")
}

/** Version, licences and the diagnostic report. */
@Composable
fun SettingsAboutCard(session: BudgetSession, settings: AppSettings, hasServer: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsState()
    var report by remember { mutableStateOf<String?>(null) }
    var showLicences by remember { mutableStateOf(false) }
    SectionCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Actualist for Android — experimental port of Actualist (GPLv3). Not a release.")
            Text("Version ${appVersionName(context)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app-version"))
            Text("Compatible with Actual Budget's sync protocol. Actual is MIT licensed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { showLicences = true }, modifier = Modifier.testTag("show-licences")) { Text("Licences and notices") }
            TextButton(
                onClick = { scope.launch { report = buildDiagnosticReport(context, session, settings, hasServer || current.serverUrl != null) } },
                modifier = Modifier.testTag("show-diagnostics"),
            ) { Text("Diagnostic report") }
        }
    }
    if (showLicences) LicencesDialog(onDismiss = { showLicences = false })
    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Diagnostic report") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("diagnostics-text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Actualist diagnostic report")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.testTag("diagnostics-share"),
                ) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } },
        )
    }
}

private const val SOURCE_URL = "https://github.com/sporez/actualist"

private const val GPL_SUMMARY = """Actualist is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

The full text is in the LICENSE file of the source repository and at https://www.gnu.org/licenses/gpl-3.0.html."""

/** One titled block that opens and closes, as the iOS licences screen's disclosure groups. */
@Composable
private fun LicenceSection(title: String, tag: String, startsOpen: Boolean, body: String, bodyTag: String) {
    var open by remember { mutableStateOf(startsOpen) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag(tag)) { Text((if (open) "▾ " else "▸ ") + title) }
        if (open) Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(bodyTag))
    }
}

/** Open-source licences: Actualist's own licence with the source link, then the third-party notices. */
@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Source Licenses") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("licences-screen"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Actualist", style = MaterialTheme.typography.titleSmall)
                Text("Copyright © 2026 Neil DeLillo", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Actualist source code is licensed under GNU GPL version 3.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.testTag("licences-source"),
                ) { Text("View Source Code") }
                LicenceSection("GNU GPLv3 License", "licences-gpl-toggle", startsOpen = false, body = GPL_SUMMARY, bodyTag = "licences-gpl-text")
                Text("Third-Party Software", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Actualist includes software distributed under compatible open-source licenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LicenceSection("Notices and Licenses", "licences-notices-toggle", startsOpen = true, body = LICENCES_TEXT, bodyTag = "licences-text")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("licences-close")) { Text("Close") } },
    )
}
