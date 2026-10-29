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
    }

    @Test fun findsByEverydayNames() {
        val items = ReceiptItem.from(e)
        assertEquals("ID TWISTER TAN 250PK JAR", ReceiptItem.search(items, "Twister Wire Connectors").first().name)
        assertEquals("BS 1/2-IN MTL INS STPL 50", ReceiptItem.search(items, "Insulated cable staples").first().name)
        assertEquals("SIGMA 3/8-IN NM CLAMP CON", ReceiptItem.search(items, "1/2 romex box Connectors Sigma").first().name)
        assertTrue(ReceiptItem.search(items, "ceiling fan").isEmpty())
    }
}
