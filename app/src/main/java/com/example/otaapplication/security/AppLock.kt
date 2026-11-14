package com.example.otaapplication.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Optional app lock: a 4–8 digit PIN (only a salted PBKDF2 hash is stored) plus, if the phone
 * has one, fingerprint unlock. The app locks on a cold start and when it comes back after being
 * away longer than [timeoutMs]. Sync keeps running while locked; only the screen is covered.
 */
class AppLock private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked

    private val _settings = MutableStateFlow(0)
    /** Bumped whenever a setting changes, so screens can refresh. */
    val settings: StateFlow<Int> = _settings

    private var backgroundAt: Long? = null

    val enabled: Boolean get() = prefs.contains(KEY_HASH)
    val pinLength: Int get() = prefs.getInt(KEY_LENGTH, 4)

    var biometric: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) { prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply(); _settings.value++ }

    var timeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT, 60_000L)
        set(value) { prefs.edit().putLong(KEY_TIMEOUT, value).apply(); _settings.value++ }

    fun setPin(pin: String) {
        require(isValidPin(pin))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(pin, salt, ITERATIONS), Base64.NO_WRAP))
            .putInt(KEY_ITERATIONS, ITERATIONS)
            .putInt(KEY_LENGTH, pin.length)
            .putInt(KEY_FAILURES, 0)
            .remove(KEY_LOCKOUT_UNTIL)
            .commit()
        _settings.value++
    }

    /** Turns the lock off (after the PIN or the cloud password was confirmed). */
    fun disable() {
        prefs.edit().remove(KEY_HASH).remove(KEY_SALT).remove(KEY_ITERATIONS).remove(KEY_LENGTH)
            .remove(KEY_FAILURES).remove(KEY_LOCKOUT_UNTIL).putBoolean(KEY_BIOMETRIC, false).commit()
        _locked.value = false
        _settings.value++
    }

    sealed interface Result {
        data object Ok : Result
        data class Wrong(val attemptsLeft: Int) : Result
        data class Wait(val millis: Long) : Result
    }

    /** Milliseconds until another PIN may be tried (0 = now). */
    fun waitMs(): Long = (prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Checks [pin]; slow on purpose (call off the main thread). Too many wrong tries add a growing wait. */
    fun check(pin: String): Result {
        waitMs().takeIf { it > 0 }?.let { return Result.Wait(it) }
        val salt = prefs.getString(KEY_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return Result.Ok
        val stored = prefs.getString(KEY_HASH, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return Result.Ok
        val iterations = prefs.getInt(KEY_ITERATIONS, ITERATIONS)
        if (MessageDigest.isEqual(stored, hash(pin, salt, iterations))) {
            prefs.edit().putInt(KEY_FAILURES, 0).remove(KEY_LOCKOUT_UNTIL).apply()
            return Result.Ok
        }
        val failures = prefs.getInt(KEY_FAILURES, 0) + 1
        val edit = prefs.edit().putInt(KEY_FAILURES, failures)
        val wait = lockoutFor(failures)
        if (wait > 0) edit.putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + wait)
        edit.commit()
        return if (wait > 0) Result.Wait(wait) else Result.Wrong(FREE_ATTEMPTS - failures)
    }

    fun unlock() { _locked.value = false }

    /** Call from Activity.onStop (not for configuration changes). */
    fun onBackground() { backgroundAt = SystemClock.elapsedRealtime() }

    /** Call from Activity.onStart: locks again if the app was away for longer than the timeout. */
    fun onForeground() {
        val away = backgroundAt?.let { SystemClock.elapsedRealtime() - it }
        backgroundAt = null
        if (enabled && away != null && away >= timeoutMs) _locked.value = true
    }

    companion object {
        private const val PREFS = "ota_lock"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_ITERATIONS = "pin_iterations"
        private const val KEY_LENGTH = "pin_length"
        private const val KEY_BIOMETRIC = "biometric"
        private const val KEY_TIMEOUT = "timeout_ms"
        private const val KEY_FAILURES = "failures"
        private const val KEY_LOCKOUT_UNTIL = "lockout_until"
        private const val ITERATIONS = 120_000
        const val FREE_ATTEMPTS = 5
        val TIMEOUTS = listOf(30_000L to "30 sec", 60_000L to "1 min", 5 * 60_000L to "5 min", 15 * 60_000L to "15 min")

        fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

        /** No wait for the first [FREE_ATTEMPTS] wrong PINs, then 30 s doubling up to 1 hour. */
        fun lockoutFor(failures: Int): Long =
            if (failures < FREE_ATTEMPTS) 0L else minOf(30_000L shl minOf(failures - FREE_ATTEMPTS, 7), 3_600_000L)

        fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

        @Volatile private var instance: AppLock? = null
        fun get(context: Context): AppLock =
            instance ?: synchronized(this) { instance ?: AppLock(context).also { instance = it } }
    }
}
