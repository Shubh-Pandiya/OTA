package com.example.otaapplication.cloud

import com.ota.desktop.db.Kv
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

/** Desktop: the signed-in session lives in the local database (user profile folder). */
class SessionStore(private val kv: Kv) {
    fun load(): CloudSession? {
        val json = kv[KEY]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return CloudSession(
            json.getString("access"), json.getString("refresh"), json.optLong("expires"),
            json.optString("user"), json.optString("email")
        )
    }

    fun save(s: CloudSession) {
        kv[KEY] = JSONObject().put("access", s.accessToken).put("refresh", s.refreshToken).put("expires", s.expiresAtMillis)
            .put("user", s.userId).put("email", s.email).toString()
    }

    fun clear() { kv[KEY] = null }

    private companion object { const val KEY = "auth.session" }
}

/**
 * Email + password sign-in against Supabase Auth, with token refresh. Same behaviour as the phone:
 * no network keeps the user signed in (sync just waits); only a rejected refresh token signs out.
 */
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
