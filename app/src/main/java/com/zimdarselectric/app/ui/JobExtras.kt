package com.zimdarselectric.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Billing
import com.zimdarselectric.app.data.JobPhoto
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.QuoteLine
import com.zimdarselectric.app.data.Settings
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.money
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
private fun d(ms: Long) = dayFmt.format(Date(ms))

private fun openFile(context: Context, f: File, mime: String, send: Boolean) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val i = if (send) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Send")
    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ---------------- ST-124 ----------------

@Composable
fun St124Section(vm: AppViewModel, q: Quote, settings: Settings) {
    if (!q.needsSt124) return
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Spacer(Modifier.height(14.dp))
    Label("NY Form ST-124 · no sales tax on install work", color = Z.info)
    Spacer(Modifier.height(6.dp))
    fun send() {
        if (busy) return
        busy = true; note = null
        vm.sendSt124(q) { url, err, texted -> busy = false; note = err ?: if (texted) "Texted the ST-124 link." else "Link made: $url" }
    }
    when {
        q.st124SignedAt != null -> {
            StatusDot("ST-124 signed ${d(q.st124SignedAt)}", Z.ok)
            val f = File(q.st124Path)
            if (f.exists()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                GhostButton("Open ST-124", Modifier.weight(1f)) { openFile(context, f, "application/pdf", false) }
                GhostButton("Send…", Modifier.weight(1f)) { openFile(context, f, "application/pdf", true) }
            }
            Text("Keep this with the job. New York says you hold it; you don't file it.", color = Z.muted, fontSize = 12.sp)
        }
        q.st124Token.isNotBlank() -> {
            StatusDot("ST-124 sent ${q.st124SentAt?.let { d(it) } ?: ""} · waiting", Z.info)
            Label("Send again", color = Z.neon, modifier = Modifier.clickable { send() }.padding(vertical = 8.dp))
        }
        q.signedAt == null -> {
            Text("When the customer signs this quote on their phone, they sign Form ST-124 at the same time. The filled-in form is saved here.",
                color = Z.muted, fontSize = 13.sp)
            Label("Or send ST-124 by itself →", color = Z.neon, modifier = Modifier.clickable { send() }.padding(vertical = 8.dp))
        }
        else -> GhostButton(if (busy) "Sending…" else "Send ST-124 to sign", Modifier.fillMaxWidth()) { send() }
    }
    if (settings.ownerSignaturePath.isBlank())
        Text("Add your own signature in Settings → ST-124 so your contractor line is signed too.", color = Z.warn, fontSize = 12.sp)
    note?.let { Text(it, color = Z.neon, fontSize = 13.sp) }
}

// ---------------- Photos ----------------

@Composable
fun PhotosSection(vm: AppViewModel, q: Quote) {
    val context = LocalContext.current
    val all by vm.photos.collectAsState()
    val photos = all.filter { it.quoteId == q.id }
    var pending by remember { mutableStateOf<File?>(null) }
    var open by remember { mutableStateOf<JobPhoto?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        pending?.let { f -> if (ok) vm.addJobPhoto(q.id, f) else f.delete() }
        pending = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { vm.addJobPhotoFromUri(q.id, it) }
    }
    if (photos.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEach { p -> ItemThumb(p.path, 92.dp) { open = p } }
        }
        val onPdf = photos.count { it.onCustomerCopy }
        Label("${photos.size} photo${if (photos.size == 1) "" else "s"}" + if (onPdf > 0) " · $onPdf on the customer's copy" else " · tap one to caption it or put it on the customer's copy")
    } else Text("Before and after shots, the panel, problems you found. They stay with this job.", color = Z.muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Take photo", Modifier.weight(1f)) {
            val f = vm.newJobPhotoFile(); pending = f
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
        }
        GhostButton("From gallery", Modifier.weight(1f)) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    open?.let { p ->
        var caption by remember(p.id) { mutableStateOf(p.caption) }
        var onPdf by remember(p.id) { mutableStateOf(p.onCustomerCopy) }
        var full by remember { mutableStateOf(false) }
        ZDialog("Photo", { open = null },
            confirm = "Save" to { vm.savePhoto(p.copy(caption = caption.trim(), onCustomerCopy = onPdf)); open = null },
            dismiss = "Delete" to { vm.deletePhoto(p); open = null }) {
            ItemThumb(p.path, 220.dp) { full = true }
            Spacer(Modifier.height(8.dp))
            Field("Caption (e.g. Before · panel)", caption, { caption = it })
            Row(Modifier.fillMaxWidth().clickable { onPdf = !onPdf }.padding(vertical = 10.dp)) {
                Text((if (onPdf) "✓  " else "○  ") + "Put on the customer's PDF", color = Z.text, fontSize = 15.sp)
            }
        }
        if (full) PhotoViewer(p.path, p.caption.ifBlank { "Photo" }) { full = false }
    }
}

