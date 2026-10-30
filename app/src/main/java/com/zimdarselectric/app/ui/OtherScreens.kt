package com.zimdarselectric.app.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.zimdarselectric.app.AppViewModel
import com.zimdarselectric.app.Nav
import com.zimdarselectric.app.Page
import com.zimdarselectric.app.Updater
import com.zimdarselectric.app.data.Customer
import com.zimdarselectric.app.data.Expense
import com.zimdarselectric.app.data.ExpenseCategories
import com.zimdarselectric.app.data.Settings
import com.zimdarselectric.app.data.computeTotals
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.toNum
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(Z.raised, cut()).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("←", color = Z.text, fontSize = 18.sp)
        }
        Spacer(Modifier.width(14.dp))
        Title(title, 28)
    }
    Divider()
}

// ---------------- Books ----------------

private val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** Reads a receipts file: a JSON list (or {"expenses": [...]}) of {date, vendor, amount, category, note}. */
fun parseExpenses(text: String): List<Pair<Expense, List<AppViewModel.ImportFile>>> {
    val trimmed = text.trim()
    val arr = if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed).getJSONArray("expenses")
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.getJSONObject(i)
        val amount = o.optDouble("amount", 0.0)
        val vendor = o.optString("vendor").trim()
        if (vendor.isBlank() || amount <= 0.0) return@mapNotNull null
        val date = runCatching { isoDay.parse(o.optString("date"))?.time }.getOrNull() ?: System.currentTimeMillis()
        val cat = o.optString("category").ifBlank { ExpenseCategories.last() }
        val files = mutableListOf<AppViewModel.ImportFile>()
        o.optJSONArray("attachments")?.let { atts ->
            for (j in 0 until atts.length()) {
                val a = atts.getJSONObject(j)
                val bytes = runCatching { android.util.Base64.decode(a.optString("data"), android.util.Base64.DEFAULT) }.getOrNull() ?: continue
                files.add(AppViewModel.ImportFile(a.optString("name", "receipt"), a.optString("mime", "application/pdf"), bytes))
            }
        }
        Expense(date = date, vendor = vendor, amount = amount, category = cat, note = o.optString("note"),
            items = o.optJSONArray("items")?.toString() ?: "") to files
    }
}

