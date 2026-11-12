package dev.netdiag

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in physical Wi-Fi experiment. Normal test runs skip network toggling. */
@RunWith(AndroidJUnit4::class)
class WifiRecoveryExperiment {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            Thread.sleep(50)
        }
        return true
    }

    @Test fun fivePhysicalWifiRecoveryTrials() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Set wifiExperiment=true explicitly to toggle the phone's Wi-Fi", args.getString("wifiExperiment") == "true")
        val address = requireNotNull(args.getString("serverIp"))
        val port = args.getString("serverPort", "7777").toInt()
        val app = instrumentation.targetContext.applicationContext as Application
        val manager = app.getSystemService(ConnectivityManager::class.java)
        fun wifiActive(): Boolean = manager.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        assertTrue("Connect the phone to Wi-Fi before starting", wifiActive())
        assertEquals("Mobile data must be off for this isolated Wi-Fi experiment", "0", shell("settings get global mobile_data"))
        val id = NativeBridge.createRun()
        try {
            val result = NativeBridge.run(id, 0, address, port, 1500, requireNotNull(manager.activeNetwork).networkHandle)
            assertEquals("Baseline phone-to-laptop TCP failed: ${result.detail} errno=${result.systemError}", "OK", result.status)
        } finally { NativeBridge.release(id) }

        val store = ViewModelStore()
        lateinit var model: DiagnosticsModel
        var initialized = false
        try {
            instrumentation.runOnMainSync {
                model = DiagnosticsModel(app)
                initialized = true
                store.put("recovery", model)
                model.attach()
            }
            assertTrue("Initial callback did not arrive", await(5000) { model.state.value.network.contains("Wi-Fi") })
            instrumentation.runOnMainSync { model.startRecovery(address, port.toString()) }
            for (trial in 1..5) {
                val losses = model.state.value.events.count { it.type == "LOST" }
                shell("svc wifi disable")
                assertTrue("Trial $trial did not record Wi-Fi loss", await(5000) {
                    model.state.value.events.count { it.type == "LOST" } > losses
                })
                Thread.sleep(5000)
                shell("svc wifi enable")
                assertTrue("Trial $trial did not finish within 45 seconds", await(45000) {
                    model.state.value.events.any {
                        it.type in listOf("RECOVERY_OK", "RECOVERY_TIMEOUT") &&
                            it.detail.substringBefore(';') == "trial=$trial"
                    }
                })
                // Allow Android's validation callback to arrive before the next loss.
                await(10000) { model.state.value.network == "Wi-Fi | Internet validated" }
                Thread.sleep(1000)
            }
        } finally {
            shell("svc wifi enable")
            if (initialized) {
                instrumentation.runOnMainSync {
                    model.stop("Five-trial Wi-Fi experiment ended")
                    File(app.filesDir, "wifi-recovery.csv").writeText(model.csv())
                    store.clear()
                }
            }
        }
    }
}
