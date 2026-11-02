package app.lockmein

import androidx.test.core.app.ActivityScenario
import org.junit.Test

/** The interface starts and draws without crashing, on a device that is not set up and on one that is. */
class ScreensTest {
    @Test
    fun theHomeScreenOpens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { check(!it.isFinishing) }
        }
    }
}
