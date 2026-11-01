package com.zimdarselectric.app.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Missed-call text-back: when a call rings and you don't pick up, the caller gets a text from your phone.
 * Only numbers not in your phone contacts (and customers, if you choose), 7 AM–9 PM, once per number per 12 hours.
 */
object MissedCalls {
    const val BOOK_URL = "https://jeffzimdars1-web.github.io/ZimdarsElectric-Android/book/"
    private fun prefs(c: Context) = c.getSharedPreferences("missed_calls", Context.MODE_PRIVATE)

    fun permissionsNeeded(context: Context): Array<String> = listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.SEND_SMS)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun defaultText(s: Settings): String {
        val who = listOf(s.ownerName.trim(), s.businessName.trim()).filter { it.isNotBlank() }.let { if (it.size == 2) "${it[0]} with ${it[1]}" else it.firstOrNull() ?: "your electrician" }
        return "Hi, this is $who. Sorry I missed your call, I'm probably on a job. I'll call you back as soon as I can, or reply here with what you need. You can also request a visit here: $BOOK_URL"
    }

    private fun digits(p: String) = p.filter { it.isDigit() }.takeLast(10)

    private fun inContacts(context: Context, number: String): Boolean = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        context.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** Called when a call ends without being answered. */
    suspend fun onMissed(context: Context, number: String) {
        val dao = AppDb.get(context).dao()
        val s = dao.settingsNow() ?: return
        if (!s.missedCallOn || digits(number).length < 10) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour !in 7..20) return
        val p = prefs(context)
        val key = "last_" + digits(number)
        if (System.currentTimeMillis() - p.getLong(key, 0) < 12 * 3_600_000L) return
        val customer = dao.customersNow().firstOrNull { digits(it.phone) == digits(number) }
        val allowed = when {
            customer != null -> s.missedCallCustomers
            inContacts(context, number) -> false // friends and family
            else -> true
        }
        if (!allowed) return
        val text = s.missedCallText.ifBlank { defaultText(s) }
        val ok = runCatching {
            val sms = if (android.os.Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java)
                      else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            CustomerTexts.splitForSms(text).forEach { part -> sms.sendMultipartTextMessage(number, null, sms.divideMessage(part), null, null) }
        }.isSuccess
        if (ok) p.edit().putLong(key, System.currentTimeMillis()).putInt("count", p.getInt("count", 0) + 1).apply()
    }

    fun sentCount(context: Context) = prefs(context).getInt("count", 0)
}

/** Watches the phone ringing: rang and was never picked up = missed. */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val p = context.getSharedPreferences("missed_calls", Context.MODE_PRIVATE)
        @Suppress("DEPRECATION") val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> if (!number.isNullOrBlank())
                p.edit().putString("ringing", number).putBoolean("answered", false).apply()
            TelephonyManager.EXTRA_STATE_OFFHOOK -> p.edit().putBoolean("answered", true).apply()
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val ringing = p.getString("ringing", null)
                val answered = p.getBoolean("answered", true)
                p.edit().remove("ringing").putBoolean("answered", false).apply()
                if (ringing != null && !answered) {
                    val pending = goAsync()
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        try { MissedCalls.onMissed(context.applicationContext, ringing) } finally { pending.finish() }
                    }
                }
            }
        }
    }
}
