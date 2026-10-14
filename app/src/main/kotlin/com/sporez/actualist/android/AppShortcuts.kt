package com.sporez.actualist.android

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Launcher shortcuts, the Android counterpart of Actualist's Open intents (New Transaction,
 * Spending, Accounts, Reports). Each opens a scaffold route once a budget is open.
 */
object AppShortcuts {
    const val EXTRA_ROUTE = "com.sporez.actualist.android.ROUTE"

    /** The route a shortcut asked for, consumed by the scaffold. */
    val pending = MutableStateFlow<String?>(null)

    /** Routes a shortcut may name; anything else is ignored. */
    val routes = listOf("transaction-new", "spending", "accounts", "reports", "budget")

    fun publish(context: Context) {
        val shortcuts = listOf(
            Triple("new-transaction", "New transaction", "transaction-new"),
            Triple("spending", "Spending", "spending"),
            Triple("accounts", "Accounts", "accounts"),
            Triple("reports", "Reports", "reports"),
        ).map { (id, label, route) ->
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(label)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_ROUTE, route))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }
}
