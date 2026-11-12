package dev.netdiag

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
class NativeProbeModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun runModel(protocol: Int, port: Int) {
        val store = ViewModelStore()
        lateinit var model: NativeProbeModel
        try {
            instrumentation.runOnMainSync {
                model = NativeProbeModel(instrumentation.targetContext.applicationContext as Application)
                store.put("probe", model)
                model.start(protocol, "127.0.0.1", port.toString(), "1500")
                assertTrue(model.state.value.working)
            }
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (model.state.value.working && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
            assertFalse("Native result did not reach the observable UI state", model.state.value.working)
            assertNull(model.state.value.error)
            val observation = requireNotNull(model.state.value.observation)
            assertEquals("OK", observation.result.status)
            assertEquals(if (protocol == 0) "TCP connect" else "UDP echo", observation.protocol)
            assertEquals("127.0.0.1:$port", observation.target)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun tcpResultReachesTheUiStateThroughTheRealJniWorker() {
        ServerSocket(0, 8, loopback).use { server -> runModel(0, server.localPort) }
    }

    @Test fun udpResultReachesTheUiStateThroughTheRealJniWorker() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 4000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val echo = executor.submit {
                    val packet = DatagramPacket(ByteArray(128), 128)
                    server.receive(packet)
                    server.send(packet)
                }
                runModel(1, server.localPort)
                echo.get(2, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
