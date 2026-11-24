package app.lockmein.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The fixtures in resources/delta were made by scripts/delta.py, so this checks the phone reads what CI writes. */
class DeltaPatchTest {
    private fun res(name: String) = File(javaClass.getResource("/delta/$name")!!.toURI())

    @Test
    fun rebuildsTheNewFileFromTheOldOneAndAPatchMadeByTheScript() {
        val out = ByteArrayOutputStream()
        var last = 0f
        DeltaPatch.apply(res("old.bin"), res("patch.bin"), out) { last = it }
        assertContentEquals(res("new.bin").readBytes(), out.toByteArray())
        assertEquals(1f, last)
    }

    @Test
    fun refusesAnotherOldFileBeforeWritingAnything() {
        val other = File.createTempFile("other", ".bin").apply { writeBytes(res("old.bin").readBytes().also { it[5] = (it[5] + 1).toByte() }); deleteOnExit() }
        val out = ByteArrayOutputStream()
        assertFailsWith<IllegalArgumentException> { DeltaPatch.apply(other, res("patch.bin"), out) }
        assertEquals(0, out.size())
    }

    @Test
    fun aDamagedPatchNeverPassesAsTheNewFile() {
        val bytes = res("patch.bin").readBytes()
        // Flip a byte in each part of the patch body, and cut the file short: each must fail, never hand back a wrong APK.
        for (at in listOf(bytes.size - 1, bytes.size / 2, 140)) {
            val bad = File.createTempFile("bad", ".patch").apply { writeBytes(bytes.copyOf().also { it[at] = (it[at].toInt() xor 0x40).toByte() }); deleteOnExit() }
            assertFailsWith<Exception>("byte $at") { DeltaPatch.apply(res("old.bin"), bad, ByteArrayOutputStream()) }
        }
        val short = File.createTempFile("short", ".patch").apply { writeBytes(bytes.copyOf(bytes.size - 10)); deleteOnExit() }
        assertFailsWith<IllegalArgumentException> { DeltaPatch.header(short) }
    }
}
