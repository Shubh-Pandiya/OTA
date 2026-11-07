package com.example.otaapplication.data

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The order history file (`internal_order_history_db.json`), the source of truth for order
 * content on the phone. Shared by the screens and background sync, so every access goes through
 * one process-wide lock. The file format is unchanged from earlier versions.
 */
class OrderHistoryStore(private val dir: File) {

    private val file get() = File(dir, FILE_NAME)

    /** Runs [block] while holding the history lock (reentrant). */
    fun <T> locked(block: () -> T): T = synchronized(LOCK, block)

    fun readText(): String = locked { if (file.exists()) file.readText() else "" }

    fun readArray(): JSONArray = locked {
        val text = readText()
        if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    fun write(array: JSONArray) = locked {
        // Write to a temp file first so a crash mid-write can never truncate the history.
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString())
            tmp.delete()
        }
    }

    fun find(uuid: String): JSONObject? = locked {
        val array = readArray()
        (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.optString("uuid") == uuid }
    }

    /** Replaces the entry with the same uuid in place (keeping its position), or appends it. */
    fun upsert(entry: JSONObject) = locked {
        val uuid = entry.optString("uuid")
        val array = readArray()
        var replaced = false
        for (i in 0 until array.length()) {
            if (uuid.isNotBlank() && array.getJSONObject(i).optString("uuid") == uuid) {
                array.put(i, entry)
                replaced = true
                break
            }
        }
        if (!replaced) array.put(entry)
        write(array)
    }

    /** Removes the entry with [uuid]; returns it, or null when there was none. */
    fun remove(uuid: String): JSONObject? = locked {
        val array = readArray()
        val kept = JSONArray()
        var removed: JSONObject? = null
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            if (removed == null && json.optString("uuid") == uuid) removed = json else kept.put(json)
        }
        if (removed != null) write(kept)
        removed
    }

    companion object {
        const val FILE_NAME = "internal_order_history_db.json"
        private val LOCK = Any()
    }
}

/** Conversions between [OrderFormData] and the history / sync JSON objects. */
object OrderJson {

    /** Writes the order fields in the same order and with the same keys as before. */
    fun putFields(json: JSONObject, data: OrderFormData): JSONObject = json
        .put("date", data.date).put("offerNo", data.offerNo).put("buyer", data.buyer)
        .put("supplier", data.supplier).put("remarks", data.remarks).put("deliveryReady", data.deliveryReady)
        .put("transportTo", data.transportTo).put("cashPayment", data.cashPayment).put("lessDisc", data.lessDisc).put("side", data.side)

    fun itemsArray(items: List<OrderItem>): JSONArray = JSONArray().apply {
        for (item in items) {
            val json = JSONObject().put("serialNo", item.serialNo).put("particulars", item.particulars).put("quantity", item.quantity).put("rate", item.rate)
            if (item.unit.isNotEmpty()) json.put("unit", item.unit)
            put(json)
        }
    }

    /** A history entry: order fields, items, then where its files live on this phone. */
    fun historyEntry(data: OrderFormData, uuid: String, filePath: String, pdfFileName: String?): JSONObject =
        putFields(JSONObject(), data).apply {
            put("items", itemsArray(data.items))
            put("filePath", filePath)
            if (pdfFileName != null) put("pdfFileName", pdfFileName)
            put("uuid", uuid)
        }

    fun parse(json: JSONObject): OrderFormData {
        val itemsArray = json.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).map { i ->
            val item = itemsArray.getJSONObject(i)
            OrderItem(item.optString("serialNo", ""), item.optString("particulars", ""), item.optString("quantity", ""), item.optString("rate", ""), item.optString("unit", ""))
        }
        return OrderFormData(
            json.optString("date", ""), json.optString("offerNo", ""), json.optString("buyer", ""), json.optString("supplier", ""),
            items, json.optString("remarks", ""), json.optString("deliveryReady", ""), json.optString("transportTo", ""),
            json.optString("cashPayment", ""), json.optString("lessDisc", ""), json.optString("side", "")
        )
    }

    /** Everything that is printed on the PDF; used to notice edits that must be synced. */
    fun contentKey(data: OrderFormData): String = buildList {
        add(data.date); add(data.offerNo); add(data.buyer); add(data.supplier); add(data.remarks)
        add(data.deliveryReady); add(data.transportTo); add(data.cashPayment); add(data.lessDisc); add(data.side)
        // The unit is appended to the quantity only when set, so keys of orders saved before units are unchanged.
        data.items.forEach { add(it.serialNo); add(it.particulars); add(if (it.unit.isEmpty()) it.quantity else it.quantity + "\u0002" + it.unit); add(it.rate) }
    }.joinToString("\u0001")
}
