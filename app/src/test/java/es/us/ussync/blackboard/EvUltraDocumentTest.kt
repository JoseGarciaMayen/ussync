package es.us.ussync.blackboard

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class EvUltraDocumentTest {

    @Test
    fun resolvesItemParentsWithoutDuplicateOrUltraDocumentBody() {
        // Matches last parent -> do not duplicate
        assertEquals(
            listOf("Teoría", "Tema 2. Estimación puntual paramétrica"),
            resolveItemParents(listOf("Teoría", "Tema 2. Estimación puntual paramétrica"), "Tema 2. Estimación puntual paramétrica")
        )
        // ultraDocumentBody -> do not include as subfolder
        assertEquals(
            listOf("Proyecto Docente"),
            resolveItemParents(listOf("Proyecto Docente"), "ultraDocumentBody")
        )
        // Blank title -> keep parents
        assertEquals(
            listOf("Problemas"),
            resolveItemParents(listOf("Problemas"), "   ")
        )
        // Distinct meaningful title -> add as subfolder
        assertEquals(
            listOf("Problemas", "Boletín 1"),
            resolveItemParents(listOf("Problemas"), "Boletín 1")
        )
    }

    @Test
    fun resolvesFilenamesCorrectly() {
        assertEquals(
            "Tema 2_ resumen (PDF).pdf",
            resolveFilename(
                displayName = "Tema 2_ resumen (PDF) ",
                linkName = "Tema 2_ resumen (PDF) .pdf",
                innerText = "",
                mimeType = "application/pdf",
                webdavPath = "/bbcswebdav/xid-1"
            )
        )
        assertEquals(
            "TeoremadeZehna.pdf",
            resolveFilename(
                displayName = "TeoremadeZehna.pdf",
                linkName = "zenha.pdf",
                innerText = "",
                mimeType = "application/pdf",
                webdavPath = "/bbcswebdav/xid-2"
            )
        )
        assertEquals(
            "Introducción (PowerPoint).pptx",
            resolveFilename(
                displayName = "",
                linkName = "Introducción (PowerPoint)",
                innerText = "",
                mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                webdavPath = "/bbcswebdav/xid-3"
            )
        )
        assertEquals(
            "P06_naive-bayes_20251118.ipynb",
            resolveFilename(
                displayName = "",
                linkName = "",
                innerText = "P06_naive-bayes_20251118.ipynb",
                mimeType = "",
                webdavPath = "/bbcswebdav/xid-4"
            )
        )
    }

    @Test
    fun extractsEmbeddedDocumentsFromHtmlBody() {
        val html = """
            <div data-layout-row="1">
              <a data-bbid="id1" data-bbfile="{&quot;linkName&quot;:&quot;Tema 2_ resumen (PDF) .pdf&quot;,&quot;displayName&quot;:&quot;Tema 2_ resumen (PDF) &quot;,&quot;mimeType&quot;:&quot;application/pdf&quot;}"
                 href="https://ev.us.es/bbcswebdav/pid-6367235-dt-content-rid-78626111_1/xid-78626111_1?token=123"></a>
            </div>
            <div data-layout-row="2">
              <a data-bbid="id2" data-bbfile="{&quot;linkName&quot;:&quot;suficiente.pdf&quot;,&quot;displayName&quot;:&quot;suficiente.pdf&quot;,&quot;mimeType&quot;:&quot;application/pdf&quot;}"
                 href="https://ev.us.es/bbcswebdav/pid-6367235-dt-content-rid-78626112_1/xid-78626112_1?token=123"></a>
            </div>
            <div data-layout-row="3">
              <a data-bbid="id3" data-bbfile="{&quot;linkName&quot;:&quot;zenha.pdf&quot;,&quot;displayName&quot;:&quot;TeoremadeZehna.pdf&quot;,&quot;mimeType&quot;:&quot;application/pdf&quot;}"
                 href="https://ev.us.es/bbcswebdav/pid-6367235-dt-content-rid-78626115_1/xid-78626115_1?token=123"></a>
            </div>
        """.trimIndent()

        val course = EvCourse("_111409_1", "Inferencia Estadística", "202627-1710024-T-EC")
        val docs = extractEmbeddedDocuments(
            course = course,
            contentId = "_6367235_1",
            parents = listOf("Teoría", "Tema 2. Estimación puntual paramétrica"),
            revision = "2026-09-24T06:55:55.297Z",
            availableFrom = null,
            bodyHtml = html
        )

        assertEquals(3, docs.size)

        assertEquals("Tema 2_ resumen (PDF).pdf", docs[0].filename)
        assertEquals("Teoría", docs[0].path[0])
        assertEquals("Tema 2. Estimación puntual paramétrica", docs[0].path[1])
        assertTrue(docs[0].key.startsWith("ev:_111409_1:_6367235_1:webdav_"))
        val decodedPath1 = URLDecoder.decode(docs[0].key.split(":")[3].removePrefix("webdav_"), StandardCharsets.UTF_8.name())
        assertEquals("/bbcswebdav/pid-6367235-dt-content-rid-78626111_1/xid-78626111_1", decodedPath1)

        assertEquals("suficiente.pdf", docs[1].filename)
        assertEquals("TeoremadeZehna.pdf", docs[2].filename)
    }

    @Test
    fun downloadsWebdavDocumentSuccessfully() {
        var requestedUrl = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("%PDF-1.5 test content for pdf".toResponseBody())
                .build()
        }.build()

        val bbClient = BlackboardClient(client)
        val target = File.createTempFile("ussync-webdav-test", ".pdf")
        try {
            val encodedPath = java.net.URLEncoder.encode("/bbcswebdav/pid-1/xid-1", StandardCharsets.UTF_8.name())
            val doc = EvDocument(
                key = "ev:course1:content1:webdav_$encodedPath",
                courseName = "Course 1",
                path = listOf("Teoría"),
                filename = "test.pdf",
                revision = null,
                size = null
            )
            bbClient.download(EvUser("user1", "User 1", "/learn/api/public/v1"), doc, target)
            assertEquals("https://ev.us.es/bbcswebdav/pid-1/xid-1", requestedUrl)
            assertTrue(target.length() > 0)
        } finally {
            target.delete()
        }
    }
}
