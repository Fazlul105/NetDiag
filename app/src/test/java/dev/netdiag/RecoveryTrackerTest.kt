package dev.netdiag

import org.junit.Assert.*
import org.junit.Test

class RecoveryTrackerTest {
    @Test fun startsOnlyAfterLossAndIgnoresRepeatedCapabilities() {
        val tracker = RecoveryTracker()
        assertNull(tracker.wifiAvailable("10", 100))
        tracker.lost()
        assertEquals(RecoveryTrial(1, "11", 200), tracker.wifiAvailable("11", 200))
        assertNull(tracker.wifiAvailable("11", 300))
        tracker.finish()
        assertNull(tracker.wifiAvailable("11", 400))
    }

    @Test fun anotherLossRearmsAndReplacesTheInterruptedTrial() {
        val tracker = RecoveryTracker()
        tracker.lost()
        tracker.wifiAvailable("11", 200)
        tracker.lost()
        assertNull(tracker.active)
        assertEquals(RecoveryTrial(2, "12", 300), tracker.wifiAvailable("12", 300))
    }

    @Test fun resetStartsANewSessionWithoutKeepingAnArmedLoss() {
        val tracker = RecoveryTracker()
        tracker.lost()
        tracker.wifiAvailable("11", 200)
        tracker.lost()
        tracker.reset()
        assertNull(tracker.wifiAvailable("12", 300))
        tracker.lost()
        assertEquals(1, tracker.wifiAvailable("13", 400)?.number)
    }
}
