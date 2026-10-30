package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
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
import kotlinx.coroutines.launch

/** A booked visit before you know the work: customer, address, day/time, what's going on, reminders. No prices. */
@Composable
fun AppointmentEditor(vm: AppViewModel, q: Quote, onBack: () -> Unit) {
    val customers by vm.customers.collectAsState()
    val settings by vm.settings.collectAsState()
    val customer = customers.firstOrNull { it.id == q.customerId }
    var pickCustomer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var sendNote by remember { mutableStateOf<String?>(null) }
    val askSms = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        sendNote = if (ok) "Texting is on. Tap Send confirmation again." else "Texting is blocked. Allow SMS for the app in your phone's Settings → Apps → Zimdars Electric."
    }
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
            SiteAddress(vm, q, customer, "Address") { save(it) }
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("What it's for (e.g. Service call)", q.title, "atitle${q.id}") { save(q.copy(title = it)) }
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("What's going on (e.g. bedroom light won't turn on)", q.notes, "anotes${q.id}", singleLine = false) { save(q.copy(notes = it)) }

            if (q.hasRescheduleRequest) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().background(Z.neonTint).padding(14.dp)) {
                    Label("${q.customerName.ifBlank { "Customer" }} asked for a different time", color = Z.warn)
                    Text(q.rescheduleText, color = Z.text, fontSize = 20.sp, fontFamily = Condensed)
                    if (q.rescheduleNote.isNotBlank()) Text("\"${q.rescheduleNote}\"", color = Z.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    if (q.startAt == q.rescheduleWhen) Text("On your calendar now. Tap Send updated confirmation below so they can confirm it.", color = Z.ok, fontSize = 14.sp)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NeonButton("Use this time", Modifier.weight(1f), arrow = false) {
                            save(q.copy(startAt = q.rescheduleWhen, hasTime = true,
                                scheduled = q.rescheduleText, rescheduleRequestedAt = q.rescheduleRequestedAt))
                        }
                        GhostButton("Call / text", Modifier.weight(1f).height(54.dp)) {
                            customer?.phone?.takeIf { it.isNotBlank() }?.let { p ->
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$p"))) }
                            }
                        }
                    }
                    Text("\"Use this time\" puts it on your calendar (morning = 9 AM, afternoon = 1 PM; change it below if needed). Then tap Send updated confirmation so they can confirm the new time.",
                        color = Z.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Label("Dismiss", modifier = Modifier.clickable { save(q.copy(rescheduleText = "")) }.padding(top = 8.dp))
                }
            }

            SectionHeader("02", "When")
            SchedulePicker(q, customer, settings) { save(it) }
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("Notes for you (customer doesn't see these)", q.privateNote, "apnote${q.id}", singleLine = false) { save(q.copy(privateNote = it)) }
            Label("Shows on the calendar and in your reminders.")
            DriveSection(vm, q)

            SectionHeader("03", "Confirmation for the customer")
            Text("Sends a link to a formal confirmation page with your letterhead: date, arrival window, address, reason for the visit and your service call terms. They tap Confirm, and can add it to their calendar.",
                color = Z.muted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            val dayFmt = java.text.SimpleDateFormat("EEE MMM d, h:mm a", java.util.Locale.US)
            when {
                q.confirmedAt != null -> StatusDot("Confirmed by customer · ${dayFmt.format(java.util.Date(q.confirmedAt))}", Z.ok)
                q.sentAt != null && q.signUrl.isNotBlank() -> {
                    StatusDot("Sent ${dayFmt.format(java.util.Date(q.sentAt))}", Z.info)
                    Label(q.viewedAt?.let { "Opened ${dayFmt.format(java.util.Date(it))} · waiting for them to confirm" } ?: "Not opened yet")
                }
            }
            if (q.sentAt != null && q.changedSinceSent && q.signUrl.isNotBlank())
                Text("You changed the appointment after sending it. Send an updated confirmation so they see the new details.", color = Z.warn, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            val phone = customer?.phone.orEmpty()
            when {
                settings.signKey.isBlank() -> Text("Add your signing key in More → Settings to send confirmations.", color = Z.warn, fontSize = 13.sp)
                phone.isBlank() -> Text("Add the customer's cell number (tap Customer above, or More → Customers) to send it.", color = Z.warn, fontSize = 13.sp)
                else -> {
                    NeonButton(when { sending -> "Sending…"; q.sentAt == null || q.signUrl.isBlank() -> "Send confirmation"; else -> "Send updated confirmation" },
                        Modifier.fillMaxWidth(), small = "Texted from your phone · $phone") {
                        if (!sending) {
                            if (!com.zimdarselectric.app.data.CustomerTexts.canSend(context)) askSms.launch(android.Manifest.permission.SEND_SMS)
                            else {
                                sending = true; sendNote = null
                                vm.sendAppointment(q) { url, err, texted ->
                                    sending = false
                                    sendNote = err ?: if (texted) "Sent. You'll get a notification when they confirm." else "Link made. Tap the notification to text it, or use Copy link."
                                }
                            }
                        }
                    }
                }
            }
            if (q.signUrl.isNotBlank()) Row {
                Label("Preview page →", color = Z.neon, modifier = Modifier.clickable {
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(q.signUrl))) }
                }.padding(vertical = 10.dp))
                Spacer(Modifier.width(18.dp))
                Label("Copy link", color = Z.neon, modifier = Modifier.clickable {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Appointment", q.signUrl))
                    sendNote = "Link copied."
                }.padding(vertical = 10.dp))
                if (!customer?.email.isNullOrBlank()) {
                    Spacer(Modifier.width(18.dp))
                    Label("Email it", color = Z.neon, modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                                .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(customer!!.email))
                                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Appointment confirmation · ${settings.businessName}")
                                .putExtra(android.content.Intent.EXTRA_TEXT, com.zimdarselectric.app.data.CustomerTexts.confirmation(q, settings, q.signUrl)))
                        }
                    }.padding(vertical = 10.dp))
                }
            }
            sendNote?.let { Text(it, color = Z.neon, fontSize = 13.sp) }

            SectionHeader("04", "After the visit")
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
