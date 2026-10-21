package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.data.decodeTiers
import com.github.opscalehub.dong.data.newId
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.model.TariffTier
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.ClickRow
import com.github.opscalehub.dong.ui.DateInput
import com.github.opscalehub.dong.ui.Dropdown
import com.github.opscalehub.dong.ui.FormDialog
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.KeyValue
import com.github.opscalehub.dong.ui.NumberInput
import com.github.opscalehub.dong.ui.ReasonDialog
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.LocalMoney
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.money
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SingleChoice
import com.github.opscalehub.dong.ui.TextInput
import com.github.opscalehub.dong.ui.rememberCount
import com.github.opscalehub.dong.ui.rememberCubic
import com.github.opscalehub.dong.ui.rememberDate
import com.github.opscalehub.dong.ui.rememberMoney

@Composable
fun WaterScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val meters by vm.meters.collectAsState()
    val readings by vm.readings.collectAsState()
    val units by vm.units.collectAsState()
    val tariffs by vm.tariffs.collectAsState()
    val periods by vm.periods.collectAsState()
    val current = vm.currentPeriod(periods, vm.today)
    val preview by derived(vm, current?.id) { current?.let { vm.repos.periods.preview(it.id) } }
    var addingMeter by remember { mutableStateOf(false) }
    var addingTariff by remember { mutableStateOf(false) }
    val superseded = readings.mapNotNull { it.supersedesId }.toSet()

    val advanced by vm.ui.advanced.collectAsState()
    val active = meters.filter { it.retiredOn == null }
    val unmetered = units.filter { u -> u.active && active.none { it.role == MeterRole.UNIT && it.unitId == u.id } }
    var splitByMeter by rememberSaveable { mutableStateOf(true) }

    WithFab(S.newMeter, { addingMeter = true }) {
        ScreenList {
            item {
                SectionCard {
                    Hint(if (meters.isEmpty()) S.noMetersNeeded else if (advanced) S.waterHint else S.waterHintSimple)
                    if (active.isNotEmpty()) Button(onClick = { nav.navigate(Routes.READINGS) }, Modifier.fillMaxWidth()) { Text(S.readingsBatch) }
                }
            }
            // Meters are generated from the units, never added one at a time.
            if (unmetered.isNotEmpty()) item {
                SectionCard(S.generateMetersTitle, subtitle = S.generateMetersBody) {
                    if (active.isNotEmpty()) Hint(S.generateMetersMissing.format(Fmt.number(unmetered.size.toLong())))
                    com.github.opscalehub.dong.ui.SwitchRow(S.generateMetersSplit, splitByMeter, { splitByMeter = it })
                    OutlinedButton(onClick = {
                        vm.run { vm.message(S.generatedMeters.format(Fmt.number(vm.repos.water.generateMeters(building.id, splitByMeter).toLong()))) }
                    }, Modifier.fillMaxWidth()) { Text(S.generateMetersButton) }
                }
            }
            preview?.water?.let { w ->
                // Main meter vs the units' sum: only meaningful when both exist.
                if (active.any { it.role == MeterRole.MAIN } && active.any { it.role == MeterRole.UNIT } && (w.mainLiters != null || advanced)) item {
                    SectionCard("${S.reconciliation} — ${current?.name ?: ""}", subtitle = S.commonUseNote) {
                        KeyValue(S.mainConsumption, w.mainLiters?.let { "${Fmt.cubicMeters(it)} ${S.m3}" } ?: S.none)
                        KeyValue(S.unitsConsumption, "${Fmt.cubicMeters(w.unitSumLiters)} ${S.m3}")
                        KeyValue(S.unallocated, w.unallocatedLiters?.let { "${Fmt.cubicMeters(it)} ${S.m3}" } ?: S.none, strong = true)
                        // Only what actually holds up the month: readings nobody splits by are just reminders.
                        IssueList(preview?.blockingIssues.orEmpty().filter { it in w.issues }, vm.names())
                    }
                }
            }
            items(meters.size) { i ->
                val m = meters[i]
                val last = readings.filter { it.meterId == m.id && it.id !in superseded }.maxWithOrNull(compareBy({ it.readingDate }, { it.enteredAt }))
                SectionCard {
                    ClickRow({ nav.navigate(Routes.meter(m.id)) }) {
                        Column(Modifier.weight(1f)) {
                            Text(m.label + if (m.retiredOn != null) " (${S.retired})" else "", style = MaterialTheme.typography.titleMedium)
                            if (advanced) Hint(Fa.meterRole(m.role) + (m.unitId?.let { id -> "، ${S.unit} ${units.firstOrNull { it.id == id }?.label ?: ""}" } ?: ""))
                            Hint(S.lastReading + ": " + (last?.let { "${Fmt.cubicMeters(it.valueLiters)} ${S.m3} (${Fmt.date(it.readingDate, building.calendar)})" } ?: S.none))
                        }
                    }
                }
            }
            // Official water tariffs are an advanced way to charge water.
            if (advanced || tariffs.isNotEmpty()) item {
                SectionCard(S.tariffs) {
                    if (tariffs.isEmpty()) Hint(S.empty)
                    val mf = LocalMoney.current
                    tariffs.forEach { t ->
                        Text("${t.name} — ${S.effectiveFrom} ${Fmt.date(t.effectiveFrom, building.calendar)}", style = MaterialTheme.typography.bodyMedium)
                        Hint(decodeTiers(t.tiersJson).joinToString(" ، ") { tier ->
                            (tier.upToLiters?.let { "تا ${Fmt.cubicMeters(it)}" } ?: "بیشتر") + " م³: " + mf.format(tier.priceMinorPerM3)
                        } + "، ${S.fixedFee}: ${money(t.fixedFeeMinor)}، ${S.surcharge}: ${Fmt.percentBp(t.surchargeBp)}")
                    }
                    OutlinedButton(onClick = { addingTariff = true }) { Text(S.newTariff) }
                }
            }
        }
    }
    if (addingMeter) MeterDialog(vm, building.id, units, advanced) { addingMeter = false }
    if (addingTariff) TariffDialog(vm, building.id) { addingTariff = false }
}

