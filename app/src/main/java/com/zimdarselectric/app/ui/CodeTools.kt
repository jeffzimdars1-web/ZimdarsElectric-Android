package com.zimdarselectric.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zimdarselectric.app.data.Nec
import com.zimdarselectric.app.data.qtyText
import com.zimdarselectric.app.data.toNum
import java.util.Locale

private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

/** − 3 + counter. */
@Composable
fun Stepper(label: String, value: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(40.dp).background(Z.raised, cut(6.dp)).clickable { onChange(maxOf(0, value - 1)) }, contentAlignment = Alignment.Center) { Text("−", color = Z.text, fontSize = 20.sp) }
        Text(value.toString(), fontFamily = Condensed, fontSize = 22.sp, color = if (value > 0) Z.neon else Z.muted, modifier = Modifier.width(44.dp).padding(horizontal = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(40.dp).background(Z.raised, cut(6.dp)).clickable { onChange(value + 1) }, contentAlignment = Alignment.Center) { Text("+", color = Z.text, fontSize = 20.sp) }
    }
}

@Composable
private fun Pills(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val sel = o == selected
            Box(Modifier.background(if (sel) Z.neon else Z.raised).clickable { onPick(o) }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(o, fontFamily = Mono, fontSize = 12.sp, color = if (sel) Z.onNeon else Z.text)
            }
        }
    }
}

@Composable
private fun Verdict(ok: Boolean, big: String, line: String) {
    Column(Modifier.fillMaxWidth().border(1.5.dp, if (ok) Z.ok else Z.warn).padding(14.dp)) {
        Label(if (ok) "OK" else "Too full", color = if (ok) Z.ok else Z.warn)
        Text(big, fontFamily = Condensed, fontSize = 40.sp, color = if (ok) Z.ok else Z.warn)
        Text(line, color = Z.text, fontSize = 14.sp)
    }
}

// ---------------- Box fill ----------------

