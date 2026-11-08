package com.ota.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.otaapplication.cloud.CloudUsage
import com.ota.desktop.data.BillFilter
import com.ota.desktop.data.AdviceFilter
import com.ota.desktop.db.Kv
import com.ota.desktop.ui.AppShell
import com.ota.desktop.ui.LocalApp
import com.ota.desktop.ui.Navigator
import com.ota.desktop.ui.OtaTheme
import com.ota.desktop.ui.Section
import com.ota.desktop.ui.SignInScreen
import com.ota.desktop.ui.Target
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.system.exitProcess

/**
 * Renders the real screens over the demo data set to PNGs (README / release screenshots).
 * Run with `./gradlew screenshots`; files go to build/screenshots.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    val db = TestDb.fresh()
    val demo = DemoData(db).seed()
    Kv(db).apply {
        this["staff.code"] = "1"
        this["staff.name"] = "Shubham"
        this["staff.admin"] = "true"
        this["sync.last"] = System.currentTimeMillis().minus(4 * 60_000).toString()
        this["maintenance.last"] = System.currentTimeMillis().minus(3 * 3_600_000).toString()
        this["maintenance.summary"] = "Archived 38 rows, purged 21 orders and 9 advices older than 30 days"
        this["cloud.usage"] = CloudUsage(61L * 1_048_576, 500, 80, 30, 12.2).toJson()
    }
    val app = AppContext(TestDb.server, db)

    val bill = demo.payments.bills(BillFilter()).first { it.buyer != null }.bill
    val advice = demo.payments.search(AdviceFilter()).first()
    val order = demo.orders.list().first { it.billNos.isNotEmpty() }

    fun shot(name: String, nav: Navigator? = null, content: (@Composable () -> Unit)? = null) {
        val scene = ImageComposeScene(1600, 1000, Density(1.25f)) {
            OtaTheme {
                CompositionLocalProvider(LocalApp provides app) {
                    if (content != null) content() else AppShell(app, nav!!)
                }
            }
        }
        // Screens load on IO and animate in: keep rendering until things settle.
        var t = 0L
        repeat(40) { scene.render(t); Thread.sleep(60); t += 100_000_000L }
        val png = scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes
        File(out, "$name.png").writeBytes(png)
        scene.close()
        println("wrote $name.png")
    }

    fun at(section: Section, target: Target? = null) = Navigator().apply { go(section, target) }

    shot("01-sign-in") { SignInScreen(app) }
    shot("02-dashboard", at(Section.DASHBOARD))
    shot("03-orders", at(Section.ORDERS, Target.Order(order.id)))
    shot("04-order-editor", at(Section.ORDERS, Target.NewOrder))
    shot("05-bills", at(Section.BILLS, Target.Bill(bill.id)))
    shot("06-payment-advices", at(Section.ADVICES, Target.Advice(advice.id)))
    shot("07-advice-editor", at(Section.ADVICES, Target.NewAdvice(bill.id)))
    shot("08-buyers-suppliers", at(Section.CATALOG))
    shot("09-sync-cloud", at(Section.SYNC))
    shot("10-export", at(Section.EXPORT))
    app.close()
    exitProcess(0)
}
