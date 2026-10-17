package com.github.opscalehub.dong.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.opscalehub.dong.ui.screens.AuditScreen
import com.github.opscalehub.dong.ui.screens.BuildingsScreen
import com.github.opscalehub.dong.ui.screens.CategoriesScreen
import com.github.opscalehub.dong.ui.screens.ChargeItemsScreen
import com.github.opscalehub.dong.ui.screens.DashboardScreen
import com.github.opscalehub.dong.ui.screens.ExpenseScreen
import com.github.opscalehub.dong.ui.screens.ExpensesScreen
import com.github.opscalehub.dong.ui.screens.FinanceScreen
import com.github.opscalehub.dong.ui.screens.MeterScreen
import com.github.opscalehub.dong.ui.screens.ParkingScreen
import com.github.opscalehub.dong.ui.screens.PeriodScreen
import com.github.opscalehub.dong.ui.screens.PeriodsScreen
import com.github.opscalehub.dong.ui.screens.ReportsScreen
import com.github.opscalehub.dong.ui.screens.SettingsScreen
import com.github.opscalehub.dong.ui.screens.StatementScreen
import com.github.opscalehub.dong.ui.screens.UnitScreen
import com.github.opscalehub.dong.ui.screens.UnitsScreen
import com.github.opscalehub.dong.ui.screens.WaterScreen
import kotlinx.coroutines.launch

object Routes {
    const val DASHBOARD = "dashboard"
    const val BUILDINGS = "buildings"
    const val UNITS = "units"
    const val PARKING = "parking"
    const val WATER = "water"
    const val EXPENSES = "expenses"
    const val CATEGORIES = "categories"
    const val CHARGE_ITEMS = "charge_items"
    const val PERIODS = "periods"
    const val FINANCE = "finance"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
    const val AUDIT = "audit"
    fun unit(id: String) = "unit/$id"
    fun meter(id: String) = "meter/$id"
    fun expense(id: String) = "expense/$id"
    fun period(id: String) = "period/$id"
    fun statement(unitId: String) = "statement/$unitId"
}

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val primary = listOf(
    Dest(Routes.DASHBOARD, S.dashboard, Icons.Filled.Home),
    Dest(Routes.UNITS, S.units, Icons.Filled.AccountBox),
    Dest(Routes.PARKING, S.parking, Icons.Filled.Place),
    Dest(Routes.WATER, S.water, Icons.Filled.Info),
    Dest(Routes.EXPENSES, S.expenses, Icons.Filled.ShoppingCart),
    Dest(Routes.PERIODS, S.periods, Icons.Filled.DateRange),
    Dest(Routes.FINANCE, S.payments, Icons.AutoMirrored.Filled.List),
    Dest(Routes.REPORTS, S.reports, Icons.Filled.Star),
    Dest(Routes.SETTINGS, S.settings, Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DongRoot(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val buildings by vm.buildings.collectAsState()
    val building by vm.building.collectAsState()
    val busy by vm.busy.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.DASHBOARD
    val isTop = primary.any { it.route == route } || route == Routes.BUILDINGS

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = building != null,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(S.appName, Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
                    building?.let { Text(it.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    primary.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.label) }, icon = { Icon(d.icon, null) }, selected = route == d.route,
                            onClick = {
                                scope.launch { drawer.close() }
                                nav.navigate(d.route) { popUpTo(Routes.DASHBOARD); launchSingleTop = true }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(label = { Text(S.switchBuilding) }, icon = { Icon(Icons.Filled.Home, null) }, selected = route == Routes.BUILDINGS,
                        onClick = { scope.launch { drawer.close() }; nav.navigate(Routes.BUILDINGS) { launchSingleTop = true } },
                        modifier = Modifier.padding(horizontal = 12.dp))
                    NavigationDrawerItem(label = { Text(S.auditLog) }, icon = { Icon(Icons.Filled.Warning, null) }, selected = route == Routes.AUDIT,
                        onClick = { scope.launch { drawer.close() }; nav.navigate(Routes.AUDIT) { launchSingleTop = true } },
                        modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(titleFor(route, building?.name), maxLines = 1) },
                        navigationIcon = {
                            if (building != null) {
                                if (isTop) {
                                    IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, S.menu) }
                                } else {
                                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, S.back) }
                                }
                            }
                        },
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (buildings.isEmpty() || building == null) {
                    // Outside the NavHost: the root switches to the dashboard by itself once a building exists.
                    BuildingsScreen(vm, onOpened = {})
                } else {
                    DongNavHost(nav, vm)
                }
            }
        }
    }
}

private fun titleFor(route: String, building: String?): String = when {
    route == Routes.DASHBOARD -> building ?: S.appName
    route == Routes.CATEGORIES -> S.categories
    route == Routes.CHARGE_ITEMS -> S.chargeItems
    route == Routes.AUDIT -> S.auditLog
    route == Routes.BUILDINGS -> S.buildings
    route.startsWith("unit/") -> S.unit
    route.startsWith("meter/") -> S.meters
    route.startsWith("expense/") -> S.expenses
    route.startsWith("period/") -> S.period
    route.startsWith("statement/") -> S.statement
    else -> primary.firstOrNull { it.route == route }?.label ?: S.appName
}

@Composable
private fun DongNavHost(nav: NavHostController, vm: AppViewModel) {
    NavHost(nav, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) { DashboardScreen(vm, nav) }
        composable(Routes.BUILDINGS) { BuildingsScreen(vm, onOpened = { nav.navigate(Routes.DASHBOARD) { popUpTo(0) } }) }
        composable(Routes.UNITS) { UnitsScreen(vm, nav) }
        composable("unit/{id}") { UnitScreen(vm, nav, it.arguments?.getString("id")!!) }
        composable(Routes.PARKING) { ParkingScreen(vm) }
        composable(Routes.WATER) { WaterScreen(vm, nav) }
        composable("meter/{id}") { MeterScreen(vm, it.arguments?.getString("id")!!) }
        composable(Routes.EXPENSES) { ExpensesScreen(vm, nav) }
        composable("expense/{id}") { ExpenseScreen(vm, nav, it.arguments?.getString("id")!!) }
        composable(Routes.CATEGORIES) { CategoriesScreen(vm) }
        composable(Routes.CHARGE_ITEMS) { ChargeItemsScreen(vm) }
        composable(Routes.PERIODS) { PeriodsScreen(vm, nav) }
        composable("period/{id}") { PeriodScreen(vm, nav, it.arguments?.getString("id")!!) }
        composable(Routes.FINANCE) { FinanceScreen(vm, nav) }
        composable("statement/{id}") { StatementScreen(vm, it.arguments?.getString("id")!!) }
        composable(Routes.REPORTS) { ReportsScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm) }
        composable(Routes.AUDIT) { AuditScreen(vm) }
    }
}
