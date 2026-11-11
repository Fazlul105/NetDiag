package dev.netdiag

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SessionTest {
    private fun event(type: String, nanos: Long = 10, detail: String = "Wi-Fi") =
        NetworkEvent(Instant.parse("2026-11-11T00:00:00Z"), nanos, type, "42", detail)

    @Test fun ignoresEventsOutsideRecordingAndPreservesStoppedSession() {
        val session = Session()
        session.record(event("IGNORED"))
        assertTrue(session.events.isEmpty())
        session.start(event("START"))
        session.record(event("LOST", 20))
        session.stop(event("STOP", 30))
        session.record(event("IGNORED", 40))
        session.stop(event("DUPLICATE_STOP", 50))
        assertEquals(listOf("START", "LOST", "STOP"), session.events.map { it.type })
        assertFalse(session.recording)
    }

    @Test fun newSessionClearsPreviousDataWithoutMutatingExistingSnapshot() {
        val session = Session()
        session.start(event("FIRST"))
        session.stop(event("STOP"))
        val previous = session.events
        session.start(event("SECOND"))
        assertEquals(2, previous.size)
        assertEquals(listOf("SECOND"), session.events.map { it.type })
    }

    @Test fun csvEscapesCommasQuotesAndNewlinesAndPreservesBothClocks() {
        val session = Session()
        session.start(event("START", 123456789, "Wi-Fi, \"lab\"\nvalidated"))
        assertEquals("wall_time_utc,elapsed_realtime_ns,event,network,detail\r\n" +
            "\"2026-11-11T00:00:00Z\",\"123456789\",\"START\",\"42\",\"Wi-Fi, \"\"lab\"\"\nvalidated\"\r\n",
            session.csv())
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateStartCannotEraseActiveRecording() {
        val session = Session()
        session.start(event("START"))
        session.start(event("SECOND"))
    }
}
