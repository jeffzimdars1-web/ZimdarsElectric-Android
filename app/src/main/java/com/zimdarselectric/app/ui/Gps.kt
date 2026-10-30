package com.zimdarselectric.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.Nav
import com.zimdarselectric.app.Page
import com.zimdarselectric.app.data.DriveService
import com.zimdarselectric.app.data.Geo
import com.zimdarselectric.app.data.Kind
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.Trip
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.toNum
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("h:mm a", Locale.US)

/** An address box that suggests real addresses as you type, like Google Maps. */
@Composable
fun AddressField(label: String, value: String, key: Any, modifier: Modifier = Modifier, onValue: (String) -> Unit, onPlace: (Geo.Place) -> Unit = { onValue(it.label) }) {
    var text by remember(key) { mutableStateOf(value) }
    var picked by remember(key) { mutableStateOf(true) }
    var hints by remember { mutableStateOf(emptyList<Geo.Place>()) }
    LaunchedEffect(text, picked) {
        if (picked || text.trim().length < 4) { hints = emptyList(); return@LaunchedEffect }
        delay(350) // wait for typing to pause
        hints = Geo.suggest(text)
    }
    Column(modifier) {
        Field(label, text, { text = it; picked = false; onValue(it) })
        if (hints.isNotEmpty()) SuggestionList(hints.map { it.label to "" }) { i ->
            val p = hints[i]; text = p.label; picked = true; hints = emptyList(); onPlace(p)
        }
    }
}

/**
 * The job's address. Offers the customer's saved address in one tap, suggests addresses as you type,
 * and when you pick one for a customer with no address on file, saves it to them for next time.
 */
