package es.us.ussync.updater

import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun detectsNewerVersionsCorrectly() {
        assertTrue(AppUpdater.isNewerVersion("0.1.9", "0.1.8"))
        assertTrue(AppUpdater.isNewerVersion("v0.1.9", "0.1.8"))
        assertTrue(AppUpdater.isNewerVersion("0.2.0", "0.1.8"))
        assertTrue(AppUpdater.isNewerVersion("1.0.0", "0.1.8"))
        assertTrue(AppUpdater.isNewerVersion("v0.1.8.1", "0.1.8"))

        assertFalse(AppUpdater.isNewerVersion("0.1.8", "0.1.8"))
        assertFalse(AppUpdater.isNewerVersion("v0.1.8", "0.1.8"))
        assertFalse(AppUpdater.isNewerVersion("0.1.7", "0.1.8"))
        assertFalse(AppUpdater.isNewerVersion("0.0.9", "0.1.8"))
    }
}
