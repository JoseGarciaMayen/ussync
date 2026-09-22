package es.us.ussync.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import es.us.ussync.blackboard.EvDocument
import es.us.ussync.storage.LibraryDownloader
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

/**
 * Reconciles remote documents against existing files in the local library.
 * If the app was reinstalled or the local database cleared, but files were previously
 * downloaded to the library folder, this links them back to avoid re-downloading everything.
 *
 * Returns the set of document keys that were matched and recognized locally.
 */
suspend fun CatalogDao.reconcileExistingLibraryFiles(
    context: Context,
    treeUri: String?,
    documents: List<EvDocument>,
    courseFolders: Map<String, String>,
): Set<String> {
    if (treeUri.isNullOrBlank()) return emptySet()
    val downloader = LibraryDownloader(context)
    val recognized = mutableSetOf<String>()
    val now = Instant.now().toString()

    for (doc in documents) {
        val courseId = doc.key.split(":").getOrElse(1) { "" }
        val folderName = courseFolders[courseId] ?: doc.courseName
        val existingFile = downloader.findFile(treeUri, folderName, doc.path, doc.filename) ?: continue

        // If remote size is known, ensure local file size matches to avoid linking partial downloads
        val fileSize = existingFile.length()
        if (doc.size != null && doc.size > 0 && fileSize != doc.size) continue

        val hash = runCatching { downloader.digest(existingFile) }.getOrNull() ?: ""
        val existingDownload = latestDownload(doc.key)
        if (existingDownload == null) {
            recordDownload(DownloadRecordEntity(
                documentKey = doc.key,
                remoteRevision = doc.revision,
                targetUri = existingFile.uri.toString(),
                sha256 = hash,
                bytes = fileSize,
                result = "DOWNLOADED",
                createdAt = now,
            ))
        }
        markDownloaded(doc.key, hash, doc.revision)
        recognized.add(doc.key)
    }

    return recognized
}
