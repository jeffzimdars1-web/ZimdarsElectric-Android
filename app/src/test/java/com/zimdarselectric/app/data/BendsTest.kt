package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class BendsTest {
    @Test fun fractions() {
        assertEquals("12-3/8\"", Bends.inches(12.375))
        assertEquals("7\"", Bends.inches(7.01))
        assertEquals("3/16\"", Bends.inches(0.19))
    }
    @Test fun stubAndOffset() {
        assertEquals(7.0, Bends.stub(12.0, "1/2").mark, 1e-9)
        val o = Bends.offset(6.0, Bends.angles.first { it.deg == "30°" }, 24.0)
        assertEquals(12.0, o.between, 1e-9); assertEquals(1.5, o.shrink, 1e-9); assertEquals(25.5, o.firstMark, 1e-9)
    }
    @Test fun saddles() {
        val s = Bends.saddle3(2.0, 30.0)
        assertEquals(30.375, s.center, 1e-9); assertEquals(25.375, s.outer1, 1e-9); assertEquals(35.375, s.outer2, 1e-9)
        assertEquals(5.0, Bends.rolling(3.0, 4.0), 1e-9)
    }
}
