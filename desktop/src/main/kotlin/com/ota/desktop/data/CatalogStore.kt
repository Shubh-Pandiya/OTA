package com.ota.desktop.data

import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.LocalSyncMeta
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PendingParty
import com.example.otaapplication.data.RemoteParty
import com.example.otaapplication.data.SearchText
import com.example.otaapplication.data.SyncMerge
import com.ota.desktop.db.Db
import com.ota.desktop.db.nextEditTime
import com.ota.desktop.db.one
import com.ota.desktop.db.query
import com.ota.desktop.db.str
import com.ota.desktop.db.update
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Buyers and suppliers. Same rules as the phone: soft delete, last write wins by edit time. */
class CatalogStore(private val db: Db, private val onChange: () -> Unit = {}) {

    fun list(type: PartyType, query: String = ""): List<Party> {
        val (where, args) = tokenFilter(query)
        return db.read { c -> c.query("select $COLUMNS from ${type.table} where not deleted$where order by lower(name)", *args) { it.toParty(type) } }
    }

    /** Type-ahead: names starting with the typed text first. */
    fun suggest(type: PartyType, typed: String, limit: Int = 8): List<Party> {
        val first = SearchText.normalize(SearchText.firstLine(typed))
        if (first.isEmpty()) return emptyList()
        val (where, args) = tokenFilter(first)
        return db.read { c ->
            c.query(
                "select $COLUMNS from ${type.table} where not deleted$where " +
                    "order by case when lower(name) like ? escape '\\' then 0 else 1 end, lower(name) limit $limit",
                *args, SearchText.prefixPattern(first)
            ) { it.toParty(type) }
        }
    }

    fun findByName(type: PartyType, name: String, excludingId: String? = null): Party? = db.read { c ->
        c.one(
            "select $COLUMNS from ${type.table} where not deleted and lower(regexp_replace(btrim(name), '\\s+', ' ', 'g')) = ? and id::text <> ?",
            SearchText.normalize(name), excludingId.orEmpty()
        ) { it.toParty(type) }
    }

    fun count(type: PartyType): Int = db.read { c -> c.one("select count(*) from ${type.table} where not deleted") { it.getInt(1) } ?: 0 }

    fun save(party: Party): Party {
        val clean = party.copy(
            name = party.name.trim(), address = party.address.trim(), city = party.city.trim(),
            phone = party.phone.trim(), gstin = party.gstin.trim().uppercase(), bank = party.bank.cleaned()
        )
        require(clean.name.isNotEmpty()) { "Name is required" }
        val saved = db.tx { c ->
            val b = clean.bank
            if (clean.id.isBlank()) {
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                c.update(
                    "insert into ${clean.type.table} (id, name, address, city, phone, gstin, bank_account_name, bank_name, bank_account_no, " +
                        "bank_ifsc, bank_branch, bank_upi, deleted, created_at, client_updated_at, sync_pending) " +
                        "values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, true)",
                    id, clean.name, clean.address, clean.city, clean.phone, clean.gstin,
                    b.accountName, b.bankName, b.accountNo, b.ifsc, b.branch, b.upi, now, now
                )
                clean.copy(id = id)
            } else {
                c.update(
                    "update ${clean.type.table} set name = ?, address = ?, city = ?, phone = ?, gstin = ?, bank_account_name = ?, bank_name = ?, " +
                        "bank_account_no = ?, bank_ifsc = ?, bank_branch = ?, bank_upi = ?, client_updated_at = ?, sync_pending = true where id = ?::uuid",
                    clean.name, clean.address, clean.city, clean.phone, clean.gstin,
                    b.accountName, b.bankName, b.accountNo, b.ifsc, b.branch, b.upi, editTime(c, clean.type, clean.id), clean.id
                )
                clean
            }
        }
        onChange()
        return saved
    }

    fun delete(type: PartyType, id: String) {
        db.tx { c -> c.update("update ${type.table} set deleted = true, client_updated_at = ?, sync_pending = true where id = ?::uuid", editTime(c, type, id), id) }
        onChange()
    }

    // ------------------------------------------------------------------ sync

    fun pendingCount(): Int = db.read { c ->
        PartyType.entries.sumOf { t -> c.one("select count(*) from ${t.table} where sync_pending") { it.getInt(1) } ?: 0 }
    }

    fun pendingRows(type: PartyType, limit: Int): List<PendingParty> = db.read { c ->
        c.query("select $COLUMNS, deleted, created_at, client_updated_at from ${type.table} where sync_pending order by client_updated_at limit $limit") {
            PendingParty(it.toParty(type), it.getBoolean("deleted"), it.getLong("created_at"), it.getLong("client_updated_at"))
        }
    }

