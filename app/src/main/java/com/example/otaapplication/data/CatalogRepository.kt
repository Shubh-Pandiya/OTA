package com.example.otaapplication.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/** Buyer and supplier catalog. All calls hit the database; run them off the main thread. */
class CatalogRepository(
    private val helper: OtaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Called after every local add/edit/remove, e.g. to schedule a cloud sync. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    fun list(type: PartyType, query: String = ""): List<Party> {
        val (where, args) = tokenFilter(query)
        return helper.readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM ${type.table} WHERE is_deleted = 0$where ORDER BY name_key",
            args
        ).use { c -> c.toParties(type) }
    }

    /** Type-ahead matches for what is typed in a form field; names starting with the text rank first. */
    fun suggest(type: PartyType, typed: String, limit: Int = 6): List<Party> {
        val first = SearchText.normalize(SearchText.firstLine(typed))
        if (first.isEmpty()) return emptyList()
        val (where, args) = tokenFilter(first)
        return helper.readableDatabase.rawQuery(
            """
            SELECT $COLUMNS FROM ${type.table}
            WHERE is_deleted = 0$where
            ORDER BY CASE WHEN name_key LIKE ? ESCAPE '\' THEN 0 ELSE 1 END, name_key
            LIMIT $limit
            """.trimIndent(),
            args + SearchText.prefixPattern(first)
        ).use { c -> c.toParties(type) }
    }

    fun findByName(type: PartyType, name: String, excludingId: String? = null): Party? =
        helper.readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM ${type.table} WHERE is_deleted = 0 AND name_key = ? AND id != ? LIMIT 1",
            arrayOf(SearchText.normalize(name), excludingId.orEmpty())
        ).use { c -> c.toParties(type).firstOrNull() }

    fun count(type: PartyType): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${type.table} WHERE is_deleted = 0", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Inserts when [party] has no id yet, otherwise updates it. Returns the stored entry. */
    fun save(party: Party): Party {
        val clean = party.copy(
            name = party.name.trim(),
            address = party.address.trim(),
            city = party.city.trim(),
            phone = party.phone.trim(),
            gstin = party.gstin.trim().uppercase(),
            bank = party.bank.cleaned()
        )
        require(clean.name.isNotEmpty()) { "Name is required" }
        val db = helper.writableDatabase
        val saved = db.inTransaction {
            val values = partyValues(clean).apply {
                put("sync_pending", 1)
            }
            if (clean.id.isBlank()) {
                val id = UUID.randomUUID().toString()
                val now = clock()
                values.put("id", id)
                values.put("created_at", now)
                values.put("updated_at", now)
                values.put("is_deleted", 0)
                db.insertOrThrow(clean.type.table, null, values)
                clean.copy(id = id)
            } else {
                values.put("updated_at", nextEditTime(db, clean.type, clean.id))
                db.update(clean.type.table, values, "id = ?", arrayOf(clean.id))
                clean
            }
        }
        onLocalChange?.invoke()
        return saved
    }

    fun delete(type: PartyType, id: String) {
        val db = helper.writableDatabase
        db.inTransaction {
            val values = ContentValues().apply {
                put("is_deleted", 1)
                put("updated_at", nextEditTime(db, type, id))
                put("sync_pending", 1)
            }
            db.update(type.table, values, "id = ?", arrayOf(id))
        }
        onLocalChange?.invoke()
    }

    // ------------------------------------------------------------------ cloud sync support

    fun pendingCount(): Int = PartyType.entries.sumOf { type ->
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ${type.table} WHERE sync_pending = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Rows with unpushed changes, including soft-deleted ones (their deletion must sync too). */
    fun pendingRows(type: PartyType, limit: Int): List<PendingParty> =
        helper.readableDatabase.rawQuery(
            "SELECT $COLUMNS, is_deleted, created_at, updated_at FROM ${type.table} WHERE sync_pending = 1 ORDER BY updated_at, id LIMIT $limit",
            null
        ).use { c ->
            val out = ArrayList<PendingParty>(c.count)
            while (c.moveToNext()) {
                out += PendingParty(c.toParty(type), deleted = c.getInt(12) == 1, createdAt = c.getLong(13), updatedAt = c.getLong(14))
            }
            out
        }

    /** Applies rows pulled from the cloud. Returns how many local rows actually changed. */
    fun mergePulled(type: PartyType, rows: List<RemoteParty>): Int {
        if (rows.isEmpty()) return 0
        val db = helper.writableDatabase
        return db.inTransaction {
            rows.count { remote ->
                when (SyncMerge.onPulled(syncMeta(db, type, remote.party.id), remote)) {
                    SyncMerge.Decision.APPLY_REMOTE -> writeRemote(db, type, remote)
                    else -> false
                }
            }
        }
    }

    /** Records the server's answer to a push. Returns how many local rows changed content. */
    fun applyPushResult(type: PartyType, sent: List<PendingParty>, returned: List<RemoteParty>): Int {
        val byId = returned.associateBy { it.party.id }
        val db = helper.writableDatabase
        return db.inTransaction {
            sent.count { row ->
                val remote = byId[row.party.id] ?: return@count false
                when (SyncMerge.onPushed(row.updatedAt, syncMeta(db, type, row.party.id), remote)) {
                    SyncMerge.Decision.MARK_SYNCED -> {
                        db.update(type.table, ContentValues().apply { put("sync_pending", 0) }, "id = ? AND updated_at = ?", arrayOf(row.party.id, row.updatedAt.toString()))
                        false
                    }
                    SyncMerge.Decision.APPLY_REMOTE -> writeRemote(db, type, remote)
                    else -> false
                }
            }
        }
    }

    private fun syncMeta(db: SQLiteDatabase, type: PartyType, id: String): LocalSyncMeta? =
        db.rawQuery("SELECT updated_at, sync_pending FROM ${type.table} WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) LocalSyncMeta(c.getLong(0), c.getInt(1) == 1) else null
        }

    /** Writes the cloud copy of a row as synced. Returns false when nothing actually changed. */
    private fun writeRemote(db: SQLiteDatabase, type: PartyType, remote: RemoteParty): Boolean {
        val id = remote.party.id
        val current = db.rawQuery(
            "SELECT $COLUMNS, is_deleted, updated_at, sync_pending FROM ${type.table} WHERE id = ?", arrayOf(id)
        ).use { c -> if (c.moveToFirst()) Triple(c.toParty(type) to (c.getInt(12) == 1), c.getLong(13), c.getInt(14) == 1) else null }
        val incoming = remote.party.copy(type = type)
        if (current != null && current.first == (incoming to remote.deleted) && current.second == remote.clientUpdatedAt && !current.third) {
            return false
        }
        val values = partyValues(incoming).apply {
            put("id", id)
            put("created_at", remote.createdAt)
            put("updated_at", remote.clientUpdatedAt)
            put("is_deleted", if (remote.deleted) 1 else 0)
            put("sync_pending", 0)
        }
        db.insertWithOnConflict(type.table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return current == null || current.first != (incoming to remote.deleted)
    }

    /**
     * Edit time for a change to an existing row: now, but never earlier than the row's current
     * edit time, so a phone with a slow clock can still overwrite a row last edited elsewhere.
     */
    private fun nextEditTime(db: SQLiteDatabase, type: PartyType, id: String): Long {
        val previous = db.rawQuery("SELECT updated_at FROM ${type.table} WHERE id = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        return maxOf(clock(), previous + 1)
    }

    private fun partyValues(party: Party) = ContentValues().apply {
        put("name", party.name)
        put("name_key", SearchText.normalize(party.name))
        put("address", party.address)
        put("city", party.city)
        put("phone", party.phone)
        put("gstin", party.gstin)
        put("bank_account_name", party.bank.accountName)
        put("bank_name", party.bank.bankName)
        put("bank_account_no", party.bank.accountNo)
        put("bank_ifsc", party.bank.ifsc)
        put("bank_branch", party.bank.branch)
        put("bank_upi", party.bank.upi)
        put(
            "search_key",
            SearchText.normalize(
                listOf(
                    party.name, party.city, party.phone, party.gstin, party.address,
                    party.bank.accountName, party.bank.bankName, party.bank.accountNo, party.bank.ifsc, party.bank.upi
                ).joinToString(" ")
            )
        )
    }

    private inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
        beginTransaction()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    private fun tokenFilter(query: String): Pair<String, Array<String>> {
        val tokens = SearchText.tokens(query)
        val where = tokens.joinToString("") { " AND search_key LIKE ? ESCAPE '\\'" }
        return where to tokens.map(SearchText::containsPattern).toTypedArray()
    }

    private fun Cursor.toParties(type: PartyType): List<Party> {
        val out = ArrayList<Party>(count)
        while (moveToNext()) out += toParty(type)
        return out
    }

    private fun Cursor.toParty(type: PartyType) = Party(
        id = getString(0), type = type, name = getString(1), address = getString(2),
        city = getString(3), phone = getString(4), gstin = getString(5),
        bank = BankDetails(
            accountName = getString(6), bankName = getString(7), accountNo = getString(8),
            ifsc = getString(9), branch = getString(10), upi = getString(11)
        )
    )

    private companion object {
        val COLUMNS = "id, name, address, city, phone, gstin, " + OtaDatabase.BANK_COLUMN_NAMES.joinToString(", ")
    }
}
