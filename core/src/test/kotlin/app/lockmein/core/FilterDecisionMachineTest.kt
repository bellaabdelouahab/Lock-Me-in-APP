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

    /** Three agreeing frames half a second apart, the last at [at]: what makes the filter sure. Returns the last action. */
    private fun sure(m: FilterDecisionMachine, scores: FilterScores, at: Instant, app: String = pkg): FilterAction {
        m.processFrame(app, scores, at.minusMillis(1000))
        m.processFrame(app, scores, at.minusMillis(500))
        return m.processFrame(app, scores, at)
    }

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
        val action = sure(machine, hitScores, t0)
        assertIs<FilterAction.ShowWarning>(action)
        assertEquals(pkg, (action as FilterAction.ShowWarning).packageName)
        assertIs<FilterState.WarningActive>(machine.state)
        val warningState = machine.state as FilterState.WarningActive
        assertEquals(t0, warningState.hitStartedAt)
    }

    @Test
    fun `during the 5 second grace window user can interact and no second action fires`() {
        val hitScores = FilterScores(porn = 0.70f)
        sure(machine, hitScores, t0)

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
        val hitScores = FilterScores(hentai = 0.95f)
        sure(machine, hitScores, t0)

        // At t0 + 5 seconds: second check detects offensive content again
        val t5 = t0.plusSeconds(5)
        val action = machine.processFrame(pkg, FilterScores(porn = 0.65f), t5)
        assertIs<FilterAction.ExitAndSuspend>(action)
        val exitAction = action as FilterAction.ExitAndSuspend
        assertEquals(pkg, exitAction.packageName)
        assertEquals(Duration.ofMinutes(30), exitAction.duration)
        assertIs<FilterState.Idle>(machine.state)
    }

    @Test
    fun `after 5 seconds a clean check dismisses the warning and leaves the app running`() {
        val hitScores = FilterScores(sexy = 0.80f)
        sure(machine, hitScores, t0)

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
        sure(machine, hitScores, t0)

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
        val rule = FilterRule.from(0.60f, 0.60f, 0.80f, null)
        // Porn + hentai combined >= 0.60
        assertTrue(rule.isHit(FilterScores(porn = 0.35f, hentai = 0.25f)))
        assertFalse(rule.isHit(FilterScores(porn = 0.30f, hentai = 0.29f)))

        // Sexy >= 0.60
        assertTrue(rule.isHit(FilterScores(sexy = 0.60f)))
        assertFalse(rule.isHit(FilterScores(sexy = 0.59f)))

        // Drawings and neutral do not trigger hits
        assertFalse(rule.isHit(FilterScores(drawings = 0.95f, neutral = 0.05f)))
    }

    @Test
    fun `suggestive anime with clothes on is a hit, sexual words on screen alone are not`() {
        val ecchi = FilterScores(drawings = 0.7f, hentai = 0.2f, neutral = 0.1f, animeSuggestive = 0.85f)
        val rule = FilterRule.from(0.6f, 0.6f, 0.8f, null)
        assertTrue(rule.isHit(ecchi))
        assertFalse(rule.isHit(ecchi.copy(animeSuggestive = 0.5f)))
        assertFalse(FilterRule().isHit(FilterScores(textMatch = "Ecchi")))
        assertEquals(FilterAction.None, machine.processFrame(pkg, FilterScores(textMatch = "harem"), t0))
    }

    @Test
    fun `one hit frame shows nothing, three of the last four do`() {
        val hit = FilterScores(sexy = 0.80f)
        val clean = FilterScores(neutral = 0.95f)
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0))
        assertEquals(FilterAction.None, machine.processFrame(pkg, clean, t0.plusMillis(500)))
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0.plusMillis(1000)))
        assertIs<FilterState.Idle>(machine.state)
        assertIs<FilterAction.ShowWarning>(machine.processFrame(pkg, hit, t0.plusMillis(1500)))
    }

    @Test
    fun `a misread frame now and then never adds up to a warning`() {
        val hit = FilterScores(porn = 0.80f)
        val clean = FilterScores(neutral = 0.95f)
        var at = t0
        repeat(40) { i ->
            assertEquals(FilterAction.None, machine.processFrame(pkg, if (i % 3 == 0) hit else clean, at))
            at = at.plusMillis(500)
        }
    }

    @Test
    fun `hits spread over more than 6 seconds or across two apps do not agree`() {
        val hit = FilterScores(sexy = 0.80f)
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0))
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0.plusSeconds(4)))
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0.plusSeconds(8)))
        assertEquals(FilterAction.None, machine.processFrame("com.other", hit, t0.plusSeconds(9)))
        assertEquals(FilterAction.None, machine.processFrame(pkg, hit, t0.plusSeconds(10)))
    }

    @Test
    fun `a very strong frame acts at once`() {
        assertIs<FilterAction.ShowWarning>(machine.processFrame(pkg, FilterScores(porn = 0.90f, hentai = 0.06f), t0))
        val other = FilterDecisionMachine(pornHentaiThreshold = 0.6f, sexyThreshold = 0.6f)
        assertEquals(FilterAction.None, other.processFrame(pkg, FilterScores(porn = 0.90f), t0))
    }

    @Test
    fun `once warned, one hit frame at the 5-second check closes the app`() {
        sure(machine, FilterScores(sexy = 0.8f), t0)
        machine.processFrame(pkg, FilterScores(neutral = 0.9f), t0.plusSeconds(2))
        assertIs<FilterAction.ExitAndSuspend>(machine.processFrame(pkg, FilterScores(sexy = 0.8f), t0.plusSeconds(5)))
    }

    @Test
    fun `the seductive score only counts once it has a threshold`() {
        val seductive = FilterScores(seductive = 0.95f)
        assertFalse(FilterRule.from(0.6f, 0.6f, 0.8f, null).isHit(seductive))
        assertTrue(FilterRule.from(0.6f, 0.6f, 0.8f, 0.9f).isHit(seductive))
        assertEquals(FilterAction.None, machine.processFrame(pkg, seductive, t0))
        val live = FilterDecisionMachine(seductiveThreshold = 0.9f)
        assertIs<FilterAction.ShowWarning>(sure(live, seductive, t0))
    }
}