@Composable
private fun MeterDialog(vm: AppViewModel, buildingId: String, units: List<UnitEntity>, advanced: Boolean, close: () -> Unit) {
    var label by rememberSaveable { mutableStateOf("") }
    var role by remember { mutableStateOf(MeterRole.UNIT) }
    var unit by remember { mutableStateOf<UnitEntity?>(null) }
    var serial by rememberSaveable { mutableStateOf("") }
    val limit = rememberCubic()
    val ok = label.isNotBlank() && (role != MeterRole.UNIT || unit != null) && (limit.text.isBlank() || limit.valid)
    FormDialog(S.newMeter, close, {
        vm.run(S.saved, onSuccess = close) {
            vm.repos.water.saveMeter(MeterEntity(newId(), buildingId, if (role == MeterRole.UNIT) unit?.id else null, role, label, serial,
                limit.value?.takeIf { limit.text.isNotBlank() && it > 0 }, installedOn = null))
        }
    }, canConfirm = ok) {
        TextInput(S.meterLabel, label, { label = it })
        Text(S.meterRole, style = MaterialTheme.typography.labelMedium)
        SingleChoice(if (advanced) MeterRole.entries else listOf(MeterRole.UNIT, MeterRole.MAIN), role, { role = it }) { Fa.meterRole(it) }
        if (role == MeterRole.UNIT) Dropdown(S.unit, units.filter { it.active }, unit, { unit = it; if (label.isBlank()) label = "کنتور واحد ${it.label}" }, { it.label })
        // Serial and counter limit are for unusual meters only.
        if (advanced) {
            TextInput(S.serial, serial, { serial = it })
            NumberInput(S.rolloverLimit, limit, allowEmpty = true)
        }
    }
}

/** Parses "5:10000;15:20000;:40000" (m³:price). */
fun parseTiers(text: String): List<TariffTier>? {
    val parts = Fmt.latin(text).split(';', '؛').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.map { p ->
        val kv = p.split(':')
        if (kv.size != 2) return null
        val upTo = if (kv[0].isBlank()) null else Fmt.parseCubicMeters(kv[0]) ?: return null
        TariffTier(upTo, kv[1].trim().toLongOrNull() ?: return null)
    }
}

@Composable
private fun TariffDialog(vm: AppViewModel, buildingId: String, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("تعرفه آب") }
    val from = rememberDate(vm.today)
    val fixed = rememberMoney(0)
    var tiers by rememberSaveable { mutableStateOf("") }
    val surcharge = rememberCount(0)
    val common = rememberMoney(0)
    val parsed = parseTiers(tiers)
    FormDialog(S.newTariff, close, {
        vm.run(S.saved, onSuccess = close) {
            vm.repos.water.saveTariff(vm.repos.water.tariffOf(buildingId, name, from.value!!, fixed.value!!, parsed!!, (surcharge.value!! * 100).toInt(), common.value!!))
        }
    }, canConfirm = name.isNotBlank() && from.value != null && fixed.valid && parsed != null && surcharge.valid && common.valid) {
        TextInput(S.name, name, { name = it })
        DateInput(S.effectiveFrom, from, vm.today)
        TextInput(S.tiersField, tiers, { tiers = it }, error = if (tiers.isNotBlank() && parsed == null) S.invalidNumber else null)
        NumberInput(S.fixedFee, fixed)
        NumberInput(S.surcharge, surcharge)
        NumberInput(S.commonRate, common)
    }
}

