@file:OptIn(ExperimentalMaterial3Api::class)

package com.sporez.actualist.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "budget"
    val isTab = tabs.any { it.route == route }
    val syncStatus by session.syncStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val key = "session-${System.identityHashCode(session)}"

    // Pull from the server when a synced budget opens; demo budgets never touch the network.
    LaunchedEffect(session) { if (!session.isDemo) container.server.sync(session) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            route == "settings" -> "Settings"
                            route == "rules" -> "Rules"
                            route.startsWith("account/") -> entry?.arguments?.getString("name")?.let(::decode) ?: "Account"
                            route.startsWith("category/") -> entry?.arguments?.getString("name")?.let(::decode) ?: "Category"
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
                    if (!session.isDemo) {
                        IconButton(onClick = { scope.launch { container.server.sync(session) } }, modifier = Modifier.testTag("sync-button")) {
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
        },
        bottomBar = {
            if (isTab) {
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
        NavHost(navController, startDestination = "budget", modifier = Modifier.padding(padding)) {
            composable("budget") {
                val vm = viewModel(key = "budget-$key") { BudgetViewModel(session, Months.current()) }
                BudgetScreen(vm) { id, name -> navController.navigate("category/$id?name=${encode(name)}") }
            }
            composable("spending") {
                val vm = viewModel(key = "spending-$key") { TransactionsViewModel(session, null, null) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new") })
            }
            composable("accounts") {
                val vm = viewModel(key = "accounts-$key") { AccountsViewModel(session) }
                AccountsScreen(vm) { navController.navigate("account/${encode(it.id)}?name=${encode(it.name)}") }
            }
            composable("reports") {
                val vm = viewModel(key = "reports-$key") { ReportsViewModel(session) }
                ReportsScreen(vm)
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
                )
            }
            composable("rules") {
                val vm = viewModel(key = "rules-$key") { com.sporez.actualist.android.ui.settings.RulesViewModel(session) }
                com.sporez.actualist.android.ui.settings.RulesScreen(vm)
            }
            composable("server") {
                ServerScreen(container.server, onDownloaded = { app.openBudget(it.id) }, onCancel = { navController.popBackStack() })
            }
            composable(
                "account/{id}?name={name}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val vm = viewModel(key = "account-$id-$key") { TransactionsViewModel(session, id, null) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new?account=${encode(id)}") })
            }
            composable(
                "category/{id}?name={name}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType; defaultValue = "" }),
            ) { backStack ->
                val id = decode(backStack.arguments?.getString("id") ?: "")
                val vm = viewModel(key = "category-$id-$key") { TransactionsViewModel(session, null, id) }
                TransactionsScreen(vm, onOpen = { navController.navigate("transaction/${encode(it.parentId ?: it.id)}") }, onAdd = { navController.navigate("transaction-new") })
            }
            composable(
                "transaction-new?account={account}",
                arguments = listOf(navArgument("account") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStack ->
                val account = backStack.arguments?.getString("account")?.let(::decode)
                val vm = viewModel(key = "new-$account-$key") { TransactionEditorViewModel(session, null, account) }
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
