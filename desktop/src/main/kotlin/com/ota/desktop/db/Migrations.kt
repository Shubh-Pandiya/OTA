package com.ota.desktop.db

/**
 * Runs the bundled SQL migrations in order, once each. All pending ones run in a single
 * transaction (PostgreSQL DDL is transactional): either the database reaches the new version or it
 * stays exactly as it was. A database already beyond this app's migrations is refused untouched.
 */
object Migrations {
    private val files = listOf("V1__init.sql", "V2__bill_lines_claims_parties.sql", "V3__bill_transport.sql", "V4__accounts.sql", "V5__hard_deletes.sql")

    class SchemaTooNew(val found: Int, val supported: Int) : IllegalStateException("Database version $found is newer than this app ($supported)")

    fun run(db: Db) = run(db, files.map { name -> name to { Migrations::class.java.getResource("/db/$name")?.readText() ?: error("Missing migration $name") } })

    /** [steps]: (name, SQL) in version order; version = position + 1. */
    internal fun run(db: Db, steps: List<Pair<String, () -> String>>) {
        db.tx { c ->
            c.exec("create table if not exists schema_version (version integer primary key, applied_at timestamptz not null default now())")
            // Two windows starting at once must not both migrate.
            c.exec("lock table schema_version in exclusive mode")
            val done = c.query("select version from schema_version") { it.getInt(1) }.toSet()
            done.maxOrNull()?.let { if (it > steps.size) throw SchemaTooNew(it, steps.size) }
            steps.forEachIndexed { i, (_, sql) ->
                val version = i + 1
                if (version in done) return@forEachIndexed
                c.exec(sql())
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
