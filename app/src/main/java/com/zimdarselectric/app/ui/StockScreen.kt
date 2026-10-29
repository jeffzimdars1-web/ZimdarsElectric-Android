package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import com.zimdarselectric.app.data.boxText
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.data.Catalog
import com.zimdarselectric.app.data.LabelGuess
import com.zimdarselectric.app.data.Material
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.toNum

@Composable
fun StockScreen(vm: AppViewModel) {
    val materials by vm.materials.collectAsState()
    val holds by vm.holds.collectAsState()
    var filter by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Material?>(null) }
    var editPhoto by remember { mutableStateOf<String?>(null) }
    var editGuess by remember { mutableStateOf<LabelGuess?>(null) }
    fun edit(m: Material, photo: String? = null, guess: LabelGuess? = null) { editPhoto = photo; editGuess = guess; editing = m }

    // Scan: a box you already have opens that item; anything else starts a new item filled in from the label.
    val scanner = rememberLabelScanner(vm) { f, g ->
        val known = g.barcodes.firstNotNullOfOrNull { code -> materials.firstOrNull { it.hasBarcode(code) } }
            ?: g.matches.firstOrNull()?.let { c -> materials.firstOrNull { it.name.equals(c.name, true) && (c.spec.isBlank() || it.spec.contains(c.spec, true)) } }
        when {
            known == null -> edit(Material(name = ""), f.path, g)
            known.photoPath.isBlank() -> edit(known, f.path, g)
            else -> { vm.discardItemPhoto(f.path); edit(known, null, g) }
        }
    }

    val receiptItems by vm.receiptItems.collectAsState()
    val openReceiptLines = receiptItems.count { !it.decided && it.isMaterial }
    var reviewing by remember { mutableStateOf(false) }

    fun free(m: Material) = m.onHand - (holds[m.id] ?: 0.0)
    val low = materials.filter { free(it) < it.alertLevel || free(it) < 0 }
    val held = materials.filter { (holds[it.id] ?: 0.0) > 0 }
    val shown = when (filter) { 1 -> low; 2 -> held; else -> materials }
        .filter { search.isBlank() || Catalog.matchesText(it.name + " " + it.spec, search) }

    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Title("Stock", 38)
                Label("${materials.size} items · ${low.size} low", color = if (low.isNotEmpty()) Z.warn else Z.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(96.dp)) { GhostButton(if (scanner.busy) "Reading…" else "Scan", Modifier.fillMaxWidth().height(54.dp)) { if (!scanner.busy) scanner.launch() } }
                Box(Modifier.width(96.dp)) { NeonButton("+ Add", Modifier.fillMaxWidth()) { edit(Material(name = "")) } }
            }
        }
        if (openReceiptLines > 0) { Spacer(Modifier.height(12.dp)); ReceiptBanner(openReceiptLines) { reviewing = true } }
        Spacer(Modifier.height(12.dp))
        Field("Search (e.g. romex 12-2, 20A gfci)", search, { search = it })
        if (search.trim().length >= 2) {
            val sugg = Catalog.search(search, 4, exclude = materials.map { it.name.lowercase() }.toSet())
            if (sugg.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Label("Not in your stock yet · tap to add", color = Z.neon)
                SuggestionList(sugg.map { it.name to listOf(it.spec, it.category, "per ${it.unit}").filter { x -> x.isNotBlank() }.joinToString(" · ") }) { i ->
                    edit(Material(name = sugg[i].name, unit = sugg[i].unit, spec = sugg[i].spec))
                    search = ""
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Segmented(listOf("All ${materials.size}", "Low ${low.size}", "On hold ${held.size}"), filter) { filter = it }
        Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("On hold", Z.info); Legend("Free", Z.ok)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (low.isNotEmpty() && filter == 0 && search.isBlank()) {
                item { SectionHeader("!", "Order list") }
                items(low, key = { "o${it.id}" }) { m ->
                    val f = free(m)
                    val buy = maxOf(m.alertLevel - f, -f, 0.0)
                    val text = if (m.packSize > 1.0) {
                        val boxes = maxOf(1.0, Math.ceil(buy / m.packSize - 1e-9))
                        "Buy ${qtyText(boxes)} box${if (boxes == 1.0) "" else "es"} (${qtyText(boxes * m.packSize)} ${m.unit}) · about ${money(boxes * m.packSize * m.cost)}"
                    } else "Buy ${qtyText(buy)} ${m.unit} · about ${money(buy * m.cost)}"
                    ListRow(m.name, text.uppercase(), onClick = { edit(m) }, photo = m.photoPath) { StatusDot("Low", Z.warn) }
                }
                item { SectionHeader("01", "Everything") }
            }
            if (shown.isEmpty()) item {
                Text(if (materials.isEmpty()) "Nothing in stock yet. Tap + Add, or add items from a quote." else "No match.", color = Z.muted, modifier = Modifier.padding(vertical = 14.dp))
            }
            items(shown, key = { it.id }) { m -> StockRow(m, holds[m.id] ?: 0.0) { edit(m) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { m -> MaterialDialog(vm, m, initialPhoto = editPhoto ?: m.photoPath, initialGuess = editGuess) { editing = null } }
    if (reviewing) ReceiptReviewDialog(vm) { reviewing = false }
}

@Composable
private fun Legend(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(4.dp).background(color)); Spacer(Modifier.width(6.dp)); Label(text)
    }
}

@Composable
private fun StockRow(m: Material, hold: Double, onClick: () -> Unit) {
    val free = m.onHand - hold
    val low = free < m.alertLevel || free < 0
    Column(Modifier.clickable { onClick() }.padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ItemThumb(m.photoPath, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, color = Z.text, fontSize = 17.sp)
                Label(listOf(m.location, m.spec).filter { it.isNotBlank() }.joinToString(" · "))
            }
            if (low) StatusDot("Low", Z.warn)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Num("On hand", "${qtyText(m.onHand)} ${m.unit}", Z.text, Modifier.weight(1f), boxText(m.onHand, m.packSize))
            Num("On hold", "${qtyText(hold)} ${m.unit}", Z.text, Modifier.weight(1f))
            Num("Free", "${qtyText(free)} ${m.unit}", if (low) Z.warn else Z.text, Modifier.weight(1f), boxText(free, m.packSize))
        }
        Spacer(Modifier.height(6.dp))
        val total = maxOf(m.onHand, hold, 1.0)
        Row(Modifier.fillMaxWidth().height(4.dp).background(Z.track)) {
            val h = (minOf(hold, m.onHand) / total).toFloat()
            val f = (maxOf(free, 0.0) / total).toFloat()
            if (h > 0f) Box(Modifier.weight(h).height(4.dp).background(Z.info))
            if (f > 0f) Box(Modifier.weight(f).height(4.dp).background(Z.ok))
            val rest = 1f - h - f
            if (rest > 0.001f) Spacer(Modifier.weight(rest))
        }
    }
    Divider()
}

