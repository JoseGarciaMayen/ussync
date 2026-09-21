package es.us.ussync.sevius

import android.content.Context
import es.us.ussync.blackboard.EvDocument
import es.us.ussync.data.*
import es.us.ussync.storage.LibraryDownloader
import es.us.ussync.storage.LocalFileState
import es.us.ussync.sync.SyncLocks
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.time.Instant
import java.text.Normalizer

data class SeviusSubject(val code: String, val name: String, val center: String)
data class TeachingDocument(val kind: String, val value: String, val label: String, val year: String = "", val program: String = "")

fun normalizeSubjectName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace("\\p{M}+".toRegex(), "").lowercase()
    .replace("[^a-z0-9]+".toRegex(), " ").trim().replace("\\s+".toRegex(), " ")

fun automaticSubjectMatch(courseName: String, subjects: List<SeviusSubject>): SeviusSubject? {
    val wanted = normalizeSubjectName(courseName)
    if (wanted.isBlank()) return null
    val exact = subjects.filter { normalizeSubjectName(it.name) == wanted }
    if (exact.size == 1) return exact.single()
    val wantedWords = wanted.split(' ').filter { it.length > 2 }.toSet()
    return subjects.map { subject ->
        val words = normalizeSubjectName(subject.name).split(' ').filter { it.length > 2 }.toSet()
        subject to if (wantedWords.isEmpty()) 0.0 else wantedWords.intersect(words).size.toDouble() / wantedWords.size
    }.sortedByDescending { it.second }.let { ranked ->
        ranked.firstOrNull()?.takeIf { it.second >= 0.75 && (ranked.size == 1 || it.second > ranked[1].second) }?.first
    }
}

object SeviusParser {
    fun subjects(html: String, center: String): List<SeviusSubject> {
        val select = requireNotNull(Jsoup.parse(html).getElementById("asignatura")) { "SEVIUS no muestra las asignaturas. Vuelve a intentarlo más tarde." }
        return select.select("option[value]").filter { it.attr("value") !in listOf("", "-1") }.map {
            val code = it.attr("value")
            SeviusSubject(code, it.text().removeSuffix("($code)").trim(), center)
        }
    }
    fun documents(html: String): List<TeachingDocument> {
        val result = mutableListOf<TeachingDocument>()
        Jsoup.parse(html).select("table").forEach { table ->
            val program = table.selectFirst("input[name=programa]")?.attr("value")?.takeIf { it.isNotBlank() } ?: return@forEach
            val version = table.selectFirst("caption")?.text() ?: program.substringAfterLast('/')
            result += TeachingDocument("programa", program, "Programa · $version")
            table.select("input[name=proyecto]").forEach { input ->
                val value = input.attr("value")
                val parts = value.split('/')
                require(parts.size == 4 && Regex("\\d{4}-\\d{2}").matches(parts[1])) { "SEVIUS ha cambiado el formato de los grupos." }
                val label = input.parents().firstOrNull { it.tagName() in listOf("th", "td") }?.text() ?: "Grupo ${parts.last()}"
                result += TeachingDocument("proyecto", value, "$label · ${parts[1]} · $version", parts[1], program)
            }
        }
        return result.distinctBy { it.kind to it.value }
    }
}

class SeviusClient {
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private fun request(form: Map<String, String>) = Request.Builder().url("https://sevius4.us.es/index.php?PyP=LISTA")
        .post(FormBody.Builder().apply { form.forEach { (key, value) -> add(key, value) } }.build()).build()
    private fun page(form: Map<String, String>): String = client.newCall(request(form)).execute().use {
        check(it.isSuccessful) { "SEVIUS no está disponible (HTTP ${it.code})." }
        it.body?.string().orEmpty()
    }
    fun subjects(degree: String, centers: List<String>): List<SeviusSubject> = centers.flatMap { center ->
        SeviusParser.subjects(page(mapOf("codcentro" to center, "titulacion" to degree)), center)
    }.distinctBy { it.code }.sortedBy { it.name.lowercase() }
    fun documents(subject: SeviusSubject, degree: String): List<TeachingDocument> =
        SeviusParser.documents(page(mapOf("codcentro" to subject.center, "titulacion" to degree, "asignatura" to subject.code)))
    fun download(document: TeachingDocument, target: File) {
        client.newCall(request(mapOf(document.kind to document.value))).execute().use { response ->
            check(response.isSuccessful) { "No se pudo descargar el documento de SEVIUS." }
            requireNotNull(response.body).byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val prefix = target.inputStream().use { input -> ByteArray(5).also { buffer -> check(input.read(buffer) == 5) { "SEVIUS devolvió un archivo vacío." } } }
        check(prefix.decodeToString() == "%PDF-") { "SEVIUS no devolvió un PDF válido." }
    }
}

/** Re-read the listing so the chosen project always uses its associated program. */
suspend fun downloadTeachingSelection(context: Context, selection: SeviusSelectionEntity) {
    val catalog = AppDatabase.get(context).catalogDao()
    val tree = requireNotNull(catalog.setting("library_tree_uri")) { "Elige primero la carpeta de biblioteca en Ajustes." }
    if (catalog.setting("wifi_only") != "false") {
        val network = context.getSystemService(android.net.ConnectivityManager::class.java)
        check(network.getNetworkCapabilities(network.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) { "Conéctate a Wi-Fi para descargar." }
    }
    val course = requireNotNull(catalog.evCourses().firstOrNull { it.remoteId == selection.courseId }) { "La asignatura ya no está disponible." }
    val client = SeviusClient()
    val published = client.documents(SeviusSubject(selection.subjectCode, course.name, selection.center), catalog.setting("sevius_degree") ?: "247")
    val project = requireNotNull(published.firstOrNull { it.kind == "proyecto" && it.value == selection.projectValue }) { "Ese proyecto ya no está publicado. Revisa el año y el grupo." }
    val program = requireNotNull(published.firstOrNull { it.kind == "programa" && it.value == project.program }) { "No se encontró el programa asociado al proyecto." }
    for (item in listOf(program, project)) {
        val temp = File.createTempFile("sevius-", ".pdf", context.cacheDir)
        try {
            client.download(item, temp)
            val key = "sevius:${course.remoteId}:${item.kind}:${item.value}"
            val doc = EvDocument(key, course.folder ?: course.name, listOf("Información docente"), "${item.label}.pdf", item.value, temp.length())
            SyncLocks.publication.withLock {
                val previous = catalog.latestDownload(key)
                val file = LibraryDownloader(context).publish(tree, doc, temp, previous?.let { LocalFileState(it.targetUri, it.sha256) })
                if (previous?.sha256 != file.sha256 || previous.targetUri != file.uri) catalog.recordDownload(DownloadRecordEntity(
                    documentKey = key, remoteRevision = item.value, targetUri = file.uri, sha256 = file.sha256,
                    bytes = temp.length(), result = "DOWNLOADED", createdAt = Instant.now().toString()))
            }
        } finally { temp.delete() }
    }
}
