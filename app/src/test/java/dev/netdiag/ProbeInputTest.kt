package dev.netdiag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeInputTest {
    @Test fun trimsAddressAndAcceptsBoundaryValues() {
        assertEquals(ProbeInput("::1", 65535, 100), ProbeInput.parse(" ::1 ", "65535", "100"))
        assertEquals(10000, ProbeInput.parse("127.0.0.1", "1", "10000").timeoutMs)
    }
    @Test fun invalidPortAndTimeoutFailBeforeStartingNativeWork() {
        for (port in listOf("", "abc", "0", "65536", "9999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) { ProbeInput.parse("127.0.0.1", port, "1000") }
        }
        for (timeout in listOf("", "abc", "99", "10001")) {
            assertThrows(IllegalArgumentException::class.java) { ProbeInput.parse("127.0.0.1", "7777", timeout) }
        }
    }
    @Test fun blankAddressIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProbeInput.parse(" ", "7777", "1000") }
    }
}
