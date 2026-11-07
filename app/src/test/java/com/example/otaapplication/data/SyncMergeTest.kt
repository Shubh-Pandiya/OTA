package com.example.otaapplication.data

import com.example.otaapplication.data.SyncMerge.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMergeTest {
    private fun remote(clientUpdatedAt: Long, deleted: Boolean = false) = RemoteParty(
        party = Party(id = "id-1", type = PartyType.BUYER, name = "Ram Traders"),
        deleted = deleted,
        createdAt = 1_000,
        clientUpdatedAt = clientUpdatedAt,
        serverUpdatedAt = "2026-11-07T10:00:00.123456+00:00"
    )

    @Test fun pulledNewRowIsInserted() =
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(null, remote(5_000)))

    @Test fun pulledDeletedRowUnknownLocallyIsSkipped() =
        assertEquals(Decision.SKIP, SyncMerge.onPulled(null, remote(5_000, deleted = true)))

    @Test fun pulledRowOverwritesSyncedLocalRow() {
        // Even an "older" edit time wins when we have nothing unsent: the server copy is the truth.
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(LocalSyncMeta(9_000, pending = false), remote(5_000)))
    }

    @Test fun newerRemoteEditBeatsPendingLocalEdit() =
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(LocalSyncMeta(4_000, pending = true), remote(5_000)))

    @Test fun newerPendingLocalEditIsKept() =
        assertEquals(Decision.KEEP_LOCAL, SyncMerge.onPulled(LocalSyncMeta(6_000, pending = true), remote(5_000)))

    @Test fun tieKeepsPendingLocalEdit() =
        assertEquals(Decision.KEEP_LOCAL, SyncMerge.onPulled(LocalSyncMeta(5_000, pending = true), remote(5_000)))

    @Test fun remoteDeleteBeatsOlderPendingEdit() =
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(LocalSyncMeta(4_000, pending = true), remote(5_000, deleted = true)))

    @Test fun acceptedPushIsMarkedSynced() =
        assertEquals(Decision.MARK_SYNCED, SyncMerge.onPushed(5_000, LocalSyncMeta(5_000, pending = true), remote(5_000)))

    @Test fun pushRejectedForNewerServerRowAppliesServerCopy() =
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPushed(5_000, LocalSyncMeta(5_000, pending = true), remote(7_000)))

    @Test fun rowEditedDuringPushStaysPending() =
        assertEquals(Decision.KEEP_LOCAL, SyncMerge.onPushed(5_000, LocalSyncMeta(6_000, pending = true), remote(5_000)))
}

class SyncTimeTest {
    @Test fun parsesPostgrestTimestamps() {
        assertEquals(1_000L, SyncTime.toMillis("1970-01-01T00:00:01+00:00"))
        assertEquals(1_123L, SyncTime.toMillis("1970-01-01T00:00:01.123456+00:00"))
        assertEquals(1_000L, SyncTime.toMillis("1970-01-01T05:30:01+05:30"))
    }

    @Test fun laterComparesInstantsNotText() {
        // Same instant written differently, and a later instant that sorts lower as text.
        val utc = "2026-11-07T10:00:00.5+00:00"
        val ist = "2026-11-07T15:30:00.4+05:30"
        assertEquals(utc, SyncTime.later(ist, utc))
        assertEquals(utc, SyncTime.later(utc, ist))
        assertEquals(utc, SyncTime.later(null, utc))
    }

    @Test fun cursorOverlapKeepsMicroseconds() {
        assertEquals("2026-11-07T09:59:00.123456Z", SyncTime.minusSeconds("2026-11-07T10:00:00.123456+00:00", 60))
    }

    @Test fun rowOverloadFollowsTheSameRules() {
        assertEquals(Decision.SKIP, SyncMerge.onPulled(null, remoteDeleted = true, remoteClientUpdatedAt = 5_000))
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(null, remoteDeleted = false, remoteClientUpdatedAt = 5_000))
        assertEquals(Decision.KEEP_LOCAL, SyncMerge.onPulled(LocalSyncMeta(6_000, pending = true), false, 5_000))
        assertEquals(Decision.APPLY_REMOTE, SyncMerge.onPulled(LocalSyncMeta(6_000, pending = true), true, 7_000))
    }

    @Test fun sameBillOnTwoPhonesGetsTheSameId() {
        assertEquals(SyncIds.bill("inv 42"), SyncIds.bill("inv 42"))
        assertEquals(36, SyncIds.bill("inv 42").length)
        assert(SyncIds.bill("inv 42") != SyncIds.bill("inv 43"))
        assertEquals(SyncIds.link("o1", "b1"), SyncIds.link("o1", "b1"))
        assert(SyncIds.link("o1", "b1") != SyncIds.link("o2", "b1"))
    }
}
