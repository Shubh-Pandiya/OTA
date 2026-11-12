package com.ota.desktop

import com.ota.desktop.accounts.AccountsExport
import com.ota.desktop.accounts.PartyKind
import com.ota.desktop.accounts.ReportKind
import com.ota.desktop.accounts.VType
import com.ota.desktop.ui.AccTab
import java.time.LocalDate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.otaapplication.cloud.CloudUsage
import com.example.otaapplication.weather.Place
import com.example.otaapplication.weather.WeatherNow
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
import com.ota.desktop.ui.UpdateDialog
import com.ota.desktop.ui.UpdatingScreen
import com.ota.desktop.update.AppVersion
import com.ota.desktop.update.Installer
import com.ota.desktop.update.Release
import com.ota.desktop.update.UpdateState
import com.ota.desktop.update.UpdateStep

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    val db = TestDb.fresh()
    val demo = DemoData(db).seed()
    val books = demo.seedAccounts()
    Kv(db).apply {
        this["staff.code"] = "1"
        this["staff.name"] = "Shubham"
        this["staff.admin"] = "true"
        this["sync.last"] = System.currentTimeMillis().minus(4 * 60_000).toString()
        this["maintenance.last"] = System.currentTimeMillis().minus(3 * 3_600_000).toString()
        this["maintenance.summary"] = "Archived 38 rows, purged 21 orders and 9 advices older than 30 days"
        this["cloud.usage"] = CloudUsage(61L * 1_048_576, 500, 80, 30, 12.2).toJson()
        // A fixed city with a fresh forecast, so Home renders the weather without the network.
        val surat = Place("Surat, Gujarat", 21.17, 72.83)
        this["weather.city"] = surat.toJson().toString()
        this["weather.now"] = WeatherNow(surat.name, 31.4, 1, true, 34.0, 22.0, surat.latitude, surat.longitude, System.currentTimeMillis() - 12 * 60_000).toJson().toString()
    }
    val app = AppContext(TestDb.server, db)

    val bill = demo.payments.bills(BillFilter()).first { it.buyer != null }.bill
    val advice = demo.payments.search(AdviceFilter()).first()
    val order = demo.orders.list().first { it.billNos.isNotEmpty() }
    val handBill = demo.orders.billByNumber("RL/8812")!!
    val splitBill = demo.orders.billByNumber("RL/8815")!!
    val twoBillAdvice = demo.payments.search(AdviceFilter()).first { a -> demo.payments.advice(a.id)!!.lines.size >= 2 }
    val claimAdvice = demo.payments.search(AdviceFilter()).first { a -> demo.payments.advice(a.id)!!.lines.any { it.amounts.claim != null } }
    // One order saved while offline, waiting for its number.
    val pending = demo.orders.create(demo.orders.list().first().data.copy(remarks = "Saved offline"), "")

    fun shot(name: String, nav: Navigator? = null, height: Int = 1000, content: (@Composable () -> Unit)? = null) {
        val scene = ImageComposeScene(1600, height, Density(1.25f)) {
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
    shot("00-home", at(Section.HOME))
    shot("02-insights", at(Section.DASHBOARD))
    shot("02b-insights-full", at(Section.DASHBOARD), height = 3000)
    shot("03-orders", at(Section.ORDERS, Target.Order(order.id)))
    shot("04-order-editor", at(Section.ORDERS, Target.NewOrder))
    shot("04b-order-pending-number", at(Section.ORDERS, Target.Order(pending.id)))
    shot("05-bills", at(Section.BILLS, Target.Bill(bill.id)))
    shot("05b-bill-entered-by-hand", at(Section.BILLS, Target.Bill(handBill.id)))
    shot("05c-add-bill", at(Section.BILLS, Target.NewBill))
    shot("05d-bill-in-two-advices", at(Section.BILLS, Target.Bill(splitBill.id)), height = 1250)
    shot("06-payment-advices", at(Section.ADVICES, Target.Advice(advice.id)))
    shot("06b-advice-with-claim", at(Section.ADVICES, Target.Advice(claimAdvice.id)))
    shot("06c-advice-with-two-bills", at(Section.ADVICES, Target.Advice(twoBillAdvice.id)))
    shot("07-advice-editor", at(Section.ADVICES, Target.NewAdvice(bill.id)))
    shot("08-buyers-suppliers", at(Section.CATALOG))
    shot("09-sync-cloud", at(Section.SYNC))
    shot("10-export", at(Section.EXPORT))

    fun acc(tab: AccTab, accountId: String? = null, report: ReportKind? = null, voucherId: String? = null) =
        at(Section.ACCOUNTS, Target.Accounts(tab, accountId, report, voucherId))
    val vouchers = books.vouchers()
    val payment = vouchers.first { it.type == VType.PAYMENT && it.narration.startsWith("Electricity") }
    val commission = vouchers.first { it.type == VType.COMMISSION }
    val raymond = books.partyAccount(PartyKind.SUPPLIER, "Raymond Ltd")!!
    shot("20-accounts-vouchers", acc(AccTab.VOUCHERS))
    shot("20b-accounts-payment-voucher", acc(AccTab.VOUCHERS, voucherId = payment.id))
    shot("20d-accounts-commission-voucher", acc(AccTab.VOUCHERS, voucherId = commission.id))
    shot("21-accounts-supplier-ledger", acc(AccTab.LEDGERS, raymond.id))
    shot("21b-accounts-bank-ledger", acc(AccTab.LEDGERS, books.accountBySystemKey("bank")!!.id))
    shot("21c-accounts-ledger-picker", acc(AccTab.LEDGERS))
    ReportKind.entries.forEachIndexed { i, k ->
        shot("22${'a' + i}-report-${k.name.lowercase().replace('_', '-')}", acc(AccTab.REPORTS, report = k), height = 1150)
    }
    shot("23-accounts-parties-commission", acc(AccTab.PARTIES))
    shot("24-accounts-chart", acc(AccTab.CHART))
    shot("25-accounts-audit", acc(AccTab.AUDIT))
    File(out, "exports").mkdirs()
    val reports = ReportKind.entries.map { k -> if (k.asOf) app.accountReports.report(k, null, LocalDate.now()) else app.accountReports.report(k, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)) }
    AccountsExport.xlsx(File(out, "exports/OTA accounts.xlsx"), reports)
    reports.forEach { AccountsExport.pdf(File(out, "exports/${it.title.replace(Regex("[^A-Za-z0-9 &]"), "").trim()}.pdf"), it) }
    AccountsExport.pdf(File(out, "exports/Ledger Raymond Ltd.pdf"), app.accountReports.ledger(raymond.id, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)))

    val notes = """
        ## What's new
        - **Automatic updates**: the app tells you when a new version is out and installs it for you.
        - A backup of all your data is taken before every update (Sync & cloud → Updates).
        - Unsynced work is uploaded first, or kept safely on this laptop when you are offline.

        Coming from 1.2.1? Install this version once by hand; later versions update themselves.
    """.trimIndent()
    val release = Release(AppVersion.parse("1.3.0")!!, "desktop-v1.3.0", notes, "https://github.com/Shubh-Pandiya/OTA/releases/tag/desktop-v1.3.0",
        Installer("OTA-Business-1.3.0.msi", "https://example.invalid/OTA-Business-1.3.0.msi", 96_416_484, "0".repeat(64)), null)
    app.updates.preview(UpdateState.Available(release, canInstall = true))
    shot("11-update-available") { AppShell(app, at(Section.HOME)); UpdateDialog(app) {} }
    app.updates.preview(UpdateState.Working(release, UpdateStep.BACKING_UP))
    shot("11b-updating") { UpdatingScreen(app.updates.state.value) {} }
    app.updates.preview(UpdateState.Idle)
    shot("11c-sync-updates-panel", at(Section.SYNC), height = 1350)
    app.close()
    exitProcess(0)
}
