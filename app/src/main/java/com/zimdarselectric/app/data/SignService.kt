package com.zimdarselectric.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zimdarselectric.app.MainActivity
import com.zimdarselectric.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Customer e-signatures. The quote is sent to a small online service (Supabase); the customer opens
 * a private link, signs, and the app picks up the signature and marks the quote Approved.
 */
object SignService {
    const val API = "https://dmaslbfwpsfnnnyushbc.supabase.co/functions/v1/sign"

    class SignException(message: String) : Exception(message)

    private fun call(key: String?, body: JSONObject): JSONObject {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
            if (key != null) setRequestProperty("x-app-key", key)
        }
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw SignException("The signing key in Settings isn't right.")
            if (code !in 200..299) throw SignException("Signing service error ($code).")
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    /** What the customer sees: a snapshot of the quote at the moment it's sent. */
    fun payload(q: Quote, lines: List<QuoteLine>, customer: Customer?, s: Settings, changeOf: String? = null): JSONObject {
        val t = computeTotals(q, lines, s)
        val factor = 1 + q.markupPct / 100.0
        fun r2(v: Double) = Math.round(v * 100) / 100.0
        return JSONObject()
            .put("business", JSONObject().put("name", s.businessName).put("phone", s.phone).put("email", s.email)
                .put("address", s.address).put("license", s.license).put("insurer", s.insurer))
            .put("customer", JSONObject().put("name", customer?.name ?: q.customerName).put("phone", customer?.phone ?: "")
                .put("email", customer?.email ?: "").put("address", customer?.address ?: ""))
            .put("quote", JSONObject().put("number", q.number).put("date", q.createdAt)
                .put("validUntil", q.createdAt + s.quoteValidDays * 86_400_000L).put("title", q.title).put("site", q.site)
                .put("jobType", q.jobType).put("scheduled", q.scheduled).put("notes", q.notes).put("repairNotes", q.repairNotes))
            .put("lines", JSONArray().apply {
                lines.forEach { l ->
                    put(JSONObject().put("name", l.name).put("qty", l.qty).put("unit", l.unit)
                        .put("price", r2(l.unitCost * factor)).put("total", r2(l.qty * l.unitCost * factor)).put("repair", l.repair))
                }
            })
            .put("labor", JSONObject().put("hours", q.laborHours).put("rate", q.laborRate).put("amount", r2(t.labor))
                .put("repairHours", q.repairHours).put("installAmount", r2(q.laborHours * q.laborRate)).put("repairAmount", r2(q.repairHours * q.laborRate)))
            .put("parts", JSONObject().put("install", r2(t.installPart)).put("repair", r2(t.repairPart)))
            .put("totals", JSONObject().put("materials", r2(t.materialsPrice)).put("labor", r2(t.labor))
                .put("subtotal", r2(t.materialsPrice + t.labor)).put("taxRate", q.taxRatePct).put("tax", r2(t.salesTax)).put("total", r2(t.total)))
            .put("terms", JSONObject().put("rates", s.chargeNote).put("payment", s.paymentTerms).put("warranty", s.warranty))
            .put("notices", JSONArray(contractNotices(s)))
            .apply {
                if (q.needsSt124) put("st124", st124Info(q))
                if (changeOf != null) getJSONObject("quote").put("changeOf", changeOf)
            }
    }

    fun st124Info(q: Quote): JSONObject = JSONObject().put("number", q.number).put("project", q.title)
        .put("site", q.site).put("description", St124Pdf.description(q))

    /** The ST-124-only page (for a job already signed or done). */
    fun st124Payload(q: Quote, customer: Customer?, s: Settings): JSONObject = JSONObject()
        .put("business", JSONObject().put("name", s.businessName).put("phone", s.phone).put("email", s.email).put("address", s.address))
        .put("customer", JSONObject().put("name", customer?.name ?: q.customerName).put("address", customer?.address?.ifBlank { q.site } ?: q.site))
        .put("st124", st124Info(q))

    /** Plain-language notices for a NY home improvement agreement. */
    fun contractNotices(s: Settings): List<String> = listOf(
        "Any payment made before the work is finished will be held in an escrow account or covered by a bond, as required by NY Lien Law §71-a(4).",
        "If you agreed to this at your home after an in-person sales visit, you may cancel without penalty within three business days of signing by telling ${s.businessName} in writing.",
        "Changes to the work or price will be agreed in writing (text or email is fine) before that work is done.",
        "Materials stay the property of ${s.businessName} until paid for in full.",
    )

    data class Link(val token: String, val url: String)

