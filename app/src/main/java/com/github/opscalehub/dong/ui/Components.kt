package com.github.opscalehub.dong.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.i18n.MoneyFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.opscalehub.dong.domain.model.CalendarSystem
import com.github.opscalehub.dong.i18n.Fmt

/** Standard scrollable screen body with 16dp gutters. */
@Composable
fun ScreenList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)

@Composable
fun KeyValue(key: String, value: String, strong: Boolean = false, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = valueColor, textAlign = TextAlign.End)
    }
}

@Composable
fun ClickRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun Divider() = HorizontalDivider(Modifier.padding(vertical = 2.dp))

@Composable
fun EmptyText(text: String = S.empty) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = color))
}

@Composable
fun BalanceText(minor: Long, @Suppress("UNUSED_PARAMETER") digits: Int = 0) {
    val (label, color) = when {
        minor > 0 -> S.owes to MaterialTheme.colorScheme.error
        minor < 0 -> S.credit to MaterialTheme.colorScheme.primary
        else -> S.settled to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(if (minor == 0L) label else "$label ${money(kotlin.math.abs(minor))}", color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

// ------------------------------------------------------------------ form fields

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null,
              keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true, supporting: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

/** The current building's money format (display unit); provided at the root. */
val LocalMoney = staticCompositionLocalOf { MoneyFormat() }

/** Formats stored minor units in the current building's display unit. */
@Composable
@ReadOnlyComposable
fun money(minor: Long): String = LocalMoney.current.format(minor)

/** Holds the text of a numeric field and its parsed value. */
class NumberFieldState(initial: String, private val parser: (String) -> Long?, val grouped: Boolean = false, val unit: String? = null) {
    var text by mutableStateOf(initial)
    val value: Long? get() = parser(text)
    val valid: Boolean get() = value != null
}

/** Money typed in the display unit (e.g. Toman); [value] is exact stored minor units. */
@Composable
fun rememberMoney(initial: Long? = null): NumberFieldState {
    val mf = LocalMoney.current
    return remember(mf) {
        NumberFieldState(initial?.let { Fmt.moneyIn(it, mf.minorDigits, mf.divisor, persian = false).replace(",", "") } ?: "", { mf.parse(it) }, grouped = true, unit = mf.unitName)
    }
}

@Composable
fun rememberCubic(initial: Long? = null) = remember { NumberFieldState(initial?.let { Fmt.cubicMeters(it) } ?: "", parser = { Fmt.parseCubicMeters(it) }) }

@Composable
fun rememberCount(initial: Long? = null) = remember { NumberFieldState(initial?.let { Fmt.digits(it.toString()) } ?: "", parser = { Fmt.parseLong(it) }) }

/**
 * Shows typed digits as grouped Persian digits («۱٬۸۰۰٬۰۰۰») while the field
 * keeps the raw text, so a missing or extra zero is visible while typing.
 */
object GroupingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.any { !(it.isDigit() || it == '.' || it == '٫' || it == '-' || it == '−') }) return TransformedText(text, OffsetMapping.Identity)
        val intEnd = raw.indexOfFirst { it == '.' || it == '٫' }.let { if (it < 0) raw.length else it }
        val signLen = if (raw.startsWith("-") || raw.startsWith("−")) 1 else 0
        val digitsInInt = intEnd - signLen
        val out = StringBuilder()
        val sepBefore = IntArray(raw.length + 1) // separators inserted before original index i
        var seps = 0
        raw.forEachIndexed { i, c ->
            if (i in signLen until intEnd && i > signLen && (intEnd - i) % 3 == 0 && digitsInInt > 3) {
                out.append('٬'); seps++
            }
            sepBefore[i] = seps
            out.append(if (c.isDigit()) Fmt.digits(Fmt.latin(c.toString())) else if (c == '.') "٫" else c.toString())
        }
        sepBefore[raw.length] = seps
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + sepBefore[offset.coerceIn(0, raw.length)]
            override fun transformedToOriginal(offset: Int): Int {
                var o = 0
                while (o < raw.length && o + sepBefore[o + 1] < offset) o++
                return o.coerceIn(0, raw.length)
            }
        }
        return TransformedText(AnnotatedString(out.toString()), mapping)
    }
}

/** Shows typed Latin digits as Persian digits (one-to-one, so cursor offsets are unchanged). */
object PersianDigitsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(AnnotatedString(Fmt.digits(text.text).replace('.', '٫')), OffsetMapping.Identity)
}

