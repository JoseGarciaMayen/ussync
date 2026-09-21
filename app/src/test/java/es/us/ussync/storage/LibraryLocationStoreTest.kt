package es.us.ussync.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryLocationStoreTest {
    private fun permission(
        uri: String,
        persistedAt: Long,
        read: Boolean = true,
        write: Boolean = true,
    ) = PersistedLibraryPermission(uri, persistedAt, read, write)

    @Test fun synchronousSelectionWinsIfRoomStillContainsThePreviousFolder() {
        assertEquals(
            "content://library/new",
            resolveLibraryUri(
                databaseUri = "content://library/old",
                rememberedUri = "content://library/new",
                permissions = listOf(
                    permission("content://library/old", 1),
                    permission("content://library/new", 2),
                ),
            ),
        )
    }

    @Test fun repairsMissingDatabaseValueFromSynchronousPreference() {
        assertEquals(
            "content://library/chosen",
            resolveLibraryUri(
                databaseUri = null,
                rememberedUri = "content://library/chosen",
                permissions = listOf(permission("content://library/chosen", 10)),
            ),
        )
    }

    @Test fun recoversMostRecentAndroidGrantWhenBothLocalWritesWereInterrupted() {
        assertEquals(
            "content://library/latest",
            resolveLibraryUri(
                databaseUri = null,
                rememberedUri = null,
                permissions = listOf(
                    permission("content://library/first", 10),
                    permission("content://library/latest", 20),
                ),
            ),
        )
    }

    @Test fun rejectsRevokedOrReadOnlyLocations() {
        assertNull(
            resolveLibraryUri(
                databaseUri = "content://library/revoked",
                rememberedUri = "content://library/read-only",
                permissions = listOf(permission("content://library/read-only", 20, write = false)),
            ),
        )
    }
}
