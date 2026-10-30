package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QuoteMathTest {
    private val s = Settings()

    @Test fun both_taxesOnlyRepairPart() {
        val q = Quote(number = "Q-1", jobType = JobType.BOTH, laborHours = 6.0, repairHours = 2.0, laborRate = 100.0, markupPct = 0.0, taxRatePct = 8.75)
        val lines = listOf(
            QuoteLine(quoteId = 0, name = "30A breaker", qty = 1.0, unitCost = 20.0),
            QuoteLine(quoteId = 0, name = "Receptacle", qty = 2.0, unitCost = 5.0, repair = true),
        )
        val t = computeTotals(q, lines, s)
        assertEquals(620.0, t.installPart, 0.001)   // 20 + 6 hr × 100
        assertEquals(210.0, t.repairPart, 0.001)    // 10 + 2 hr × 100
        assertEquals(210.0 * 0.0875, t.salesTax, 0.001)
        assertEquals(830.0 + 210.0 * 0.0875, t.total, 0.001)
        assertEquals(800.0, t.labor, 0.001)
    }

    @Test fun install_noTax_repair_allTaxed() {
        val lines = listOf(QuoteLine(quoteId = 0, name = "x", qty = 1.0, unitCost = 100.0, repair = true))
        val inst = computeTotals(Quote(number = "Q", jobType = JobType.INSTALL, laborHours = 1.0, laborRate = 100.0, markupPct = 0.0), lines, s)
        assertEquals(0.0, inst.salesTax, 0.001)
        val rep = computeTotals(Quote(number = "Q", jobType = JobType.REPAIR, laborHours = 1.0, laborRate = 100.0, markupPct = 0.0, taxRatePct = 10.0), lines, s)
        assertEquals(20.0, rep.salesTax, 0.001)
    }
}