@Composable
private fun Num(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier, sub: String = "") {
    Column(modifier) {
        Label(label); Text(value, color = color, fontSize = 19.sp, fontFamily = Condensed)
        if (sub.isNotBlank()) Text(sub, color = Z.muted, fontSize = 11.sp)
    }
}

@Composable
fun MaterialDialog(vm: AppViewModel, m: Material, initialPhoto: String = m.photoPath, initialGuess: LabelGuess? = null, onClose: () -> Unit) {
    val isNew = m.id == 0L
    var name by remember { mutableStateOf(m.name) }
    var spec by remember { mutableStateOf(m.spec) }
    var unit by remember { mutableStateOf(m.unit) }
    var onHand by remember { mutableStateOf(qtyText(m.onHand)) }
    var alert by remember { mutableStateOf(qtyText(m.alertLevel)) }
    var cost by remember { mutableStateOf(if (m.cost == 0.0) "" else qtyText(m.cost)) }
    var location by remember { mutableStateOf(m.location) }
    var receive by remember { mutableStateOf("") }
    var used by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var nameLocked by remember { mutableStateOf(m.name.isNotBlank()) }
    var photo by remember { mutableStateOf(initialPhoto) }
    var viewing by remember { mutableStateOf(false) }
    var guess by remember { mutableStateOf(initialGuess) }
    var codes by remember { mutableStateOf(m.barcodes) }
    // Bought by the box: stock and cost are kept per piece, entered per box.
    var boxed by remember { mutableStateOf(m.packSize > 1.0) }
    var pack by remember { mutableStateOf(if (m.packSize > 1.0) qtyText(m.packSize) else "") }
    var boxPrice by remember { mutableStateOf(if (m.packSize > 1.0 && m.cost > 0) qtyText(Math.round(m.cost * m.packSize * 100) / 100.0) else "") }
    var boxesNow by remember { mutableStateOf("") }
    var looseNow by remember { mutableStateOf("") }
    var alertBoxes by remember { mutableStateOf(if (m.packSize > 1.0 && m.alertLevel > 0) qtyText(m.alertLevel / m.packSize) else "") }
    val packN = pack.toNum()
    val boxOn = boxed && packN > 1.0
    val receiptItems by vm.receiptItems.collectAsState()
    var pickReceipt by remember { mutableStateOf(false) }
    var receiptSearch by remember { mutableStateOf("") }
    val materials by vm.materials.collectAsState()

    /** Fills only the boxes that are still empty, so nothing you typed gets replaced. */
    fun applyGuess(g: LabelGuess) {
        guess = g
        val top = g.matches.firstOrNull()
        if (name.isBlank() && top != null) { name = top.name; nameLocked = true }
        if (spec.isBlank()) spec = listOf(top?.spec ?: "", g.spec).filter { it.isNotBlank() }.joinToString(" · ")
        if ((unit.isBlank() || unit == "ea") && g.unit != null && isNew) unit = g.unit!!
        if (g.packCount != null && pack.isBlank()) { boxed = true; pack = g.packCount.toString() }
        if (boxed && boxPrice.isBlank() && g.packCount != null && g.price != null) boxPrice = qtyText(g.price)
        else if (cost.isBlank()) g.unitCost?.let { cost = qtyText(it) }
        if (g.barcodes.isNotEmpty()) codes = m.copy(barcodes = codes).withBarcodes(g.barcodes).barcodes
    }
    LaunchedEffect(Unit) { initialGuess?.let { applyGuess(it) } }

    val scanner = rememberLabelScanner(vm) { f, g ->
        if (photo != m.photoPath) vm.discardItemPhoto(photo)
        photo = f.path
        applyGuess(g)
    }
    fun snap() = scanner.launch()
    /** Closing without saving throws away a photo taken in this dialog. */
    val cancel = { if (photo != m.photoPath) vm.discardItemPhoto(photo); onClose() }

    if (confirmDelete) {
        ZDialog("Delete ${m.name}?", cancel, confirm = "Delete" to { vm.deleteMaterial(m); cancel() }, dismiss = "Keep" to { confirmDelete = false }) {
            Text("Quotes that use it keep their lines, but it won't be tracked in stock anymore.", color = Z.muted)
        }
        return
    }

    if (viewing && photo.isNotBlank()) { PhotoViewer(photo, name.ifBlank { "Item" }) { viewing = false } }
    if (pickReceipt) {
        val found = com.zimdarselectric.app.data.ReceiptItem.search(receiptItems, receiptSearch).take(30)
        val day = java.text.SimpleDateFormat("MMM d", java.util.Locale.US)
        ZDialog("Price from my receipts", { pickReceipt = false }, confirm = "Close" to { pickReceipt = false }) {
            Field("Search your receipts", receiptSearch, { receiptSearch = it })
            Spacer(Modifier.height(6.dp))
            if (receiptItems.isEmpty()) Text("No receipt lines yet. Import your receipts file in Books first.", color = Z.muted, fontSize = 13.sp)
            else if (found.isEmpty()) Text("Nothing matches. Try one word, like \"twister\" or \"staples\".", color = Z.muted, fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(found) { r ->
                    val packs = r.packCount
                    Column(Modifier.fillMaxWidth().clickable {
                        val ft = r.feet
                        when {
                            ft != null -> { boxed = false; unit = "ft"; cost = qtyText(Math.round(r.paidEach / ft * 1000) / 1000.0) }
                            packs != null && packs > 1 -> { boxed = true; pack = packs.toString(); boxPrice = qtyText(r.paidEach) }
                            boxed -> boxPrice = qtyText(r.paidEach)
                            else -> cost = qtyText(r.paidEach)
                        }
                        pickReceipt = false
                    }.padding(vertical = 10.dp)) {
                        Text(r.name, color = Z.text, fontSize = 15.sp)
                        Label("${r.store} · ${day.format(java.util.Date(r.date))} · ${money(r.paidEach)} with tax" + (if (r.qty > 1) " each" else "") + (packs?.takeIf { it > 1 }?.let { " · box of $it" } ?: "") + (r.feet?.let { " · ${money(r.paidEach / it)}/ft" } ?: ""))
                    }
                    Divider()
                }
            }
        }
    }
    ZDialog(if (isNew) "Add to stock" else "Edit stock item", cancel,
        confirm = "Save" to {
            if (name.isNotBlank()) {
                val unitCost = if (boxOn && boxPrice.isNotBlank()) boxPrice.toNum() / packN else cost.toNum()
                val base = m.copy(name = name.trim(), spec = spec.trim(), unit = unit.trim().ifBlank { "ea" },
                    alertLevel = if (boxOn) alertBoxes.toNum() * packN else alert.toNum(), cost = unitCost,
                    packSize = if (boxOn) packN else 0.0,
                    location = location.trim().ifBlank { "Truck" }, photoPath = photo, barcodes = codes)
                if (m.photoPath.isNotBlank() && m.photoPath != photo) vm.discardItemPhoto(m.photoPath)
                if (isNew) vm.saveMaterial(base.copy(onHand = if (boxOn) boxesNow.toNum() * packN + looseNow.toNum() else onHand.toNum()))
                else {
                    val received = if (boxOn) receive.toNum() * packN else receive.toNum()
                    val moves = buildList {
                        if (received > 0) add(received to (if (boxOn) "Received ${qtyText(receive.toNum())} box(es)" else "Received"))
                        if (used.toNum() > 0) add(-used.toNum() to "Used (not on a quote)")
                    }
                    vm.saveMaterialWithMoves(base, moves)
                }
                onClose()
            }
        },
        dismiss = (if (isNew) "Cancel" else "Delete") to { if (isNew) cancel() else confirmDelete = true }) {
        androidx.compose.foundation.lazy.LazyColumn {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ItemThumb(photo, 84.dp, onClick = { if (photo.isNotBlank()) viewing = true else snap() })
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(if (photo.isBlank()) "Snap photo" else "Retake photo", color = Z.neon, modifier = Modifier.clickable { snap() }.padding(vertical = 4.dp))
                        if (photo.isNotBlank()) Label("Remove photo", color = Z.warn, modifier = Modifier.clickable { photo = "" }.padding(vertical = 4.dp))
                        else Text("Snap the label or box. The app reads it and fills in what it can.", color = Z.muted, fontSize = 12.sp)
                    }
                }
                if (scanner.busy) { Spacer(Modifier.height(8.dp)); Label("Reading label…", color = Z.neon) }
                guess?.let { g ->
                    Spacer(Modifier.height(8.dp))
                    LabelResult(g) { c ->
                        name = c.name; nameLocked = true
                        if (isNew) unit = g.unit ?: c.unit
                        spec = listOf(c.spec, g.spec).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                    val other = g.barcodes.firstNotNullOfOrNull { code -> materials.firstOrNull { it.id != m.id && it.hasBarcode(code) } }
                    if (other != null) Text("This barcode is already on \"${other.name}\" in your stock.", color = Z.warn, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Field("Name (e.g. 12/2 NM-B)", name, { name = it; nameLocked = false })
                if (isNew && !nameLocked && name.trim().length >= 2) {
                    val sugg = Catalog.search(name, 5).filter { !it.name.equals(name.trim(), true) }
                    SuggestionList(sugg.map { it.name to listOf(it.spec, it.category, "per ${it.unit}").filter { x -> x.isNotBlank() }.joinToString(" · ") }) { i ->
                        name = sugg[i].name; unit = sugg[i].unit; if (spec.isBlank()) spec = sugg[i].spec; nameLocked = true
                    }
                }
                Spacer(Modifier.height(6.dp))
                Field("Brand / model / size", spec, { spec = it })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Unit (ea, ft)", unit, { unit = it }, Modifier.weight(1f))
                    Field("Where", location, { location = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                // Comes in a box?
                Row(Modifier.fillMaxWidth().clickable { boxed = !boxed }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).border(1.5.dp, if (boxed) Z.neon else Z.lineStrong).background(if (boxed) Z.neon else Z.raised), contentAlignment = Alignment.Center) {
                        if (boxed) Text("✓", color = Z.onNeon, fontSize = 15.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Comes in a box or pack", color = Z.text, fontSize = 16.sp)
                }
                if (boxed) {
                    Field("How many in a box (${unit.ifBlank { "ea" }})", pack, { pack = it }, number = true)
                    if (packN <= 1.0) Text("Enter how many come in one box, like 100.", color = Z.muted, fontSize = 12.sp)
                }
                if (receiptItems.isNotEmpty()) {
                    Label("Price from my receipts →", color = Z.neon, modifier = Modifier.clickable {
                        receiptSearch = listOf(name, spec).filter { it.isNotBlank() }.joinToString(" "); pickReceipt = true
                    }.padding(vertical = 8.dp))
                }
                Spacer(Modifier.height(6.dp))
                if (boxOn) {
                    if (isNew) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Full boxes", boxesNow, { boxesNow = it }, Modifier.weight(1f), number = true)
                        Field("Loose (${unit.ifBlank { "ea" }})", looseNow, { looseNow = it }, Modifier.weight(1f), number = true)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Price per box (incl. tax)", boxPrice, { boxPrice = it }, Modifier.weight(1f), number = true)
                        Field("Alert under (boxes)", alertBoxes, { alertBoxes = it }, Modifier.weight(1f), number = true)
                    }
                    if (boxPrice.toNum() > 0) Text("= ${money(boxPrice.toNum() / packN)} per ${unit.ifBlank { "ea" }}. Quotes use the per-piece cost.", color = Z.muted, fontSize = 12.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isNew) Field("On hand", onHand, { onHand = it }, Modifier.weight(1f), number = true)
                        Field("Alert under", alert, { alert = it }, Modifier.weight(1f), number = true)
                    }
                    Spacer(Modifier.height(6.dp))
                    Field("Your cost per unit (incl. tax)", cost, { cost = it }, number = true)
                    Text("Tip: bought a 250 ft roll for \$500? Enter unit \"ft\" and cost 2.00.", color = Z.muted, fontSize = 12.sp)
                }
                if (!isNew) {
                    Spacer(Modifier.height(10.dp))
                    Label("On hand now: ${qtyText(m.onHand)} ${m.unit}" + boxText(m.onHand, m.packSize).let { if (it.isBlank()) "" else " · $it" })
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(if (boxOn) "+ Boxes received" else "+ Received", receive, { receive = it }, Modifier.weight(1f), number = true)
                        Field("− Used (${unit.ifBlank { "ea" }})", used, { used = it }, Modifier.weight(1f), number = true)
                    }
                }
            }
        }
    }
}
