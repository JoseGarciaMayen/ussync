package es.us.ussync.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import es.us.ussync.blackboard.EvDocument
import es.us.ussync.storage.LibraryDownloader
import java.text.Normalizer
import java.time.Instant

/**
 * Removes year prefixes, transversal groups, codes, and extra tags from subject names.
 * e.g. "202627-Inferencia Estadística-TRANSVERSAL (Grupos 986392, 986393, 986394)" -> "Inferencia Estadística"
 */
fun cleanCourseName(courseName: String): String {
    var cleaned = courseName.replace(Regex("""^\s*(\d{6}|\d{4}-\d{2}|\(\d{4}-\d{2}\))\s*[-–—:]*\s*"""), "")
    cleaned = cleaned.replace(Regex("""\s*[-–—]\s*TRANSVERSAL.*$""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""\s*\(Grupos?.*?\)$""", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replace(Regex("""^\s*\d{6,8}\s*[-–—:]*\s*"""), "")
    return cleaned.trim()
}

val SPANISH_STOPWORDS = setOf(
    "de", "del", "la", "las", "el", "los", "y", "e", "en", "para", "por",
    "con", "a", "o", "u", "una", "uno", "unos", "unas"
)

/**
 * Generates possible acronyms/initials for a course name.
 * e.g. "Inferencia Estadística" -> {"IE"}
 * "Geometría Local de Curvas y Superficies" -> {"GL", "GLC", "GLCS"}
 * "Ampliación de Ecuaciones Diferenciales" -> {"AED", "AE"}
 * "Funciones de Una Variable Compleja" -> {"FVC", "FV", "FUVC"}
 * "Programación Declarativa" -> {"PD"}
 * "Tecnologías Avanzadas de la Información" -> {"TAI", "TA"}
 */
fun subjectInitials(courseName: String): Set<String> {
    val cleaned = cleanCourseName(courseName)
    val words = cleaned.split(Regex("[^\\p{L}0-9]+")).filter { it.isNotBlank() }
    val significantWords = words.filter { it.lowercase() !in SPANISH_STOPWORDS }

    val results = mutableSetOf<String>()
    if (significantWords.isNotEmpty()) {
        results += significantWords.map { it.first() }.joinToString("").uppercase()
        if (significantWords.size >= 2) {
            results += significantWords.take(2).map { it.first() }.joinToString("").uppercase()
        }
        if (significantWords.size >= 3) {
            results += significantWords.take(3).map { it.first() }.joinToString("").uppercase()
        }
    }
    if (words.size > significantWords.size) {
        results += words.map { it.first() }.joinToString("").uppercase()
    }
    return results
}

/**
 * Clears stale download markers when the user removed the local file.
 * Safe against tree URI grant issues: never clears if tree is inaccessible.
 */
suspend fun CatalogDao.reconcileMissingDownloads(context: Context, treeUri: String? = null): Set<String> {
    if (treeUri.isNullOrBlank()) return emptySet()
    val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
    if (root == null || !root.exists() || !root.canRead()) {
        return emptySet()
    }
    val missing = allDownloads()
        .distinctBy { it.documentKey }
        .filter { record ->
            val uri = runCatching { Uri.parse(record.targetUri) }.getOrNull() ?: return@filter true
            val exists = runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }.getOrElse {
                runCatching {
                    DocumentFile.fromSingleUri(context, uri)?.exists() == true
                }.getOrDefault(false)
            }
            !exists
        }
        .map { it.documentKey }
        .toSet()
    missing.forEach { key -> clearDownloadState(key) }
    return missing
}

internal fun normalizeForMatching(text: String): String {
    val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
    return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")
}

private data class ScannedLocalFile(
    val file: DocumentFile,
    val relativePath: String,
    val name: String,
    val normName: String,
)

private fun scanDirectoryFiles(dir: DocumentFile, currentPath: String = ""): List<ScannedLocalFile> {
    val result = mutableListOf<ScannedLocalFile>()
    val children = runCatching { dir.listFiles() }.getOrNull() ?: return emptyList()
    for (child in children) {
        val childName = child.name ?: continue
        if (child.isDirectory) {
            if (!childName.startsWith(".") && childName != "Versiones") {
                val next = if (currentPath.isEmpty()) childName else "$currentPath/$childName"
                result += scanDirectoryFiles(child, next)
            }
        } else if (!child.isDirectory) {
            val next = if (currentPath.isEmpty()) childName else "$currentPath/$childName"
            result += ScannedLocalFile(child, next, childName, normalizeForMatching(childName))
        }
    }
    return result
}

/**
 * Reconciles remote documents against existing files in the local library.
 * If the app was reinstalled/updated, files were downloaded outside the app (e.g. via Syncthing),
 * or folder names are acronyms (AED, FVC, GL, IE, PD, TAI), this links them back to avoid
 * re-downloading and marks them as EV.
 *
 * It also auto-persists detected course folders in the database so the mapping is never lost again.
 *
 * Returns the set of document keys that were matched and recognized locally.
 */
suspend fun CatalogDao.reconcileExistingLibraryFiles(
    context: Context,
    treeUri: String?,
    documents: List<EvDocument>,
    courseFolders: Map<String, String>,
    onCourseFolderDiscovered: ((courseId: String, folderName: String) -> Unit)? = null,
): Set<String> {
    if (treeUri.isNullOrBlank() || documents.isEmpty()) return emptySet()
    val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull() ?: return emptySet()
    if (!root.exists() || !root.canRead()) return emptySet()

    val rootDirs = runCatching { root.listFiles() }.getOrNull()
        ?.filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
        .orEmpty()
    if (rootDirs.isEmpty()) return emptySet()

    val downloader = LibraryDownloader(context)
    val recognized = mutableSetOf<String>()
    val now = Instant.now().toString()

    // Pre-scan all directories once, keyed by URI string
    val dirFilesMap = rootDirs.associate { it.uri.toString() to scanDirectoryFiles(it) }

    // Group documents by courseId
    val docsByCourse = documents.groupBy { it.key.split(":").getOrElse(1) { "" } }
    val claimedDirs = mutableSetOf<String>()

    for ((courseId, courseDocs) in docsByCourse) {
        val customFolder = courseFolders[courseId]?.trim().orEmpty()
        val courseName = courseDocs.firstOrNull()?.courseName?.trim().orEmpty()
        val cleanCourse = cleanCourseName(courseName)
        val initials = subjectInitials(courseName)

        val normCustom = if (customFolder.isNotBlank()) normalizeForMatching(customFolder) else ""
        val normCourse = if (courseName.isNotBlank()) normalizeForMatching(courseName) else ""
        val normCleanCourse = if (cleanCourse.isNotBlank()) normalizeForMatching(cleanCourse) else ""
        val safeCustom = if (customFolder.isNotBlank()) LibraryDownloader.safeName(customFolder) else ""
        val safeCourse = if (courseName.isNotBlank()) LibraryDownloader.safeName(courseName) else ""

        // 1. Find matching course folder in root
        var courseDir = rootDirs.firstOrNull { dir ->
            val dirName = dir.name ?: return@firstOrNull false
            if (dir.uri.toString() in claimedDirs) return@firstOrNull false
            val normDir = normalizeForMatching(dirName)

            // Direct name matches
            dirName.equals(customFolder, ignoreCase = true) ||
            dirName.equals(safeCustom, ignoreCase = true) ||
            dirName.equals(courseName, ignoreCase = true) ||
            dirName.equals(cleanCourse, ignoreCase = true) ||
            dirName.equals(safeCourse, ignoreCase = true) ||
            // Normalized name matches (handles accents, punctuation)
            (normCustom.isNotBlank() && normDir == normCustom) ||
            (normCleanCourse.isNotBlank() && normDir == normCleanCourse) ||
            (normCourse.isNotBlank() && normDir == normCourse) ||
            // Acronym / initials match (e.g. "IE", "GL", "AED", "FVC", "PD", "TAI")
            dirName.uppercase() in initials ||
            normDir.uppercase() in initials ||
            // Substring containment (e.g. folder "Fisica" vs course "1050012 - FISICA GENERAL")
            (normCustom.isNotBlank() && normCustom.length >= 3 && (normDir.contains(normCustom) || (normDir.length >= 3 && normCustom.contains(normDir)))) ||
            (normCleanCourse.isNotBlank() && normDir.length >= 4 && (normDir.contains(normCleanCourse) || normCleanCourse.contains(normDir)))
        }

        // 2. Content-based matching fallback:
        // If not matched by name/acronym, check which folder in rootDirs contains the files of this course!
        if (courseDir == null) {
            val candidateScores = rootDirs.filter { it.uri.toString() !in claimedDirs }.map { dir ->
                val files = dirFilesMap[dir.uri.toString()].orEmpty()
                val score = courseDocs.count { doc ->
                    val normDoc = normalizeForMatching(doc.filename)
                    val normSafeDoc = normalizeForMatching(LibraryDownloader.safeName(doc.filename))
                    files.any { it.normName == normDoc || it.normName == normSafeDoc || it.name.equals(doc.filename, ignoreCase = true) }
                }
                dir to score
            }.filter { it.second > 0 }.sortedByDescending { it.second }

            courseDir = candidateScores.firstOrNull()?.first
        }

        if (courseDir == null) continue
        claimedDirs += courseDir.uri.toString()

        val dirName = courseDir.name.orEmpty()
        // If the course folder was not persisted yet or differed, persist it now!
        if (customFolder.isBlank() || customFolder != dirName) {
            updateCourseFolder(courseId, dirName)
            onCourseFolderDiscovered?.invoke(courseId, dirName)
        }

        val localFiles = dirFilesMap[courseDir.uri.toString()].orEmpty()
        if (localFiles.isEmpty()) continue

        // Track local files already claimed to avoid duplicate matches
        val claimedUris = mutableSetOf<String>()

        for (doc in courseDocs) {
            val expectedRelPath = (doc.path + doc.filename).joinToString("/")
            val normFilename = normalizeForMatching(doc.filename)
            val normSafeFilename = normalizeForMatching(LibraryDownloader.safeName(doc.filename))
            val baseNameNorm = normalizeForMatching(doc.filename.substringBeforeLast('.'))
            val extension = doc.filename.substringAfterLast('.', "")

            // 1. Exact relative path match
            var matched = localFiles.firstOrNull {
                it.file.uri.toString() !in claimedUris &&
                it.relativePath.equals(expectedRelPath, ignoreCase = true)
            }

            // 2. Exact filename match (case-insensitive) anywhere under course
            if (matched == null) {
                matched = localFiles.firstOrNull {
                    it.file.uri.toString() !in claimedUris &&
                    (it.name.equals(doc.filename, ignoreCase = true) ||
                     it.name.equals(LibraryDownloader.safeName(doc.filename), ignoreCase = true))
                }
            }

            // 3. Normalized filename match (accents / punctuation removed)
            if (matched == null) {
                matched = localFiles.firstOrNull {
                    it.file.uri.toString() !in claimedUris &&
                    (it.normName == normFilename || it.normName == normSafeFilename)
                }
            }

            // 4. Same extension & matching normalized base name
            if (matched == null && extension.isNotBlank() && baseNameNorm.length >= 4) {
                matched = localFiles.firstOrNull {
                    it.file.uri.toString() !in claimedUris &&
                    it.name.substringAfterLast('.', "").equals(extension, ignoreCase = true) &&
                    (normalizeForMatching(it.name.substringBeforeLast('.')) == baseNameNorm ||
                     normalizeForMatching(it.name.substringBeforeLast('.')).contains(baseNameNorm) ||
                     baseNameNorm.contains(normalizeForMatching(it.name.substringBeforeLast('.'))))
                }
            }

            val existingFile = matched?.file ?: continue
            val targetUriString = existingFile.uri.toString()
            claimedUris += targetUriString

            val fileSize = existingFile.length()
            val existingDownload = latestDownload(doc.key)
            val hash = existingDownload?.sha256?.takeIf { it.isNotBlank() } ?: ""

            if (existingDownload == null || existingDownload.targetUri != targetUriString) {
                recordDownload(DownloadRecordEntity(
                    documentKey = doc.key,
                    remoteRevision = doc.revision,
                    targetUri = targetUriString,
                    sha256 = hash,
                    bytes = fileSize,
                    result = "DOWNLOADED",
                    createdAt = now,
                ))
            }
            markDownloaded(doc.key, hash, doc.revision)
            recognized.add(doc.key)
        }
    }

    if (recognized.isNotEmpty()) {
        recognized.chunked(500).forEach { chunk ->
            resolveInboxByDocumentKeys(chunk, "DOWNLOADED", now)
        }
    }

    return recognized
}
