package es.us.ussync.ui

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalDatesTest {
    @Test
    fun formatsShortDateCorrectly() {
        val iso = "2026-09-22T07:00:21.352Z"
        val formatted = localDateShort(iso)
        assertTrue(formatted.contains("22/09/2026"))
        assertTrue(formatted.length >= 16)
    }

    @Test
    fun detectsFutureDates() {
        val future = Instant.now().plusSeconds(3600).toString()
        val past = Instant.now().minusSeconds(3600).toString()
        assertTrue(isFutureDate(future))
        assertFalse(isFutureDate(past))
        assertFalse(isFutureDate("invalid"))
    }
}
