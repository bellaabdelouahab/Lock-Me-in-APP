package app.lockmein

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * deepghs anime_rating (MobileNetV3, MIT): rates a drawing safe / r15 / r18. r15 is fanservice and suggestive art with clothes
 * on, the level the first model misses. Input 384x384 RGB, (x / 255 - 0.5) / 0.5, CHW. The bitmap is only read, never kept.
 */
class AnimeRatingClassifier(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = runCatching {
        env.createSession(context.assets.open(MODEL_PATH).use { it.readBytes() }, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    }.getOrNull()
    private val pixels = IntArray(SIZE * SIZE)
    private val input = FloatBuffer.allocate(3 * SIZE * SIZE)

    val isReady get() = session != null

    /** r15 + r18, or 0 when the model is missing. */
    @Synchronized
    fun suggestive(bitmap: Bitmap): Float {
        val s = session ?: return 0f
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
        input.rewind()
        val plane = SIZE * SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            input.put(i, ((p shr 16 and 0xFF) / 255f - 0.5f) / 0.5f)
            input.put(plane + i, ((p shr 8 and 0xFF) / 255f - 0.5f) / 0.5f)
            input.put(2 * plane + i, ((p and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            s.run(mapOf("input" to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val p = (out[0].value as Array<FloatArray>)[0] // safe, r15, r18 (already softmax)
                return p[1] + p[2]
            }
        }
    }

    override fun close() {
        session?.close()
    }

    companion object {
        const val MODEL_PATH = "anime_rating.onnx"
        private const val SIZE = 384
    }
}
