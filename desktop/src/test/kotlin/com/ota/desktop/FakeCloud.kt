package com.ota.desktop

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small in-memory stand-in for Supabase (auth + the PostgREST calls the desktop makes), enough
 * to run real sync rounds in tests without network or secrets. Mirrors the cloud rules the sync
 * relies on: upsert by id, unique numbers (23505), updated_at set by the server, archive marks
 * only exact versions, purge removes only archived and unchanged rows.
 */
class FakeCloud(var staff: JSONObject? = JSONObject().put("code", 3).put("name", "Shubham").put("is_admin", true)) : AutoCloseable {
    val tables = HashMap<String, LinkedHashMap<String, JSONObject>>()
    val archived = HashMap<String, HashMap<String, String>>()
    val requests = CopyOnWriteArrayList<String>()
    var usage = JSONObject().put("used_bytes", 20L * 1048576).put("limit_mb", 500).put("warn_percent", 80).put("retention_days", 30).put("percent", 4.0)
    var settings = JSONObject().put("retention_days", 30).put("storage_limit_mb", 500).put("warn_percent", 80)
    /** When set, every write answers with this HTTP status / Postgres code (e.g. 402 = over quota). */
    var failWrites: Pair<Int, String?>? = null
    /** Last FY order serial handed out by ota_allocate_order_no, per FY prefix ("2627"). */
    val orderSerials = HashMap<String, Int>()
    /** Last FY advice serial handed out by ota_allocate_advice_no (its own counter), per FY prefix. */
    val adviceSerials = HashMap<String, Int>()
    /** When true, ota_allocate_order_no answers like the database at 9999 orders. */
    var orderLimitReached = false
    private var tick = 0L
    private val base = Instant.parse("2026-11-10T04:30:00Z")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx").withZone(ZoneOffset.UTC)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().decodeToString()
            val path = ex.requestURI.rawPath
            val query = ex.requestURI.rawQuery.orEmpty()
            requests += "${ex.requestMethod} $path${if (query.isEmpty()) "" else "?$query"}"
            val (status, out) = synchronized(this@FakeCloud) {
                runCatching { handle(ex.requestMethod, path, query, body) }.getOrElse { 500 to JSONObject().put("message", it.toString()).toString() }
            }
            val bytes = out.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun table(name: String) = tables.getOrPut(name) { LinkedHashMap() }

    /** Next server timestamp, strictly increasing, in PostgREST's format. */
    fun now(): String = fmt.format(base.plusNanos(++tick * 1000))

    /** A row as another device would have written it. */
    fun put(name: String, row: JSONObject) {
        row.put("updated_at", now())
        if (!row.has("client_updated_at")) row.put("client_updated_at", System.currentTimeMillis())
        table(name)[row.getString("id")] = row
    }

