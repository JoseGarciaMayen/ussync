package es.us.ussync.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import es.us.ussync.blackboard.EvDocument
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class LocalFileState(val uri: String, val sha256: String)

class LibraryDownloader(
    context: Context,
) {
    fun ensureCourseFolder(treeUri: String, courseName: String) {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(treeUri)))
        directory(root, safeName(courseName))
    }
    private val context = context.applicationContext
    private val resolver: ContentResolver = this.context.contentResolver
    fun publish(treeUri: String, document: EvDocument, temporary: File, previous: LocalFileState?): LocalFileState {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(treeUri)))
        val course = directory(root, safeName(document.courseName))
        var parent = course
        document.path.forEach { parent = directory(parent, safeName(it)) }
        val name = safeName(document.filename)
        val existing = parent.findFile(name)
        val receivedHash = digest(temporary)
        val localHash = existing?.let { digest(it) }
        val modifiedByUser = existing != null && (previous == null || localHash != previous.sha256)
        if (existing != null && modifiedByUser) {
            val versions = directory(course, "Versiones")
            val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"))
            return copyToDocument(temporary, versions, versionName(name, suffix), receivedHash)
        }
        if (existing != null && localHash == receivedHash) return LocalFileState(existing.uri.toString(), receivedHash)
        if (existing != null) {
            val versions = directory(course, "Versiones")
            val suffix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss"))
            copyDocument(existing, versions, versionName(name, suffix))
            check(existing.delete()) { "No se pudo sustituir el archivo. Tu copia se ha conservado." }
        }
        return copyToDocument(temporary, parent, name, receivedHash)
    }

    fun findFile(treeUri: String, courseName: String, path: List<String>, filename: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        if (!root.exists() || !root.canRead()) return null
        var current: DocumentFile = root.findFile(safeName(courseName)) ?: return null
        for (segment in path) {
            current = current.findFile(safeName(segment)) ?: return null
        }
        val file = current.findFile(safeName(filename)) ?: return null
        return if (file.exists() && file.isFile) file else null
    }

    private fun directory(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name) ?: requireNotNull(parent.createDirectory(name))

    private fun copyToDocument(source: File, parent: DocumentFile, name: String, hash: String): LocalFileState {
        val target = requireNotNull(parent.createFile("application/octet-stream", name))
        resolver.openOutputStream(target.uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out, 128 * 1024) } }
        check(digest(target) == hash) { "No se pudo verificar la copia publicada." }
        return LocalFileState(target.uri.toString(), hash)
    }

    private fun copyDocument(source: DocumentFile, parent: DocumentFile, name: String) {
        val target = requireNotNull(parent.createFile("application/octet-stream", name))
        resolver.openOutputStream(target.uri, "w")!!.use { out ->
            resolver.openInputStream(source.uri)!!.use { it.copyTo(out, 128 * 1024) }
        }
        check(digest(source) == digest(target)) { "No se pudo verificar la copia de seguridad. Se conserva el original." }
    }

    private fun versionName(name: String, suffix: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} [$suffix].${name.substring(dot + 1)}" else "$name [$suffix]"
    }

    fun digest(file: DocumentFile): String = resolver.openInputStream(file.uri)!!.use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
        hash.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "-").take(120).ifBlank { "Sin título" }

        fun digest(file: File): String = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
