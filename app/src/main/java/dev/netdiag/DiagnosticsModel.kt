package dev.netdiag

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

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
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
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
        session.start(event("SESSION_START", currentNetwork, currentDescription))
        publish()
    }

    fun stop(reason: String = "User stopped recording") {
        session.stop(event("SESSION_STOP", currentNetwork, reason))
        publish()
    }

    fun csv(): String = session.csv()

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
