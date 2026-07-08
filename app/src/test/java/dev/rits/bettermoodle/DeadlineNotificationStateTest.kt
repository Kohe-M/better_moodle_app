package dev.rits.bettermoodle

import dev.rits.bettermoodle.work.retainedNotifiedEventIds
import org.junit.Assert.assertEquals
import org.junit.Test

class DeadlineNotificationStateTest {

    @Test
    fun `retains only existing notified ids that are still current deadlines plus newly notified ids`() {
        val retained = retainedNotifiedEventIds(
            existingNotifiedIds = setOf("old", "still-current", "already-notified"),
            currentDeadlineIds = setOf("still-current", "already-notified", "new-deadline"),
            newlyNotifiedIds = setOf("new-deadline"),
        )

        assertEquals(setOf("still-current", "already-notified", "new-deadline"), retained)
    }

    @Test
    fun `does not record fresh deadlines when no notification was sent`() {
        val retained = retainedNotifiedEventIds(
            existingNotifiedIds = setOf("stale"),
            currentDeadlineIds = setOf("fresh"),
            newlyNotifiedIds = emptySet(),
        )

        assertEquals(emptySet<String>(), retained)
    }
}