// ---------------- Change orders ----------------

@Composable
fun ChangeOrdersSection(vm: AppViewModel, q: Quote, quotes: List<Quote>, lines: List<QuoteLine>, settings: Settings, onOpen: (Long) -> Unit) {
    if (q.parentId != null) {
        val parent = quotes.firstOrNull { it.id == q.parentId }
        Text("This is a change order to ${parent?.number ?: "another job"}. Send it to sign like a quote; once signed, it's added to that job's invoice.",
            color = Z.info, fontSize = 13.sp)
        parent?.let { Label("Open ${it.number} →", color = Z.neon, modifier = Modifier.clickable { onOpen(it.id) }.padding(vertical = 8.dp)) }
        return
    }
    val kids = quotes.filter { it.parentId == q.id }.sortedBy { it.number }
    if (kids.isEmpty()) Text("Found more work on the job? Add a change order, send it to sign, and do the work once it's approved.", color = Z.muted, fontSize = 13.sp)
    kids.forEach { k ->
        val t = computeTotals(k, lines.filter { it.quoteId == k.id }, settings)
        Row(Modifier.fillMaxWidth().clickable { onOpen(k.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(k.title.ifBlank { "Change order" }, color = Z.text, fontSize = 16.sp)
                Label("${k.number} · ${money(t.total)}")
            }
            StatusDot(if (k.signedAt != null) "Signed" else k.status, if (k.signedAt != null || k.status == Status.APPROVED) Z.ok else Z.muted)
        }
        Divider()
    }
    Spacer(Modifier.height(8.dp))
    GhostButton("+ Change order", Modifier.fillMaxWidth()) { vm.newChangeOrder(q) { onOpen(it) } }
}

// ---------------- Invoice + payment ----------------

@Composable
fun InvoiceSection(vm: AppViewModel, q: Quote, quotes: List<Quote>, lines: List<QuoteLine>, settings: Settings) {
    if (q.parentId != null) return
    val context = LocalContext.current
    val kids = quotes.filter { it.parentId == q.id }
    val total = Billing.invoiceTotal(q, kids, lines, settings)
    val due = maxOf(0.0, total - Billing.paidSoFar(q))
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val changes = Billing.billableChanges(kids)
    if (changes.isNotEmpty()) Label("Includes ${changes.size} change order${if (changes.size == 1) "" else "s"} · total ${money(total)}")
    when {
        q.paidAt != null -> {
            StatusDot("Paid ${money(q.paidAmount)} · ${SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(q.paidAt))}", Z.ok)
        }
        q.invoiceSentAt == null -> {
            NeonButton(if (busy) "Sending…" else "Send invoice", Modifier.fillMaxWidth(), small = "${money(due)} · texted with a link to view and pay") {
                if (!busy) { busy = true; note = null
                    vm.sendInvoice(q) { _, err, texted -> busy = false; note = err ?: if (texted) "Invoice texted." else "Invoice link made. Use Copy link to send it." } }
            }
        }
        else -> {
            StatusDot("Invoice sent ${d(q.invoiceSentAt)}", Z.info)
            Label(q.invoiceRemindedAt?.let { "Reminder sent ${d(it)}" }
                ?: "Automatic reminder ${Billing.day(q.invoiceSentAt + settings.invoiceReminderDays * 86_400_000L)} if still unpaid")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                GhostButton("Remind now", Modifier.weight(1f)) { vm.sendInvoiceReminder(q) { ok -> note = if (ok) "Reminder texted." else "Couldn't text. Check the customer's number." } }
                GhostButton("Copy link", Modifier.weight(1f)) {
                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Invoice", q.invoiceUrl))
                    note = "Link copied."
                }
            }
            Label("Send updated invoice →", color = Z.neon, modifier = Modifier.clickable {
                if (!busy) { busy = true; vm.sendInvoice(q) { _, err, texted -> busy = false; note = err ?: if (texted) "Updated invoice texted." else "Link updated." } }
            }.padding(vertical = 8.dp))
        }
    }
    if (q.paidAt == null && settings.venmo.isBlank() && settings.zelle.isBlank())
        Text("Tip: add your Venmo or Zelle in Settings → Getting paid so customers can pay from the invoice.", color = Z.muted, fontSize = 12.sp)
    note?.let { Text(it, color = Z.neon, fontSize = 13.sp) }
    // Review request
    if (settings.reviewUrl.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        if (q.reviewAskedAt != null) StatusDot("Review request sent ${d(q.reviewAskedAt)}", Z.ok)
        else Row(Modifier.fillMaxWidth().clickable { vm.saveQuote(q.copy(askReview = !q.askReview)) }.padding(vertical = 8.dp)) {
            Text((if (q.askReview) "✓  " else "○  ") + "Text a Google review request the day after the job is done", color = Z.text, fontSize = 14.sp)
        }
    }
}

