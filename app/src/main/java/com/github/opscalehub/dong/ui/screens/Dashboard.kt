package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.SampleData
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.period.IssueCode
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Badge
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard

private val readingCodes = setOf(IssueCode.MISSING_READING, IssueCode.MISSING_OPENING_READING, IssueCode.MISSING_CLOSING_READING, IssueCode.NEGATIVE_CONSUMPTION, IssueCode.READING_AFTER_FINAL, IssueCode.ROLLOVER_WITHOUT_LIMIT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val periods by vm.periods.collectAsState()
    val funds by vm.funds.collectAsState()
    val entries by vm.entries.collectAsState()
    val digits = building.minorDigits
    val balances by derived(vm, building.id) { vm.repos.finance.balances(building.id) }
    val current = vm.currentPeriod(periods, vm.today)
    val preview by derived(vm, current?.id) { current?.let { vm.repos.periods.preview(it.id) } }

    ScreenList {
        item {
            SectionCard(S.currentPeriod) {
                if (current == null) {
                    Hint(S.noPeriod)
                    Button(onClick = {
                        val m = SampleData.month(vm.today)
                        vm.run(S.saved) { vm.repos.periods.create(building.id, m.name, m.start, m.end, m.end + 10) }
                    }) { Text(S.createPeriodNow) }
                } else {
                    ClickRow({ nav.navigate(Routes.period(current.id)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(current.name, style = MaterialTheme.typography.titleMedium)
                            Hint("${Fmt.date(current.startDate, building.calendar)} — ${Fmt.date(current.endDate, building.calendar)}")
                        }
                        Badge(if (current.status == PeriodStatus.FINALIZED) S.finalized else S.draft)
                    }
                    preview?.let { p ->
                        KeyValue(S.chargesTotal, Fmt.money(p.total, digits))
                        val blocking = p.blockingIssues.size
                        if (current.status == PeriodStatus.DRAFT) {
                            KeyValue("${S.blockingIssues}", Fmt.number(blocking.toLong()), valueColor = if (blocking > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                        val missing = p.issues.filter { it.code in readingCodes }
                        KeyValue(S.missingReadings, Fmt.number(missing.size.toLong()), valueColor = if (missing.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        missing.take(4).forEach { Hint("• " + Fa.issue(it, vm.names()), MaterialTheme.colorScheme.error) }
                        p.water.unallocatedLiters?.let { KeyValue(S.unresolvedWater, "${Fmt.cubicMeters(it)} ${S.m3}") }
                    }
                }
            }
        }
        item {
            val bal = balances
            SectionCard(S.buildingSummary) {
                if (bal != null) {
                    KeyValue(S.outstandingDebts, Fmt.money(bal.outstandingDebts, digits), strong = true, valueColor = MaterialTheme.colorScheme.error)
                    Hint("${Fmt.number(bal.units.values.count { it > 0 }.toLong())} ${S.debtorUnits}، ${S.debtVsCashNote}")
                    KeyValue(S.residentCredits, Fmt.money(bal.residentCredits, digits))
                    KeyValue(S.availableCash, Fmt.money(bal.cashTotal, digits), strong = true)
                    funds.filter { it.kind == FundKind.RESERVE }.forEach { KeyValue("${S.reserveFund}: ${it.name}", Fmt.money(bal.funds[it.id] ?: 0, digits)) }
                    KeyValue(S.payables, Fmt.money(bal.payablesTotal, digits))
                    Hint(if (bal.reconciles) S.ledgerOk else S.ledgerNotOk, if (bal.reconciles) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            SectionCard(S.quickActions) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { nav.navigate(Routes.FINANCE) }, label = { Text(S.recordPayment) })
                    AssistChip(onClick = { nav.navigate(Routes.expense("new")) }, label = { Text(S.recordExpense) })
                    AssistChip(onClick = { nav.navigate(Routes.WATER) }, label = { Text(S.recordReading) })
                    AssistChip(onClick = { nav.navigate(Routes.REPORTS) }, label = { Text(S.reports) })
                }
            }
        }
        item {
            SectionCard(S.recentTransactions) {
                if (entries.isEmpty()) Hint(S.empty)
                val reversed = entries.mapNotNull { it.reversesEntryId }.toSet()
                entries.take(8).forEach { e ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(Fa.entryType(e.type) + if (e.id in reversed) " (${S.reversedBadge})" else "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Hint("${Fmt.date(e.entryDate, building.calendar)}، ${e.memo}".take(90))
                        }
                    }
                }
            }
        }
    }
}
