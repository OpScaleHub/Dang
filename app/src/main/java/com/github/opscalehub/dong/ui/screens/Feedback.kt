package com.github.opscalehub.dong.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.github.opscalehub.dong.BuildConfig
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.share.BillShare
import com.github.opscalehub.dong.share.ImageDoc
import com.github.opscalehub.dong.ui.AppViewModel
import com.github.opscalehub.dong.ui.Hint
import com.github.opscalehub.dong.ui.S
import com.github.opscalehub.dong.ui.ScreenList
import com.github.opscalehub.dong.ui.SectionCard
import com.github.opscalehub.dong.ui.SwitchRow
import com.github.opscalehub.dong.ui.TextInput

/** Device and app context appended to every feedback message; no building data. */
private fun context(vm: AppViewModel): String {
    val advanced = vm.ui.advanced.value
    val units = vm.units.value.size
    return listOf(
        "${S.fbApp}: ${Fmt.digits(BuildConfig.VERSION_NAME)} (${Fmt.digits(BuildConfig.VERSION_CODE.toString())})",
        "${S.fbAndroid}: ${Fmt.digits(Build.VERSION.RELEASE)}، ${S.fbPhone}: ${Build.MANUFACTURER} ${Build.MODEL}",
        "${S.fbMode}: ${if (advanced) S.advancedMode else S.simpleMode}، ${S.fbTextSize}: ${Fmt.digits("${(vm.ui.textScale.value * 100).toInt()}")}٪",
        "${S.fbUnits}: ${Fmt.number(units.toLong())}",
    ).joinToString("\n")
}

private fun send(context: Context, text: String, shot: Bitmap?) {
    if (shot != null) ImageDoc.share(context, shot, "feedback", text) else BillShare.shareText(context, text)
}

/**
 * «نظر یا مشکل»: a few guided questions plus, when opened from the top bar, a picture of the
 * screen the person was on. Sent through the share sheet to whoever handed out the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(vm: AppViewModel, nav: NavController) {
    val ctx = LocalContext.current
    val shot = vm.feedbackShot
    val from = vm.feedbackFrom
    val kinds = listOf(S.fbProblem, S.fbConfused, S.fbIdea, S.fbGood)
    var kind by rememberSaveable { mutableStateOf(kinds[0]) }
    var doing by rememberSaveable { mutableStateOf("") }
    var happened by rememberSaveable { mutableStateOf("") }
    var withShot by rememberSaveable { mutableStateOf(shot != null) }

    ScreenList {
        item { SectionCard { Hint(S.fbIntro) } }
        item {
            SectionCard(S.fbKind) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k, style = MaterialTheme.typography.bodyLarge) }) }
                }
                TextInput(S.fbDoing, doing, { doing = it }, singleLine = false)
                TextInput(S.fbHappened, happened, { happened = it }, singleLine = false)
            }
        }
        if (shot != null) item {
            SectionCard(S.fbShot) {
                SwitchRow(S.fbAttachShot, withShot, { withShot = it }, supporting = S.fbShotWarn)
                if (withShot) Image(shot.asImageBitmap(), S.fbShot, Modifier.fillMaxWidth().heightIn(max = 360.dp))
            }
        }
        item { SectionCard { Hint(S.fbVoiceHint) } }
        item {
            Button(onClick = {
                val text = buildString {
                    appendLine(S.fbTitle)
                    appendLine("${S.fbKind}: $kind")
                    from?.let { appendLine("${S.fbScreen}: $it") }
                    if (doing.isNotBlank()) appendLine("${S.fbDoing} ${doing.trim()}")
                    if (happened.isNotBlank()) appendLine("${S.fbHappened} ${happened.trim()}")
                    appendLine("—")
                    append(context(vm))
                }
                try {
                    send(ctx, text, if (withShot) shot else null)
                    vm.feedbackShot = null
                    nav.popBackStack()
                } catch (e: Exception) {
                    vm.message(S.unexpectedError)
                }
            }, Modifier.fillMaxWidth(), enabled = doing.isNotBlank() || happened.isNotBlank()) { Text(S.share) }
        }
    }
}

/** The 10 SUS questions (docs/10), answered 1–5; sends answers and the 0–100 score. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SurveyScreen(vm: AppViewModel, nav: NavController) {
    val ctx = LocalContext.current
    val answers = remember { mutableStateListOf<Int?>().apply { repeat(S.susQuestions.size) { add(null) } } }
    var comment by rememberSaveable { mutableStateOf("") }
    val done = answers.all { it != null }

    ScreenList {
        item { SectionCard { Hint(S.susIntro) } }
        items(S.susQuestions.size) { i ->
            SectionCard {
                Text("${Fmt.number((i + 1).toLong())}. ${S.susQuestions[i]}", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { v ->
                        FilterChip(selected = answers[i] == v, onClick = { answers[i] = v }, label = { Text(S.susScale[v - 1]) })
                    }
                }
            }
        }
        item { SectionCard { TextInput(S.susComment, comment, { comment = it }, singleLine = false) } }
        item {
            if (!done) Hint(S.susAnswerAll)
            Button(onClick = {
                val a = answers.map { it!! }
                // Odd items score − 1, even items 5 − score; sum × 2.5 → 0..100.
                val score = a.mapIndexed { i, v -> if (i % 2 == 0) v - 1 else 5 - v }.sum() * 2.5
                val text = buildString {
                    appendLine(S.susTitle)
                    appendLine("${S.susScore}: ${Fmt.digits("%.1f".format(java.util.Locale.ROOT, score)).replace('.', '٫')} ${S.of} ۱۰۰")
                    appendLine("${S.susAnswers}: " + a.joinToString(" ") { Fmt.digits(it.toString()) })
                    if (comment.isNotBlank()) appendLine("${S.susComment}: ${comment.trim()}")
                    appendLine("—")
                    append(context(vm))
                }
                try {
                    BillShare.shareText(ctx, text)
                    vm.ui.closeSurveyOffer()
                    nav.popBackStack()
                } catch (e: Exception) {
                    vm.message(S.unexpectedError)
                }
            }, Modifier.fillMaxWidth(), enabled = done) { Text(S.share) }
        }
    }
}
