package com.example.otaapplication.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.edit
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.PartyType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class CloudStatus(
    val configured: Boolean,
    val email: String? = null,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** The last attempt failed only because there was no network; edits upload when it returns. */
    val offline: Boolean = false,
    val pending: Int = 0
) {
    val signedIn: Boolean get() = email != null
}

/** App-wide cloud state: staff sign-in and the buyer/supplier catalog sync with Supabase. */
class CloudSync private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val config = SupabaseConfig.fromBuildConfig()
    private val repository = CatalogRepository(OtaDatabase.get(context))
    private val auth = SupabaseAuth(config, SessionStore(prefs))
    private val cursors = PrefsSyncCursors(prefs)
    private val engine = CatalogSync(repository, PostgrestCatalogApi(config, auth), cursors)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private var scheduled: Job? = null
    @Volatile private var lastAttemptAt = 0L

    private val _status = MutableStateFlow(
        CloudStatus(
            configured = config.isConfigured,
            email = auth.session.value?.email,
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0).takeIf { it > 0 }
        )
    )
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _catalogChanges = MutableStateFlow(0)
    /** Increases whenever a sync changed the local catalog, so open screens can reload. */
    val catalogChanges: StateFlow<Int> = _catalogChanges.asStateFlow()

    init {
        scope.launch { refreshPending() }
        // Retry as soon as the phone is back online after an offline failure.
        context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val s = _status.value
                    if (s.offline || s.pending > 0) requestSync(delayMillis = 2_000)
                }
            }
        )
    }

    suspend fun signIn(email: String, password: String) {
        check(config.isConfigured) { "Cloud is not set up in this build" }
        val session = auth.signIn(email, password)
        _status.update { it.copy(email = session.email, lastError = null) }
        requestSync()
    }

    suspend fun signOut() {
        scheduled?.cancel()
        syncLock.withLock {
            auth.signOut()
            // Next sign-in pulls the whole catalog again; local rows and pending edits are kept.
            PartyType.entries.forEach { cursors.set(it, null) }
            prefs.edit { remove(KEY_LAST_SYNC) }
        }
        _status.update { it.copy(email = null, lastSyncAt = null, lastError = null, offline = false) }
    }

    /** Schedules a sync; repeated requests within [delayMillis] collapse into one. */
    fun requestSync(delayMillis: Long = 0) {
        refreshPendingAsync()
        if (!config.isConfigured || auth.session.value == null) return
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(delayMillis)
            // Not a child of this job: a newer request must not cancel a sync already running.
            scope.launch { syncNow() }
        }
    }

    /** Called when the app comes to the foreground; syncs at most once a minute. */
    fun onAppForeground() {
        if (System.currentTimeMillis() - lastAttemptAt > FOREGROUND_INTERVAL_MS) requestSync()
    }

    suspend fun syncNow() {
        if (!config.isConfigured || auth.session.value == null) return
        syncLock.withLock {
            lastAttemptAt = System.currentTimeMillis()
            _status.update { it.copy(syncing = true) }
            try {
                val result = engine.syncAll()
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_LAST_SYNC, now) }
                _status.update { it.copy(lastSyncAt = now, lastError = null, offline = false) }
                if (result.pulledChanges > 0) _catalogChanges.update { it + 1 }
            } catch (e: CloudException) {
                _status.update {
                    it.copy(
                        lastError = e.message,
                        offline = false,
                        email = if (e.authFailed && auth.session.value == null) null else it.email
                    )
                }
            } catch (e: Exception) {
                _status.update { it.copy(lastError = friendlyError(e), offline = isOffline(e)) }
            } finally {
                _status.update { it.copy(syncing = false, pending = repository.pendingCount()) }
            }
        }
    }

    private fun refreshPendingAsync() = scope.launch { refreshPending() }

    private fun refreshPending() {
        val pending = repository.pendingCount()
        _status.update { it.copy(pending = pending) }
    }

    companion object {
        private const val PREFS = "ota_cloud"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val FOREGROUND_INTERVAL_MS = 60_000L

        @Volatile private var instance: CloudSync? = null

        fun get(context: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: CloudSync(context.applicationContext).also { instance = it }
        }

        private fun isOffline(e: Throwable) =
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException

        fun friendlyError(e: Throwable): String = when (e) {
            is CloudException -> e.message ?: "Cloud error"
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> "No internet connection"
            is SocketTimeoutException -> "Connection timed out"
            is SSLException -> "Secure connection failed (${e.message})"
            is IOException -> "Network error: ${e.message ?: e.javaClass.simpleName}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
