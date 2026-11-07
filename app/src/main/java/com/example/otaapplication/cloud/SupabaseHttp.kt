package com.example.otaapplication.cloud

import com.example.otaapplication.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

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

/** A failed call to Supabase. [authFailed] means the user has to sign in again. */
class CloudException(message: String, val status: Int = 0, val authFailed: Boolean = false) : IOException(message)

internal class HttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

internal object Http {
    fun send(method: String, url: String, headers: Map<String, String>, body: String? = null): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach(conn::setRequestProperty)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, text)
        } finally {
            conn.disconnect()
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
}
