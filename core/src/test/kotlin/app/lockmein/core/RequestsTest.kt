package app.lockmein.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestsTest {
    private val t1 = Instant.ofEpochSecond(1_700_000_000)
    private val t2 = Instant.ofEpochSecond(1_700_010_000)
    private val t3 = Instant.ofEpochSecond(1_700_020_000)
    private val t4 = Instant.ofEpochSecond(1_700_030_000)

    @Test
    fun `asking again for the same target does not create duplicate and increments counter`() {
        val req1 = ChangeRequest(RequestKind.BLOCKED_REMOVE, "com.instagram.android", t1.toEpochMilli())
        val initial = Requests.add(emptyList(), req1, t1.toEpochMilli())
        assertEquals(1, initial.size)
        assertEquals(1, initial[0].count)
        assertEquals(listOf(t1.toEpochMilli()), initial[0].requestTimes)

        // Asking a 2nd time
        val after2 = Requests.add(initial, req1, t2.toEpochMilli())
        assertEquals(1, after2.size)
        assertEquals(2, after2[0].count)
        assertEquals(listOf(t1.toEpochMilli(), t2.toEpochMilli()), after2[0].requestTimes)
        assertEquals(t1.toEpochMilli(), after2[0].requestedAtMillis, "original requestedAt is preserved")

        // Asking a 3rd and 4th time
        val after3 = Requests.add(after2, req1, t3.toEpochMilli())
        val after4 = Requests.add(after3, req1, t4.toEpochMilli())
        assertEquals(1, after4.size)
        assertEquals(4, after4[0].count)
        assertEquals(4, after4[0].requestTimes.size)
    }

    @Test
    fun `requests for different targets or kinds remain separate`() {
        val insta = ChangeRequest(RequestKind.BLOCKED_REMOVE, "com.instagram.android", t1.toEpochMilli())
        val fb = ChangeRequest(RequestKind.BLOCKED_REMOVE, "com.facebook.katana", t1.toEpochMilli())
        val browser = ChangeRequest(RequestKind.BROWSER, "com.brave.browser", t1.toEpochMilli())

        var list = Requests.add(emptyList(), insta, t1.toEpochMilli())
        list = Requests.add(list, fb, t1.toEpochMilli())
        list = Requests.add(list, browser, t1.toEpochMilli())
        assertEquals(3, list.size)
    }

    @Test
    fun `settings request updates draft while incrementing count`() {
        val config1 = LockConfig(dnsHost = "dns.google")
        val config2 = LockConfig(dnsHost = "1.1.1.1")
        val req1 = ChangeRequest(RequestKind.SETTINGS, "", t1.toEpochMilli(), settings = config1)
        val req2 = ChangeRequest(RequestKind.SETTINGS, "", t2.toEpochMilli(), settings = config2)

        val list1 = Requests.add(emptyList(), req1, t1.toEpochMilli())
        val list2 = Requests.add(list1, req2, t2.toEpochMilli())

        assertEquals(1, list2.size)
        assertEquals(2, list2[0].count)
        assertEquals("1.1.1.1", list2[0].settings?.dnsHost)
    }

    @Test
    fun `sortByMostAsked places highest count first`() {
        val r1 = ChangeRequest(RequestKind.BLOCKED_REMOVE, "app1", t1.toEpochMilli(), count = 1)
        val r2 = ChangeRequest(RequestKind.BLOCKED_REMOVE, "app2", t1.toEpochMilli(), count = 5)
        val r3 = ChangeRequest(RequestKind.BLOCKED_REMOVE, "app3", t1.toEpochMilli(), count = 3)

        val sorted = Requests.sortByMostAsked(listOf(r1, r2, r3))
        assertEquals(listOf("app2", "app3", "app1"), sorted.map { it.target })
    }

    @Test
    fun `resolve removes request and returns resolved history item`() {
        val req = ChangeRequest(RequestKind.BLOCKED_REMOVE, "com.instagram.android", t1.toEpochMilli(), count = 4)
        val list = listOf(req)

        val (remaining, resolved) = Requests.resolve(list, req.id, RequestResolution.APPROVED, t2)
        assertTrue(remaining.isEmpty())
        assertNotNull(resolved)
        assertEquals(RequestKind.BLOCKED_REMOVE, resolved.kind)
        assertEquals("com.instagram.android", resolved.target)
        assertEquals(4, resolved.count)
        assertEquals(RequestResolution.APPROVED, resolved.resolution)
        assertEquals(t2.toEpochMilli(), resolved.resolvedAtMillis)
    }

    @Test
    fun `encode and decode history preserves items`() {
        val h1 = ResolvedRequest(RequestKind.BLOCKED_REMOVE, "app1", 4, t1.toEpochMilli(), t2.toEpochMilli(), RequestResolution.APPROVED)
        val h2 = ResolvedRequest(RequestKind.BROWSER, "app2", 2, t1.toEpochMilli(), t2.toEpochMilli(), RequestResolution.DISMISSED)

        val encoded = Requests.encodeHistory(listOf(h1, h2))
        val decoded = Requests.decodeHistory(encoded)

        assertEquals(listOf(h1, h2), decoded)
        assertTrue(Requests.decodeHistory("invalid json").isEmpty())
    }
}