@Composable
fun SiteAddress(vm: AppViewModel, q: com.zimdarselectric.app.data.Quote, customer: com.zimdarselectric.app.data.Customer?, label: String, save: (com.zimdarselectric.app.data.Quote) -> Unit) {
    var fillKey by remember { mutableStateOf(0) }
    AddressField(label, q.site, "site${q.id}-$fillKey", onValue = { save(q.copy(site = it, siteLat = null, siteLon = null)) },
        onPlace = { p ->
            save(q.copy(site = p.label, siteLat = p.lat, siteLon = p.lon))
            if (customer != null && customer.address.isBlank()) vm.saveCustomer(customer.copy(address = p.label))
        })
    val saved = customer?.address.orEmpty()
    if (q.site.isBlank() && saved.isNotBlank()) {
        Label("Use ${customer!!.name.substringBefore(' ')}'s address: $saved →", color = Z.neon,
            modifier = Modifier.clickable { save(q.copy(site = saved, siteLat = null, siteLon = null)); fillKey++ }.padding(vertical = 10.dp))
    } else if (q.site.isBlank() && customer != null) {
        Text("No address saved for ${customer.name.substringBefore(' ')} yet. Type it here (pick a suggestion) and it's saved to them for next time.",
            color = Z.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** On my way → (GPS) arrived, job clock → leave job → drive home. */
@Composable
fun DriveSection(vm: AppViewModel, q: Quote) {
    val context = LocalContext.current
    val drive by DriveService.state.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { pending?.invoke(); pending = null }
    fun withLocation(action: () -> Unit) {
        val need = buildList {
            if (!Geo.canLocate(context)) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty() || Geo.canLocate(context)) action() else { pending = action; askLocation.launch(need.toTypedArray()) }
    }
    val mine = drive?.takeIf { it.quoteId == q.id }

    Spacer(Modifier.height(12.dp))
    when {
        mine != null -> {
            Column(Modifier.fillMaxWidth().background(Z.neonTint).padding(12.dp)) {
                Label(if (mine.home) "Driving home" else "On the way", color = Z.neon)
                Text("${qtyText(Math.round(mine.miles * 10) / 10.0)} mi so far", color = Z.text, fontSize = 18.sp)
                Text(if (mine.home) "Stops by itself when you get home." else "Stops by itself when you pull up, and starts the job clock.", color = Z.muted, fontSize = 12.sp)
                Label("Stop tracking", color = Z.warn, modifier = Modifier.clickable { DriveService.stop(context) }.padding(top = 8.dp))
            }
        }
        q.arrivedAt != null && q.leftAt == null -> {
            val mins = ((System.currentTimeMillis() - q.arrivedAt) / 60000).toInt()
            StatusDot("On site since ${clock.format(Date(q.arrivedAt))} · ${mins / 60} hr ${mins % 60} min", Z.ok)
            Spacer(Modifier.height(8.dp))
            NeonButton("Leave job", Modifier.fillMaxWidth(), small = "Stops the job clock") { vm.leaveJob(q) }
        }
        q.arrivedAt != null && q.leftAt != null -> {
            val hrs = (q.leftAt - q.arrivedAt) / 3_600_000.0
            StatusDot("On site ${clock.format(Date(q.arrivedAt))}–${clock.format(Date(q.leftAt))} · ${qtyText(Math.round(hrs * 10) / 10.0)} hr", Z.ok)
            if (q.kind == Kind.QUOTE && q.laborHours == 0.0) Label("Use ${qtyText(Math.round(hrs * 4) / 4.0)} hr as labor →", color = Z.neon,
                modifier = Modifier.clickable { vm.saveQuote(q.copy(laborHours = Math.round(hrs * 4) / 4.0)) }.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            GhostButton(if (drive != null) "Another drive is being tracked" else "Track drive home", Modifier.fillMaxWidth()) {
                if (drive == null) withLocation { DriveService.startHome(context, q.id) }
            }
        }
        else -> {
            if (q.site.isBlank()) Text("Add the address to use On my way.", color = Z.muted, fontSize = 13.sp)
            else {
                NeonButton(if (busy) "Getting your location…" else "On my way", Modifier.fillMaxWidth(),
                    small = if (q.textCustomer || q.kind == Kind.APPT) "Texts them your drive time" else "Texts the customer your drive time") {
                    if (!busy && drive == null) withLocation { busy = true; vm.onMyWay(q) { busy = false; status = it } }
                }
                Label("I'm already here →", color = Z.neon, modifier = Modifier.clickable { vm.markArrived(q) }.padding(vertical = 8.dp))
            }
        }
    }
    status?.let { Text(it, color = Z.neon, fontSize = 13.sp) }
}

// ---------------- Mileage (Books) ----------------

private val tripDay = SimpleDateFormat("MMM d", Locale.US)

/** Business miles this year with the IRS deduction, and the trip list. */
@Composable
fun MileageSection(vm: AppViewModel) {
    val trips by vm.trips.collectAsState()
    var editing by remember { mutableStateOf<Trip?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val year = Calendar.getInstance().get(Calendar.YEAR)
    val thisYear = trips.filter { Calendar.getInstance().apply { timeInMillis = it.startedAt }.get(Calendar.YEAR) == year }
    val miles = thisYear.sumOf { it.miles }
    val deduction = thisYear.sumOf { it.miles * Geo.irsRate(it.startedAt) }

    SectionHeader("", "Mileage log · $year", "${qtyText(Math.round(miles * 10) / 10.0)} mi")
    MoneyRow("Mileage deduction (IRS rate)", money(deduction), Z.ok, bold = true)
    Text("72.5¢/mi through June 30, 76¢/mi from July 1. Trips log by themselves when you use On my way.", color = Z.muted, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    (if (showAll) thisYear else thisYear.take(5)).forEach { t ->
        ListRow("${tripDay.format(Date(t.startedAt))} · ${qtyText(t.miles)} mi", (t.purpose.ifBlank { t.toLabel.ifBlank { "Business drive" } } + if (t.manual) " · typed in" else "").uppercase(),
            onClick = { editing = t })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (thisYear.size > 5) Label(if (showAll) "Show less" else "Show all ${thisYear.size}", color = Z.neon, modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 10.dp))
        Label("+ Add a trip", color = Z.neon, modifier = Modifier.clickable { editing = Trip(startedAt = System.currentTimeMillis(), miles = 0.0, manual = true) }.padding(vertical = 10.dp))
    }

    editing?.let { t ->
        val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var date by remember(t.id) { mutableStateOf(iso.format(Date(t.startedAt))) }
        var milesText by remember(t.id) { mutableStateOf(if (t.miles == 0.0) "" else qtyText(t.miles)) }
        var purpose by remember(t.id) { mutableStateOf(t.purpose) }
        var to by remember(t.id) { mutableStateOf(t.toLabel) }
        ZDialog(if (t.id == 0L) "Add a trip" else "Trip", { editing = null },
            confirm = "Save" to {
                val d = runCatching { iso.parse(date.trim())?.time }.getOrNull() ?: t.startedAt
                if (milesText.toNum() > 0) { vm.saveTrip(t.copy(startedAt = d, endedAt = maxOf(d, t.endedAt), miles = milesText.toNum(), purpose = purpose.trim(), toLabel = to.trim())); editing = null }
            },
            dismiss = (if (t.id == 0L) "Cancel" else "Delete") to { if (t.id != 0L) vm.deleteTrip(t); editing = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Date (YYYY-MM-DD)", date, { date = it }, Modifier.weight(1f))
                Field("Miles", milesText, { milesText = it }, Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(6.dp))
            Field("Where to", to, { to = it })
            Spacer(Modifier.height(6.dp))
            Field("Business reason (e.g. Q-0009 Missy · switch repair)", purpose, { purpose = it })
            Text("The IRS wants date, miles, where and why for each trip.", color = Z.muted, fontSize = 12.sp)
        }
    }
}

// ---------------- Job map ----------------

/** Upcoming jobs and appointments on a free OpenStreetMap map. Tap a pin for details and directions. */
@Composable
fun JobMapScreen(vm: AppViewModel, nav: Nav, onBack: () -> Unit) {
    val context = LocalContext.current
    val quotes by vm.quotes.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Quote?>(null) }
    var finding by remember { mutableStateOf(0) }
    val now = System.currentTimeMillis() - 12 * 3_600_000L
    val upcoming = quotes.filter { it.site.isNotBlank() && it.status != Status.DECLINED && it.status != Status.DONE && (it.startAt == null || it.startAt >= now) }

    // Find map spots for addresses that don't have one yet (once each).
    LaunchedEffect(upcoming.map { it.id to it.site }) {
        val missing = upcoming.filter { it.siteLat == null }
        finding = missing.size
        missing.forEach { q ->
            Geo.locate(q.site)?.let { p -> vm.setSiteCoords(q, p.lat, p.lon) }
            finding--
            delay(1100) // be polite to the free address service
        }
        finding = 0
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) { Text("←", color = Z.text, fontSize = 18.sp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(if (finding > 0) "Finding $finding address${if (finding == 1) "" else "es"}…" else "${upcoming.count { it.siteLat != null }} on the map")
                Text("JOB MAP", fontFamily = Condensed, fontSize = 24.sp, color = Z.text)
            }
        }
        val pinned = upcoming.filter { it.siteLat != null && it.siteLon != null }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
                    controller.setZoom(11.0); controller.setCenter(GeoPoint(43.015, -75.035))
                }
            }, update = { map ->
                map.overlays.clear()
                pinned.forEach { q ->
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(q.siteLat!!, q.siteLon!!)
                        title = q.title.ifBlank { "Job" }
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ -> selected = q; true }
                    })
                }
                if (pinned.size >= 2) runCatching {
                    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pinned.map { GeoPoint(it.siteLat!!, it.siteLon!!) }).increaseByScale(1.4f), false)
                } else pinned.firstOrNull()?.let { map.controller.setCenter(GeoPoint(it.siteLat!!, it.siteLon!!)); map.controller.setZoom(14.0) }
                map.invalidate()
            })
            Text("© OpenStreetMap contributors", color = androidx.compose.ui.graphics.Color.DarkGray, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
        }
        selected?.let { q ->
            Column(Modifier.fillMaxWidth().background(Z.raised).padding(16.dp)) {
                Label("${q.number}" + (q.startAt?.let { " · " + SimpleDateFormat(if (q.hasTime) "EEE MMM d, h:mm a" else "EEE MMM d", Locale.US).format(Date(it)) } ?: " · not scheduled"), color = Z.neon)
                Text(q.title.ifBlank { "Job" }, color = Z.text, fontSize = 18.sp)
                Text(listOf(q.customerName, q.site).filter { it.isNotBlank() }.joinToString(" · "), color = Z.muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("Open", Modifier.weight(1f), arrow = false) { nav.open(Page.EditQuote(q.id)) }
                    GhostButton("Directions", Modifier.weight(1f).height(54.dp)) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q.site))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q.site))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    }
                }
            }
        }
        if (pinned.isEmpty() && finding == 0) Text("No upcoming jobs with an address yet.", color = Z.muted, modifier = Modifier.padding(16.dp))
    }
}
