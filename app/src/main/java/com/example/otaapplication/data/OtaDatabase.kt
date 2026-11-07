package com.example.otaapplication.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-first store for the catalog, bills and the order search index.
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val DB_NAME = "ota_local.db"
        private const val VERSION = 1
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
