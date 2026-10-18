package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.Divider
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard

private data class Entry(val label: String, val hint: String, val icon: ImageVector, val route: String)

/** Everything that is not a daily task, grouped by what the manager is looking for. */
@Composable
fun MoreScreen(nav: NavController) {
    val groups = listOf(
        listOf(
            Entry(S.readingsBatch, S.stepReadings, Icons.Filled.Refresh, Routes.READINGS),
            Entry(S.monthsAndClosing, S.menuMonths, Icons.Filled.DateRange, Routes.PERIODS),
            Entry(S.transactions, S.menuTransactions, Icons.AutoMirrored.Filled.List, Routes.FINANCE),
        ),
        listOf(
            Entry(S.metersAndTariffs, S.menuMeters, Icons.Filled.Info, Routes.WATER),
            Entry(S.parking, S.menuParking, Icons.Filled.Place, Routes.PARKING),
            Entry(S.categories, S.menuCategories, Icons.Filled.ShoppingCart, Routes.CATEGORIES),
            Entry(S.chargeItems, S.menuCharges, Icons.Filled.AccountBox, Routes.CHARGE_ITEMS),
        ),
        listOf(
            Entry(S.buildingsAndSwitch, S.switchBuilding, Icons.Filled.Home, Routes.BUILDINGS),
            Entry(S.settings, S.backupTitle, Icons.Filled.Settings, Routes.SETTINGS),
            Entry(S.auditLog, S.menuAudit, Icons.Filled.Warning, Routes.AUDIT),
        ),
    )
    ScreenList {
        groups.forEach { g ->
            item {
                SectionCard {
                    g.forEachIndexed { i, e ->
                        if (i > 0) Divider()
                        ClickRow({ nav.navigate(e.route) }) {
                            Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.label, style = MaterialTheme.typography.titleSmall)
                                Hint(e.hint.take(70))
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
