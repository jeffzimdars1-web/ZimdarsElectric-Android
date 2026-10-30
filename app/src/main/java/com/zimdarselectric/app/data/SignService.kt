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
    fun payload(q: Quote, lines: List<QuoteLine>, customer: Customer?, s: Settings): JSONObject {
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
    }

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
        val key = dao.settingsNow()?.signKey?.trim().orEmpty()
        val waiting = dao.quotesAwaitingSignature()
        if (key.isBlank() || waiting.isEmpty()) return@withContext emptyList()
        val r = call(key, JSONObject().put("action", "status").put("tokens", JSONArray(waiting.map { it.signToken })))
        val items = r.optJSONArray("items") ?: return@withContext emptyList()
        val justSigned = mutableListOf<Quote>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val q = dao.quote(waiting.firstOrNull { w -> w.signToken == item.optString("token") }?.id ?: continue) ?: continue
            if (q.signedAt != null || q.signToken != item.optString("token")) continue
            val viewed = parseIso(item.optString("viewed_at"))
            when (item.optString("status")) {
                "signed" -> {
                    val png = item.optString("signature_png").substringAfter("base64,", "")
                    val file = File(context.filesDir, "signatures").apply { mkdirs() }.let { d -> File(d, "${q.number}_${q.id}.png") }
                    if (png.isNotBlank()) runCatching { file.writeBytes(Base64.decode(png, Base64.DEFAULT)) }
                    val signedAt = parseIso(item.optString("signed_at")) ?: System.currentTimeMillis()
                    val updated = q.copy(
                        signedAt = signedAt, viewedAt = viewed ?: q.viewedAt,
                        signerName = item.optString("signer_name"), signerIp = item.optString("signer_ip").takeIf { ip -> ip != "null" } ?: "",
                        signaturePath = if (file.exists()) file.absolutePath else "",
                        status = if (q.status in setOf(Status.DRAFT, Status.SENT)) Status.APPROVED else q.status,
                    )
                    dao.updateQuote(updated)
                    justSigned += updated
                }
                "confirmed" -> {
                    val at = parseIso(item.optString("confirmed_at")) ?: System.currentTimeMillis()
                    val updated = q.copy(confirmedAt = at, viewedAt = viewed ?: q.viewedAt)
                    dao.updateQuote(updated)
                    notifyConfirmed(context, updated)
                }
                "sent" -> if (viewed != null && q.viewedAt == null) dao.updateQuote(q.copy(viewedAt = viewed))
                "canceled" -> {} // replaced by a newer link
            }
        }
        justSigned
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