@Composable
fun BoxFillTool() {
    val wires = remember { mutableStateMapOf("14" to 0, "12" to 0, "10" to 0) }
    var grounds by remember { mutableIntStateOf(0) }
    var devices by remember { mutableIntStateOf(1) }
    var clamps by remember { mutableStateOf(true) }
    var studs by remember { mutableIntStateOf(0) }
    var box by remember { mutableStateOf("Marked on box") }
    var marked by remember { mutableStateOf("") }
    Text("Count each hot, neutral and traveler once (a wire passing through unbroken also counts once). Pigtails that start and end in the box don't count.",
        color = Z.muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    listOf("14", "12", "10").forEach { s -> Stepper("#$s wires (not grounds)", wires[s] ?: 0) { wires[s] = it } }
    Stepper("Ground wires", grounds) { grounds = it }
    Stepper("Devices (switch / receptacle)", devices) { devices = it }
    Stepper("Fixture studs / hickeys", studs) { studs = it }
    Row(Modifier.fillMaxWidth().clickable { clamps = !clamps }.padding(vertical = 10.dp)) {
        Text((if (clamps) "✓  " else "○  ") + "Box has internal cable clamps", color = Z.text, fontSize = 15.sp)
    }
    val fill = Nec.boxFill(wires.toMap(), grounds, devices, clamps, studs)
    Label("Box")
    Spacer(Modifier.height(6.dp))
    Pills(listOf("Marked on box") + Nec.metalBoxes.map { it.first }, box) { box = it }
    if (box == "Marked on box") {
        Spacer(Modifier.height(8.dp))
        Field("Volume stamped inside the box (cu in)", marked, { marked = it }, number = true)
    }
    val volume = if (box == "Marked on box") marked.toNum() else Nec.metalBoxes.first { it.first == box }.second
    Spacer(Modifier.height(12.dp))
    if (fill.required > 0) {
        if (volume > 0) Verdict(volume >= fill.required, "${f1(fill.required)} of ${f1(volume)} cu in",
            if (volume >= fill.required) "${f1(volume - fill.required)} cu in to spare." else "Over by ${f1(fill.required - volume)} cu in. Use a bigger box or a mud ring/extension.")
        else { Label("Box needs at least"); Text("${f1(fill.required)} cu in", fontFamily = Condensed, fontSize = 40.sp, color = Z.neon) }
        Spacer(Modifier.height(8.dp))
        fill.lines.forEach { (k, v) -> MoneyRow(k, "${f1(v)} cu in") }
        val fits = Nec.metalBoxes.filter { it.second >= fill.required }.minByOrNull { it.second }
        fits?.let { Label("Smallest standard metal box that fits: ${it.first} (${f1(it.second)})") }
    }
    Spacer(Modifier.height(8.dp))
    Text("NEC 314.16 (2023). Grounds: all count as one, plus ¼ for each over four.", color = Z.muted, fontSize = 12.sp)
}

// ---------------- Conduit fill ----------------

@Composable
fun ConduitFillTool() {
    var type by remember { mutableStateOf("EMT") }
    var size by remember { mutableStateOf("1/2") }
    val wires = remember { mutableStateMapOf<String, Int>() }
    var addSize by remember { mutableStateOf("12") }
    Label("Conduit")
    Spacer(Modifier.height(6.dp))
    Pills(Nec.conduits.keys.toList(), type) { type = it }
    Spacer(Modifier.height(6.dp))
    Pills(Nec.conduits.getValue(type).map { it.first + "\"" }, "$size\"") { size = it.removeSuffix("\"") }
    SectionHeader("", "THHN / THWN-2 wires inside")
    Pills(Nec.sizes, addSize) { addSize = it }
    Stepper("#$addSize wires", wires[addSize] ?: 0) { wires[addSize] = it }
    val shown = wires.filter { it.value > 0 }
    if (shown.isNotEmpty()) Label("In the pipe: " + shown.entries.sortedBy { Nec.sizes.indexOf(it.key) }.joinToString(" · ") { "${it.value} × #${it.key}" })
    Spacer(Modifier.height(12.dp))
    val r = Nec.conduitFill(type, size, wires.toMap())
    if (r.wireArea > 0) {
        Verdict(r.ok, "${f1(r.pct)}% full", "Limit ${qtyText(r.limit * 100)}% for ${shown.values.sum()} wire${if (shown.values.sum() == 1) "" else "s"}.")
        Spacer(Modifier.height(8.dp))
        Nec.smallestConduit(type, wires.toMap())?.let { MoneyRow("Smallest $type that works", "$it\"", Z.ok) } ?: Label("Too much for 2\" $type. Split into two runs.", color = Z.warn)
    }
    Spacer(Modifier.height(10.dp))
    Label("How many fit in $size\" $type")
    listOf("14", "12", "10", "8", "6").forEach { w -> MoneyRow("#$w THHN", Nec.maxWires(type, size, w).toString()) }
    Text("NEC Chapter 9 Tables 1, 4, 5 (2023). Count the green ground too. More than 3 current-carrying wires also lowers ampacity (310.15(C)(1)).",
        color = Z.muted, fontSize = 12.sp)
}

// ---------------- Wire size ----------------

@Composable
fun WireSizeTool() {
    var amps by remember { mutableStateOf("20") }
    var feet by remember { mutableStateOf("50") }
    var volts by remember { mutableStateOf("120") }
    var al by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(Nec.WireType.NM) }
    var continuous by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Load amps", amps, { amps = it }, Modifier.weight(1f), number = true)
        Field("One-way ft", feet, { feet = it }, Modifier.weight(1f), number = true)
        Field("Volts", volts, { volts = it }, Modifier.weight(1f), number = true)
    }
    Spacer(Modifier.height(8.dp))
    Segmented(Nec.WireType.entries.map { it.label.substringBefore(" ·").substringBefore(" /") }, type.ordinal) { type = Nec.WireType.entries[it] }
    Spacer(Modifier.height(6.dp))
    Segmented(listOf("Copper", "Aluminum"), if (al) 1 else 0) { al = it == 1 }
    Row(Modifier.fillMaxWidth().clickable { continuous = !continuous }.padding(vertical = 10.dp)) {
        Text((if (continuous) "✓  " else "○  ") + "Continuous load, 3+ hours (EV charger, heat, water heater)", color = Z.text, fontSize = 14.sp)
    }
    val pick = Nec.pickWire(amps.toNum(), feet.toNum(), volts.toNum().coerceAtLeast(1.0), al, type, continuous)
    if (amps.toNum() > 0 && pick != null) {
        Column(Modifier.fillMaxWidth().border(1.5.dp, Z.ok).padding(14.dp)) {
            Label("Use", color = Z.ok)
            Text("#${pick.size} ${if (al) "aluminum" else "copper"}", fontFamily = Condensed, fontSize = 40.sp, color = Z.ok)
            Text(pick.why, color = Z.text, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        MoneyRow("Ampacity (${type.label.substringAfter("· ", "60°C")})", "${pick.ampacity} A")
        MoneyRow("Largest breaker", "${pick.breakerMax} A")
        MoneyRow("Voltage drop", "${f1(pick.dropPct)}%")
    } else if (amps.toNum() > 0) Text("Nothing up to 4/0 works for that. Check the numbers or run a feeder.", color = Z.warn)
    SectionHeader("", "Dwelling service / main feeder (310.12)")
    Nec.dwellingService.forEach { (a, w) -> MoneyRow("$a A", "#${w.first} Cu · #${w.second} Al") }
    Spacer(Modifier.height(8.dp))
    Text("NEC 310.16 (2023): NM cable sized at 60°C (334.80); 14/12/10 limited to 15/20/30 A (240.4(D)). Voltage drop kept at 3% or less. Derate for hot attics, bundles or more than 3 wires in a pipe.",
        color = Z.muted, fontSize = 12.sp)
}

// ---------------- Load calc ----------------

@Composable
fun LoadCalcTool() {
    var sqft by remember { mutableStateOf("1500") }
    var sa by remember { mutableIntStateOf(2) }
    var laundry by remember { mutableIntStateOf(1) }
    var ac by remember { mutableStateOf("0") }
    var heat by remember { mutableStateOf("0") }
    var heatUnits by remember { mutableIntStateOf(1) }
    val picked = remember { mutableStateListOf<Nec.Appliance>() }
    var otherName by remember { mutableStateOf("") }
    var otherWatts by remember { mutableStateOf("") }
    Text("Optional method for a house (220.82). Use nameplate watts when you have them.", color = Z.muted, fontSize = 13.sp)
    Spacer(Modifier.height(8.dp))
    Field("Living area (sq ft, not garage or open porch)", sqft, { sqft = it }, number = true)
    Stepper("Kitchen small-appliance circuits (20 A)", sa) { sa = it }
    Stepper("Laundry circuits", laundry) { laundry = it }
    SectionHeader("", "Appliances")
    Nec.commonAppliances.forEach { a ->
        val on = picked.any { it.name == a.name }
        Row(Modifier.fillMaxWidth().clickable { if (on) picked.removeAll { it.name == a.name } else picked.add(a) }.padding(vertical = 8.dp)) {
            Text((if (on) "✓  " else "○  ") + a.name, color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Label("${qtyText(a.watts)} W")
        }
    }
    picked.filter { p -> Nec.commonAppliances.none { it.name == p.name } }.forEach { a ->
        Row(Modifier.fillMaxWidth().clickable { picked.remove(a) }.padding(vertical = 8.dp)) {
            Text("✓  ${a.name}", color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f)); Label("${qtyText(a.watts)} W · remove")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field("Other appliance", otherName, { otherName = it }, Modifier.weight(1.4f))
        Field("Watts", otherWatts, { otherWatts = it }, Modifier.weight(1f), number = true)
        Label("Add", color = Z.neon, modifier = Modifier.clickable {
            if (otherName.isNotBlank() && otherWatts.toNum() > 0) { picked.add(Nec.Appliance(otherName.trim(), otherWatts.toNum())); otherName = ""; otherWatts = "" }
        }.padding(8.dp))
    }
    SectionHeader("", "Heating + cooling")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("A/C or heat pump (W)", ac, { ac = it }, Modifier.weight(1f), number = true)
        Field("Electric heat (W)", heat, { heat = it }, Modifier.weight(1f), number = true)
    }
    if (heat.toNum() > 0) Stepper("Separately controlled heat units", heatUnits) { heatUnits = maxOf(1, it) }
    val r = Nec.loadCalc(sqft.toNum(), sa, laundry, picked.toList(), ac.toNum(), heat.toNum(), heatUnits)
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().border(1.5.dp, Z.neon).padding(14.dp)) {
        Label("Calculated load", color = Z.neon)
        Text("${f1(r.amps)} A", fontFamily = Condensed, fontSize = 40.sp, color = Z.neon)
        Text("${qtyText(Math.round(r.total).toDouble())} VA at 240 V → ${r.service} A service is enough.", color = Z.text, fontSize = 14.sp)
    }
    Spacer(Modifier.height(8.dp))
    MoneyRow("General + appliances", "${qtyText(Math.round(r.general).toDouble())} VA")
    MoneyRow("After demand (10 kVA + 40% of rest)", "${qtyText(Math.round(r.generalDemand).toDouble())} VA")
    MoneyRow("Heating or cooling (larger)", "${qtyText(Math.round(r.heatCool).toDouble())} VA")
    Text("NEC 220.82 (2023). Planning number only. Your inspector or utility may want the standard method or room for future loads.", color = Z.muted, fontSize = 12.sp)
}


