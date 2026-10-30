package com.zimdarselectric.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zimdarselectric.app.MainActivity
import com.zimdarselectric.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Job reminders: the evening before and shortly before the start time. The app sets them itself
 * from each quote's date, so moving a job moves its reminders, and finished or declined jobs stop reminding.
 */
object Reminders {
    private const val CHANNEL = "job_reminders"
    const val EXTRA_QUOTE = "quoteId"
    private const val KIND_NIGHT = 0
    private const val KIND_SOON = 1

    private fun intent(context: Context, quoteId: Long, kind: Int) = PendingIntent.getBroadcast(
        context, (quoteId * 2 + kind).toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_QUOTE, quoteId).putExtra("kind", kind),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** When each reminder for [q] should fire, or empty if none. */
    fun times(q: Quote, s: Settings): List<Pair<Int, Long>> {
        val start = q.startAt ?: return emptyList()
        if (q.status == Status.DONE || q.status == Status.DECLINED) return emptyList()
        val out = mutableListOf<Pair<Int, Long>>()
        if (s.remindNightBefore) {
            val c = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, s.remindNightHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
            out += KIND_NIGHT to c.timeInMillis
        }
        if (s.remindMinutesBefore > 0 && q.hasTime) out += KIND_SOON to (start - s.remindMinutesBefore * 60_000L)
        return out
    }

    /** Sets (or clears) both reminders for every quote. Safe to call any time. */
    fun syncAll(context: Context, quotes: List<Quote>, s: Settings) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        quotes.forEach { q ->
            listOf(KIND_NIGHT, KIND_SOON).forEach { am.cancel(intent(context, q.id, it)) }
            times(q, s).filter { it.second > now }.forEach { (kind, at) ->
                // Inexact-but-allowed-while-idle: lands within a few minutes without special alarm permission.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, q.id, kind))
            }
        }
    }

    fun show(context: Context, q: Quote, kind: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val start = q.startAt ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Job reminders", NotificationManager.IMPORTANCE_HIGH))
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(start))
        val title = (if (kind == KIND_NIGHT) (if (q.hasTime) "Tomorrow $time" else "Tomorrow (time TBD)") else "Coming up at $time") + ": " + q.title.ifBlank { "Job" }
        val text = listOf(q.number, q.customerName, q.site).filter { it.isNotBlank() }.joinToString(" · ")
        val open = PendingIntent.getActivity(context, q.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_QUOTE, q.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (q.notes.isNotBlank()) "\n" + q.notes else ""))
            .setContentIntent(open).setAutoCancel(true)
        if (q.site.isNotBlank()) {
            val maps = PendingIntent.getActivity(context, 100_000 + q.id.toInt(),
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q.site))),
                PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, "Directions", maps)
        }
        runCatching { NotificationManagerCompat.from(context).notify(500_000 + (q.id * 2 + kind).toInt(), b.build()) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(context).dao()
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Reminders.syncAll(context, dao.quotesNow(), dao.settingsNow() ?: Settings())
                } else {
                    val q = dao.quote(intent.getLongExtra(Reminders.EXTRA_QUOTE, -1))
                    // Only remind if the job is still on and still at the time this reminder was set for.
                    if (q != null) {
                        val s = dao.settingsNow() ?: Settings()
                        val kind = intent.getIntExtra("kind", 0)
                        val due = Reminders.times(q, s).firstOrNull { it.first == kind }?.second
                        if (due != null && kotlin.math.abs(due - System.currentTimeMillis()) < 6 * 3_600_000L) Reminders.show(context, q, kind)
                    }
                }
            } finally { pending.finish() }
        }
    }
}
