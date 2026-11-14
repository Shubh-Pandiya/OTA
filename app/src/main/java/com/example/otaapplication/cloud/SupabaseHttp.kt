package com.example.otaapplication.cloud

import com.example.otaapplication.BuildConfig
import com.example.otaapplication.data.SyncTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Supabase project settings, read from BuildConfig (filled from the git-ignored local.properties).
 * Only the publishable (anon) key is ever used by the app; row-level security protects the data.
 */
data class SupabaseConfig(val url: String, val publishableKey: String) {
    val isConfigured: Boolean get() = url.startsWith("https://") && publishableKey.isNotBlank()

    companion object {
        fun fromBuildConfig() = SupabaseConfig(BuildConfig.SUPABASE_URL.trimEnd('/'), BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }
}

/**
 * A failed call to Supabase. [authFailed] means the user has to sign in again; [code] is the
 * Postgres error code from PostgREST when there is one (e.g. 23505 = duplicate key).
 */
class CloudException(message: String, val status: Int = 0, val authFailed: Boolean = false, val code: String? = null) : IOException(message) {
    val duplicateKey: Boolean get() = code == "23505"

    /**
     * The cloud refused to store more: the database went read-only (disk almost full, 25006),
     * the disk is full (53100) or the plan's database quota is exceeded (HTTP 402).
     */
    val storageFull: Boolean get() = status == 402 || code == "25006" || code == "53100"

    /** The function or table isn't in the cloud yet (its migration hasn't been run). */
    val missingInCloud: Boolean get() = code == "PGRST202" || code == "PGRST205" || code == "42P01" || code == "42883"
}

/** [date]: the server's clock (the HTTP `Date` header, whole seconds) in epoch millis; 0 when absent. */
internal class HttpResponse(val status: Int, val body: String, val date: Long = 0) {
    val ok: Boolean get() = status in 200..299
}

internal object Http {
    /**
     * One request. Responses are asked for gzip-compressed (JSON shrinks several times, which is what
     * counts on a slow link) and unpacked here: the JVM's HttpURLConnection never does it, and Android's
     * only does when it added the header itself. After a complete exchange the connection is left open
     * for the next call (keep-alive) instead of paying for a new TLS handshake.
     * Request bodies stay uncompressed: PostgREST rejects `Content-Encoding: gzip` (PGRST102).
     */
    fun send(method: String, url: String, headers: Map<String, String>, body: String? = null): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach(conn::setRequestProperty)
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val raw = if (status >= 400) conn.errorStream else conn.inputStream
            val stream = if (raw != null && conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, text, conn.date)
        } catch (e: IOException) {
            // A broken exchange must not go back to the keep-alive pool.
            conn.disconnect()
            throw e
        }
    }

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    /** Best human-readable message from a GoTrue / PostgREST error body. */
    fun errorMessage(response: HttpResponse): String {
        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val text = listOf("msg", "error_description", "message", "error")
            .firstNotNullOfOrNull { key -> json?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } }
        return text ?: "Server error (${response.status})"
    }

    fun errorCode(response: HttpResponse): String? =
        runCatching { JSONObject(response.body).optString("code") }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}

/** Unknown column or table: the app is newer than the cloud schema. */
private val SCHEMA_MISSING = setOf("42703", "42P01", "PGRST204", "PGRST205")

/** Authenticated PostgREST calls; retries once with a refreshed token on 401. */
class PostgrestClient(val config: SupabaseConfig, private val auth: SupabaseAuth) {
    @Volatile private var serverMillis = 0L

    /**
     * The cloud's clock as of the latest answer (its `Date` header), so no later than when any call
     * made from now on is served; null before the first answer. Pull cursors use it, never this
     * device's clock, which may be off.
     */
    fun serverTime(): String? = serverMillis.takeIf { it > 0 }?.let(SyncTime::fromMillis)

    suspend fun call(method: String, path: String, extraHeaders: Map<String, String> = emptyMap(), body: String? = null): String {
        val url = "${config.url}/rest/v1/$path"
        var token = auth.accessToken()
        repeat(2) { attempt ->
            val headers = extraHeaders + mapOf("apikey" to config.publishableKey, "Authorization" to "Bearer $token")
            val response = withContext(Dispatchers.IO) { Http.send(method, url, headers, body) }
            if (response.date > serverMillis) serverMillis = response.date
            if (response.ok) return response.body
            if (response.status == 401 && attempt == 0) {
                token = auth.accessToken(forceRefresh = true, rejected = token)
            } else {
                val message = Http.errorMessage(response)
                val code = Http.errorCode(response)
                throw CloudException(
                    when {
                        response.status == 401 || response.status == 403 -> "Not allowed: $message"
                        code in SCHEMA_MISSING -> "The cloud database needs an update: run the latest file in supabase/migrations " +
                            "in the Supabase SQL Editor ($message)"
                        else -> message
                    },
                    response.status,
                    authFailed = response.status == 401,
                    code = code
                )
            }
        }
        error("unreachable")
    }
}