    /** What the customer sees on the appointment confirmation page. */
    fun apptPayload(q: Quote, customer: Customer?, s: Settings): JSONObject = JSONObject()
        .put("business", JSONObject().put("name", s.businessName).put("phone", s.phone).put("email", s.email)
            .put("address", s.address).put("license", s.license).put("insurer", s.insurer).put("owner", s.ownerName))
        .put("customer", JSONObject().put("name", customer?.name ?: q.customerName))
        .put("appt", JSONObject().put("number", q.number).put("start", q.startAt ?: JSONObject.NULL).put("hasTime", q.hasTime)
            .put("windowMin", s.arrivalWindow).put("site", q.site.ifBlank { customer?.address ?: "" })
            .put("reason", q.notes.trim().ifBlank { q.title.takeIf { it != "Service call" } ?: "" }))
        .put("terms", JSONObject().put("rates", s.chargeNote))

    suspend fun create(key: String, payload: JSONObject, number: String, replaceToken: String?, kind: String = "quote"): Link = withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", "create").put("quote_number", number).put("payload", payload).put("kind", kind)
        if (!replaceToken.isNullOrBlank()) body.put("replace", replaceToken)
        val r = call(key, body)
        Link(r.getString("token"), r.getString("url"))
    }

    /** Shows "Paid" on the invoice page. */
    suspend fun markInvoicePaid(key: String, token: String) = withContext(Dispatchers.IO) {
        call(key, JSONObject().put("action", "paid").put("t", token))
    }

    suspend fun cancel(key: String, token: String) = withContext(Dispatchers.IO) {
        call(key, JSONObject().put("action", "cancel").put("t", token))
    }

    private fun parseIso(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    /**
     * Checks every quote waiting for a signature. Signed ones get the signature saved and move to Approved.
     * Returns the quotes that were just signed.
     */
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun sync(context: Context): List<Quote> = lock.withLock { syncLocked(context) }

    private suspend fun syncLocked(context: Context): List<Quote> = withContext(Dispatchers.IO) {
        val dao = AppDb.get(context).dao()
        val settings = dao.settingsNow()
        val key = settings?.signKey?.trim().orEmpty()
        val waiting = dao.quotesAwaitingSignature()
        val waitingSt = dao.quotesAwaitingSt124()
        if (key.isBlank() || (waiting.isEmpty() && waitingSt.isEmpty())) return@withContext emptyList()
        val tokens = waiting.map { it.signToken } + waitingSt.map { it.st124Token }
        val r = call(key, JSONObject().put("action", "status").put("tokens", JSONArray(tokens)))
        val items = r.optJSONArray("items") ?: return@withContext emptyList()
        val justSigned = mutableListOf<Quote>()
        fun saveSignature(item: JSONObject, name: String): File? {
            val png = item.optString("signature_png").substringAfter("base64,", "")
            val file = File(context.filesDir, "signatures").apply { mkdirs() }.let { d -> File(d, name) }
            if (png.isNotBlank()) runCatching { file.writeBytes(Base64.decode(png, Base64.DEFAULT)) }
            return file.takeIf { it.exists() }
        }
        suspend fun makeSt124(q: Quote, item: JSONObject, sig: File?, signedAt: Long): String {
            val st = item.optJSONObject("st124") ?: return ""
            val customer = q.customerId?.let { dao.customer(it) }
            return runCatching {
                St124Pdf.make(context, q, customer, settings ?: Settings(), item.optString("signer_name"), sig,
                    st.optString("role", "owner"), st.optString("contract", "not"), signedAt).absolutePath
            }.getOrDefault("")
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val token = item.optString("token")
            val viewed = parseIso(item.optString("viewed_at"))
            // ST-124 on its own
            waitingSt.firstOrNull { it.st124Token == token }?.let { w ->
                val q = dao.quote(w.id) ?: return@let
                if (item.optString("status") == "signed" && q.st124SignedAt == null) {
                    val at = parseIso(item.optString("signed_at")) ?: System.currentTimeMillis()
                    val sig = saveSignature(item, "${q.number}_${q.id}_st124.png")
                    val path = makeSt124(q, item, sig, at)
                    val updated = q.copy(st124SignedAt = at, st124Path = path)
                    dao.updateQuote(updated)
                    notifySt124(context, updated)
                }
                return@let
            }
            val q = dao.quote(waiting.firstOrNull { w -> w.signToken == token }?.id ?: continue) ?: continue
            if (q.signedAt != null || q.signToken != token) continue
            when (item.optString("status")) {
                "signed" -> {
                    val sig = saveSignature(item, "${q.number}_${q.id}.png")
                    val signedAt = parseIso(item.optString("signed_at")) ?: System.currentTimeMillis()
                    var updated = q.copy(
                        signedAt = signedAt, viewedAt = viewed ?: q.viewedAt,
                        signerName = item.optString("signer_name"), signerIp = item.optString("signer_ip").takeIf { ip -> ip != "null" } ?: "",
                        signaturePath = sig?.absolutePath ?: "",
                        status = if (q.status in setOf(Status.DRAFT, Status.SENT)) Status.APPROVED else q.status,
                    )
                    if (item.optJSONObject("st124") != null) {
                        val path = makeSt124(updated, item, sig, signedAt)
                        if (path.isNotBlank()) updated = updated.copy(st124SignedAt = signedAt, st124Path = path)
                    }
                    dao.updateQuote(updated)
                    justSigned += updated
                }
                "confirmed" -> {
                    val at = parseIso(item.optString("confirmed_at")) ?: System.currentTimeMillis()
                    val updated = q.copy(confirmedAt = at, viewedAt = viewed ?: q.viewedAt)
                    dao.updateQuote(updated)
                    notifyConfirmed(context, updated)
                }
                "sent" -> {
                    var updated = if (viewed != null && q.viewedAt == null) q.copy(viewedAt = viewed) else q
                    item.optJSONObject("reschedule")?.let { rs ->
                        val at = parseIso(rs.optString("requested_at")) ?: return@let
                        if (at <= (q.rescheduleRequestedAt ?: 0L)) return@let
                        val (whenMs, label) = rescheduleTime(rs)
                        updated = updated.copy(rescheduleRequestedAt = at, rescheduleWhen = whenMs, rescheduleText = label,
                            rescheduleNote = rs.optString("note").takeIf { it != "null" }.orEmpty())
                        notifyReschedule(context, updated)
                    }
                    if (updated != q) dao.updateQuote(updated)
                }
                "canceled" -> {} // replaced by a newer link
            }
        }
        justSigned
    }

    /** The requested day/time as a time on this phone (morning = 9 AM, afternoon = 1 PM) and a readable label. */
    fun rescheduleTime(rs: JSONObject): Pair<Long, String> {
        val d = java.time.LocalDate.parse(rs.optString("date"))
        val part = rs.optString("part")
        val t = when (part) {
            "time" -> runCatching { java.time.LocalTime.parse(rs.optString("time")) }.getOrDefault(java.time.LocalTime.of(9, 0))
            "afternoon" -> java.time.LocalTime.of(13, 0)
            else -> java.time.LocalTime.of(9, 0)
        }
        val ms = d.atTime(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day = d.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US))
        val label = when (part) {
            "time" -> day + " at " + t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
            "morning" -> "$day, morning"
            "afternoon" -> "$day, afternoon"
            else -> "$day, any time"
        }
        return ms to label
    }

    fun notifyReschedule(context: Context, q: Quote) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Customer signatures", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 500_000 + q.id.toInt(), Intent(context, MainActivity::class.java).putExtra(Reminders.EXTRA_QUOTE, q.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = q.rescheduleText + if (q.rescheduleNote.isNotBlank()) " · \"${q.rescheduleNote}\"" else ""
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("${q.customerName.ifBlank { "Customer" }} asked for a different time")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(500_000 + q.id.toInt(), n) }
    }

    fun notifySt124(context: Context, q: Quote) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Customer signatures", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 400_000 + q.id.toInt(), Intent(context, MainActivity::class.java).putExtra(Reminders.EXTRA_QUOTE, q.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("${q.customerName.ifBlank { "Customer" }} signed the ST-124")
            .setContentText("${q.number} · saved with the job").setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(400_000 + q.id.toInt(), n) }
    }

    // ---------- Notifications ----------
    private const val CHANNEL = "signatures"

    fun notifySigned(context: Context, quotes: List<Quote>) {
        if (quotes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Customer signatures", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        quotes.forEach { q ->
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("${q.signerName.ifBlank { q.customerName }} signed ${q.number}")
                .setContentText("${q.title.ifBlank { "Quote" }} is approved.")
                .setContentIntent(open).setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(q.id.toInt(), n) }
        }
    }

    fun notifyConfirmed(context: Context, q: Quote) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Customer signatures", NotificationManager.IMPORTANCE_HIGH))
        val day = q.startAt?.let { java.text.SimpleDateFormat("EEE MMM d", java.util.Locale.US).format(java.util.Date(it)) } ?: ""
        val open = PendingIntent.getActivity(context, q.id.toInt(), Intent(context, MainActivity::class.java).putExtra(Reminders.EXTRA_QUOTE, q.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle("${q.customerName.ifBlank { "Customer" }} confirmed the appointment")
            .setContentText(listOf(day, q.site).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(300_000 + q.id.toInt(), n) }
    }

    /** Checks about every 15 minutes while the app is closed (Android decides the exact timing). */
    fun scheduleBackgroundCheck(context: Context) {
        val req = PeriodicWorkRequestBuilder<SignWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("sign-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SignService.notifySigned(applicationContext, SignService.sync(applicationContext))
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
