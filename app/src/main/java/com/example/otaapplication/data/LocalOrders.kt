package com.example.otaapplication.data

import android.content.Context
import com.example.otaapplication.OrderFormData
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Orders on this phone: the history file (content) plus its search index (bills, sync state).
 * Shared by the screens and the cloud sync; history writes and the index update that follows
 * happen under the history lock so the two never drift apart.
 */
class LocalOrders(val history: OrderHistoryStore, val index: OrderRepository) {

    /** Mirrors the history file into the index. [remote] marks rows just written by a cloud pull. */
    fun reindex(remote: Map<String, Long> = emptyMap()): Boolean = history.locked {
        index.reconcile(HistoryIndex.parse(history.readText()), remote)
    }

    fun get(uuid: String): OrderFormData? = history.find(uuid)?.let(OrderJson::parse)

    /** Removes an order and its files on this phone for good; the delete reaches the cloud on the next sync. */
    fun delete(uuid: String?, syncFilePath: String, pdfName: String?): Boolean {
        val ok = runCatching { history.locked { uuid?.let(history::remove); reindex() } }
            .onFailure { it.printStackTrace() }.isSuccess
        deleteFiles(syncFilePath, pdfName)
        return ok
    }

    /**
     * Applies orders pulled from the cloud (last write wins against unsynced local edits).
     * New orders get no local files: the PDF is made again when the order is reopened and saved.
     * Returns how many orders changed on this phone.
     */
    fun applyPulled(rows: List<RemoteRow<OrderFormData>>): Int {
        if (rows.isEmpty()) return 0
        val staleFiles = ArrayList<Pair<String, String?>>()
        val changed = history.locked {
            val local = index.localSyncMeta("orders", rows.map { it.id })
            val array = history.readArray()
            val positions = HashMap<String, Int>()
            for (i in 0 until array.length()) {
                val uuid = array.getJSONObject(i).optString("uuid")
                if (uuid.isNotBlank()) positions.putIfAbsent(uuid, i)
            }
            val remote = HashMap<String, Long>()
            val removed = HashSet<Int>()
            for (row in rows) {
                // Deleted here after this copy was made: the delete wins and goes up on the next sync.
                if (!row.deleted && index.deletedHere(row.id, row.clientUpdatedAt)) continue
                if (SyncMerge.onPulled(local[row.id], row.deleted, row.clientUpdatedAt) != SyncMerge.Decision.APPLY_REMOTE) continue
                val pos = positions[row.id]
                val existing = pos?.let { array.getJSONObject(it) }
                if (row.deleted) {
                    if (existing != null) {
                        removed += pos
                        remote[row.id] = row.clientUpdatedAt
                        staleFiles += existing.optString("filePath") to existing.pdfName()
                    }
                } else {
                    val entry = OrderJson.historyEntry(row.value, row.id, existing?.optString("filePath").orEmpty(), existing?.pdfName())
                    if (pos != null) array.put(pos, entry) else {
                        positions[row.id] = array.length()
                        array.put(entry)
                    }
                    remote[row.id] = row.clientUpdatedAt
                }
            }
            if (remote.isNotEmpty()) {
                val kept = if (removed.isEmpty()) array else JSONArray().also { out ->
                    for (i in 0 until array.length()) if (i !in removed) out.put(array.getJSONObject(i))
                }
                history.write(kept)
                index.reconcile(HistoryIndex.parse(kept.toString()), remote)
            }
            remote.size
        }
        staleFiles.forEach { (path, pdf) -> deleteFiles(path, pdf) }
        return changed
    }

    /** Orders deleted on other devices (cloud tombstones). Returns how many were removed here. */
    fun applyDeletions(rows: List<Deletion>): Int =
        applyPulled(rows.map { RemoteRow(it.id, OrderFormData(date = "", offerNo = "", items = emptyList()), true, it.at, it.serverUpdatedAt) })

    /** Content of the orders waiting to be uploaded, read from the history file. */
    fun contentOf(uuids: Collection<String>): Map<String, OrderFormData> = history.locked {
        val wanted = uuids.toHashSet()
        val array = history.readArray()
        buildMap {
            for (i in 0 until array.length()) {
                val json = array.getJSONObject(i)
                val uuid = json.optString("uuid")
                if (uuid in wanted && uuid !in this) put(uuid, OrderJson.parse(json))
            }
        }
    }

    /**
     * Gives an order a new number (the cloud already has its number from another order).
     * Returns false if the order is no longer in the history.
     */
    fun renumber(uuid: String, offerNo: String): Boolean = history.locked {
        val json = history.find(uuid) ?: return@locked false
        history.upsert(json.put("offerNo", offerNo))
        reindex()
        true
    }

    private fun JSONObject.pdfName(): String? = if (has("pdfFileName")) optString("pdfFileName") else null

    companion object {
        @Volatile private var instance: LocalOrders? = null

        /** The app-wide instance (history in the app's files folder, index in the app database). */
        fun get(context: Context): LocalOrders = instance ?: synchronized(this) {
            instance ?: LocalOrders(OrderHistoryStore(context.filesDir), OrderRepository(OtaDatabase.get(context))).also { instance = it }
        }

        /** Deletes an order's sync JSON and the PDF next to its folder (layout made by the PDF screen). */
        fun deleteFiles(syncFilePath: String, pdfName: String?) {
            try {
                if (syncFilePath.isBlank()) return
                val jsonFile = File(syncFilePath)
                if (jsonFile.exists()) jsonFile.delete()
                if (!pdfName.isNullOrBlank()) {
                    val pdfFile = File(jsonFile.parentFile?.parentFile, pdfName)
                    if (pdfFile.exists()) pdfFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
