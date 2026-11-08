package com.example.otaapplication.cloud

import com.example.otaapplication.OrderFormData
import com.example.otaapplication.OrderItem
import com.example.otaapplication.data.AdviceLineValue
import com.example.otaapplication.data.AdviceValue
import com.example.otaapplication.data.BillValue
import com.example.otaapplication.data.LineAmounts
import com.example.otaapplication.data.LinkValue
import com.example.otaapplication.data.PendingRow
import com.example.otaapplication.data.RemoteRow
import com.example.otaapplication.data.SyncTime
import org.json.JSONArray
import org.json.JSONObject

/** The staff member's order number digit, read from the cloud `staff` table. */
data class StaffInfo(val code: Int, val name: String)

/**
 * One synced order table: its name, the columns read on pull (only what the phone needs) and
 * the JSON mapping. See supabase/migrations/20261108000000_orders.sql.
 */
class OrderTable<T>(
    val name: String,
    val columns: String,
    val toJson: (PendingRow<T>) -> JSONObject,
    val parse: (JSONObject) -> RemoteRow<T>
) {
    companion object {
        val ORDERS = OrderTable<OrderFormData>(
            name = "orders",
            columns = "id,order_no,order_date,buyer,supplier,items,remarks,delivery_ready,transport_to,cash_payment,less_disc,side,deleted,updated_at,client_updated_at",
            toJson = { row ->
                // A deleted order keeps only its number and date in the cloud.
                val d = if (row.deleted) OrderFormData(date = row.value.date, offerNo = row.value.offerNo, items = emptyList()) else row.value
                JSONObject()
                    .put("id", row.id)
                    .put("order_no", d.offerNo)
                    .put("order_date", d.date)
                    .put("buyer", d.buyer)
                    .put("supplier", d.supplier)
                    .put("items", itemsToJson(d.items))
                    .put("remarks", d.remarks)
                    .put("delivery_ready", d.deliveryReady)
                    .put("transport_to", d.transportTo)
                    .put("cash_payment", d.cashPayment)
                    .put("less_disc", d.lessDisc)
                    .put("side", d.side)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, OrderFormData(
                    date = json.text("order_date"),
                    offerNo = json.text("order_no"),
                    buyer = json.text("buyer"),
                    supplier = json.text("supplier"),
                    items = itemsFromJson(json.optJSONArray("items")),
                    remarks = json.text("remarks"),
                    deliveryReady = json.text("delivery_ready"),
                    transportTo = json.text("transport_to"),
                    cashPayment = json.text("cash_payment"),
                    lessDisc = json.text("less_disc"),
                    side = json.text("side")
                ))
            }
        )

        val BILLS = OrderTable<BillValue>(
            name = "bills",
            columns = "id,bill_no,bill_date,amount,lr_no,cd_percent,cd_amount,items,deleted,updated_at,client_updated_at",
            toJson = { row ->
                JSONObject()
                    .put("id", row.id)
                    .put("bill_no", row.value.billNo)
                    .put("bill_date", row.value.billDate ?: JSONObject.NULL)
                    .put("amount", row.value.amount ?: JSONObject.NULL)
                    .put("lr_no", row.value.lrNo)
                    .put("cd_percent", row.value.cdPercent ?: JSONObject.NULL)
                    .put("cd_amount", row.value.cdAmount ?: JSONObject.NULL)
                    .put("items", row.value.items.takeIf { it.isNotEmpty() }?.let(::itemsToJson) ?: JSONObject.NULL)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, BillValue(
                    billNo = json.text("bill_no"),
                    billDate = json.text("bill_date").ifBlank { null },
                    amount = json.number("amount"),
                    lrNo = json.text("lr_no"),
                    cdPercent = json.number("cd_percent"),
                    cdAmount = json.number("cd_amount"),
                    items = itemsFromJson(json.optJSONArray("items"))
                ))
            }
        )

        val LINKS = OrderTable<LinkValue>(
            name = "order_bills",
            columns = "id,order_id,bill_id,deleted,updated_at,client_updated_at",
            toJson = { row ->
                JSONObject()
                    .put("id", row.id)
                    .put("order_id", row.value.orderId)
                    .put("bill_id", row.value.billId)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json -> remoteRow(json, LinkValue(json.getString("order_id"), json.getString("bill_id"))) }
        )

        /** See supabase/migrations/20261109000000_payment_advice.sql. */
        val ADVICES = OrderTable<AdviceValue>(
            name = "payment_advices",
            columns = "id,advice_no,pay_date,buyer,supplier,mode,reference,balance,balance_side,remarks,deleted,updated_at,client_updated_at",
            toJson = { row ->
                val v = row.value
                // A deleted advice keeps only its number and date in the cloud.
                val keep = !row.deleted
                JSONObject()
                    .put("id", row.id)
                    .put("advice_no", v.adviceNo)
                    .put("pay_date", v.payDate ?: JSONObject.NULL)
                    .put("buyer", if (keep) v.buyer else "")
                    .put("supplier", if (keep) v.supplier else "")
                    .put("mode", if (keep) v.mode else "CASH")
                    .put("reference", if (keep) v.reference else "")
                    .put("balance", v.balanceAmount?.takeIf { keep } ?: JSONObject.NULL)
                    .put("balance_side", v.balanceSide?.takeIf { keep && v.balanceAmount != null } ?: JSONObject.NULL)
                    .put("remarks", if (keep) v.remarks else "")
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, AdviceValue(
                    adviceNo = json.text("advice_no"),
                    payDate = json.text("pay_date").ifBlank { null },
                    buyer = json.text("buyer"),
                    supplier = json.text("supplier"),
                    mode = json.text("mode"),
                    reference = json.text("reference"),
                    balanceAmount = json.number("balance"),
                    balanceSide = json.text("balance_side").ifBlank { null },
                    remarks = json.text("remarks")
                ))
            }
        )

        val ADVICE_LINES = OrderTable<AdviceLineValue>(
            name = "advice_lines",
            columns = "id,advice_id,bill_id,paid,cd_percent,cd_amount,goods_return,rate_diff,claim,claim_note,deleted,updated_at,client_updated_at",
            toJson = { row ->
                val a = row.value.amounts
                JSONObject()
                    .put("id", row.id)
                    .put("advice_id", row.value.adviceId)
                    .put("bill_id", row.value.billId)
                    .put("paid", a.paid)
                    .put("cd_percent", a.cdPercent ?: JSONObject.NULL)
                    .put("cd_amount", a.cdAmount ?: JSONObject.NULL)
                    .put("goods_return", a.goodsReturn ?: JSONObject.NULL)
                    .put("rate_diff", a.rateDiff ?: JSONObject.NULL)
                    .put("claim", a.claim ?: JSONObject.NULL)
                    .put("claim_note", a.claimNote)
                    .put("deleted", row.deleted)
                    .put("client_updated_at", row.updatedAt)
            },
            parse = { json ->
                remoteRow(json, AdviceLineValue(
                    json.getString("advice_id"),
                    json.getString("bill_id"),
                    LineAmounts(
                        paid = json.number("paid") ?: 0.0,
                        cdPercent = json.number("cd_percent"),
                        cdAmount = json.number("cd_amount"),
                        goodsReturn = json.number("goods_return"),
                        rateDiff = json.number("rate_diff"),
                        claim = json.number("claim"),
                        claimNote = json.text("claim_note")
                    )
                ))
            }
        )

        /** Items as compact arrays: [s.no, particulars, qty, rate] plus the unit code when set. */
        fun itemsToJson(items: List<OrderItem>) = JSONArray().apply {
            items.forEach {
                val row = JSONArray().put(it.serialNo).put(it.particulars).put(it.quantity).put(it.rate)
                if (it.unit.isNotEmpty()) row.put(it.unit)
                put(row)
            }
        }

        fun itemsFromJson(array: JSONArray?): List<OrderItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONArray(i) ?: return@mapNotNull null
                OrderItem(item.optString(0, ""), item.optString(1, ""), item.optString(2, ""), item.optString(3, ""), item.optString(4, ""))
            }
        }

        private fun <T> remoteRow(json: JSONObject, value: T): RemoteRow<T> {
            val serverUpdatedAt = json.getString("updated_at")
            return RemoteRow(
                id = json.getString("id"),
                value = value,
                deleted = json.optBoolean("deleted", false),
                clientUpdatedAt = if (json.isNull("client_updated_at")) SyncTime.toMillis(serverUpdatedAt) else json.getLong("client_updated_at"),
                serverUpdatedAt = serverUpdatedAt
            )
        }

        private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

        /** Postgres numeric arrives as a JSON number (or a string for very large values). */
        private fun JSONObject.number(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() || it.isInfinite() }
    }
}

