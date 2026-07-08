package dev.rits.bettermoodle

import dev.rits.bettermoodle.data.MoodleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionEventPagingTest {

    @Test
    fun `fetches next page when current page is full and below max pages`() {
        assertTrue(MoodleRepository.shouldFetchNextPage(pageSize = 50, received = 50, pagesFetched = 1))
        assertTrue(MoodleRepository.shouldFetchNextPage(pageSize = 50, received = 50, pagesFetched = 2))
    }

    @Test
    fun `stops paging when current page is not full`() {
        assertFalse(MoodleRepository.shouldFetchNextPage(pageSize = 50, received = 49, pagesFetched = 1))
        assertFalse(MoodleRepository.shouldFetchNextPage(pageSize = 50, received = 0, pagesFetched = 1))
    }

    @Test
    fun `stops paging after maximum pages`() {
        assertFalse(MoodleRepository.shouldFetchNextPage(pageSize = 50, received = 50, pagesFetched = 3))
    }

    @Test
    fun `action events params include aftereventid only for follow up pages`() {
        assertEquals(
            mapOf(
                "timesortfrom" to "123",
                "limitnum" to "50",
            ),
            MoodleRepository.actionEventsParams(fromEpochSec = 123L, limit = 50),
        )

        assertEquals(
            mapOf(
                "timesortfrom" to "123",
                "limitnum" to "50",
                "aftereventid" to "456",
            ),
            MoodleRepository.actionEventsParams(fromEpochSec = 123L, limit = 50, afterEventId = 456L),
        )
    }
}