// ---------------- EMT bends ----------------

@Composable
fun EmtBendTool() {
    var kind by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf("1/2") }
    var angle by remember { mutableStateOf("30°") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    val kinds = listOf("Stub 90", "Offset", "3-pt saddle", "4-pt saddle", "Rolling offset")
    Pills(kinds, kinds[kind]) { kind = kinds.indexOf(it); a = ""; b = ""; c = "" }
    Spacer(Modifier.height(8.dp))
    val ang = com.zimdarselectric.app.data.Bends.angles.first { it.deg == angle }
    fun ins(v: Double) = com.zimdarselectric.app.data.Bends.inches(v)
    @Composable fun big(label: String, v: Double) {
        Label(label, color = Z.ok)
        Text(ins(v), fontFamily = Condensed, fontSize = 36.sp, color = Z.ok)
    }
    @Composable fun anglePick() {
        Label("Bend angle"); Spacer(Modifier.height(6.dp))
        Pills(com.zimdarselectric.app.data.Bends.angles.map { it.deg }, angle) { angle = it }
        Spacer(Modifier.height(8.dp))
    }
    when (kind) {
        0 -> {
            Label("Pipe size"); Spacer(Modifier.height(6.dp))
            Pills(com.zimdarselectric.app.data.Bends.takeUp.keys.map { "$it\"" }, "$size\"") { size = it.removeSuffix("\"") }
            Spacer(Modifier.height(8.dp))
            Field("Stub height (inches, to the back of the 90)", a, { a = it }, number = true)
            if (a.toNum() > 0) {
                Spacer(Modifier.height(10.dp))
                big("Mark the pipe at", com.zimdarselectric.app.data.Bends.stub(a.toNum(), size).mark)
                Text("Measured from the end. Take-up for $size\" is ${ins(com.zimdarselectric.app.data.Bends.takeUp.getValue(size))}. Put the mark at the bender's arrow, hook toward the end, bend to 90.", color = Z.text, fontSize = 14.sp)
            }
            Text("Back-to-back 90: measure the outside length you need, mark it, and line that mark up with the star (back of the bend) instead of the arrow.", color = Z.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        1 -> {
            anglePick()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Offset depth (in)", a, { a = it }, Modifier.weight(1f), number = true)
                Field("End to obstruction (in)", b, { b = it }, Modifier.weight(1f), number = true)
            }
            if (a.toNum() > 0) {
                val o = com.zimdarselectric.app.data.Bends.offset(a.toNum(), ang, b.toNum())
                Spacer(Modifier.height(10.dp))
                if (b.toNum() > 0) big("First mark", o.firstMark)
                big("Second mark, after the first", o.between)
                Text("Multiplier ${qtyText(ang.multiplier)} for $angle. Shrink ${ins(o.shrink)} (already added to the first mark). Bend both to $angle, the second one flipped 180°.", color = Z.text, fontSize = 14.sp)
            }
        }
        2 -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Saddle height (in)", a, { a = it }, Modifier.weight(1f), number = true)
                Field("End to center of obstruction (in)", b, { b = it }, Modifier.weight(1f), number = true)
            }
            if (a.toNum() > 0 && b.toNum() > 0) {
                val s = com.zimdarselectric.app.data.Bends.saddle3(a.toNum(), b.toNum())
                Spacer(Modifier.height(10.dp))
                big("Center mark (45°)", s.center)
                big("Outside marks (22.5° each)", s.outer1)
                Text("and ${ins(s.outer2)} from the end (2.5 × height each side of center). Shrink ${ins(s.shrink)} is included. Bend the center at the bender's notch/teardrop, outsides at the arrow.", color = Z.text, fontSize = 14.sp)
            }
        }
        3 -> {
            anglePick()
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Height (in)", a, { a = it }, Modifier.weight(1f), number = true)
                Field("Obstruction width (in)", c, { c = it }, Modifier.weight(1f), number = true)
            }
            Field("End to near side of obstruction (in)", b, { b = it }, number = true)
            if (a.toNum() > 0 && b.toNum() > 0) {
                val s = com.zimdarselectric.app.data.Bends.saddle4(a.toNum(), c.toNum(), ang, b.toNum())
                Spacer(Modifier.height(10.dp))
                Label("Marks from the end", color = Z.ok)
                s.marks.forEachIndexed { i, m -> MoneyRow("Mark ${i + 1}", ins(m), Z.ok, bold = true) }
                Text("All four bends $angle. Marks 1–2 are the first offset (up), 3–4 the second (back down). Total shrink ${ins(s.shrink)}.", color = Z.text, fontSize = 14.sp)
            }
        }
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field("Rise (in)", a, { a = it }, Modifier.weight(1f), number = true)
                Field("Roll (in)", b, { b = it }, Modifier.weight(1f), number = true)
            }
            anglePick()
            if (a.toNum() > 0 && b.toNum() > 0) {
                val t = com.zimdarselectric.app.data.Bends.rolling(a.toNum(), b.toNum())
                val o = com.zimdarselectric.app.data.Bends.offset(t, ang, 0.0)
                Spacer(Modifier.height(10.dp))
                big("True offset", t)
                big("Distance between marks", o.between)
                Text("Bend it like a normal $angle offset of ${ins(t)}, then roll the pipe so it lines up with both the rise and the roll. Shrink ${ins(o.shrink)}.", color = Z.text, fontSize = 14.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text("Common hand-bender numbers (take-up 5\", 6\", 8\", 11\" for 1/2\"–1-1/4\"). Check the deduct stamped on your bender; makes vary a little.", color = Z.muted, fontSize = 12.sp)
}