/** Cloud database size against the plan quota, plus how long the cloud keeps orders. */
data class CloudUsage(val usedBytes: Long, val limitMb: Int, val warnPercent: Int, val retentionDays: Int, val percent: Double) {
    val warning: Boolean get() = percent >= warnPercent
    val usedMb: Double get() = usedBytes / 1_048_576.0

    fun toJson(): String = JSONObject()
        .put("used_bytes", usedBytes).put("limit_mb", limitMb).put("warn_percent", warnPercent)
        .put("retention_days", retentionDays).put("percent", percent).toString()

    companion object {
        fun parse(text: String?): CloudUsage? {
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            if (!json.has("used_bytes")) return null
            return CloudUsage(
                usedBytes = json.optLong("used_bytes"),
                limitMb = json.optInt("limit_mb", 500),
                warnPercent = json.optInt("warn_percent", 80),
                retentionDays = json.optInt("retention_days", 30),
                percent = json.optDouble("percent", 0.0)
            )
        }
    }
}

/** Cloud side of orders, bills and bill links. */
interface OrderCloudApi {
    /** Rows changed after the cursor, oldest first. [afterId] breaks ties between equal timestamps. */
    suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>>

    /** Upserts rows (the server keeps a newer copy if it has one). */
    suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>)

    /** The signed-in user's staff code, or null when the owner hasn't added one. */
    suspend fun staff(userId: String): StaffInfo?

    /** Storage use and retention (ota_cloud_status); null when not available. */
    suspend fun usage(): CloudUsage? = null
}