@Composable
fun MeterScreen(vm: AppViewModel, meterId: String) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val meters by vm.meters.collectAsState()
    val readings by vm.readings.collectAsState()
    val meter = meters.firstOrNull { it.id == meterId } ?: return
    val mine = readings.filter { it.meterId == meterId }.sortedWith(compareByDescending<MeterReadingEntity> { it.readingDate }.thenByDescending { it.enteredAt })
    val superseded = readings.mapNotNull { it.supersedesId }.toSet()
    val periods by vm.periods.collectAsState()
    // Readings up to the last finalized period's end can only be corrected, never deleted.
    val lockedUntil = periods.filter { it.status == com.github.opscalehub.dong.data.db.PeriodStatus.FINALIZED }.maxOfOrNull { it.endDate }
    var adding by remember { mutableStateOf(false) }
    var correcting by remember { mutableStateOf<MeterReadingEntity?>(null) }
    var replacing by remember { mutableStateOf(false) }

    WithFab(if (meter.retiredOn == null) S.newReading else null, { adding = true }) {
        ScreenList {
            item {
                SectionCard(meter.label) {
                    KeyValue(S.meterRole, Fa.meterRole(meter.role))
                    if (meter.serial.isNotBlank()) KeyValue(S.serial, meter.serial)
                    meter.rolloverLimitLiters?.let { KeyValue(S.rolloverLimit, Fmt.cubicMeters(it)) }
                    meter.retiredOn?.let { KeyValue(S.retired, Fmt.date(it, building.calendar)) }
                    if (meter.retiredOn == null) OutlinedButton(onClick = { replacing = true }) { Text(S.replaceMeter) }
                }
            }
            item { Text(S.readings, style = MaterialTheme.typography.titleMedium) }
            items(mine.size) { i ->
                val r = mine[i]
                val isSuperseded = r.id in superseded
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.cubicMeters(r.valueLiters)} ${S.m3}", style = MaterialTheme.typography.titleMedium,
                                textDecoration = if (isSuperseded) TextDecoration.LineThrough else null)
                            Hint("${Fmt.date(r.readingDate, building.calendar)}، ${Fa.readingKind(r.kind)}" + if (isSuperseded) "، ${S.superseded}" else "")
                            if (r.note.isNotBlank()) Hint(r.note)
                        }
                        if (!isSuperseded) {
                            Column {
                                TextButton(onClick = { correcting = r }) { Text(S.correctReading) }
                                if (lockedUntil == null || r.readingDate > lockedUntil) {
                                    TextButton(onClick = { vm.run(S.done) { vm.repos.water.deleteReading(r.id) } }) { Text(S.delete) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        val date = rememberDate(vm.today, building.calendar)
        val value = rememberCubic()
        var kind by remember { mutableStateOf(ReadingKind.REGULAR) }
        var note by rememberSaveable { mutableStateOf("") }
        FormDialog(S.newReading, { adding = false }, {
            vm.run(S.saved, onSuccess = { adding = false }) { vm.repos.water.addReading(meterId, date.value!!, value.value!!, kind, note) }
        }, canConfirm = date.value != null && value.valid) {
            DateInput(S.date, date, vm.today)
            NumberInput(S.readingValue, value)
            Text(S.readingKind, style = MaterialTheme.typography.labelMedium)
            SingleChoice(ReadingKind.entries.filter { it != ReadingKind.FINAL }, kind, { kind = it }) { Fa.readingKind(it) }
            TextInput(S.notes, note, { note = it })
        }
    }
    correcting?.let { r ->
        val value = rememberCubic()
        var reason by rememberSaveable { mutableStateOf("") }
        FormDialog(S.correctReading, { correcting = null }, {
            vm.run(S.saved, onSuccess = { correcting = null }) { vm.repos.water.correctReading(r.id, value.value!!, reason) }
        }, canConfirm = value.valid && reason.isNotBlank()) {
            Hint("${Fmt.cubicMeters(r.valueLiters)} ${S.m3}، ${Fmt.date(r.readingDate, building.calendar)}")
            NumberInput(S.correctedValue, value)
            TextInput(S.reasonRequiredHint, reason, { reason = it })
        }
    }
    if (replacing) {
        val date = rememberDate(vm.today, building.calendar)
        val final = rememberCubic()
        var newLabel by rememberSaveable { mutableStateOf(meter.label + " (جدید)") }
        var serial by rememberSaveable { mutableStateOf("") }
        val initial = rememberCubic(0)
        FormDialog(S.replaceMeter, { replacing = false }, {
            vm.run(S.saved, onSuccess = { replacing = false }) { vm.repos.water.replaceMeter(meterId, date.value!!, final.value!!, newLabel, serial, initial.value!!) }
        }, canConfirm = date.value != null && final.valid && initial.valid && newLabel.isNotBlank()) {
            DateInput(S.date, date, vm.today)
            NumberInput(S.finalValue, final)
            TextInput(S.newMeterLabel, newLabel, { newLabel = it })
            TextInput(S.serial, serial, { serial = it })
            NumberInput(S.initialValue, initial)
        }
    }
}
