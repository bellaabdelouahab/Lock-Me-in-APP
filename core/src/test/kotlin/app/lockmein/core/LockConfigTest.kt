package app.lockmein.core

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LockConfigTest {
    private val config = LockConfig(
        dnsHost = "abc123.dns.nextdns.io",
        allowedBrowser = "com.brave.browser",
        nightAllowlist = setOf("deezer.android.app"),
        alwaysBlocked = setOf("com.instagram.android"),
    )

    @Test
    fun `a configuration survives being saved and loaded`() {
        assertEquals(config, ConfigCodec.decode(ConfigCodec.encode(config)))
    }

    @Test
    fun `the defaults describe the agreed rhythm`() {
        val schedule = config.scheduleConfig()
        assertEquals(LocalTime.of(22, 0), schedule.nightStart)
        assertEquals(DayOfWeek.SUNDAY, schedule.windowDay)
        assertEquals(LocalTime.of(20, 0), schedule.windowStart)
        assertEquals(LocalTime.of(22, 0), schedule.windowEnd)
        assertEquals(19.0, config.fajrConfig().sunAngleDegrees)
    }

    @Test
    fun `text that is not a configuration is refused with a plain message`() {
        assertEquals("This is not a Lock Me In configuration.", assertFailsWith<IllegalArgumentException> { ConfigCodec.decode("hello") }.message)
        assertFailsWith<IllegalArgumentException> { ConfigCodec.decode("{}") }
    }

    @Test
    fun `an imported file with a broken lock is refused`() {
        fun refused(change: LockConfig.() -> LockConfig) = assertFailsWith<IllegalArgumentException> { ConfigCodec.decode(ConfigCodec.encode(config.change())) }.message!!
        assertTrue(refused { copy(dnsHost = "not a host") }.isNotBlank())
        assertTrue("time" in refused { copy(nightStart = "late") })
        assertTrue("end after" in refused { copy(windowStart = "21:00", windowEnd = "20:00") })
        assertTrue("Monday" in refused { copy(windowDay = 9) })
        assertTrue("wait" in refused { copy(browserWaitMinutes = 0) })
        assertTrue("both" in refused { copy(alwaysBlocked = setOf("deezer.android.app")) })
        assertTrue("allowed browser" in refused { copy(alwaysBlocked = setOf("com.brave.browser")) })
        assertTrue("poles" in refused { copy(latitude = 80.0) })
    }

    @Test
    fun `unknown keys from a newer version are ignored`() {
        val text = ConfigCodec.encode(config).replaceFirst("{", "{ \"futureSetting\": 1,")
        assertEquals(config, ConfigCodec.decode(text))
    }

    @Test
    fun `a good configuration has no problems`() {
        assertEquals(emptyList(), ConfigRules.problems(config))
    }

    @Test
    fun `the config holds nothing secret`() {
        val text = ConfigCodec.encode(config).lowercase()
        for (word in listOf("secret", "password", "totp", "code")) assertTrue(word !in text, word)
    }

    @Test
    fun `dns hosts are checked and tidied`() {
        assertEquals("poco-6b1bbb.dns.nextdns.io", DnsHost.normalise(" poco-6b1bbb.dns.nextdns.io "))
        assertEquals("6b1bbb.dns.nextdns.io", DnsHost.normalise("6b1bbb"))
        assertEquals("dns.google", DnsHost.normalise("https://dns.google/"))
        assertEquals("one.one.one.one", DnsHost.normalise("TLS://One.One.One.One"))
        assertNull(DnsHost.normalise(""))
        assertNull(DnsHost.normalise("dns"))
        assertNull(DnsHost.normalise("1.1.1.1"))
        assertNull(DnsHost.normalise("host with space.example"))
        assertNull(DnsHost.normalise("dns.google:853"))
        assertNull(DnsHost.normalise("-bad.example.com"))
    }
}
