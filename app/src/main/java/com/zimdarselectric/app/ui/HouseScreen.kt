package com.zimdarselectric.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.House
import com.zimdarselectric.app.data.Kind
import com.zimdarselectric.app.data.RepeatWork
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything about one house: panel and wiring, every job you've done there, and all the photos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HouseScreen(vm: AppViewModel, customerId: Long, address: String, onBack: () -> Unit, onOpenQuote: (Long) -> Unit) {
    val customers by vm.customers.collectAsState()
    val quotes by vm.quotes.collectAsState()
    val lines by vm.lines.collectAsState()
    val settings by vm.settings.collectAsState()
    val photos by vm.photos.collectAsState()
    val c = customers.firstOrNull { it.id == customerId }
    Column(Modifier.fillMaxSize()) {
        BackHeader("House history", onBack)
        if (c == null) { Text("Customer not found.", color = Z.muted, modifier = Modifier.padding(22.dp)); return }
        // Edits are kept here and saved as you go.
        val info = remember(customerId, address) { mutableStateMapOf<String, String>().apply { putAll(House.get(c, address)) } }
        fun set(k: String, v: String) { info[k] = v; vm.saveHouse(customerId, address, info.toMap()) }
        val jobs = House.jobs(c, address, quotes)
        val jobPhotos = photos.filter { p -> jobs.any { it.id == p.quoteId } }.sortedByDescending { it.takenAt }
        var viewing by remember { mutableStateOf<com.zimdarselectric.app.data.JobPhoto?>(null) }
        val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }
        val label = c.allProperties.firstOrNull { House.sameHouse(it.address, address) }?.label.orEmpty()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Label(c.name + if (label.isNotBlank() && label != "Main") " · $label" else "")
            Text(address, color = Z.text, fontSize = 20.sp, modifier = Modifier.padding(top = 2.dp))

            SectionHeader("01", "The house")
            House.fields.forEach { f ->
                Spacer(Modifier.height(8.dp))
                if (f.choices.isEmpty()) {
                    TextFieldSaved(f.label, info[f.key].orEmpty(), "h${customerId}${f.key}", singleLine = f.key != "notes") { set(f.key, it.trim()) }
                } else {
                    Label(f.label)
                    Spacer(Modifier.height(4.dp))
                    val picked = info[f.key].orEmpty().split(", ").filter { it.isNotBlank() }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.choices.forEach { ch ->
                            val on = ch in picked
                            Text(ch, color = if (on) Z.neon else Z.muted, fontSize = 13.sp,
                                modifier = Modifier.border(1.dp, if (on) Z.neon else Z.line).clickable {
                                    val next = if (f.multi) (if (on) picked - ch else picked + ch) else (if (on) emptyList() else listOf(ch))
                                    set(f.key, next.joinToString(", "))
                                }.padding(horizontal = 10.dp, vertical = 7.dp))
                        }
                    }
                }
            }
            if (info["wiring"].orEmpty().let { "Knob & tube" in it || "Aluminum branch" in it })
                Text("Heads up: knob & tube or aluminum branch wiring. Check connections and insurance requirements before adding load.", color = Z.warn, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))

            SectionHeader("02", "Work done here", if (jobs.isEmpty()) null else "${jobs.size}")
            if (jobs.isEmpty()) Text("No jobs at this address yet.", color = Z.muted, fontSize = 14.sp)
            jobs.forEach { q ->
                val total = computeTotals(q, lines.filter { it.quoteId == q.id }, settings).total
                val at = q.completedAt ?: q.startAt ?: q.createdAt
                Row(Modifier.fillMaxWidth().clickable { onOpenQuote(q.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(q.title.ifBlank { if (q.kind == Kind.APPT) "Service call" else "Job" }, color = Z.text, fontSize = 16.sp)
                        Label("${fmt.format(Date(at))} · ${q.number} · " + when {
                            q.canceledAt != null -> "canceled"; q.paidAt != null -> "paid"; else -> q.status.lowercase()
                        })
                        if (q.notes.isNotBlank()) Text(q.notes.lineSequence().first().take(90), color = Z.muted, fontSize = 13.sp)
                    }
                    if (q.kind != Kind.APPT) Text(money(total), fontFamily = Mono, color = Z.text)
                }
                Divider()
            }
            val reminders = jobs.flatMap { q -> RepeatWork.parse(q.remindLater).filter { it.sent == null }.map { it to q } }.sortedBy { it.first.due }
            if (reminders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Label("Coming up", color = Z.info)
                reminders.forEach { (r, _) -> Text("${r.what} · ${fmt.format(Date(r.due))}", color = Z.text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
            }

            SectionHeader("03", "Photos", if (jobPhotos.isEmpty()) null else "${jobPhotos.size}")
            if (jobPhotos.isEmpty()) Text("Photos you take on jobs here show up here. Get one of the panel with the cover off and the label.", color = Z.muted, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                jobPhotos.forEach { p -> ItemThumb(p.path, 100.dp) { viewing = p } }
            }
            Spacer(Modifier.height(40.dp))
        }
        viewing?.let { p ->
            val job = jobs.firstOrNull { it.id == p.quoteId }
            PhotoViewer(p.path, listOfNotNull(p.caption.ifBlank { null }, job?.let { "${it.number} · ${fmt.format(Date(p.takenAt))}" }).joinToString(" · ")) { viewing = null }
        }
    }
}

/** "House history →" link used on quotes, appointments and customers. */
@Composable
fun HouseLink(text: String = "House history →", onClick: () -> Unit) {
    Label(text, color = Z.info, modifier = Modifier.clickable { onClick() }.padding(vertical = 8.dp))
}
