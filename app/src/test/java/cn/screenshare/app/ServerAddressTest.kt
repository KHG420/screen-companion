package cn.screenshare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
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
