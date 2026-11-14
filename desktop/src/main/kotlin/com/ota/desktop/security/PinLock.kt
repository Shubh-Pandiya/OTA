package com.ota.desktop.security

import com.ota.desktop.backup.LocalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PINs are stored only as `pbkdf2-sha256:<iterations>:<salt>:<hash>` (random 16-byte salt). */
object PinHash {
    const val ITERATIONS = 210_000
    private val b64 = Base64.getEncoder().withoutPadding()

    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes), iterations: Int = ITERATIONS): String =
        "pbkdf2-sha256:$iterations:${b64.encodeToString(salt)}:${b64.encodeToString(derive(pin, salt, iterations))}"

    fun verify(pin: String, stored: String?): Boolean {
        val parts = stored?.split(':') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * The optional PIN lock of this laptop: locks on start and after [idleMinutes] without keyboard or
 * mouse input. Only the screens lock; background sync and backups go on. After [FREE_TRIES] wrong
 * PINs each further try waits longer (30 s doubling, up to 15 min), also across restarts.
 */
class PinLock(private val settings: LocalSettings, private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface Attempt {
        data object Ok : Attempt
        data class Wrong(val triesLeft: Int) : Attempt
        data class Wait(val untilMillis: Long) : Attempt
    }

    val enabled: Boolean get() = settings[PIN] != null

    private val _locked = MutableStateFlow(enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile private var lastActivity = clock()

    /** Minutes without input before the lock comes on; 0 = only when the app starts. */
    var idleMinutes: Int
        get() = settings[IDLE]?.toIntOrNull() ?: DEFAULT_IDLE
        set(value) { settings[IDLE] = value.coerceAtLeast(0).toString() }

    /** Until when PIN entry is paused after too many wrong tries (0 = not paused). */
    val waitUntil: Long get() = settings[WAIT]?.toLongOrNull()?.takeIf { it > clock() } ?: 0L

    fun setPin(pin: String) {
        require(PinHash.valid(pin)) { "The PIN must be 4 to 8 digits" }
        settings.update(PIN to PinHash.hash(pin), FAILS to null, WAIT to null)
        lastActivity = clock()
    }

    fun turnOff() {
        settings.update(PIN to null, FAILS to null, WAIT to null)
        _locked.value = false
    }

    fun lockNow() {
        if (enabled) _locked.value = true
    }

    /** Checks [pin] (slow on purpose: call off the UI thread). */
    @Synchronized
    fun unlock(pin: String): Attempt {
        val wait = waitUntil
        if (wait > 0) return Attempt.Wait(wait)
        if (PinHash.verify(pin, settings[PIN])) {
            settings.update(FAILS to null, WAIT to null)
            unlocked()
            return Attempt.Ok
        }
        val fails = (settings[FAILS]?.toIntOrNull() ?: 0) + 1
        val pause = if (fails < FREE_TRIES) null else clock() + minOf(30_000L shl minOf(fails - FREE_TRIES, 10), MAX_WAIT_MS)
        settings.update(FAILS to fails.toString(), WAIT to pause?.toString())
        return if (pause != null) Attempt.Wait(pause) else Attempt.Wrong(FREE_TRIES - fails)
    }

    /** After the user proved who they are another way (sign-in, or "Forgot PIN" with the account password). */
    fun unlocked() {
        settings.update(FAILS to null, WAIT to null)
        lastActivity = clock()
        _locked.value = false
    }

    /** Any keyboard or mouse input. */
    fun activity() {
        lastActivity = clock()
    }

    /** Locks when idle for longer than [idleMinutes]; called every few seconds. */
    fun checkIdle() {
        val minutes = idleMinutes
        if (!enabled || _locked.value || minutes <= 0) return
        if (clock() - lastActivity >= minutes * 60_000L) _locked.value = true
    }

    companion object {
        const val DEFAULT_IDLE = 15
        const val FREE_TRIES = 5
        const val MAX_WAIT_MS = 15 * 60_000L
        val IDLE_CHOICES = listOf(5, 15, 30, 60, 0)
        private const val PIN = "lock.pin"
        private const val IDLE = "lock.idle.minutes"
        private const val FAILS = "lock.failed.tries"
        private const val WAIT = "lock.wait.until"
    }
}
