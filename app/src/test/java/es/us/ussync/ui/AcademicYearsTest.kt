package es.us.ussync.ui

import es.us.ussync.blackboard.EvCourse
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AcademicYearsTest {
    @Test fun currentAcademicYearChangesInAugust() {
        assertEquals(2025, currentAcademicStart(LocalDate.of(2026, 7, 31)))
        assertEquals(2026, currentAcademicStart(LocalDate.of(2026, 8, 1)))
    }
    @Test fun detectsSlashAndDashAcademicCodes() {
        assertEquals(2026, academicStart(EvCourse("1", "Álgebra 2026-27", "")))
        assertEquals(2025, academicStart(EvCourse("2", "Programación", "2025/26")))
    }
    @Test fun unknownYearRemainsVisible() {
        assertTrue(isCurrentAcademic(EvCourse("3", "Curso sin año", ""), LocalDate.of(2026, 9, 1)))
    }
}
