package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteTest {
    private val c = LockConfig(dnsHost = "abc.dns.nextdns.io")

    @Test
    fun `each listed setting maps onto the config`() {
        assertEquals("23:00", RemoteSettings.apply(c, "night_start", "23:00").nightStart)
        assertNull(RemoteSettings.apply(c.copy(allowedBrowser = "x"), "allowed_browser", "none").allowedBrowser)
        assertEquals(0.8f, RemoteSettings.apply(c, "filter_porn_hentai", "0.8").filterPornHentaiThreshold)
        assertFalse(RemoteSettings.apply(c, "filter_seductive_live", "false").filterSeductiveLive)
    }

    @Test
    fun `unknown fields and broken values are refused`() {
        assertFailsWith<IllegalArgumentException> { RemoteSettings.apply(c, "watchedApps", "[]") }
        assertFailsWith<IllegalArgumentException> { RemoteSettings.apply(c, "night_start", "late") }
        assertFailsWith<IllegalArgumentException> { RemoteSettings.apply(c, "filter_sexy", "2") }
        assertFailsWith<IllegalArgumentException> { RemoteSettings.apply(c, "window_day", "monday") }
        assertFailsWith<IllegalArgumentException> { RemoteSettings.strictness("off") }
    }

    @Test
    fun `a stricter filter from the MCP is tightening, a looser one is not`() {
        assertTrue(FilterTighten.onlyTightens(c, RemoteSettings.apply(c, "filter_porn_hentai", "0.8")))
        assertFalse(FilterTighten.onlyTightens(c, RemoteSettings.apply(c, "filter_porn_hentai", "0.9")))
        assertFalse(FilterTighten.onlyTightens(c, RemoteSettings.apply(c, "night_start", "23:00")))
        assertEquals(-0.1f, RemoteSettings.strictness("stricter"))
    }
}
