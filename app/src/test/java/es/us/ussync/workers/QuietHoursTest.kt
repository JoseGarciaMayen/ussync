package es.us.ussync.workers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class QuietHoursTest {
    @Test fun `overnight range includes both sides of midnight`() {
        assertTrue(QuietHours.isActive(LocalTime.of(23, 30), 22, 7))
        assertTrue(QuietHours.isActive(LocalTime.of(6, 59), 22, 7))
        assertFalse(QuietHours.isActive(LocalTime.of(7, 0), 22, 7))
        assertFalse(QuietHours.isActive(LocalTime.of(14, 0), 22, 7))
    }

    @Test fun `daytime range and incomplete values are handled safely`() {
        assertTrue(QuietHours.isActive(LocalTime.of(12, 0), 9, 17))
        assertFalse(QuietHours.isActive(LocalTime.of(17, 0), 9, 17))
        assertFalse(QuietHours.isActive(LocalTime.NOON, 22, null))
        assertFalse(QuietHours.isActive(LocalTime.NOON, 8, 8))
    }
}
