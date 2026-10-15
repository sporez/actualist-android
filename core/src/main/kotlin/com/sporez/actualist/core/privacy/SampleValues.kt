package com.sporez.actualist.core.privacy

import com.sporez.actualist.core.budget.BudgetCalculation
import com.sporez.actualist.core.budget.BudgetMonth
import com.sporez.actualist.core.budget.BudgetMonthCategory
import com.sporez.actualist.core.budget.BudgetMonthCategoryGroup
import com.sporez.actualist.core.budget.BudgetTable
import com.sporez.actualist.core.budget.CategoryValue
import com.sporez.actualist.core.transactions.Account
import com.sporez.actualist.core.transactions.TransactionRow

enum class SampleNameKind { ACCOUNT, BUDGET, CATEGORY, CATEGORY_GROUP, PAYEE }

/**
 * Actualist's "Use Sample Values" projection (`PrivacyDisplay` and `BudgetMonthPrivacyProjection`):
 * display-only, deterministic and seeded by ids, so the same row always shows the same sample.
 * The real data stays the source of truth for writes.
 */
object SampleValues {
    /** True while Sample Values is on: the app sets it from the setting, and write paths Actualist blocks check it. */
    @Volatile
    var active: Boolean = false

    /** Actualist hides rename and reorder for categories and groups under sample values; this is what the port says instead. */
    const val CATEGORY_WRITE_BLOCKED = "Turn off Sample Values before changing categories."

    /** Shown where a note would be (Actualist's `EntityNotesView` privacy content). */
    const val NOTES_HIDDEN_TITLE = "Notes are hidden while Sample Values is on."
    const val NOTES_HIDDEN_DETAIL = "Turn off Sample Values to view or edit this note."

    /** Actualist's message when a payee change is attempted under sample values. */
    const val PAYEE_WRITE_BLOCKED = "Turn off randomized display data before changing payees."

