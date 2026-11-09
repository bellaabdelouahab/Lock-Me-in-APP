package app.lockmein

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * An ONNX image model from the assets that turns a picture into one score. Input is RGB, CHW, size x size, each channel
 * mapped by [pixel] from 0..1; [score] reads the model's first output. The bitmap is only read, never kept.
 */
class OnnxImageScorer(
    context: Context,
    path: String,
    private val size: Int,
    private val pixel: (Float) -> Float,
    private val score: (FloatArray) -> Float,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = runCatching {
        // Loaded from a file, not a byte array: the seductive model is 88 MB and two copies in the app's memory could crash it.
        env.createSession(unpacked(context, path).path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
    }.getOrNull()
    private val inputName = session?.inputNames?.first()
    private val pixels = IntArray(size * size)
    private val input = FloatBuffer.allocate(3 * size * size)

    val isReady get() = session != null

    /** The score, or 0 when the model is missing. */
    @Synchronized
    fun score(bitmap: Bitmap): Float {
        val s = session ?: return 0f
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        if (scaled !== bitmap) scaled.recycle()
        val plane = size * size
        for (i in pixels.indices) {
            val p = pixels[i]
            input.put(i, pixel((p shr 16 and 0xFF) / 255f))
            input.put(plane + i, pixel((p shr 8 and 0xFF) / 255f))
            input.put(2 * plane + i, pixel((p and 0xFF) / 255f))
        }
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            s.run(mapOf(inputName to tensor)).use { out ->
                @Suppress("UNCHECKED_CAST")
                return score((out[0].value as Array<FloatArray>)[0])
            }
        }
    }

    override fun close() {
        session?.close()
    }

    companion object {
        /** The asset copied to app storage once per app install or update. */
        private fun unpacked(context: Context, path: String): java.io.File {
            val file = java.io.File(context.noBackupFilesDir, path)
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            if (!file.exists() || file.lastModified() < installed) {
                val tmp = java.io.File(file.path + ".tmp")
                context.assets.open(path).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
            }
            return file
        }

        /**
         * deepghs anime_rating (MobileNetV3, MIT): rates a drawing safe / r15 / r18; returns r15 + r18. r15 is fanservice and
         * suggestive art with clothes on. Input 384, (x - 0.5) / 0.5. Output is already softmax.
         */
        /**
         * Seductive looks, poses and framing (photos and drawings): a CLIP image encoder with a linear judge on top, exported as
         * one graph that takes 0..1 RGB (its own normalisation is inside) and returns the probability.
         */
        fun seductive(context: Context) = OnnxImageScorer(context, "seductive.onnx", 224, { it }) { it[0] }

        fun animeRating(context: Context) = OnnxImageScorer(context, "anime_rating.onnx", 384, { (it - 0.5f) / 0.5f }) { it[1] + it[2] }
    }
}
