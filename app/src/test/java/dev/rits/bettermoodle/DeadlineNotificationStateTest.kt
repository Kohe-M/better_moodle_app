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

class DeadlineNotificationKeyTest {

    @Test
    fun `key changes when the deadline moves so the event is re-notified`() {
        val before = dev.rits.bettermoodle.work.deadlineNotificationKey(10L, 1_000L)
        val after = dev.rits.bettermoodle.work.deadlineNotificationKey(10L, 2_000L)
        assertEquals("10@1000", before)
        org.junit.Assert.assertNotEquals(before, after)
    }

    @Test
    fun `notification target prefers action url and only allows moodle https`() {
        val event = dev.rits.bettermoodle.data.ActionEvent(
            id = 1,
            url = "http://lms.ritsumei.ac.jp/mod/assign/view.php?id=5",
            action = dev.rits.bettermoodle.data.EventAction(url = "https://lms.ritsumei.ac.jp/mod/assign/view.php?id=5&action=editsubmission"),
        )
        assertEquals(
            "https://lms.ritsumei.ac.jp/mod/assign/view.php?id=5&action=editsubmission",
            dev.rits.bettermoodle.work.DeadlineWorker.notificationTargetUrl(event),
        )

        val httpOnly = event.copy(action = null)
        assertEquals(
            "https://lms.ritsumei.ac.jp/mod/assign/view.php?id=5",
            dev.rits.bettermoodle.work.DeadlineWorker.notificationTargetUrl(httpOnly),
        )

        val foreign = event.copy(url = "https://evil.example/x", action = dev.rits.bettermoodle.data.EventAction(url = "https://evil.example/y"))
        assertEquals(null, dev.rits.bettermoodle.work.DeadlineWorker.notificationTargetUrl(foreign))
    }
}
