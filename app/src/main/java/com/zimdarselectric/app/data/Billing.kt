package com.zimdarselectric.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Invoices, payment reminders and review requests. */
object Billing {
    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    /** Money already received for the job: payments, plus the deposit until the job is marked paid in full. */
    fun paidSoFar(q: Quote) = if (q.paidAt != null) q.paidAmount else q.paidAmount + q.depositPaid

    /** Change orders that count toward the bill: signed or approved/done. */
    fun billableChanges(children: List<Quote>) = children.filter { it.signedAt != null || it.status == Status.APPROVED || it.status == Status.DONE }

    /** What the customer owes for the job: this quote plus approved change orders. */
    fun invoiceTotal(q: Quote, children: List<Quote>, lines: List<QuoteLine>, s: Settings): Double =
        (listOf(q) + billableChanges(children)).sumOf { j -> computeTotals(j, lines.filter { it.quoteId == j.id }, s).total }

    fun payload(q: Quote, children: List<Quote>, lines: List<QuoteLine>, customer: Customer?, s: Settings): JSONObject {
        val parts = JSONArray()
        (listOf(q) + billableChanges(children)).forEach { j ->
            val t = computeTotals(j, lines.filter { it.quoteId == j.id }, s)
            parts.put(JSONObject().put("number", j.number).put("title", j.title.ifBlank { if (j.parentId != null) "Change order" else "Electrical work" })
                .put("work", listOf(j.notes, j.repairNotes).filter { it.isNotBlank() }.joinToString("\n"))
                .put("materials", r2(t.materialsPrice)).put("labor", r2(t.labor)).put("tax", r2(t.salesTax)).put("total", r2(t.total))
                .put("changeOrder", j.parentId != null))
        }
        val total = invoiceTotal(q, children, lines, s)
        val done = q.completedAt ?: System.currentTimeMillis()
        return JSONObject()
            .put("business", JSONObject().put("name", s.businessName).put("phone", s.phone).put("email", s.email).put("address", s.address).put("license", s.license))
            .put("customer", JSONObject().put("name", customer?.name ?: q.customerName).put("address", customer?.address ?: ""))
            .put("invoice", JSONObject().put("number", q.number).put("title", q.title).put("site", q.site).put("date", done)
                .put("total", r2(total)).put("paid", r2(paidSoFar(q))).put("deposit", r2(if (q.depositPaidAt != null) q.depositPaid else 0.0)).put("due", r2(maxOf(0.0, total - Billing.paidSoFar(q))))
                .put("terms", s.paymentTerms).put("parts", parts))
            .put("pay", JSONObject().put("venmo", s.venmo).put("zelle", s.zelle).put("note", s.payNote).put("phone", s.phone)
                .put("card", cardLink(s, maxOf(0.0, total - Billing.paidSoFar(q))))
                .put("financing", if (s.financingLink.isNotBlank() && total >= s.financingMin) s.financingLink.trim() else ""))
    }

    private fun biz(s: Settings) = s.businessName.trim().ifBlank { "Your electrician" }

    /** Your card link with the amount filled in where it says {amount} (e.g. paypal.me/you/{amount}). */
    fun cardLink(s: Settings, amount: Double): String =
        s.cardPayLink.trim().replace("{amount}", "%.2f".format(java.util.Locale.US, amount))

    fun followUpText(q: Quote, s: Settings, total: Double, second: Boolean): String {
        val what = q.title.ifBlank { "the electrical work" }
        return if (!second) "${biz(s)}: Just checking in on your quote for $what (${q.number}, ${money(total)}). Any questions? I'm happy to help. You can review and sign here: ${q.signUrl}"
        else "${biz(s)}: Following up one last time on your quote for $what (${q.number}). If you'd like to go ahead, you can sign here: ${q.signUrl}  If now isn't the right time, no problem at all."
    }
    private fun money(v: Double) = java.text.NumberFormat.getCurrencyInstance(Locale.US).format(v)

    fun invoiceText(q: Quote, s: Settings, due: Double, url: String) =
        "${biz(s)}: Thank you! Your invoice for ${q.title.ifBlank { "the electrical work" }} (${q.number}) is ready. Amount due: ${money(due)}. View and pay here: $url"

