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
                    ListRow(m.name, "Buy ${qtyText(buy)} ${m.unit} · about ${money(buy * m.cost)}".uppercase(), onClick = { edit(m) }) { StatusDot("Low", Z.warn) }
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
            Num("On hand", "${qtyText(m.onHand)} ${m.unit}", Z.text, Modifier.weight(1f))
            Num("On hold", "${qtyText(hold)} ${m.unit}", Z.text, Modifier.weight(1f))
            Num("Free", "${qtyText(free)} ${m.unit}", if (low) Z.warn else Z.text, Modifier.weight(1f))
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
private fun Num(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) { Label(label); Text(value, color = color, fontSize = 19.sp, fontFamily = Condensed) }
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
    val materials by vm.materials.collectAsState()

    /** Fills only the boxes that are still empty, so nothing you typed gets replaced. */
    fun applyGuess(g: LabelGuess) {
        guess = g
        val top = g.matches.firstOrNull()
        if (name.isBlank() && top != null) { name = top.name; nameLocked = true }
        if (spec.isBlank()) spec = listOf(top?.spec ?: "", g.spec).filter { it.isNotBlank() }.joinToString(" · ")
        if ((unit.isBlank() || unit == "ea") && g.unit != null && isNew) unit = g.unit!!
        if (cost.isBlank()) g.unitCost?.let { cost = qtyText(it) }
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
    ZDialog(if (isNew) "Add to stock" else "Edit stock item", cancel,
        confirm = "Save" to {
            if (name.isNotBlank()) {
                val base = m.copy(name = name.trim(), spec = spec.trim(), unit = unit.trim().ifBlank { "ea" },
                    alertLevel = alert.toNum(), cost = cost.toNum(), location = location.trim().ifBlank { "Truck" }, photoPath = photo, barcodes = codes)
                if (m.photoPath.isNotBlank() && m.photoPath != photo) vm.discardItemPhoto(m.photoPath)
                if (isNew) vm.saveMaterial(base.copy(onHand = onHand.toNum()))
                else {
                    val moves = buildList {
                        if (receive.toNum() > 0) add(receive.toNum() to "Received")
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
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isNew) Field("On hand", onHand, { onHand = it }, Modifier.weight(1f), number = true)
                    Field("Alert under", alert, { alert = it }, Modifier.weight(1f), number = true)
                }
                Spacer(Modifier.height(6.dp))
                Field("Your cost per unit (incl. tax)", cost, { cost = it }, number = true)
                Text("Tip: bought a 250 ft roll for \$500? Enter unit \"ft\" and cost 2.00.", color = Z.muted, fontSize = 12.sp)
                if (!isNew) {
                    Spacer(Modifier.height(10.dp))
                    Label("On hand now: ${qtyText(m.onHand)} ${m.unit}")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("+ Received", receive, { receive = it }, Modifier.weight(1f), number = true)
                        Field("− Used", used, { used = it }, Modifier.weight(1f), number = true)
                    }
                }
            }
        }
    }
}
