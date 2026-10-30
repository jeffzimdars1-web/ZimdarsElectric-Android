package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NecTest {
    @Test fun boxFill_switchBox() {
        // Two 14/2 cables into a box with one switch, grounds, clamps: 4 wires + 1 ground + 2 device + 1 clamp = 8 × 2.0 = 16.0
        val f = Nec.boxFill(mapOf("14" to 4), grounds = 2, devices = 1, clamps = true, studs = 0)
        assertEquals(16.0, f.required, 1e-9)
    }

    @Test fun boxFill_manyGroundsAddQuarter() {
        // 6 grounds: 1 + 2 × 1/4 = 1.5 allowances of #12 (2.25) = 3.375
        val f = Nec.boxFill(mapOf("12" to 6), grounds = 6, devices = 0, clamps = false, studs = 0)
        assertEquals(6 * 2.25 + 3.375, f.required, 1e-9)
    }

    @Test fun conduitFill_emt() {
        // Annex C Table C.1: 1/2" EMT holds 9 #12 or 12 #14 THHN, 5 #10
        assertEquals(9, Nec.maxWires("EMT", "1/2", "12"))
        assertEquals(12, Nec.maxWires("EMT", "1/2", "14"))
        assertEquals(5, Nec.maxWires("EMT", "1/2", "10"))
        assertEquals("1/2", Nec.smallestConduit("EMT", mapOf("12" to 3)))
        assertFalse(Nec.conduitFill("EMT", "1/2", mapOf("12" to 13)).ok)
    }

    @Test fun ampacity_limits() {
        assertEquals(15, Nec.ampacity("14", false, Nec.WireType.NM))
        assertEquals(30, Nec.ampacity("10", false, Nec.WireType.THHN))
        assertEquals(55, Nec.ampacity("6", false, Nec.WireType.NM))
        assertEquals(65, Nec.ampacity("6", false, Nec.WireType.THHN))
    }

    @Test fun pickWire_longRunUpsizes() {
        // 20 A at 120 V, 150 ft one way: #12 9.9%, #10 6.2%, #8 3.9%, #6 2.5%
        val p = Nec.pickWire(20.0, 150.0, 120.0, false, Nec.WireType.NM, continuous = false)!!
        assertEquals("6", p.size)
        assertTrue(p.dropPct <= 3.0)
        // EV charger 32 A continuous at 240 V, 40 ft, THHN: needs 40 A → #8 (50 A at 75°C)
        assertEquals("8", Nec.pickWire(32.0, 40.0, 240.0, false, Nec.WireType.THHN, continuous = true)!!.size)
    }

    @Test fun loadCalc_typicalHouse() {
        // 2000 sq ft: 6000 + 3 × 1500 = 10500, + range 12000 + dryer 5000 + WH 4500 + DW 1200 = 33200
        // demand: 10000 + 23200 × 0.4 = 19280; + A/C 3600 → 22880 VA = 95.3 A → 100 A service
        val l = Nec.loadCalc(2000.0, 2, 1, listOf(Nec.Appliance("R", 12000.0), Nec.Appliance("D", 5000.0), Nec.Appliance("W", 4500.0), Nec.Appliance("DW", 1200.0)),
            acWatts = 3600.0, heatWatts = 0.0, heatUnits = 0)
        assertEquals(33200.0, l.general, 1e-9)
        assertEquals(22880.0, l.total, 1e-9)
        assertEquals(100, l.service)
    }
}
