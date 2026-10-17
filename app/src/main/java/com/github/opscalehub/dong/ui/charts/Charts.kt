package com.github.opscalehub.dong.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.ui.theme.LocalViz

/*
 * Chart primitives. Bars are plain layout boxes (automatically right-to-left,
 * accessible, wrap-safe); only the ring and the sparkline draw on a Canvas.
 * Marks are thin with 4dp rounded data ends, 2dp gaps between fills, and text
 * always uses text colours — never the series colour.
 */

private val DataEnd = 4.dp

/** Progress ring with a hero figure in the middle (single series: no legend). */
@Composable
fun Ring(perMille: Int, center: String, caption: String, color: Color = MaterialTheme.colorScheme.primary, size: Dp = 132.dp, description: String = "$center $caption") {
    val viz = LocalViz.current
    Box(Modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(viz.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            val sweep = 360f * perMille.coerceIn(0, 1000) / 1000f
            // Clockwise from the top reads right-to-left in an RTL layout.
            if (sweep > 0f) drawArc(color, -90f, -sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class HBar(val label: String, val value: Long, val valueText: String, val note: String? = null, val noteColor: Color? = null)

/**
 * Horizontal bars, one hue, labelled by name (identity is the label, not colour).
 * [reference] draws a dashed reference line (e.g. the building average).
 */
@Composable
fun HBars(items: List<HBar>, color: Color = LocalViz.current.categorical[0], reference: Long? = null, referenceLabel: String? = null) {
    val viz = LocalViz.current
    val max = (items.maxOfOrNull { it.value } ?: 0L).coerceAtLeast(reference ?: 0L).coerceAtLeast(1L)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { b ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (b.note != null) Text(b.note, style = MaterialTheme.typography.labelSmall, color = b.noteColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(b.valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(10.dp)) {
                    val f = (b.value.toFloat() / max).coerceIn(0f, 1f)
                    if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(DataEnd)).background(color))
                    if (reference != null) {
                        val rf = (reference.toFloat() / max).coerceIn(0f, 1f)
                        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                            val x = size.width * (1 - rf) // RTL: values grow from the right edge
                            drawLine(viz.muted, Offset(x, -4f), Offset(x, size.height + 4f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        }
                    }
                }
            }
        }
        if (reference != null && referenceLabel != null) {
            Text("┆ $referenceLabel", style = MaterialTheme.typography.labelSmall, color = viz.muted)
        }
    }
}

data class Segment(val label: String, val value: Long, val color: Color, val valueText: String)

/** One horizontal stacked bar (composition) with a legend that always names the parts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StackedBar(segments: List<Segment>, height: Dp = 16.dp) {
    val total = segments.sumOf { it.value }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(DataEnd)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.filter { it.value > 0 }.forEach { s ->
                Box(Modifier.weight(s.value.toFloat() / total).fillMaxHeight().background(s.color))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            segments.filter { it.value > 0 }.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                    Spacer(Modifier.width(4.dp))
                    Text("${s.label}: ${s.valueText}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

data class ColumnGroup(val label: String, val values: List<Long>)

/**
 * Grouped columns over time (oldest on the right, as Persian is read). Tap a
 * group to inspect its values — the mobile equivalent of a hover tooltip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupedColumns(groups: List<ColumnGroup>, seriesNames: List<String>, format: (Long) -> String, height: Dp = 140.dp) {
    val viz = LocalViz.current
    val colors = viz.categorical
    // Open on the latest group that has data (the current month is often still empty).
    var selected by remember(groups.size) { mutableIntStateOf(groups.indexOfLast { g -> g.values.any { it != 0L } }.takeIf { it >= 0 } ?: groups.lastIndex) }
    val max = groups.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            seriesNames.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(4.dp))
                    Text(n, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            groups.forEachIndexed { gi, g ->
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                        .background(if (gi == selected) viz.track.copy(alpha = 0.6f) else Color.Transparent)
                        .clickable { selected = gi }.padding(horizontal = 2.dp)
                        .semantics(mergeDescendants = true) { contentDescription = g.label + ": " + g.values.mapIndexed { i, v -> "${seriesNames[i]} ${format(v)}" }.joinToString("، ") },
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    g.values.forEachIndexed { i, v ->
                        val f = (v.toFloat() / max).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).fillMaxHeight(f.coerceAtLeast(0.01f)).clip(RoundedCornerShape(topStart = DataEnd, topEnd = DataEnd)).background(colors[i]))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(viz.baseline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEachIndexed { gi, g ->
                Text(g.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1,
                    overflow = TextOverflow.Clip, fontWeight = if (gi == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
        groups.getOrNull(selected)?.let { g ->
            Text(g.label + ": " + g.values.mapIndexed { i, v -> "${seriesNames[i]} ${format(v)}" }.joinToString("، "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

data class Step(val label: String, val value: Long, val total: Boolean = false)

/**
 * Waterfall: totals are neutral columns from zero; deltas float, money in and
 * out use the two diverging poles. Labels carry the sign, so colour is never alone.
 */
@Composable
fun Waterfall(steps: List<Step>, format: (Long) -> String, height: Dp = 150.dp) {
    val viz = LocalViz.current
    data class Bar(val top: Long, val bottom: Long, val color: Color, val step: Step)
    var running = 0L
    val bars = steps.map { s ->
        if (s.total) {
            running = s.value
            Bar(maxOf(0, s.value), minOf(0, s.value), viz.neutral, s)
        } else {
            val from = running
            running += s.value
            Bar(maxOf(from, running), minOf(from, running), if (s.value >= 0) viz.positive else viz.negative, s)
        }
    }
    val hi = bars.maxOf { it.top }.coerceAtLeast(1)
    val lo = bars.minOf { it.bottom }.coerceAtMost(0)
    val range = (hi - lo).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bars.forEach { b ->
                Column(Modifier.weight(1f).fillMaxHeight().semantics(mergeDescendants = true) { contentDescription = "${b.step.label} ${format(b.step.value)}" }) {
                    val above = (hi - b.top) / range
                    val body = ((b.top - b.bottom) / range).coerceAtLeast(0.01f)
                    val below = (b.bottom - lo) / range
                    if (above > 0f) Spacer(Modifier.weight(above))
                    Box(Modifier.fillMaxWidth().weight(body).clip(RoundedCornerShape(DataEnd)).background(b.color))
                    if (below > 0f) Spacer(Modifier.weight(below))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(viz.baseline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bars.forEach { b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(b.step.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
                    Text(if (!b.step.total && b.step.value > 0) com.github.opscalehub.dong.i18n.Fmt.signed(true, format(b.step.value)) else format(b.step.value), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** Small trend line (oldest on the right). Single series, last point marked. */
@Composable
fun Sparkline(values: List<Long>, color: Color = LocalViz.current.categorical[0], modifier: Modifier = Modifier.fillMaxWidth().height(48.dp), description: String = "") {
    if (values.size < 2) return
    val hi = values.max()
    val lo = values.min()
    val span = (hi - lo).toFloat().takeIf { it > 0f } ?: 1f
    Canvas(modifier.semantics { contentDescription = description }) {
        val pad = 6.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        fun pt(i: Int): Offset {
            val x = size.width - pad - w * i / (values.size - 1) // RTL: index 0 (oldest) on the right
            val y = pad + h * (1 - (values[i] - lo) / span)
            return Offset(x, y)
        }
        for (i in 1 until values.size) drawLine(color, pt(i - 1), pt(i), 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, 4.dp.toPx(), pt(values.lastIndex))
    }
}

/** A row of ordinal-coloured segments for one unit's debt by age, with the legend handled by the caller. */
@Composable
fun AgingBar(parts: List<Long>, max: Long, modifier: Modifier = Modifier) {
    val viz = LocalViz.current
    val total = parts.sum()
    Row(modifier.fillMaxWidth().height(10.dp)) {
        val f = (total.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        if (f > 0f) {
            Row(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(DataEnd)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                parts.forEachIndexed { i, v -> if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(viz.aging[i])) }
            }
        }
    }
}
