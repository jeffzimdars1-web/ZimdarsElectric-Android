package com.zimdarselectric.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zimdarselectric.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Reminder texts to customers, sent from your own phone and number so they can reply to you. */
object CustomerTexts {
    private const val CHANNEL = "customer_texts"

    fun message(q: Quote, customer: Customer?, s: Settings, nightBefore: Boolean): String {
        val start = q.startAt ?: return ""
        val first = (customer?.name ?: q.customerName).trim().substringBefore(' ').ifBlank { "there" }
        val me = listOf(s.ownerName.trim(), s.businessName.trim()).filter { it.isNotBlank() }.let { if (it.size == 2) "${it[0]} with ${it[1]}" else it.firstOrNull() ?: "your electrician" }
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(start))
        val day = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(start))
        val window = if (s.arrivalWindow > 0) ", give or take ${s.arrivalWindow} minutes for weather or traffic" else ""
        val place = q.site.ifBlank { customer?.address ?: "" }.let { if (it.isBlank()) "" else " at $it" }
        return if (nightBefore) {
            if (q.hasTime) "Hi $first, this is $me. Reminder that I'll be$place tomorrow ($day) at $time$window. Reply here if you need to change anything."
            else "Hi $first, this is $me. Reminder that I'll be$place tomorrow ($day). I'll text you when I'm on my way. Reply here if you need to change anything."
        } else "Hi $first, it's $me. I'm on schedule to be there around $time$window. See you soon!"
    }

    fun canSend(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Sends the reminder text. If the phone won't let the app send it, posts a notification to send it with one tap. */
    fun remind(context: Context, q: Quote, customer: Customer?, s: Settings, nightBefore: Boolean) =
        sendText(context, q, customer, message(q, customer, s, nightBefore))

    /** "On my way, about 18 minutes" text. */
    fun onMyWay(q: Quote, customer: Customer?, s: Settings, minutes: Int): String {
        val first = (customer?.name ?: q.customerName).trim().substringBefore(' ').ifBlank { "there" }
        val me = listOf(s.ownerName.trim(), s.businessName.trim()).filter { it.isNotBlank() }.let { if (it.size == 2) "${it[0]} with ${it[1]}" else it.firstOrNull() ?: "your electrician" }
        val eta = if (minutes <= 2) "just a couple of minutes" else "about $minutes minutes"
        return "Hi $first, it's $me. I'm on my way and should be there in $eta. See you soon!"
    }

    /** Texts the customer from this phone. Returns true if it went out; otherwise leaves a tap-to-send notification. */
    fun sendText(context: Context, q: Quote, customer: Customer?, text: String): Boolean {
        val phone = customer?.phone?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (phone.length < 7) { notify(context, q, "Couldn't text ${q.customerName.ifBlank { "the customer" }}", "No cell number saved for them.", null); return false }
        val sent = canSend(context) && runCatching {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null)
        }.isSuccess
        val who = (customer?.name ?: q.customerName).ifBlank { phone }
        if (sent) notify(context, q, "Texted $who", text, null)
        else notify(context, q, "Tap to text $who", text, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text))
        return sent
    }

    private fun notify(context: Context, q: Quote, title: String, text: String, tap: Intent?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Customer reminder texts", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (tap != null) b.setContentIntent(PendingIntent.getActivity(context, 200_000 + q.id.toInt(), tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        runCatching { NotificationManagerCompat.from(context).notify(700_000 + q.id.toInt() + (if (title.startsWith("Tap")) 1 else 0), b.build()) }
    }
}