// ---------------- Checklists ----------------

@Composable
fun ChecklistSection(q: Quote, locked: Boolean, onChange: (String) -> Unit) {
    val lists = remember(q.checklist) { com.zimdarselectric.app.data.Checklists.parse(q.checklist) }
    var picking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Int?>(null) }
    if (lists.isEmpty()) Text("Add a checklist so nothing gets missed: safety at the start, panel inspection, clean-up at the end. It prints on the job copy.", color = Z.muted, fontSize = 13.sp)
    lists.forEachIndexed { li, l ->
        val done = l.items.count { it.done }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(l.title, color = Z.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Label("$done / ${l.items.size}", color = if (done == l.items.size) Z.ok else Z.muted)
            if (!locked) Label("Remove", color = Z.warn, modifier = Modifier.clickable { removing = li }.padding(start = 12.dp))
        }
        l.items.forEachIndexed { ii, it ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { onChange(com.zimdarselectric.app.data.Checklists.toggle(q.checklist, li, ii)) }
                .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).border(1.5.dp, if (it.done) Z.ok else Z.muted).background(if (it.done) Z.ok else Z.bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    if (it.done) Text("✓", color = Z.bg, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(it.text, color = if (it.done) Z.muted else Z.text, fontSize = 15.sp)
            }
        }
        Divider()
    }
    if (!locked) {
        Spacer(Modifier.height(10.dp))
        GhostButton("+ Add checklist", Modifier.fillMaxWidth()) { picking = true }
    }
    if (picking) ZDialog("Add checklist", { picking = false }, confirm = "Close" to { picking = false }) {
        com.zimdarselectric.app.data.Checklists.templates.keys.forEach { t ->
            Text(t, color = Z.text, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onChange(com.zimdarselectric.app.data.Checklists.add(q.checklist, t)); picking = false }.padding(vertical = 12.dp))
            Divider()
        }
    }
    removing?.let { i ->
        ZDialog("Remove “${lists.getOrNull(i)?.title}”?", { removing = null },
            confirm = "Remove" to { onChange(com.zimdarselectric.app.data.Checklists.remove(q.checklist, i)); removing = null },
            dismiss = "Keep" to { removing = null }) {}
    }
}

// ---------------- Repeat-work reminders ----------------

@Composable
fun RemindLaterSection(q: Quote, settingsOn: Boolean, onChange: (String) -> Unit) {
    val items = remember(q.remindLater) { com.zimdarselectric.app.data.RepeatWork.parse(q.remindLater) }
    var adding by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }
    Text("Set a reminder and the customer gets a text when it's due, with a link to book you. Good for generator service, detector checks, surge protectors.",
        color = Z.muted, fontSize = 13.sp)
    if (!settingsOn) Text("Reminders are turned off in Settings.", color = Z.warn, fontSize = 13.sp)
    items.forEachIndexed { i, it ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it.what, color = Z.text, fontSize = 16.sp)
                Label(if (it.sent != null) "Texted ${fmt.format(Date(it.sent))}" else "Texts them ${fmt.format(Date(it.due))}", color = if (it.sent != null) Z.ok else Z.info)
            }
            Label("Remove", color = Z.warn, modifier = Modifier.clickable { onChange(com.zimdarselectric.app.data.RepeatWork.write(items.filterIndexed { j, _ -> j != i })) }.padding(8.dp))
        }
        Divider()
    }
    Spacer(Modifier.height(10.dp))
    GhostButton("+ Remind them later", Modifier.fillMaxWidth()) { adding = true }
    if (adding) {
        var pick by remember { mutableStateOf(0) }
        var custom by remember { mutableStateOf("") }
        var months by remember { mutableStateOf(com.zimdarselectric.app.data.RepeatWork.presets[0].months.toString()) }
        val presets = com.zimdarselectric.app.data.RepeatWork.presets
        ZDialog("Remind them later", { adding = false }, confirm = "Add" to {
            val m = months.toIntOrNull()?.coerceIn(1, 240) ?: 12
            val p = presets.getOrNull(pick)
            val what = if (p != null) p.what else custom.trim()
            if (what.isNotBlank()) {
                val due = com.zimdarselectric.app.data.RepeatWork.dueIn(m, q.completedAt ?: System.currentTimeMillis())
                onChange(com.zimdarselectric.app.data.RepeatWork.write(items + com.zimdarselectric.app.data.RepeatWork.Item(what, due, null, p?.why ?: "")))
            }
            adding = false
        }, dismiss = "Cancel" to { adding = false }) {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                (presets.map { it.what } + "Something else").forEachIndexed { i, label ->
                    val on = i == pick
                    Text(label, color = if (on) Z.neon else Z.text, fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { pick = i; presets.getOrNull(i)?.let { months = it.months.toString() } }.padding(vertical = 10.dp))
                }
                if (pick >= presets.size) { Field("What's due", custom, { custom = it }); Spacer(Modifier.height(8.dp)) }
                Field("Text them in (months, from when the job is done)", months, { months = it }, number = true)
            }
        }
    }
}

