@file:OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)

package com.sporez.actualist.android.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sporez.actualist.android.AppContainer
import com.sporez.actualist.android.ui.accounts.AccountsScreen
import com.sporez.actualist.android.ui.accounts.AccountsViewModel
import com.sporez.actualist.android.ui.budget.BudgetScreen
import com.sporez.actualist.android.ui.budget.BudgetViewModel
import com.sporez.actualist.android.ui.onboarding.ServerScreen
import com.sporez.actualist.android.ui.reports.ReportsScreen
import com.sporez.actualist.android.ui.reports.ReportsViewModel
import com.sporez.actualist.android.ui.settings.SettingsScreen
import com.sporez.actualist.android.ui.transactions.TransactionEditorScreen
import com.sporez.actualist.android.ui.transactions.TransactionEditorViewModel
import com.sporez.actualist.android.ui.transactions.TransactionsScreen
import com.sporez.actualist.android.ui.transactions.TransactionsViewModel
import com.sporez.actualist.core.budget.Months
import com.sporez.actualist.core.store.BudgetSession
import com.sporez.actualist.core.store.SyncStatus
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("budget", "Budget", Icons.Filled.Description),
    Tab("spending", "Spending", Icons.Filled.CreditCard),
    Tab("accounts", "Accounts", Icons.Filled.AccountBalance),
    Tab("reports", "Reports", Icons.Filled.BarChart),
)

// Navigation decodes arguments itself, so only encode.
private fun encode(text: String): String = android.net.Uri.encode(text)
private fun decode(text: String): String = text

