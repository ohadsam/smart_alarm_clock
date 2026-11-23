package com.smartring.app.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.smartring.app.BuildConfig
import com.smartring.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** What the update box shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val version: String, val notes: String) : UpdateState
    /** [pct] is -1 when the size is unknown. */
    data class Downloading(val pct: Int) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState
    /** Android is asking the person to confirm the install. */
    data object Confirm : UpdateState
    data object Done : UpdateState
    /** The "install unknown apps" permission is off for this app; the person has to allow it once. */
    data object NeedPermission : UpdateState
    data class Error(val reason: Reason) : UpdateState
}

enum class Reason { OFFLINE, CHECK_FAILED, NO_ASSET, HTTP, SIZE, CHECKSUM, INVALID_APK, WRONG_PACKAGE, NOT_NEWER, SIGNATURE, INSTALL, FAILED }

/**
 * In-app update, the way StepUp does it: look at the project's latest GitHub release, download the APK,
 * check it, and hand it to Android's installer as an **update** of the installed app (the data stays).
 *
 * ## What is checked, and why in this order
 *
 *  1. The address must be one [UpdateMath.validUrl] accepts — this project's own release assets, nothing else.
 *  2. The published SHA-256 is fetched *first*: no checksum, no download.
 *  3. The download is capped in size and hashed as it arrives; a mismatch is refused. (The checksum comes
 *     from the same place as the file, so this guards against a damaged or truncated download, not against
 *     a compromised release — that is the next check's job.)
 *  4. The APK must be this app (same package), a **higher** version code, and signed by **the same key** as
 *     the installed one. That is what Android itself enforces for an update, checked here first so a refusal
 *     comes with a reason instead of a generic installer error.
 *
 * Nothing is sent about the person: the only requests are one GET for the release description and one for the
 * APK and its checksum, and only when the person asks.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    private class Fail(val reason: Reason) : Exception(reason.name)

    private val debugBuild get() = BuildConfig.DEBUG

    /** The address of the newest APK for *this* build type, for a manual download. */
    val manualUrl: String get() = UpdateMath.latestUrl(debugBuild)

    fun reset() { if (job?.isActive != true) _state.value = UpdateState.Idle }

    // ── Checking ──────────────────────────────────────────────────────────

    fun check() {
        if (job?.isActive == true) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val json = JSONObject(readText(UpdateMath.RELEASE_API, 512 * 1024))
                val version = UpdateMath.versionFromTitle(json.optString("name")) ?: throw Fail(Reason.CHECK_FAILED)
                val wanted = UpdateMath.assetName(debugBuild)
                val assets = json.optJSONArray("assets")
                val hasAsset = assets != null && (0 until assets.length()).any { assets.optJSONObject(it)?.optString("name") == wanted }
                when {
                    UpdateMath.compareVersions(BuildConfig.VERSION_NAME, version) >= 0 -> UpdateState.UpToDate(version)
                    !hasAsset -> throw Fail(Reason.NO_ASSET)
                    else -> UpdateState.Available(version, UpdateMath.cleanNotes(json.optString("body")))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Fail) {
                UpdateState.Error(e.reason)
            } catch (e: JSONException) {
                UpdateState.Error(Reason.CHECK_FAILED)
            } catch (e: java.io.IOException) {
                UpdateState.Error(Reason.OFFLINE)
            } catch (e: Exception) {
                UpdateState.Error(Reason.CHECK_FAILED)
            }
        }
    }

    // ── Downloading and installing ────────────────────────────────────────

    fun install() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                perform(UpdateMath.latestUrl(debugBuild))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Fail) {
                appLogger.log("Update", "העדכון נכשל: ${e.reason}")
                _state.value = UpdateState.Error(e.reason)
            } catch (e: java.io.IOException) {
                appLogger.log("Update", "העדכון נכשל: ${e.javaClass.simpleName}")
                _state.value = UpdateState.Error(Reason.OFFLINE)
            } catch (e: Exception) {
                appLogger.log("Update", "העדכון נכשל: ${e.javaClass.simpleName}")
                _state.value = UpdateState.Error(Reason.FAILED)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.toSet() ?: emptySet()

    @SuppressLint("InlinedApi")   // FLAG_MUTABLE is ignored below API 31, where PendingIntents are mutable anyway
    private suspend fun perform(url: String) {
        if (!UpdateMath.validUrl(url)) throw Fail(Reason.FAILED)
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) { _state.value = UpdateState.NeedPermission; return }

        val want = UpdateMath.parseSha256(readText(UpdateMath.checksumUrl(url), 2048)) ?: throw Fail(Reason.CHECKSUM)
        _state.value = UpdateState.Downloading(0)
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(dir, "smartring-update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        val c = open(url)
        try {
            if (c.responseCode != 200) throw Fail(Reason.HTTP)
            val total = c.contentLengthLong
            if (total > UpdateMath.MAX_BYTES) throw Fail(Reason.SIZE)
            c.inputStream.use { input ->
                apk.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > UpdateMath.MAX_BYTES) throw Fail(Reason.SIZE)
                        md.update(buf, 0, n); out.write(buf, 0, n)
                        val p = UpdateMath.percent(done, total)
                        if (p != last && (p < 0 || p % 4 == 0)) { last = p; _state.value = UpdateState.Downloading(p) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }

        _state.value = UpdateState.Verifying
        if (!UpdateMath.toHex(md.digest()).equals(want, ignoreCase = true)) throw Fail(Reason.CHECKSUM)
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw Fail(Reason.INVALID_APK)
        if (info.packageName != context.packageName) throw Fail(Reason.WRONG_PACKAGE)
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(info) <= PackageInfoCompat.getLongVersionCode(mine)) throw Fail(Reason.NOT_NEWER)
        if (signers(info).isEmpty() || signers(info) != signers(mine)) throw Fail(Reason.SIGNATURE)

        _state.value = UpdateState.Installing
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("smartring.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) }
            }
            // Explicit, our own receiver. It has to be MUTABLE: the installer fills in the status extras.
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallReceiver::class.java).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    // ── From the installer ────────────────────────────────────────────────

    internal fun onInstallerStatus(status: Int, confirmation: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                _state.value = UpdateState.Confirm
                confirmation?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Done
            else -> {
                appLogger.log("Update", "המתקין סירב (קוד $status)")
                _state.value = UpdateState.Error(Reason.INSTALL)
            }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "SmartRing-updater")
        setRequestProperty("Accept", "application/vnd.github+json, */*")
    }

    /** A small text document, at most [max] bytes (the rest is ignored). */
    private fun readText(url: String, max: Int): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw Fail(if (url.endsWith(".sha256")) Reason.CHECKSUM else Reason.CHECK_FAILED)
            val buf = ByteArray(max)
            var n = 0
            c.inputStream.use { s -> while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r < 0) break; n += r } }
            return String(buf, 0, n, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }
}

/** The installer's answer: asks for the person's confirmation when Android wants it, and says how it ended. */
@AndroidEntryPoint
class InstallReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: UpdateManager

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val ask: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                           else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        manager.onInstallerStatus(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1), ask)
    }
}
