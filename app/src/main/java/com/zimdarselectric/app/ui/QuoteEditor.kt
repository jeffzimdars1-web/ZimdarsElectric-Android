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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Catalog
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.JobType
import com.zimdarselectric.app.data.Material
import com.zimdarselectric.app.data.Quote
import com.zimdarselectric.app.data.QuoteLine
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.holdsByMaterial
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.toNum

/** A number field that keeps what you type and saves the parsed value. */
@Composable
fun NumField(label: String, value: Double, key: Any, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(if (value == 0.0) "" else qtyText(value)) }
    Field(label, text, { text = it; onValue(it.toNum()) }, modifier, number = true)
}

@Composable
fun TextFieldSaved(label: String, value: String, key: Any, modifier: Modifier = Modifier, singleLine: Boolean = true, onValue: (String) -> Unit) {
    var text by remember(key) { mutableStateOf(value) }
    Field(label, text, { text = it; onValue(it) }, modifier, singleLine = singleLine)
}

@Composable
fun QuoteEditor(vm: AppViewModel, quoteId: Long, onBack: () -> Unit) {
    val quotes by vm.quotes.collectAsState()
    val allLines by vm.lines.collectAsState()
    val materials by vm.materials.collectAsState()
    val customers by vm.customers.collectAsState()
    val settings by vm.settings.collectAsState()
    val q = quotes.firstOrNull { it.id == quoteId } ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = Z.muted) }
        return
    }
    val lines = allLines.filter { it.quoteId == q.id }
    val holdsOthers = holdsByMaterial(quotes, allLines, excludeQuoteId = q.id)
    val totals = computeTotals(q, lines, settings)
    val locked = q.status == Status.DONE

    var pickCustomer by remember { mutableStateOf(false) }
    var addMaterial by remember { mutableStateOf(false) }
    var editLine by remember { mutableStateOf<QuoteLine?>(null) }
    var completing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showPrivate by remember { mutableStateOf(false) }

    fun save(new: Quote) = vm.saveQuote(new)

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("←", color = Z.text, fontSize = 18.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label("${q.number} · ${q.status}")
                Text((q.title.ifBlank { "New quote" }).uppercase(), fontFamily = Condensed, fontSize = 24.sp, color = Z.text, maxLines = 1)
            }
            StatusDot("Saved", Z.ok)
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            SectionHeader("01", "Job")
            TextFieldSaved("Job name (e.g. 200A service upgrade)", q.title, "title${q.id}") { save(q.copy(title = it)) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clickable { pickCustomer = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Customer")
                    Text(q.customerName.ifBlank { "Choose or add a customer" }, color = if (q.customerName.isBlank()) Z.muted else Z.text, fontSize = 18.sp)
                }
                Label("Change", color = Z.neon)
            }
            Divider()
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("Job site address", q.site, "site${q.id}") { save(q.copy(site = it)) }
            Spacer(Modifier.height(8.dp))
            TextFieldSaved("Planned day (e.g. Mon Nov 2)", q.scheduled, "sch${q.id}") { save(q.copy(scheduled = it)) }

            SectionHeader("02", "Job type · decides NY sales tax")
            Segmented(listOf("New install", "Repair"), if (q.jobType == JobType.REPAIR) 1 else 0) {
                save(q.copy(jobType = if (it == 1) JobType.REPAIR else JobType.INSTALL))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (q.jobType == JobType.REPAIR) "Repair or maintenance: sales tax is added to the whole bill. You must be registered for NY sales tax."
                else "Capital improvement: no sales tax on the bill. The customer signs form ST-124. Enter material costs including the tax you paid at the store.",
                color = Z.muted, fontSize = 14.sp,
            )

            SectionHeader("03", "Materials", "${lines.size} items")
            lines.forEach { l ->
                val mid = l.materialId
                val m = materials.firstOrNull { it.id == mid }
                val stockNote: Pair<String, androidx.compose.ui.graphics.Color> = if (m == null) "Not tracked in stock" to Z.muted else {
                    val inThis = lines.filter { it.materialId == mid }.sumOf { it.qty }
                    val free = m.onHand - (holdsOthers[m.id] ?: 0.0)
                    when {
                        locked -> "Taken from stock" to Z.muted
                        free >= inThis -> "In stock · ${qtyText(free - inThis)} ${m.unit} left after this job" to Z.ok
                        free <= 0 -> "None free · on order list" to Z.warn
                        else -> "Short ${qtyText(inThis - free)} ${m.unit} · on order list" to Z.warn
                    }
                }
                Column(Modifier.clickable(enabled = !locked) { editLine = l }) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(l.name, color = Z.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(money(l.qty * l.unitCost * (1 + q.markupPct / 100)), fontFamily = Mono, fontSize = 14.sp, color = Z.text)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stockNote.first.uppercase(), fontFamily = Mono, fontSize = 11.sp, color = stockNote.second, modifier = Modifier.weight(1f))
                        Text("${qtyText(l.qty)} ${l.unit} × ${money(l.unitCost)}", fontFamily = Mono, fontSize = 11.sp, color = Z.muted)
                    }
                    Divider()
                }
            }
            if (!locked) {
                Spacer(Modifier.height(12.dp))
                GhostButton("+ Add material", Modifier.fillMaxWidth()) { addMaterial = true }
            }

            SectionHeader("04", "Labor + pricing")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Hours", q.laborHours, "h${q.id}", Modifier.weight(1f)) { save(q.copy(laborHours = it)) }
                NumField("Rate $/hr", q.laborRate, "r${q.id}", Modifier.weight(1f)) { save(q.copy(laborRate = it)) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Markup %", q.markupPct, "m${q.id}", Modifier.weight(1f)) { save(q.copy(markupPct = it)) }
                if (q.jobType == JobType.REPAIR) NumField("Sales tax %", q.taxRatePct, "t${q.id}", Modifier.weight(1f)) { save(q.copy(taxRatePct = it)) }
                else NumField("Round-trip miles", q.miles, "mi${q.id}", Modifier.weight(1f)) { save(q.copy(miles = it)) }
            }
            if (q.jobType == JobType.REPAIR) {
                Spacer(Modifier.height(8.dp))
                NumField("Round-trip miles (all trips)", q.miles, "mi2${q.id}") { save(q.copy(miles = it)) }
            }

            SectionHeader("05", "Total")
            MoneyRow("Materials", money(totals.materialsPrice))
            MoneyRow("Labor", money(totals.labor))
            MoneyRow(if (q.jobType == JobType.REPAIR) "Sales tax ${qtyText(q.taxRatePct)}%" else "Sales tax · new install", money(totals.salesTax))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Label("Quote total")
                Text(money(totals.total), fontFamily = Condensed, fontSize = 40.sp, color = Z.text)
            }

            // Private numbers
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().background(Z.raised).clickable { showPrivate = !showPrivate }.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label("My numbers · private")
                    Label(if (showPrivate) "Hide" else "Show", color = Z.neon)
                }
                Spacer(Modifier.height(6.dp))
                Text("Take-home ${money(totals.takeHome)}  ·  ${money(totals.perHour)}/hr", color = Z.neon, fontSize = 18.sp)
                if (showPrivate) {
                    Spacer(Modifier.height(8.dp))
                    MoneyRow("Customer pays", money(totals.total), bold = true)
                    if (totals.salesTax > 0) MoneyRow("Sales tax (owed to NY)", "−" + money(totals.salesTax))
                    MoneyRow("Materials (your cost)", "−" + money(totals.materialsCost))
                    MoneyRow("Fuel · ${qtyText(q.miles)} mi", "−" + money(totals.fuel), Z.warn)
                    MoneyRow("Shop supplies ${qtyText(settings.suppliesPct)}%", "−" + money(totals.supplies), Z.warn)
                    MoneyRow("Profit", money(totals.profit), bold = true)
                    MoneyRow("Savings ${qtyText(settings.savingsPct)}%", "−" + money(totals.savings), Z.ok)
                    MoneyRow("Income tax set-aside ${qtyText(settings.taxSetAsidePct)}%", "−" + money(totals.taxSetAside), Z.info)
                    MoneyRow("Take-home", money(totals.takeHome), Z.neon, bold = true)
                }
            }

            SectionHeader("06", "Status")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Status.DRAFT, Status.SENT, Status.APPROVED, Status.DECLINED).forEach { s ->
                    val sel = q.status == s
                    Box(
                        Modifier.weight(1f).heightIn(min = 40.dp).background(if (sel) Z.neonTint else Z.bg)
                            .clickable(enabled = !locked) { save(q.copy(status = s)) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(s, fontFamily = Mono, fontSize = 10.sp, color = if (sel) Z.neon else Z.muted) }
                }
            }
            Spacer(Modifier.height(14.dp))
            if (!locked) {
                NeonButton("Job done", Modifier.fillMaxWidth(), small = "Take materials out of stock") { completing = true }
            } else {
                Text("Job done. Materials were taken out of stock.", color = Z.muted)
            }
            Spacer(Modifier.height(10.dp))
            if (q.paidAt == null) {
                GhostButton("Mark paid · ${money(totals.total)}", Modifier.fillMaxWidth(), color = Z.ok) { vm.markPaid(q, totals.total) }
            } else {
                Text("Paid ${money(q.paidAmount)}", color = Z.ok, fontFamily = Mono)
            }
            Spacer(Modifier.height(10.dp))
            GhostButton("Delete quote", Modifier.fillMaxWidth(), color = Z.warn) { confirmDelete = true }
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

    if (addMaterial) MaterialPicker(
        materials = materials, holdsOthers = holdsOthers, thisQuote = lines,
        onDismiss = { addMaterial = false },
        onNewMaterial = { m, qty -> vm.saveMaterial(m) { id -> vm.addLine(QuoteLine(quoteId = q.id, materialId = id, name = m.name, unit = m.unit, qty = qty, unitCost = m.cost)) }; addMaterial = false },
    ) { m, qty ->
        vm.addLine(QuoteLine(quoteId = q.id, materialId = m.id, name = m.name, unit = m.unit, qty = qty, unitCost = m.cost))
        addMaterial = false
    }

    editLine?.let { l ->
        var qty by remember(l.id) { mutableStateOf(qtyText(l.qty)) }
        var cost by remember(l.id) { mutableStateOf(qtyText(l.unitCost)) }
        ZDialog(title = l.name, onDismiss = { editLine = null },
            confirm = "Save" to { vm.saveLine(l.copy(qty = qty.toNum(), unitCost = cost.toNum())); editLine = null },
            dismiss = "Remove" to { vm.deleteLine(l); editLine = null }) {
            Field("Quantity (${l.unit})", qty, { qty = it }, number = true)
            Spacer(Modifier.height(8.dp))
            Field("Your cost per ${l.unit}", cost, { cost = it }, number = true)
        }
    }

    if (completing) {
        val used = remember { mutableStateMapOf<Long, String>().apply { lines.filter { it.materialId != null }.groupBy { it.materialId!! }.forEach { (k, v) -> put(k, qtyText(v.sumOf { it.qty })) } } }
        ZDialog(title = "Job done", onDismiss = { completing = false },
            confirm = "Take out of stock" to { vm.completeJob(q, used.mapValues { it.value.toNum() }); completing = false },
            dismiss = "Cancel" to { completing = false }) {
            Text("Fix any amounts you actually used, then confirm.", color = Z.muted, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(used.keys.toList()) { mid ->
                    val m = materials.firstOrNull { it.id == mid }
                    Field("${m?.name ?: "Item"} (${m?.unit ?: ""})", used[mid] ?: "", { used[mid] = it }, number = true)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (confirmDelete) ZDialog(title = "Delete ${q.number}?", onDismiss = { confirmDelete = false },
        confirm = "Delete" to { vm.deleteQuote(q); confirmDelete = false; onBack() },
        dismiss = "Keep" to { confirmDelete = false }) {
        Text("This removes the quote and releases any stock it was holding.", color = Z.muted)
    }
}

@Composable
fun ZDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: Pair<String, () -> Unit>,
    dismiss: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    val dismissContent: (@Composable () -> Unit)? = dismiss?.let { d ->
        @Composable { TextButton(onClick = d.second) { Text(d.first.uppercase(), color = Z.muted, fontFamily = Mono) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.raised,
        title = { Text(title.uppercase(), fontFamily = Condensed, fontSize = 22.sp, color = Z.text) },
        text = { Column { content() } },
        confirmButton = { TextButton(onClick = confirm.second) { Text(confirm.first.uppercase(), color = Z.neon, fontFamily = Mono, fontWeight = FontWeight.Bold) } },
        dismissButton = dismissContent,
    )
}

@Composable
fun CustomerPicker(customers: List<Customer>, onDismiss: () -> Unit, onNew: (Customer) -> Unit, onPick: (Customer) -> Unit) {
    var adding by remember { mutableStateOf(customers.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    if (adding) {
        ZDialog("New customer", onDismiss,
            confirm = "Add" to { if (name.isNotBlank()) onNew(Customer(name = name.trim(), phone = phone.trim(), address = address.trim())) },
            dismiss = (if (customers.isEmpty()) "Cancel" else "Back") to { if (customers.isEmpty()) onDismiss() else adding = false }) {
            Field("Name", name, { name = it })
            Spacer(Modifier.height(6.dp))
            Field("Phone", phone, { phone = it })
            Spacer(Modifier.height(6.dp))
            Field("Address", address, { address = it })
        }
    } else {
        ZDialog("Choose customer", onDismiss, confirm = "+ New customer" to { adding = true }, dismiss = "Cancel" to onDismiss) {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(customers, key = { it.id }) { c ->
                    ListRow(c.name, c.address.ifBlank { c.phone }.uppercase(), onClick = { onPick(c) })
                }
            }
        }
    }
}

@Composable
fun MaterialPicker(
    materials: List<Material>,
    holdsOthers: Map<Long, Double>,
    thisQuote: List<QuoteLine>,
    onDismiss: () -> Unit,
    onNewMaterial: (Material, Double) -> Unit,
    onPick: (Material, Double) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<Material?>(null) }
    var creating by remember { mutableStateOf(false) }
    var qty by remember { mutableStateOf("1") }
    var conflict by remember { mutableStateOf(false) }

    // New item, not in stock yet
    var nName by remember { mutableStateOf("") }
    var nUnit by remember { mutableStateOf("ea") }
    var nCost by remember { mutableStateOf("") }
    var nSpec by remember { mutableStateOf("") }
    var nameLocked by remember { mutableStateOf(false) }

    when {
        creating -> ZDialog("New material", onDismiss,
            confirm = "Add to quote" to {
                if (nName.isNotBlank()) onNewMaterial(Material(name = nName.trim(), spec = nSpec.trim(), unit = nUnit.trim().ifBlank { "ea" }, cost = nCost.toNum()), qty.toNum().takeIf { it > 0 } ?: 1.0)
            },
            dismiss = "Back" to { creating = false }) {
            Field("Name (e.g. 12/2 NM-B cable)", nName, { nName = it; nameLocked = false })
            if (!nameLocked && nName.trim().length >= 2) {
                val sugg = Catalog.search(nName, 5).filter { !it.name.equals(nName.trim(), true) }
                SuggestionList(sugg.map { it.name to listOf(it.spec, it.category, "per ${it.unit}").filter { s -> s.isNotBlank() }.joinToString(" · ") }) { i ->
                    nName = sugg[i].name; nUnit = sugg[i].unit; nSpec = sugg[i].spec; nameLocked = true
                }
            }
            if (nSpec.isNotBlank()) Label(nSpec)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Unit (ea, ft)", nUnit, { nUnit = it }, Modifier.weight(1f))
                Field("Your cost each", nCost, { nCost = it }, Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(6.dp))
            Field("Quantity", qty, { qty = it }, number = true)
            Text("It's added to Stock with 0 on hand, so it shows on your order list.", color = Z.muted, fontSize = 13.sp)
        }

        chosen != null -> {
            val m = chosen!!
            val have = m.onHand
            val heldElsewhere = holdsOthers[m.id] ?: 0.0
            val alreadyHere = thisQuote.filter { it.materialId == m.id }.sumOf { it.qty }
            val free = have - heldElsewhere - alreadyHere
            val need = qty.toNum()
            if (conflict) {
                val short = need - maxOf(free, 0.0)
                ZDialog(if (heldElsewhere > 0) "Not enough for both jobs" else "Not enough in stock", onDismiss,
                    confirm = "Add + order ${qtyText(short)} ${m.unit}" to { onPick(m, need) },
                    dismiss = "Change amount" to { conflict = false }) {
                    Text(m.name, color = Z.muted)
                    Spacer(Modifier.height(8.dp))
                    MoneyRow("You have", "${qtyText(have)} ${m.unit}")
                    if (heldElsewhere > 0) MoneyRow("On hold for other quotes", "−${qtyText(heldElsewhere)} ${m.unit}")
                    if (alreadyHere > 0) MoneyRow("Already on this quote", "−${qtyText(alreadyHere)} ${m.unit}")
                    MoneyRow("Free now", "${qtyText(maxOf(free, 0.0))} ${m.unit}")
                    MoneyRow("This quote needs", "${qtyText(need)} ${m.unit}")
                    MoneyRow("Short", "${qtyText(short)} ${m.unit}", Z.warn, bold = true)
                }
            } else {
                ZDialog(m.name, onDismiss,
                    confirm = "Add" to { if (need > 0) { if (need > free) conflict = true else onPick(m, need) } },
                    dismiss = "Back" to { chosen = null }) {
                    Text("${qtyText(maxOf(free, 0.0))} ${m.unit} free · ${money(m.cost)} per ${m.unit}", color = Z.muted)
                    Spacer(Modifier.height(8.dp))
                    Field("Quantity (${m.unit})", qty, { qty = it }, number = true)
                }
            }
        }

        else -> ZDialog("Add material", onDismiss, confirm = "+ New item" to { nName = search; creating = true }, dismiss = "Cancel" to onDismiss) {
            Field("Search (e.g. romex 12-2, 20A gfci)", search, { search = it })
            Spacer(Modifier.height(8.dp))
            val shown = materials.filter { search.isBlank() || Catalog.matchesText(it.name + " " + it.spec, search) }
            val fromCatalog = if (search.trim().length >= 2)
                Catalog.search(search, 12, exclude = materials.map { it.name.lowercase() }.toSet()) else emptyList()
            if (shown.isEmpty() && fromCatalog.isEmpty()) Text("No match. Tap + New item to add it.", color = Z.muted)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (shown.isNotEmpty()) item { Label("Your stock", color = Z.neon) }
                items(shown, key = { it.id }) { m ->
                    val free = m.onHand - (holdsOthers[m.id] ?: 0.0) - thisQuote.filter { it.materialId == m.id }.sumOf { it.qty }
                    ListRow(m.name, "${qtyText(maxOf(free, 0.0))} ${m.unit} free · ${money(m.cost)}/${m.unit}", onClick = { chosen = m; qty = "1" })
                }
                if (fromCatalog.isNotEmpty()) item { Spacer(Modifier.height(10.dp)); Label("From the catalog · tap to add", color = Z.neon) }
                items(fromCatalog, key = { "c" + it.name + it.spec }) { c ->
                    ListRow(c.name, listOf(c.spec, c.category, "per ${c.unit}").filter { it.isNotBlank() }.joinToString(" · "),
                        onClick = { nName = c.name; nUnit = c.unit; nSpec = c.spec; creating = true })
                }
            }
        }
    }
}