@Composable
fun BooksScreen(vm: AppViewModel, nav: Nav) {
    val quotes by vm.quotes.collectAsState()
    val lines by vm.lines.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val settings by vm.settings.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Expense?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val obj = text.trim().takeIf { it.startsWith("{") }?.let { org.json.JSONObject(it) }
            val list = if (obj != null && !obj.has("expenses")) emptyList() else parseExpenses(text)
            val stock = obj?.optJSONArray("stock")
            if (list.isEmpty() && stock == null) message = "That file had no receipts in it."
            else vm.importExpenses(list) { added, skipped ->
                val receipts = if (list.isEmpty()) "" else "Added $added receipt${if (added == 1) "" else "s"}" + if (skipped > 0) " · $skipped already in Books" else ""
                if (stock == null) message = receipts
                else vm.importStockUpdates(stock) { n, notFound ->
                    message = listOf(receipts, "Updated $n stock item${if (n == 1) "" else "s"}",
                        if (notFound.isNotEmpty()) "Not found: ${notFound.joinToString()}" else "").filter { it.isNotBlank() }.joinToString(" · ")
                }
            }
        } catch (e: Exception) {
            message = "Couldn't read that file. Make sure it's the receipts file."
        }
    }

    val year = Calendar.getInstance().get(Calendar.YEAR)
    fun inYear(t: Long?) = t != null && Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.YEAR) == year
    val paid = quotes.filter { inYear(it.paidAt) }
    val income = paid.sumOf { it.paidAmount }
    val salesTax = paid.sumOf { q -> computeTotals(q, lines.filter { it.quoteId == q.id }, settings).salesTax }
    val yearExpenses = expenses.filter { inYear(it.date) }
    val spent = yearExpenses.sumOf { it.amount }
    val net = income - salesTax - spent

    // Search + month filter for the expense list
    val ui = vm.booksUi
    val monthKey = SimpleDateFormat("yyyy-MM", Locale.US)
    val monthName = SimpleDateFormat("MMMM yyyy", Locale.US)
    var pickingMonth by remember { mutableStateOf(false) }
    val q = ui.search.trim().lowercase()
    val shownExpenses = when {
        q.isNotEmpty() -> expenses.filter { e ->
            listOf(e.vendor, e.note, e.category, e.items, money(e.amount), qtyText(e.amount)).any { it.lowercase().contains(q) }
        }.filter { ui.month == null || monthKey.format(Date(it.date)) == ui.month }
        ui.month != null -> expenses.filter { monthKey.format(Date(it.date)) == ui.month }
        else -> yearExpenses
    }
    val vendors = expenses.map { it.vendor.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
    val storeHints = if (q.length >= 1) vendors.filter { it.lowercase().contains(q) && !it.equals(ui.search.trim(), true) }.take(5) else emptyList()

    if (pickingMonth) {
        val months = expenses.groupBy { monthKey.format(Date(it.date)) }.toList().sortedByDescending { it.first }
        ZDialog("Show which month?", { pickingMonth = false }, confirm = "Close" to { pickingMonth = false }) {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { ListRow("All of $year", "${yearExpenses.size} receipts · ${money(spent)}".uppercase(), onClick = { ui.month = null; pickingMonth = false }) }
                items(months) { (key, list) ->
                    ListRow(monthName.format(monthKey.parse(key)!!), "${list.size} receipt${if (list.size == 1) "" else "s"} · ${money(list.sumOf { it.amount })}".uppercase(),
                        onClick = { ui.month = key; pickingMonth = false })
                }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 22.dp), state = ui.list) {
        item {
            Spacer(Modifier.height(22.dp))
            Column { Title("Books", 38); Label("Tax year $year", color = Z.neon) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton("+ Expense", Modifier.weight(1f)) { editing = Expense(vendor = "", amount = 0.0, category = ExpenseCategories.first()) }
                GhostButton("Import file", Modifier.weight(1f).height(54.dp)) {
                    message = null
                    importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
            }
            message?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Z.neon, fontSize = 15.sp) }
            Spacer(Modifier.height(18.dp))
            Label(if (net >= 0) "Net profit so far" else "Net loss so far")
            Text(money(net), fontFamily = Condensed, fontSize = 52.sp, color = if (net >= 0) Z.neon else Z.warn)
            Spacer(Modifier.height(8.dp))
            MoneyRow("Income (jobs paid)", money(income))
            if (salesTax > 0) MoneyRow("Sales tax collected (owed to NY)", "−" + money(salesTax))
            MoneyRow("Expenses", "−" + money(spent))
            if (net > 0) {
                MoneyRow("Savings ${qtyText(settings.savingsPct)}%", money((income - salesTax) * settings.savingsPct / 100), Z.ok)
                MoneyRow("Tax set-aside ${qtyText(settings.taxSetAsidePct)}%", money(net * settings.taxSetAsidePct / 100), Z.info)
            }
            SectionHeader("01", "By tax category")
            yearExpenses.groupBy { it.category }.toList().sortedByDescending { (_, l) -> l.sumOf { it.amount } }.forEach { (cat, l) ->
                MoneyRow(cat, money(l.sumOf { it.amount }))
            }
            if (yearExpenses.isEmpty()) Text("No expenses yet this year.", color = Z.muted, modifier = Modifier.padding(vertical = 10.dp))
            val missing = yearExpenses.count { e -> attachments.none { it.expenseId == e.id } }
            SectionHeader("02", "Expenses · tap for details", if (missing > 0) "$missing need a receipt" else null)
            Field("Search receipts (store, item, amount)", ui.search, { ui.search = it })
            if (storeHints.isNotEmpty()) SuggestionList(storeHints.map { v -> v to "${expenses.count { it.vendor.equals(v, true) }} receipts".uppercase() }) { i -> ui.search = storeHints[i] }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().background(Z.raised).clickable { pickingMonth = true }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Month")
                    Text(ui.month?.let { monthName.format(monthKey.parse(it)!!) } ?: "All of $year", color = Z.text, fontSize = 16.sp)
                }
                Text("▾", color = Z.neon, fontSize = 18.sp)
            }
            if (q.isNotEmpty() || ui.month != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("${shownExpenses.size} found · ${money(shownExpenses.sumOf { it.amount })}", color = Z.neon, modifier = Modifier.weight(1f))
                    Label("Clear", color = Z.neon, modifier = Modifier.clickable { ui.search = ""; ui.month = null }.padding(6.dp))
                }
            }
            if (shownExpenses.isEmpty() && (q.isNotEmpty() || ui.month != null)) Text("No receipts match.", color = Z.muted, modifier = Modifier.padding(vertical = 10.dp))
        }
        items(shownExpenses, key = { it.id }) { e ->
            ListRow("${e.vendor} · ${money(e.amount)}", "${SimpleDateFormat("MMM d", Locale.US).format(Date(e.date))} · ${e.category}".uppercase()
                + if (e.note.isNotBlank()) "\n" + e.note else "",
                onClick = { nav.open(Page.ExpenseDetail(e.id)) }) {
                if (attachments.any { it.expenseId == e.id }) StatusDot("Receipt", Z.ok) else StatusDot("No receipt", Z.warn)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { e ->
        ExpenseDialog(e, onDismiss = { editing = null }, onDelete = { vm.deleteExpense(e); editing = null }, vendors = vendors) { vm.addExpense(it); editing = null }
    }
}

@Composable
fun ExpenseDialog(initial: Expense, onDismiss: () -> Unit, onDelete: () -> Unit, vendors: List<String> = emptyList(), onSave: (Expense) -> Unit) {
    val isNew = initial.id == 0L
    var vendor by remember { mutableStateOf(initial.vendor) }
    var amount by remember { mutableStateOf(if (initial.amount == 0.0) "" else qtyText(initial.amount)) }
    var date by remember { mutableStateOf(isoDay.format(Date(initial.date))) }
    var category by remember { mutableStateOf(initial.category) }
    var note by remember { mutableStateOf(initial.note) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        ZDialog("Tax category", { picking = false }, confirm = "Back" to { picking = false }) {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(ExpenseCategories) { c -> ListRow(c, "", onClick = { category = c; picking = false }) }
            }
        }
        return
    }
    ZDialog(if (isNew) "Add expense" else "Edit expense", onDismiss,
        confirm = "Save" to {
            val d = runCatching { isoDay.parse(date.trim())?.time }.getOrNull() ?: initial.date
            if (vendor.isNotBlank() && amount.toNum() > 0) onSave(initial.copy(vendor = vendor.trim(), amount = amount.toNum(), date = d, category = category, note = note.trim()))
        },
        dismiss = (if (isNew) "Cancel" else "Delete") to { if (isNew) onDismiss() else onDelete() }) {
        LazyColumn {
            item {
                Field("Store / vendor", vendor, { vendor = it })
                val hints = if (vendor.isNotBlank()) vendors.filter { it.contains(vendor.trim(), true) && !it.equals(vendor.trim(), true) }.take(4) else emptyList()
                if (hints.isNotEmpty()) SuggestionList(hints.map { it to "" }) { i -> vendor = hints[i] }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Total paid", amount, { amount = it }, Modifier.weight(1f), number = true)
                    Field("Date (YYYY-MM-DD)", date, { date = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 8.dp)) {
                    Column(Modifier.weight(1f)) { Label("Tax category"); Text(category, color = Z.text) }
                    Label("Change", color = Z.neon)
                }
                Field("Note (what it was for)", note, { note = it }, singleLine = false)
            }
        }
    }
}

