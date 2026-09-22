package es.us.ussync.data

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    private val document = InboxDocument(1, "ev:course:content:file", "v1", "NEW", "PENDING", "Álgebra", "Tema 1/Apuntes", "Resumen.PDF", 2048)
    private fun rule(path: String? = null) = RuleEntity(priority = 1, source = "EV", courseId = "course", pathPrefix = path, action = "AUTO_DOWNLOAD", createdAt = "2026-01-01")

    @Test fun folderIncludesDescendantsButNotSimilarNames() {
        assertTrue(rule("Tema 1").matches(document))
        assertFalse(rule("Tema 1").matches(document.copy(relativePath = "Tema 10/Apuntes")))
        assertTrue(rule("Tema 1/Apuntes").matches(document))
    }

    @Test fun scopeDoesNotLeakToOtherCoursesOrSources() {
        assertFalse(rule().matches(document.copy(documentKey = "ev:other:content:file")))
        assertFalse(rule().matches(document.copy(documentKey = "sevius:course:content:file")))
        assertFalse(rule().copy(enabled = false).matches(document))
    }

    @Test fun unknownSizeDoesNotPassSizeLimits() {
        assertTrue(rule().copy(extension = "pdf", maxSize = 4096).matches(document))
        assertFalse(rule().copy(maxSize = 4096).matches(document.copy(size = null)))
        assertFalse(rule().copy(minSize = 4096).matches(document))
    }

    @Test fun rootFolderMatchesAllFoldersWithinCourse() {
        assertTrue(rule("").matches(document))
        assertTrue(rule("").matches(document.copy(relativePath = "")))
    }

    @Test fun blockedExtensionsAreNormalizedAndMatchCaseInsensitively() {
        val blocked = parseExtensionList(" MP4 ,.Mp3;wav ")
        assertEquals(setOf("mp4", "mp3", "wav"), blocked)
        assertEquals("mp3,mp4,wav", blocked.asExtensionList())
        assertTrue(isBlockedExtension("Clase.MP4", blocked))
        assertFalse(isBlockedExtension("Clase.pdf", blocked))
        assertFalse(isBlockedExtension("sin-extension", blocked))
    }

    @Test fun normalizeForMatchingHandlesAccentsPunctuationAndCase() {
        assertEquals("fisicageneral", normalizeForMatching("Física General"))
        assertEquals("1050012algebrai", normalizeForMatching("1050012 - Álgebra I"))
        assertEquals("tema1ejercicios2024pdf", normalizeForMatching("Tema 1: Ejercicios [2024].pdf"))
    }
}
