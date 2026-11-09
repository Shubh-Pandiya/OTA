package com.ota.desktop

import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.SessionStore
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import com.ota.desktop.dash.Analytics
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Kv
import com.ota.desktop.db.Migrations
import com.ota.desktop.export.DataExport
import com.ota.desktop.sync.CloudSettings
import com.ota.desktop.sync.StaffProfile
import com.ota.desktop.sync.SyncEngine
import com.ota.desktop.sync.SyncScheduler
import com.ota.desktop.weather.DesktopWeather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the screens use: the local database, stores, cloud sign-in and background sync. */
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
    )
    val scheduler = SyncScheduler(engine)
    val cloudSettings = CloudSettings(config, auth)
    val weather = DesktopWeather(kv)

    private val _staff = MutableStateFlow(engine.cachedStaff())
    val staff: StateFlow<StaffProfile?> = _staff.asStateFlow()

    private var started = false

    private fun changed() {
        _revision.update { it + 1 }
        if (started) scheduler.poke()
    }

    fun start() {
        started = true
        engine.refreshPending()
        scheduler.start(scope)
        scope.launch {
            // A finished sync round may have pulled rows from the phones.
            engine.status.distinctUntilChangedBy { it.lastSyncAt to it.running }.drop(1).collect {
                if (!it.running) {
                    _revision.update { r -> r + 1 }
                    _staff.value = engine.cachedStaff()
                }
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signIn(email, password)
        runCatching { engine.refreshStaff() }
        _staff.value = engine.cachedStaff()
        scheduler.poke()
    }

    suspend fun signOut() {
        auth.signOut()
        engine.clearStaff()
        _staff.value = null
    }

    override fun close() {
        scheduler.stop()
        scope.cancel()
        runCatching { db.close() }
        runCatching { server.close() }
    }

    companion object {
        /** Boots (and on first launch creates) the local PostgreSQL, then migrates the schema. */
        fun open(): AppContext {
            val server = EmbeddedDb.start()
            try {
                val db = Db(server::connect)
                Migrations.run(db)
                return AppContext(server, db)
            } catch (e: Throwable) {
                runCatching { server.close() }
                throw e
            }
        }
    }
}
