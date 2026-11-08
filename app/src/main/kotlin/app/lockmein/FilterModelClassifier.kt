package app.lockmein

import android.content.Context
import android.graphics.Bitmap
import app.lockmein.core.FilterScores
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device NSFW image classifier wrapping GantMan's MobileNetV2 TFLite model.
 * Input: 224x224 RGB float32 normalized to [0.0, 1.0].
 * Output: Softmax distribution over [drawings, hentai, neutral, porn, sexy].
 * Dropping the input bitmap right after pixel extraction guarantees no image is ever saved or logged.
 */
class FilterModelClassifier(private val context: Context) : AutoCloseable {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val inputBuffer = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * CHANNELS * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }
    private val pixelArray = IntArray(INPUT_SIZE * INPUT_SIZE)

    init {
        loadModel()
    }

    private fun loadModel() {
        val mappedBuffer = runCatching {
            context.assets.openFd(MODEL_PATH).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }.getOrNull() ?: return

        val options = Interpreter.Options().apply {
            setNumThreads(2)
            runCatching {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                    addDelegate(delegate)
                    gpuDelegate = delegate
                }
            }
        }

        interpreter = runCatching { Interpreter(mappedBuffer, options) }.getOrElse {
            // Fallback to CPU without GPU delegate if GPU initialization fails
            gpuDelegate?.close()
            gpuDelegate = null
            Interpreter(mappedBuffer, Interpreter.Options().apply { setNumThreads(2) })
        }
    }

    val isReady: Boolean get() = interpreter != null

    /**
     * Runs inference on the given [bitmap].
     * The caller is responsible for recycling or dropping the bitmap immediately.
     */
    @Synchronized
    fun predict(bitmap: Bitmap): FilterScores {
        val interp = interpreter ?: return FilterScores()

        val scaled = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }

        scaled.getPixels(pixelArray, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        inputBuffer.rewind()
        for (pixel in pixelArray) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        interp.run(inputBuffer, outputBuffer)
        val raw = outputBuffer[0]

        // Output order in GantMan's Keras/TFLite export:
        // [0: drawings, 1: hentai, 2: neutral, 3: porn, 4: sexy]
        return FilterScores(
            drawings = raw.getOrElse(0) { 0f },
            hentai = raw.getOrElse(1) { 0f },
            neutral = raw.getOrElse(2) { 1f },
            porn = raw.getOrElse(3) { 0f },
            sexy = raw.getOrElse(4) { 0f },
        )
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }

    companion object {
        const val MODEL_PATH = "nsfw_mobilenet_v2.tflite"
        const val INPUT_SIZE = 224
        const val CHANNELS = 3
        const val BYTES_PER_FLOAT = 4
        const val NUM_CLASSES = 5

        fun isModelAvailable(context: Context): Boolean = runCatching {
            context.assets.open(MODEL_PATH).use { true }
        }.getOrDefault(false)
    }
}