    /** Applies pulled rows; returns how many changed locally. The desktop keeps deleted rows too. */
    fun mergePulled(type: PartyType, rows: List<RemoteParty>): Int = db.tx { c ->
        rows.count { remote ->
            val local = meta(c, type, remote.party.id)
            when (SyncMerge.onPulled(local, remote)) {
                SyncMerge.Decision.APPLY_REMOTE, SyncMerge.Decision.SKIP -> writeRemote(c, type, remote)
                else -> false
            }
        }
    }.also { if (it > 0) onChange() }

    fun applyPushResult(type: PartyType, sent: List<PendingParty>, returned: List<RemoteParty>): Int = db.tx { c ->
        val sentAt = sent.associate { it.party.id to it.updatedAt }
        returned.count { remote ->
            val sentUpdatedAt = sentAt[remote.party.id] ?: return@count false
            when (SyncMerge.onPushed(sentUpdatedAt, meta(c, type, remote.party.id), remote)) {
                SyncMerge.Decision.MARK_SYNCED -> c.update(
                    "update ${type.table} set sync_pending = false, server_updated_at = ? where id = ?::uuid", remote.serverUpdatedAt, remote.party.id
                ) > 0
                SyncMerge.Decision.APPLY_REMOTE -> writeRemote(c, type, remote)
                else -> false
            }
        }
    }.also { if (it > 0) onChange() }

    private fun meta(c: Connection, type: PartyType, id: String): LocalSyncMeta? =
        c.one("select client_updated_at, sync_pending from ${type.table} where id = ?::uuid", id) { LocalSyncMeta(it.getLong(1), it.getBoolean(2)) }

    private fun writeRemote(c: Connection, type: PartyType, r: RemoteParty): Boolean {
        val p = r.party
        val b = p.bank
        return c.update(
            "insert into ${type.table} (id, name, address, city, phone, gstin, bank_account_name, bank_name, bank_account_no, bank_ifsc, " +
                "bank_branch, bank_upi, deleted, created_at, client_updated_at, server_updated_at, sync_pending) " +
                "values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false) on conflict (id) do update set " +
                "name = excluded.name, address = excluded.address, city = excluded.city, phone = excluded.phone, gstin = excluded.gstin, " +
                "bank_account_name = excluded.bank_account_name, bank_name = excluded.bank_name, bank_account_no = excluded.bank_account_no, " +
                "bank_ifsc = excluded.bank_ifsc, bank_branch = excluded.bank_branch, bank_upi = excluded.bank_upi, deleted = excluded.deleted, " +
                "created_at = excluded.created_at, client_updated_at = excluded.client_updated_at, server_updated_at = excluded.server_updated_at, " +
                "sync_pending = false where ${type.table}.server_updated_at is distinct from excluded.server_updated_at or ${type.table}.sync_pending",
            p.id, p.name.ifBlank { "?" }, p.address, p.city, p.phone, p.gstin, b.accountName, b.bankName, b.accountNo, b.ifsc, b.branch, b.upi,
            r.deleted, r.createdAt, r.clientUpdatedAt, r.serverUpdatedAt
        ) > 0
    }

    private fun editTime(c: Connection, type: PartyType, id: String): Long =
        nextEditTime(c.one("select client_updated_at from ${type.table} where id = ?::uuid", id) { it.getLong(1) })

    private fun tokenFilter(query: String): Pair<String, Array<Any?>> {
        val tokens = SearchText.tokens(query)
        if (tokens.isEmpty()) return "" to emptyArray()
        val clause = tokens.joinToString("") { " and lower(name || ' ' || city || ' ' || phone || ' ' || gstin || ' ' || address) like ? escape '\\'" }
        return clause to tokens.map { SearchText.containsPattern(it) }.toTypedArray()
    }

    companion object {
        const val COLUMNS = "id, name, address, city, phone, gstin, bank_account_name, bank_name, bank_account_no, bank_ifsc, bank_branch, bank_upi"

        fun ResultSet.toParty(type: PartyType) = Party(
            id = getObject("id").toString(), type = type, name = str("name"), address = str("address"), city = str("city"),
            phone = str("phone"), gstin = str("gstin"),
            bank = BankDetails(str("bank_account_name"), str("bank_name"), str("bank_account_no"), str("bank_ifsc"), str("bank_branch"), str("bank_upi"))
        )
    }
}
