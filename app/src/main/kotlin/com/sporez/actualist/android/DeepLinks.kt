package com.sporez.actualist.android

import android.content.Intent
import android.net.Uri
import com.sporez.actualist.core.money.Money
import com.sporez.actualist.core.textimport.TextImportDirection
import com.sporez.actualist.core.textimport.TextImportParse

/**
 * `actualist://open/<route>` links, the Android counterpart of Actualist's widget deep links and
 * AppRoute. They resolve to the scaffold routes the launcher shortcuts use and are handed over
 * through [AppShortcuts.pending].
 *
 * Routes: budget, spending, accounts, reports, new-transaction, add-expense, add-income, history,
 * settings, payees, rules, bank-sync, schedules, uncategorized, account/<id>,
 * category/<id>[?month=yyyy-mm&name=<title>].
 *
 * new-transaction (and add-expense / add-income) also take prefill parameters like Actualist's
 * ShortcutEditorPrefill: account, amount, payee, category, notes and direction (spend or inflow).
 * account and category may be an id or a name; they are resolved when the editor opens.
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
        // The Spending feed has an Uncategorized filter; there is no separate screen for it.
        "uncategorized" to "spending",
    )

    private val monthPattern = Regex("""\d{4}-(0[1-9]|1[0-2])""")

    /** Whether the scaffold may navigate to [route] when it arrives through [AppShortcuts.pending]. */
    fun accepts(route: String): Boolean =
        route in fixedRoutes || route in AppShortcuts.routes ||
            (route.startsWith("account/") && route.substringAfter('/').isNotBlank() && !route.contains('?')) ||
            (route.startsWith("category/") && route.substringAfter('/').isNotBlank() && !route.contains('?')) ||
            (route.startsWith("category-month/") && route.substringAfter('/').substringBefore('?').isNotBlank() && route.contains("&month="))

    /**
     * The scaffold route for the path segments of a link, or null when it names nothing known.
     * [param] reads a query parameter; a month on category/<id> opens that category's month screen.
     */
    fun routeForSegments(segments: List<String>, param: (String) -> String? = { null }): String? {
        val parts = segments.filter { it.isNotEmpty() }
        val first = parts.firstOrNull() ?: return null
        if (parts.size == 1) {
            val route = aliases[first] ?: first
            return route.takeIf { it in fixedRoutes }
        }
        if (parts.size == 2 && first == "account") return "account/${parts[1]}"
        if (parts.size == 2 && first == "category") {
            val month = param("month")?.takeIf { monthPattern.matches(it) } ?: return "category/${parts[1]}"
            val name = param("name")?.takeIf { it.isNotBlank() } ?: "Category"
            return "category-month/${Uri.encode(parts[1])}?name=${Uri.encode(name)}&month=$month"
        }
        return null
    }

    /** The scaffold route for a link, or null when it is not an Actualist link or names nothing known. */
    fun routeFor(uri: Uri?): String? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        return routeForSegments(uri.pathSegments ?: emptyList()) { uri.getQueryParameter(it) }
    }

    /**
     * The new-transaction prefill a link carries, or null when it is not a new-transaction link or
     * carries nothing to prefill. add-income alone preselects the inflow direction.
     */
    fun prefillFor(uri: Uri?): NewTransactionPrefill? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val segments = (uri.pathSegments ?: emptyList()).filter { it.isNotEmpty() }
        val first = segments.singleOrNull() ?: return null
        if (aliases[first] != "transaction-new") return null
        val direction = directionOf(uri.getQueryParameter("direction")) ?: if (first == "add-income") TextImportDirection.INFLOW else null
        val amount = uri.getQueryParameter("amount")?.let { Money.parse(it) }?.let { kotlin.math.abs(it) } ?: 0L
        val account = uri.getQueryParameter("account")?.trim()?.takeIf { it.isNotEmpty() }
        val payee = uri.getQueryParameter("payee")?.trim()?.takeIf { it.isNotEmpty() }
        val category = uri.getQueryParameter("category")?.trim()?.takeIf { it.isNotEmpty() }
        val notes = uri.getQueryParameter("notes")?.trim()?.takeIf { it.isNotEmpty() }
        if (direction == null && amount == 0L && account == null && payee == null && category == null && notes == null) return null
        return NewTransactionPrefill(
            parse = TextImportParse(
                amountMinorUnits = amount,
                direction = direction ?: TextImportDirection.SPEND,
                payeeText = payee,
                accountText = account,
                categoryText = category,
                notes = notes,
            ),
            accountHint = account,
            categoryHint = category,
        )
    }

    /** spend / expense / outflow or inflow / income, any case; null when absent or unknown. */
    fun directionOf(text: String?): TextImportDirection? = when (text?.trim()?.lowercase()) {
        "spend", "expense", "outflow", "out" -> TextImportDirection.SPEND
        "inflow", "income", "in" -> TextImportDirection.INFLOW
        else -> null
    }

    fun uriFor(route: String): Uri = Uri.parse("$SCHEME://$HOST/${if (route == "transaction-new") "new-transaction" else route}")

    /** The link behind the widget's "Add income" action: a new transaction with the inflow direction preselected. */
    fun incomeUri(): Uri = Uri.parse("$SCHEME://$HOST/add-income")

    /** Queues the screen an Actualist link names; true when [intent] was such a link. */
    fun handle(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data
        val route = routeFor(data) ?: return false
        val prefill = prefillFor(data)
        if (prefill != null) {
            TextImportPrefill.queueParse(prefill.parse, prefill.accountHint, prefill.categoryHint)
        } else {
            AppShortcuts.pending.value = route
        }
        intent.data = null
        return true
    }
}

/** A new transaction a link asks for; account and category may be ids, which win over name matches. */
data class NewTransactionPrefill(val parse: TextImportParse, val accountHint: String?, val categoryHint: String?)
