package es.us.ussync.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import es.us.ussync.blackboard.EvDocument
import es.us.ussync.storage.LibraryDownloader
import java.text.Normalizer
import java.time.Instant

/** Clears stale download markers when the user removed the local file. */
suspend fun CatalogDao.reconcileMissingDownloads(context: Context): Set<String> {
    val missing = allDownloads()
        .distinctBy { it.documentKey }
        .filter { record ->
            val target = runCatching {
                DocumentFile.fromSingleUri(context, Uri.parse(record.targetUri))
            }.getOrNull()
            target == null || !target.exists() || target.isDirectory
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
        } else if (child.isFile && child.length() > 0) {
            val next = if (currentPath.isEmpty()) childName else "$currentPath/$childName"
            result += ScannedLocalFile(child, next, childName, normalizeForMatching(childName))
        }
    }
    return result
}

/**
 * Reconciles remote documents against existing files in the local library.
 * If the app was reinstalled, files were downloaded outside the app, or folder names
 * differ slightly, this links them back to avoid re-downloading and marks them as EV.
 *
 * Returns the set of document keys that were matched and recognized locally.
 */
suspend fun CatalogDao.reconcileExistingLibraryFiles(
    context: Context,
    treeUri: String?,
    documents: List<EvDocument>,
    courseFolders: Map<String, String>,
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

    // Group documents by courseId
    val docsByCourse = documents.groupBy { it.key.split(":").getOrElse(1) { "" } }

    for ((courseId, courseDocs) in docsByCourse) {
        val customFolder = courseFolders[courseId]?.trim().orEmpty()
        val courseName = courseDocs.firstOrNull()?.courseName?.trim().orEmpty()

        val normCustom = if (customFolder.isNotBlank()) normalizeForMatching(customFolder) else ""
        val normCourse = if (courseName.isNotBlank()) normalizeForMatching(courseName) else ""
        val safeCustom = if (customFolder.isNotBlank()) LibraryDownloader.safeName(customFolder) else ""
        val safeCourse = if (courseName.isNotBlank()) LibraryDownloader.safeName(courseName) else ""

        // Find matching course folder in root
        val courseDir = rootDirs.firstOrNull { dir ->
            val dirName = dir.name ?: return@firstOrNull false
            val normDir = normalizeForMatching(dirName)

            // Direct name matches
            dirName.equals(customFolder, ignoreCase = true) ||
            dirName.equals(safeCustom, ignoreCase = true) ||
            dirName.equals(courseName, ignoreCase = true) ||
            dirName.equals(safeCourse, ignoreCase = true) ||
            // Normalized name matches (handles accents, punctuation)
            (normCustom.isNotBlank() && normDir == normCustom) ||
            (normCourse.isNotBlank() && normDir == normCourse) ||
            // Substring containment (e.g. folder "Fisica" vs course "1050012 - FISICA GENERAL")
            (normCustom.isNotBlank() && normCustom.length >= 3 && (normDir.contains(normCustom) || normCustom.contains(normDir))) ||
            (normCourse.isNotBlank() && normDir.length >= 4 && normCourse.contains(normDir))
        } ?: continue

        val localFiles = scanDirectoryFiles(courseDir)
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
            val hash = runCatching { downloader.digest(existingFile) }.getOrNull() ?: ""
            val existingDownload = latestDownload(doc.key)

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
        resolveInboxByDocumentKeys(recognized, "DOWNLOADED", now)
    }

    return recognized
}
