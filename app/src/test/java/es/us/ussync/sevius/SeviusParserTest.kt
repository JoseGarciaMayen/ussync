package es.us.ussync.sevius

import org.junit.Assert.*
import org.junit.Test

class SeviusParserTest {
    @Test fun projectKeepsAssociatedProgramInsteadOfLatest() {
        val html = """<table><caption>Versión 3</caption><tr><td><input name="programa" value="999/3"></td></tr></table>
            <table><caption>Versión 2</caption><tr><td><input name="programa" value="999/2"></td></tr>
            <tr><th>Grupo A<input name="proyecto" value="999/2026-27/42/A"></th></tr></table>"""
        val project = SeviusParser.documents(html).first { it.kind == "proyecto" }
        assertEquals("999/2", project.program)
        assertEquals("2026-27", project.year)
        assertTrue(project.label.contains("Grupo A"))
    }
    @Test(expected = IllegalArgumentException::class) fun unknownPageIsNotAnEmptyCatalog() {
        SeviusParser.subjects("<html>Error</html>", "17")
    }
    @Test fun subjectsExcludePlaceholderAndStripCode() {
        val subjects = SeviusParser.subjects("""<select id="asignatura"><option value="-1">Elige</option><option value="999">Asignatura (999)</option></select>""", "17")
        assertEquals(listOf(SeviusSubject("999", "Asignatura", "17")), subjects)
    }
}
