package com.example.otaapplication.cloud

import com.example.otaapplication.data.BankDetails
import com.example.otaapplication.data.Party
import com.example.otaapplication.data.PartyType
import com.example.otaapplication.data.PendingParty
import com.example.otaapplication.data.RemoteParty
import com.example.otaapplication.data.SyncTime
import org.json.JSONArray
import org.json.JSONObject

/** Cloud side of the buyer/supplier catalog. */
interface CatalogCloudApi {
    /** Rows changed after the cursor, oldest first. [afterId] breaks ties between equal timestamps. */
    suspend fun pull(type: PartyType, after: String?, afterId: String?, limit: Int): List<RemoteParty>

    /** Upserts rows; returns the rows as stored on the server (possibly a newer copy kept by the server). */
    suspend fun push(type: PartyType, rows: List<PendingParty>): List<RemoteParty>
}

/** [CatalogCloudApi] over Supabase's REST API (PostgREST); row-level security limits it to signed-in staff. */
class PostgrestCatalogApi(private val client: PostgrestClient) : CatalogCloudApi {

    override suspend fun pull(type: PartyType, after: String?, afterId: String?, limit: Int): List<RemoteParty> {
        val filter = when {
            after == null -> ""
            afterId == null -> "&updated_at=gt.${Http.encode(after)}"
            else -> "&or=" + Http.encode("(updated_at.gt.\"$after\",and(updated_at.eq.\"$after\",id.gt.$afterId))")
        }
        val body = client.call("GET", "${type.table}?select=${CatalogJson.COLUMNS}&order=updated_at.asc,id.asc&limit=$limit$filter")
        return CatalogJson.parseRows(body, type)
    }

    override suspend fun push(type: PartyType, rows: List<PendingParty>): List<RemoteParty> {
        if (rows.isEmpty()) return emptyList()
        val body = client.call(
            "POST", "${type.table}?on_conflict=id&select=${CatalogJson.COLUMNS}",
            mapOf("Prefer" to "resolution=merge-duplicates,return=representation"),
            CatalogJson.toJsonArray(rows).toString()
        )
        return CatalogJson.parseRows(body, type)
    }
}

/** JSON mapping between local rows and the cloud tables (see supabase/migrations). */
object CatalogJson {
    /** Only what the apps read (not e.g. archived_at or is_test). */
    const val COLUMNS = "id,name,address,city,phone,gstin,bank_account_name,bank_name,bank_account_no,bank_ifsc,bank_branch,bank_upi,deleted,created_at,updated_at,client_updated_at"

    fun toJson(row: PendingParty): JSONObject {
        val p = row.party
        return JSONObject()
            .put("id", p.id)
            .put("name", p.name)
            .put("address", p.address)
            .put("city", p.city)
            .put("phone", p.phone)
            .put("gstin", p.gstin)
            .put("bank_account_name", p.bank.accountName)
            .put("bank_name", p.bank.bankName)
            .put("bank_account_no", p.bank.accountNo)
            .put("bank_ifsc", p.bank.ifsc)
            .put("bank_branch", p.bank.branch)
            .put("bank_upi", p.bank.upi)
            .put("deleted", row.deleted)
            .put("created_at", SyncTime.fromMillis(row.createdAt))
            .put("client_updated_at", row.updatedAt)
    }

    fun toJsonArray(rows: List<PendingParty>) = JSONArray().apply { rows.forEach { put(toJson(it)) } }

    fun parseRows(body: String, type: PartyType): List<RemoteParty> {
        val array = JSONArray(body)
        return List(array.length()) { i -> parse(array.getJSONObject(i), type) }
    }

    fun parse(json: JSONObject, type: PartyType): RemoteParty {
        val serverUpdatedAt = json.getString("updated_at")
        return RemoteParty(
            party = Party(
                id = json.getString("id"),
                type = type,
                name = json.text("name"),
                address = json.text("address"),
                city = json.text("city"),
                phone = json.text("phone"),
                gstin = json.text("gstin"),
                bank = BankDetails(
                    accountName = json.text("bank_account_name"),
                    bankName = json.text("bank_name"),
                    accountNo = json.text("bank_account_no"),
                    ifsc = json.text("bank_ifsc"),
                    branch = json.text("bank_branch"),
                    upi = json.text("bank_upi")
                )
            ),
            deleted = json.optBoolean("deleted", false),
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() && it != "null" }
                ?.let(SyncTime::toMillis) ?: SyncTime.toMillis(serverUpdatedAt),
            clientUpdatedAt = if (json.isNull("client_updated_at")) SyncTime.toMillis(serverUpdatedAt) else json.getLong("client_updated_at"),
            serverUpdatedAt = serverUpdatedAt
        )
    }

    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()
}
