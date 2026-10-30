package com.zimdarselectric.app.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Money math for the Money screen: monthly summary, NY sales tax by quarter, income tax estimates. */
object MoneyCalc {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    fun ms(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    data class Summary(
        val jobsPaid: Int, val income: Double, val taxCollected: Double,
        val expenses: Double, val mileage: Double, val miles: Double,
    ) { val profit: Double get() = income - expenses - mileage }

    /** [from, to) in milliseconds. Income leaves out sales tax (that's NY's money). */
    fun summarize(from: Long, to: Long, quotes: List<Quote>, lines: List<QuoteLine>, expenses: List<Expense>, trips: List<Trip>, s: Settings): Summary {
        fun inside(t: Long?) = t != null && t >= from && t < to
        var income = 0.0; var tax = 0.0; var jobs = 0
        quotes.filter { inside(it.paidAt) && it.paidAmount > 0 }.forEach { q ->
            val related = quotes.filter { it.id == q.id || it.parentId == q.id }
            val totals = related.map { j -> computeTotals(j, lines.filter { it.quoteId == j.id }, s) }
            val total = totals.sumOf { it.total }; val st = totals.sumOf { it.salesTax }
            val share = if (total > 0) q.paidAmount / total else 1.0
            income += q.paidAmount - st * share; tax += st * share; jobs++
        }
        val spent = expenses.filter { inside(it.date) }.sumOf { it.amount }
        val tripMiles = trips.filter { inside(it.startedAt) }
        // Jobs with miles on the quote but no logged drive (e.g. written up afterward) still count.
        val quoteMiles = quotes.filter { q -> q.miles > 0 && inside(q.completedAt ?: q.paidAt) && trips.none { it.quoteId == q.id } }
        val miles = tripMiles.sumOf { it.miles } + quoteMiles.sumOf { it.miles }
        val mileage = tripMiles.sumOf { it.miles * Geo.irsRate(it.startedAt) } + quoteMiles.sumOf { it.miles * Geo.irsRate(it.completedAt ?: it.paidAt!!) }
        return Summary(jobs, income, tax, spent, mileage, miles)
    }

    data class Quarter(val label: String, val from: LocalDate, val toExclusive: LocalDate, val due: LocalDate)

    /** NY sales tax quarters: Mar–May, Jun–Aug, Sep–Nov, Dec–Feb. Returns the ones that overlap [year]. */
    fun salesTaxQuarters(year: Int): List<Quarter> {
        val f = DateTimeFormatter.ofPattern("MMM d")
        return listOf(
            LocalDate.of(year - 1, 12, 1), LocalDate.of(year, 3, 1), LocalDate.of(year, 6, 1), LocalDate.of(year, 9, 1), LocalDate.of(year, 12, 1),
        ).map { start ->
            val end = start.plusMonths(3)
            Quarter("${start.format(f)} – ${end.minusDays(1).format(f)}, ${end.minusDays(1).year}", start, end, end.withDayOfMonth(20))
        }
    }

    /** IRS estimated tax periods for [year]. */
    fun irsQuarters(year: Int): List<Quarter> {
        val f = DateTimeFormatter.ofPattern("MMM d")
        val starts = listOf(LocalDate.of(year, 1, 1), LocalDate.of(year, 4, 1), LocalDate.of(year, 6, 1), LocalDate.of(year, 9, 1), LocalDate.of(year + 1, 1, 1))
        val dues = listOf(LocalDate.of(year, 4, 15), LocalDate.of(year, 6, 15), LocalDate.of(year, 9, 15), LocalDate.of(year + 1, 1, 15))
        return (0 until 4).map { i -> Quarter("${starts[i].format(f)} – ${starts[i + 1].minusDays(1).format(f)}", starts[i], starts[i + 1], dues[i]) }
    }

    data class SalesTax(val gross: Double, val taxable: Double, val tax: Double, val nonTaxable: Double, val byRate: Map<Double, Pair<Double, Double>>, val missingSt124: List<Quote>)

    /** Sales in a period (by the day the job was done, or paid if no done date). Change orders count on their own dates. */
    fun salesTax(from: Long, to: Long, quotes: List<Quote>, lines: List<QuoteLine>, s: Settings): SalesTax {
        var taxable = 0.0; var tax = 0.0; var non = 0.0
        val byRate = mutableMapOf<Double, Pair<Double, Double>>()
        val missing = mutableListOf<Quote>()
        quotes.filter { it.kind == Kind.QUOTE }.forEach { q ->
            val parent = q.parentId?.let { pid -> quotes.firstOrNull { it.id == pid } }
            val date = q.completedAt ?: q.paidAt ?: parent?.completedAt ?: parent?.paidAt ?: return@forEach
            if (date < from || date >= to) return@forEach
            val t = computeTotals(q, lines.filter { it.quoteId == q.id }, s)
            taxable += t.repairPart; tax += t.salesTax; non += t.installPart
            if (t.repairPart > 0) byRate[q.taxRatePct] = (byRate[q.taxRatePct] ?: (0.0 to 0.0)).let { (a, b) -> (a + t.repairPart) to (b + t.salesTax) }
            if (t.installPart > 0 && q.st124SignedAt == null && q.parentId == null) missing += q
        }
        return SalesTax(taxable + non + tax, taxable, tax, non, byRate, missing)
    }

    data class IncomeTax(val profit: Double, val selfEmployment: Double, val incomeTax: Double) { val total get() = selfEmployment + incomeTax }

    /** Rough estimate: 15.3% self-employment tax on 92.35% of profit, plus your federal + NY rate on the rest. */
    fun incomeTax(profit: Double, s: Settings): IncomeTax {
        val p = maxOf(0.0, profit)
        val se = p * 0.9235 * 0.153
        val it = maxOf(0.0, p - se / 2) * (s.fedTaxPct + s.stateTaxPct) / 100
        return IncomeTax(profit, se, it)
    }
}
