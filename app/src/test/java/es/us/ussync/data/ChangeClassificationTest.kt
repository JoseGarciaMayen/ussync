package es.us.ussync.data

import es.us.ussync.blackboard.EvDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeClassificationTest {

    private fun document(
        revision: String? = "r1",
        size: Long? = 10,
        filename: String = "Tema 1.pdf",
        path: List<String> = listOf("Teoría"),
    ) = EvDocument("ev:course:content:file", "Asignatura", path, filename, revision, size)

    private fun entity(
        revision: String? = "r1",
        size: Long? = 10,
        filename: String = "Tema 1.pdf",
        path: String = "Teoría",
    ) = RemoteDocumentEntity(
        key = "ev:course:content:file",
        source = "EV",
        courseId = "course",
        courseName = "Asignatura",
        relativePath = path,
        filename = filename,
        revision = revision,
        size = size,
        firstSeen = "2026-01-01T00:00:00Z",
        lastSeen = "2026-01-01T00:00:00Z",
        remoteAvailable = true,
    )

    @Test
    fun unknownDocumentIsNew() {
        assertEquals(ChangeKind.NEW, classifyEvChange(document(), null))
    }

    @Test
    fun sameMetadataIsUnchanged() {
        assertEquals(ChangeKind.UNCHANGED, classifyEvChange(document(), entity()))
    }

    @Test
    fun changedRevisionIsUpdated() {
        assertEquals(ChangeKind.UPDATED, classifyEvChange(document(revision = "r2"), entity()))
    }

    @Test
    fun changedPathIsUpdated() {
        assertEquals(ChangeKind.UPDATED, classifyEvChange(document(path = listOf("Prácticas")), entity()))
    }

    @Test
    fun localFileRecognizedIsNotPendingEvenIfRemoteIsNew() {
        assertEquals(
            ChangeKind.UNCHANGED,
            classifyEvChange(document(), null, alreadyDownloadedKeys = setOf("ev:course:content:file")),
        )
    }

    @Test
    fun forcedMissingDownloadIsNewAgain() {
        assertEquals(
            ChangeKind.NEW,
            classifyEvChange(document(), entity(), forcePendingKeys = setOf("ev:course:content:file")),
        )
    }
}
