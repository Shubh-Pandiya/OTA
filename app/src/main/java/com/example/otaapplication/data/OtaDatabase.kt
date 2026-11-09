package com.example.otaapplication.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-first store for the catalog, bills, payment advices and the order search index.
 *
 * Every synced table follows the same contract so it can later be mirrored to the cloud:
 * UUID text primary key, `created_at`/`updated_at` (epoch millis), a soft-delete flag
 * (`is_deleted`) and a dirty marker (`sync_pending` = 1 until pushed to the server).
 * Orders themselves remain stored as the existing JSON/PDF files; `orders` is an index of them.
 */
class OtaDatabase(context: Context, name: String? = DB_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (party in PartyType.entries) {
            db.execSQL(
                """
                CREATE TABLE ${party.table} (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    name_key TEXT NOT NULL,
                    address TEXT NOT NULL DEFAULT '',
                    city TEXT NOT NULL DEFAULT '',
                    phone TEXT NOT NULL DEFAULT '',
                    gstin TEXT NOT NULL DEFAULT '',
                    $BANK_COLUMNS,
                    search_key TEXT NOT NULL DEFAULT '',
                    $SYNC_COLUMNS
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_${party.table}_name ON ${party.table}(is_deleted, name_key)")
        }
        db.execSQL(
            """
            CREATE TABLE orders (
                id TEXT PRIMARY KEY NOT NULL,
                uuid TEXT,
                offer_no TEXT NOT NULL,
                order_date TEXT NOT NULL,
                day_key INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                buyer TEXT NOT NULL DEFAULT '',
                supplier TEXT NOT NULL DEFAULT '',
                particulars TEXT NOT NULL DEFAULT '',
                json_path TEXT NOT NULL DEFAULT '',
                pdf_file_name TEXT,
                search_text TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                $SYNC_COLUMNS
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_orders_day ON orders(is_deleted, day_key, seq)")
        db.execSQL(
            """
            CREATE TABLE bills (
                id TEXT PRIMARY KEY NOT NULL,
                bill_no TEXT NOT NULL,
                bill_key TEXT NOT NULL UNIQUE,
                bill_date TEXT,
                amount REAL,
                lr_no TEXT NOT NULL DEFAULT '',
                cd_percent REAL,
                cd_amount REAL,
                $SYNC_COLUMNS
            )
            """.trimIndent()
        )
        // Many-to-many: an order can have several bills and a bill can cover several orders.
        db.execSQL(
            """
            CREATE TABLE order_bills (
                id TEXT PRIMARY KEY NOT NULL,
                order_id TEXT NOT NULL REFERENCES orders(id),
                bill_id TEXT NOT NULL REFERENCES bills(id),
                $SYNC_COLUMNS,
                UNIQUE(order_id, bill_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_order_bills_bill ON order_bills(bill_id)")
        createPaymentTables(db)
        addBillLinesClaimsImages(db)
        addBillParties(db)
        addBillTransport(db)
    }

    /** v7: transport company on a bill (the supplier may send goods by another transport). */
    private fun addBillTransport(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN transport TEXT NOT NULL DEFAULT ''")
    }

    /** v6: buyer and supplier stored on a bill, for bills added without orders. */
    private fun addBillParties(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN buyer TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE bills ADD COLUMN supplier TEXT NOT NULL DEFAULT ''")
    }

    /**
     * v5: bill item lines (bills.items, the compact JSON used for order items), the claim /
     * defect deduction on advice lines, and advice images. An advice lists its image ids in
     * page order; the files live in [AdviceImageFiles] and never leave this phone.
     */
    private fun addBillLinesClaimsImages(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE bills ADD COLUMN items TEXT")
        db.execSQL("ALTER TABLE advice_lines ADD COLUMN claim REAL")
        db.execSQL("ALTER TABLE advice_lines ADD COLUMN claim_note TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE payment_advices ADD COLUMN images TEXT NOT NULL DEFAULT ''")
    }

    /**
     * v4: bill details (LR number, cash discount) and payment advices. An advice is one buyer
     * paying one supplier; its lines say how much of each bill it settles (many-to-many).
     */
    private fun createPaymentTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE payment_advices (
                id TEXT PRIMARY KEY NOT NULL,
                advice_no TEXT NOT NULL,
                pay_date TEXT,
                buyer TEXT NOT NULL DEFAULT '',
                supplier TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL DEFAULT 'CASH',
                reference TEXT NOT NULL DEFAULT '',
                balance_amount REAL,
                balance_side TEXT,
                remarks TEXT NOT NULL DEFAULT '',
                search_text TEXT NOT NULL DEFAULT '',
                $SYNC_COLUMNS
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_advices_date ON payment_advices(is_deleted, pay_date)")
        db.execSQL(
            """
            CREATE TABLE advice_lines (
                id TEXT PRIMARY KEY NOT NULL,
                advice_id TEXT NOT NULL,
                bill_id TEXT NOT NULL,
                paid REAL NOT NULL DEFAULT 0,
                cd_percent REAL,
                cd_amount REAL,
                goods_return REAL,
                rate_diff REAL,
                $SYNC_COLUMNS,
                UNIQUE(advice_id, bill_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_advice_lines_bill ON advice_lines(bill_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2: bank details on buyers and suppliers.
            for (party in PartyType.entries) {
                for (column in BANK_COLUMN_NAMES) {
                    db.execSQL("ALTER TABLE ${party.table} ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
                }
            }
        }
        if (oldVersion < 3) rekeyBills(db)
        if (oldVersion < 4) {
            val hasBills = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'bills'", null).use { it.moveToFirst() }
            if (hasBills) {
                db.execSQL("ALTER TABLE bills ADD COLUMN lr_no TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bills ADD COLUMN cd_percent REAL")
                db.execSQL("ALTER TABLE bills ADD COLUMN cd_amount REAL")
            }
            createPaymentTables(db)
        }
        if (oldVersion < 5) addBillLinesClaimsImages(db)
        if (oldVersion < 6) addBillParties(db)
        if (oldVersion < 7) addBillTransport(db)
    }

    /**
     * v3: bills and bill links get ids derived from their content ([SyncIds]) so the same bill
     * entered on two phones syncs as one row. Everything re-keyed is marked for upload.
     */
    private fun rekeyBills(db: SQLiteDatabase) {
        val hasBillTables = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('bills', 'order_bills')", null
        ).use { it.moveToFirst() && it.getInt(0) == 2 }
        if (!hasBillTables) return
        val links = db.rawQuery("SELECT order_id, bill_id, created_at, updated_at, is_deleted FROM order_bills", null).use { c ->
            buildList { while (c.moveToNext()) add(ContentValues().apply {
                put("order_id", c.getString(0)); put("bill_id", c.getString(1)); put("created_at", c.getLong(2))
                put("updated_at", c.getLong(3)); put("is_deleted", c.getInt(4)); put("sync_pending", 1)
            }) }
        }
        db.delete("order_bills", null, null)
        val newIds = HashMap<String, String>()
        db.rawQuery("SELECT id, bill_key FROM bills", null).use { c ->
            while (c.moveToNext()) newIds[c.getString(0)] = SyncIds.bill(c.getString(1))
        }
        for ((oldId, newId) in newIds) {
            db.execSQL("UPDATE bills SET id = ?, sync_pending = 1 WHERE id = ?", arrayOf(newId, oldId))
        }
        for (link in links) {
            val billId = newIds[link.getAsString("bill_id")] ?: continue
            link.put("bill_id", billId)
            link.put("id", SyncIds.link(link.getAsString("order_id"), billId))
            db.insertWithOnConflict("order_bills", null, link, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    companion object {
        const val DB_NAME = "ota_local.db"
        const val VERSION = 7
        val BANK_COLUMN_NAMES = listOf(
            "bank_account_name", "bank_name", "bank_account_no", "bank_ifsc", "bank_branch", "bank_upi"
        )
        private val BANK_COLUMNS = BANK_COLUMN_NAMES.joinToString(",\n") { "$it TEXT NOT NULL DEFAULT ''" }
        private const val SYNC_COLUMNS = """
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                sync_pending INTEGER NOT NULL DEFAULT 1"""

        @Volatile private var instance: OtaDatabase? = null

        fun get(context: Context): OtaDatabase =
            instance ?: synchronized(this) { instance ?: OtaDatabase(context).also { instance = it } }
    }
}
