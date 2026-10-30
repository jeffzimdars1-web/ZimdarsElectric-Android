package com.zimdarselectric.app.data

import java.text.NumberFormat
import java.util.Locale

/** Everything a quote adds up to: what the customer sees, and your private numbers. */
data class QuoteTotals(
    val materialsCost: Double,
    val markup: Double,
    val materialsPrice: Double,
    val labor: Double,
    val salesTax: Double,
    val total: Double,
    // Private
    val fuel: Double,
    val supplies: Double,
    val profit: Double,
    val savings: Double,
    val taxSetAside: Double,
    val takeHome: Double,
    val perHour: Double,
    /** Install + repair jobs: the two parts before tax, as listed separately on the bill. */
    val installPart: Double = 0.0,
    val repairPart: Double = 0.0,
)

/** Whether this item is billed as repair (taxed) on this quote. */
fun isRepairLine(q: Quote, l: QuoteLine) = when (q.jobType) {
    JobType.REPAIR -> true
    JobType.BOTH -> l.repair
    else -> false
}

/** All labor hours on the quote (install + repair). */
fun totalHours(q: Quote) = q.laborHours + if (q.jobType == JobType.BOTH) q.repairHours else 0.0

fun computeTotals(q: Quote, lines: List<QuoteLine>, s: Settings): QuoteTotals {
    val materialsCost = lines.sumOf { it.qty * it.unitCost }
    val markup = materialsCost * q.markupPct / 100.0
    val materialsPrice = materialsCost + markup
    val hours = totalHours(q)
    val labor = hours * q.laborRate
    val factor = 1 + q.markupPct / 100.0
    val repairMaterials = lines.filter { isRepairLine(q, it) }.sumOf { it.qty * it.unitCost } * factor
    val repairLabor = when (q.jobType) { JobType.REPAIR -> labor; JobType.BOTH -> q.repairHours * q.laborRate; else -> 0.0 }
    val repairPart = repairMaterials + repairLabor
    val installPart = materialsPrice + labor - repairPart
    val taxable = repairPart
    val salesTax = taxable * q.taxRatePct / 100.0
    val total = materialsPrice + labor + salesTax

    val fuel = if (s.mpg > 0) q.miles / s.mpg * s.gasPrice else 0.0
    val supplies = materialsCost * s.suppliesPct / 100.0
    val profit = total - salesTax - materialsCost - fuel - supplies
    val savings = (total - salesTax) * s.savingsPct / 100.0
    val taxSetAside = maxOf(profit, 0.0) * s.taxSetAsidePct / 100.0
    val takeHome = profit - savings - taxSetAside
    val perHour = if (hours > 0) takeHome / hours else 0.0
    return QuoteTotals(materialsCost, markup, materialsPrice, labor, salesTax, total,
        fuel, supplies, profit, savings, taxSetAside, takeHome, perHour, installPart, repairPart)
}

/**
 * How much of each material is on hold: the sum of quantities on every quote that is
 * Draft, Sent or Approved. Pass [excludeQuoteId] to leave one quote out.
 */
fun holdsByMaterial(quotes: List<Quote>, lines: List<QuoteLine>, excludeQuoteId: Long? = null): Map<Long, Double> {
    val holding = quotes.filter { it.status in Status.holding && it.id != excludeQuoteId }.map { it.id }.toSet()
    return lines.filter { it.quoteId in holding && it.materialId != null }
        .groupBy { it.materialId!! }
        .mapValues { (_, ls) -> ls.sumOf { it.qty } }
}

private val moneyFmt: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)
fun money(v: Double): String = moneyFmt.format(v)

fun qtyText(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

fun String.toNum(): Double = this.replace(",", "").replace("$", "").trim().toDoubleOrNull() ?: 0.0

/** "2 boxes + 37" for an amount of pieces when an item comes [pack] to a box. Blank if not boxed. */
fun boxText(qty: Double, pack: Double): String {
    if (pack <= 1.0 || qty <= 0.0) return ""
    val boxes = Math.floor(qty / pack + 1e-9).toLong()
    val loose = qty - boxes * pack
    val b = if (boxes == 0L) "" else "$boxes box" + if (boxes == 1L) "" else "es"
    val l = if (loose < 1e-9) "" else qtyText(loose) + " loose"
    return listOf(b, l).filter { it.isNotBlank() }.joinToString(" + ")
}

/** "Thu, Nov 5 · 8:00 AM · about 3 hr" for quotes and the customer's copy. */
fun scheduleText(startAt: Long?, hours: Double, hasTime: Boolean = true): String {
    if (startAt == null) return ""
    val d = java.text.SimpleDateFormat(if (hasTime) "EEE, MMM d · h:mm a" else "EEE, MMM d · 'time TBD'", Locale.US).format(java.util.Date(startAt))
    return if (hours > 0) "$d · about ${qtyText(hours)} hr" else d
}
