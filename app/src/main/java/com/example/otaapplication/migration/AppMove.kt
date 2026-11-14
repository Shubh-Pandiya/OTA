package com.example.otaapplication.migration

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.example.otaapplication.BuildConfig
import com.example.otaapplication.cloud.CatalogSyncWorker
import com.example.otaapplication.cloud.CloudSync
import com.example.otaapplication.reminders.Reminders
import com.example.otaapplication.update.AppUpdater
import com.example.otaapplication.update.PhoneReleases
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface MoveState {
    data object Idle : MoveState
    data object NeedsPermission : MoveState
    data object Syncing : MoveState
    data class Downloading(val progress: Float?) : MoveState
    data object Packing : MoveState
    /** Android's installer is asking the user to confirm the new app. */
    data object Installing : MoveState
    /** The new app is installed; it is being opened to take the data. */
    data class HandingOver(val intent: Intent) : MoveState
    data class Failed(val message: String) : MoveState
}

/**
 * Moves this phone from the old app (signed with a development key, which Android won't let a
 * release-key app update) to the new OTA Business app. Nothing is deleted here: the new app is
 * installed next to this one, receives an encrypted copy of all data, and only then is this app
 * retired. The same file also lands in Downloads, restorable with its recovery code.
 */
class AppMove private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<MoveState>(MoveState.Idle)
    val state: StateFlow<MoveState> = _state.asStateFlow()
    private val running = Mutex()
    @Volatile private var installResult: CompletableDeferred<Pair<Int, String?>>? = null

    private val _moved = MutableStateFlow(prefs.getLong(KEY_MOVED_OUT_AT, 0) > 0)
    /** True once the new app holds this phone's data. */
    val moved: StateFlow<Boolean> = _moved.asStateFlow()
    val recoveryCode: String? get() = prefs.getString(KEY_CODE, null)
    val backupName: String? get() = prefs.getString(KEY_BACKUP_NAME, null)
    val targetInstalled: Boolean get() = verifiedTarget() == null

    fun dismiss() {
        if (_state.value is MoveState.Failed || _state.value is MoveState.NeedsPermission) _state.value = MoveState.Idle
    }

    fun permissionIntent(): Intent = AppUpdater.get(context).permissionIntent()

    /**
     * Sync → download and check the new app → pack the data → install → hand over. Changes still
     * waiting to upload stop the move; packing holds the sync lock so no sync writes meanwhile.
     */
    suspend fun start(cloud: CloudSync) {
        if (!running.tryLock()) return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.value = MoveState.NeedsPermission
                return
            }
            _state.value = MoveState.Syncing
            withTimeoutOrNull(120_000) { cloud.syncNow() }
            val pending = cloud.status.value.pending
            if (pending > 0) {
                fail("$pending change${if (pending == 1) "" else "s"} on this phone haven't uploaded yet. Connect to the internet and try again. Nothing was moved.")
                return
            }
            val updater = AppUpdater.get(context)
            val apk = if (targetInstalled) null else {
                _state.value = MoveState.Downloading(null)
                withContext(Dispatchers.IO) {
                    val release = PhoneReleases.parse(updater.get(feed()), PhoneReleases.PREFIX).maxByOrNull { it.version }
                        ?: throw IOException("The new app isn't published yet. Try again later.")
                    updater.download(release) { _state.value = MoveState.Downloading(it) }.also(::checkTarget)
                }
            }
            _state.value = MoveState.Packing
            val code = recoveryCode ?: MoveCrypto.newCode().also { c -> prefs.edit(commit = true) { putString(KEY_CODE, c) } }
            val file = cloud.whileSyncPaused { withContext(Dispatchers.IO) { pack(code) } }
            if (apk != null) {
                _state.value = MoveState.Installing
                val (status, message) = install(apk)
                if (status != PackageInstaller.STATUS_SUCCESS) {
                    fail(
                        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The new app wasn't installed. Tap Move again when ready."
                        else "Android could not install the new app" + (message?.let { ": $it" } ?: ".")
                    )
                    return
                }
            }
            verifiedTarget()?.let { fail(it); return }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val intent = Intent()
                .setComponent(ComponentName(BuildConfig.MOVE_TARGET_PACKAGE, RESTORE_ACTIVITY))
                .setDataAndType(uri, MIME)
                .putExtra(EXTRA_CODE, code)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            _state.value = MoveState.HandingOver(intent)
        } catch (e: Exception) {
            fail(AppUpdater.get(context).friendly(e))
        } finally {
            running.unlock()
        }
    }

    /** The new app answered: true once it has taken all the data. */
    fun handedOver(ok: Boolean) {
        if (!ok) {
            fail("The new app didn't take the data. Tap Move to try again; nothing was lost.")
            return
        }
        retire()
        _state.value = MoveState.Idle
    }

    /** This app stops syncing and ringing reminders; the new app does both from now on. */
    private fun retire() {
        prefs.edit(commit = true) { putLong(KEY_MOVED_OUT_AT, System.currentTimeMillis()) }
        _moved.value = true
        CatalogSyncWorker.cancel(context)
        runCatching { Reminders.cancelAll(context) }
    }

    fun openNewApp(): Intent? = context.packageManager.getLaunchIntentForPackage(BuildConfig.MOVE_TARGET_PACKAGE)

    fun uninstallSelfIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))

    private fun fail(message: String) {
        _state.value = MoveState.Failed(message)
    }

    private fun feed() = BuildConfig.UPDATE_FEED.ifBlank { PhoneReleases.FEED }

    /** Null when [BuildConfig.MOVE_TARGET_PACKAGE] is installed and signed with the release key. */
    private fun verifiedTarget(): String? {
        val info = try {
            context.packageManager.getPackageInfo(BuildConfig.MOVE_TARGET_PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) {
            return "The new app is not installed."
        }
        val pins = PhoneReleases.pins(BuildConfig.MOVE_TARGET_CERT)
        return if (AppUpdater.certFingerprints(info).any { it in pins }) null
        else "An app called ${BuildConfig.MOVE_TARGET_PACKAGE} is installed but is not signed with the OTA Business key. Uninstall it and try again."
    }

    private fun checkTarget(apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: throw IOException("The downloaded file is not a valid app")
        if (info.packageName != BuildConfig.MOVE_TARGET_PACKAGE) throw IOException("The download is a different app (${info.packageName})")
        val pins = PhoneReleases.pins(BuildConfig.MOVE_TARGET_CERT)
        if (AppUpdater.certFingerprints(info).none { it in pins }) throw IOException("The new app is not signed with the OTA Business release key. Nothing was changed.")
    }

    /** Writes the encrypted move file into the cache (for the hand-over) and copies it to Downloads. */
    private fun pack(code: String): File {
        val dir = File(context.cacheDir, "move").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "OTA-move.otamove")
        file.outputStream().buffered(1 shl 16).use { MoveBackup.export(context, it, code, BuildConfig.VERSION_NAME) }
        val name = "OTA-move-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".otamove"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OTA Business")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not save the move file to Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } } ?: throw IOException("Could not save the move file to Downloads")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        prefs.edit(commit = true) { putString(KEY_BACKUP_NAME, "Download/OTA Business/$name") }
        return file
    }

    private suspend fun install(apk: File): Pair<Int, String?> {
        val result = CompletableDeferred<Pair<Int, String?>>().also { installResult = it }
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(BuildConfig.MOVE_TARGET_PACKAGE)
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
                    val intent = Intent(context, MoveInstallReceiver::class.java).setAction(MoveInstallReceiver.ACTION)
                    val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(id) }
                throw e
            }
        }
        return result.await()
    }

    internal fun installFinished(status: Int, message: String?) {
        installResult?.complete(status to message)
    }

    companion object {
        const val PREFS = "ota_move"
        const val KEY_MOVED_IN_AT = "moved_in_at"
        const val KEY_MOVED_FROM = "moved_from_version"
        private const val KEY_MOVED_OUT_AT = "moved_out_at"
        private const val KEY_CODE = "recovery_code"
        private const val KEY_BACKUP_NAME = "backup_name"
        const val EXTRA_CODE = "com.ota.business.MOVE_CODE"
        const val MIME = "application/octet-stream"
        const val RESTORE_ACTIVITY = "com.example.otaapplication.migration.RestoreActivity"

        fun isMovedOut(context: Context): Boolean =
            BuildConfig.IS_BRIDGE && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_MOVED_OUT_AT, 0) > 0

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: AppMove? = null

        fun get(context: Context): AppMove =
            instance ?: synchronized(this) { instance ?: AppMove(context.applicationContext).also { instance = it } }
    }
}

/** Android's installer reports on the new app here: first asking the user to confirm, then the result. */
class MoveInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        AppMove.get(context).installFinished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "com.example.otaapplication.MOVE_INSTALL_STATUS"
    }
}
