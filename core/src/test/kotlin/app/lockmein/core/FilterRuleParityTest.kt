package app.lockmein.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shipped rule must decide exactly as the rule that was measured (docs/FILTER-EVAL.md): 400 real tile scores from the
 * evaluation (200 hits, 200 not), with the answer the evaluation gave each one.
 */
class FilterRuleParityTest {
    @Test
    fun `the shipped rule matches the measured rule on 400 real tiles`() {
        val t = FilterRule.TUNED
        val rule = FilterRule.from(t.photoPornHentai, t.photoSexy, t.drawnSuggestive, t.photoSeductive)
        val lines = javaClass.getResource("/filter-rule-parity.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
        assertEquals(400, lines.size)
        val wrong = lines.filter { line ->
            val v = line.split(',').map { it.toFloat() }
            val s = FilterScores(drawings = v[0], hentai = v[1], neutral = v[2], porn = v[3], sexy = v[4], animeSuggestive = v[5], animeExplicit = v[6], seductive = v[7])
            rule.isHit(s) != (v[8] == 1f)
        }
        assertEquals(emptyList(), wrong, "tiles decided differently from the evaluation")
    }

    @Test
    fun `strong porn on one frame acts, a man's face read as porn once does not`() {
        val m = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f)
        val t0 = java.time.Instant.parse("2026-11-22T12:00:00Z")
        assertEquals(FilterAction.None, m.processFrame("yt", FilterScores(porn = 0.88f, neutral = 0.1f), t0))
        assert(m.processFrame("yt2", FilterScores(porn = 0.97f), t0.plusSeconds(1)) is FilterAction.ShowWarning)
    }
}
