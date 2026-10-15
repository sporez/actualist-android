package com.sporez.actualist.android

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.sporez.actualist.core.textimport.TextImportDraft
import com.sporez.actualist.core.textimport.TextImportParse
import com.sporez.actualist.core.textimport.TextImportParser
import com.sporez.actualist.core.textimport.TextImportResolver
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.Payee
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Actualist's "Import Transaction From Text" intent as an Android share target: text shared to
 * the app (a bank SMS or notification) is parsed into a draft that the new-transaction editor
 * picks up with [take] once the budget is open.
 */
object TextImportPrefill {
    /** The last shared text's parse, waiting for the editor. */
    val pending = MutableStateFlow<TextImportParse?>(null)

    /** Parses [text] and queues the new-transaction screen; false (nothing queued) when it holds no amount. */
    fun queue(text: String?): Boolean {
        val parse = text?.let(TextImportParser::parse) ?: return false
        pending.value = parse
        AppShortcuts.pending.value = "transaction-new"
        return true
    }

    /** Consumes the waiting parse against the budget's lists; null when nothing was shared. */
    fun take(accounts: List<Account>, payees: List<Payee>, categories: List<CategoryOption>): TextImportDraft? {
        val parse = pending.value ?: return null
        pending.value = null
        return TextImportResolver.resolve(parse, accounts, payees, categories)
    }

    /** Handles an ACTION_SEND text/plain intent; true when it was one (even if no amount was found). */
    fun handleShare(context: Context, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        intent.removeExtra(Intent.EXTRA_TEXT)
        intent.action = null
        if (!queue(text)) {
            Toast.makeText(context, "No amount found in the shared text", Toast.LENGTH_LONG).show()
        }
        return true
    }
}
