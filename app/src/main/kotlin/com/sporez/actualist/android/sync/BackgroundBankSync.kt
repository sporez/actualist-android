package com.sporez.actualist.android.sync

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sporez.actualist.android.BackgroundSyncScheduling
import com.sporez.actualist.android.data.SecretStore
import com.sporez.actualist.core.banksync.SimpleFinServerSupport
import com.sporez.actualist.core.banksync.SimpleFinSync
import com.sporez.actualist.core.notifications.PendingNewTransactionSource
import com.sporez.actualist.core.notifications.PendingNewTransactions
import com.sporez.actualist.core.server.EndpointHeaders
import com.sporez.actualist.core.server.ServerClients
import com.sporez.actualist.core.store.BudgetSession
import java.util.UUID

/** The "Background Bank Sync" choice, kept in its own preferences file. Off by default, as in Actualist. */
object BackgroundBankSyncPrefs {
    private const val FILE = "background_bank_sync"
    private const val KEY = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        BackgroundSyncScheduling.update(context)
    }
}

/**
 * After a background budget sync, downloads every linked SimpleFIN account through the server and
 * records the new transactions as bank-sync arrivals. Like Actualist it uses only the server's
 * SimpleFIN (never a device-only key) and posts no notification.
 */
object BackgroundBankSync {
    /** Runs when the toggle is on. Returns how many transactions arrived; failures are swallowed (the next pass retries). */
    suspend fun run(context: Context, session: BudgetSession, serverUrl: String?, server: ServerConnection, secrets: SecretStore): Int {
        if (session.isDemo || !BackgroundBankSyncPrefs.isEnabled(context)) return 0
        return try {
            val pending = PendingNewTransactions(session.database)
            session.automatically {
                val sync = serverOnlySimpleFin(session, serverUrl, server, secrets) ?: return@automatically 0
                if (sync.serverSupport() != SimpleFinServerSupport.CONFIGURED) return@automatically 0
                val before = pending.liveTransactionAccounts()
                sync.syncAll()
                val fresh = pending.newSince(before)
                if (fresh.isEmpty()) {
                    0
                } else {
                    // Recorded already acknowledged and suppressed: this path never notifies.
                    pending.record(fresh, PendingNewTransactionSource.BANK_SYNC, UUID.randomUUID().toString(), alreadyAcknowledged = true)
                }
            }
        } catch (error: Exception) {
            0
        }
    }

    private fun serverOnlySimpleFin(session: BudgetSession, serverUrl: String?, server: ServerConnection, secrets: SecretStore): SimpleFinSync? {
        val url = serverUrl ?: return null
        val token = secrets.getString(SecretStore.SERVER_TOKEN) ?: return null
        val saved = server.savedCustomHeaders()
        val headers = saved?.let { (savedUrl, text) ->
            runCatching { EndpointHeaders(savedUrl, EndpointHeaders.parse(text)) }.getOrNull()?.takeIf { it.appliesToUrl(url) }
        }
        return SimpleFinSync(session.database, ServerClients.simpleFin(url, headers), token, session.clock)
    }
}

/** The Settings row for Background Bank Sync, with Actualist's footer. */
@Composable
fun BackgroundBankSyncSetting() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(BackgroundBankSyncPrefs.isEnabled(context)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("background-bank-sync-setting")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Background Bank Sync", Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    BackgroundBankSyncPrefs.setEnabled(context, it)
                },
                modifier = Modifier.testTag("background-bank-sync-toggle"),
            )
        }
        Text(
            "After a background budget sync, linked bank accounts are downloaded and saved automatically. " +
                "Requires SimpleFIN through your Actual server. No notification is posted for this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
