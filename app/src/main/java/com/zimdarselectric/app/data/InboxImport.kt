package com.zimdarselectric.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zimdarselectric.app.MainActivity
import com.zimdarselectric.app.R
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Turns online inbox items into jobs: write-ups from Claude ("job") and requests from your booking page ("request"). */
object InboxImport {
    private val lock = kotlinx.coroutines.sync.Mutex()

    /** Picks up everything waiting. Returns (quote id, short description) for each one added. */
    suspend fun pullAll(context: Context): List<Pair<Long, String>> = lock.withLock {
        val dao = AppDb.get(context).dao()
        val key = dao.settingsNow()?.signKey.orEmpty()
        if (key.isBlank()) return@withLock emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        for (item in runCatching { Inbox.pending(key) }.getOrDefault(emptyList())) {
            val added = runCatching {
                when (item.kind) {
                    "job" -> job(context, item.data)
                    "request" -> request(context, item.data).also { notifyRequest(context, it.first, item.data) }
                    else -> null
                }
            }.getOrNull() ?: continue
            Inbox.done(key, item.id)
            out += added
        }
        out
    }

    private fun digits(p: String) = p.filter(Char::isDigit).takeLast(10)

    /** A request from the booking page becomes an appointment to book (draft), with their photos. */
    suspend fun request(context: Context, d: JSONObject): Pair<Long, String> {
        val dao = AppDb.get(context).dao()
        val name = d.optString("name").trim(); val phone = d.optString("phone").trim(); val address = d.optString("address").trim()
        var cust = dao.customersNow().firstOrNull { digits(it.phone).length == 10 && digits(it.phone) == digits(phone) }
        if (cust == null) {
            val src = d.optString("source").trim().let { if (it.isBlank() || it == "Other") "Booking page" else it }
            val nc = Customer(name = name.ifBlank { phone }, phone = phone, email = d.optString("email"), address = address, source = src)
            cust = nc.copy(id = dao.insertCustomer(nc))
        } else if (address.isNotBlank() && cust.allProperties.none { it.address.equals(address, true) }) {
            cust = if (cust.address.isBlank()) cust.copy(address = address) else cust.withProperties(cust.otherProperties + Customer.Property("", address))
            dao.updateCustomer(cust)
        }
        val s = dao.settingsNow() ?: Settings()
        val times = d.optJSONArray("times")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val urgent = d.optBoolean("urgent")
        val problem = d.optString("problem").trim()
        val q = Quote(
            number = "Q-" + s.nextQuoteNo.toString().padStart(4, '0'), kind = Kind.APPT, jobType = JobType.REPAIR, textCustomer = true,
            title = (if (urgent) "URGENT · " else "") + problem.lineSequence().first().take(48).ifBlank { "Service request" },
            notes = problem, site = address, customerId = cust.id, customerName = cust.name,
            laborRate = s.laborRate, markupPct = s.markupPct, taxRatePct = s.repairTaxPct,
            privateNote = "Requested online" + (if (times.isNotEmpty()) " · good times: " + times.joinToString(", ") else "") + if (urgent) " · says it's urgent" else "",
        )
        val id = dao.insertQuote(q)
        dao.saveSettings(s.copy(nextQuoteNo = s.nextQuoteNo + 1))
        d.optJSONArray("photos")?.let { a ->
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            for (i in 0 until a.length()) runCatching {
                val bytes = Base64.decode(a.getString(i).substringAfter("base64,"), Base64.DEFAULT)
                val f = File(dir, "request_${id}_$i.jpg").apply { writeBytes(bytes) }
                dao.insertPhoto(JobPhoto(quoteId = id, path = f.absolutePath, caption = "From ${cust.name.substringBefore(' ')}"))
            }
        }
        return id to "New request · ${cust.name}"
    }

    private fun notifyRequest(context: Context, quoteId: Long, d: JSONObject) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("requests", "Job requests", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 600_000 + quoteId.toInt(), Intent(context, MainActivity::class.java).putExtra(Reminders.EXTRA_QUOTE, quoteId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = d.optString("problem").take(200)
        val n = NotificationCompat.Builder(context, "requests").setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle((if (d.optBoolean("urgent")) "URGENT request from " else "New job request from ") + d.optString("name"))
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + d.optString("address")))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(600_000 + quoteId.toInt(), n) }
    }

    suspend fun job(context: Context, d: JSONObject): Pair<Long, String> {
        val dao = AppDb.get(context).dao()
        fun digits(p: String) = p.filter(Char::isDigit).takeLast(10)
        val c = d.optJSONObject("customer") ?: JSONObject()
        val qo = d.optJSONObject("quote") ?: JSONObject()
        val name = c.optString("name").trim()
        val phone = c.optString("phone").trim()
        val site = qo.optString("site").trim()
        // Customer: match by phone, then name; otherwise add them.
        var cust = dao.customersNow().firstOrNull { (phone.isNotBlank() && digits(it.phone) == digits(phone)) } ?: dao.customersNow().firstOrNull { name.isNotBlank() && it.name.equals(name, true) }
        if (cust == null && name.isNotBlank()) {
            val nc = Customer(name = name, phone = phone, email = c.optString("email"), address = c.optString("address").ifBlank { site })
            cust = nc.copy(id = dao.insertCustomer(nc))
        }
        if (cust != null && site.isNotBlank() && cust.allProperties.none { it.address.equals(site, true) }) {
            cust = if (cust.address.isBlank()) cust.copy(address = site)
                   else cust.withProperties(cust.otherProperties + Customer.Property(c.optString("propertyLabel"), site))
            dao.updateCustomer(cust)
        }
        // Quote: update the one with this number, or make a new one.
        val number = qo.optString("number").trim()
        val existing = if (number.isNotBlank()) dao.quotesNow().firstOrNull { it.number.equals(number, true) } else null
        val s = dao.settingsNow() ?: Settings()
        val base = existing ?: Quote(number = "Q-" + s.nextQuoteNo.toString().padStart(4, '0'), laborRate = s.laborRate, markupPct = s.markupPct, taxRatePct = s.repairTaxPct)
        val q = Inbox.applyQuote(base, qo).copy(customerId = cust?.id ?: base.customerId, customerName = cust?.name ?: base.customerName)
        val id = if (existing != null) { dao.updateQuote(q); existing.id } else { val nid = dao.insertQuote(q); dao.saveSettings(s.copy(nextQuoteNo = s.nextQuoteNo + 1)); nid }
        // Materials (added to what's there; matched to your stock by name).
        val lines = d.optJSONArray("lines")
        if (lines != null && lines.length() > 0) {
            fun norm(x: String) = x.lowercase().replace(Regex("[^a-z0-9/.]+"), " ").trim()
            val stock = dao.materialsNow()
            for (i in 0 until lines.length()) {
                val l = lines.getJSONObject(i)
                val m = stock.firstOrNull { norm(it.name) == norm(l.optString("name")) }
                dao.insertLine(QuoteLine(quoteId = id, materialId = m?.id, name = m?.name ?: l.optString("name"), unit = l.optString("unit", m?.unit ?: "ea"),
                    qty = l.optDouble("qty", 1.0), unitCost = if (l.has("unitCost")) l.getDouble("unitCost") else m?.cost ?: 0.0, repair = l.optBoolean("repair")))
            }
        }
        return id to "${q.number} · ${q.customerName.ifBlank { q.title }}"
    }
}
