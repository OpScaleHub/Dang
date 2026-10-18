package com.github.opscalehub.dong.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The landing page's release.json, written by scripts/release.sh. */
@Serializable
data class ReleaseInfo(
    val version: String,
    val versionCode: Int = 0,
    val minSdk: Int = 0,
    val sizeBytes: Long = 0,
    val sha256: String = "",
    val apk: String = "",
    /** Download URL fixed to this version, so a newer release can't swap the file mid-update. */
    val apkPinned: String = "",
    val notes: String = "",
)

/** Pure decisions, kept apart from Android for tests. */
object UpdatePolicy {
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ReleaseInfo = json.decodeFromString(ReleaseInfo.serializer(), text)

    /** An update is offered only when it is newer, installable here, and verifiable. */
    fun offer(info: ReleaseInfo, currentCode: Int, sdk: Int, skippedCode: Int, manual: Boolean): Boolean =
        info.versionCode > currentCode &&
            info.minSdk <= sdk &&
            info.sha256.length == 64 &&
            info.downloadUrl().startsWith("http") &&
            (manual || info.versionCode != skippedCode)

    fun dueForAutoCheck(lastCheck: Long, now: Long) = now - lastCheck !in 0 until CHECK_INTERVAL_MS

    fun ReleaseInfo.downloadUrl() = apkPinned.ifBlank { apk }

    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val info: ReleaseInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val info: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val reason: Reason, val info: ReleaseInfo?) : UpdateState

    enum class Reason { NETWORK, VERIFY, SIGNATURE, STORAGE, INSTALL }
}

/**
 * Checks the landing page for a newer release, downloads and verifies the APK
 * and hands it to the system installer, which always asks the user to confirm.
 * Android itself refuses an update signed with a different key.
 * Nothing about the user or their buildings is sent: it is a plain GET of a public file.
 */
class Updater(
    context: Context,
    private val currentCode: Int,
    private val url: String,
    private val autoCheckAllowed: Boolean,
    private val fetch: (String) -> String = ::httpGet,
    private val open: (String) -> HttpURLConnection = ::connect,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)
    private val dir = File(app.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Hidden for the rest of this run after «بعداً». */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) { prefs.edit().putBoolean(KEY_AUTO, v).apply() }

    init {
        // Leftovers from an earlier update (installed or abandoned) are useless now.
        dir.listFiles()?.forEach { it.delete() }
    }

    fun onAppStart(now: Long = System.currentTimeMillis()) {
        if (autoCheckAllowed && autoCheck && UpdatePolicy.dueForAutoCheck(prefs.getLong(KEY_LAST, 0), now)) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        _dismissed.value = false
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val info = UpdatePolicy.parse(fetch(url))
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                if (UpdatePolicy.offer(info, currentCode, Build.VERSION.SDK_INT, prefs.getInt(KEY_SKIPPED, 0), manual)) UpdateState.Available(info)
                else if (manual) UpdateState.UpToDate else UpdateState.Idle
            } catch (e: Exception) {
                Log.i("Dong", "update check failed: ${e.javaClass.simpleName}")
                if (manual) UpdateState.Failed(UpdateState.Reason.NETWORK, null) else UpdateState.Idle
            }
        }
    }

    fun later() { _dismissed.value = true }

    fun skip(info: ReleaseInfo) {
        prefs.edit().putInt(KEY_SKIPPED, info.versionCode).apply()
        _state.value = UpdateState.Idle
    }

    fun download(info: ReleaseInfo) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, null)
            _state.value = try {
                UpdateState.ReadyToInstall(info, fetchApk(info))
            } catch (e: VerifyException) {
                UpdateState.Failed(UpdateState.Reason.VERIFY, info)
            } catch (e: kotlinx.coroutines.CancellationException) {
                UpdateState.Available(info)
            } catch (e: Exception) {
                Log.i("Dong", "update download failed: ${e.javaClass.simpleName}")
                UpdateState.Failed(UpdateState.Reason.NETWORK, info)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        (_state.value as? UpdateState.Downloading)?.let { _state.value = UpdateState.Available(it.info) }
    }

    private class VerifyException : Exception()

    private suspend fun fetchApk(info: ReleaseInfo): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val part = File(dir, "dong-${info.versionCode}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(with(UpdatePolicy) { info.downloadUrl() })
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input -> part.outputStream().use { out -> copy(input, out, digest, total, info) } }
        } finally {
            conn.disconnect()
        }
        if (UpdatePolicy.hex(digest.digest()) != info.sha256.lowercase()) { part.delete(); throw VerifyException() }
        // The file must be this app, and newer than what is installed.
        @Suppress("DEPRECATION")
        val pkg = app.packageManager.getPackageArchiveInfo(part.path, 0)
        val code = pkg?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode } ?: -1
        if (pkg?.packageName != app.packageName || code <= currentCode) { part.delete(); throw VerifyException() }
        File(dir, "dong-${info.versionCode}.apk").also { part.renameTo(it) }
    }

    private suspend fun copy(input: InputStream, out: java.io.OutputStream, digest: MessageDigest, total: Long, info: ReleaseInfo) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var shown = -1
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
            if (total > 0) {
                if (done > total + MAX_SLACK) throw VerifyException()
                val pct = (done * 100 / total).toInt()
                if (pct != shown) { shown = pct; _state.value = UpdateState.Downloading(info, (done.toFloat() / total).coerceAtMost(1f)) }
            }
        }
    }

    /** Hands the verified APK to the system installer; the result arrives in [UpdateReceiver]. */
    fun install(ready: UpdateState.ReadyToInstall) {
        _state.value = UpdateState.Installing(ready.info)
        scope.launch {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(ready.file.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("dong.apk", 0, ready.file.length()).use { out -> ready.file.inputStream().use { it.copyTo(out); s.fsync(out) } }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                    s.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                Log.i("Dong", "update install failed: ${e.javaClass.simpleName}")
                _state.value = UpdateState.Failed(UpdateState.Reason.STORAGE, ready.info)
            }
        }
    }

    /** Called by [UpdateReceiver] with the installer's final status. */
    internal fun onInstallResult(status: Int) {
        val info = (_state.value as? UpdateState.Installing)?.info
        val file = info?.let { File(dir, "dong-${it.versionCode}.apk") }
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            // The user said no on the system screen: offer the install again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (info != null && file?.exists() == true) UpdateState.ReadyToInstall(info, file) else UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateState.Failed(UpdateState.Reason.SIGNATURE, info)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed(UpdateState.Reason.STORAGE, info)
            else -> UpdateState.Failed(UpdateState.Reason.INSTALL, info)
        }
    }

    companion object {
        private const val KEY_AUTO = "auto"
        private const val KEY_LAST = "lastCheck"
        private const val KEY_SKIPPED = "skipped"
        private const val MAX_SLACK = 1L shl 20

        private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Cache-Control", "no-cache")
        }

        private fun httpGet(url: String): String {
            val c = connect(url)
            try {
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                // release.json is tiny; refuse anything that isn't.
                val bytes = c.inputStream.use { s -> s.readBytesLimited(64 * 1024) }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }

        private fun InputStream.readBytesLimited(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) error("too large")
            }
            return out.toByteArray()
        }
    }
}
