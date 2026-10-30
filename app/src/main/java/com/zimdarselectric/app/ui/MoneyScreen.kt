package com.zimdarselectric.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.zimdarselectric.app.data.Billing
import com.zimdarselectric.app.data.MoneyCalc
import com.zimdarselectric.app.data.Status
import com.zimdarselectric.app.data.money
import com.zimdarselectric.app.data.qtyText
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Your business money: this month, NY sales tax by quarter, and income tax estimates. */
@Composable
fun MoneyScreen(vm: AppViewModel, onBack: () -> Unit, onOpenQuote: (Long) -> Unit) {
    val quotes by vm.quotes.collectAsState()
    val lines by vm.lines.collectAsState()
    val expenses by vm.expenses.collectAsState()
    val trips by vm.trips.collectAsState()
    val s by vm.settings.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val year = LocalDate.now().year
    val dueFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

    Column(Modifier.fillMaxSize()) {
        BackHeader("Money", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Segmented(listOf("Month", "Sales tax", "Income tax"), tab) { tab = it }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹", color = Z.neon, fontSize = 28.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 12.dp))
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")).uppercase(), fontFamily = Condensed, fontSize = 24.sp, color = Z.text, modifier = Modifier.weight(1f))
                        Text("›", color = Z.neon, fontSize = 28.sp, modifier = Modifier.clickable { month = month.plusMonths(1) }.padding(horizontal = 12.dp))
                    }
                    val m = MoneyCalc.summarize(MoneyCalc.ms(month.atDay(1)), MoneyCalc.ms(month.plusMonths(1).atDay(1)), quotes, lines, expenses, trips, s)
                    SummaryBlock(m, s.taxSetAsidePct, s.savingsPct)
                    SectionHeader("YTD", "All of $year")
                    val y = MoneyCalc.summarize(MoneyCalc.ms(LocalDate.of(year, 1, 1)), MoneyCalc.ms(LocalDate.of(year + 1, 1, 1)), quotes, lines, expenses, trips, s)
                    SummaryBlock(y, s.taxSetAsidePct, s.savingsPct)
                    val unpaid = quotes.filter { it.parentId == null && it.paidAt == null && it.status == Status.DONE }
                    if (unpaid.isNotEmpty()) {
                        SectionHeader("!", "Done but not paid")
                        unpaid.forEach { q ->
                            val owed = Billing.invoiceTotal(q, quotes.filter { it.parentId == q.id }, lines, s)
                            ListRow(q.title.ifBlank { q.number }, "${q.number} · ${q.customerName} · ${money(owed)}".uppercase() +
                                (q.invoiceSentAt?.let { " · INVOICED ${Billing.day(it).uppercase()}" } ?: " · NOT INVOICED"), onClick = { onOpenQuote(q.id) })
                        }
                    }
                }
                1 -> {
                    Text("New York sales tax is filed by quarter. Only repair work is taxable; new installs with a signed ST-124 aren't. Sales count on the day the job was done.",
                        color = Z.muted, fontSize = 13.sp)
                    val now = LocalDate.now()
                    MoneyCalc.salesTaxQuarters(year).filter { it.from <= now }.reversed().forEach { q ->
                        val t = MoneyCalc.salesTax(MoneyCalc.ms(q.from), MoneyCalc.ms(q.toExclusive), quotes, lines, s)
                        SectionHeader("Q", q.label)
                        Label("Return due ${q.due.format(dueFmt)}" + if (now >= q.toExclusive) "" else " · quarter still open", color = if (now >= q.toExclusive && now <= q.due) Z.warn else Z.muted)
                        MoneyRow("Gross sales", money(t.gross))
                        MoneyRow("Taxable sales (repairs)", money(t.taxable))
                        MoneyRow("Sales tax collected", money(t.tax), Z.info, bold = true)
                        MoneyRow("Capital improvements (no tax)", money(t.nonTaxable))
                        t.byRate.forEach { (rate, v) -> Label("${qtyText(rate)}% jurisdiction: ${money(v.first)} taxable · ${money(v.second)} tax") }
                        t.missingSt124.forEach { mq ->
                            Text("⚠ ${mq.number} ${mq.customerName}: install work without a signed ST-124 →", color = Z.warn, fontSize = 13.sp,
                                modifier = Modifier.clickable { onOpenQuote(mq.id) }.padding(vertical = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("You file on New York's Online Services (tax.ny.gov) once you're registered for sales tax. If you have no sales in a quarter, you still file a \"no sales\" return.",
                        color = Z.muted, fontSize = 12.sp)
                }
                else -> {
                    Text("A rough estimate of what you owe on your business profit: 15.3% self-employment tax plus your federal (${qtyText(s.fedTaxPct)}%) and NY (${qtyText(s.stateTaxPct)}%) rates. Change the rates in Settings.",
                        color = Z.muted, fontSize = 13.sp)
                    var ytd = 0.0
                    MoneyCalc.irsQuarters(year).forEach { q ->
                        val sum = MoneyCalc.summarize(MoneyCalc.ms(q.from), MoneyCalc.ms(q.toExclusive), quotes, lines, expenses, trips, s)
                        val est = MoneyCalc.incomeTax(sum.profit, s)
                        ytd += est.total
                        SectionHeader("Q", q.label)
                        Label("Payment due ${q.due.format(dueFmt)}")
                        MoneyRow("Profit", money(sum.profit))
                        MoneyRow("Self-employment tax", money(est.selfEmployment))
                        MoneyRow("Income tax (fed + NY)", money(est.incomeTax))
                        MoneyRow("Estimated payment", money(est.total), Z.info, bold = true)
                    }
                    SectionHeader("YTD", "Total for $year")
                    MoneyRow("Estimated tax so far", money(ytd), Z.neon, bold = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Because you also have a W-2 job, you can cover this instead by raising the withholding at DeIorio's (new W-4). Either way avoids an underpayment penalty. Federal payments: IRS Direct Pay. NY: Online Services.",
                        color = Z.muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SummaryBlock(m: MoneyCalc.Summary, taxPct: Double, savePct: Double) {
    Label(if (m.profit >= 0) "Profit" else "Loss")
    Text(money(m.profit), fontFamily = Condensed, fontSize = 46.sp, color = if (m.profit >= 0) Z.neon else Z.warn)
    MoneyRow("Income · ${m.jobsPaid} job${if (m.jobsPaid == 1) "" else "s"} paid", money(m.income))
    if (m.taxCollected > 0) Label("Plus ${money(m.taxCollected)} sales tax collected (owed to NY, not income)", color = Z.info)
    MoneyRow("Expenses", "−" + money(m.expenses))
    MoneyRow("Mileage · ${qtyText(Math.round(m.miles * 10) / 10.0)} mi", "−" + money(m.mileage))
    if (m.profit > 0) {
        MoneyRow("Set aside for taxes ${qtyText(taxPct)}%", money(m.profit * taxPct / 100), Z.info)
        MoneyRow("Savings ${qtyText(savePct)}%", money(m.income * savePct / 100), Z.ok)
    }
}
