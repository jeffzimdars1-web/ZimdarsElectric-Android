package com.zimdarselectric.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.zimdarselectric.app.data.AppDb
import com.zimdarselectric.app.data.Kind
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Home screen widget: next job (On my way / Navigate), the rest of today, + Quote / + Appt. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_ACTION = "widget_action"
        const val ACTION_ON_MY_WAY = "onmyway"
        const val ACTION_NEW_QUOTE = "new_quote"
        const val ACTION_NEW_APPT = "new_appt"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Redraw every widget on the home screen (call when jobs change). */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            val m = AppWidgetManager.getInstance(app)
            val ids = m.getAppWidgetIds(ComponentName(app, TodayWidget::class.java))
            if (ids.isEmpty()) return
            scope.launch { runCatching { render(app, m, ids) } }
        }

        private fun open(context: Context, code: Int, quoteId: Long? = null, action: String? = null): PendingIntent {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            quoteId?.let { i.putExtra(com.zimdarselectric.app.data.Reminders.EXTRA_QUOTE, it) }
            action?.let { i.putExtra(EXTRA_ACTION, it) }
            return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Jobs and appointments still to come, soonest first (one in progress counts for 3 hours). */
        fun upcoming(quotes: List<Quote>, now: Long): List<Quote> = quotes.filter { q ->
            val s = q.startAt ?: return@filter false
            q.canceledAt == null && q.status != Status.DONE && q.status != Status.DECLINED && q.leftAt == null &&
                s + (if (q.hasTime) 3 * 3_600_000L else 24 * 3_600_000L) > now
        }.sortedBy { it.startAt }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val dao = AppDb.get(context).dao()
            val now = System.currentTimeMillis()
            val list = upcoming(dao.quotesNow(), now)
            val time = SimpleDateFormat("h:mm a", Locale.US)
            val dayName = SimpleDateFormat("EEE MMM d", Locale.US)
            fun sameDay(a: Long, b: Long): Boolean {
                val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
                return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
            }
            val tomorrow = now + 86_400_000L
            fun whenLabel(q: Quote): String {
                val s = q.startAt!!
                val day = when { sameDay(s, now) -> "Today"; sameDay(s, tomorrow) -> "Tomorrow"; else -> dayName.format(Date(s)) }
                return day + " · " + if (q.hasTime) time.format(Date(s)) else "time TBD"
            }
            val next = list.firstOrNull()
            val rest = list.drop(1).filter { sameDay(it.startAt!!, next?.startAt ?: now) }.take(3)
            // Weather: at the next job (for the time you'll be there, within the next 5 days), otherwise at your office now.
            val settings = dao.settingsNow()
            val weatherLine = runCatching {
                val soon = next?.takeIf { (it.startAt ?: 0) - now < 5 * 86_400_000L }
                var lat = soon?.siteLat; var lon = soon?.siteLon
                if (soon != null && (lat == null || lon == null) && soon.site.isNotBlank()) {
                    com.zimdarselectric.app.data.Geo.locate(soon.site)?.let { lat = it.lat; lon = it.lon; dao.updateQuote(soon.copy(siteLat = it.lat, siteLon = it.lon)) }
                }
                if (lat != null && lon != null) {
                    val at = maxOf(now, soon!!.startAt!!.let { if (soon.hasTime) it else it + 10 * 3_600_000L })
                    com.zimdarselectric.app.data.Weather.at(context, lat!!, lon!!, at)?.let { h ->
                        h.line + "  ·  at the job" + if (sameDay(at, now)) "" else " " + whenLabel(soon).substringBefore(" ·")
                    }
                } else {
                    val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
                    val addr = settings?.address.orEmpty()
                    var oLat = prefs.getFloat("office_lat", 0f).toDouble(); var oLon = prefs.getFloat("office_lon", 0f).toDouble()
                    if ((oLat == 0.0 || prefs.getString("office_addr", "") != addr) && addr.isNotBlank()) {
                        com.zimdarselectric.app.data.Geo.locate(addr)?.let { p ->
                            oLat = p.lat; oLon = p.lon
                            prefs.edit().putFloat("office_lat", p.lat.toFloat()).putFloat("office_lon", p.lon.toFloat()).putString("office_addr", addr).apply()
                        }
                    }
                    if (oLat != 0.0) com.zimdarselectric.app.data.Weather.at(context, oLat, oLon)?.let { it.line + "  ·  now" } else null
                }
            }.getOrNull()
            for (id in ids) {
                val v = RemoteViews(context.packageName, R.layout.widget_today)
                v.setTextViewText(R.id.w_weather, weatherLine ?: "")
                v.setViewVisibility(R.id.w_weather, if (weatherLine == null) View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.w_date, dayName.format(Date(now)).uppercase())
                v.setOnClickPendingIntent(R.id.w_root, open(context, 10))
                v.setOnClickPendingIntent(R.id.w_new_quote, open(context, 11, action = ACTION_NEW_QUOTE))
                v.setOnClickPendingIntent(R.id.w_new_appt, open(context, 12, action = ACTION_NEW_APPT))
                if (next == null) {
                    v.setTextViewText(R.id.w_next_label, "NEXT")
                    v.setTextViewText(R.id.w_next_title, "Nothing scheduled")
                    v.setTextViewText(R.id.w_next_sub, "Enjoy the free time, or book something below.")
                    v.setViewVisibility(R.id.w_next_buttons, View.GONE)
                    v.setTextViewText(R.id.w_later, "")
                } else {
                    val appt = next.kind == Kind.APPT
                    val inProgress = next.arrivedAt != null && next.leftAt == null
                    v.setTextViewText(R.id.w_next_label, (if (inProgress) "ON SITE NOW" else "NEXT · " + whenLabel(next)).uppercase() +
                        if (appt) "  ·  APPT" else "")
                    v.setTextViewText(R.id.w_next_title, next.title.ifBlank { if (appt) "Service call" else "Job" })
                    v.setTextViewText(R.id.w_next_sub, listOf(next.customerName, next.site).filter { it.isNotBlank() }.joinToString(" · ") +
                        if (next.privateNote.isNotBlank()) "\n📝 " + next.privateNote.trim() else "")
                    v.setOnClickPendingIntent(R.id.w_next_title, open(context, 13, quoteId = next.id))
                    v.setViewVisibility(R.id.w_next_buttons, if (inProgress) View.GONE else View.VISIBLE)
                    v.setOnClickPendingIntent(R.id.w_onmyway, open(context, 14, quoteId = next.id, action = ACTION_ON_MY_WAY))
                    if (next.site.isNotBlank()) {
                        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(next.site))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(next.site))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val target = if (nav.resolveActivity(context.packageManager) != null) nav else fallback
                        v.setOnClickPendingIntent(R.id.w_nav, PendingIntent.getActivity(context, 15, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    } else v.setOnClickPendingIntent(R.id.w_nav, open(context, 15, quoteId = next.id))
                    val later = rest.joinToString("\n") { q ->
                        (if (q.hasTime) time.format(Date(q.startAt!!)) else "Any time") + "  ·  " + q.title.ifBlank { "Job" } +
                            (if (q.customerName.isNotBlank()) " · " + q.customerName.substringBefore(' ') else "")
                    }
                    val more = list.size - 1 - rest.size
                    v.setTextViewText(R.id.w_later, if (later.isBlank()) (if (more > 0) "$more more coming up" else "") else "LATER\n$later")
                }
                manager.updateAppWidget(id, v)
            }
        }
    }
}