@Composable
fun MainScaffold(session: BudgetSession, container: AppContainer, app: AppViewModel) {
    val navController = rememberNavController()
    // A launcher shortcut's screen, once the budget is open.
    val shortcutRoute by com.sporez.actualist.android.AppShortcuts.pending.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shortcutRoute) {
        val route = shortcutRoute?.takeIf { com.sporez.actualist.android.DeepLinks.accepts(it) } ?: return@LaunchedEffect
        com.sporez.actualist.android.AppShortcuts.pending.value = null
        navController.navigate(route) {
            if (route != "transaction-new") {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "budget"
    val isTab = tabs.any { it.route == route }
    val wideShell = isWideShell()
    val syncStatus by session.syncStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val key = "session-${System.identityHashCode(session)}"
    val undoState by session.undoState.collectAsState()
    val snackbar = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    fun historyStep(undo: Boolean) {
        scope.launch {
            val message = try {
                val stepped = if (undo) session.undo() else session.redo()
                if (!stepped) null else if (undo) "Undone" else "Redone"
            } catch (error: Exception) {
                error.message ?: "That could not be undone."
            }
            message?.let { snackbar.showSnackbar(it) }
        }
    }

    // Every foreground sync, then Actual's schedule advancement (auto-post due schedules, move paid
    // ones on), uploading again if that changed anything.
    val scheduleContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    suspend fun syncNow() {
        if (session.isDemo) return
        val status = container.server.sync(session)
        if (status is SyncStatus.Failed) return
        val advanced = com.sporez.actualist.android.sync.ScheduleAdvancer.advance(scheduleContext, session)
        if (com.sporez.actualist.android.sync.ScheduleAdvancer.changedData(advanced)) container.server.sync(session)
    }
    // Pull from the server when a synced budget opens; demo budgets never touch the network.
    LaunchedEffect(session) { syncNow() }
    // Opportunistic flush after local writes, as Actualist does: a short debounce batches
    // quick edits, and a failure leaves the changes queued for the next attempt.
    LaunchedEffect(session) {
        if (session.isDemo) return@LaunchedEffect
        session.revision.drop(1).debounce(1_500).collect {
            val pending = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.database.pendingLocalMessageCount() }
            if (pending > 0 && session.syncStatus.value !is SyncStatus.Syncing) syncNow()
        }
    }
    // Keep the home-screen widget current shortly after edits and syncs.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(session) {
        session.revision.debounce(2_000).collect { com.sporez.actualist.android.widget.WidgetData.refresh(appContext) }
    }
    // Sync again whenever the app returns to the foreground.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (!session.isDemo) scope.launch { syncNow() }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            androidx.compose.foundation.layout.Column {
            if (!session.isDemo) ReauthenticationBanner(container.server, session)
            // The transaction editor has its own header with close and save.
            if (!route.startsWith("transaction")) TopAppBar(
                title = {
                    Text(
                        when {
                            route == "settings" -> "Settings"
                            route == "rules" -> "Rules"
                            route.startsWith("rule-edit") -> "Rule"
                            route == "payees" -> "Payees"
                            route == "schedules" -> "Schedules"
                            route == "history" -> "History"
                            route == "bank-sync" -> "Bank sync"
                            route.startsWith("explorer") -> "Explorer"
                            route.startsWith("account/") -> entry?.arguments?.getString("name")?.let(::decode) ?: "Account"
                            route.startsWith("category/") || route.startsWith("category-month/") -> entry?.arguments?.getString("name")?.let(::decode) ?: "Category"
                            route == "server" -> "Server"
                            route.startsWith("transaction") -> ""
                            else -> tabs.firstOrNull { it.route == route }?.label ?: session.name
                        },
                        modifier = Modifier.testTag("screen-title"),
                    )
                },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.testTag("nav-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isTab) {
                        IconButton(onClick = { historyStep(undo = true) }, enabled = undoState.canUndo, modifier = Modifier.testTag("undo")) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                        if (undoState.canRedo) {
                            IconButton(onClick = { historyStep(undo = false) }, modifier = Modifier.testTag("redo")) {
                                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                            }
                        }
                    }
                    if (!session.isDemo) {
                        IconButton(onClick = { scope.launch { syncNow() } }, modifier = Modifier.testTag("sync-button")) {
                            Icon(if (syncStatus is SyncStatus.Failed) Icons.Filled.SyncProblem else Icons.Filled.Sync, contentDescription = "Sync")
                        }
                    }
                    if (isTab) {
                        IconButton(onClick = { navController.navigate("settings") }, modifier = Modifier.testTag("open-settings")) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
            }
        },
        bottomBar = {
            if (isTab && !wideShell) {
                NavigationBar(Modifier.testTag("bottom-bar")) {
                    for (tab in tabs) {
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("tab-${tab.route}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding)) {
        if (isTab && wideShell) {
            AdaptiveNavigationRail(
                destinations = tabs.map { ShellDestination(it.route, it.label, it.icon) },
                currentRoute = route,
                onSelect = { target ->
                    navController.navigate(target) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        NavHost(navController, startDestination = "budget", modifier = Modifier.weight(1f)) {
            composable("budget") {
                val vm = viewModel(key = "budget-$key") { BudgetViewModel(session, Months.current()) }
                BudgetScreen(
                    vm,
                    onShowCategoryTransactions = { id, name -> navController.navigate("category/$id?name=${encode(name)}") },
                    onShowCategoryMonth = { id, name, month -> navController.navigate("category-month/${encode(id)}?name=${encode(name)}&month=$month") },
                    onOpenHistory = { navController.navigate("history") },
                    onOpenSchedules = { navController.navigate("schedules") },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("spending") {
                val vm = viewModel(key = "spending-$key") { TransactionsViewModel(session, null, null) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new") }, onRefresh = { syncNow() })
            }
            composable("accounts") {
                val vm = viewModel(key = "accounts-$key") { AccountsViewModel(session) }
                AccountsScreen(vm) { navController.navigate("account/${encode(it.id)}?name=${encode(it.name)}") }
            }
            composable("reports") {
                val vm = viewModel(key = "reports-$key") { ReportsViewModel(session) }
                ReportsScreen(
                    vm,
                    onExplore = { navController.navigate("explorer") },
                    onOpenCard = { card -> navController.navigate("explorer?card=${encode(card)}") },
                ) { id, name -> navController.navigate("category/$id?name=${encode(name)}") }
            }
            composable(
                "explorer?card={card}",
                arguments = listOf(navArgument("card") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val card = backStack.arguments?.getString("card").orEmpty()
                val vm = viewModel(key = "explorer-$key-$card") {
                    com.sporez.actualist.android.ui.reports.ExplorerViewModel(session, preset = com.sporez.actualist.core.reports.ReportExplorerPresets.forCard(card))
                }
                com.sporez.actualist.android.ui.reports.ExplorerScreen(vm) { id -> navController.navigate("transaction/${encode(id)}") }
            }
            composable("settings") {
                SettingsScreen(
                    session = session,
                    settings = container.settings,
                    server = container.server,
                    onConnectServer = { navController.navigate("server") },
                    onCloseBudget = app::closeBudget,
                    onResetDemo = app::resetDemo,
                    onShowRules = { navController.navigate("rules") },
                    onShowPayees = { navController.navigate("payees") },
                    onShowSchedules = { navController.navigate("schedules") },
                    onShowHistory = { navController.navigate("history") },
                    onShowBankSync = { navController.navigate("bank-sync") },
                )
            }
            composable("bank-sync") {
                com.sporez.actualist.android.ui.banksync.BankSyncScreen(session, container.server)
            }
            composable("history") {
                com.sporez.actualist.android.ui.settings.HistoryScreen(session)
            }
            composable("schedules") {
                val vm = viewModel(key = "schedules-$key") { com.sporez.actualist.android.ui.settings.SchedulesViewModel(session) }
                com.sporez.actualist.android.ui.settings.SchedulesScreen(vm)
            }
            composable("payees") {
                val vm = viewModel(key = "payees-$key") { com.sporez.actualist.android.ui.settings.PayeesViewModel(session) }
                com.sporez.actualist.android.ui.settings.PayeesScreen(vm) { ruleId, payeeId ->
                    navController.navigate(if (ruleId == null) "rule-edit?payee=${encode(payeeId)}" else "rule-edit?id=${encode(ruleId)}&payee=${encode(payeeId)}")
                }
            }
            composable("rules") {
                val vm = viewModel(key = "rules-$key") { com.sporez.actualist.android.ui.settings.RulesViewModel(session) }
                com.sporez.actualist.android.ui.settings.RulesScreen(vm) { id -> navController.navigate(if (id == null) "rule-edit" else "rule-edit?id=${encode(id)}") }
            }
            composable(
                "rule-edit?id={id}&payee={payee}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("payee") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val id = backStack.arguments?.getString("id")
                val payee = backStack.arguments?.getString("payee")
                val vm = viewModel(key = "rule-edit-$id-$payee-$key") { com.sporez.actualist.android.ui.settings.RuleEditorViewModel(session, id, payee) }
                com.sporez.actualist.android.ui.settings.RuleEditorScreen(vm) { navController.popBackStack() }
            }
            composable("server") {
                ServerScreen(container.server, onDownloaded = { app.openBudget(it.id) }, onCancel = { navController.popBackStack() })
            }
            composable(
                "account/{id}?name={name}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val vm = viewModel(key = "account-$id-$key") { TransactionsViewModel(session, id, null, container.server) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new?account=${encode(id)}") }, onRefresh = { syncNow() })
            }
            composable(
                "category-month/{id}?name={name}&month={month}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("month") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val month = backStack.arguments?.getString("month") ?: Months.current()
                com.sporez.actualist.android.ui.budget.CategoryMonthScreen(session, id, month, instanceKey = "$key") {
                    navController.navigate("transaction/${encode(it.parentId ?: it.id)}")
                }
            }
            composable(
                "category/{id}?name={name}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val vm = viewModel(key = "category-$id-$key") { TransactionsViewModel(session, null, id) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new") }, onRefresh = { syncNow() })
            }
            composable(
                "transaction-new?account={account}",
                arguments = listOf(navArgument("account") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStack ->
                val account = backStack.arguments?.getString("account")?.let(::decode)
                // An account from the feed wins; otherwise the default account from Settings.
                val vm = viewModel(key = "new-$account-$key") { TransactionEditorViewModel(session, null, account ?: container.settings.settings.value.defaultAccountId) }
                TransactionEditorScreen(vm) { navController.popBackStack() }
            }
            composable("transaction/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val vm = viewModel(key = "edit-$id-$key") { TransactionEditorViewModel(session, id, null) }
                TransactionEditorScreen(vm) { navController.popBackStack() }
            }
        }
        }
    }
}

/**
 * Actualist's re-authentication banner: shown when the server rejected the saved session, with
 * a sign-in for the same server that resumes syncing.
 */
@Composable
private fun ReauthenticationBanner(server: com.sporez.actualist.android.sync.ServerConnection, session: BudgetSession) {
    val needed by server.needsReauthentication.collectAsState()
    if (!needed) return
    var signingIn by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("reauth-banner")) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Actual session expired. Sign in again to resume syncing.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { signingIn = true }, modifier = Modifier.testTag("reauth-sign-in")) { Text("Sign in again") }
        }
    }
    if (signingIn) {
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!busy) signingIn = false },
            title = { Text("Sign in again") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(server.serverUrl ?: "", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Server password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("reauth-password"),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = !busy && password.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                server.signIn(server.serverUrl ?: throw IllegalStateException("No server is saved."), password)
                                signingIn = false
                                server.sync(session)
                            } catch (e: Exception) {
                                error = e.message ?: "Sign-in failed."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.testTag("reauth-submit"),
                ) { Text("Sign in") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { signingIn = false }, enabled = !busy) { Text("Cancel") } },
        )
    }
}
