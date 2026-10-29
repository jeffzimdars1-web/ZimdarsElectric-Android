package com.zimdarselectric.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PriceBookTest {
    @Test fun parsesAllParts() {
        val b = PriceBook.parse("""{"title":"Price Book","updated":"2026-10-29","intro":"Hi",
          "rates":[{"value":"${'$'}125","label":"Service call"}],
          "steps":["Look","Quote"],
          "sections":[{"title":"Receptacles","columns":["First","Each add'l"],"items":[{"name":"GFCI","note":"25 min","prices":["${'$'}185","${'$'}90"]}]}],
          "callouts":[{"kind":"warn","title":"Careful","bullets":["Aluminum"]}],
          "examples":[{"title":"Ex","lines":[["A","${'$'}1.00"]],"total":["Total","${'$'}1.00"],"note":""}],
          "footer":"End","somethingNew":123}""")
        assertEquals("2026-10-29", b.updated)
        assertEquals("${'$'}185", b.sections[0].items[0].prices[0])
        assertEquals(2, b.sections[0].columns.size)
        assertEquals("Aluminum", b.callouts[0].bullets[0])
        assertEquals("Total" to "${'$'}1.00", b.examples[0].total)
    }

    @Test fun missingPartsAreFine() {
        val b = PriceBook.parse("""{"title":"X"}""")
        assertEquals(0, b.sections.size)
        assertEquals(null, b.examples.firstOrNull())
    }
}
