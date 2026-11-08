package com.ota.desktop.db

/** Runs the bundled SQL migrations in order, once each, inside a transaction. */
object Migrations {
    private val files = listOf("V1__init.sql")

    fun run(db: Db) {
        db.tx { c ->
            c.exec("create table if not exists schema_version (version integer primary key, applied_at timestamptz not null default now())")
            // Two windows starting at once must not both migrate.
            c.exec("lock table schema_version in exclusive mode")
            val done = c.query("select version from schema_version") { it.getInt(1) }.toSet()
            files.forEachIndexed { i, name ->
                val version = i + 1
                if (version in done) return@forEachIndexed
                val sql = Migrations::class.java.getResource("/db/$name")?.readText() ?: error("Missing migration $name")
                c.exec(sql)
                c.update("insert into schema_version (version) values (?)", version)
            }
        }
    }

    val latest: Int get() = files.size
}

/** Small settings store (cursors, counters, session, preferences) in the local database. */
class Kv(private val db: Db) {
    operator fun get(key: String): String? = db.read { c -> c.one("select value from kv where key = ?", key) { it.getString(1) } }

    operator fun set(key: String, value: String?) {
        db.read { c ->
            if (value == null) c.update("delete from kv where key = ?", key)
            else c.update("insert into kv (key, value) values (?, ?) on conflict (key) do update set value = excluded.value", key, value)
        }
    }

    fun long(key: String): Long? = get(key)?.toLongOrNull()
    fun int(key: String): Int? = get(key)?.toIntOrNull()
    fun bool(key: String): Boolean = get(key) == "true"

    fun remove(vararg keys: String) = db.read { c -> keys.forEach { c.update("delete from kv where key = ?", it) } }
}
