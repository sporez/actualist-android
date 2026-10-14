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
}

class ActualistApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
