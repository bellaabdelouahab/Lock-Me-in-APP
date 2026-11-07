package app.lockmein.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledRemovalsTest {
    private val now = Instant.parse("2026-11-01T20:00:00Z")
    private val pkg = "com.instagram.android"

    @Test
    fun `scheduled removal takes effect automatically 7 days later`() {
        val removal = ScheduledRemovals.schedule(AppList.ALWAYS_BLOCKED, pkg, now)
        assertEquals(now.plus(Duration.ofDays(7)), removal.dueAt)
        assertFalse(removal.isDue(now))
        assertFalse(removal.isDue(now.plus(Duration.ofDays(6)).plus(Duration.ofHours(23))))
        assertTrue(removal.isDue(now.plus(Duration.ofDays(7))))
        assertTrue(removal.isDue(now.plus(Duration.ofDays(8))))
    }

    @Test
    fun `cancel removes the pending scheduled removal`() {
        val removal = ScheduledRemovals.schedule(AppList.ALWAYS_BLOCKED, pkg, now)
        val list = listOf(removal)
        assertEquals(emptyList(), ScheduledRemovals.cancel(list, removal.id))
    }

    @Test
    fun `due filters only items that reached their 7 day wait`() {
        val past = ScheduledRemoval(AppList.ALWAYS_BLOCKED, "app1", now.minus(Duration.ofDays(8)).toEpochMilli(), now.minus(Duration.ofDays(1)).toEpochMilli())
        val future = ScheduledRemoval(AppList.ALWAYS_BLOCKED, "app2", now.toEpochMilli(), now.plus(Duration.ofDays(7)).toEpochMilli())
        val list = listOf(past, future)

        val due = ScheduledRemovals.due(list, now)
        assertEquals(listOf(past), due)

        val remaining = ScheduledRemovals.remaining(list, now)
        assertEquals(listOf(future), remaining)
    }

    @Test
    fun `scheduled removals serialize and deserialize cleanly`() {
        val removal = ScheduledRemovals.schedule(AppList.WINDOW_APPS, "com.google.android.youtube", now)
        val encoded = ScheduledRemovals.encode(listOf(removal))
        val decoded = ScheduledRemovals.decode(encoded)
        assertEquals(listOf(removal), decoded)
    }
}
