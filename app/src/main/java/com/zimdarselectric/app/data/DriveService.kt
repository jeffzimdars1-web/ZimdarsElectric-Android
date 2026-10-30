package com.zimdarselectric.app.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zimdarselectric.app.MainActivity
import com.zimdarselectric.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The drive being tracked right now, if any. */
data class DriveState(
    val quoteId: Long?,
    val toLabel: String,
    val startedAt: Long,
    val miles: Double,
    /** True for the drive back home after a job. */
    val home: Boolean,
)

/**
 * Tracks one drive with GPS: from "On my way" until you reach the job (or home), then stops by itself.
 * Logs the real miles driven, and on arrival starts the job clock.
 */
class DriveService : Service(), LocationListener {
    companion object {
        private const val CHANNEL = "drive"
        private const val NOTE_ID = 42
        private const val ARRIVE_METERS = 150.0
        private val _state = MutableStateFlow<DriveState?>(null)
        val state: StateFlow<DriveState?> get() = _state

        fun startToJob(context: Context, quoteId: Long) = start(context, Intent(context, DriveService::class.java).setAction("job").putExtra("quoteId", quoteId))
        fun startHome(context: Context, fromQuoteId: Long?) = start(context, Intent(context, DriveService::class.java).setAction("home").putExtra("quoteId", fromQuoteId ?: -1))
        fun stop(context: Context) = context.startService(Intent(context, DriveService::class.java).setAction("stop"))
        private fun start(context: Context, i: Intent) = ContextCompat.startForegroundService(context, i)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var targetLat: Double? = null
    private var targetLon: Double? = null
    private var last: Location? = null
    private var meters = 0.0
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { finish(arrived = false); return START_NOT_STICKY }
            "job", "home" -> begin(intent.action == "home", intent.getLongExtra("quoteId", -1).takeIf { it > 0 })
        }
        return START_NOT_STICKY
    }

    private fun begin(home: Boolean, quoteId: Long?) {
        meters = 0.0; last = null; finishing = false
        val label = if (home) "Home" else "job"
        _state.value = DriveState(quoteId, label, System.currentTimeMillis(), 0.0, home)
        goForeground("Tracking your drive", "Starting GPS…")
        scope.launch {
            val dao = AppDb.get(this@DriveService).dao()
            if (home) {
                val addr = dao.settingsNow()?.address.orEmpty()
                Geo.locate(addr)?.let { targetLat = it.lat; targetLon = it.lon }
                _state.value = _state.value?.copy(toLabel = "Home")
            } else if (quoteId != null) {
                var q = dao.quote(quoteId)
                if (q != null && (q.siteLat == null || q.siteLon == null) && q.site.isNotBlank()) {
                    Geo.locate(q.site)?.let { p -> q = q!!.copy(siteLat = p.lat, siteLon = p.lon); dao.updateQuote(q!!) }
                }
                targetLat = q?.siteLat; targetLon = q?.siteLon
                _state.value = _state.value?.copy(toLabel = q?.customerName?.ifBlank { q?.site } ?: "job")
            }
            listen()
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (!Geo.canLocate(this)) { finish(arrived = false); return }
        val lm = getSystemService(LocationManager::class.java)
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 15f, this, Looper.getMainLooper()) }
        runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 50f, this, Looper.getMainLooper()) }
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 60f) return // ignore rough fixes so miles stay honest
        last?.let { prev ->
            val d = prev.distanceTo(loc).toDouble()
            if (d > 10) meters += d
        }
        last = loc
        val s = _state.value ?: return
        _state.value = s.copy(miles = meters / 1609.344)
        val tLat = targetLat; val tLon = targetLon
        if (tLat != null && tLon != null && s.startedAt < System.currentTimeMillis() - 60_000 &&
            Geo.metersBetween(loc.latitude, loc.longitude, tLat, tLon) < ARRIVE_METERS) {
            finish(arrived = true)
        } else {
            goForeground(if (s.home) "Driving home" else "On the way to ${s.toLabel}", "%.1f mi so far · tap Stop when you're there".format(meters / 1609.344))
        }
    }

    private fun finish(arrived: Boolean) {
        if (finishing) return
        finishing = true
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        val s = _state.value
        val miles = meters / 1609.344
        scope.launch {
            if (s != null) {
                val dao = AppDb.get(this@DriveService).dao()
                val q = s.quoteId?.let { dao.quote(it) }
                if (miles >= 0.1) dao.insertTrip(Trip(
                    startedAt = s.startedAt, endedAt = System.currentTimeMillis(), miles = Math.round(miles * 10) / 10.0,
                    fromLabel = if (s.home) (q?.site ?: "") else "", toLabel = if (s.home) "Home" else (q?.site ?: s.toLabel),
                    quoteId = q?.id, purpose = q?.let { (if (s.home) "Return from " else "") + "${it.number} ${it.title}".trim() + if (it.customerName.isNotBlank()) " · ${it.customerName}" else "" } ?: "",
                ))
                if (arrived && !s.home && q != null) {
                    dao.updateQuote(q.copy(arrivedAt = System.currentTimeMillis(), leftAt = null))
                    val settings = dao.settingsNow() ?: Settings()
                    if (settings.textOnArrival) {
                        val c = q.customerId?.let { dao.customer(it) }
                        val first = (c?.name ?: q.customerName).substringBefore(' ').ifBlank { "there" }
                        CustomerTexts.sendText(this@DriveService, q, c, "Hi $first, it's ${settings.ownerName.ifBlank { "your electrician" }} with ${settings.businessName}. I'm here!")
                    }
                }
                notifyDone(if (arrived) (if (s.home) "Home" else "Arrived at ${s.toLabel}") else "Drive stopped",
                    "%.1f mi logged".format(miles) + if (arrived && !s.home) " · job clock started" else "")
            }
            _state.value = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Drive tracking", NotificationManager.IMPORTANCE_LOW))
    }

    private fun goForeground(title: String, text: String) {
        channel()
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, DriveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(0, "Stop", stop).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTE_ID, n)
    }

    private fun notifyDone(title: String, text: String) {
        channel()
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_bolt).setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTE_ID + 1, n) }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
