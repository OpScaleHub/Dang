package com.github.opscalehub.dong.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.update.ReleaseInfo
import com.github.opscalehub.dong.update.UpdateState
import com.github.opscalehub.dong.update.UpdateState.Reason
import com.github.opscalehub.dong.update.Updater

private fun mb(bytes: Long) = Fmt.digits("%.1f".format(java.util.Locale.ROOT, bytes / 1048576.0)).replace('.', '٫')
private fun ver(info: ReleaseInfo) = Fmt.digits(info.version)

/**
 * The update offer. [inSettings] also shows the quiet states (checking, up to date) that only make
 * sense after the user asked; on the home screen only an actual offer or its progress appears.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateCard(updater: Updater, inSettings: Boolean = false) {
    val state by updater.state.collectAsState()
    val dismissed by updater.dismissed.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Set when the user was sent to allow installs; the install continues when they come back.
    var awaitingPermission by remember { mutableStateOf(false) }

    fun install(ready: UpdateState.ReadyToInstall) {
        if (context.packageManager.canRequestPackageInstalls()) updater.install(ready)
        else {
            awaitingPermission = true
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }
    }
    LaunchedEffect(awaitingPermission) {
        if (!awaitingPermission) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val ready = updater.state.value as? UpdateState.ReadyToInstall
            if (ready != null && context.packageManager.canRequestPackageInstalls()) { awaitingPermission = false; updater.install(ready) }
        }
    }

    if (!inSettings && (dismissed || state is UpdateState.Idle || state is UpdateState.Checking || state is UpdateState.UpToDate)) return
    if (inSettings && state is UpdateState.Idle) return

    val failed = state is UpdateState.Failed
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    )) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = state) {
                UpdateState.Idle -> {}
                UpdateState.Checking -> {
                    Text(S.updChecking, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                UpdateState.UpToDate -> Text(S.updUpToDate, style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> {
                    Text("${S.updAvailable} ${ver(s.info)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (s.info.sizeBytes > 0) Text("${S.updSize}: ${mb(s.info.sizeBytes)} ${S.megabytes}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { updater.download(s.info) }) { Text(S.updNow) }
                        if (!inSettings) OutlinedButton(onClick = { updater.later() }) { Text(S.updLater) }
                        TextButton(onClick = { updater.skip(s.info) }) { Text(S.updSkip) }
                        if (s.info.notes.startsWith("https://")) TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.info.notes))) }
                        }) { Text(S.updNotes) }
                    }
                }
                is UpdateState.Downloading -> {
                    val pct = s.progress?.let { Fmt.digits("${(it * 100).toInt()}") + "٪" } ?: ""
                    Text("${S.updDownloading} ${ver(s.info)} $pct", style = MaterialTheme.typography.bodyMedium)
                    if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { updater.cancel() }) { Text(S.cancel) }
                }
                is UpdateState.ReadyToInstall -> {
                    Text("${S.updReady} ${ver(s.info)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(S.updConfirmHint, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { install(s) }) { Text(S.updInstall) }
                }
                is UpdateState.Installing -> {
                    Text(S.updInstalling, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateState.Failed -> {
                    Text(when (s.reason) {
                        Reason.NETWORK -> S.updErrNetwork
                        Reason.VERIFY -> S.updErrVerify
                        Reason.SIGNATURE -> S.updErrSignature
                        Reason.STORAGE -> S.updErrStorage
                        Reason.INSTALL -> S.updErrInstall
                    }, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { s.info?.let { updater.download(it) } ?: updater.check(manual = true) }) { Text(S.updRetry) }
                        if (!inSettings) OutlinedButton(onClick = { updater.later() }) { Text(S.updLater) }
                    }
                }
            }
        }
    }
}
