package com.example.otaapplication.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.edit
import com.example.otaapplication.AdviceNumberAllocator
import com.example.otaapplication.OrderFiles
import com.example.otaapplication.OrderNumberAllocator
import com.example.otaapplication.data.CatalogRepository
import com.example.otaapplication.data.LocalOrders
import com.example.otaapplication.data.OtaDatabase
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PaymentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
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
    val pending: Int = 0,
    /** Something from the last order sync the user should know (e.g. an order got a new number). */
    val notice: String? = null,
    /** Cloud database use and retention days from the last successful check. */
    val usage: CloudUsage? = null,
    /** The cloud refused writes because it is out of space; edits stay pending on the phone. */
    val storageFull: Boolean = false
) {
    val signedIn: Boolean get() = email != null
}

enum class SyncOutcome { SKIPPED, SUCCESS, OFFLINE, FAILED, SIGNED_OUT }

/** App-wide cloud state: staff sign-in and syncing the catalog, orders and bills with Supabase. */
class CloudSync private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val config = SupabaseConfig.fromBuildConfig()
    private val repository = CatalogRepository(OtaDatabase.get(context))
    private val auth = SupabaseAuth(config, SessionStore(prefs))
    private val cursors = PrefsSyncCursors(prefs)
    private val client = PostgrestClient(config, auth)
    private val engine = CatalogSync(repository, PostgrestCatalogApi(client), cursors)
    private val orders = LocalOrders.get(context)
    private val payments = PaymentRepository.get(context)
    private val orderApi = PostgrestOrderApi(client)
    private val orderCursors = PrefsCursorStore(prefs)
    private val numberPrefs = context.getSharedPreferences(OrderNumberAllocator.PREFS, Context.MODE_PRIVATE)
    private val orderEngine = OrderSync(
        orders, orderApi, orderCursors,
        renumber = OrderNumberAllocator(numberPrefs, orders.history, orders.index)::replacementFor,
        payments = payments,
        renumberAdvice = AdviceNumberAllocator(numberPrefs, payments)::replacementFor,
        numberOrder = { uuid, offerNo ->
            // Saved again under its number, which also makes the order PDF it had to wait for.
            orders.get(uuid)?.let { OrderFiles(context, orders).save(it.copy(offerNo = offerNo), uuid).first } ?: false
        }
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()
    private var scheduled: Job? = null
    private var foregroundPoll: Job? = null
    @Volatile private var lastAttemptAt = 0L

    private val _status = MutableStateFlow(
        CloudStatus(
            configured = config.isConfigured,
            email = auth.session.value?.email,
            lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0).takeIf { it > 0 },
            usage = CloudUsage.parse(prefs.getString(KEY_USAGE, null)),
            storageFull = prefs.getBoolean(KEY_STORAGE_FULL, false)
        )
    )
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _catalogChanges = MutableStateFlow(0)
    /** Increases whenever a sync changed the local catalog, so open screens can reload. */
    val catalogChanges: StateFlow<Int> = _catalogChanges.asStateFlow()

    private val _orderChanges = MutableStateFlow(0)
    /** Increases whenever a sync changed orders or bills on this phone. */
    val orderChanges: StateFlow<Int> = _orderChanges.asStateFlow()

    private val _staff = MutableStateFlow(loadStaff())
    /**
     * The signed-in staff member's order number digit, cached so orders can be created offline.
     * Null until fetched at sign-in, or when the owner hasn't given this login a code.
     */
    val staff: StateFlow<StaffInfo?> = _staff.asStateFlow()

    init {
        // Order edits (new, changed, deleted, bills) upload shortly after they are made.
        orders.index.onLocalChange = { requestSync(delayMillis = 1_500) }
        payments.onLocalChange = { requestSync(delayMillis = 1_500) }
        scope.launch { refreshPending() }
        if (config.isConfigured && auth.session.value != null) CatalogSyncWorker.schedule(context)
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
        CatalogSyncWorker.schedule(context)
        requestSync()
    }

    suspend fun signOut() {
        scheduled?.cancel()
        CatalogSyncWorker.cancel(context)
        syncLock.withLock {
            auth.signOut()
            // Next sign-in pulls everything again; local rows and pending edits are kept.
            PartyType.entries.forEach { cursors.set(it, null) }
            ORDER_TABLES.forEach { orderCursors.set(it, null) }
            saveStaff(null)
            prefs.edit { remove(KEY_LAST_SYNC).remove(KEY_USAGE).remove(KEY_STORAGE_FULL) }
            StorageAlert.update(context, null, full = false)
        }
        _status.update {
            it.copy(email = null, lastSyncAt = null, lastError = null, offline = false, notice = null, usage = null, storageFull = false)
        }
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

    /**
     * Called when the app becomes visible: syncs right away (unless one just ran) and then keeps
     * pulling every [FOREGROUND_POLL_MS] while visible, so edits from other phones show up quickly.
     */
    fun onAppForeground() {
        if (System.currentTimeMillis() - lastAttemptAt > FOREGROUND_MIN_GAP_MS) requestSync()
        if (foregroundPoll?.isActive == true) return
        foregroundPoll = scope.launch {
            while (isActive) {
                delay(FOREGROUND_POLL_MS)
                // Skip a round if another sync (edit, reconnect, manual) ran recently.
                if (System.currentTimeMillis() - lastAttemptAt >= FOREGROUND_POLL_MS - FOREGROUND_MIN_GAP_MS) {
                    // Separate job: leaving the app must not cut a round off halfway.
                    scope.launch { syncNow() }.join()
                }
            }
        }
    }

    /** Called when the app is no longer visible; background sync is left to WorkManager. */
    fun onAppBackground() {
        foregroundPoll?.cancel()
        foregroundPoll = null
    }

    /**
     * Runs one sync round. Manual, automatic and background syncs share [syncLock], so they never
     * overlap; a caller that arrives while a round is running waits and then runs its own round.
     */
    /**
     * The next agency-wide order number from the cloud, for a new order dated [orderDate].
     * Throws when offline or not signed in (the order is then saved with a pending number) and
     * [OrderLimitReached] when the financial year's numbers are used up.
     */
    suspend fun allocateOrderNo(orderDate: LocalDate): String {
        if (!config.isConfigured || auth.session.value == null) throw CloudException("Not signed in")
        return withTimeoutOrNull(ALLOCATE_TIMEOUT_MS) { orderApi.allocateOrderNo(orderDate) } ?: throw SocketTimeoutException("No answer from the cloud")
    }

    suspend fun syncNow(): SyncOutcome {
        if (!config.isConfigured || auth.session.value == null) return SyncOutcome.SKIPPED
        return syncLock.withLock {
            // Signed out while waiting for the running round.
            if (auth.session.value == null) return@withLock SyncOutcome.SKIPPED
            lastAttemptAt = System.currentTimeMillis()
            _status.update { it.copy(syncing = true) }
            try {
                // A full cloud refuses uploads but still serves pulls: keep going with the other
                // tables and report it at the end. Rows that failed to upload stay pending.
                var fullError: CloudException? = null
                suspend fun <T> part(block: suspend () -> T): T? = try {
                    block()
                } catch (e: CloudException) {
                    if (!e.storageFull) throw e
                    fullError = e
                    null
                }
                val result = part { engine.syncAll() }
                if ((result?.pulledChanges ?: 0) > 0) _catalogChanges.update { it + 1 }
                refreshStaff()
                val orderResult = part { orderEngine.syncAll() }
                if (orderResult != null && (orderResult.pulledChanges > 0 || orderResult.notices.isNotEmpty())) {
                    _orderChanges.update { it + 1 }
                }
                refreshUsage(full = fullError != null)
                fullError?.let { throw it }
                val now = System.currentTimeMillis()
                prefs.edit { putLong(KEY_LAST_SYNC, now) }
                _status.update {
                    it.copy(
                        lastSyncAt = now, lastError = null, offline = false,
                        notice = orderResult?.notices?.lastOrNull() ?: it.notice
                    )
                }
                SyncOutcome.SUCCESS
            } catch (e: CloudException) {
                val sessionLost = e.authFailed && auth.session.value == null
                if (e.storageFull) {
                    // Over the plan quota (402) every call fails, so the usage check above may not have run.
                    if (!_status.value.storageFull) setStorageFull(true)
                    StorageAlert.update(context, _status.value.usage, full = true)
                }
                _status.update {
                    it.copy(
                        lastError = if (e.storageFull) StorageAlert.FULL else e.message,
                        offline = false, email = if (sessionLost) null else it.email
                    )
                }
                if (sessionLost) {
                    CatalogSyncWorker.cancel(context)
                    SyncOutcome.SIGNED_OUT
                } else {
                    SyncOutcome.FAILED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val offline = isOffline(e)
                _status.update { it.copy(lastError = friendlyError(e), offline = offline) }
                if (offline) SyncOutcome.OFFLINE else SyncOutcome.FAILED
            } finally {
                _status.update { it.copy(syncing = false, pending = pendingCount()) }
            }
        }
    }

    private fun refreshPendingAsync() = scope.launch { refreshPending() }

    private fun refreshPending() {
        val pending = pendingCount()
        _status.update { it.copy(pending = pending) }
    }

    private fun pendingCount() = repository.pendingCount() + orders.index.pendingCount() + payments.pendingCount()

    fun dismissNotice() = _status.update { it.copy(notice = null) }

    /** Reads storage use and retention (one small RPC per sync) and raises or clears the alert. */
    private suspend fun refreshUsage(full: Boolean) {
        val usage = try {
            orderApi.usage()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: _status.value.usage
        prefs.edit { if (usage != null) putString(KEY_USAGE, usage.toJson()) }
        _status.update { it.copy(usage = usage) }
        setStorageFull(full)
        StorageAlert.update(context, usage, full)
    }

    private fun setStorageFull(full: Boolean) {
        prefs.edit { putBoolean(KEY_STORAGE_FULL, full) }
        _status.update { it.copy(storageFull = full) }
    }

    /** Reads this login's staff code when unknown or not checked for a while. Runs under [syncLock]. */
    private suspend fun refreshStaff() {
        val session = auth.session.value ?: return
        val checkedAt = prefs.getLong(KEY_STAFF_CHECKED, 0)
        val fresh = prefs.getString(KEY_STAFF_USER, null) == session.userId &&
            System.currentTimeMillis() - checkedAt < STAFF_RECHECK_MS
        if (_staff.value != null && fresh) return
        val info = orderApi.staff(session.userId)
        saveStaff(info, session.userId)
    }

    private fun loadStaff(): StaffInfo? {
        val code = prefs.getInt(KEY_STAFF_CODE, 0)
        return if (code in 1..9 && auth.session.value?.userId == prefs.getString(KEY_STAFF_USER, null)) {
            StaffInfo(code, prefs.getString(KEY_STAFF_NAME, "").orEmpty())
        } else null
    }

    private fun saveStaff(info: StaffInfo?, userId: String? = null) {
        prefs.edit {
            if (info == null || userId == null) {
                listOf(KEY_STAFF_CODE, KEY_STAFF_NAME, KEY_STAFF_USER, KEY_STAFF_CHECKED).forEach(::remove)
                if (userId != null) putString(KEY_STAFF_USER, userId).putLong(KEY_STAFF_CHECKED, System.currentTimeMillis())
            } else {
                putInt(KEY_STAFF_CODE, info.code).putString(KEY_STAFF_NAME, info.name)
                    .putString(KEY_STAFF_USER, userId).putLong(KEY_STAFF_CHECKED, System.currentTimeMillis())
            }
        }
        _staff.value = info
    }

    companion object {
        private const val PREFS = "ota_cloud"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_STAFF_CODE = "staff_code"
        private const val KEY_STAFF_NAME = "staff_name"
        private const val KEY_STAFF_USER = "staff_user_id"
        private const val KEY_STAFF_CHECKED = "staff_checked_at"
        private const val KEY_USAGE = "cloud_usage"
        private const val KEY_STORAGE_FULL = "cloud_storage_full"
        /** How often a known staff code is checked again (codes rarely change). */
        private const val ALLOCATE_TIMEOUT_MS = 10_000L
        private const val STAFF_RECHECK_MS = 6 * 60 * 60 * 1000L
        private val ORDER_TABLES = listOf(
            OrderTable.ORDERS.name, OrderTable.BILLS.name, OrderTable.LINKS.name, OrderTable.ADVICES.name, OrderTable.ADVICE_LINES.name
        )
        /** A foreground sync is skipped if any sync started less than this long ago. */
        private const val FOREGROUND_MIN_GAP_MS = 5_000L
        /** Pull interval while the app is on screen (two small requests when nothing changed). */
        private const val FOREGROUND_POLL_MS = 30_000L

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
