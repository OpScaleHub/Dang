package com.github.opscalehub.dong.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.data.WaterRepository
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.DateInput
import com.github.opscalehub.dong.ui.EmptyState
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.Routes
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.rememberDate

/** All active meters on one screen: previous reading, live consumption and anomaly flags. */
@Composable
fun ReadingsScreen(vm: AppViewModel, nav: NavController) {
    val b by vm.building.collectAsState()
    val building = b ?: return
    val meters by vm.meters.collectAsState()
    val readings by vm.readings.collectAsState()
    val units by vm.units.collectAsState()
    val periods by vm.periods.collectAsState()
    val advanced by vm.ui.advanced.collectAsState()
    val draft = vm.currentPeriod(periods, vm.today)?.takeIf { it.status == PeriodStatus.DRAFT }
    val date = rememberDate(draft?.let { minOf(vm.today, it.endDate).coerceAtLeast(it.startDate) } ?: vm.today, building.calendar)
    val input = remember { mutableStateMapOf<String, String>() }
    val superseded = readings.mapNotNull { it.supersedesId }.toSet()
    // Main meter first, then in the building's unit order (not alphabetically by label).
    val unitOrder = units.associate { it.id to it.sortOrder }
    val active = meters.filter { it.retiredOn == null }.sortedWith(compareBy({ it.role != MeterRole.MAIN }, { unitOrder[it.unitId] ?: Int.MAX_VALUE }, { it.label }))
    val day = date.value ?: vm.today

    data class Row(val prev: MeterReadingEntity?, val prevUse: Long?, val value: Long?, val use: Long?)
    val rows = active.associate { m ->
        val eff = readings.filter { it.meterId == m.id && it.id !in superseded && it.readingDate <= day }.sortedWith(compareBy({ it.readingDate }, { it.enteredAt }))
        val prev = eff.lastOrNull()
        val before = eff.dropLast(1).lastOrNull()
        val prevUse = if (prev != null && before != null && prev.valueLiters >= before.valueLiters) prev.valueLiters - before.valueLiters else null
        val value = input[m.id]?.takeIf { it.isNotBlank() }?.let { Fmt.parseCubicMeters(it) }
        m.id to Row(prev, prevUse, value, if (value != null && prev != null) value - prev.valueLiters else null)
    }
    val entered = rows.filterValues { it.value != null }
    val negatives = entered.filterValues { (it.use ?: 0) < 0 }
    val invalid = input.filterValues { it.isNotBlank() && Fmt.parseCubicMeters(it) == null }

    if (active.isEmpty()) {
        EmptyState(S.empty, S.waterHint, S.newMeter) { nav.navigate(Routes.WATER) }
        return
    }
    ScreenList {
        item {
            SectionCard(subtitle = S.readingsBatchHint) {
                DateInput(S.date, date, vm.today)
                draft?.let { Hint("${S.month}: ${it.name}") }
            }
        }
        items(active.size) { i ->
            val m = active[i]
            val r = rows.getValue(m.id)
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (advanced) Hint(Fa.meterRole(m.role) + (m.unitId?.let { id -> "، ${S.unit} ${units.firstOrNull { it.id == id }?.label ?: ""}" } ?: ""))
                        Hint(r.prev?.let { "${S.previous}: ${Fmt.cubicMeters(it.valueLiters)} (${Fmt.date(it.readingDate, building.calendar)})" } ?: S.firstReading)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = input[m.id] ?: "", onValueChange = { input[m.id] = it }, modifier = Modifier.width(140.dp),
                        label = { Text(S.m3) }, singleLine = true, isError = (r.use ?: 0) < 0 || m.id in invalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        visualTransformation = com.github.opscalehub.dong.ui.PersianDigitsTransformation,
                    )
                }
                r.use?.let { use ->
                    val unusual = use < 0 || (r.prevUse != null && r.prevUse > 0 && use > 2 * r.prevUse)
                    Text(
                        "${S.consumption}: ${Fmt.cubicMeters(use)} ${S.m3}" + when {
                            use < 0 -> " — " + S.lowerThanPrevious
                            unusual -> " — ${S.unusual}: ${S.muchHigher} (${Fmt.cubicMeters(r.prevUse!!)})"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (unusual) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (entered.isEmpty()) {
                        vm.message(S.nothingToSave)
                        return@Button
                    }
                    vm.run("${Fmt.number(entered.size.toLong())} ${S.savedCount}", onSuccess = { nav.popBackStack() }) {
                        vm.repos.water.addReadings(day, entered.map { (id, r) -> WaterRepository.ReadingInput(id, r.value!!) })
                    }
                },
                enabled = date.value != null && negatives.isEmpty() && invalid.isEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("${S.saveAll} (${Fmt.number(entered.size.toLong())})") }
            if (negatives.isNotEmpty()) Hint(S.lowerThanPrevious, MaterialTheme.colorScheme.error)
        }
    }
}