    private fun handle(method: String, path: String, query: String, body: String): Pair<Int, String> {
        if (path.startsWith("/auth/v1/token")) {
            return 200 to JSONObject().put("access_token", "token").put("refresh_token", "refresh").put("expires_in", 3600)
                .put("user", JSONObject().put("id", "00000000-0000-0000-0000-000000000003").put("email", "test@ota.com")).toString()
        }
        if (path == "/auth/v1/logout") return 204 to ""
        val name = path.removePrefix("/rest/v1/")
        if (method != "GET") failWrites?.let { (status, code) ->
            return status to JSONObject().put("message", "write refused").put("code", code ?: JSONObject.NULL).toString()
        }
        return when {
            name == "staff" -> 200 to JSONArray().apply { staff?.let { put(it) } }.toString()
            name == "rpc/ota_cloud_status" -> 200 to usage.toString()
            name == "rpc/ota_allocate_order_no" -> {
                if (orderLimitReached) 400 to JSONObject().put("code", "P0001").put("message", "ORDER_LIMIT_REACHED").toString()
                else {
                    val date = java.time.LocalDate.parse(JSONObject(body).getString("p_date"))
                    val prefix = com.example.otaapplication.OrderNumber.fyPrefix(date)
                    val serial = (orderSerials[prefix] ?: 0) + 1
                    orderSerials[prefix] = serial
                    200 to JSONObject.quote(com.example.otaapplication.OrderNumber.formatFy(date, serial))
                }
            }
            name == "rpc/ota_allocate_advice_no" -> {
                val date = java.time.LocalDate.parse(JSONObject(body).getString("p_date"))
                val prefix = com.example.otaapplication.OrderNumber.fyPrefix(date)
                val serial = (adviceSerials[prefix] ?: 0) + 1
                adviceSerials[prefix] = serial
                200 to JSONObject.quote(com.example.otaapplication.OrderNumber.formatFy(date, serial))
            }
            name == "rpc/ota_mark_archived" -> markArchived(JSONObject(body))
            name == "rpc/ota_purge_archived_orders" -> purge()
            name == "app_settings" && method == "PATCH" -> {
                if (staff?.optBoolean("is_admin") != true) 200 to "[]"
                else { JSONObject(body).let { b -> b.keys().forEach { settings.put(it, b.get(it)) } }; 200 to JSONArray().put(settings).toString() }
            }
            method == "GET" -> 200 to JSONArray(table(name).values.sortedBy { it.getString("updated_at") }).toString()
            method == "POST" -> upsert(name, JSONArray(body), query)
            else -> 404 to "{}"
        }
    }

    private fun upsert(name: String, rows: JSONArray, query: String): Pair<Int, String> {
        val numberColumn = mapOf("orders" to "order_no", "payment_advices" to "advice_no")[name]
        val incoming = List(rows.length()) { rows.getJSONObject(it) }
        if (numberColumn != null) {
            for (r in incoming) {
                val clash = table(name).values.any { it.getString("id") != r.getString("id") && it.optString(numberColumn) == r.optString(numberColumn) }
                if (clash) return 409 to JSONObject().put("code", "23505").put("message", "duplicate key value violates unique constraint").toString()
            }
        }
        val stored = incoming.map { r ->
            val existing = table(name)[r.getString("id")]
            // Last write wins: the server keeps a newer copy if it already has one.
            if (existing != null && existing.optLong("client_updated_at") > r.optLong("client_updated_at")) existing
            else JSONObject(r.toString()).also { it.put("updated_at", now()); table(name)[it.getString("id")] = it }
        }
        return if (URLDecoder.decode(query, Charsets.UTF_8).contains("on_conflict") && name in setOf("buyers", "suppliers")) 201 to JSONArray(stored).toString()
        else 201 to ""
    }

    private fun markArchived(args: JSONObject): Pair<Int, String> {
        if (staff?.optBoolean("is_admin") != true) return 403 to JSONObject().put("message", "Only an admin can archive orders").put("code", "42501").toString()
        val name = args.getString("p_table")
        val rows = args.getJSONArray("p_rows")
        var n = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val row = table(name)[r.getString("id")] ?: continue
            if (row.getString("updated_at") == r.getString("updated_at")) {
                archived.getOrPut(name) { HashMap() }[r.getString("id")] = r.getString("updated_at"); n++
            }
        }
        return 200 to n.toString()
    }

    /** Removes every archived, unchanged row (the real RPC also requires it to be older than retention). */
    private fun purge(): Pair<Int, String> {
        if (staff?.optBoolean("is_admin") != true) return 403 to JSONObject().put("message", "Only an admin can purge orders").put("code", "42501").toString()
        val result = JSONObject()
        for (name in listOf("orders", "order_bills", "payment_advices", "advice_lines", "bills")) {
            val marks = archived[name].orEmpty()
            val gone = table(name).values.filter { marks[it.getString("id")] == it.getString("updated_at") }.map { it.getString("id") }
            gone.forEach { table(name).remove(it) }
            result.put(name, gone.size)
        }
        return 200 to result.put("cutoff", "2026-10-11").put("retention_days", settings.getInt("retention_days")).toString()
    }

    override fun close() = server.stop(0)
}
