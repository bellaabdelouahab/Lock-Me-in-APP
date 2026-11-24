package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DnsFailoverTest {
    private fun run(start: DnsFailover, vararg probes: Triple<Boolean, Boolean, Long>): Pair<DnsFailover, List<DnsFailover.Change>> {
        var s = start
        val changes = probes.map { (server, network, at) -> s.after(server, network, at).also { s = it.first }.second }
        return s to changes
    }

    @Test
    fun fallsBackOnlyAfterThreeFailuresOverAMinuteWithTheNetworkUp() {
        val (s, changes) = run(DnsFailover(), Triple(false, true, 0), Triple(false, true, 30_000), Triple(false, true, 60_000))
        assertEquals(DnsFailover.Change.FALL_BACK, changes.last())
        assertTrue(s.isFallback)
        // Three quick failures (a restart) are not enough on their own.
        val (quick, quickChanges) = run(DnsFailover(), Triple(false, true, 0), Triple(false, true, 1_000), Triple(false, true, 2_000))
        assertFalse(quick.isFallback)
        assertTrue(quickChanges.all { it == DnsFailover.Change.NONE })
    }

    @Test
    fun noNetworkIsNotTheServersFault() {
        val (s, _) = run(DnsFailover(), *Array(10) { Triple(false, false, it * 60_000L) })
        assertFalse(s.isFallback)
        assertEquals(0, s.fails)
    }

    @Test
    fun oneGoodAnswerClearsTheFailures() {
        val (s, _) = run(DnsFailover(), Triple(false, true, 0), Triple(false, true, 60_000), Triple(true, true, 90_000), Triple(false, true, 120_000))
        assertFalse(s.isFallback)
        assertEquals(1, s.fails)
    }

    @Test
    fun pinsBackAfterTwoGoodAnswersAMinuteApart() {
        val down = DnsFailover(fallbackSince = 0)
        val (s, changes) = run(down, Triple(true, true, 10_000), Triple(false, true, 20_000), Triple(true, true, 30_000), Triple(true, true, 50_000), Triple(true, true, 90_000))
        assertEquals(DnsFailover.Change.RESTORE, changes.last())
        assertFalse(s.isFallback)
        assertTrue(changes.dropLast(1).all { it == DnsFailover.Change.NONE })
    }

    @Test
    fun theProbeQueryAndAnswersAreReadRight() {
        val q = DnsWire.query(0x1234, "example.com")
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(12 + 13 + 4, q.size) // header, 7example3com0, type and class
        val framed = DnsWire.framed(q)
        assertEquals(q.size, (framed[0].toInt() shl 8) or (framed[1].toInt() and 0xFF))

        fun reply(id: Int, flags: Int, answers: Int) = byteArrayOf((id shr 8).toByte(), id.toByte(), (flags shr 8).toByte(), flags.toByte(), 0, 1, 0, answers.toByte(), 0, 0, 0, 0)
        assertTrue(DnsWire.isGoodAnswer(reply(0x1234, 0x8180, 1), 0x1234))
        assertFalse(DnsWire.isGoodAnswer(reply(0x1234, 0x8183, 0), 0x1234), "NXDOMAIN")
        assertFalse(DnsWire.isGoodAnswer(reply(0x1234, 0x8182, 0), 0x1234), "SERVFAIL")
        assertFalse(DnsWire.isGoodAnswer(reply(0x9999, 0x8180, 1), 0x1234), "another query's answer")
        assertFalse(DnsWire.isGoodAnswer(reply(0x1234, 0x0100, 1), 0x1234), "not a response")
    }

    @Test
    fun readsTheAddressOutOfACompressedAnswer() {
        val q = DnsWire.query(7, "dns.example.com")
        // Header says 1 question, 2 answers: a CNAME then an A, both names as pointers to the question (0xC00C).
        val header = byteArrayOf(0, 7, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 2, 0, 0, 0, 0)
        val cname = byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0.toByte(), 12)
        val a = byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 129.toByte(), 151.toByte(), 1, 2)
        val reply = header + q.copyOfRange(12, q.size) + cname + a
        assertTrue(DnsWire.isGoodAnswer(reply, 7))
        assertEquals(listOf(129, 151, 1, 2), DnsWire.firstA(reply)!!.map { it.toInt() and 0xFF })
        assertEquals(null, DnsWire.firstA(header + q.copyOfRange(12, q.size)))
    }
}
