package com.example.otaapplication.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit
import com.example.otaapplication.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the update dialog shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    /** A check the user asked for is running. */
    data object Checking : UpdateState
    /** The user asked and this is the newest version. */
    data object UpToDate : UpdateState
    data class Available(val release: PhoneRelease) : UpdateState
    /** Android must first allow this app to install updates ("Install unknown apps"). */
    data class NeedsPermission(val release: PhoneRelease) : UpdateState
    /** Uploading this phone's unsynced changes before the update. */
    data class Syncing(val release: PhoneRelease) : UpdateState
    data class Downloading(val release: PhoneRelease, val progress: Float?) : UpdateState
    /** Handed to Android's installer, which asks the user to confirm. */
    data class Installing(val release: PhoneRelease, val syncNote: String?) : UpdateState
    data class Failed(val release: PhoneRelease?, val message: String) : UpdateState
}

/**
 * Phone updates from GitHub Releases. The APK is downloaded into the app's cache, checked against
 * the published size and SHA-256, and must be this app (same package, same signing key, a higher
 * versionCode) before Android's installer gets it. An update keeps all app data: the database
 * (including changes not yet synced), to-dos, cheque images and settings.
 */
class AppUpdater private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val lock = Mutex()
    private val feed = BuildConfig.UPDATE_FEED.ifBlank { PhoneReleases.FEED }
    private val dir get() = File(context.cacheDir, "updates")

    val currentCode: Long get() = installedInfo().longVersionCode
    val currentName: String get() = installedInfo().versionName ?: BuildConfig.VERSION_NAME

    /**
     * Looks for a newer release. An automatic check stays quiet when there is none, fails silently
     * (no network is normal) and doesn't ask again for a version the user put off in the last day.
     */
    suspend fun check(manual: Boolean) {
        // GitHub allows 60 unauthenticated calls an hour per network: automatic checks stay rare.
        if (!manual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < MIN_GAP_MS) return
        if (!lock.tryLock()) return
        try {
            if (!manual && _state.value !is UpdateState.Idle) return
            if (manual) _state.value = UpdateState.Checking
            val release = try {
                withContext(Dispatchers.IO) { PhoneReleases.newer(get(feed), currentCode) }
            } catch (e: Exception) {
                if (manual) _state.value = UpdateState.Failed(null, "Could not check for updates: ${friendly(e)}")
                return
            }
            prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
            _state.value = when {
                release == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                !manual && snoozed(release) -> UpdateState.Idle
                else -> UpdateState.Available(release)
            }
        } finally {
            lock.unlock()
        }
    }

    fun later() {
        (_state.value as? UpdateState.Available)?.let { prefs.edit { putString(KEY_LATER_VERSION, it.release.tag); putLong(KEY_LATER_AT, System.currentTimeMillis()) } }
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun snoozed(release: PhoneRelease) =
        prefs.getString(KEY_LATER_VERSION, null) == release.tag &&
            System.currentTimeMillis() - prefs.getLong(KEY_LATER_AT, 0) < SNOOZE_MS

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The system page where the user lets OTA Business install updates (once per phone). */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * "Update now": permission, then sync (unsynced changes stay safely in the database if that
     * fails, e.g. offline), then download, verify and install. [sync] returns a note when changes
     * are still waiting to upload.
     */
    suspend fun update(release: PhoneRelease, sync: suspend () -> String?) {
        if (!lock.tryLock()) return
        try {
            if (!canInstall()) {
                _state.value = UpdateState.NeedsPermission(release)
                return
            }
            _state.value = UpdateState.Syncing(release)
            val note = runCatching { sync() }.getOrNull()
            _state.value = UpdateState.Downloading(release, null)
            val apk = withContext(Dispatchers.IO) { download(release) }
            withContext(Dispatchers.IO) { checkPackage(apk) }
            _state.value = UpdateState.Installing(release, note)
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(release, friendly(e))
        } finally {
            lock.unlock()
        }
    }

    /** Called by [UpdateInstallReceiver] when Android's installer reports back. */
    internal fun installFinished(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(
                release,
                when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Android refused the update because it is signed differently from the app on this phone. Nothing was changed."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free space on the phone for the update."
                    else -> "Android could not install the update" + (message?.let { ": $it" } ?: ".")
                }
            )
        }
    }

    /** Deletes downloaded APKs that are installed now (or older). */
    fun cleanUp() {
        val current = currentCode
        dir.listFiles()?.forEach { f ->
            val v = PhoneVersion.parse(Regex("""(\d+\.\d+(?:\.\d+)?)""").find(f.name)?.value)
            if (f.name.endsWith(".part") || v == null || v.code <= current) f.delete()
        }
    }

    private fun download(release: PhoneRelease): File {
        val apk = release.apk
        val published = release.checksumsUrl?.let { url -> PhoneReleases.parseChecksums(get(url))[apk.name] }
        if (published != null && apk.sha256 != null && !published.equals(apk.sha256, ignoreCase = true)) {
            throw IOException("The release's checksums disagree, so the download can't be trusted")
        }
        val sha = PhoneReleases.expectedSha(apk, published) ?: throw IOException("The release has no checksum to check the download against")
        dir.mkdirs()
        val target = File(dir, safeName(apk.name))
        if (target.exists() && PhoneReleases.verify(target, apk.size, sha) == null) return target
        target.delete()
        val part = File(dir, target.name + ".part").apply { delete() }
        val conn = open(apk.url, readTimeoutMs = 60_000)
        try {
            if (conn.responseCode != 200) throw IOException("Download answered HTTP ${conn.responseCode}")
            val total = apk.size.takeIf { it > 0 } ?: conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) {
                                shown = pct
                                _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        PhoneReleases.verify(part, apk.size, sha)?.let { part.delete(); throw IOException(it) }
        if (!part.renameTo(target)) throw IOException("Could not save the update")
        return target
    }

    /** Only this app, signed with the same key, and newer than what is installed. */
    private fun checkPackage(apk: File) {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: throw IOException("The downloaded file is not a valid app")
        if (info.packageName != context.packageName) throw IOException("The download is a different app (${info.packageName})")
        val installed = installedInfo()
        if (info.longVersionCode <= installed.longVersionCode) {
            throw IOException("The download (${info.versionName}) is not newer than this app (${installed.versionName}); downgrades are refused")
        }
        val mine = signers(installed)
        val theirs = info.signingInfo?.signingCertificateHistory?.map { it.toByteArray().contentHashCode() }.orEmpty()
        if (mine.isEmpty() || theirs.isEmpty() || mine.none { it in theirs }) {
            throw IOException("The update is signed with a different key than the app on this phone, so Android would refuse it. Nothing was changed.")
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 1 shl 16)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION)
                val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun installedInfo(): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))

    private fun signers(info: PackageInfo): List<Int> {
        val s = info.signingInfo ?: return emptyList()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { it.toByteArray().contentHashCode() }
    }

    private fun get(url: String): String {
        val conn = open(url, readTimeoutMs = 20_000)
        try {
            if (conn.responseCode != 200) throw IOException("Update server answered HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OTA-Business-Android/${BuildConfig.VERSION_NAME}")
        }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "no internet connection"
        is java.net.SocketTimeoutException -> "the connection timed out"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val PREFS = "ota_updates"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_LATER_VERSION = "later_version"
        private const val KEY_LATER_AT = "later_at"
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L
        /** How often a running app looks for a new release. */
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        private const val MIN_GAP_MS = 30 * 60 * 1000L

        private fun safeName(name: String) = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: AppUpdater? = null

        fun get(context: Context): AppUpdater =
            instance ?: synchronized(this) { instance ?: AppUpdater(context.applicationContext).also { instance = it } }
    }
}
