package com.example.otaapplication

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.data.PaymentRepository
import java.time.LocalDate

/**
 * Hands out per-staff payment advice numbers ([AdviceNumber]), following the same rules as order
 * numbers: numbers already used locally (including deleted advices and those pulled from the
 * cloud) and a persisted counter are both respected, so a number is never handed out twice.
 */
class AdviceNumberAllocator(
    private val prefs: SharedPreferences,
    private val payments: PaymentRepository,
    private val today: () -> LocalDate = LocalDate::now
) {
    fun peek(staffCode: Int): String = synchronized(PaymentRepository.numberLock) {
        val prefix = AdviceNumber.prefix(staffCode, today())
        AdviceNumber.format(prefix, nextSerial(prefix))
    }

    /** Picks the next number and calls [save] with it; the number is used up only if [save] succeeds. */
    fun <T : Any> assign(staffCode: Int, save: (String) -> T?): T? = synchronized(PaymentRepository.numberLock) {
        val prefix = AdviceNumber.prefix(staffCode, today())
        val serial = nextSerial(prefix)
        save(AdviceNumber.format(prefix, serial))?.also { remember(prefix, serial) }
    }

    /** A free number for the same staff member and day, when the cloud already has [adviceNo]. */
    fun replacementFor(adviceNo: String): String? = synchronized(PaymentRepository.numberLock) {
        val prefix = AdviceNumber.prefixOf(adviceNo) ?: return null
        val serial = nextSerial(prefix)
        remember(prefix, serial)
        AdviceNumber.format(prefix, serial)
    }

    private fun nextSerial(prefix: String): Int =
        AdviceNumber.nextSerial(prefix, prefs.getString(KEY_PREFIX, null), prefs.getInt(KEY_SERIAL, 0), payments.adviceNosWithPrefix(prefix))

    private fun remember(prefix: String, serial: Int) {
        val storedPrefix = prefs.getString(KEY_PREFIX, null)
        if (storedPrefix == null || storedPrefix == prefix || prefix > storedPrefix) {
            prefs.edit(commit = true) { putString(KEY_PREFIX, prefix).putInt(KEY_SERIAL, serial) }
        }
    }

    private companion object {
        const val KEY_PREFIX = "advice_serial_date"
        const val KEY_SERIAL = "advice_serial"
    }
}