@Composable
fun NumberInput(label: String, state: NumberFieldState, modifier: Modifier = Modifier, allowEmpty: Boolean = false, supporting: String? = null, showError: Boolean = true) {
    val err = if (!showError || (allowEmpty && state.text.isBlank())) null else if (state.text.isNotBlank() && !state.valid) S.invalidNumber else null
    OutlinedTextField(
        value = state.text, onValueChange = { state.text = it }, label = { Text(label) }, modifier = modifier.fillMaxWidth(),
        singleLine = true, isError = err != null,
        supportingText = (err ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        visualTransformation = if (state.grouped) GroupingTransformation else PersianDigitsTransformation,
        suffix = state.unit?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingIcon = if (state.grouped) ({
            TextButton(onClick = { state.text = Fmt.latin(state.text).ifBlank { "1" } + "000" }) { Text(S.zeros) }
        }) else null,
    )
}

/** Date field in the building calendar; accepts Persian or Latin digits, or the picker. */
class DateFieldState(initial: Long?, val calendar: CalendarSystem) {
    var text by mutableStateOf(initial?.let { Fmt.date(it, calendar) } ?: "")
    val value: Long? get() = Fmt.parseDate(text, calendar)
    fun set(epochDay: Long) {
        text = Fmt.date(epochDay, calendar)
    }
}

@Composable
fun rememberDate(initial: Long?, calendar: CalendarSystem = CalendarSystem.JALALI) = remember { DateFieldState(initial, calendar) }

@Composable
fun DateInput(label: String, state: DateFieldState, today: Long, modifier: Modifier = Modifier, allowEmpty: Boolean = false) {
    val bad = state.text.isNotBlank() && state.value == null
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.text, onValueChange = { state.text = it }, label = { Text(label) }, singleLine = true,
        isError = bad || (!allowEmpty && state.text.isBlank()), modifier = modifier.fillMaxWidth(),
        supportingText = if (bad) ({ Text(S.invalidDate) }) else state.value?.let { v -> { Text(Fmt.longDate(v, state.calendar)) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            IconButton(onClick = { picking = true }) { Icon(Icons.Filled.DateRange, S.calendarPick) }
        },
    )
    if (picking) {
        DatePickerDialog(state.value ?: today, today, state.calendar, { picking = false }) {
            state.set(it)
            picking = false
        }
    }
}

/** Month grid date picker; Saturday-first week and Persian month names for the Jalali calendar. */
@Composable
fun DatePickerDialog(initial: Long, today: Long, calendar: CalendarSystem, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    fun ym(d: Long): Pair<Int, Int> = if (calendar == CalendarSystem.JALALI) Jalali.fromEpochDay(d).let { it.year to it.month }
        else java.time.LocalDate.ofEpochDay(d).let { it.year to it.monthValue }
    var shown by remember { mutableStateOf(ym(initial)) }
    var chosen by remember { mutableStateOf(initial) }
    val (y, m) = shown
    val first = if (calendar == CalendarSystem.JALALI) Jalali.toEpochDay(y, m, 1) else java.time.LocalDate.of(y, m, 1).toEpochDay()
    val length = if (calendar == CalendarSystem.JALALI) Jalali.monthLength(y, m) else java.time.YearMonth.of(y, m).lengthOfMonth()
    val offset = (java.time.LocalDate.ofEpochDay(first).dayOfWeek.value + 1) % 7 // Saturday = 0
    val title = if (calendar == CalendarSystem.JALALI) "${Jalali.monthNames[m - 1]} ${Fmt.digits(y.toString())}" else Fmt.digits("$y/$m")
    fun move(delta: Int) {
        var nm = m + delta
        var ny = y
        if (nm < 1) { nm = 12; ny-- }
        if (nm > 12) { nm = 1; ny++ }
        shown = ny to nm
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(chosen) }) { Text(S.confirm) } },
        dismissButton = {
            Row {
                TextButton(onClick = { chosen = today; shown = ym(today) }) { Text(S.today) }
                TextButton(onClick = onDismiss) { Text(S.cancel) }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Auto-mirrored: in RTL the "left" arrow points right — backwards in time for a right-to-left reader.
                IconButton(onClick = { move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, S.previousMonth) }
                Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { move(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, S.nextMonth) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    S.weekDays.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val cells = offset + length
                for (row in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val day = row * 7 + col - offset + 1
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (day in 1..length) {
                                    val epoch = first + day - 1
                                    val sel = epoch == chosen
                                    Box(
                                        Modifier.fillMaxSize().clip(CircleShape)
                                            .background(if (sel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                                            .clickable { chosen = epoch },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(Fmt.digits(day.toString()), style = MaterialTheme.typography.bodyMedium,
                                            color = if (sel) MaterialTheme.colorScheme.onPrimary else if (epoch == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (epoch == today) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

/** Big number with a caption; the building block of the home screen. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null, valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    Card(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** A unit's label in a rounded chip («۲-۱»), used as an avatar in lists. */
@Composable
fun UnitChip(label: String, container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(container), contentAlignment = Alignment.Center) {
        Text(Fmt.digits(label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
    }
}

/** Friendly empty state with an optional call to action. */
@Composable
fun EmptyState(title: String, body: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) androidx.compose.material3.Button(onClick = onAction) { Text(action) }
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, options: List<T>, selected: T?, onSelect: (T) -> Unit, text: (T) -> String, modifier: Modifier = Modifier, error: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = selected?.let(text) ?: "", onValueChange = {}, readOnly = true, label = { Text(label) },
            placeholder = { Text(S.select) }, isError = error,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = {
                    onSelect(o)
                    open = false
                })
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) Hint(supporting)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipSelect(options: List<T>, selected: Set<T>, onToggle: (T) -> Unit, text: (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o in selected, onClick = { onToggle(o) }, label = { Text(text(o)) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SingleChoice(options: List<T>, selected: T, onSelect: (T) -> Unit, text: (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(text(o)) }) }
    }
}

/** Full-height dialog for forms; [onConfirm] enabled only when [canConfirm]. */
@Composable
fun FormDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, canConfirm: Boolean = true, confirmText: String = S.save,
               content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit, confirmText: String = S.confirm) =
    FormDialog(title, onDismiss, onConfirm, confirmText = confirmText) { Text(body) }

/** Asks for a mandatory reason (reversals, voids, corrections). */
@Composable
fun ReasonDialog(title: String, hint: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    FormDialog(title, onDismiss, { onConfirm(reason.trim()) }, canConfirm = reason.isNotBlank(), confirmText = S.confirm) {
        if (hint != null) Hint(hint)
        TextInput(S.reasonRequiredHint, reason, { reason = it }, singleLine = false)
    }
}

@Composable
fun Gap(h: Int = 4) = Spacer(Modifier.height(h.dp))

@Composable
fun BoxCenter(content: @Composable () -> Unit) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
