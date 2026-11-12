package com.ota.desktop

import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.one
import com.ota.desktop.update.DataSnapshot
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * CI's update test: `gradlew e2eData --args="seed|snapshot <out>|duplicates"` over the installed
 * app's data folder (or `OTA_E2E_HOME`). `seed` fills it with realistic work, part synced and part
 * still waiting to upload, including an order without its number; `snapshot` writes every table's
 * row count and checksum in the self-test's `table <name> <count> <md5>` format; `duplicates` fails
 * on any repeated id or number. Uses the schema the installed version created (no migrations).
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
                "snapshot" -> {
                    val text = DataSnapshot.take(db).entries.joinToString("") { (t, s) -> "table $t $s\n" }
                    File(args.getOrNull(1) ?: error("snapshot needs an output file")).writeText(text)
                    print(text)
                }
                "duplicates" -> {
                    val n = db.read { c ->
                        listOf("orders" to "order_no", "payment_advices" to "advice_no", "orders" to "id", "bills" to "id", "payment_advices" to "id").sumOf { (t, col) ->
                            c.one("select count(*) from (select $col from $t where $col::text <> '' group by $col having count(*) > 1) d") { it.getInt(1) } ?: 0
                        }
                    }
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
