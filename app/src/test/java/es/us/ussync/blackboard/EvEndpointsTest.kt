package es.us.ussync.blackboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvEndpointsTest {
    @Test
    fun acceptsOnlyTheHttpsEvOrigin() {
        assertTrue(EvEndpoints.isTrusted("https://ev.us.es/learn/api/public/v1/users/me"))
        assertFalse(EvEndpoints.isTrusted("http://ev.us.es/learn/api/public/v1/users/me"))
        assertFalse(EvEndpoints.isTrusted("https://evil.invalid/learn/api/public/v1/users/me"))
        assertFalse(EvEndpoints.isTrusted("https://ev.us.es.evil.invalid/"))
    }
}
