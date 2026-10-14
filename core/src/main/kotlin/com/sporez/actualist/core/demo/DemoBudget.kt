package com.sporez.actualist.core.demo

import androidx.sqlite.SQLiteDriver
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.db.BudgetDatabase
import com.sporez.actualist.core.db.CellDraft
import com.sporez.actualist.core.db.Clock
import com.sporez.actualist.core.sync.SyncValue
import java.time.LocalDate
import java.time.YearMonth

/**
 * The bundled demo budget: deterministic, synthetic data shaped like Actualist's
 * `DemoBudget.zip` (four accounts, three expense groups and income, seven months of
 * activity). It is written through the real CRDT local-write path on Actual's starter
 * schema, then the outbox is drained: demo mode never uploads anything.
 *
 * Everything is derived from [anchor] (the "today" the demo is built for), so the same
 * anchor always yields byte-identical budget rows.
 */
object DemoBudget {
    const val FILE_ID = "actualist-android-demo-v1"
    const val NODE_ID = "demo000000000001"
    const val NAME = "Demo Budget"

    private data class Category(val id: String, val name: String, val group: String, val monthlyBudget: Long)

    private val groups = listOf(
        Triple("essentials", "Essentials", false),
        Triple("lifestyle", "Lifestyle", false),
        Triple("savings", "Savings Goals", false),
        Triple("income", "Income", true),
    )

    private val categories = listOf(
        Category("rent", "Rent", "essentials", 180_000),
        Category("groceries", "Groceries", "essentials", 60_000),
        Category("utilities", "Utilities", "essentials", 18_400),
        Category("transportation", "Transportation", "essentials", 45_000),
        Category("insurance", "Insurance", "essentials", 12_000),
        Category("dining", "Dining", "lifestyle", 30_000),
        Category("entertainment", "Entertainment", "lifestyle", 15_000),
        Category("subscriptions", "Subscriptions", "lifestyle", 8_000),
        Category("shopping", "Shopping", "lifestyle", 25_000),
        Category("personal_care", "Personal Care", "lifestyle", 12_000),
        Category("emergency_fund", "Emergency Fund", "savings", 40_000),
        Category("vacation", "Vacation", "savings", 20_000),
        Category("retirement", "Retirement", "savings", 30_000),
        Category("paycheck", "Paycheck", "income", 0),
        Category("freelance", "Freelance", "income", 0),
        Category("interest", "Interest", "income", 0),
        Category("starting", "Starting Balances", "income", 0),
    )

    private val accounts = listOf(
        Triple("checking", "Everyday Checking", false),
        Triple("savings", "High-Yield Savings", false),
        Triple("credit", "Visa Credit Card", false),
        Triple("carloan", "Car Loan", true),
    )

    private val payees = listOf(
        "landlord" to "Landlord Co", "grocer" to "Fresh Market", "utilityco" to "City Utilities",
        "gasstation" to "QuickFuel", "insuranceco" to "SafeCover", "cafe" to "Daily Grind",
        "restaurant" to "Bistro Nove", "streamflix" to "StreamFlix", "telco" to "MobileCo",
        "employer" to "Payroll Inc", "freelanceclient" to "Pixel Studio", "store" to "MarketPlace",
        "salon" to "Glow Studio", "cinema" to "Starlight Cinema", "bank" to "Harbor Bank",
        "startingbalance" to "Starting Balance",
    )

    /** Creates the demo budget file at [path], built for [anchor]. */
    fun create(driver: SQLiteDriver, path: String, anchor: LocalDate, clock: Clock = Clock.SYSTEM): BudgetDatabase {
        val database = BudgetDatabase.createStarter(driver, path, NODE_ID, clock)
        seed(database, anchor)
        return database
    }

