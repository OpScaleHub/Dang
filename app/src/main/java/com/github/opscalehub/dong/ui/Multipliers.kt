package com.github.opscalehub.dong.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.i18n.Fmt

/** One row of the multiplier editor: what the person sees, and the floor for «بر اساس طبقه». */
data class MultiplierRow(val label: String, val floor: Int)

/** Choices shown as chips; 0 means the unit is exempt. Larger values (from «بر اساس طبقه») still display. */
val MULTIPLIER_CHOICES = listOf(0L, 1L, 2L, 3L, 4L)

/** «۲ برابر» reads naturally in Persian; a bare «×» flips around in right-to-left text. */
fun multiplierLabel(w: Long) = if (w == 0L) S.exempt else "${Fmt.digits(w.toString())} ${S.times2}"

/**
 * Per-unit multipliers in plain terms: «۱ برابر، ۲ برابر…» instead of raw weights. A unit at
 * «۲ برابر» pays twice what a «۱ برابر» unit pays. «بر اساس طبقه» sets each unit to its floor number (ground floor 0 = exempt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiplierEditor(rows: List<MultiplierRow>, weights: List<Long>, onChange: (List<Long>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint(S.multipliersHint)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(rows.map { it.floor.coerceAtLeast(0).toLong() }) }) { Text(S.byFloor) }
            OutlinedButton(onClick = { onChange(rows.map { 1L }) }) { Text(S.allOne) }
        }
        rows.forEachIndexed { i, r ->
            val w = weights.getOrElse(i) { 1L }
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${r.label} — ${multiplierLabel(w)}", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MULTIPLIER_CHOICES.forEach { c ->
                        FilterChip(selected = w == c, onClick = { onChange(weights.toMutableList().also { l -> while (l.size < rows.size) l += 1L; l[i] = c }) },
                            label = { Text(multiplierLabel(c)) })
                    }
                }
            }
        }
        if (weights.isNotEmpty() && weights.all { it == 0L }) Hint(S.multipliersAllZero, MaterialTheme.colorScheme.error)
    }
}
