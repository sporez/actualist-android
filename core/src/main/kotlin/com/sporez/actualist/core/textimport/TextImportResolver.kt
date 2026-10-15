package com.sporez.actualist.core.textimport

import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.CategoryOption
import com.sporez.actualist.core.transactions.Payee
import java.time.LocalDate

/** A parse matched against the open budget's accounts, payees and categories. */
data class TextImportDraft(
    val amountMinorUnits: Long,
    val outflow: Boolean,
    val accountId: String?,
    val payeeId: String?,
    val newPayeeName: String,
    val categoryId: String?,
    val notes: String,
    val date: LocalDate?,
)

object TextImportResolver {
    fun resolve(parse: TextImportParse, accounts: List<Account>, payees: List<Payee>, categories: List<CategoryOption>): TextImportDraft {
        val account = parse.accountText?.let { match(it, accounts.filter { a -> !a.closed }) { a -> a.name } }
        val transferPayee = if (parse.direction == TextImportDirection.TRANSFER) {
            val destination = parse.destinationAccountText?.let { match(it, accounts.filter { a -> !a.closed }) { a -> a.name } }
            destination?.let { d -> payees.firstOrNull { it.transferAccountId == d.id } }
        } else {
            null
        }
        val payee = transferPayee ?: parse.payeeText?.let { text -> match(text, payees.filter { !it.isTransfer }) { p -> p.name } }
        val category = parse.categoryText?.let { text -> match(text, categories.filter { !it.hidden && !it.isIncome }) { c -> c.name } }
        return TextImportDraft(
            amountMinorUnits = parse.amountMinorUnits,
            outflow = parse.direction != TextImportDirection.INFLOW,
            accountId = account?.id,
            payeeId = payee?.id,
            newPayeeName = if (payee == null) parse.payeeText.orEmpty() else "",
            categoryId = category?.id,
            notes = parse.notes.orEmpty(),
            date = parse.date,
        )
    }

    /** Exact name (ignoring case) first, then a name that contains the text or that the text contains. */
    private fun <T> match(text: String, items: List<T>, name: (T) -> String): T? {
        val wanted = text.trim().lowercase()
        if (wanted.isEmpty()) return null
        return items.firstOrNull { name(it).lowercase() == wanted }
            ?: items.firstOrNull { name(it).lowercase().contains(wanted) }
            ?: items.firstOrNull { name(it).isNotBlank() && wanted.contains(name(it).lowercase()) }
    }
}
