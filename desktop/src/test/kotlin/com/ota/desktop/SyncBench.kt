package com.ota.desktop

import com.example.otaapplication.cloud.PostgrestClient
import com.example.otaapplication.cloud.SessionStore
import com.example.otaapplication.cloud.SupabaseAuth
import com.example.otaapplication.cloud.SupabaseConfig
import com.example.otaapplication.data.OtaAccounts
import com.ota.desktop.data.CatalogStore
import com.ota.desktop.data.Numbers
import com.ota.desktop.data.OrderStore
import com.ota.desktop.data.PaymentStore
import com.ota.desktop.db.Kv
import com.ota.desktop.db.one
import com.ota.desktop.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Times sync rounds against the real cloud as the TEST account only, optionally through a throttling
 * HTTPS proxy: a first sync into an empty local database, then rounds with nothing new.
 * `OTA_PW=… OTA_BENCH_PROXY=<port> java --add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED -cp <test classpath> com.ota.desktop.SyncBenchKt`
 * The proxy (any CONNECT proxy) may answer `GET /reset` and `GET /stats` with its byte counters.
 */
fun main() {
    val proxyPort = System.getenv("OTA_BENCH_PROXY")?.toIntOrNull()
    if (proxyPort != null) {
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", proxyPort.toString())
    }
    val requests = AtomicInteger()
    countHttpsRequests(requests)
    val props = Properties().apply { File("../supabase.properties").reader().use(::load) }
    val config = SupabaseConfig(props.getProperty("SUPABASE_URL").trimEnd('/'), props.getProperty("SUPABASE_PUBLISHABLE_KEY"))
    val db = TestDb.fresh()
    val kv = Kv(db)
    val auth = SupabaseAuth(config, SessionStore(kv))
    val engine = SyncEngine(db, kv, auth, PostgrestClient(config, auth), CatalogStore(db), OrderStore(db), PaymentStore(db), Numbers(db, kv))
    fun proxy(path: String) = proxyPort?.let { URI("http://127.0.0.1:$it$path").toURL().openConnection(Proxy.NO_PROXY).getInputStream().use { s -> s.readBytes().decodeToString() } }
    runBlocking {
        auth.signIn(OtaAccounts.TEST_EMAIL, System.getenv("OTA_PW") ?: error("OTA_PW not set"))
        check(auth.session.value?.email == OtaAccounts.TEST_EMAIL) { "only for the test account" }
        engine.adoptDataMode(OtaAccounts.TEST_EMAIL)
        val rounds = (System.getenv("OTA_BENCH_ROUNDS") ?: "3").toInt()
        for (round in 0..rounds) {
            // Idle like between scheduled rounds, so pooled connections time out as they would.
            if (round > 0) Thread.sleep(6_000)
            proxy("/reset")
            requests.set(0)
            val started = System.nanoTime()
            val status = engine.sync()
            val ms = (System.nanoTime() - started) / 1_000_000
            val rows = listOf("orders", "bills", "order_bills", "payment_advices", "advice_lines", "buyers", "suppliers")
                .joinToString(" ") { t -> "$t=" + db.read { c -> c.one("select count(*) from $t") { it.getInt(1) } } }
            println("BENCH ${if (round == 0) "first" else "nochange$round"} ms=$ms requests=${requests.get()} proxy=${proxy("/stats")} error=${status.error} $rows")
        }
    }
    exitProcess(0)
}

/** Counts every https request the JVM makes, by wrapping the built-in handler. */
private fun countHttpsRequests(counter: AtomicInteger) {
    val builtIn = Class.forName("sun.net.www.protocol.https.Handler").getDeclaredConstructor().newInstance() as URLStreamHandler
    val open = URLStreamHandler::class.java.getDeclaredMethod("openConnection", URL::class.java, Proxy::class.java).apply { isAccessible = true }
    val openPlain = URLStreamHandler::class.java.getDeclaredMethod("openConnection", URL::class.java).apply { isAccessible = true }
    URL.setURLStreamHandlerFactory { protocol ->
        if (protocol != "https") null else object : URLStreamHandler() {
            override fun getDefaultPort() = 443
            override fun openConnection(u: URL): URLConnection { counter.incrementAndGet(); return openPlain.invoke(builtIn, u) as URLConnection }
            override fun openConnection(u: URL, p: Proxy): URLConnection { counter.incrementAndGet(); return open.invoke(builtIn, u, p) as URLConnection }
        }
    }
}
