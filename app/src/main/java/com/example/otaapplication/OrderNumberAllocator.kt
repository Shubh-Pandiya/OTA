package com.example.otaapplication

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.otaapplication.data.OrderHistoryStore
import com.example.otaapplication.data.OrderRepository
import java.time.LocalDate

/**
 * Hands out per-staff order numbers ([OrderNumber]). Runs under the history lock, so a number is
 * picked and its order written in one step, and a sync applying pulled orders can't interleave.
 * Numbers already in use come from the history (which includes orders pulled from other phones)
 * and the order index (which also remembers deleted orders), plus a persisted counter.
 */
class OrderNumberAllocator(
    private val prefs: SharedPreferences,
    private val history: OrderHistoryStore,
    private val orders: OrderRepository,
    private val today: () -> LocalDate = LocalDate::now
) {
    /** The number the next new order of [staffCode] gets if it is saved now. */
    fun peek(staffCode: Int): String = history.locked {
        val prefix = OrderNumber.prefix(staffCode, today())
        OrderNumber.format(prefix, nextSerial(prefix))
    }

    /**
     * Picks the next number and calls [save] with it. The number is consumed only when [save]
     * returns true, so a failed save doesn't use one up. Returns the number, or null on failure.
     */
    fun assign(staffCode: Int, save: (String) -> Boolean): String? = history.locked {
        val prefix = OrderNumber.prefix(staffCode, today())
        val serial = nextSerial(prefix)
        val offerNo = OrderNumber.format(prefix, serial)
        if (save(offerNo)) {
            remember(prefix, serial)
            offerNo
        } else null
    }

    /**
     * A free number for the same staff member and day as [offerNo], used when the cloud reports
     * that [offerNo] is already taken. Null for numbers that aren't in the per-staff format.
     */
    fun replacementFor(offerNo: String): String? = history.locked {
        val prefix = OrderNumber.prefixOf(offerNo) ?: return@locked null
        val serial = nextSerial(prefix)
        remember(prefix, serial)
        OrderNumber.format(prefix, serial)
    }

    private fun nextSerial(prefix: String): Int {
        val array = history.readArray()
        val used = (0 until array.length()).map { array.getJSONObject(it).optString("offerNo") } + orders.offerNosWithPrefix(prefix)
        return OrderNumber.nextSerial(prefix, prefs.getString(KEY_PREFIX, null), prefs.getInt(KEY_SERIAL, 0), used)
    }

    private fun remember(prefix: String, serial: Int) {
        val storedPrefix = prefs.getString(KEY_PREFIX, null)
        // Keep the counter on today's prefix; an older day's renumbering doesn't move it back.
        if (storedPrefix == null || storedPrefix == prefix || prefix > storedPrefix) {
            prefs.edit(commit = true) { putString(KEY_PREFIX, prefix).putInt(KEY_SERIAL, serial) }
        }
    }

    companion object {
        const val PREFS = "OTA_Prefs"
        private const val KEY_PREFIX = "order_serial_date"
        private const val KEY_SERIAL = "order_serial"
    }
}
