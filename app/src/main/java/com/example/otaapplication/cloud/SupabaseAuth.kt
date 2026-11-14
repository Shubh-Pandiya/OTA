package com.example.otaapplication.cloud

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String
)

/** Keeps the signed-in session in app-private preferences (excluded from backups). */
class SessionStore(private val prefs: SharedPreferences) {
    fun load(): CloudSession? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return CloudSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = prefs.getLong(KEY_EXPIRES, 0),
            userId = prefs.getString(KEY_USER, "").orEmpty(),
            email = prefs.getString(KEY_EMAIL, "").orEmpty()
        )
    }

    fun save(session: CloudSession) = prefs.edit {
        putString(KEY_ACCESS, session.accessToken)
        putString(KEY_REFRESH, session.refreshToken)
        putLong(KEY_EXPIRES, session.expiresAtMillis)
        putString(KEY_USER, session.userId)
        putString(KEY_EMAIL, session.email)
    }

    fun clear() = prefs.edit { listOf(KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES, KEY_USER, KEY_EMAIL).forEach(::remove) }

    private companion object {
        const val KEY_ACCESS = "auth_access_token"
        const val KEY_REFRESH = "auth_refresh_token"
        const val KEY_EXPIRES = "auth_expires_at"
        const val KEY_USER = "auth_user_id"
        const val KEY_EMAIL = "auth_email"
    }
}

/** Email + password sign-in against Supabase Auth (GoTrue REST API), with token refresh. */
class SupabaseAuth(
    private val config: SupabaseConfig,
    private val store: SessionStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Mutex()
    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<CloudSession?> = _session.asStateFlow()

    suspend fun signIn(email: String, password: String): CloudSession = lock.withLock {
        val body = JSONObject().put("email", email.trim()).put("password", password).toString()
        val response = withContext(Dispatchers.IO) { Http.send("POST", "${config.url}/auth/v1/token?grant_type=password", headers(), body) }
        if (!response.ok) throw CloudException(signInErrorMessage(response.status, response.body), response.status)
        parseSession(response.body).also(::store)
    }

    /**
     * A valid access token, refreshed when close to expiry. Throws [CloudException] with authFailed when signed out.
     * [rejected]: the token the cloud just refused. When several calls are refused at once, only the
     * first refreshes; the others get its new token instead of using up the rotating refresh token again.
     */
    suspend fun accessToken(forceRefresh: Boolean = false, rejected: String? = null): String = lock.withLock {
        val current = _session.value ?: throw CloudException("Not signed in", authFailed = true)
        if (!forceRefresh && current.expiresAtMillis - clock() > REFRESH_MARGIN_MS) return@withLock current.accessToken
        if (forceRefresh && rejected != null && current.accessToken != rejected) return@withLock current.accessToken
        val body = JSONObject().put("refresh_token", current.refreshToken).toString()
        val response = withContext(Dispatchers.IO) { Http.send("POST", "${config.url}/auth/v1/token?grant_type=refresh_token", headers(), body) }
        if (!response.ok) {
            if (response.status in 400..403) {
                // Refresh token revoked/expired or user removed: the user has to sign in again.
                clear()
                throw CloudException("Session expired. Please sign in again.", response.status, authFailed = true)
            }
            throw CloudException(Http.errorMessage(response), response.status)
        }
        parseSession(response.body, fallbackEmail = current.email).also(::store).accessToken
    }

    suspend fun signOut() {
        val token = _session.value?.accessToken
        lock.withLock { clear() }
        if (token != null) {
            // Best effort: revoke the refresh token on the server.
            runCatching {
                withContext(Dispatchers.IO) {
                    Http.send("POST", "${config.url}/auth/v1/logout", headers() + ("Authorization" to "Bearer $token"), "{}")
                }
            }
        }
    }

    private fun headers() = mapOf("apikey" to config.publishableKey)

    private fun store(session: CloudSession) {
        store.save(session)
        _session.value = session
    }

    private fun clear() {
        store.clear()
        _session.value = null
    }

    private fun parseSession(body: String, fallbackEmail: String = ""): CloudSession {
        val json = JSONObject(body)
        val user = json.optJSONObject("user")
        val expiresAt = json.optLong("expires_at", 0).takeIf { it > 0 }?.times(1000)
            ?: (clock() + json.optLong("expires_in", 3600) * 1000)
        return CloudSession(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtMillis = expiresAt,
            userId = user?.optString("id").orEmpty(),
            email = user?.optString("email")?.takeIf { it.isNotBlank() } ?: fallbackEmail
        )
    }

    companion object {
        private const val REFRESH_MARGIN_MS = 60_000L

        /** What the sign-in screen shows for a failed sign-in response from Supabase Auth. */
        fun signInErrorMessage(status: Int, body: String): String {
            val response = HttpResponse(status, body)
            val code = runCatching { JSONObject(body) }.getOrNull()
                ?.let { json -> json.optString("error_code").ifBlank { json.optString("code") } }
                .orEmpty()
            val message = Http.errorMessage(response)
            return when {
                code == "email_not_confirmed" || message.contains("not confirmed", ignoreCase = true) ->
                    "Email not confirmed. Ask the owner to confirm this account in Supabase (Authentication → Users)."
                code == "invalid_credentials" || (status == 400 && message.contains("invalid", ignoreCase = true)) ->
                    "Wrong email or password"
                code == "user_banned" || message.contains("banned", ignoreCase = true) ->
                    "This account has been blocked. Ask the owner."
                status == 429 || code.contains("rate_limit") -> "Too many attempts. Wait a minute and try again."
                status >= 500 -> "The cloud is not responding (${status}). Try again shortly."
                else -> message
            }
        }
    }
}
