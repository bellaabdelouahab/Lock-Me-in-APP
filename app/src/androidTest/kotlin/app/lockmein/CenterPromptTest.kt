package app.lockmein

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.PromptRecord
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The center prompt (spec N2): leaving the card is "Later", twice, and the third time it is logged as skipped. */
class CenterPromptTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val slot = LocalTime.of(7, 45)

    @Before
    fun clean() {
        context.getSharedPreferences("lock_prompt", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.noBackupFilesDir, "prompts.log").delete()
    }

    private fun showAndLeave() {
        val intent = Intent(context, PromptActivity::class.java).putExtra(PromptActivity.SLOT, slot.toString()).putExtra(PromptActivity.UNLOCK_MS, System.currentTimeMillis())
        ActivityScenario.launch<PromptActivity>(intent).use { it.moveToState(Lifecycle.State.CREATED) }
    }

    private fun record() = CenterPrompt.records(context).single { it.day == LocalDate.now() && it.slot == slot }

    @Test
    fun laterTwiceThenSkipped() {
        showAndLeave()
        assertEquals(1, record().snoozes)
        assertTrue("a later waits 15 minutes", record().snoozedUntilMs > System.currentTimeMillis() + 14 * 60_000)
        showAndLeave()
        assertEquals(2, record().snoozes)
        showAndLeave()
        assertEquals(PromptRecord.SKIPPED, record().result)
        Thread.sleep(500) // the log is written on its own thread
        assertEquals(listOf(PromptRecord.SKIPPED), PromptLog(context).read().map { it.result })
    }
}
