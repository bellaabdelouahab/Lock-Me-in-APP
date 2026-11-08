package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameDiffTest {
    @Test
    fun `identical thumbnails have zero difference and report unchanged`() {
        val thumb1 = ByteArray(FrameDiff.TOTAL_PIXELS) { (it % 256).toByte() }
        val thumb2 = thumb1.copyOf()

        val diff = FrameDiff.difference(thumb1, thumb2)
        assertEquals(0f, diff, 0.0001f)
        assertFalse(FrameDiff.hasChanged(thumb1, thumb2))
    }

    @Test
    fun `first frame always reports hasChanged true`() {
        val thumb = ByteArray(FrameDiff.TOTAL_PIXELS) { 128.toByte() }
        assertTrue(FrameDiff.hasChanged(null, thumb))
    }

    @Test
    fun `small difference below 2 percent is treated as unchanged static frame`() {
        val thumb1 = ByteArray(FrameDiff.TOTAL_PIXELS) { 100.toByte() }
        // Change only 2 pixels out of 256
        val thumb2 = thumb1.copyOf().apply {
            this[0] = 120.toByte()
            this[1] = 120.toByte()
        }
        val diff = FrameDiff.difference(thumb1, thumb2)
        assertTrue(diff < 0.02f)
        assertFalse(FrameDiff.hasChanged(thumb1, thumb2, threshold = 0.02f))
    }

    @Test
    fun `scrolling or visual content changes above threshold reports changed`() {
        val thumb1 = ByteArray(FrameDiff.TOTAL_PIXELS) { 50.toByte() }
        val thumb2 = ByteArray(FrameDiff.TOTAL_PIXELS) { 80.toByte() } // ~11.7% change across screen
        val diff = FrameDiff.difference(thumb1, thumb2)
        assertTrue(diff > 0.02f)
        assertTrue(FrameDiff.hasChanged(thumb1, thumb2, threshold = 0.02f))
    }

    @Test
    fun `rgbToGrayscale converts colors using luma weights`() {
        // Red pixel: (255, 0, 0) -> gray = 0.299 * 255 = ~76
        val redPixels = IntArray(FrameDiff.TOTAL_PIXELS) { 0x00FF0000 }
        val gray = FrameDiff.rgbToGrayscale(redPixels)
        assertEquals(76, gray[0].toInt() and 0xFF)

        // White pixel: (255, 255, 255) -> 255
        val whitePixels = IntArray(FrameDiff.TOTAL_PIXELS) { 0x00FFFFFF }
        val whiteGray = FrameDiff.rgbToGrayscale(whitePixels)
        assertEquals(255, whiteGray[0].toInt() and 0xFF)
    }
}
