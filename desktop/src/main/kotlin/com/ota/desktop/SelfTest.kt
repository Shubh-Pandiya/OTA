package com.ota.desktop

import com.example.otaapplication.BuildConfig
import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.ota.desktop.db.Db
import com.ota.desktop.db.EmbeddedDb
import com.ota.desktop.db.Migrations
import com.ota.desktop.db.one
import com.ota.desktop.export.SheetTable
import com.ota.desktop.export.XlsxExport
import com.ota.desktop.pdf.OrderPdf
import com.ota.desktop.update.DataSnapshot
import com.ota.desktop.update.StartupGuard
import com.ota.desktop.update.UpdateManager
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.crypto.KeyAgreement
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * `OTA Business.exe --self-test <report file>`: boots the bundled PostgreSQL from the installed
 * app through the same start-up path as a normal launch (version guard, pre-update backup,
 * migrations), runs a query, then exits. CI runs it on the installed MSI so a missing runtime module
 * or a broken database bundle fails the build instead of the user's first launch. The report lists
 * every table's row count and checksum, which the update test compares across versions.
 */
object SelfTest {
    fun run(report: File): Int {
        val lines = mutableListOf(
            "started ${Instant.now()}",
            "app ${UpdateManager.RUNNING}",
            "java ${System.getProperty("java.version")} ${System.getProperty("os.name")} ${System.getProperty("os.arch")}"
        )
        val code = try {
            val guard = StartupGuard(AppPaths.home, AppPaths.pgData, UpdateManager.RUNNING)
            lines += "last-run ${guard.lastVersion ?: "none"}"
            EmbeddedDb.start(beforeStart = guard::beforeStart).use { server ->
                lines += "postgres port ${server.port}"
                Db(server::connect).use { db ->
                    guard.migrate(db)
                    guard.started()
                    guard.backup?.let { lines += "backup ${it.absolutePath}" }
                    val version = db.read { c -> c.one("select max(version) from schema_version") { it.getInt(1) } }
                    val pg = db.read { c -> c.one("select version()") { it.getString(1) } }
                    lines += "schema $version / ${Migrations.latest}"
                    lines += "server $pg"
                    check(version == Migrations.latest) { "schema not migrated" }
                    DataSnapshot.take(db).forEach { (table, sum) -> lines += "table $table $sum" }
                }
            }
            UpdateManager(UpdateManager.RUNNING).consumeOutcome()?.let { lines += "update ${if (it.ok) "ok" else "failed"}: ${it.message.lineSequence().first()}" }
            runtimeChecks(lines)
            lines += "OK"
            0
        } catch (e: Throwable) {
            lines += "FAILED ${e::class.java.name}: ${e.message}"
            lines += e.stackTraceToString()
            1
        }
        report.absoluteFile.parentFile?.mkdirs()
        report.writeText(lines.joinToString("\n") + "\n")
        return code
    }

    /**
     * What the trimmed Java runtime must still do: TLS (EC key exchange and the bundled CA list, for
     * Supabase and GitHub), PDFs (AWT fonts and graphics) and Excel files (XML, zip).
     */
    private fun runtimeChecks(lines: MutableList<String>) {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val cas = tmf.trustManagers.filterIsInstance<X509TrustManager>().sumOf { it.acceptedIssuers.size }
        check(cas > 50) { "only $cas trusted CA certificates" }
        KeyAgreement.getInstance("X25519")
        KeyAgreement.getInstance("ECDH")
        Signature.getInstance("SHA256withECDSA")
        val suites = SSLContext.getDefault().createSSLEngine().enabledCipherSuites
        check(suites.any { it.contains("ECDHE") }) { "no ECDHE cipher suites" }
        lines += "crypto ok: $cas CAs, ${suites.size} cipher suites"
        lines += https("https://api.github.com/zen")
        BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() }?.let { lines += https("${it.trimEnd('/')}/auth/v1/health") }

        val dir = File(AppPaths.logs, "self-test-files").apply { mkdirs() }
        try {
            val pdf = OrderPdf.write(
                OrderFormData(offerNo = "SELFTEST", buyer = "Self test", supplier = "Self test", items = listOf(OrderItem("1", "Cotton ₹ check", "10", "145", "M"))),
                File(dir, "order.pdf")
            )
            check(pdf.length() > 1000 && pdf.readBytes().copyOfRange(0, 5).decodeToString() == "%PDF-") { "bad PDF" }
            val xlsx = XlsxExport.write(
                File(dir, "export.xlsx"),
                listOf(SheetTable("Check", listOf("Name", "Date", "Amount"), listOf(listOf("Self test", LocalDate.now(), 1234567.5)), moneyColumns = setOf(2), currency = true))
            )
            check(xlsx.length() > 1000 && xlsx.readBytes().copyOfRange(0, 2).decodeToString() == "PK") { "bad XLSX" }
            lines += "pdf ${pdf.length()} bytes, xlsx ${xlsx.length()} bytes"
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * One HTTPS request: any HTTP status proves the TLS handshake. Unreachable (offline) is reported
     * but tolerated; a TLS failure is not.
     */
    private fun https(url: String): String {
        val host = URI(url).host
        return try {
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
                .send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding())
            "tls $host ${response.statusCode()} ${response.sslSession().map { "${it.protocol} ${it.cipherSuite}" }.orElse("?")}"
        } catch (e: IOException) {
            if (generateSequence(e as Throwable) { it.cause }.any { it is SSLException }) throw IllegalStateException("TLS to $host failed: $e", e)
            "tls $host unreachable (${e::class.java.simpleName}: ${e.message})"
        }
    }
}
