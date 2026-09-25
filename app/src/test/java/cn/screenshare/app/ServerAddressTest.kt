package cn.screenshare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun importsRoomAndServerWithoutCredentials() {
        assertEquals("https://example.com:8443" to "12345678", ServerAddress.invitation("https://example.com:8443/#room=12345678", false))
        assertEquals("https://example.com" to "12345678", ServerAddress.invitation("https://example.com/screenshare/#room=12345678", false))
        listOf("https://user:pass@example.com/#room=12345678", "https://example.com/#room=12", "https://example.com/?token=secret#room=12345678", "https://example.com/path#room=12345678", "http://example.com/#room=12345678", "https://example.com/#room=12345678&token=secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.invitation(it, false) }
        }
    }
    @Test fun trimsAddress() { assertEquals("https://example.com", ServerAddress.normalize(" https://example.com/ ", false)) }
    @Test fun permitsLocalHttpOnlyForDebug() {
        assertEquals("http://192.168.1.2:8080", ServerAddress.normalize("http://192.168.1.2:8080", true))
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize("http://192.168.1.2:8080", false) }
    }
    @Test fun rejectsEmbeddedSecretsAndPaths() {
        listOf("https://user:pass@example.com", "https://example.com/path", "https://example.com?q=secret", "file:///tmp/test", "example.com", "https://example.com#fragment").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize(value, true) }
        }
    }
}