// ---------------- More ----------------

@Composable
fun MoreScreen(nav: Nav) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(22.dp))
        Title("More", 38)
        Label("Everything else in one place")
        Spacer(Modifier.height(14.dp))
        Divider()
        MoreRow("01", "Price book", "What to charge · how to quote it") { nav.open(Page.PriceBook) }
        MoreRow("02", "Customers", "Call, text, directions, new quote") { nav.open(Page.Customers) }
        MoreRow("03", "Tools + calculators", "Voltage drop · more coming") { nav.open(Page.Tools) }
        MoreRow("04", "Settings", "Business info · rates · updates · v${Updater.currentVersion}") { nav.open(Page.Settings) }
    }
}

@Composable
private fun MoreRow(num: String, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(num, color = Z.neon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, color = Z.text, fontSize = 19.sp); Label(sub) }
        Text("→", color = Z.muted)
    }
    Divider()
}

// ---------------- Customers ----------------

@Composable
fun CustomersScreen(vm: AppViewModel, onBack: () -> Unit) {
    val customers by vm.customers.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Customer?>(null) }
    var search by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BackHeader("Customers", onBack)
        Column(Modifier.padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Field("Search", search, { search = it }, Modifier.weight(1f))
                Box(Modifier.width(100.dp)) { NeonButton("+ New", Modifier.fillMaxWidth()) { editing = Customer(name = "") } }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 22.dp)) {
            val shown = customers.filter { search.isBlank() || it.name.contains(search, true) || it.address.contains(search, true) || it.phone.contains(search) }
            if (shown.isEmpty()) item { Text("No customers yet.", color = Z.muted, modifier = Modifier.padding(vertical = 14.dp)) }
            items(shown, key = { it.id }) { c ->
                Column(Modifier.clickable { editing = c }.padding(vertical = 12.dp)) {
                    Text(c.name, color = Z.text, fontSize = 18.sp)
                    Label(listOf(c.address, c.phone).filter { it.isNotBlank() }.joinToString(" · "))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.phone.isNotBlank()) {
                            GhostButton("Call") { context.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${c.phone}"))) }
                            GhostButton("Text") { context.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${c.phone}"))) }
                        }
                        if (c.address.isNotBlank()) GhostButton("Directions") {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(c.address))))
                        }
                    }
                }
                Divider()
            }
        }
    }
    editing?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var phone by remember(c.id) { mutableStateOf(c.phone) }
        var email by remember(c.id) { mutableStateOf(c.email) }
        var address by remember(c.id) { mutableStateOf(c.address) }
        var notes by remember(c.id) { mutableStateOf(c.notes) }
        ZDialog(if (c.id == 0L) "New customer" else "Edit customer", { editing = null },
            confirm = "Save" to { if (name.isNotBlank()) { vm.saveCustomer(c.copy(name = name.trim(), phone = phone.trim(), email = email.trim(), address = address.trim(), notes = notes.trim())); editing = null } },
            dismiss = (if (c.id == 0L) "Cancel" else "Delete") to { if (c.id != 0L) vm.deleteCustomer(c); editing = null }) {
            Field("Name", name, { name = it }); Spacer(Modifier.height(6.dp))
            Field("Phone", phone, { phone = it }); Spacer(Modifier.height(6.dp))
            Field("Email", email, { email = it }); Spacer(Modifier.height(6.dp))
            Field("Address", address, { address = it }); Spacer(Modifier.height(6.dp))
            Field("Notes (panel location, gate code…)", notes, { notes = it }, singleLine = false)
        }
    }
}

