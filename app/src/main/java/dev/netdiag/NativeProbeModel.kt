package dev.netdiag

import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.time.Instant

data class ProbeObservation(val protocol: String, val target: String, val at: Instant, val result: NativeResult)
data class ProbeState(val working: Boolean = false, val observation: ProbeObservation? = null, val error: String? = null)

class NativeProbeModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ProbeState())
    val state = mutableState.asStateFlow()
    private var activeId: Long? = null

    fun start(protocol: Int, address: String, port: String, timeout: String) {
        if (mutableState.value.working || !viewModelScope.isActive) return
        val input = try { ProbeInput.parse(address, port, timeout) }
        catch (error: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = error.message)
            return
        }
        val network = getApplication<Application>().getSystemService(ConnectivityManager::class.java).activeNetwork?.networkHandle ?: 0L
        val id = try { NativeBridge.createRun() }
        catch (error: IllegalStateException) {
            mutableState.value = ProbeState(error = error.message ?: "Unable to create native request")
            return
        }
        activeId = id
        mutableState.value = ProbeState(working = true)
        val job = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NativeBridge.run(id, protocol, input.address, input.port, input.timeoutMs, network)
                }
                mutableState.value = ProbeState(observation = ProbeObservation(
                    if (protocol == 0) "TCP connect" else "UDP echo",
                    if (input.address.contains(':')) "[${input.address}]:${input.port}" else "${input.address}:${input.port}",
                    Instant.now(), result,
                ))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = ProbeState(error = error.message ?: "Native request failed")
            } finally {
                if (activeId == id) activeId = null
            }
        }
        // Also releases the handle if cancellation prevents the coroutine body
        // from starting. Completion waits for the blocking IO child to finish.
        job.invokeOnCompletion { NativeBridge.release(id) }
    }

    fun cancel() { activeId?.let { NativeBridge.cancel(it) } }
    override fun onCleared() { cancel(); super.onCleared() }
}
