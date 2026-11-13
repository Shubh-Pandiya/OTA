package com.ota.desktop

import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.SessionStore
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import com.ota.desktop.accounts.AccountReports
import com.ota.desktop.accounts.AccountsStore
import com.ota.desktop.dash.Analytics
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Kv
import com.ota.desktop.export.DataExport
import com.example.otaapplication.data.OtaAccounts
import com.ota.desktop.sync.CloudSettings
import com.ota.desktop.sync.DataModeConflict
import com.ota.desktop.sync.StaffProfile
import com.ota.desktop.sync.SyncEngine
import com.ota.desktop.sync.SyncScheduler
import com.ota.desktop.update.StartupGuard
import com.ota.desktop.update.UpdateHost
import com.ota.desktop.update.UpdateManager
import com.ota.desktop.weather.DesktopWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch

/** Everything the screens use: the local database, stores, cloud sign-in and background sync. */
@OptIn(FlowPreview::class)
class AppContext internal constructor(private val server: EmbeddedDb, val db: Db) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val kv = Kv(db)
    val config: SupabaseConfig = SupabaseConfig.fromBuildConfig()
    val auth = SupabaseAuth(config, SessionStore(kv))

    private val _revision = MutableStateFlow(0L)

    /** Bumped on every local edit and every sync round that changed data; screens reload on it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val numbers = Numbers(db, kv)
    val catalog = CatalogStore(db, ::changed)
    val orders = OrderStore(db, ::changed)
    val payments = PaymentStore(db, ::changed)
    val analytics = Analytics(db, orders, payments)
    val export = DataExport(orders, payments, catalog, analytics)

    val engine = SyncEngine(
        db, kv, auth, PostgrestClient(config, auth), catalog, orders, payments, numbers,
        onAdviceNumbered = { com.ota.desktop.pdf.AdvicePdf.write(it) }
    ).also { it.loadDataMode() }

    /** The agency's own books: local only, so edits refresh the screens without waking sync. */
    val accounts = AccountsStore(db, postingAllowed = engine::postingAllowed, onChange = ::localChanged)
    val accountReports = AccountReports(db, accounts, payments)

    /** Admin (cloud clean-up, retention, Accounts): varun@ / shubham@, by sign-in email like the cloud's check. */
    val admin: StateFlow<Boolean> = auth.session.map { OtaAccounts.isAdmin(it?.email) }
        .stateIn(scope, SharingStarted.Eagerly, OtaAccounts.isAdmin(auth.session.value?.email))

    /** The test account: its data is kept apart in the cloud and it can clear it. */
    val testAccount: StateFlow<Boolean> = auth.session.map { OtaAccounts.isTest(it?.email) }
        .stateIn(scope, SharingStarted.Eagerly, OtaAccounts.isTest(auth.session.value?.email))
    val scheduler = SyncScheduler(engine)
    val cloudSettings = CloudSettings(config, auth)
    val weather = DesktopWeather(kv)
    val updates = UpdateManager(UpdateManager.RUNNING)

    private val _staff = MutableStateFlow(engine.cachedStaff())
    val staff: StateFlow<StaffProfile?> = _staff.asStateFlow()

    private var started = false

    private fun changed() {
        _revision.update { it + 1 }
        if (started) scheduler.poke()
    }

    private fun localChanged() {
        _revision.update { it + 1 }
    }

    fun start() {
        started = true
        engine.refreshPending()
        scheduler.start(scope)
        updates.consumeOutcome()
        updates.start(scope)
        scope.launch {
            // A finished sync round may have pulled rows from the phones.
            engine.status.distinctUntilChangedBy { it.lastSyncAt to it.running }.drop(1).collect {
                if (!it.running) {
                    _revision.update { r -> r + 1 }
                    _staff.value = engine.cachedStaff()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            // Commission follows the bills: re-post after edits settle (idempotent; a no-op when nothing changed).
            revision.debounce(1500).collect { runCatching { accounts.reconcileCommission() } }
        }
    }

    suspend fun signIn(email: String, password: String) {
        // Refuse before the network call, so a refused login never shows this laptop's data.
        kotlinx.coroutines.withContext(Dispatchers.IO) { engine.checkDataMode(email) }
        val session = auth.signIn(email, password)
        try {
            engine.adoptDataMode(session.email)
        } catch (e: DataModeConflict) {
            auth.signOut()
            throw e
        }
        _revision.update { it + 1 }
        runCatching { engine.refreshStaff() }
        _staff.value = engine.cachedStaff()
        scheduler.poke()
    }

    /** Test account only: deletes everything it made, in the cloud and on this laptop. */
    suspend fun clearTestData(): Map<String, Int> {
        val counts = engine.clearTestData()
        _revision.update { it + 1 }
        return counts
    }

    suspend fun signOut() {
        auth.signOut()
        engine.clearStaff()
        _staff.value = null
    }

    /** For the updater: a last sync round, then a clean stop; [onExit] ends the process. */
    fun updateHost(onExit: () -> Unit) = object : UpdateHost {
        override suspend fun syncBeforeUpdate(): String? {
            scheduler.stop()
            if (auth.session.value == null) return "not signed in"
            // Waits for a round already running (the engine is single-flight), then runs one more.
            val status = engine.sync()
            return status.error ?: status.pending.takeIf { it > 0 }?.let { "$it changes still pending" }
        }

        override fun shutdown() = close()

        override fun exit() = onExit()
    }

    override fun close() {
        updates.stop()
        scheduler.stop()
        scope.cancel()
        runCatching { db.close() }
        runCatching { server.close() }
    }

    companion object {
        /** Boots (and on first launch creates) the local PostgreSQL, then migrates the schema. */
        fun open(): AppContext {
            // Backs up before a new version touches the data, refuses data from a newer version.
            val guard = StartupGuard(AppPaths.home, AppPaths.pgData, UpdateManager.RUNNING)
            val server = EmbeddedDb.start(beforeStart = guard::beforeStart)
            try {
                val db = Db(server::connect)
                guard.migrate(db)
                guard.started()
                return AppContext(server, db)
            } catch (e: Throwable) {
                runCatching { server.close() }
                throw e
            }
        }
    }
}