    /** FNV-1a, 64 bit, as Actualist's `PrivacyDisplay.stableHash` (unsigned). */
    fun stableHash(value: String): ULong {
        var hash = 14_695_981_039_346_656_037UL
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toInt() and 0xFF).toULong()
            hash *= 1_099_511_628_211UL
        }
        return hash
    }

    private fun names(kind: SampleNameKind): List<String> = when (kind) {
        SampleNameKind.ACCOUNT -> listOf("Checking", "Savings", "Reserve", "Credit Card", "Cash", "Travel Card", "Brokerage", "Rewards Card")
        SampleNameKind.BUDGET -> listOf("Sample Budget")
        SampleNameKind.CATEGORY -> listOf("Groceries", "Dining", "Transport", "Utilities", "Subscriptions", "Home", "Health", "Travel", "Gifts", "Shopping", "Fuel", "Savings")
        SampleNameKind.CATEGORY_GROUP -> listOf("Essentials", "Everyday", "Lifestyle", "Goals", "Bills", "Flex", "Long Term")
        SampleNameKind.PAYEE -> listOf("Market", "Cafe", "Pharmacy", "Hardware", "Transit", "Bookstore", "Store", "Service", "Clinic", "Vendor", "Online Shop")
    }

    fun name(kind: SampleNameKind, seed: String): String {
        val value = stableHash(seed)
        val list = names(kind)
        val index = (value % list.size.toULong()).toInt()
        val suffix = ((value / 17UL) % 90UL).toInt() + 10
        return when (kind) {
            SampleNameKind.BUDGET -> "Sample Budget $suffix"
            SampleNameKind.ACCOUNT -> "${list[index]} $suffix"
            else -> list[index]
        }
    }

    /** A stable amount in minor units (two decimals); the sign follows [original]. */
    fun amount(original: Long, seed: String, minimumDollars: Int = 4, maximumDollars: Int = 900): Long {
        val value = stableHash(seed)
        val span = maxOf(maximumDollars - minimumDollars, 1)
        val units = minimumDollars + (value % (span + 1).toULong()).toLong()
        val cents = ((value / 97UL) % 100UL).toLong()
        val sign = if (original < 0) -1L else 1L
        return sign * (units * 100 + cents)
    }

    private fun leaf(sign: Int, seed: String, maximumDollars: Int): Long =
        amount(sign.toLong(), seed, 4, maximumDollars)

    // ---- Budget month ----

    fun project(month: BudgetMonth): BudgetMonth {
        val table = month.table
        val groups = month.categoryGroups.map { project(it, month.month, table) }
        val totals = try {
            BudgetCalculation.totals(groups, table)
        } catch (error: Exception) {
            BudgetCalculation.Totals(0, 0, 0, 0, null)
        }
        val toBudget = if (table == BudgetTable.TRACKING) 0L else leaf(
            if (stableHash("budget-leaf-to-budget-sign-${month.month}") % 5UL == 0UL) -1 else 1,
            "budget-leaf-to-budget-${month.month}",
            2_000,
        )
        return month.copy(
            incomeAvailable = toBudget,
            toBudget = toBudget,
            totalBudgeted = totals.budgeted,
            totalIncome = totals.income,
            totalSpent = totals.spent,
            totalBalance = totals.balance,
            categoryGroups = groups,
            trackingSummary = if (table == BudgetTable.TRACKING) totals.tracking else null,
        )
    }

    private fun project(group: BudgetMonthCategoryGroup, month: String, table: BudgetTable): BudgetMonthCategoryGroup {
        val categories = group.categories.map { project(it, month, table) }
        val included = BudgetCalculation.includedCategories(categories, table)
        fun sum(amounts: List<Long>): Long = try {
            BudgetCalculation.sum(amounts, table)
        } catch (error: Exception) {
            0L
        }
        return group.copy(
            budgeted = sum(included.map { it.budgeted }),
            spent = sum(included.map { it.spent }),
            balance = sum(included.map { it.balance }),
            categories = categories,
        )
    }

    fun project(category: BudgetMonthCategory, month: String, table: BudgetTable): BudgetMonthCategory {
        val budgeted = if (category.isIncome && table == BudgetTable.ENVELOPE) 0L
        else leaf(1, "budget-leaf-budgeted-$month-${category.id}", 900)
        val spent = leaf(if (category.isIncome) 1 else -1, "budget-leaf-spent-$month-${category.id}", 600)
        val leftoverSign = if (stableHash("budget-leaf-leftover-sign-$month-${category.id}") % 2UL == 0UL) 1 else -1
        val leftover = leaf(leftoverSign, "budget-leaf-leftover-$month-${category.id}", 400)
        val uncarried = if (category.isIncome) category.budgeted - category.spent else category.budgeted + category.spent
        val sampleCarry = if (table == BudgetTable.TRACKING && category.balance == uncarried) 0L else leftover
        val balance = try {
            BudgetCalculation.category(
                table, category.isIncome, budgeted, spent, category.carryover,
                CategoryValue(0, 0, sampleCarry, true),
            ).balance
        } catch (error: Exception) {
            0L
        }
        return category.copy(budgeted = budgeted, spent = spent, balance = balance)
    }

    fun overspentCategoryName(categoryId: String): String = name(SampleNameKind.CATEGORY, categoryId)

    fun overspentGroupName(groupName: String): String = name(SampleNameKind.CATEGORY_GROUP, groupName)

    // ---- Accounts and transactions ----

    fun account(account: Account): Account = account.copy(
        name = name(SampleNameKind.ACCOUNT, account.id),
        balance = amount(account.balance, "account-balance-${account.id}", 4, 15_000),
        clearedBalance = amount(account.clearedBalance, "account-cleared-${account.id}", 4, 15_000),
    )

    fun accounts(accounts: List<Account>): List<Account> = accounts.map { account(it) }

    fun transaction(row: TransactionRow): TransactionRow = row.copy(
        accountName = name(SampleNameKind.ACCOUNT, row.accountId),
        amount = if (row.amount == 0L) 0L else amount(row.amount, "transaction-${row.id}", 4, 600),
        payeeName = if (row.payeeName.isNullOrBlank()) row.payeeName else name(SampleNameKind.PAYEE, row.payeeId ?: row.id),
        categoryName = if (row.categoryName == null) null else name(SampleNameKind.CATEGORY, row.categoryId ?: row.id),
        notes = null,
        transferAccountName = row.transferAccountName?.let { name(SampleNameKind.ACCOUNT, row.transferId ?: it) },
        children = row.children.map { transaction(it) },
    )

    fun transactions(rows: List<TransactionRow>): List<TransactionRow> = rows.map { transaction(it) }
}
