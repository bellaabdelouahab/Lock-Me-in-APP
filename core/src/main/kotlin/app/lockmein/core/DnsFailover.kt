package app.lockmein.core

import java.io.ByteArrayOutputStream

/**
 * When the private DNS server stops answering, the phone would have no internet at all (Android's strict mode fails closed).
 * His choice (2026-11-24): fall back to plain automatic DNS until the server answers again, then pin it back (FEATURES 3.17).
 *
 * Decided on what the phone itself measures, never on a guess:
 * - it falls back only when the network works (the network's own DNS answered) and the private DNS server failed
 *   [FAILS_TO_FALL_BACK] times in a row, at least [MIN_SPAN_MS] apart; no network at all is not the server's fault, and
 *   falling back then would only leave the phone unfiltered once the network returns;
 * - it pins the server again after [OKS_TO_RESTORE] good answers in a row, at least [MIN_SPAN_MS] apart, so the unfiltered
 *   time stays as short as possible without flapping on a server that is restarting.
 */
data class DnsFailover(
    /** When the phone fell back to automatic DNS; null while the server is pinned. */
    val fallbackSince: Long? = null,
    /** Consecutive failures while pinned, and when the first of them was. */
    val fails: Int = 0,
    val firstFailAt: Long = 0,
    /** Consecutive good answers while fallen back, and when the first of them was. */
    val oks: Int = 0,
    val firstOkAt: Long = 0,
) {
    val isFallback: Boolean get() = fallbackSince != null

    enum class Change { NONE, FALL_BACK, RESTORE }

    /**
     * One probe: [serverOk] the private DNS server answered a real query over TLS; [networkOk] the network's own DNS answered.
     * Returns the new state and what to do.
     */
    fun after(serverOk: Boolean, networkOk: Boolean, nowMs: Long): Pair<DnsFailover, Change> {
        if (!isFallback) {
            if (serverOk) return copy(fails = 0, firstFailAt = 0) to Change.NONE
            if (!networkOk) return this to Change.NONE // offline or a captive portal: nothing to learn about the server
            val fails = fails + 1
            val first = if (this.fails == 0) nowMs else firstFailAt
            return if (fails >= FAILS_TO_FALL_BACK && nowMs - first >= MIN_SPAN_MS) {
                DnsFailover(fallbackSince = nowMs) to Change.FALL_BACK
            } else {
                copy(fails = fails, firstFailAt = first) to Change.NONE
            }
        }
        if (!serverOk) return copy(oks = 0, firstOkAt = 0) to Change.NONE
        val oks = oks + 1
        val first = if (this.oks == 0) nowMs else firstOkAt
        return if (oks >= OKS_TO_RESTORE && nowMs - first >= MIN_SPAN_MS) {
            DnsFailover() to Change.RESTORE
        } else {
            copy(oks = oks, firstOkAt = first) to Change.NONE
        }
    }

    companion object {
        const val FAILS_TO_FALL_BACK = 3
        const val OKS_TO_RESTORE = 2
        const val MIN_SPAN_MS = 60_000L

        /** Probe every minute while something is wrong (not validated, or fallen back); quiet otherwise. */
        const val PROBE_EVERY_MS = 60_000L
    }
}

/** The bit of the DNS wire format a probe needs: one A query, and whether the answer is a real one (RFC 1035). */
object DnsWire {
    fun query(id: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x0100) // recursion desired
        u16(1); u16(0); u16(0); u16(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad label in $name" }
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        u16(1); u16(1) // type A, class IN
        return out.toByteArray()
    }

    /** True when [reply] answers query [id] with NOERROR and at least one answer record. */
    fun isGoodAnswer(reply: ByteArray, id: Int): Boolean {
        if (reply.size < 12) return false
        val u16 = { i: Int -> (reply[i].toInt() and 0xFF shl 8) or (reply[i + 1].toInt() and 0xFF) }
        val flags = u16(2)
        val isResponse = flags and 0x8000 != 0
        val rcode = flags and 0x000F
        return u16(0) == id && isResponse && rcode == 0 && u16(6) > 0
    }

    /** The first IPv4 address in [reply], if any (answers may use name compression). */
    fun firstA(reply: ByteArray): ByteArray? = runCatching {
        val u16 = { i: Int -> (reply[i].toInt() and 0xFF shl 8) or (reply[i + 1].toInt() and 0xFF) }
        fun skipName(start: Int): Int {
            var i = start
            while (true) {
                val len = reply[i].toInt() and 0xFF
                if (len == 0) return i + 1
                if (len and 0xC0 == 0xC0) return i + 2
                i += len + 1
            }
        }
        var i = 12
        repeat(u16(4)) { i = skipName(i) + 4 }
        repeat(u16(6)) {
            i = skipName(i)
            val type = u16(i)
            val length = u16(i + 8)
            val data = i + 10
            if (type == 1 && length == 4) return@runCatching reply.copyOfRange(data, data + 4)
            i = data + length
        }
        null
    }.getOrNull()

    /** DNS over TCP and TLS: each message carries its length first (RFC 7858). */
    fun framed(message: ByteArray): ByteArray = byteArrayOf((message.size shr 8).toByte(), message.size.toByte()) + message
}
