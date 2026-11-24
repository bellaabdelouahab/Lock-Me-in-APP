package app.lockmein.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTest {
    private val t0 = Instant.parse("2026-11-22T12:00:00Z")
    private val pkg = "com.brave.browser"
    private fun base() = LockConfig(dnsHost = "abc.dns.nextdns.io")

    private fun sure(m: FilterDecisionMachine, s: FilterScores, at: Instant = t0, app: String = pkg): FilterAction {
        m.processFrame(app, s, at.minusMillis(1000))
        m.processFrame(app, s, at.minusMillis(500))
        return m.processFrame(app, s, at)
    }

    // --- per-app thresholds (F2) ---

    @Test
    fun `a stricter app catches what the default does not, a looser app lets it pass`() {
        val photo = FilterScores(porn = 0.80f, neutral = 0.2f) // under the tuned 0.85
        val strict = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f, appOffsets = mapOf(pkg to -0.1f))
        assertIs<FilterAction.ShowWarning>(sure(strict, photo))
        assertIs<FilterAction.None>(sure(strict, photo, app = "other.app"))

        val strong = FilterScores(porn = 0.90f, neutral = 0.1f)
        val loose = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f, appOffsets = mapOf(pkg to 0.1f))
        assertIs<FilterAction.None>(sure(loose, strong))
    }

    @Test
    fun `shifting keeps thresholds in range and leaves switched-off paths off`() {
        val r = FilterRule.TUNED.shifted(0.3f)
        assertEquals(1f, r.photoPornHentai)
        assertEquals(2f, r.photoBacking)
        assertEquals(0.05f, FilterRule.TUNED.shifted(-0.9f).drawnBacking)
    }

    // --- the judge (F5) ---

    private val judge = FilterJudge("j1", List(FilterJudge.FEATURES.size) { i -> if (FilterJudge.FEATURES[i] == "seductive") -10f else 0f }, bias = 5f, cutoff = 0.9f)

    @Test
    fun `the judge drops a hit it is sure is a false alarm and keeps the rest`() {
        val fight = FilterScores(drawings = 0.9f, animeExplicit = 0.6f, seductive = 0.1f, neutral = 0.05f)
        assertTrue(judge.falseAlarm(fight) > 0.98f)
        val m = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f, judge = judge)
        assertIs<FilterAction.None>(sure(m, fight))

        val pose = FilterScores(drawings = 0.9f, animeExplicit = 0.6f, seductive = 0.9f, neutral = 0.05f)
        assertTrue(judge.falseAlarm(pose) < 0.02f)
        assertIs<FilterAction.ShowWarning>(sure(FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f, judge = judge), pose))
    }

    @Test
    fun `the judge never overrules a very strong frame`() {
        val strong = FilterScores(porn = 0.97f, neutral = 0.03f, seductive = 0f)
        val m = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f, judge = judge)
        assertIs<FilterAction.ShowWarning>(m.processFrame(pkg, strong, t0))
    }

    @Test
    fun `a broken judge is refused`() {
        assertNotNull(judge.copy(weights = listOf(1f)).problem())
        assertNotNull(judge.copy(cutoff = 0.2f).problem())
        assertNull(judge.problem())
        assertTrue(ConfigRules.problems(base().copy(filterJudge = judge.copy(weights = listOf(1f)))).isNotEmpty())
        assertTrue(ConfigRules.problems(base().copy(filterAppOffsets = mapOf(pkg to 0.5f))).isNotEmpty())
    }

    @Test
    fun `the judge on the phone gives the same answer as scripts judge train py`() {
        // Weights and expected values from a run of train.py (synthetic labels).
        val j = FilterJudge(
            "judge-2026-11-22",
            listOf(-1.027988f, -0.064952f, -1.334014f, 1.766147f, 0.662125f, -3.922284f, 1.121396f, -7.208065f, 1.250394f, -1.09294f),
            bias = 5.144741f, cutoff = 0.995f,
        )
        val fight = FilterScores(porn = 0.02f, hentai = 0.3f, sexy = 0.02f, drawings = 0.6f, neutral = 0.06f, animeSuggestive = 0.9f, animeExplicit = 0.6f, seductive = 0.1f)
        val photo = FilterScores(porn = 0.5f, hentai = 0.1f, sexy = 0.3f, drawings = 0.1f, neutral = 0.1f, seductive = 0.8f)
        assertEquals(0.970689f, j.falseAlarm(fight), 1e-4f)
        assertEquals(0.123972f, j.falseAlarm(photo), 1e-4f)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(FilterJudge.serializer(), """{"version":"v","weights":[${j.weights.joinToString()}],"bias":5.144741,"cutoff":0.995}""")
        assertEquals(j.weights, decoded.weights)
    }

    @Test
    fun `the export opens with its passphrase and not without`() {
        val data = "blocks".toByteArray()
        val sealed = Bundle.seal(data, "ABCD-EFGH")
        assertEquals("blocks", String(Bundle.open(sealed, "ABCD-EFGH")))
        assertTrue(runCatching { Bundle.open(sealed, "wrong") }.isFailure)
        System.getenv("LMI_BUNDLE_OUT")?.let { java.io.File(it).writeBytes(Bundle.seal("hello from kotlin".toByteArray(), "ABCD-EFGH")) }
    }

    // --- tighten or loosen ---

    @Test
    fun `only stricter filter settings skip the request`() {
        val c = base()
        assertTrue(FilterTighten.onlyTightens(c, c.copy(filterPornHentaiThreshold = 0.8f)))
        assertTrue(FilterTighten.onlyTightens(c, c.copy(filterAppOffsets = mapOf(pkg to -0.1f))))
        assertFalse(FilterTighten.onlyTightens(c, c.copy(filterAppOffsets = mapOf(pkg to 0.1f))))
        assertFalse(FilterTighten.onlyTightens(c, c.copy(filterPornHentaiThreshold = 0.9f)))
        assertFalse(FilterTighten.onlyTightens(c, c.copy(filterJudge = judge)))
        assertTrue(FilterTighten.onlyTightens(c.copy(filterJudge = judge), c))
        assertFalse(FilterTighten.onlyTightens(c, c.copy(filterPornHentaiThreshold = 0.8f, nightStart = "23:00")))
        assertFalse(FilterTighten.onlyTightens(c, c))
    }

    // --- the frames that agreed (F3) ---

    @Test
    fun `the warning remembers the frames that agreed`() {
        val m = FilterDecisionMachine(0.85f, 0.85f, 0.60f, 0.75f)
        val hit = FilterScores(porn = 0.9f, neutral = 0.1f)
        m.processFrame(pkg, FilterScores(), t0.minusMillis(1500))
        sure(m, hit)
        assertEquals(listOf(false, true, true, true), m.agreedFrames.map { it.second })
    }

    // --- the queue (F3, F4) ---

    private fun rec(i: Int, label: BlockLabel = BlockLabel.NONE) =
        BlockRecord("r$i", t0.toEpochMilli() + i, pkg, StoredScores(porn = 0.9f), label = label, hasImage = label == BlockLabel.CLAIMED_FALSE)

    @Test
    fun `the queue keeps 500 and drops plain blocks before saved ones`() {
        val saved = rec(0, BlockLabel.CLAIMED_FALSE)
        var list = listOf(saved) + (1 until Feedback.MAX_RECORDS).map { rec(it) }
        val (kept, dropped) = Feedback.add(list, rec(999))
        assertEquals(Feedback.MAX_RECORDS, kept.size)
        assertEquals(listOf("r1"), dropped)
        assertTrue(kept.any { it.id == "r0" })
        list = kept
        assertEquals(Feedback.MAX_RECORDS, list.size)
    }

    @Test
    fun `both labels keep the image for training`() {
        val list = listOf(rec(1, BlockLabel.CLAIMED_FALSE), rec(2, BlockLabel.CLAIMED_FALSE))
        val a = Feedback.label(Feedback.label(list, "r1", BlockLabel.CORRECT, t0), "r2", BlockLabel.WRONG, t0)
        assertEquals(BlockLabel.CORRECT, a[0].label)
        assertTrue(a[0].hasImage)
        assertTrue(a[1].hasImage)
        assertEquals(a, Feedback.decode(Feedback.encode(a)))
    }

    // --- the review window (F4) ---

    @Test
    fun `the review window opens at 19 30 for half an hour`() {
        val z = ZoneOffset.UTC
        assertFalse(ReviewWindow.isOpen(Instant.parse("2026-11-22T19:29:59Z"), z))
        assertTrue(ReviewWindow.isOpen(Instant.parse("2026-11-22T19:30:00Z"), z))
        assertFalse(ReviewWindow.isOpen(Instant.parse("2026-11-22T20:00:00Z"), z))
        assertEquals(Instant.parse("2026-11-22T19:30:00Z"), ReviewWindow.nextOpening(Instant.parse("2026-11-22T08:00:00Z"), z).toInstant())
        assertEquals(Instant.parse("2026-11-23T19:30:00Z"), ReviewWindow.nextOpening(Instant.parse("2026-11-22T21:00:00Z"), z).toInstant())
    }

    // --- eval mode (F2) ---

    @Test
    fun `eval names the app from the screenshot and counts false closes and misses`() {
        assertEquals("com.brave.browser", EvalReport.packageOf("Screenshot_2026-11-20-21-03-11-123_com.brave.browser.jpg"))
        assertEquals("unknown app", EvalReport.packageOf("IMG_0001.jpg"))
        val items = List(100) { EvalItem("n$it.jpg", false, FilterScores(), closed = it == 0) } +
            List(10) { EvalItem("r$it.jpg", true, FilterScores(porn = 0.9f), closed = it < 8) }
        val r = EvalReport(items)
        assertEquals(1, r.normal.closed)
        assertTrue(r.meetsTarget)
        assertEquals(2, r.misses.size)
        assertTrue(EvalReport.isRiskyFolder("Risky"))
        assertTrue(EvalReport.isNormalFolder("normal screens"))
    }

    // --- tries on closed apps ---

    @Test
    fun `tries on closed apps are counted per app and in all`() {
        val zone = ZoneOffset.UTC
        val events = listOf(
            UseEvent(Instant.parse("2026-11-22T08:00:00Z"), UseKind.BLOCKED, "com.instagram.android"),
            UseEvent(Instant.parse("2026-11-22T08:01:00Z"), UseKind.BLOCKED, "com.instagram.android"),
            UseEvent(Instant.parse("2026-11-22T09:00:00Z"), UseKind.BLOCKED, ""),
        )
        val d = PhoneUse.summarize(events, LocalDate.parse("2026-11-22"), zone, Instant.parse("2026-11-22T12:00:00Z"))
        assertEquals(3, d.blockedTries)
        assertEquals(2, d.apps.first { it.pkg == "com.instagram.android" }.tries)
        assertEquals(UseEvent(events[0].at, UseKind.BLOCKED, "com.instagram.android"), UseLines.decode(UseLines.encode(events[0])))
    }
}