    fun reminderText(q: Quote, s: Settings, due: Double, url: String) =
        "${biz(s)}: A friendly reminder that invoice ${q.number} for ${money(due)} is still open. View and pay here: $url  If you've already paid, thank you, and please disregard this."

    fun reviewText(customer: Customer?, q: Quote, s: Settings): String {
        val first = (customer?.name ?: q.customerName).trim().substringBefore(' ')
        return "${biz(s)}: Thanks${if (first.isNotBlank()) ", $first" else ""}, for choosing us! If you were happy with the work, a quick Google review would mean a lot to a small local business: ${s.reviewUrl.trim()}"
    }

    /** Texts are only sent 10 AM to 7 PM. */
    fun okHour(now: Long = System.currentTimeMillis()) = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) in 10..18

    /** Sends automatic invoice reminders and review requests that are due. */
    suspend fun runFollowUps(context: Context) {
        if (!okHour()) return
        val dao = AppDb.get(context).dao()
        val s = dao.settingsNow() ?: return
        val now = System.currentTimeMillis()
        val all = dao.quotesNow()
        val lines = dao.allLinesNow()
        // Payment reminder: once, N days after the invoice, if still unpaid.
        for (q in all) {
            val sent = q.invoiceSentAt ?: continue
            if (q.paidAt != null || q.invoiceRemindedAt != null || q.invoiceUrl.isBlank()) continue
            if (now - sent < s.invoiceReminderDays * 86_400_000L) continue
            val customer = q.customerId?.let { dao.customer(it) }
            val due = invoiceTotal(q, all.filter { it.parentId == q.id }, lines, s) - paidSoFar(q)
            if (due <= 0.5) continue
            CustomerTexts.sendText(context, q, customer, reminderText(q, s, due, q.invoiceUrl))
            dao.updateQuote(q.copy(invoiceRemindedAt = now))
        }
        // Quote follow-ups: unsigned quotes get a friendly nudge after N days, and one more N days later.
        if (s.followUpOn) for (q in all) {
            if (q.kind != Kind.QUOTE || q.signUrl.isBlank() || q.signToken.isBlank() || q.signedAt != null) continue
            if (q.status != Status.SENT || q.followUps >= 2) continue
            val since = q.lastFollowUpAt ?: q.sentAt ?: continue
            if (now - since < s.followUpDays * 86_400_000L) continue
            val customer = q.customerId?.let { dao.customer(it) }
            val total = computeTotals(q, lines.filter { it.quoteId == q.id }, s).total
            CustomerTexts.sendText(context, q, customer, followUpText(q, s, total, second = q.followUps >= 1))
            dao.updateQuote(q.copy(followUps = q.followUps + 1, lastFollowUpAt = now))
        }
        // Repeat work: texts when a reminder you set on a job comes due (generator service, detectors…).
        if (s.remindersOn) for (q in all) {
            if (q.remindLater.isBlank() || q.canceledAt != null) continue
            val items = RepeatWork.parse(q.remindLater)
            val due = items.indexOfFirst { it.sent == null && it.due <= now } .takeIf { it >= 0 } ?: continue
            val customer = q.customerId?.let { dao.customer(it) }
            if (customer?.phone.isNullOrBlank()) continue
            CustomerTexts.sendText(context, q, customer, RepeatWork.text(q, customer, s, items[due]))
            dao.updateQuote(q.copy(remindLater = RepeatWork.write(items.mapIndexed { i, it -> if (i == due) it.copy(sent = now) else it })))
        }
        // Review request: the day after the job is done (within a week).
        if (s.reviewUrl.isNotBlank()) for (q in all) {
            val done = q.completedAt ?: continue
            if (!q.askReview || q.reviewAskedAt != null || q.parentId != null) continue
            if (now - done < 20 * 3_600_000L || now - done > 7 * 86_400_000L) continue
            val customer = q.customerId?.let { dao.customer(it) }
            if (customer?.phone.isNullOrBlank()) continue
            CustomerTexts.sendText(context, q, customer, reviewText(customer, q, s))
            dao.updateQuote(q.copy(reviewAskedAt = now))
        }
    }

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<FollowUpWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("follow-ups", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun day(ms: Long) = SimpleDateFormat("EEE MMM d", Locale.US).format(Date(ms))
}

class FollowUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Billing.runFollowUps(applicationContext) }
        return Result.success()
    }
}
