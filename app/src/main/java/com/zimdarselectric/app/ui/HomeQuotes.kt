package com.zimdarselectric.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.Nav
import com.zimdarselectric.app.Page
import com.zimdarselectric.app.Tab
import com.zimdarselectric.app.Updater
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun statusColor(s: String): Color = when (s) {
    Status.DRAFT -> Z.muted
    Status.SENT -> Z.info
    Status.APPROVED -> Z.ok
    Status.DONE -> Z.neon
    else -> Z.lineStrong
}

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav) {
    val quotes by vm.quotes.collectAsState()
    val materials by vm.materials.collectAsState()
    val holds by vm.holds.collectAsState()
    var update by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    LaunchedEffect(Unit) { update = Updater.check() }

    val low = materials.filter { m -> m.onHand - (holds[m.id] ?: 0.0) < m.alertLevel }
    val waiting = quotes.count { it.status == Status.SENT }
    val approved = quotes.count { it.status == Status.APPROVED }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        item {
            Spacer(Modifier.height(22.dp))
            NewContactsBanner(vm)
            InboxBanner(vm, nav)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Title("Zimdars", 40)
                    Label("Electric LLC", color = Z.neon)
                }
                Label(SimpleDateFormat("EEE d MMM", Locale.US).format(Date()))
            }
            if (update is Updater.State.Available) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().background(Z.neonTint).clickable { nav.open(Page.Settings) }.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Label("Update available", color = Z.neon)
                    Label("Open settings →", color = Z.neon)
                }
            }
            Spacer(Modifier.height(20.dp))
            NeonButton("New quote", Modifier.fillMaxWidth(), small = "Start") {
                vm.newQuote { id -> nav.open(Page.EditQuote(id)) }
            }
            Spacer(Modifier.height(10.dp))
            OutlineBigButton("New appointment", Modifier.fillMaxWidth(), small = "Book a visit") {
                vm.newAppointment(null) { id -> nav.open(Page.EditQuote(id)) }
            }
            Spacer(Modifier.height(20.dp))
            Divider()
            Row(Modifier.fillMaxWidth()) {
                Stat(waiting.toString().padStart(2, '0'), "Awaiting signature", Z.text, Modifier.weight(1f))
                Stat(approved.toString().padStart(2, '0'), "Approved jobs", Z.text, Modifier.weight(1f))
                Stat(low.size.toString().padStart(2, '0'), "Parts running low", Z.warn, Modifier.weight(1f))
            }
            Divider()
        }
        if (low.isNotEmpty()) {
            item { SectionHeader("01", "Order soon", "Stock →") { nav.goTab(Tab.STOCK) } }
            items(low.take(4), key = { "low${it.id}" }) { m ->
                val free = m.onHand - (holds[m.id] ?: 0.0)
                ListRow(m.name, "${qtyText(free)} ${m.unit} free · alert under ${qtyText(m.alertLevel)}") { StatusDot("Low", Z.warn) }
            }
        }
        item { SectionHeader("02", "Recent quotes", "All →") { nav.goTab(Tab.QUOTES) } }
        if (quotes.isEmpty()) item {
            Text("No quotes yet. Tap New quote to start your first one.", color = Z.muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        items(quotes.take(5), key = { "q${it.id}" }) { q ->
            ListRow(q.title.ifBlank { "Untitled job" }, "${q.number} · ${q.customerName.ifBlank { "No customer" }}".uppercase(),
                onClick = { nav.open(Page.EditQuote(q.id)) }) { StatusDot(q.status, statusColor(q.status)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier.padding(vertical = 14.dp)) {
        Text(value, fontFamily = Condensed, fontSize = 40.sp, color = color)
        Label(label)
    }
}

@Composable
fun ListRow(title: String, sub: String, onClick: (() -> Unit)? = null, photo: String = "", trailing: @Composable () -> Unit = {}) {
    Column(if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photo.isNotBlank()) { ItemThumb(photo, 44.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Z.text, fontSize = 17.sp)
                Spacer(Modifier.height(3.dp))
                Text(sub, fontFamily = Mono, fontSize = 11.sp, color = Z.muted)
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        Divider()
    }
}

@Composable
fun QuotesScreen(vm: AppViewModel, nav: Nav) {
    val quotes by vm.quotes.collectAsState()
    val lines by vm.lines.collectAsState()
    val settings by vm.settings.collectAsState()
    var filter by remember { mutableStateOf(0) }
    val filters = listOf("Open", "Done", "All")
    val shown = when (filter) {
        0 -> quotes.filter { it.status in Status.holding }
        1 -> quotes.filter { it.status == Status.DONE }
        else -> quotes
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Title("Quotes", 34)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(96.dp)) { GhostButton("+ Appt", Modifier.fillMaxWidth().height(54.dp)) { vm.newAppointment(null) { nav.open(Page.EditQuote(it)) } } }
                Box(Modifier.width(112.dp)) { NeonButton("+ Quote", Modifier.fillMaxWidth(), arrow = false, fontSize = 18) { vm.newQuote { nav.open(Page.EditQuote(it)) } } }
            }
        }
        Spacer(Modifier.height(14.dp))
        val ui = vm.quotesUi
        Segmented(listOf("List", "Calendar"), if (ui.calendar) 1 else 0) { ui.calendar = it == 1 }
        Spacer(Modifier.height(8.dp))
        if (!ui.calendar) Segmented(filters, filter) { filter = it }
        if (ui.calendar) LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) { calendarItems(vm, nav, quotes, lines, settings) }
        else LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty()) item {
                Text("Nothing here yet.", color = Z.muted, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(shown, key = { it.id }) { q: Quote ->
                val t = computeTotals(q, lines.filter { it.quoteId == q.id }, settings)
                ListRow(q.title.ifBlank { "Untitled job" }, ("${q.number} · ${q.customerName.ifBlank { "No customer" }} · ${money(t.total)}" +
                    (q.startAt?.let { " · " + java.text.SimpleDateFormat(if (q.hasTime) "EEE MMM d, h:mm a" else "EEE MMM d", java.util.Locale.US).format(java.util.Date(it)) } ?: "")).uppercase(),
                    onClick = { nav.open(Page.EditQuote(q.id)) }) {
                    if (q.kind == com.zimdarselectric.app.data.Kind.APPT) StatusDot("Appt", Z.info) else StatusDot(q.status, statusColor(q.status))
                }
            }
        }
    }
}


/** "Claude added Q-0014 · Pauly" after jobs come in from the online inbox. */
@Composable
fun InboxBanner(vm: AppViewModel, nav: Nav) {
    val added by vm.inboxAdded.collectAsState()
    if (added.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(Z.neonTint).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label("Added for you", color = Z.neon)
            Label("Dismiss", modifier = Modifier.clickable { vm.dismissInbox() })
        }
        added.forEach { (id, text) ->
            Text("$text →", color = Z.text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable {
                nav.goTab(Tab.QUOTES); nav.open(Page.EditQuote(id))
            }.padding(vertical = 6.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
}
