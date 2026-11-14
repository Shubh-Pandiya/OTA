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
    /** The email the token endpoint answers with (follows the last password sign-in). */
    var lastEmail = "test@ota.com"
    /** False = the cloud before the hard-delete migration (no ota_delete_rows / ota_deletions). */
    var hardDeletes = true
    /** Times ota_clear_test_data was called. */
    var clearCalls = 0
    /** Rows returned by each table GET, in order ("orders" to 2). */
    val pulled = CopyOnWriteArrayList<Pair<String, Int>>()
    /** The cloud API's "max rows": a GET never returns more, whatever its limit. */
    var maxRows = 1000
    /** Table GETs still answered before they fail with 503 (a dropped link mid-pull); absent = no limit. */
    val getBudget = HashMap<String, Int>()
    private var last: Instant = Instant.EPOCH
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

    /**
     * Next server timestamp in PostgREST's format: the real clock (the HTTP Date header the server
     * adds is the real clock too), kept strictly increasing.
     */
    fun now(): String {
        val real = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        last = if (real.isAfter(last)) real else last.plusNanos(1000)
        return fmt.format(last)
    }

    /** A row as another device would have written it ([at] = its server time, e.g. ten minutes ago). */
    fun put(name: String, row: JSONObject, at: Instant? = null) {
        row.put("updated_at", at?.let(fmt::format) ?: now())
        if (!row.has("client_updated_at")) row.put("client_updated_at", System.currentTimeMillis())
        table(name)[row.getString("id")] = row
    }

    /** What ota_record_deletion does: unless the row was edited after [at], it goes with its children and a tombstone stays. */
    fun recordDeletion(name: String, id: String, at: Long): Boolean {
        val row = table(name)[id]
        if (row != null && row.optLong("client_updated_at") > at) return false
        for ((child, column) in com.example.otaapplication.data.DeleteCascade.children(name)) {
            table(child).values.filter { it.optString(column) == id }.map { it.getString("id") }.forEach { cid ->
                recordDeletion(child, cid, maxOf(at, table(child)[cid]!!.optLong("client_updated_at")))
            }
        }
        table(name).remove(id)
        val old = table("ota_deletions")[id]
        if (old == null || old.optLong("client_updated_at") <= at) {
            table("ota_deletions")[id] = JSONObject().put("id", id).put("tbl", name).put("client_updated_at", at).put("updated_at", now())
        }
        return true
    }

    private fun handle(method: String, path: String, query: String, body: String): Pair<Int, String> {
        if (path.startsWith("/auth/v1/token")) {
            return 200 to JSONObject().put("access_token", "token").put("refresh_token", "refresh").put("expires_in", 3600)
                .put("user", JSONObject().put("id", "00000000-0000-0000-0000-000000000003")
                    .put("email", body.takeIf { it.isNotBlank() }?.let { JSONObject(it).optString("email") }?.ifBlank { null } ?: lastEmail)).toString()
                .also { body.takeIf { it.isNotBlank() }?.let { JSONObject(it).optString("email") }?.ifBlank { null }?.let { lastEmail = it } }
        }
        if (path == "/auth/v1/logout") return 204 to ""
        val name = path.removePrefix("/rest/v1/")
        if (method != "GET") failWrites?.let { (status, code) ->
            return status to JSONObject().put("message", "write refused").put("code", code ?: JSONObject.NULL).toString()
        }
        if (!hardDeletes && (name == "ota_deletions" || name == "rpc/ota_delete_rows")) {
            return 404 to JSONObject().put("code", if (name == "ota_deletions") "PGRST205" else "PGRST202").put("message", "not found").toString()
        }
        return when {
            name == "rpc/ota_delete_rows" -> {
                val rows = JSONObject(body).getJSONArray("p_rows")
                val kept = JSONArray()
                var deleted = 0
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    if (recordDeletion(r.getString("table"), r.getString("id"), r.getLong("at"))) deleted++ else kept.put(r.getString("id"))
                }
                200 to JSONObject().put("deleted", deleted).put("kept", kept).toString()
            }
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
            name == "rpc/ota_clear_test_data" -> {
                clearCalls++
                val counts = JSONObject()
                for ((t, rows) in tables) { counts.put(t, rows.size); rows.clear() }
                200 to counts.toString()
            }
            name == "app_settings" && method == "PATCH" -> {
                if (staff?.optBoolean("is_admin") != true) 200 to "[]"
                else { JSONObject(body).let { b -> b.keys().forEach { settings.put(it, b.get(it)) } }; 200 to JSONArray().put(settings).toString() }
            }
            method == "GET" -> select(name, query)
            method == "POST" -> upsert(name, JSONArray(body), query)
            else -> 404 to "{}"
        }
    }

    /** A table GET the way PostgREST answers the sync's: updated_at / keyset filter, (updated_at, id) order, limit. */
    private fun select(name: String, query: String): Pair<Int, String> {
        getBudget[name]?.let { left ->
            if (left <= 0) return 503 to JSONObject().put("message", "connection lost").toString()
            getBudget[name] = left - 1
        }
        val params = query.split('&').filter { it.contains('=') }.associate { p ->
            p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), Charsets.UTF_8)
        }
        fun time(s: String) = java.time.OffsetDateTime.parse(s.replace(' ', '+')).toInstant()
        val at = { row: JSONObject -> time(row.getString("updated_at")) }
        var rows = table(name).values.sortedWith(compareBy<JSONObject>({ at(it) }, { it.getString("id") }))
        params["updated_at"]?.let { f -> val t = time(f.removePrefix("gt.")); rows = rows.filter { at(it) > t } }
        params["or"]?.let { f ->
            val m = Regex("""\(updated_at\.gt\."(.+)",and\(updated_at\.eq\."(.+)",id\.gt\.(.+)\)\)""").matchEntire(f) ?: error("bad or: $f")
            val t = time(m.groupValues[1])
            val id = m.groupValues[3]
            rows = rows.filter { at(it) > t || (at(it) == t && it.getString("id") > id) }
        }
        rows = rows.take(minOf(params["limit"]?.toInt() ?: Int.MAX_VALUE, maxRows))
        pulled += name to rows.size
        return 200 to JSONArray(rows).toString()
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
        val stored = incoming.mapNotNull { r ->
            val id = r.getString("id")
            // The tombstone guard: a row deleted at or after this copy's edit stays deleted.
            val tomb = table("ota_deletions")[id]
            if (tomb != null && tomb.optLong("client_updated_at") >= r.optLong("client_updated_at")) return@mapNotNull null
            if (tomb != null) table("ota_deletions").remove(id)
            // An app from before hard deletes still sends deleted = true: the cloud turns it into a delete.
            if (r.optBoolean("deleted")) { recordDeletion(name, id, r.optLong("client_updated_at")); return@mapNotNull null }
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
