package com.sporez.actualist.android

import android.content.Intent
import android.net.Uri

/**
 * `actualist://open/<route>` links, the Android counterpart of Actualist's widget deep links and
 * AppRoute. They resolve to the scaffold routes the launcher shortcuts use and are handed over
 * through [AppShortcuts.pending].
 *
 * Routes: budget, spending, accounts, reports, new-transaction, history, settings, payees, rules,
 * bank-sync, schedules, account/<id>, category/<id>.
 */
object DeepLinks {
    const val SCHEME = "actualist"
    const val HOST = "open"

    /** Scaffold routes without an argument that a link may open. */
    val fixedRoutes = listOf("budget", "spending", "accounts", "reports", "transaction-new", "history", "settings", "payees", "rules", "bank-sync", "schedules")

    private val aliases = mapOf(
        "new-transaction" to "transaction-new",
        "transaction-new" to "transaction-new",
        "add-expense" to "transaction-new",
        "add-income" to "transaction-new",
    )

    /** Whether the scaffold may navigate to [route] when it arrives through [AppShortcuts.pending]. */
    fun accepts(route: String): Boolean =
        route in fixedRoutes || route in AppShortcuts.routes ||
            ((route.startsWith("account/") || route.startsWith("category/")) && route.substringAfter('/').isNotBlank() && !route.contains('?'))

    /** The scaffold route for the path segments of a link, or null when it names nothing known. */
    fun routeForSegments(segments: List<String>): String? {
        val parts = segments.filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        if (parts.size == 1) {
            val route = aliases[first] ?: first
            return route.takeIf { it in fixedRoutes }
        }
        if (parts.size == 2 && (first == "account" || first == "category")) return "$first/${parts[1]}"
        return null
    }

    /** The scaffold route for a link, or null when it is not an Actualist link or names nothing known. */
    fun routeFor(uri: Uri?): String? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        return routeForSegments(uri.pathSegments ?: emptyList())
    }

    fun uriFor(route: String): Uri = Uri.parse("$SCHEME://$HOST/${if (route == "transaction-new") "new-transaction" else route}")

    /** Queues the screen an Actualist link names; true when [intent] was such a link. */
    fun handle(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val route = routeFor(intent.data) ?: return false
        AppShortcuts.pending.value = route
        intent.data = null
        return true
    }
}
