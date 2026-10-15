package com.sporez.actualist.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sporez.actualist.android.AppShortcuts
import com.sporez.actualist.android.BackgroundSyncScheduling
import com.sporez.actualist.android.MainActivity
import com.sporez.actualist.android.R
import com.sporez.actualist.core.notifications.NewTransactionsNotificationCopy

/** The on/off flag for new-transaction alerts, kept in its own preferences file. */
object TransactionAlertPrefs {
    private const val FILE = "transaction_alerts"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        BackgroundSyncScheduling.update(context)
    }
}

/** Posts the "New transactions found" notification; tapping it opens the Spending feed. */
object TransactionAlertNotifier {
    const val CHANNEL_ID = "new-transactions"
    private const val NOTIFICATION_ID = 4101

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Returns true when the notification was handed to the system. */
    fun post(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasPermission(app) || !NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New transactions", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = Intent(app, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(AppShortcuts.EXTRA_ROUTE, "spending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(app, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(NewTransactionsNotificationCopy.TITLE)
            .setContentText(NewTransactionsNotificationCopy.BODY)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            true
        } catch (error: SecurityException) {
            false
        }
    }
}

/** Settings row: turns new-transaction alerts on (asking for notification permission on Android 13+) or off. */
@Composable
fun NewTransactionAlertsSetting() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(TransactionAlertPrefs.isEnabled(context) && TransactionAlertNotifier.hasPermission(context)) }
    // Actualist explains a missing notification permission instead of silently flipping the switch back.
    var blocked by remember { mutableStateOf(TransactionAlertPrefs.isEnabled(context) && !TransactionAlertNotifier.hasPermission(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        TransactionAlertPrefs.setEnabled(context, granted)
        enabled = granted
        blocked = !granted
    }
    Column {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("New Transaction Alerts")
            Text(
                "Notify when a background sync finds new transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { wanted ->
                if (!wanted) {
                    TransactionAlertPrefs.setEnabled(context, false)
                    enabled = false
                    blocked = false
                } else if (TransactionAlertNotifier.hasPermission(context)) {
                    TransactionAlertPrefs.setEnabled(context, true)
                    enabled = true
                    blocked = false
                } else {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            modifier = Modifier.testTag("new-transaction-alerts"),
        )
    }
    if (blocked) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Notification permission is not granted, so alerts can't be shown. Allow notifications for Actualist in Android settings, then turn this on again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("notification-permission-message"),
            )
            TextButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.testTag("open-notification-settings"),
            ) { Text("Open Android Settings") }
        }
    }
    }
}
