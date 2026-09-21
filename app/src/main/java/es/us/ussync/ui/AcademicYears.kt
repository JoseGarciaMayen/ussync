package es.us.ussync.ui

import es.us.ussync.blackboard.EvCourse
import java.time.LocalDate

private val AcademicYearPattern = Regex("20\\d{2}[-/]?(?:20)?\\d{2}")

fun academicStart(course: EvCourse): Int? {
    val match = AcademicYearPattern.find("${course.name} ${course.courseId}") ?: return null
    return match.value.take(4).toIntOrNull()
}

fun currentAcademicStart(today: LocalDate = LocalDate.now()): Int = if (today.monthValue >= 8) today.year else today.year - 1

fun isCurrentAcademic(course: EvCourse, today: LocalDate = LocalDate.now()): Boolean =
    academicStart(course)?.let { it == currentAcademicStart(today) } ?: true