// ---------------- Good / Better / Best ----------------

@Composable
fun OptionsSection(vm: AppViewModel, q: Quote, quotes: List<Quote>, allLines: List<QuoteLine>, s: Settings, onOpenQuote: (Long) -> Unit) {
    val options = quotes.filter { it.optionOf == q.id }.sortedBy { it.id }
    if (options.isEmpty()) {
        Text("Give the customer 2 or 3 choices, like a basic repair, a better fix, and the full upgrade. They pick one on the signing page. Most people pick the middle one.",
            color = Z.muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        if (q.signedAt == null) GhostButton("+ Add a second option", Modifier.fillMaxWidth()) { vm.addOption(q) { onOpenQuote(it) } }
        return
    }
    val all = listOf(q) + options
    if (q.signedAt != null) Text("They picked ${q.optionLabel.ifBlank { "this option" }}. This quote now has that option's work and price; the others are kept for your records.",
        color = Z.ok, fontSize = 13.sp)
    else Text("The customer sees these side by side and picks one. Tap one to change it.", color = Z.muted, fontSize = 13.sp)
    all.forEachIndexed { i, o ->
        val total = computeTotals(o, allLines.filter { it.quoteId == o.id }, s).total
        Row(Modifier.fillMaxWidth().clickable(enabled = o.id != q.id) { onOpenQuote(o.id) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(o.optionLabel.ifBlank { "Option ${i + 1}" } + if (o.id == q.id) " · this quote" else "", color = if (o.id == q.id) Z.neon else Z.info)
                Text(o.title.ifBlank { "No job name yet" }, color = Z.text, fontSize = 16.sp)
            }
            Text(money(total), fontFamily = Mono, color = Z.text)
        }
        Divider()
    }
    if (q.signedAt == null) {
        if (q.optionLabel.isBlank()) { Spacer(Modifier.height(8.dp)); TextFieldSaved("This quote's option name", q.optionLabel, "mlabel${q.id}") { vm.saveQuote(q.copy(optionLabel = it.take(24))) } }
        if (options.size < 2) { Spacer(Modifier.height(10.dp)); GhostButton("+ Add a third option", Modifier.fillMaxWidth()) { vm.addOption(q) { onOpenQuote(it) } } }
        Spacer(Modifier.height(6.dp))
        Text("Send for signature below sends all of them together.", color = Z.muted, fontSize = 12.sp)
    }
}

// ---------------- Deposit ----------------

@Composable
fun DepositRow(vm: AppViewModel, q: Quote, total: Double, save: (Quote) -> Unit) {
    NumField("Deposit when they sign (% of total, 0 = none)", q.depositPct, "dep${q.id}") { save(q.copy(depositPct = it.coerceIn(0.0, 100.0))) }
    if (q.depositPct <= 0) return
    val amount = Math.round(total * q.depositPct) / 100.0
    Spacer(Modifier.height(6.dp))
    if (q.depositPaidAt != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Deposit received ${money(q.depositPaid)} · ${SimpleDateFormat("MMM d", Locale.US).format(Date(q.depositPaidAt))}", color = Z.ok, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Label("Undo", color = Z.warn, modifier = Modifier.clickable { vm.undoDeposit(q) }.padding(8.dp))
        }
    } else {
        Text("Deposit: ${money(amount)}. The signing page shows it and gives them your Venmo / Zelle / card buttons after they sign.", color = Z.info, fontSize = 13.sp)
        if (q.signedAt != null) { Spacer(Modifier.height(6.dp)); GhostButton("Got the deposit · ${money(amount)}", Modifier.fillMaxWidth(), color = Z.ok) { vm.markDepositPaid(q, amount) } }
    }
    Spacer(Modifier.height(4.dp))
    Text("NY law: deposit money for home improvement work has to go in a separate escrow account (or be covered by a bond) within 5 business days, until the work is done.",
        color = Z.muted, fontSize = 12.sp)
}
