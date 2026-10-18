package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.data.billedUnits
import com.github.opscalehub.dong.data.db.BillShareEntity
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.share.BillShare
import com.github.opscalehub.dong.share.SummaryShare
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.BalanceText
import com.github.opscalehub.dong.ui.EmptyState
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.LocalMoney
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SwitchRow
import com.github.opscalehub.dong.ui.UnitChip
import com.github.opscalehub.dong.ui.money
import java.time.Instant
import java.time.ZoneId

/** Epoch millis → the building's calendar date. */
private fun shareDate(at: Long, b: BuildingEntity) = Fmt.date(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(), b.calendar)

/** Units billed in [periodId] and, of those, the ones whose bill went out. */
fun sendProgress(billed: Set<String>, shares: List<BillShareEntity>, periodId: String): Pair<Int, Int> =
    billed.count { u -> shares.any { it.periodId == periodId && it.unitId == u } } to billed.size

/** Home step 4: every unit's bill for a closed month, with who has received it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendBillsScreen(vm: AppViewModel, periodId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val periods by vm.periods.collectAsState()
    val units by vm.units.collectAsState()
    val shares by vm.billShares.collectAsState()
    val p = periods.firstOrNull { it.id == periodId } ?: return
    val report by derived(vm, building.id) { vm.reports.load(building.id) }
    val context = LocalContext.current
    val mf = LocalMoney.current
    val d = report

    ScreenList {
        if (p.status != PeriodStatus.FINALIZED) {
            item { EmptyState(S.stepSend, S.afterClosing) }
            return@ScreenList
        }
        if (d == null) return@ScreenList
        val billed = billedUnits(d, p.id)
        val (sent, total) = sendProgress(billed, shares, p.id)
        item {
            SectionCard("${S.stepSend} — ${p.name}") {
                Text(S.sentCount.format(Fmt.number(sent.toLong()), Fmt.number(total.toLong())), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (total == 0) 0f else sent.toFloat() / total }, Modifier.fillMaxWidth())
                Hint(S.sendHint)
                SummaryShareButton(vm, building, p)
            }
        }
        val rows = units.filter { it.id in billed }
        items(rows.size) { i ->
            val u = rows[i]
            val mine = shares.filter { it.periodId == p.id && it.unitId == u.id }
            val last = mine.maxByOrNull { it.at }
            val charge = d.periodResults[p.id]?.lines.orEmpty().filter { it.unitId == u.id }.sumOf { it.amount }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnitChip(u.label, if (last != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${S.unit} ${u.label}", style = MaterialTheme.typography.titleMedium)
                        Hint("${S.billThisMonth}: ${money(charge)}")
                        Hint(if (last == null) S.notSent else "${S.sentOn} ${shareDate(last.at, building)}" +
                            if (mine.size > 1) " (${Fmt.number(mine.size.toLong())} ${S.times})" else "",
                            if (last == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                    }
                    BalanceText(d.balances.units[u.id] ?: 0)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val share = {
                        vm.run {
                            val bill = vm.repos.bills.bill(building.id, u.id, p.id)
                            val reminder = S.reminderText.format(u.label, building.name, mf.withUnit(bill.due.coerceAtLeast(0)))
                            BillShare.shareBill(context, bill, mf, reminder, p.id, u.id)
                        }
                    }
                    if (last == null) Button(onClick = share) { Text(S.shareBill) } else OutlinedButton(onClick = share) { Text(S.sendAgain) }
                    if (last == null) TextButton(onClick = { vm.run(S.saved) { vm.repos.bills.markSent(p.id, u.id, BillShareEntity.MANUAL) } }) { Text(S.markSent) }
                    else TextButton(onClick = { vm.run { vm.repos.bills.unmarkSent(p.id, u.id) } }) { Text(S.unmarkSent) }
                }
            }
        }
    }
}

/**
 * «تصویر خلاصه ماه»: the month as one image for the building's group or channel. Per-unit
 * shares are on by default; per-unit debts are off, because everyone in the group sees them.
 */
@Composable
fun SummaryShareButton(vm: AppViewModel, building: BuildingEntity, p: PeriodEntity) {
    val context = LocalContext.current
    val mf = LocalMoney.current
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth(), enabled = p.status == PeriodStatus.FINALIZED) { Text(S.shareSummary) }
    if (open) {
        var units by remember { mutableStateOf(true) }
        var debts by remember { mutableStateOf(false) }
        FormDialog(S.shareSummary, { open = false }, {
            vm.run(onSuccess = { open = false }) {
                SummaryShare.share(context, vm.repos.bills.monthSummary(building.id, p.id), mf, units, debts)
            }
        }, confirmText = S.share) {
            Hint(S.summaryHint)
            SwitchRow(S.summaryUnits, units, { units = it })
            SwitchRow(S.summaryShowDebts, debts && units, { debts = it }, supporting = S.summaryDebtsHint)
        }
    }
}
