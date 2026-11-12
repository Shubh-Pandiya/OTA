package com.ota.desktop

import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.one
import com.ota.desktop.update.DataSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * CI's update test: `gradlew e2eData --args="seed|seed-accounts|snapshot <out>|duplicates"` over the installed
 * app's data folder (or `OTA_E2E_HOME`). `seed` fills it with realistic work, part synced and part
 * still waiting to upload, including an order without its number; `snapshot` writes every table's
 * row count and checksum in the self-test's `table <name> <count> <md5>` format; `duplicates` fails
 * on any repeated id or number. `seed-accounts` (1.4.0+ schema) adds supplier commission and vouchers.
 * Uses the schema the installed version created (no migrations).
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0)
    val home = System.getenv("OTA_E2E_HOME")?.let(::File) ?: AppPaths.home
    var code = 0
    val bin = Files.createTempDirectory("ota-e2e-bin").toFile()
    EmbeddedDb.start(File(home, "pgdata"), bin, File(home, "logs/e2e-postgres.log").apply { parentFile.mkdirs() }).use { server ->
        Db(server::connect).use { db ->
            when (mode) {
                "seed" -> {
                    val demo = DemoData(db).seed()
                    demo.orders.create(demo.orders.list().first().data.copy(remarks = "Saved offline, waiting for its number"), "")
                    db.read { c ->
                        c.createStatement().use { st ->
                            // Most of it was uploaded long ago; the newest work has not been yet.
                            for (t in listOf("orders", "bills", "order_bills", "payment_advices", "advice_lines")) {
                                st.execute(
                                    "update $t set sync_pending = false, server_updated_at = '2026-10-01T10:00:00.000000Z' " +
                                        "where id in (select id from $t where not deleted order by client_updated_at, id limit (select count(*) * 2 / 3 from $t))" +
                                        if (t == "orders") " and order_no <> ''" else ""
                                )
                            }
                        }
                    }
                    println("seeded: " + DataSnapshot.take(db).filterKeys { it != "kv" })
                }
                "seed-accounts" -> {
                    val books = DemoData(db).seedAccounts()
                    println("accounts: ${books.accounts().size}, vouchers: ${books.vouchers().size}")
                }
                "snapshot" -> {
                    val text = DataSnapshot.take(db).entries.joinToString("") { (t, s) -> "table $t $s\n" }
                    File(args.getOrNull(1) ?: error("snapshot needs an output file")).writeText(text)
                    print(text)
                }
                "duplicates" -> {
                    val n = duplicates(db)
                    println("duplicates $n")
                    if (n > 0) code = 1
                }
                else -> error("unknown mode $mode")
            }
        }
    }
    bin.deleteRecursively()
    exitProcess(code)
}

/** Repeated ids or numbers across the synced tables and, on 1.4.0+ data, the local books. */
fun duplicates(db: Db): Int {
    return db.read { c ->
        val books = c.one("select count(*) from information_schema.tables where table_name = 'acc_vouchers'") { it.getInt(1) } == 1
        val keys = listOf("orders" to "order_no", "payment_advices" to "advice_no", "orders" to "id", "bills" to "id", "payment_advices" to "id") +
            // One commission voucher per bill, and voucher numbers never repeat.
            (if (books) listOf("acc_vouchers" to "voucher_no", "acc_vouchers" to "source_bill_id", "acc_accounts" to "party_type || ':' || party_key") else emptyList())
        keys.sumOf { (t, col) ->
            c.one("select count(*) from (select $col from $t where ($col) is not null and ($col)::text <> '' group by $col having count(*) > 1) d") { it.getInt(1) } ?: 0
        }
    }
}
