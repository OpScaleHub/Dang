package com.github.opscalehub.dong.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.github.opscalehub.dong.domain.period.Issue
import com.github.opscalehub.dong.domain.period.IssueCode
import com.github.opscalehub.dong.domain.period.Severity
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.ui.S

private val readingCodes = setOf(IssueCode.MISSING_CLOSING_READING, IssueCode.MISSING_OPENING_READING, IssueCode.MISSING_READING)

/**
 * Month-review problems in plain words: the same problem for many meters is one line («برای ۱۰
 * کنتور…») instead of ten, and reading problems come with a button that goes to the readings screen.
 */
@Composable
fun IssueList(issues: List<Issue>, names: Fa.Names, onReadings: (() -> Unit)? = null) {
    val grouped = issues.filter { it.code in readingCodes }.groupBy { it.code }
    val rest = issues.filter { it.code !in readingCodes }.sortedBy { it.severity }
    grouped.forEach { (code, list) ->
        val text = if (list.size == 1) Fa.issue(list.single(), names) else {
            val meters = list.map { names.meter(it.meterId) }
            val shown = meters.take(4).joinToString("، ") + if (meters.size > 4) " ${S.andMore.format(Fmt.number((meters.size - 4).toLong()))}" else ""
            when (code) {
                IssueCode.MISSING_CLOSING_READING -> S.groupMissingClosing
                IssueCode.MISSING_OPENING_READING -> S.groupMissingOpening
                else -> S.groupMissingAny
            }.format(Fmt.number(list.size.toLong())) + " " + shown
        }
        Line(Severity.ERROR, text)
    }
    if (grouped.isNotEmpty() && onReadings != null) OutlinedButton(onClick = onReadings) { Text(S.readingsBatch) }
    rest.forEach { Line(it.severity, Fa.issue(it, names)) }
}

@Composable
private fun Line(severity: Severity, text: String) = Text(
    if (severity == Severity.INFO) text else "${Fa.severity(severity)}: $text",
    style = MaterialTheme.typography.bodyMedium,
    color = when (severity) {
        Severity.ERROR -> MaterialTheme.colorScheme.error
        Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    },
)
