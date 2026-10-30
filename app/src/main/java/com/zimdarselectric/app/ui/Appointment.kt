package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Quote

/** A booked visit before you know the work: customer, address, day/time, what's going on, reminders. No prices. */
@Composable
fun AppointmentEditor(vm: AppViewModel, q: Quote, onBack: () -> Unit) {
    val customers by vm.customers.collectAsState()
    val settings by vm.settings.collectAsState()
    val customer = customers.firstOrNull { it.id == q.customerId }
    var pickCustomer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun save(n: Quote) = vm.saveQuote(n)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("←", color = Z.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label("Appointment · ${q.number}", color = Z.info)
                Text(q.title.ifBlank { "Service call" }.uppercase(), fontFamily = Condensed, fontSize = 24.sp, color = Z.text, maxLines = 1)
            }
            StatusDot("Saved", Z.ok)
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            SectionHeader("01", "Who + where")
            Row(Modifier.fillMaxWidth().clickable { pickCustomer = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Customer")
                    Text(q.customerName.ifBlank { "Choose or add a customer" }, color = if (q.customerName.isBlank()) Z.muted else Z.text, fontSize = 18.sp)
                    customer?.phone?.takeIf { it.isNotBlank() }?.let { Label(it) }
                }
                Label("Change", color = Z.neon)
            }
            Divider()
            Spacer(Modifier.height(8.dp))
            AddressField("Address", q.site, "asite${q.id}", onValue = { save(q.copy(site = it, siteLat = null, siteLon = null)) },
                onPlace = { p -> save(q.copy(site = p.label, siteLat = p.lat, siteLon = p.lon)) })
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("What it's for (e.g. Service call)", q.title, "atitle${q.id}") { save(q.copy(title = it)) }
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("What's going on (e.g. bedroom light won't turn on)", q.notes, "anotes${q.id}", singleLine = false) { save(q.copy(notes = it)) }

            SectionHeader("02", "When")
            SchedulePicker(q, customer, settings) { save(it) }
            DriveSection(vm, q)

            SectionHeader("03", "After the visit")
            Text("Once you know the fix, turn this into a quote. The customer, address, date and notes carry over, and you add the materials and labor.",
                color = Z.muted, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            NeonButton("Turn into a quote", Modifier.fillMaxWidth(), small = "Add materials + labor") { vm.appointmentToQuote(q) }
            Spacer(Modifier.height(10.dp))
            GhostButton("Delete appointment", Modifier.fillMaxWidth(), color = Z.warn) { confirmDelete = true }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (pickCustomer) CustomerPicker(customers, onDismiss = { pickCustomer = false }, onNew = { c ->
        vm.saveCustomer(c) { id -> save(q.copy(customerId = id, customerName = c.name, site = q.site.ifBlank { c.address })) }
        pickCustomer = false
    }) { c ->
        save(q.copy(customerId = c.id, customerName = c.name, site = q.site.ifBlank { c.address }))
        pickCustomer = false
    }
    if (confirmDelete) ZDialog("Delete this appointment?", { confirmDelete = false },
        confirm = "Delete" to { vm.deleteQuote(q); confirmDelete = false; onBack() }, dismiss = "Keep" to { confirmDelete = false }) {
        Text("Its reminders will stop too.", color = Z.muted)
    }
}
