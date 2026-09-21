package es.us.ussync.sevius

import org.junit.Assert.*
import org.junit.Test

class AutomaticSubjectMatchTest {
    private val subjects = listOf(
        SeviusSubject("1", "Álgebra Lineal", "17"),
        SeviusSubject("2", "Programación", "3"),
    )

    @Test fun matchIgnoresAccentsAndCase() {
        assertEquals(subjects[0], automaticSubjectMatch("algebra lineal", subjects))
    }

    @Test fun matchNeedsAUniqueStrongCandidate() {
        assertNull(automaticSubjectMatch("Matemáticas", subjects))
        assertNull(automaticSubjectMatch("Programación y Redes", subjects))
    }
}
