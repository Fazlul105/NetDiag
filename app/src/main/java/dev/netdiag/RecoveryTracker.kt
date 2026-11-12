package dev.netdiag

data class RecoveryTrial(val number: Int, val network: String, val startedNanos: Long)

/** One recovery trial per loss, even when Android repeats capability callbacks. */
class RecoveryTracker {
    private var armed = false
    private var count = 0
    var active: RecoveryTrial? = null
        private set

    fun lost() {
        armed = true
        active = null
    }

    fun wifiAvailable(network: String, now: Long): RecoveryTrial? {
        if (!armed) return null
        armed = false
        return RecoveryTrial(++count, network, now).also { active = it }
    }

    fun finish() {
        active = null
    }

    fun reset() {
        armed = false
        count = 0
        active = null
    }
}
