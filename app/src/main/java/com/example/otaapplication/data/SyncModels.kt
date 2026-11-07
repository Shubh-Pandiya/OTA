package com.example.otaapplication.data

import java.time.Instant
import java.time.OffsetDateTime

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

    fun onPulled(local: LocalSyncMeta?, remote: RemoteParty): Decision = when {
        local == null -> if (remote.deleted) Decision.SKIP else Decision.APPLY_REMOTE
        !local.pending -> Decision.APPLY_REMOTE
        remote.clientUpdatedAt > local.updatedAt -> Decision.APPLY_REMOTE
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
