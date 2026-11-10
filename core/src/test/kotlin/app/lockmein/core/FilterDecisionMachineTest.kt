package app.lockmein.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilterDecisionMachineTest {
    private val machine = FilterDecisionMachine(
        pornHentaiThreshold = 0.60f,
        sexyThreshold = 0.60f,
        warningGraceDuration = Duration.ofSeconds(5),
        suspensionDuration = Duration.ofMinutes(30),
    )
    private val t0 = Instant.parse("2026-11-08T12:00:00Z")
    private val pkg = "com.brave.browser"

    @Test
    fun `neutral scores produce no action and keep state idle`() {
        val cleanScores = FilterScores(neutral = 0.95f, drawings = 0.05f)
        val action = machine.processFrame(pkg, cleanScores, t0)
        assertEquals(FilterAction.None, action)
        assertIs<FilterState.Idle>(machine.state)
    }

    @Test
    fun `first hit triggers warning overlay and records start time`() {
        val hitScores = FilterScores(porn = 0.70f, neutral = 0.30f)
        val action = machine.processFrame(pkg, hitScores, t0)
        assertIs<FilterAction.ShowWarning>(action)
        assertEquals(pkg, (action as FilterAction.ShowWarning).packageName)
        assertIs<FilterState.WarningActive>(machine.state)
        val warningState = machine.state as FilterState.WarningActive
        assertEquals(t0, warningState.hitStartedAt)
    }

    @Test
    fun `during the 5 second grace window user can interact and no second action fires`() {
        val hitScores = FilterScores(porn = 0.70f)
        machine.processFrame(pkg, hitScores, t0)

        // At t0 + 2 seconds: user scrolling away, still in warning state
        val t2 = t0.plusSeconds(2)
        val actionAt2 = machine.processFrame(pkg, FilterScores(sexy = 0.65f), t2)
        assertEquals(FilterAction.None, actionAt2)
        assertIs<FilterState.WarningActive>(machine.state)

        // At t0 + 4 seconds: still in warning grace window
        val t4 = t0.plusSeconds(4)
        val actionAt4 = machine.processFrame(pkg, FilterScores(porn = 0.70f), t4)
        assertEquals(FilterAction.None, actionAt4)
        assertIs<FilterState.WarningActive>(machine.state)
    }

    @Test
    fun `after 5 seconds a second hit triggers exit to home and 30 minute suspension`() {
        val hitScores = FilterScores(hentai = 0.65f)
        machine.processFrame(pkg, hitScores, t0)

        // At t0 + 5 seconds: second check detects offensive content again
        val t5 = t0.plusSeconds(5)
        val action = machine.processFrame(pkg, FilterScores(porn = 0.60f), t5)
        assertIs<FilterAction.ExitAndSuspend>(action)
        val exitAction = action as FilterAction.ExitAndSuspend
        assertEquals(pkg, exitAction.packageName)
        assertEquals(Duration.ofMinutes(30), exitAction.duration)
        assertIs<FilterState.Idle>(machine.state)
    }

    @Test
    fun `after 5 seconds a clean check dismisses the warning and leaves the app running`() {
        val hitScores = FilterScores(sexy = 0.80f)
        machine.processFrame(pkg, hitScores, t0)

        // At t0 + 5 seconds: user scrolled away to clean content
        val t5 = t0.plusSeconds(5)
        val cleanScores = FilterScores(neutral = 0.90f, drawings = 0.10f)
        val action = machine.processFrame(pkg, cleanScores, t5)
        assertIs<FilterAction.ClearWarning>(action)
        val clearAction = action as FilterAction.ClearWarning
        assertEquals(pkg, clearAction.packageName)
        assertIs<FilterState.Idle>(machine.state)
    }

    @Test
    fun `switching apps during the 5s warning clears the warning on the old app`() {
        val hitScores = FilterScores(porn = 0.75f)
        machine.processFrame(pkg, hitScores, t0)

        // User switches to home or another clean app at t0 + 2s
        val otherPkg = "com.android.calculator"
        val t2 = t0.plusSeconds(2)
        val action = machine.processFrame(otherPkg, FilterScores(neutral = 1.0f), t2)
        assertIs<FilterAction.ClearWarning>(action)
        assertEquals(pkg, (action as FilterAction.ClearWarning).packageName)
        assertIs<FilterState.Idle>(machine.state)
    }

    @Test
    fun `threshold rules work for combined porn and hentai and for sexy`() {
        // Porn + hentai combined >= 0.60
        assertTrue(FilterScores(porn = 0.35f, hentai = 0.25f).isHit(0.60f, 0.60f))
        assertFalse(FilterScores(porn = 0.30f, hentai = 0.29f).isHit(0.60f, 0.60f))

        // Sexy >= 0.60
        assertTrue(FilterScores(sexy = 0.60f).isHit(0.60f, 0.60f))
        assertFalse(FilterScores(sexy = 0.59f).isHit(0.60f, 0.60f))

        // Drawings and neutral do not trigger hits
        assertFalse(FilterScores(drawings = 0.95f, neutral = 0.05f).isHit(0.60f, 0.60f))
    }

    @Test
    fun `suggestive anime with clothes on is a hit, sexual words on screen alone are not`() {
        val ecchi = FilterScores(drawings = 0.7f, hentai = 0.2f, neutral = 0.1f, animeSuggestive = 0.85f)
        assertTrue(ecchi.isHit(0.6f, 0.6f, 0.8f))
        assertFalse(ecchi.copy(animeSuggestive = 0.5f).isHit(0.6f, 0.6f, 0.8f))
        assertFalse(FilterScores(textMatch = "Ecchi").isHit())
        assertEquals(FilterAction.None, machine.processFrame(pkg, FilterScores(textMatch = "harem"), t0))
    }

    @Test
    fun `three borderline frames within 6 seconds are a hit, scattered ones are not`() {
        val borderline = FilterScores(sexy = 0.50f) // 0.83 of the 0.60 threshold
        assertEquals(FilterAction.None, machine.processFrame(pkg, borderline, t0))
        assertEquals(FilterAction.None, machine.processFrame(pkg, borderline, t0.plusSeconds(10)))
        assertEquals(FilterAction.None, machine.processFrame(pkg, borderline, t0.plusSeconds(20)))
        assertEquals(FilterAction.None, machine.processFrame(pkg, borderline, t0.plusSeconds(21)))
        assertIs<FilterAction.ShowWarning>(machine.processFrame(pkg, borderline, t0.plusSeconds(22)))
        // The second check needs its own evidence: one borderline frame after the warning is not enough.
        assertIs<FilterAction.ClearWarning>(machine.processFrame(pkg, borderline, t0.plusSeconds(27)))
    }

    @Test
    fun `the seductive score only counts once it has a threshold`() {
        val seductive = FilterScores(seductive = 0.95f)
        assertFalse(seductive.isHit(0.6f, 0.6f, 0.8f))
        assertTrue(seductive.isHit(0.6f, 0.6f, 0.8f, seductiveThreshold = 0.9f))
        assertEquals(FilterAction.None, machine.processFrame(pkg, seductive, t0))
        val live = FilterDecisionMachine(seductiveThreshold = 0.9f)
        assertIs<FilterAction.ShowWarning>(live.processFrame(pkg, seductive, t0))
    }
}
