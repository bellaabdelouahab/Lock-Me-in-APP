package app.lockmein

import android.app.AlarmManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase changes (fajr, night, the windows) run on exact alarms. Without the permission Android delays them: on 2026-11-28
 * fajr's pass had not run 17 minutes later and the night's apps stayed closed. This fails the build if exact alarms are refused.
 */
class ExactAlarmTest {
    @Test
    fun theAppMaySetExactAlarms() {
        val alarms = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AlarmManager::class.java)
        assertTrue("exact alarms refused: the phase changes would come late", alarms.canScheduleExactAlarms())
    }
}
