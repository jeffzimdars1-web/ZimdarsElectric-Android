package com.zimdarselectric.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Repeat-work reminders saved on a job: the customer gets a text when it's due ("time for your generator service"). */
object RepeatWork {
    data class Preset(val what: String, val months: Int, val why: String)
    data class Item(val what: String, val due: Long, val sent: Long?, val why: String)

    val presets = listOf(
        Preset("Generator yearly service", 12, "Generators should be serviced once a year so they start when the power goes out."),
        Preset("Smoke + CO detector test", 12, "It's a good time to test your smoke and CO detectors and change the batteries."),
        Preset("Replace smoke + CO detectors", 120, "Smoke and CO detectors should be replaced every 10 years."),
        Preset("Whole-house surge protector check", 60, "Surge protectors wear out after big surges, so it's worth checking it."),
        Preset("Panel check-up", 24, "A quick panel check-up catches loose or overheating connections early."),
        Preset("EV charger check", 12, "A yearly look at your EV charger keeps it charging safely."),
    )

    fun dueIn(months: Int, from: Long = System.currentTimeMillis()): Long =
        LocalDate.ofInstant(java.time.Instant.ofEpochMilli(from), ZoneId.systemDefault()).plusMonths(months.toLong())
            .atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun parse(json: String): List<Item> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Item(it.getString("what"), it.getLong("due"), if (it.isNull("sent")) null else it.optLong("sent").takeIf { v -> v > 0 }, it.optString("why")) } }
    }.getOrDefault(emptyList())

    fun write(items: List<Item>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("what", it.what).put("due", it.due).put("sent", it.sent ?: JSONObject.NULL).put("why", it.why)) }
    }.toString()

    fun text(q: Quote, customer: Customer?, s: Settings, item: Item): String {
        val first = (customer?.name ?: q.customerName).trim().substringBefore(' ').ifBlank { "there" }
        val who = listOf(s.ownerName.ifBlank { null }, s.businessName).filterNotNull().joinToString(" at ")
        val where = q.site.substringBefore(',').ifBlank { "your place" }
        return "Hi $first, it's $who. Friendly reminder: ${item.what.lowercase()} is due at $where. ${item.why} " +
            "Want me to set up a time? Just reply here, or pick a time: ${MissedCalls.BOOK_URL}"
    }
}
