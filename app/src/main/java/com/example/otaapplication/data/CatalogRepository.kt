package com.example.otaapplication.data

import android.content.ContentValues
import android.database.Cursor
import java.util.UUID

/** Buyer and supplier catalog. All calls hit the database; run them off the main thread. */
class CatalogRepository(
    private val helper: OtaDatabase,
    private val clock: () -> Long = System::currentTimeMillis
) {

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
        val now = clock()
        val values = ContentValues().apply {
            put("name", clean.name)
            put("name_key", SearchText.normalize(clean.name))
            put("address", clean.address)
            put("city", clean.city)
            put("phone", clean.phone)
            put("gstin", clean.gstin)
            put("bank_account_name", clean.bank.accountName)
            put("bank_name", clean.bank.bankName)
            put("bank_account_no", clean.bank.accountNo)
            put("bank_ifsc", clean.bank.ifsc)
            put("bank_branch", clean.bank.branch)
            put("bank_upi", clean.bank.upi)
            put(
                "search_key",
                SearchText.normalize(
                    listOf(
                        clean.name, clean.city, clean.phone, clean.gstin, clean.address,
                        clean.bank.accountName, clean.bank.bankName, clean.bank.accountNo, clean.bank.ifsc, clean.bank.upi
                    ).joinToString(" ")
                )
            )
            put("updated_at", now)
            put("sync_pending", 1)
        }
        val db = helper.writableDatabase
        return if (clean.id.isBlank()) {
            val id = UUID.randomUUID().toString()
            values.put("id", id)
            values.put("created_at", now)
            values.put("is_deleted", 0)
            db.insertOrThrow(clean.type.table, null, values)
            clean.copy(id = id)
        } else {
            db.update(clean.type.table, values, "id = ?", arrayOf(clean.id))
            clean
        }
    }

    fun delete(type: PartyType, id: String) {
        val values = ContentValues().apply {
            put("is_deleted", 1)
            put("updated_at", clock())
            put("sync_pending", 1)
        }
        helper.writableDatabase.update(type.table, values, "id = ?", arrayOf(id))
    }

    private fun tokenFilter(query: String): Pair<String, Array<String>> {
        val tokens = SearchText.tokens(query)
        val where = tokens.joinToString("") { " AND search_key LIKE ? ESCAPE '\\'" }
        return where to tokens.map(SearchText::containsPattern).toTypedArray()
    }

    private fun Cursor.toParties(type: PartyType): List<Party> {
        val out = ArrayList<Party>(count)
        while (moveToNext()) {
            out += Party(
                id = getString(0), type = type, name = getString(1), address = getString(2),
                city = getString(3), phone = getString(4), gstin = getString(5),
                bank = BankDetails(
                    accountName = getString(6), bankName = getString(7), accountNo = getString(8),
                    ifsc = getString(9), branch = getString(10), upi = getString(11)
                )
            )
        }
        return out
    }

    private companion object {
        val COLUMNS = "id, name, address, city, phone, gstin, " + OtaDatabase.BANK_COLUMN_NAMES.joinToString(", ")
    }
}
