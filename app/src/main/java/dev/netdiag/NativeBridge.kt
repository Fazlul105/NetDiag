package dev.netdiag

import androidx.annotation.Keep

@Keep
data class NativeResult(val status: String, val elapsedMs: Double, val systemError: Int, val detail: String)

/** The blocking run call belongs on an IO worker, never the Android UI thread. */
@Keep
object NativeBridge {
    init { System.loadLibrary("netdiag") }
    external fun createRun(): Long
    external fun cancel(id: Long)
    external fun release(id: Long)
    external fun run(id: Long, protocol: Int, address: String, port: Int, timeoutMs: Int, networkHandle: Long): NativeResult
}

data class ProbeInput(val address: String, val port: Int, val timeoutMs: Int) {
    companion object {
        fun parse(address: String, port: String, timeout: String): ProbeInput {
            val host = address.trim()
            require(host.isNotEmpty() && host.length <= 64) { "Enter a numeric server IP address" }
            val number = port.toIntOrNull()
            require(number != null && number in 1..65535) { "Port must be 1-65535" }
            val milliseconds = timeout.toIntOrNull()
            require(milliseconds != null && milliseconds in 100..10000) { "Timeout must be 100-10000 ms" }
            return ProbeInput(host, number, milliseconds)
        }
    }
}
