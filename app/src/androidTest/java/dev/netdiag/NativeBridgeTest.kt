package dev.netdiag

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeBridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun probe(protocol: Int, port: Int, timeout: Int = 1000, address: String = "127.0.0.1"): NativeResult {
        val id = NativeBridge.createRun()
        return try { NativeBridge.run(id, protocol, address, port, timeout, 0) }
        finally { NativeBridge.release(id) }
    }

    @Test fun tcpConnectionReturnsTypedResultThroughJni() {
        ServerSocket(0, 8, loopback).use { server ->
            val result = probe(0, server.localPort)
            assertEquals("OK", result.status)
            assertEquals(0, result.systemError)
            assertTrue(result.elapsedMs >= 0)
        }
    }

    @Test fun udpRoundTripMatchesThePayload() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val echo = executor.submit {
                    val packet = DatagramPacket(ByteArray(128), 128)
                    server.receive(packet)
                    server.send(packet)
                }
                assertEquals("OK", probe(1, server.localPort).status)
                echo.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun silentUdpPeerReportsTimeoutInsteadOfBlockingIndefinitely() {
        DatagramSocket(0, loopback).use { server ->
            val result = probe(1, server.localPort, 150)
            assertEquals("TIMEOUT", result.status)
            assertTrue(result.elapsedMs in 140.0..2000.0)
        }
    }

    @Test fun activeRequestCanBeCancelledFromAnotherManagedThread() {
        DatagramSocket(0, loopback).use { server ->
            val id = NativeBridge.createRun()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<NativeResult> { NativeBridge.run(id, 1, "127.0.0.1", server.localPort, 3000, 0) }
                Thread.sleep(30)
                NativeBridge.cancel(id)
                assertEquals("CANCELLED", pending.get(2, TimeUnit.SECONDS).status)
            } finally { NativeBridge.cancel(id); executor.shutdownNow(); NativeBridge.release(id) }
        }
    }

    @Test fun releasedHandlesAndInvalidAddressesAreRejectedSafely() {
        val id = NativeBridge.createRun()
        NativeBridge.release(id)
        NativeBridge.cancel(id)
        NativeBridge.release(id)
        assertEquals("INVALID_INPUT", NativeBridge.run(id, 0, "127.0.0.1", 7777, 500, 0).status)
        assertEquals("INVALID_INPUT", probe(0, 7777, address = "not-a-numeric-ip").status)
    }
}
