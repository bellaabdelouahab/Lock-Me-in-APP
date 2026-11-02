package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Base32Test {
    // RFC 4648 section 10 test vectors (padding left off).
    private val vectors = mapOf("" to "", "f" to "MY", "fo" to "MZXQ", "foo" to "MZXW6", "foob" to "MZXW6YQ", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI")

    @Test
    fun `encodes the RFC vectors`() {
        for ((plain, encoded) in vectors) assertEquals(encoded, Base32.encode(plain.toByteArray()))
    }

    @Test
    fun `decodes the RFC vectors`() {
        for ((plain, encoded) in vectors) assertContentEquals(plain.toByteArray(), Base32.decode(encoded))
    }

    @Test
    fun `decoding tolerates the way a secret is copied`() {
        assertContentEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb-oi=="))
    }

    @Test
    fun `decoding refuses characters that are not base32`() {
        assertNull(Base32.decode("MZXW1"))
        assertNull(Base32.decode("hello!"))
    }

    @Test
    fun `any secret round-trips`() {
        val secret = ByteArray(20) { (it * 37 + 11).toByte() }
        assertContentEquals(secret, Base32.decode(Base32.encode(secret)))
    }
}
