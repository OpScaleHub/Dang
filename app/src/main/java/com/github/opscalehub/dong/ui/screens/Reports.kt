package com.github.opscalehub.dong.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.Insights
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.io.CsvExporter
import com.github.opscalehub.dong.io.WorkbookExporter
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.EmptyState
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.LocalMoney
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.charts.ColumnGroup
import com.github.opscalehub.dong.ui.charts.GroupedColumns
import com.github.opscalehub.dong.ui.charts.HBar
import com.github.opscalehub.dong.ui.charts.HBars
import com.github.opscalehub.dong.ui.charts.Ring
import com.github.opscalehub.dong.ui.charts.Sparkline
import com.github.opscalehub.dong.ui.charts.Step
import com.github.opscalehub.dong.ui.charts.Waterfall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short month label for chart axes: first word of the period name («مهر»). */
private fun shortName(name: String) = name.substringBefore(' ').take(6)

@Composable
fun ReportsScreen(vm: AppViewModel, @Suppress("UNUSED_PARAMETER") nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val periods by vm.periods.collectAsState()
    val data by derived(vm, building.id) { vm.reports.load(building.id) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mf = LocalMoney.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    val sorted = periods.sortedByDescending { it.startDate }
    val selected = sorted.firstOrNull { it.id == selectedId } ?: sorted.firstOrNull { it.status == PeriodStatus.FINALIZED } ?: sorted.firstOrNull()

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
        if (sorted.isEmpty()) {
            item { EmptyState(S.noData, S.noPeriod) }
        } else {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sorted.forEach { p -> FilterChip(selected = p.id == selected?.id, onClick = { selectedId = p.id }, label = { Text(p.name) }) }
                }
            }
        }
        val d = data ?: return@ScreenList
        val p = selected
        if (p != null) {
            // ---- collection ------------------------------------------------
            item {
                val c = Insights.collection(d, p.id)
                SectionCard("${S.collectionRate} — ${p.name}") {
                    if (c.billed <= 0) {
                        Hint(S.afterClosing)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ring(c.perMille, Fmt.digits("${c.perMille / 10}") + "٪", S.collected, description = "${S.collectionRate} ${p.name}: ${mf.format(c.collected)} ${S.of} ${mf.format(c.billed)}")
                            Spacer(Modifier.width(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${S.billed}: ${mf.withUnit(c.billed)}", style = MaterialTheme.typography.bodyMedium)
                                Text("${S.collected}: ${mf.withUnit(c.collected)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("${S.remaining}: ${mf.withUnit(c.outstanding)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        SummaryShareButton(vm, building, p)
                    }
                }
            }
            // ---- cash flow ---------------------------------------------------
            item {
                val f = Insights.cashFlow(d, p.startDate, p.endDate)
                val steps = buildList {
                    add(Step(S.flowOpening, f.opening, total = true))
                    f.flows[Insights.Flow.COLLECTIONS]?.takeIf { it != 0L }?.let { add(Step(S.flowIn, it)) }
                    f.flows[Insights.Flow.SUPPLIER_PAYMENTS]?.takeIf { it != 0L }?.let { add(Step(S.flowOut, it)) }
                    f.flows[Insights.Flow.REFUNDS]?.takeIf { it != 0L }?.let { add(Step(S.flowRefund, it)) }
                    f.flows[Insights.Flow.OTHER]?.takeIf { it != 0L }?.let { add(Step(S.flowOther, it)) }
                    add(Step(S.flowClosing, f.closing, total = true))
                }
                SectionCard("${S.cashFlow} — ${p.name}", subtitle = mf.unitName) { Waterfall(steps, { mf.format(it) }) }
            }
            // ---- expenses by category -----------------------------------------
            item {
                val e = Insights.expensesByCategory(d, p).filter { it.amount > 0 }
                SectionCard("${S.expensesByCategory} — ${p.name}", subtitle = S.vsLastMonth) {
                    if (e.isEmpty()) Hint(S.noData)
                    HBars(e.map { c ->
                        val note = if (c.previous > 0) {
                            val pct = ((c.amount - c.previous) * 100 / c.previous)
                            when {
                                pct > 0 -> "▲ " + Fmt.digits(pct.toString()) + "٪"
                                pct < 0 -> "▼ " + Fmt.digits((-pct).toString()) + "٪"
                                else -> "="
                            }
                        } else null
                        HBar(d.categoryName(c.categoryId), c.amount, mf.format(c.amount), note,
                            if (c.previous > 0 && c.amount > c.previous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    })
                }
            }
        }
        // ---- 12-month trend --------------------------------------------------
        item {
            val m = Insights.monthly(d)
            if (m.size < 2) return@item
            SectionCard(S.trend12, subtitle = mf.unitName) {
                GroupedColumns(m.map { ColumnGroup(shortName(it.name), listOf(it.billed, it.collected)) }, listOf(S.billed, S.collected), { mf.format(it) })
            }
        }
        item {
            val m = Insights.monthly(d)
            if (m.size < 2) return@item
            SectionCard(S.reserveTrend, subtitle = "${m.last().name}: ${mf.withUnit(m.last().reserve)}") {
                Sparkline(m.map { it.reserve }, description = S.reserveTrend)
            }
        }
        // ---- water -----------------------------------------------------------
        if (p != null) {
            item {
                val w = Insights.water(d, p.id) ?: return@item
                SectionCard("${S.waterPerUnit} — ${p.name}", subtitle = S.m3) {
                    HBars(
                        d.units.filter { it.id in w.unitLiters }.map { u ->
                            val l = w.unitLiters[u.id]
                            HBar("${S.unit} ${u.label}", l ?: 0, l?.let { Fmt.cubicMeters(it) } ?: S.none)
                        },
                        reference = w.averageLiters, referenceLabel = "${S.buildingAverage}: ${Fmt.cubicMeters(w.averageLiters)}",
                    )
                    w.unexplainedPerMille?.let { pm ->
                        KeyValue(S.unexplainedWater, "${Fmt.cubicMeters(w.unexplainedLiters ?: 0)} ${S.m3} (${Fmt.digits((pm / 10).toString())}٪)")
                    }
                }
            }
        }
        item {
            val series = d.periods.sortedBy { it.startDate }.mapNotNull { per -> Insights.water(d, per.id)?.unexplainedPerMille?.toLong() }
            if (series.size < 2) return@item
            SectionCard(S.unexplainedWater, subtitle = S.leakHint) { Sparkline(series, description = S.unexplainedWater) }
        }
        // ---- balances & files ---------------------------------------------------
        item {
            val bal = d.balances
            SectionCard(S.buildingSummary) {
                KeyValue(S.availableCash, mf.withUnit(bal.cashTotal), strong = true)
                d.cashAccounts.forEach { KeyValue("  ${it.name}", mf.format(bal.cash[it.id] ?: 0)) }
                KeyValue(S.outstandingDebts, mf.format(bal.outstandingDebts))
                KeyValue(S.residentCredits, mf.format(bal.residentCredits))
                KeyValue(S.payables, mf.format(bal.payablesTotal))
                d.funds.forEach { KeyValue(it.name, mf.format(bal.funds[it.id] ?: 0)) }
                Hint(if (bal.reconciles) S.ledgerOk else S.ledgerNotOk, if (bal.reconciles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
        item {
            SectionCard(S.reports, subtitle = S.exportNote) {
                Button(onClick = { xlsx.launch("dang-${Fmt.date(vm.today, building.calendar, persian = false).replace('/', '-')}.xlsx") }, Modifier.fillMaxWidth()) { Text(S.exportXlsx) }
                OutlinedButton(onClick = { csv.launch("dang-journal.csv") }, Modifier.fillMaxWidth()) { Text(S.exportCsv) }
            }
        }
    }
}
