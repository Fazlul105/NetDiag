package dev.netdiag

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.Locale

data class DiagnosticsState(
    val network: String = "Waiting for network state",
    val recording: Boolean = false,
    val events: List<NetworkEvent> = emptyList(),
    val error: String? = null,
)

class DiagnosticsModel(application: Application) : AndroidViewModel(application) {
    private val manager = application.getSystemService(ConnectivityManager::class.java)
    private val session = Session()
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state = mutableState.asStateFlow()
    private var registered = false
    private var currentNetwork: Network? = null
    private var currentDescription = "No default network"
    private val recovery = RecoveryTracker()
    private var recoveryTarget: ProbeInput? = null
    private var recoveryJob: Job? = null
    private var recoveryId: Long? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            currentNetwork = network
            currentDescription = "Network available; checking capabilities"
            observe("AVAILABLE", network, currentDescription)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != currentNetwork) return
            val transports = buildList {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Mobile")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            }.ifEmpty { listOf("Other") }.joinToString(" + ")
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            currentDescription = "$transports | Internet ${if (validated) "validated" else "not validated"}"
            observe("CAPABILITIES", network, currentDescription)
            if (session.recording && recoveryTarget != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                recovery.wifiAvailable(network.toString(), SystemClock.elapsedRealtimeNanos())?.let {
                    runRecovery(it, network)
                }
            }
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                cancelRecovery("Network lost during probing")
                if (session.recording && recoveryTarget != null) recovery.lost()
                currentNetwork = null
                currentDescription = "No default network"
            }
            observe("LOST", network, "Default network lost")
        }
    }

    fun attach() {
        if (registered) return
        try {
            currentNetwork = null
            currentDescription = "Waiting for network state"
            publish()
            manager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
            if (manager.activeNetwork == null) {
                currentDescription = "No default network"
                publish()
            }
        } catch (exception: RuntimeException) {
            mutableState.value = mutableState.value.copy(error = exception.message)
        }
    }

    /** Callbacks and recording run on the main thread, so session mutations are serialized. */
    fun detach() {
        if (session.recording) stop("App left the foreground")
        if (registered) {
            manager.unregisterNetworkCallback(callback)
            registered = false
        }
    }

    fun start() {
        if (session.recording || !registered) return
        recovery.reset()
        recoveryTarget = null
        session.start(event("SESSION_START", currentNetwork, currentDescription))
        publish()
    }

    fun startRecovery(address: String, port: String) {
        if (session.recording || !registered) return
        val target = try { ProbeInput.parse(address, port, "500") }
        catch (error: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = error.message)
            return
        }
        start()
        recoveryTarget = target
        session.record(event("RECOVERY_CONFIG", currentNetwork,
            "target=${target.address}:${target.port};timeout_ms=500;retry_delay_ms=250;budget_ms=15000"))
        publish()
    }

    fun stop(reason: String = "User stopped recording") {
        cancelRecovery(reason)
        recoveryTarget = null
        recovery.reset()
        session.stop(event("SESSION_STOP", currentNetwork, reason))
        publish()
    }

    fun csv(): String = session.csv()

    private fun runRecovery(trial: RecoveryTrial, network: Network) {
        val target = requireNotNull(recoveryTarget)
        session.record(NetworkEvent(Instant.now(), trial.startedNanos, "RECOVERY_START",
            trial.network, "trial=${trial.number}"))
        val id = NativeBridge.createRun()
        recoveryId = id
        recoveryJob = viewModelScope.launch {
            var attempt = 0
            while (recovery.active == trial && session.recording) {
                val remainingMs = (trial.startedNanos + 15_000_000_000L - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
                if (remainingMs < 100) break
                attempt++
                session.record(event("PROBE_START", network, "trial=${trial.number};attempt=$attempt"))
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.run(id, 0, target.address, target.port, minOf(500L, remainingMs).toInt(), network.networkHandle)
                }
                ensureActive()
                val finished = SystemClock.elapsedRealtimeNanos()
                val detail = "trial=${trial.number};attempt=$attempt;status=${result.status};" +
                    "elapsed_ms=${String.format(Locale.US, "%.3f", result.elapsedMs)};errno=${result.systemError}"
                session.record(NetworkEvent(Instant.now(), finished, "PROBE_RESULT", trial.network, detail))
                if (result.status == "OK") {
                    val recoveryMs = (finished - trial.startedNanos) / 1_000_000.0
                    session.record(event("RECOVERY_OK", network, "trial=${trial.number};attempts=$attempt;" +
                        "observed_recovery_ms=${String.format(Locale.US, "%.3f", recoveryMs)}"))
                    recovery.finish()
                    publish()
                    return@launch
                }
                publish()
                delay(250)
            }
            session.record(event("RECOVERY_TIMEOUT", network, "trial=${trial.number};attempts=$attempt"))
            recovery.finish()
            publish()
        }.also { job -> job.invokeOnCompletion { NativeBridge.release(id) } }
        publish()
    }

    private fun cancelRecovery(reason: String) {
        recoveryId?.let { NativeBridge.cancel(it) }
        recoveryId = null
        recoveryJob?.cancel()
        recoveryJob = null
        recovery.active?.let {
            session.record(event("RECOVERY_CANCELLED", currentNetwork, "trial=${it.number};reason=$reason"))
        }
        recovery.finish()
    }

    private fun observe(type: String, network: Network, detail: String) {
        session.record(event(type, network, detail))
        publish()
    }

    private fun event(type: String, network: Network?, detail: String) = NetworkEvent(
        Instant.now(), SystemClock.elapsedRealtimeNanos(), type, network?.toString() ?: "none", detail,
    )

    private fun publish() {
        mutableState.value = DiagnosticsState(currentDescription, session.recording, session.events)
    }

    override fun onCleared() { detach() }
}
