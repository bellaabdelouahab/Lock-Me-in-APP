package app.lockmein.core

import kotlin.math.abs

/**
 * Compares tiny 16x16 grayscale thumbnails to detect whether the screen content has changed.
 * This allows skipping inference when reading static pages, bringing battery cost near zero.
 */
object FrameDiff {
    const val THUMB_WIDTH = 16
    const val THUMB_HEIGHT = 16
    const val TOTAL_PIXELS = THUMB_WIDTH * THUMB_HEIGHT // 256

    /**
     * Calculates the normalized mean pixel difference between two 16x16 grayscale byte arrays.
     * Returns a float in [0.0, 1.0].
     */
    fun difference(thumb1: ByteArray, thumb2: ByteArray): Float {
        if (thumb1.size != TOTAL_PIXELS || thumb2.size != TOTAL_PIXELS) return 1.0f
        var diffSum = 0L
        for (i in 0 until TOTAL_PIXELS) {
            val v1 = thumb1[i].toInt() and 0xFF
            val v2 = thumb2[i].toInt() and 0xFF
            diffSum += abs(v1 - v2)
        }
        return diffSum.toFloat() / (TOTAL_PIXELS * 255f)
    }

    /**
     * Determines whether the screen has changed enough to warrant running on-device inference.
     * If the difference is below [threshold] (default 0.02 = 2% mean change), the frame is considered static.
     */
    fun hasChanged(lastThumb: ByteArray?, currentThumb: ByteArray, threshold: Float = 0.02f): Boolean {
        if (lastThumb == null) return true
        return difference(lastThumb, currentThumb) >= threshold
    }

    /**
     * Converts a 16x16 ARGB int array (as obtained from downscaling a bitmap) to a 16x16 grayscale ByteArray.
     * Standard luma formula: Y = 0.299*R + 0.587*G + 0.114*B.
     */
    fun rgbToGrayscale(pixels: IntArray): ByteArray {
        val bytes = ByteArray(TOTAL_PIXELS)
        val count = minOf(pixels.size, TOTAL_PIXELS)
        for (i in 0 until count) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            bytes[i] = gray.toByte()
        }
        return bytes
    }
}
