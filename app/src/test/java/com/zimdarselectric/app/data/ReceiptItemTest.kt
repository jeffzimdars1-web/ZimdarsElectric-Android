package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptItemTest {
    private val e = Expense(id = 1, date = 1L, vendor = "Lowe's Utica", amount = 95.63, category = "x", items = """[
      {"name":"SIGMA 3/8-IN NM CLAMP CON","sku":"44996","model":"C-500","qty":1,"each":35.98,"paidEach":39.13},
      {"name":"ID TWISTER TAN 250PK JAR","sku":"663286","model":"30-1341J","qty":1,"each":34.98,"paidEach":38.04},
      {"name":"BS 1/2-IN MTL INS STPL 50","sku":"173273","model":"SN 40 IB 500","qty":1,"each":16.98,"paidEach":18.47}]""")

    @Test fun parsesLines() {
        val items = ReceiptItem.from(e)
        assertEquals(3, items.size)
        assertEquals(38.04, items[1].paidEach, 0.001)
        assertEquals(250, items[1].packCount)
        assertEquals(50, ReceiptItem("14/2 CU NM-B W/G 50-FT", 1.0, 52.0, 56.55, "", "", "", 0, 0).feet)
        assertEquals(null, items[0].feet)
    }

    @Test fun findsByEverydayNames() {
        val items = ReceiptItem.from(e)
        assertEquals("ID TWISTER TAN 250PK JAR", ReceiptItem.search(items, "Twister Wire Connectors").first().name)
        assertEquals("BS 1/2-IN MTL INS STPL 50", ReceiptItem.search(items, "Insulated cable staples").first().name)
        assertEquals("SIGMA 3/8-IN NM CLAMP CON", ReceiptItem.search(items, "1/2 romex box Connectors Sigma").first().name)
        assertTrue(ReceiptItem.search(items, "ceiling fan").isEmpty())
    }
}

class ReceiptStockTest {
    @org.junit.Test fun marksAndMatches() {
        val json = """[{"name":"ID TWISTER TAN 250PK JAR","sku":"663286","qty":2,"each":34.98,"paidEach":38.04}]"""
        val marked = ReceiptItem.mark(json, 0, "7")
        val e = Expense(id = 3, date = 1L, vendor = "Lowe's Utica", amount = 1.0, category = "Materials + supplies (line 22)", items = marked)
        val r = ReceiptItem.from(e).single()
        org.junit.Assert.assertEquals("7", r.stock)
        org.junit.Assert.assertTrue(r.isMaterial)
        val wireNuts = Material(id = 7, name = "Twister Wire Connectors", packSize = 250.0).withAliases(r.keys)
        org.junit.Assert.assertTrue(wireNuts.matchesReceipt(r.keys))
        org.junit.Assert.assertEquals(500.0, r.piecesFor(wireNuts), 0.001)
        org.junit.Assert.assertEquals(wireNuts, ReceiptItem.suggest(r, listOf(Material(id = 1, name = "Insulated staples"), wireNuts)).first())
    }
}
