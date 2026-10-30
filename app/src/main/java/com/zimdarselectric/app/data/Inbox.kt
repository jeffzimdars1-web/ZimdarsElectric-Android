package com.zimdarselectric.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jobs Claude puts together for you (e.g. "write up the job I did for Pauly") wait in your online inbox.
 * The app picks them up when it starts and turns each one into a quote, then the online copy is erased.
 */
object Inbox {
    private const val API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/inbox"

    data class Item(val id: Long, val kind: String, val data: JSONObject)

    private fun call(key: String, body: JSONObject): JSONObject {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-app-key", key.trim())
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("Inbox error ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    suspend fun pending(key: String): List<Item> = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext emptyList()
        val arr = call(key, JSONObject().put("action", "list")).optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val d = o.optJSONObject("data") ?: return@mapNotNull null
            Item(o.getLong("id"), o.optString("kind", "job"), d)
        }
    }

    suspend fun done(key: String, id: Long) = withContext(Dispatchers.IO) {
        runCatching { call(key, JSONObject().put("action", "done").put("id", id)) }
    }

    /** The quote fields from a job item, applied on top of [base]. */
    fun applyQuote(base: Quote, o: JSONObject): Quote {
        fun d(k: String, def: Double) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else def
        fun l(k: String, def: Long?) = if (o.has(k) && !o.isNull(k)) o.getLong(k) else def
        fun s(k: String, def: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else def
        return base.copy(
            title = s("title", base.title), site = s("site", base.site), jobType = s("jobType", base.jobType),
            notes = s("notes", base.notes), repairNotes = s("repairNotes", base.repairNotes),
            laborHours = d("laborHours", base.laborHours), repairHours = d("repairHours", base.repairHours),
            laborRate = d("laborRate", base.laborRate), taxRatePct = d("taxRatePct", base.taxRatePct),
            markupPct = d("markupPct", base.markupPct), miles = d("miles", base.miles),
            startAt = l("startAt", base.startAt), hasTime = if (o.has("hasTime")) o.getBoolean("hasTime") else base.hasTime,
            scheduled = s("scheduled", base.scheduled), status = s("status", base.status),
            completedAt = l("completedAt", base.completedAt), paidAmount = d("paidAmount", base.paidAmount), paidAt = l("paidAt", base.paidAt),
            updatedAt = System.currentTimeMillis(),
        )
    }
}
