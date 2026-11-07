package com.example.otaapplication.data

import org.json.JSONArray

/** Reads the existing order history file (`internal_order_history_db.json`) into index entries. */
object HistoryIndex {

    fun parse(historyJson: String): List<HistoryIndexEntry> {
        if (historyJson.isBlank()) return emptyList()
        val array = JSONArray(historyJson)
        val out = ArrayList<HistoryIndexEntry>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val uuid = json.optString("uuid").takeIf { it.isNotBlank() }
            val filePath = json.optString("filePath", "")
            val items = json.optJSONArray("items")
            val particulars = buildString {
                if (items != null) for (j in 0 until items.length()) {
                    val p = items.optJSONObject(j)?.optString("particulars").orEmpty()
                    if (p.isNotBlank()) append(p).append('\n')
                }
            }.trim()
            out += HistoryIndexEntry(
                id = orderId(uuid, filePath, i),
                uuid = uuid,
                offerNo = json.optString("offerNo", "N/A"),
                date = json.optString("date", "N/A"),
                buyer = json.optString("buyer", "Unknown Buyer"),
                supplier = json.optString("supplier", ""),
                particulars = particulars,
                jsonPath = filePath,
                pdfFileName = if (json.has("pdfFileName")) json.optString("pdfFileName") else null,
                seq = i
            )
        }
        // Defensive: keep ids unique even if the history file ever contains a duplicated uuid.
        val seen = HashSet<String>()
        return out.map { if (seen.add(it.id)) it else it.copy(id = "${it.id}#${it.seq}").also { e -> seen.add(e.id) } }
    }

    /** Stable id for an order: its uuid, falling back to the sync file path for very old entries. */
    fun orderId(uuid: String?, filePath: String, index: Int): String = when {
        !uuid.isNullOrBlank() -> uuid
        filePath.isNotBlank() -> "file:$filePath"
        else -> "legacy:$index"
    }
}