class PostgrestOrderApi(private val client: PostgrestClient) : OrderCloudApi {

    override suspend fun <T> pull(table: OrderTable<T>, after: String?, afterId: String?, limit: Int): List<RemoteRow<T>> {
        val filter = when {
            after == null -> ""
            afterId == null -> "&updated_at=gt.${Http.encode(after)}"
            else -> "&or=" + Http.encode("(updated_at.gt.\"$after\",and(updated_at.eq.\"$after\",id.gt.$afterId))")
        }
        val body = client.call("GET", "${table.name}?select=${table.columns}&order=updated_at.asc,id.asc&limit=$limit$filter")
        val array = JSONArray(body)
        return List(array.length()) { i -> table.parse(array.getJSONObject(i)) }
    }

    override suspend fun <T> push(table: OrderTable<T>, rows: List<PendingRow<T>>) {
        if (rows.isEmpty()) return
        client.call(
            "POST", "${table.name}?on_conflict=id",
            // Nothing is sent back: changes made elsewhere arrive with the next pull.
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
            JSONArray().apply { rows.forEach { put(table.toJson(it)) } }.toString()
        )
    }

    override suspend fun staff(userId: String): StaffInfo? {
        val body = client.call("GET", "staff?select=code,name&user_id=eq.${Http.encode(userId)}")
        val array = JSONArray(body)
        if (array.length() == 0) return null
        val json = array.getJSONObject(0)
        return StaffInfo(json.getInt("code"), json.optString("name").takeUnless { json.isNull("name") }.orEmpty())
    }

    override suspend fun usage(): CloudUsage? =
        CloudUsage.parse(client.call("POST", "rpc/ota_cloud_status", body = "{}").trim().takeUnless { it == "null" })
}
