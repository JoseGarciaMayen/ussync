package es.us.ussync.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

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
