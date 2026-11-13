package com.ota.desktop

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.data.OtaAccounts
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Manual check against the real cloud, as the test account only:
 * `OTA_LIVE_HOME=/tmp/x OTA_PW=… gradlew liveCheck --args="signin|sync|list|create <buyer>|delete <order no>"`.
 * A throwaway laptop (its own data folder) running the real sync engine, to watch deletes and order
 * numbers travel between it and a phone. Every command ends with a sync round and the order list.
 */
fun main(args: Array<String>) {
    val app = AppContext.open()
    var code = 0
    try {
        runBlocking {
            when (args.getOrNull(0)) {
                "signin" -> app.signIn(OtaAccounts.TEST_EMAIL, System.getenv("OTA_PW") ?: error("OTA_PW not set"))
                "sync", "list" -> Unit
                "create" -> {
                    require(app.engine.sync().error == null) { "sync failed" }
                    val date = LocalDate.now()
                    val number = app.engine.allocateOrderNo(date).orEmpty()
                    val row = app.orders.create(
                        OrderFormData(
                            date = date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")), buyer = args[1], supplier = "Live Check Mills",
                            items = listOf(OrderItem(serialNo = "1", particulars = "Live check", quantity = "5", rate = "60", unit = "M"))
                        ),
                        number
                    )
                    println("created ${row.orderNo.ifBlank { "(pending number)" }} for ${args[1]}")
                }
                "delete" -> {
                    val row = app.orders.list().firstOrNull { it.orderNo == args[1] } ?: error("no order ${args[1]} on this laptop")
                    app.orders.delete(row.id)
                    println("deleted ${args[1]} on the laptop")
                }
                else -> error("unknown command ${args.getOrNull(0)}")
            }
            check(app.auth.session.value?.email == OtaAccounts.TEST_EMAIL) { "only for the test account" }
            val status = app.engine.sync()
            println("sync: ${status.error ?: "ok"}, pending ${status.pending}")
            if (status.error != null) code = 1
            val orders = app.orders.list()
            println("laptop orders (${orders.size}): " + orders.joinToString { "${it.orderNo.ifBlank { "pending" }} ${it.data.buyer}" })
        }
    } catch (e: Throwable) {
        System.err.println("failed: $e")
        code = 1
    } finally {
        app.close()
    }
    exitProcess(code)
}
