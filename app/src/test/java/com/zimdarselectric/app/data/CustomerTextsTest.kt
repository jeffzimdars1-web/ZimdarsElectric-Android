package com.zimdarselectric.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CustomerTextsTest {
    private val start = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 31, 10, 0, 0) }.timeInMillis
    private val s = Settings(ownerName = "Jeff")
    private val c = Customer(id = 1, name = "Pat Sample", phone = "315-555-0199")

    @Test fun eveningBeforeWithTime() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start, site = "12 Main St"), c, s, nightBefore = true)
        assertTrue(m, m.startsWith("Hi Pat, this is Jeff with Zimdars Electric LLC."))
        assertTrue(m, m.contains("at 12 Main St tomorrow (Sat, Oct 31) at 10:00 AM, give or take 15 minutes"))
    }

    @Test fun hourBefore() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start), c, s, nightBefore = false)
        assertTrue(m, m.contains("around 10:00 AM, give or take 15 minutes"))
    }

    @Test fun noSetTime() {
        val m = CustomerTexts.message(Quote(number = "Q-1", startAt = start, hasTime = false), c, s, nightBefore = true)
        assertTrue(m, m.contains("I'll text you when I'm on my way"))
    }
}
