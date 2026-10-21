package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.Insights
import com.github.opscalehub.dong.data.SampleData
import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SectionTitle
import com.github.opscalehub.dong.ui.StatTile
import com.github.opscalehub.dong.ui.charts.Ring
import com.github.opscalehub.dong.ui.money

private data class StepState(val title: String, val sub: String, val done: Boolean, val enabled: Boolean, val onClick: () -> Unit)

@Composable
fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val periods by vm.periods.collectAsState()
    val units by vm.units.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val funds by vm.funds.collectAsState()
    val shares by vm.billShares.collectAsState()
    val chargeItems by vm.chargeItems.collectAsState()
    val current = vm.currentPeriod(periods, vm.today)
    val preview by derived(vm, current?.id, current?.status) { current?.let { vm.repos.periods.preview(it.id) } }
    val report by derived(vm, building.id) { vm.reports.load(building.id) }
    var paying by remember { mutableStateOf(false) }

    WithFab(S.payNow, { paying = true }) {
        ScreenList {
            item {
                val offer by vm.ui.surveyOffer.collectAsState()
                if (offer && System.currentTimeMillis() - vm.ui.firstUse > com.github.opscalehub.dong.ui.UiPrefs.SURVEY_AFTER_MS) {
                    SectionCard {
                        Text(S.surveyOffer, style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { nav.navigate(com.github.opscalehub.dong.ui.Routes.SURVEY) }) { Text(S.surveyYes) }
                            TextButton(onClick = { vm.ui.closeSurveyOffer() }) { Text(S.surveyNo) }
                        }
                    }
                }
            }
            // ---- month header ------------------------------------------------
            item {
                if (current == null) {
                    MonthStart(S.thisMonth, S.noPeriod, S.startMonth) {
                        val m = SampleData.month(vm.today)
                        vm.run(S.saved) { vm.repos.periods.create(building.id, m.name, m.start, m.end, m.end + 10) }
                    }
                    return@item
                }
                val finalized = current.status == PeriodStatus.FINALIZED
                val water = preview?.water
                val relevant = water?.meters?.filter { !(it.inactive && it.liters == 0L) }.orEmpty()
                val readDone = relevant.count { it.liters != null }
                val monthExpenses = expenses.filter { it.periodId == current.id && it.status == ExpenseStatus.ACTIVE }
                val blocking = preview?.blockingIssues?.size ?: 0
                val coll = report?.let { Insights.collection(it, current.id) }
                val steps = buildList {
                    if (relevant.isNotEmpty()) add(StepState(S.stepReadings, "${Fmt.number(readDone.toLong())} ${S.of} ${Fmt.number(relevant.size.toLong())} ${S.meters}",
                        readDone == relevant.size, !finalized) { nav.navigate(Routes.READINGS) })
                    // With a fixed monthly charge, bills are optional: they are paid from the fund.
                    val fixedCharge = chargeItems.any { it.active }
                    add(StepState(S.stepExpenses, when {
                        monthExpenses.isNotEmpty() -> "${Fmt.number(monthExpenses.size.toLong())} ${S.items}، ${money(monthExpenses.sumOf { it.amountMinor })}"
                        fixedCharge -> S.expensesOptionalFixed
                        else -> S.empty
                    }, monthExpenses.isNotEmpty() || finalized || fixedCharge, true) { nav.navigate(Routes.EXPENSES) })
                    add(StepState(S.stepReview, when {
                        finalized -> S.monthDone
                        blocking > 0 -> "${Fmt.number(blocking.toLong())} ${S.blockingIssues}"
                        (preview?.total ?: 0L) == 0L -> S.addExpensesFirst // closing an empty month is pointless
                        else -> S.noIssues
                    }, finalized, true) { nav.navigate(Routes.period(current.id)) })
                    val send = report?.let { sendProgress(com.github.opscalehub.dong.data.billedUnits(it, current.id), shares, current.id) }
                    add(StepState(S.stepSend, if (finalized && send != null) S.sentCount.format(Fmt.number(send.first.toLong()), Fmt.number(send.second.toLong())) else S.afterClosing,
                        send != null && send.second > 0 && send.first >= send.second, finalized) { nav.navigate(Routes.send(current.id)) })
                    add(StepState(S.stepCollect, coll?.takeIf { it.billed > 0 }?.let { "${money(it.collected)} ${S.of} ${money(it.billed)}" } ?: S.afterClosing,
                        coll != null && coll.billed > 0 && coll.collected >= coll.billed, finalized) { nav.navigate(Routes.COLLECT) })
                }
                MonthHeader(current.name, finalized, steps.count { it.done }, steps.size)
                Spacer(Modifier.padding(4.dp))
                SectionCard {
                    steps.forEachIndexed { i, s -> StepRow(i + 1, s) }
                }
                if (finalized && vm.today > current.endDate && periods.none { it.startDate > current.endDate }) {
                    Spacer(Modifier.padding(4.dp))
                    MonthStart(S.monthDone, null, S.startNextMonth) {
                        val m = SampleData.month(current.endDate + 1)
                        vm.run(S.saved) { vm.repos.periods.create(building.id, m.name, m.start, m.end, m.end + 10) }
                    }
                }
            }
            // ---- key numbers -------------------------------------------------
            item {
                val bal = report?.balances
                if (bal != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(S.availableCash, money(bal.cashTotal), Modifier.weight(1f), sub = LocalUnit())
                            StatTile(S.outstandingDebts, money(bal.outstandingDebts), Modifier.weight(1f),
                                sub = "${Fmt.number(bal.units.values.count { it > 0 }.toLong())} ${S.debtorUnits}",
                                valueColor = if (bal.outstandingDebts > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                onClick = { nav.navigate(Routes.UNITS) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val reserve = funds.filter { it.kind == FundKind.RESERVE }.sumOf { bal.funds[it.id] ?: 0 }
                            StatTile(S.reserveFund, money(reserve), Modifier.weight(1f))
                            StatTile(S.payables, money(bal.payablesTotal), Modifier.weight(1f), onClick = { nav.navigate(Routes.EXPENSES) })
                        }
                        if (!bal.reconciles) Hint(S.ledgerNotOk, MaterialTheme.colorScheme.error)
                    }
                }
            }
            // ---- collection of the latest closed month ------------------------
            item {
                val d = report ?: return@item
                val last = periods.filter { it.status == PeriodStatus.FINALIZED }.maxByOrNull { it.endDate } ?: return@item
                val c = Insights.collection(d, last.id)
                if (c.billed <= 0) return@item
                SectionCard("${S.collectionRate} — ${last.name}") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(c.perMille, Fmt.digits("${c.perMille / 10}") + "٪", S.collected,
                            description = "${S.collectionRate}: ${money(c.collected)} ${S.of} ${money(c.billed)}")
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${S.collected}: ${money(c.collected)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${S.billed}: ${money(c.billed)}", style = MaterialTheme.typography.bodyMedium)
                            Text("${S.remaining}: ${money(c.outstanding)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // ---- recent transactions ------------------------------------------
            item {
                val d = report ?: return@item
                SectionTitle(S.recentTransactions, S.transactions) { nav.navigate(Routes.FINANCE) }
                val amounts = d.lines.groupBy { it.entryId }.mapValues { (_, l) -> l.filter { it.amountMinor > 0 }.sumOf { it.amountMinor } }
                SectionCard {
                    val recent = d.entries.sortedWith(compareByDescending<com.github.opscalehub.dong.data.db.JournalEntryEntity> { it.entryDate }.thenByDescending { it.createdAt }).take(5)
                    if (recent.isEmpty()) Hint(S.empty)
                    recent.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Fa.entryType(e.type) + (e.unitId?.let { id -> " — ${S.unit} ${units.firstOrNull { it.id == id }?.label ?: ""}" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium)
                                Hint(Fmt.date(e.entryDate, building.calendar) + if (e.id in d.reversedEntryIds) "، ${S.reversedBadge}" else "")
                            }
                            Text(money(amounts[e.id] ?: 0), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
    if (paying) PaymentDialog(vm, building.id, units, building.calendar, 0, close = { paying = false })
}

@Composable
private fun LocalUnit(): String = com.github.opscalehub.dong.ui.LocalMoney.current.unitName

@Composable
private fun MonthHeader(name: String, finalized: Boolean, done: Int, total: Int) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(S.thisMonth, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (finalized) S.finalized else S.draft, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            Text("${Fmt.number(done.toLong())} ${S.of} ${Fmt.number(total.toLong())} ${S.stepsDone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun StepRow(n: Int, s: StepState) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = s.enabled, onClick = s.onClick).padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${s.title}، ${s.sub}" + if (s.done) "، ${S.doneLabel}" else "" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (s.done) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            else Text(Fmt.digits(n.toString()), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Auto-mirrored: points left in RTL, i.e. "forward".
        if (s.enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonthStart(title: String, body: String?, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onClick = onClick) { Text(action) }
        }
    }
}