    fun seed(database: BudgetDatabase, anchor: LocalDate) {
        val drafts = mutableListOf<CellDraft>()
        fun cell(dataset: String, row: String, column: String, value: SyncValue) {
            drafts += CellDraft(dataset, row, column, value)
        }
        cell("preferences", "budgetType", "value", SyncValue.text("envelope"))
        groups.forEachIndexed { index, (id, name, income) ->
            cell("category_groups", id, "name", SyncValue.text(name))
            cell("category_groups", id, "is_income", SyncValue.bool(income))
            cell("category_groups", id, "sort_order", SyncValue.Real((index + 1) * 16_384.0))
        }
        categories.forEachIndexed { index, category ->
            val income = groups.first { it.first == category.group }.third
            cell("categories", category.id, "name", SyncValue.text(category.name))
            cell("categories", category.id, "cat_group", SyncValue.text(category.group))
            cell("categories", category.id, "is_income", SyncValue.bool(income))
            cell("categories", category.id, "sort_order", SyncValue.Real((index + 1) * 16_384.0))
            cell("category_mapping", category.id, "transferId", SyncValue.text(category.id))
        }
        accounts.forEachIndexed { index, (id, name, offBudget) ->
            cell("accounts", id, "name", SyncValue.text(name))
            cell("accounts", id, "offbudget", SyncValue.bool(offBudget))
            cell("accounts", id, "closed", SyncValue.bool(false))
            cell("accounts", id, "sort_order", SyncValue.Real((index + 1) * 16_384.0))
            // Actual gives every account a transfer payee with an empty name.
            cell("payees", "xfer-$id", "name", SyncValue.text(""))
            cell("payees", "xfer-$id", "transfer_acct", SyncValue.text(id))
            cell("payee_mapping", "xfer-$id", "targetId", SyncValue.text("xfer-$id"))
        }
        for ((id, name) in payees) {
            cell("payees", id, "name", SyncValue.text(name))
            cell("payee_mapping", id, "targetId", SyncValue.text(id))
        }

        val random = Lcg(20_261_015)
        val firstMonth = YearMonth.from(anchor).minusMonths(6)
        var sort = 1.0
        fun txn(
            id: String, account: String, date: LocalDate, amount: Long, payee: String?, category: String?,
            notes: String? = null, cleared: Boolean = true, transfer: String? = null, startingBalance: Boolean = false,
        ) {
            cell("transactions", id, "acct", SyncValue.text(account))
            cell("transactions", id, "date", SyncValue.int(Months.packedDate(date)))
            cell("transactions", id, "amount", SyncValue.int(amount))
            payee?.let { cell("transactions", id, "description", SyncValue.text(it)) }
            category?.let { cell("transactions", id, "category", SyncValue.text(it)) }
            notes?.let { cell("transactions", id, "notes", SyncValue.text(it)) }
            cell("transactions", id, "cleared", SyncValue.bool(cleared))
            cell("transactions", id, "reconciled", SyncValue.bool(false))
            transfer?.let { cell("transactions", id, "transferred_id", SyncValue.text(it)) }
            if (startingBalance) cell("transactions", id, "starting_balance_flag", SyncValue.bool(true))
            cell("transactions", id, "sort_order", SyncValue.Real(sort++))
        }
        fun transfer(id: String, from: String, to: String, date: LocalDate, amount: Long, category: String? = null, notes: String? = null) {
            txn("$id-out", from, date, -amount, "xfer-$to", category, notes, transfer = "$id-in")
            txn("$id-in", to, date, amount, "xfer-$from", null, notes, cleared = false, transfer = "$id-out")
        }

        val start = firstMonth.atDay(1)
        txn("sb-checking", "checking", start, 420_000, "startingbalance", "starting", startingBalance = true)
        txn("sb-savings", "savings", start, 1_250_000, "startingbalance", "starting", startingBalance = true)
        txn("sb-credit", "credit", start, -38_000, "startingbalance", "starting", startingBalance = true)
        txn("sb-carloan", "carloan", start, -1_480_000, "startingbalance", null, startingBalance = true)

        var month = firstMonth
        val anchorMonth = YearMonth.from(anchor)
        while (!month.isAfter(anchorMonth)) {
            val key = Months.id(month.year, month.monthValue)
            val m = month.monthValue
            fun day(d: Int): LocalDate? {
                val date = month.atDay(minOf(d, month.lengthOfMonth()))
                return if (date.isAfter(anchor)) null else date
            }
            fun spend(tag: String, d: Int, account: String, payee: String, category: String, base: Long, spread: Long, notes: String? = null) {
                val date = day(d) ?: return
                val amount = base + random.next(spread)
                val recent = date.isAfter(anchor.minusDays(4))
                txn("$key-$tag", account, date, -amount, payee, category, notes, cleared = !recent)
            }
            day(1)?.let { txn("$key-pay1", "checking", it, 245_000, "employer", "paycheck", "Salary") }
            day(15)?.let { txn("$key-pay2", "checking", it, 245_000, "employer", "paycheck", "Salary") }
            if (m % 2 == 0) day(20)?.let { txn("$key-freelance", "checking", it, 60_000 + random.next(40_000), "freelanceclient", "freelance", "Design work") }
            day(28)?.let { txn("$key-interest", "savings", it, 3_100 + random.next(900), "bank", "interest") }
            spend("rent", 1, "checking", "landlord", "rent", 180_000, 1)
            spend("groceries1", 3, "credit", "grocer", "groceries", 9_000, 6_000)
            spend("groceries2", 10, "credit", "grocer", "groceries", 9_000, 6_000)
            spend("groceries3", 17, "checking", "grocer", "groceries", 9_000, 6_000)
            spend("groceries4", 24, "credit", "grocer", "groceries", 9_000, 6_000)
            spend("utilities", 6, "checking", "utilityco", "utilities", 15_000, 6_000)
            spend("gas1", 8, "credit", "gasstation", "transportation", 4_500, 2_000)
            spend("gas2", 22, "credit", "gasstation", "transportation", 4_500, 2_000)
            spend("insurance", 12, "checking", "insuranceco", "insurance", 12_000, 1)
            spend("coffee1", 4, "credit", "cafe", "dining", 600, 500)
            spend("coffee2", 11, "credit", "cafe", "dining", 600, 500)
            spend("dinner", 19, "credit", "restaurant", "dining", 6_500, 5_000)
            if (m % 3 == 0) spend("dinner2", 26, "credit", "restaurant", "dining", 7_500, 6_000, "Birthday dinner")
            spend("stream", 5, "credit", "streamflix", "subscriptions", 1_599, 1)
            spend("phone", 9, "checking", "telco", "subscriptions", 4_500, 1)
            spend("movies", 14, "credit", "cinema", "entertainment", 2_800, 2_000)
            spend("shopping", 21, "credit", "store", "shopping", 4_000, 12_000)
            if (m % 2 == 1) spend("salon", 16, "credit", "salon", "personal_care", 5_500, 2_500)
            day(25)?.let { transfer("$key-ccpay", "checking", "credit", it, 95_000, notes = "Card payment") }
            day(2)?.let { transfer("$key-save", "checking", "savings", it, 50_000, notes = "Monthly savings") }
            day(18)?.let { transfer("$key-car", "checking", "carloan", it, 32_000, category = "transportation", notes = "Car payment") }

            for (category in categories) {
                if (category.monthlyBudget == 0L) continue
                val rowId = "${month.year * 100 + m}-${category.id}"
                cell("zero_budgets", rowId, "month", SyncValue.int(month.year * 100 + m))
                cell("zero_budgets", rowId, "category", SyncValue.text(category.id))
                cell("zero_budgets", rowId, "amount", SyncValue.int(category.monthlyBudget))
            }
            month = month.plusMonths(1)
        }
        // Savings goals roll over, as people use them for sinking funds.
        val anchorKey = Months.id(anchorMonth.year, anchorMonth.monthValue)
        for (id in listOf("emergency_fund", "vacation", "retirement")) {
            var cursor = firstMonth
            while (!cursor.isAfter(anchorMonth)) {
                cell("zero_budgets", "${cursor.year * 100 + cursor.monthValue}-$id", "carryover", SyncValue.bool(true))
                cursor = cursor.plusMonths(1)
            }
        }
        cell("notes", "budget-$anchorKey", "note", SyncValue.text("Demo month. Every number here is synthetic."))

        // Two rules in Actual's stored JSON shape, so the rule preview has something to do.
        fun rule(id: String, payee: String, category: String) {
            cell("rules", id, "stage", SyncValue.Null)
            cell("rules", id, "conditions_op", SyncValue.text("and"))
            cell("rules", id, "conditions", SyncValue.text("""[{"op":"is","field":"payee","value":"$payee","type":"id"}]"""))
            cell("rules", id, "actions", SyncValue.text("""[{"op":"set","field":"category","value":"$category","type":"id"}]"""))
            cell("rules", id, "tombstone", SyncValue.bool(false))
        }
        rule("demo-rule-cafe", "cafe", "dining")
        rule("demo-rule-fuel", "gasstation", "transportation")

        database.commitLocal(drafts)
        database.drainAllPendingLocalMessages()
    }

    /** Small deterministic generator so the demo never depends on platform randomness. */
    private class Lcg(seed: Long) {
        private var state = seed
        fun next(bound: Long): Long {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val positive = (state ushr 17)
            return if (bound <= 1) 0 else positive % bound
        }
    }
}
