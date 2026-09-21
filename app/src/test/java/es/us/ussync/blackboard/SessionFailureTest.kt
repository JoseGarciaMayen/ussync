package es.us.ussync.blackboard

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionFailureTest {
    private fun client(code: Int) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("Test").body("".toResponseBody()).build()
    }.build()

    @Test fun unauthorizedProfileRequiresReconnection() {
        assertEquals(ProfileResult.Expired, EvProfileClient(client(401)).verify())
    }

    @Test fun unavailableServerIsNotAnExpiredSession() {
        assertTrue(EvProfileClient(client(503)).verify() is ProfileResult.Failed)
    }

    @Test fun incompatibleEndpointIsNotAnExpiredSession() {
        assertTrue(EvProfileClient(client(404)).verify() is ProfileResult.Failed)
    }

    @Test fun sessionCanExpireDuringDownload() {
        val target = File.createTempFile("ussync-session-test", ".part")
        try {
            try {
                BlackboardClient(client(401)).download(EvUser("user", "Name", "/learn/api/public/v1"),
                    EvDocument("ev:course:content:file", "Course", emptyList(), "test.pdf", null, null), target)
                fail("Expected expired-session error")
            } catch (_: EvSessionExpiredException) { /* The worker can distinguish this from a network failure. */ }
        } finally { target.delete() }
    }
}
