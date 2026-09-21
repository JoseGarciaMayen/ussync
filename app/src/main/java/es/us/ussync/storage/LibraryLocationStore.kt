package es.us.ussync.storage

import android.content.Context

data class PersistedLibraryPermission(
    val uri: String,
    val persistedAt: Long,
    val canRead: Boolean,
    val canWrite: Boolean,
)

/**
 * Chooses only locations for which Android still exposes a durable read/write grant.
 * The database and preferences are hints; the system grant is the source of truth.
 */
fun resolveLibraryUri(
    databaseUri: String?,
    rememberedUri: String?,
    permissions: List<PersistedLibraryPermission>,
): String? {
    val writable = permissions.filter { it.canRead && it.canWrite }
    // The preference is committed in the picker callback before the asynchronous
    // Room write, so it wins when a process death left both values out of sync.
    return sequenceOf(rememberedUri, databaseUri)
        .filterNotNull()
        .firstOrNull { candidate -> writable.any { it.uri == candidate } }
        ?: writable.maxByOrNull { it.persistedAt }?.uri
}

/**
 * A synchronous preference closes the small gap between Android persisting the SAF
 * permission and Room saving the selected URI. It also lets us repair Room from the
 * permissions retained by Android after an interrupted save.
 */
class LibraryLocationStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun remember(uri: String): Boolean = preferences.edit().putString(KEY_URI, uri).commit()

    fun resolve(databaseUri: String?): String? {
        val permissions = runCatching {
            appContext.contentResolver.persistedUriPermissions.map {
                PersistedLibraryPermission(
                    uri = it.uri.toString(),
                    persistedAt = it.persistedTime,
                    canRead = it.isReadPermission,
                    canWrite = it.isWritePermission,
                )
            }
        }.getOrDefault(emptyList())
        return resolveLibraryUri(databaseUri, preferences.getString(KEY_URI, null), permissions)
    }

    private companion object {
        const val PREFERENCES = "library_location"
        const val KEY_URI = "tree_uri"
    }
}
