package app.lockmein

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The anime model loads on the phone and reads pixels exactly as the server evaluation did. */
class AnimeModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theAnimeModelGivesTheSameScoreAsTheServerEvaluation() {
        val n = 384
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        for (y in 0 until n) for (x in 0 until n) bitmap.setPixel(x, y, Color.rgb(x * 255 / (n - 1), y * 255 / (n - 1), 128))
        AnimeRatingClassifier(context).use { model ->
            assertTrue("model loads from the APK", model.isReady)
            // Reference from onnxruntime on the server for the same gradient image: r15 + r18 = 0.1012.
            assertEquals(0.1012f, model.suggestive(bitmap), 0.01f)
        }
    }
}
