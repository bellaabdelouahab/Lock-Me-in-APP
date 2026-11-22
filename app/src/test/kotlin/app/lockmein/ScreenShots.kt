package app.lockmein

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Draws every screen of the preview build (sample data, nothing enforced) to a PNG on the computer, with no emulator or phone,
 * so a design change can be looked at in about a minute. Run with `scripts/shots.sh`. The same shots the CI emulator takes.
 * Only for looking at: it checks nothing and is not part of the ship gate.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Conscrypt has no Linux ARM build (this server); the screens make no network calls.
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenShots(private val name: String, private val extras: Map<String, Any>) {

    @Test
    fun shot() {
        assumeTrue("screens are drawn from the preview build only (-Ppreview=true)", BuildConfig.PREVIEW)
        val intent = Intent(Intent.ACTION_MAIN).setClassName("app.lockmein.preview", "app.lockmein.MainActivity")
        // The phone follows the system theme: some screens are drawn in dark mode too.
        if (extras["dark"] == true) org.robolectric.RuntimeEnvironment.setQualifiers("+night")
        extras.filterKeys { it != "dark" }.forEach { (k, v) ->
            when (v) {
                is Int -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                else -> intent.putExtra(k, v.toString())
            }
        }
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        // Let the screen settle: background checks (they run off the main thread, so real time has to pass), effects such as
        // opening the drawer, and their animations.
        repeat(60) {
            Thread.sleep(25)
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun shots(): List<Array<Any>> = buildList {
            for (n in 0..7) add(arrayOf("setup-$n", mapOf("step" to n)))
            for (s in listOf("HOME", "HEALTH", "WINDOW", "LISTS", "BROWSERS", "EMERGENCY", "REPORT", "SETTINGS", "REMOVE", "ABOUT")) {
                add(arrayOf(s.lowercase(), mapOf("screen" to s, "demo" to true)))
            }
            add(arrayOf("lists-window-apps", mapOf("screen" to "LISTS", "tab" to 2, "demo" to true)))
            add(arrayOf("lists-watched", mapOf("screen" to "LISTS", "tab" to 3, "demo" to true)))
            add(arrayOf("drawer", mapOf("screen" to "HOME", "demo" to true, "openDrawer" to true)))
            add(arrayOf("home-dark", mapOf("screen" to "HOME", "demo" to true, "dark" to true)))
            add(arrayOf("lists-dark", mapOf("screen" to "LISTS", "demo" to true, "dark" to true)))
            for (s in listOf("HEALTH", "WINDOW", "EMERGENCY", "REPORT", "SETTINGS", "BROWSERS")) {
                add(arrayOf("${s.lowercase()}-dark", mapOf("screen" to s, "demo" to true, "dark" to true)))
            }
            add(arrayOf("phone-use", mapOf("screen" to "PHONE_USE", "demo" to true)))
            add(arrayOf("phone-use-dark", mapOf("screen" to "PHONE_USE", "demo" to true, "dark" to true)))
            add(arrayOf("setup-0-dark", mapOf("step" to 0, "dark" to true)))
            add(arrayOf("setup-3-dark", mapOf("step" to 3, "dark" to true)))
            add(arrayOf("drawer-dark", mapOf("screen" to "HOME", "demo" to true, "openDrawer" to true, "dark" to true)))
        }
    }
}
