package com.github.opscalehub.dong.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.io.CsvExporter
import com.github.opscalehub.dong.io.WorkbookExporter
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Divider
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReportsScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val data by derived(vm, building.id) { vm.reports.load(building.id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val digits = building.minorDigits

    fun save(uri: android.net.Uri?, write: suspend (java.io.OutputStream) -> Unit) {
        if (uri == null) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { write(it) } ?: error("no stream") }
                vm.message(S.exported)
            } catch (e: Exception) {
                vm.message(S.unexpectedError)
            }
        }
    }
    val xlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        save(uri) { out -> WorkbookExporter.write(vm.reports.load(building.id), out) }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        save(uri) { out -> out.write(CsvExporter.journal(vm.reports.load(building.id)).toByteArray(Charsets.UTF_8)) }
    }

    ScreenList {
        item {
            SectionCard(S.reports, subtitle = S.exportNote) {
                Button(onClick = { xlsx.launch("dong-${Fmt.date(vm.today, building.calendar, persian = false).replace('/', '-')}.xlsx") }, Modifier.fillMaxWidth()) { Text(S.exportXlsx) }
                OutlinedButton(onClick = { csv.launch("dong-journal.csv") }, Modifier.fillMaxWidth()) { Text(S.exportCsv) }
                Hint("${S.exportStatement}: ${S.periods}")
            }
        }
        val d = data ?: return@ScreenList
        item {
            SectionCard(S.buildingSummary) {
                val bal = d.balances
                KeyValue(S.availableCash, Fmt.money(bal.cashTotal, digits), strong = true)
                d.cashAccounts.forEach { KeyValue("  ${it.name}", Fmt.money(bal.cash[it.id] ?: 0, digits)) }
                KeyValue(S.outstandingDebts, Fmt.money(bal.outstandingDebts, digits))
                KeyValue(S.residentCredits, Fmt.money(bal.residentCredits, digits))
                KeyValue(S.payables, Fmt.money(bal.payablesTotal, digits))
                d.funds.forEach { KeyValue(it.name, Fmt.money(bal.funds[it.id] ?: 0, digits), strong = true) }
                Hint(if (bal.reconciles) S.ledgerOk else S.ledgerNotOk, if (bal.reconciles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
        item {
            SectionCard(S.expensesByCategory) {
                val incurred = d.lines.filter { it.account == AccountKind.EXPENSE }.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountMinor } }
                val charged = d.lines.filter { it.account == AccountKind.INCOME }.groupBy { it.categoryId }.mapValues { (_, l) -> -l.sumOf { it.amountMinor } }
                (incurred.keys + charged.keys).distinct().forEach { cat ->
                    Column {
                        Text(d.categoryName(cat), style = MaterialTheme.typography.bodyMedium)
                        KeyValue("  ${S.incurred}", Fmt.money(incurred[cat] ?: 0, digits))
                        KeyValue("  ${S.charged}", Fmt.money(charged[cat] ?: 0, digits))
                    }
                }
            }
        }
        item {
            SectionCard(S.periodComparison) {
                d.periods.sortedBy { it.startDate }.forEach { p ->
                    val r = d.periodResults[p.id]
                    KeyValue("${p.name} (${if (p.status == PeriodStatus.FINALIZED) S.finalized else S.draft})", Fmt.money(r?.total ?: 0, digits))
                    if ((r?.fundBorneTotal ?: 0) != 0L) Hint("  ${S.fundBorne}: ${Fmt.money(r!!.fundBorneTotal, digits)}")
                }
            }
        }
        item {
            SectionCard(S.waterReconciliation, subtitle = S.unallocatedNote) {
                d.periods.sortedBy { it.startDate }.forEach { p ->
                    val w = d.periodResults[p.id]?.water ?: return@forEach
                    if (w.meters.isEmpty()) return@forEach
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                    KeyValue("  ${S.mainConsumption}", w.mainLiters?.let { Fmt.cubicMeters(it) } ?: S.none)
                    KeyValue("  ${S.unitsConsumption}", Fmt.cubicMeters(w.unitSumLiters))
                    KeyValue("  ${S.unallocated}", w.unallocatedLiters?.let { Fmt.cubicMeters(it) } ?: S.none)
                    Divider()
                }
            }
        }
    }
}
