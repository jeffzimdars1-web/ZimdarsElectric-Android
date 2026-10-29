package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.AppViewModel.ReceiptAction
import com.zimdarselectric.app.data.Material
import com.zimdarselectric.app.data.ReceiptItem
import com.zimdarselectric.app.data.boxText
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shortDay = SimpleDateFormat("MMM d", Locale.US)

fun receiptLineLabel(r: ReceiptItem) = "${r.store} · ${shortDay.format(Date(r.date))} · " +
    (if (r.qty != 1.0) "${qtyText(r.qty)} × " else "") + "${money(r.paidEach)} with tax"

/** What happened to a receipt line, in plain words. */
fun receiptLineStatus(r: ReceiptItem, materials: List<Material>): String = when (r.stock) {
    "" -> "Not in stock yet"
    "skip" -> "Not a stock item"
    "counted" -> "Already counted"
    else -> materials.firstOrNull { it.id.toString() == r.stock }?.let { "Added to ${it.name}" } ?: "Added to stock"
}

/** Decide what one receipt line means for your stock. */
@Composable
fun ReceiptLineDialog(vm: AppViewModel, r: ReceiptItem, onClose: () -> Unit) {
    val materials by vm.materials.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Material?>(null) }
    val suggestions = remember(materials, r) { ReceiptItem.suggest(r, materials) }

    val m = chosen
    if (m != null) {
        val pieces = r.piecesFor(m)
        ZDialog(m.name, onClose,
            confirm = "Add ${qtyText(pieces)} ${m.unit}" to { vm.receiptToStock(r, m, ReceiptAction.ADD); onClose() },
            dismiss = "Already counted" to { vm.receiptToStock(r, m, ReceiptAction.COUNTED); onClose() }) {
            Text(r.name, color = Z.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("Adds ${qtyText(pieces)} ${m.unit}" + boxText(pieces, m.packSize).let { if (it.isBlank()) "" else " ($it)" } +
                " to ${m.name}. You have ${qtyText(m.onHand)} now.", color = Z.text, fontSize = 15.sp)
            if (m.packSize <= 1.0 && r.packCount != null) {
                Spacer(Modifier.height(6.dp))
                Text("This receipt says ${r.packCount} per box. Set \"Comes in a box\" on the item first so it adds the right count.", color = Z.warn, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Pick \"Already counted\" if you already put this in your on-hand number. Either way the app remembers this product for next time.",
                color = Z.muted, fontSize = 12.sp)
            Label("‹ Pick a different item", color = Z.neon, modifier = Modifier.clickable { chosen = null; picking = true }.padding(vertical = 10.dp))
        }
        return
    }

    ZDialog("Add to stock?", onClose, confirm = "Close" to onClose) {
        Text(r.name, color = Z.text, fontSize = 16.sp)
        Label(receiptLineLabel(r))
        Spacer(Modifier.height(10.dp))
        val list = if (picking) materials else suggestions.take(4)
        if (list.isNotEmpty()) {
            Label(if (picking) "All your stock items" else "Is it one of these?", color = Z.neon)
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(list, key = { it.id }) { mat ->
                    Column(Modifier.fillMaxWidth().clickable { chosen = mat }.padding(vertical = 10.dp)) {
                        Text(mat.name, color = Z.text, fontSize = 15.sp)
                        Label(listOf(mat.spec, "${qtyText(mat.onHand)} ${mat.unit} on hand").filter { it.isNotBlank() }.joinToString(" · "))
                    }
                    Divider()
                }
            }
        }
        if (!picking && materials.size > list.size) Label("Pick from all stock items →", color = Z.neon, modifier = Modifier.clickable { picking = true }.padding(vertical = 10.dp))
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NeonButton("New stock item from this", Modifier.fillMaxWidth()) { vm.newStockFromReceipt(r); onClose() }
            GhostButton("Not a stock item (tool, personal)", Modifier.fillMaxWidth()) { vm.receiptToStock(r, null, ReceiptAction.SKIP); onClose() }
        }
    }
}

/** Every receipt line you haven't decided on yet. */
@Composable
fun ReceiptReviewDialog(vm: AppViewModel, onClose: () -> Unit) {
    val all by vm.receiptItems.collectAsState()
    val materials by vm.materials.collectAsState()
    val open = all.filter { !it.decided && it.isMaterial }
    var line by remember { mutableStateOf<ReceiptItem?>(null) }
    line?.let { r -> ReceiptLineDialog(vm, r) { line = null }; return }
    ZDialog("Receipt items", onClose, confirm = "Done" to onClose) {
        if (open.isEmpty()) { Text("All caught up. Every receipt item is in stock or marked.", color = Z.muted); return@ZDialog }
        Text("Tap each one to add it to stock, or mark it as not a stock item. You only match a product once.", color = Z.muted, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(open, key = { "${it.expenseId}-${it.index}" }) { r ->
                val hint = ReceiptItem.suggest(r, materials).firstOrNull()
                Column(Modifier.fillMaxWidth().clickable { line = r }.padding(vertical = 10.dp)) {
                    Text(r.name, color = Z.text, fontSize = 15.sp)
                    Label(receiptLineLabel(r) + (hint?.let { " · looks like ${it.name}" } ?: ""))
                }
                Divider()
            }
        }
    }
}

/** Small banner for the Stock screen when receipts have items you haven't added yet. */
@Composable
fun ReceiptBanner(count: Int, onClick: () -> Unit) {
    if (count == 0) return
    Column(Modifier.fillMaxWidth().background(Z.neonTint).clickable { onClick() }.padding(12.dp)) {
        Text("$count receipt item${if (count == 1) "" else "s"} not in stock yet", color = Z.neon, fontSize = 15.sp)
        Label("Tap to add them or mark them")
    }
}
