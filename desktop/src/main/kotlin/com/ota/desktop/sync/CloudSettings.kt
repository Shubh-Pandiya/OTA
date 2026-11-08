package com.ota.desktop.sync

import com.example.otaapplication.cloud.CloudException
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Cloud settings only an admin may change (app_settings). */
class CloudSettings(private val config: SupabaseConfig, private val auth: SupabaseAuth) {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Updates retention days, storage limit and warning threshold. Throws when not allowed. */
    suspend fun update(retentionDays: Int, storageLimitMb: Int, warnPercent: Int) {
        require(retentionDays in 1..3650) { "Retention must be 1 to 3650 days" }
        require(storageLimitMb > 0) { "Storage limit must be positive" }
        require(warnPercent in 1..100) { "Warning level must be 1 to 100 %" }
        val body = JSONObject().put("retention_days", retentionDays).put("storage_limit_mb", storageLimitMb).put("warn_percent", warnPercent).toString()
        val token = auth.accessToken()
        val response = withContext(Dispatchers.IO) {
            http.send(
                HttpRequest.newBuilder(URI.create("${config.url}/rest/v1/app_settings?id=eq.true"))
                    .timeout(Duration.ofSeconds(30))
                    .header("apikey", config.publishableKey)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "return=representation")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
        }
        if (response.statusCode() !in 200..299) {
            val message = runCatching { JSONObject(response.body()).optString("message") }.getOrNull().orEmpty().ifBlank { "HTTP ${response.statusCode()}" }
            throw CloudException(message, response.statusCode(), authFailed = response.statusCode() == 401)
        }
        // Row-level security hides the row from non-admins, so nothing is updated and nothing returned.
        if (JSONArray(response.body()).length() == 0) throw CloudException("Only an admin can change cloud settings", 403)
    }
}

/**
 * Background sync: right away on start, shortly after local edits, and every [every]. Maintenance
 * (archive + purge, admins only) runs on the first round and then every [maintenanceEvery].
 */
class SyncScheduler(
    private val engine: SyncEngine,
    private val every: Duration = Duration.ofMinutes(5),
    private val maintenanceEvery: Duration = Duration.ofHours(6),
    private val debounce: Duration = Duration.ofSeconds(4)
) {
    private val wake = Channel<Boolean>(Channel.CONFLATED)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var first = true
            while (true) {
                val maintenance = first || engine.maintenanceDue(maintenanceEvery.toMillis())
                first = false
                engine.sync(maintenance)
                select {
                    wake.onReceive { }
                    onTimeout(every.toMillis()) { }
                }
                // Lets a burst of edits settle into one round.
                delay(debounce.toMillis())
                while (wake.tryReceive().isSuccess) Unit
            }
        }
    }

    /** Asks for a round soon (after local edits). */
    fun poke() {
        wake.trySend(true)
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
