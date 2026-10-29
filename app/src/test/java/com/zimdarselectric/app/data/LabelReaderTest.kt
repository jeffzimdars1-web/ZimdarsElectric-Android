package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelReaderTest {
    @Test fun romexRoll() {
        val g = LabelReader.read("SOUTHWIRE\nROMEX SIMPULL\n12-2 W/G\nNM-B 600V\n250 FT")
        assertEquals("12/2 NM-B cable w/ground", g.matches.first().name)
        assertEquals(250, g.rollFeet)
        assertEquals("ft", g.unit)
        assertEquals("Southwire", g.brand)
        assertNull(g.model)
    }

    @Test fun gfci() {
        val g = LabelReader.read("LEVITON\n20 Amp GFCI Receptacle\nTamper-Resistant\n125 Volt\nGFNT2-W")
        assertTrue(g.matches.map { it.name }.toString(), g.matches.any { it.name == "20A GFCI receptacle" })
        assertTrue(g.matches.none { it.name.startsWith("15A") })
        assertEquals("Leviton", g.brand)
        assertEquals("GFNT2-W", g.model)
    }

    @Test fun squareBox() {
        val g = LabelReader.read("CARLON 4\" SQUARE BOX 2-1/8\" DEEP")
        assertEquals("4 in square box, 2-1/8 in deep", g.matches.first().name)
    }

    @Test fun breaker() {
        val g = LabelReader.read("EATON BR 20 AMP SINGLE POLE BREAKER BR120")
        val top = g.matches.first()
        assertEquals("20A 1-pole breaker", top.name)
        assertEquals("Eaton BR", top.spec)
        assertEquals("BR120", g.model)
    }

    @Test fun thhnSpoolWithComma() {
        val g = LabelReader.read("1,000 FT 12 AWG THHN BLACK")
        assertEquals(1000, g.rollFeet)
        assertEquals("12 AWG THHN copper, black", g.matches.first().name)
    }

    @Test fun packPrice() {
        val g = LabelReader.read("IDEAL WING-NUT YELLOW 100 PACK \$12.97")
        assertEquals(100, g.packCount)
        assertEquals(12.97, g.price!!, 0.001)
        assertEquals(0.13, g.unitCost!!, 0.001)
        assertEquals("Ideal", g.brand)
    }

    @Test fun conduitSizeNotMangled() {
        val g = LabelReader.read("1-1/2\" EMT SET SCREW CONNECTOR")
        assertEquals("1-1/2 in EMT set-screw connector", g.matches.first().name)
    }

    @Test fun nothingReadable() {
        val g = LabelReader.read("")
        assertTrue(g.matches.isEmpty())
        assertTrue(!g.found)
    }
}

class BoxTextTest {
    @org.junit.Test fun boxes() {
        org.junit.Assert.assertEquals("2 boxes + 37 loose", boxText(237.0, 100.0))
        org.junit.Assert.assertEquals("1 box", boxText(250.0, 250.0))
        org.junit.Assert.assertEquals("40 loose", boxText(40.0, 100.0))
        org.junit.Assert.assertEquals("", boxText(5.0, 0.0))
    }
}