// ---------------- Tools ----------------

@Composable
fun ToolsScreen(onBack: () -> Unit) {
    var volts by remember { mutableStateOf("120") }
    var amps by remember { mutableStateOf("20") }
    var feet by remember { mutableStateOf("100") }
    var awgIdx by remember { mutableStateOf(1) }
    var threePhase by remember { mutableStateOf(0) }
    // Copper, circular mils by size.
    val sizes = listOf("14" to 4110.0, "12" to 6530.0, "10" to 10380.0, "8" to 16510.0, "6" to 26240.0, "4" to 41740.0, "3" to 52620.0, "2" to 66360.0, "1" to 83690.0, "1/0" to 105600.0, "2/0" to 133100.0, "3/0" to 167800.0, "4/0" to 211600.0)
    fun drop(cm: Double): Double {
        val k = 12.9
        val mult = if (threePhase == 1) sqrt(3.0) else 2.0
        return mult * k * amps.toNum() * feet.toNum() / cm
    }
    val v = volts.toNum()
    val vd = drop(sizes[awgIdx].second)
    val pct = if (v > 0) vd / v * 100 else 0.0
    Column(Modifier.fillMaxSize()) {
        BackHeader("Tools", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            SectionHeader("01", "Voltage drop · copper")
            Segmented(listOf("Single phase", "Three phase"), threePhase) { threePhase = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Volts", volts, { volts = it }, Modifier.weight(1f), number = true)
                Field("Amps", amps, { amps = it }, Modifier.weight(1f), number = true)
                Field("One-way ft", feet, { feet = it }, Modifier.weight(1f), number = true)
            }
            Spacer(Modifier.height(10.dp))
            Label("Wire size (AWG)")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                sizes.take(7).forEachIndexed { i, s ->
                    Box(Modifier.weight(1f).background(if (i == awgIdx) Z.neon else Z.raised).clickable { awgIdx = i }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(s.first, fontFamily = Mono, fontSize = 12.sp, color = if (i == awgIdx) Z.onNeon else Z.text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                sizes.drop(7).forEachIndexed { j, s ->
                    val i = j + 7
                    Box(Modifier.weight(1f).background(if (i == awgIdx) Z.neon else Z.raised).clickable { awgIdx = i }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(s.first, fontFamily = Mono, fontSize = 12.sp, color = if (i == awgIdx) Z.onNeon else Z.text)
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Label("Drop")
            Text(String.format(Locale.US, "%.1f%%", pct), fontFamily = Condensed, fontSize = 52.sp, color = if (pct > 3) Z.warn else Z.ok)
            Label(String.format(Locale.US, "%.1f V · %.1f V at the load", vd, v - vd))
            Spacer(Modifier.height(10.dp))
            val better = sizes.drop(awgIdx + 1).firstOrNull { v > 0 && drop(it.second) / v * 100 <= 3 }
            if (pct > 3 && better != null) {
                MoneyRow("Try ${better.first} AWG", String.format(Locale.US, "%.1f%%", drop(better.second) / v * 100), Z.ok)
            }
            Spacer(Modifier.height(10.dp))
            Text("Resistance method, uncoated copper at about 75°C (K = 12.9). NEC suggests 3% or less for branch circuits. Check conditions on your job.", color = Z.muted, fontSize = 13.sp)
            Spacer(Modifier.height(30.dp))
        }
    }
}

// ---------------- Settings ----------------

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    fun save(n: Settings) = vm.saveSettings(n)

    Column(Modifier.fillMaxSize()) {
        BackHeader("Settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            SectionHeader("01", "App version")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Zimdars Electric v${Updater.currentVersion}", color = Z.text, fontSize = 18.sp)
                    Label("Build ${Updater.currentBuild}")
                }
            }
            when (val u = update) {
                Updater.State.Idle -> NeonButton("Check for updates", Modifier.fillMaxWidth()) {
                    update = Updater.State.Checking; scope.launch { update = Updater.check() }
                }
                Updater.State.Checking -> Text("Checking…", color = Z.muted)
                Updater.State.UpToDate -> {
                    Text("You're on the newest version.", color = Z.ok)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Check again", Modifier.fillMaxWidth()) { update = Updater.State.Checking; scope.launch { update = Updater.check() } }
                }
                is Updater.State.Available -> {
                    Text("New version: build ${u.release.build}", color = Z.neon, fontSize = 17.sp)
                    if (u.release.notes.isNotBlank()) Text(u.release.notes, color = Z.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("Download + install", Modifier.fillMaxWidth()) {
                        update = Updater.State.Downloading(0)
                        scope.launch {
                            val result = Updater.download(context, u.release) { p -> update = Updater.State.Downloading(p) }
                            update = result
                            if (result is Updater.State.Ready) Updater.install(context, result.file)
                        }
                    }
                }
                is Updater.State.Downloading -> Text("Downloading… ${u.percent}%", color = Z.muted)
                is Updater.State.Ready -> {
                    Text("Downloaded. If Android asked you to allow installs from this app, turn it on, then tap Install.", color = Z.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    NeonButton("Install", Modifier.fillMaxWidth()) { Updater.install(context, u.file) }
                }
                is Updater.State.Error -> {
                    Text(u.message, color = Z.warn)
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Try again", Modifier.fillMaxWidth()) { update = Updater.State.Checking; scope.launch { update = Updater.check() } }
                }
            }

            SectionHeader("02", "Business info · prints on estimates")
            TextFieldSaved("Business name", s.businessName, "bn") { save(s.copy(businessName = it)) }; Gap()
            TextFieldSaved("Address", s.address, "ad") { save(s.copy(address = it)) }; Gap()
            TextFieldSaved("Phone", s.phone, "ph") { save(s.copy(phone = it)) }; Gap()
            TextFieldSaved("Email", s.email, "em") { save(s.copy(email = it)) }; Gap()
            TextFieldSaved("License # (if any)", s.license, "li") { save(s.copy(license = it)) }; Gap()
            TextFieldSaved("Insurance company + phone", s.insurer, "in") { save(s.copy(insurer = it)) }

            SectionHeader("03", "Pricing defaults · new quotes")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Labor $/hr", s.laborRate, "lr", Modifier.weight(1f)) { save(s.copy(laborRate = it)) }
                NumField("Markup %", s.markupPct, "mk", Modifier.weight(1f)) { save(s.copy(markupPct = it)) }
            }
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Repair sales tax %", s.repairTaxPct, "tx", Modifier.weight(1f)) { save(s.copy(repairTaxPct = it)) }
                NumField("Quote valid (days)", s.quoteValidDays.toDouble(), "vd", Modifier.weight(1f)) { save(s.copy(quoteValidDays = it.toInt())) }
            }
            Text("Sales tax depends on the county of the job. Check the rate for where you work.", color = Z.muted, fontSize = 13.sp)

            SectionHeader("04", "My money split · private")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Savings %", s.savingsPct, "sv", Modifier.weight(1f)) { save(s.copy(savingsPct = it)) }
                NumField("Tax set-aside %", s.taxSetAsidePct, "ts", Modifier.weight(1f)) { save(s.copy(taxSetAsidePct = it)) }
            }
            Gap()
            NumField("Shop supplies % of materials", s.suppliesPct, "sp") { save(s.copy(suppliesPct = it)) }

            SectionHeader("05", "Truck")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Miles per gallon", s.mpg, "mpg", Modifier.weight(1f)) { save(s.copy(mpg = it)) }
                NumField("Gas $/gallon", s.gasPrice, "gas", Modifier.weight(1f)) { save(s.copy(gasPrice = it)) }
            }

            SectionHeader("06", "Terms · print on estimates")
            TextFieldSaved("How I charge (every customer sees this)", s.chargeNote, "cn", singleLine = false) { save(s.copy(chargeNote = it)) }; Gap()
            TextFieldSaved("Payment terms", s.paymentTerms, "pt", singleLine = false) { save(s.copy(paymentTerms = it)) }; Gap()
            TextFieldSaved("Warranty", s.warranty, "wa", singleLine = false) { save(s.copy(warranty = it)) }

            SectionHeader("07", "Job reminders")
            Row(Modifier.fillMaxWidth().clickable { save(s.copy(remindNightBefore = !s.remindNightBefore)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (s.remindNightBefore) "✓  " else "○  ") + "Remind me the evening before", color = Z.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Evening hour (0-23)", s.remindNightHour.toDouble(), "rnh", Modifier.weight(1f)) { save(s.copy(remindNightHour = it.toInt().coerceIn(0, 23))) }
                NumField("Minutes before start", s.remindMinutesBefore.toDouble(), "rmb", Modifier.weight(1f)) { save(s.copy(remindMinutesBefore = it.toInt().coerceAtLeast(0))) }
            }
            Text("19 = 7 PM. Set minutes to 0 to turn off the before-the-job reminder.", color = Z.muted, fontSize = 12.sp)

            SectionHeader("08", "Customer signatures")
            Text("Paste the signing key Claude gave you. It lets this phone create signing links; keep it private.", color = Z.muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            TextFieldSaved("Signing key", s.signKey, "sk") { save(s.copy(signKey = it.trim())) }
            if (s.signKey.isNotBlank()) { Spacer(Modifier.height(4.dp)); StatusDot("Signing is set up", Z.ok) }

            SectionHeader("09", "Quote numbers")
            NumField("Next quote number", s.nextQuoteNo.toDouble(), "nq") { save(s.copy(nextQuoteNo = it.toInt().coerceAtLeast(1))) }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Gap() = Spacer(Modifier.height(8.dp))
