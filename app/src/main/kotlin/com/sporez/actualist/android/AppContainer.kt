package com.sporez.actualist.android

import android.app.Application
import android.content.Context
import com.sporez.actualist.android.data.AppSettings
import com.sporez.actualist.android.data.BudgetLibrary
import com.sporez.actualist.android.data.SecretStore
import com.sporez.actualist.android.sync.ServerConnection

/** Process-wide dependencies, created once. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    val secrets = SecretStore(context)
    val library = BudgetLibrary(context)
    val server = ServerConnection(settings, secrets, library)
    val sessions = com.sporez.actualist.android.sync.SessionHub()
}

class ActualistApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        BackgroundSyncScheduling.update(this)
    }
}

/**
 * Actualist registers background refresh only when new-transaction alerts or background bank sync
 * is on (`wantsBackgroundAppRefresh`). The hourly worker follows the same rule: scheduled while
 * either toggle is on, cancelled otherwise. The two toggles call [update] when they change.
 */
object BackgroundSyncScheduling {
    fun wanted(context: Context): Boolean =
        com.sporez.actualist.android.notifications.TransactionAlertPrefs.isEnabled(context) ||
            com.sporez.actualist.android.sync.BackgroundBankSyncPrefs.isEnabled(context)

    fun update(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (wanted(app)) {
                com.sporez.actualist.android.sync.BackgroundSyncWorker.schedule(app)
            } else {
                androidx.work.WorkManager.getInstance(app).cancelUniqueWork(com.sporez.actualist.android.sync.BackgroundSyncWorker.NAME)
            }
        }
    }
}
