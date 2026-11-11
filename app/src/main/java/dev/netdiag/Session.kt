package dev.netdiag

import java.time.Instant

data class NetworkEvent(
    val wallTime: Instant,
    val elapsedNanos: Long,
    val type: String,
    val network: String,
    val detail: String,
)

/** Pure recording logic, independent of Android callbacks and the UI. */
class Session {
    var recording = false
        private set
    private val recorded = mutableListOf<NetworkEvent>()
    val events: List<NetworkEvent> get() = recorded.toList()

    fun start(event: NetworkEvent) {
        check(!recording) { "A session is already recording" }
        recorded.clear()
        recording = true
        recorded.add(event)
    }

    fun record(event: NetworkEvent) {
        if (recording) recorded.add(event)
    }

    fun stop(event: NetworkEvent) {
        if (!recording) return
        recorded.add(event)
        recording = false
    }

    fun csv(): String = buildString {
        append("wall_time_utc,elapsed_realtime_ns,event,network,detail\r\n")
        for (event in recorded) {
            append(listOf(event.wallTime.toString(), event.elapsedNanos.toString(),
                event.type, event.network, event.detail).joinToString(",", transform = ::csvCell))
            append("\r\n")
        }
    }

    private fun csvCell(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""
}
