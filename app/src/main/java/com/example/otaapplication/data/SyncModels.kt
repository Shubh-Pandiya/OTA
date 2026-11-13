package com.example.otaapplication.data

import com.example.otaapplication.OrderItem
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** A catalog row as stored in the cloud. */
data class RemoteParty(
    val party: Party,
    val deleted: Boolean,
    val createdAt: Long,
    /** Edit time on the phone that made the change (epoch millis); decides conflicts. */
    val clientUpdatedAt: Long,
    /** Server-assigned change time (ISO-8601); used as the pull cursor. */
    val serverUpdatedAt: String
)

/** A local row waiting to be pushed, with the edit time it had when read. */
data class PendingParty(
    val party: Party,
    val deleted: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** What sync knows about a local row: its edit time and whether it still has unpushed changes. */
data class LocalSyncMeta(val updatedAt: Long, val pending: Boolean)

/**
 * Merge rules for the catalog sync (last write wins, by edit time).
 *
 * Pull: a row without local changes always takes the server copy. If both sides changed,
 * the newer edit wins; a local edit that wins stays pending and is pushed next.
 * Push: the server may answer with a newer row it kept instead of ours (see the SQL trigger).
 */
object SyncMerge {
    enum class Decision { APPLY_REMOTE, KEEP_LOCAL, MARK_SYNCED, SKIP }

    fun onPulled(local: LocalSyncMeta?, remote: RemoteParty): Decision = onPulled(local, remote.deleted, remote.clientUpdatedAt)

    fun onPulled(local: LocalSyncMeta?, remoteDeleted: Boolean, remoteClientUpdatedAt: Long): Decision = when {
        local == null -> if (remoteDeleted) Decision.SKIP else Decision.APPLY_REMOTE
        !local.pending -> Decision.APPLY_REMOTE
        remoteClientUpdatedAt > local.updatedAt -> Decision.APPLY_REMOTE
        else -> Decision.KEEP_LOCAL
    }

    fun onPushed(sentUpdatedAt: Long, local: LocalSyncMeta?, returned: RemoteParty): Decision = when {
        local == null -> Decision.APPLY_REMOTE
        // Edited again while the push was in flight: keep it pending for the next push.
        local.updatedAt != sentUpdatedAt -> Decision.KEEP_LOCAL
        returned.clientUpdatedAt == sentUpdatedAt -> Decision.MARK_SYNCED
        else -> Decision.APPLY_REMOTE
    }
}

/** Server timestamp helpers for the pull cursor. */
object SyncTime {
    fun toInstant(serverTime: String): Instant = OffsetDateTime.parse(serverTime).toInstant()

    fun toMillis(serverTime: String): Long = toInstant(serverTime).toEpochMilli()

    fun fromMillis(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun later(a: String?, b: String): String = if (a == null || toInstant(b).isAfter(toInstant(a))) b else a

    /**
     * Cursor moved back by [seconds]. Pulls re-read a short overlap so a row whose transaction
     * committed slightly out of order is never skipped; re-applying an unchanged row is a no-op.
     */
    fun minusSeconds(serverTime: String, seconds: Long): String = toInstant(serverTime).minusSeconds(seconds).toString()
}

/** A row of a synced order table as stored in the cloud. */
data class RemoteRow<T>(
    val id: String,
    val value: T,
    val deleted: Boolean,
    val clientUpdatedAt: Long,
    val serverUpdatedAt: String
)

/**
 * A hard delete: [table] is the cloud table name, [at] the delete time on the deleting device's
 * clock (it beats any edit made before it). [serverUpdatedAt] is the tombstone's pull cursor.
 */
data class Deletion(val table: String, val id: String, val at: Long, val serverUpdatedAt: String = "")

/** Which rows go with a deleted row, so every device removes the same set. */
object DeleteCascade {
    /** (child table, column pointing at the parent) for rows deleted along with a row of [table]. */
    fun children(table: String): List<Pair<String, String>> = when (table) {
        "orders" -> listOf("order_bills" to "order_id")
        "bills" -> listOf("order_bills" to "bill_id", "advice_lines" to "bill_id")
        "payment_advices" -> listOf("advice_lines" to "advice_id")
        else -> emptyList()
    }

    val TABLES = setOf("buyers", "suppliers", "orders", "bills", "order_bills", "payment_advices", "advice_lines")
}

/** A local row waiting to be pushed, with the edit time it had when read. */
data class PendingRow<T>(val id: String, val value: T, val deleted: Boolean, val updatedAt: Long)

/** Index facts about an order waiting to be pushed; the content comes from the history file. */
data class PendingOrderRef(val id: String, val offerNo: String, val date: String, val updatedAt: Long, val deleted: Boolean)

data class BillValue(
    val billNo: String,
    val billDate: String?,
    val amount: Double?,
    val lrNo: String = "",
    val cdPercent: Double? = null,
    val cdAmount: Double? = null,
    val items: List<OrderItem> = emptyList(),
    val buyer: String = "",
    val supplier: String = "",
    val transport: String = ""
)

data class LinkValue(val orderId: String, val billId: String)

/**
 * Ids derived from content, so the same bill (or the same bill-to-order link) created offline on
 * two phones ends up as one cloud row instead of a duplicate.
 */
object SyncIds {
    /**
     * True while the test account's data is on this device. Test bills get their own ids so a
     * test bill never shares an id (and so a cloud row) with a real bill of the same number.
     */
    @Volatile var testMode: Boolean = false

    fun bill(billKey: String): String =
        UUID.nameUUIDFromBytes((if (testMode) "ota-test-bill:$billKey" else "ota-bill:$billKey").toByteArray()).toString()

    fun link(orderId: String, billId: String): String = UUID.nameUUIDFromBytes("ota-link:$orderId:$billId".toByteArray()).toString()

    /** A bill appears at most once on an advice. */
    fun adviceLine(adviceId: String, billId: String): String =
        UUID.nameUUIDFromBytes("ota-advice-line:$adviceId:$billId".toByteArray()).toString()
}

/**
 * Who may do what, by sign-in email. The cloud applies the same rules (ota_is_admin / ota_is_test in
 * supabase/migrations/20261117000000_admins_and_test_isolation.sql), so these only decide what to show.
 */
object OtaAccounts {
    val ADMIN_EMAILS = setOf("varun@ota.com", "shubham@ota.com")
    const val TEST_EMAIL = "testuser1@ota.com"

    fun isAdmin(email: String?): Boolean = email?.trim()?.lowercase() in ADMIN_EMAILS

    /** The test account's rows are kept apart in the cloud: only it sees them, and it sees nothing else. */
    fun isTest(email: String?): Boolean = email?.trim()?.lowercase() == TEST_EMAIL

    /** Which data set a login works on; a device holds one at a time. */
    fun dataMode(email: String?): String = if (isTest(email)) MODE_TEST else MODE_PRODUCTION

    const val MODE_PRODUCTION = "production"
    const val MODE_TEST = "test"
}
