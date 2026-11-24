package com.israk.ftptv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.israk.ftptv.BuildConfig
import com.israk.ftptv.FtpTvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the GitHub releases: check → show the notes → download → install, all inside the app.
 * TV always takes the armv7 APK (Mi TV Stick), phones the arm64 one (armv7 on old 32-bit phones).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/imisrakssr/FTP-TV/releases/latest"

    data class Release(val version: String, val code: Int, val notes: String, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        /** Android won't let the app install updates yet ("Unknown sources" is off for it — common on TV). */
        data class NeedsPermission(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** A newer release than the installed one: drives the dot on the Settings tab / About me. */
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available
    @Volatile private var lastCheck = 0L

    /** Automatic check: on app open and every few hours while it's open; silent when it fails. */
    fun checkIfStale(maxAgeMs: Long = 6 * 3600_000L) {
        if (System.currentTimeMillis() - lastCheck > maxAgeMs) check(quiet = true)
    }

    private val app get() = FtpTvApp.instance

    private fun abiSuffix(ctx: Context): String {
        if (FtpTvApp.isTv(ctx)) return "-armv7.apk"
        return if (Build.SUPPORTED_64_BIT_ABIS.any { it.startsWith("arm64") }) "-arm64.apk" else "-armv7.apk"
    }

    fun check(quiet: Boolean = false) {
        val before = _state.value
        if (before is State.Checking || before is State.Downloading || before is State.Installing) return
        lastCheck = System.currentTimeMillis()
        if (!quiet) _state.value = State.Checking
        app.appScope.launch(Dispatchers.IO) {
            val result = runCatching {
                // a build can be for phones only or TV only, so take the newest release that has this device's APK
                val req = Request.Builder().url(LATEST.substringBeforeLast("/latest") + "?per_page=15")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FTPTV/${BuildConfig.VERSION_NAME}")
                    .build()
                val body = app.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("GitHub HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                val suffix = abiSuffix(app)
                fun assetOf(o: JSONObject): JSONObject? {
                    val a = o.optJSONArray("assets") ?: return null
                    for (i in 0 until a.length()) { val x = a.optJSONObject(i) ?: continue; if (x.optString("name").endsWith(suffix)) return x }
                    return null
                }
                val arr = JSONArray(body)
                val releases = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                val o = releases.firstOrNull { assetOf(it) != null } ?: throw java.io.IOException("No release found")
                val tag = o.optString("tag_name").ifBlank { throw java.io.IOException("No release found") }
                if (compareVersions(tag.removePrefix("v"), BuildConfig.VERSION_NAME) <= 0) return@runCatching State.UpToDate
                val asset = assetOf(o)!!
                val notes = releases.takeWhile { r -> compareVersions(r.optString("tag_name").removePrefix("v"), BuildConfig.VERSION_NAME) > 0 }
                    .filter { assetOf(it) != null }
                    .joinToString("\n") { cleanNotes(it.optString("body")) }
                State.Available(
                    Release(
                        version = tag.removePrefix("v"), code = 0, notes = notes,
                        apkUrl = asset.optString("browser_download_url").ifBlank { throw java.io.IOException("No download link") },
                        size = asset.optLong("size"),
                    )
                )
            }.getOrElse { State.Failed(it.message ?: it.toString()) }
            when (result) {
                is State.Available -> _available.value = result.release
                State.UpToDate -> _available.value = null
                else -> Unit
            }
            // a silent check only changes what's shown when it found something
            if (!quiet || result is State.Available) _state.value = result
        }
    }

    /** Notes of the installed version's release (the "Release notes" button); null until loaded. */
    private val _installedNotes = MutableStateFlow<String?>(null)
    val installedNotes: StateFlow<String?> = _installedNotes
    private val _notesLoading = MutableStateFlow(false)
    val notesLoading: StateFlow<Boolean> = _notesLoading

    fun loadInstalledNotes() {
        if (_installedNotes.value != null || _notesLoading.value) return
        _notesLoading.value = true
        app.appScope.launch(Dispatchers.IO) {
            _installedNotes.value = runCatching {
                val url = LATEST.substringBeforeLast("/latest") + "/tags/v" + BuildConfig.VERSION_NAME
                val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FTPTV/${BuildConfig.VERSION_NAME}").build()
                val body = app.http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("GitHub HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                cleanNotes(JSONObject(body).optString("body")).ifBlank { "—" }
            }.getOrElse { "⚠ " + (it.message ?: it.toString()) }
            _notesLoading.value = false
        }
    }

    /** "2.0.3" vs "1.0.41" → positive when [a] is newer. */
    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /** The release body minus the "which APK" lines (the app picks the right one by itself). */
    private fun cleanNotes(body: String): String = body.lines()
        .filterNot { it.contains("APK", ignoreCase = false) && it.contains("→") }
        .joinToString("\n").replace("**", "").trim()

    /** Whether Android lets this app install APKs (Settings → Unknown sources / Install unknown apps). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()

    /**
     * Opens the screen where "Unknown sources" is turned on for this app. Android TV (Mi TV Stick) has no
     * per-app "Install unknown apps" page — asking for it is what gave "you don't have an app that can do this" —
     * so fall back to the security page, then to Settings itself.
     */
    fun openInstallPermission(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                add(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}")))
            add(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            add(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        for (i in candidates) {
            if (i.resolveActivity(pm) == null) continue
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    fun downloadAndInstall(release: Release) {
        if (_state.value is State.Downloading) return
        if (!canInstall()) { _state.value = State.NeedsPermission(release); return }
        _state.value = State.Downloading(release, 0f)
        app.appScope.launch(Dispatchers.IO) {
            runCatching {
                val file = File(app.cacheDir, "update.apk")
                val client = app.http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val req = Request.Builder().url(release.apkUrl).header("User-Agent", "FTPTV/${BuildConfig.VERSION_NAME}").build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("Download HTTP ${r.code}")
                    val b = r.body ?: throw java.io.IOException("Empty download")
                    val total = b.contentLength().takeIf { it > 0 } ?: release.size
                    b.byteStream().use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; _state.value = State.Downloading(release, pct / 100f) }
                                }
                            }
                        }
                    }
                }
                _state.value = State.Installing(release)
                install(file)
            }.onFailure { _state.value = State.Failed(it.message ?: it.toString(), release) }
        }
    }

    /** Hands the APK to Android's package installer; Android shows its own "Update this app?" screen. */
    private fun install(file: File) {
        val ctx = app
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(ctx, InstallReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    internal fun onConfirmMissing() {
        val rel = (_state.value as? State.Installing)?.release
        _state.value = if (rel != null && !canInstall()) State.NeedsPermission(rel)
        else State.Failed("This device couldn't show the install screen", rel)
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val rel = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit // the confirm screen is being shown
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle // the app restarts as the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = rel?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed(message ?: "Install failed ($status)", rel)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = confirm != null && confirm.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(confirm) }.isSuccess
            if (!ok) {
                // no confirm screen on this device: most likely "Unknown sources" is off for the app
                Updater.onConfirmMissing()
                return
            }
        }
        Updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
