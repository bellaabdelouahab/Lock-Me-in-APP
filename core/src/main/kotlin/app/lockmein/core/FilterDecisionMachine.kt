package app.lockmein.core

import java.time.Duration
import java.time.Instant

/**
 * Output scores from the 5-class NSFW model:
 * [drawings, hentai, neutral, porn, sexy].
 */
data class FilterScores(
    val drawings: Float = 0f,
    val hentai: Float = 0f,
    val neutral: Float = 1f,
    val porn: Float = 0f,
    val sexy: Float = 0f,
    /**
     * Second model, run only on drawings (anime, manga, manhwa): r15 + r18 from deepghs anime_rating. Catches fanservice and
     * suggestive art with clothes on, which the first model misses. 0 when the frame is not a drawing.
     */
    val animeSuggestive: Float = 0f,
    /** The r18 part of [animeSuggestive] alone: explicit. r15 also covers violence (fight scenes), so it is not read alone. */
    val animeExplicit: Float = 0f,
    /** The sexual word or phrase found in the screen's text, if any. */
    val textMatch: String? = null,
    /**
     * Third model: seductive looks, poses and framing, clothes on or off, photos and drawings (a CLIP image encoder with a
     * judge trained on seductive vs ordinary pictures). 0 when the model is missing.
     */
    val seductive: Float = 0f,
) {
    /** True when the first model sees a drawing, which is when the anime model runs. */
    val isDrawing: Boolean get() = drawings + hentai >= DRAWING_GATE

    override fun toString(): String =
        "porn=%.2f, hentai=%.2f, sexy=%.2f, drawings=%.2f, neutral=%.2f, anime=%.2f, anime18=%.2f, seductive=%.2f%s".format(
            porn, hentai, sexy, drawings, neutral, animeSuggestive, animeExplicit, seductive, textMatch?.let { ", text=\"$it\"" } ?: "",
        )

    companion object {
        /** Measured on 306 photos: with this gate the anime model never ran on a photo of a normally dressed person. */
        const val DRAWING_GATE = 0.5f
    }
}

/**
 * When one frame (one screen tile) is a hit (spec v3.1 F2). Measured on simulated phone screens (feed and full-screen video, four
 * frames each): the old rule read any single model above its threshold, so a bald head or a face close-up read as "porn" and a
 * Dragon Ball fight as "r15". Now:
 * - photos: porn + hentai, the seductive model or "sexy" alone only when clear; a lower porn + hentai or "sexy" only when the
 *   seductive model agrees on the same tile (two different models seeing the same thing).
 * - drawings: hentai / porn, the anime model's explicit (r18) score alone, its suggestive score (r15 + r18, which also rates
 *   violence) only when the seductive model agrees, or the seductive model alone when clear.
 * [level] is 1 or more on a hit: the strongest of the paths, each the weakest of its parts (score / threshold).
 */
data class FilterRule(
    val photoPornHentai: Float = 0.85f,
    val photoSexy: Float = 0.85f,
    val photoSeductive: Float = 0.75f,
    val photoBackedPornHentai: Float = 0.70f,
    val photoBackedSexy: Float = 0.70f,
    /** The seductive score that backs a lower porn + hentai or sexy score on a photo; above 1, that path is off (tuning left it off). */
    val photoBacking: Float = 2f,
    val drawnPornHentai: Float = 0.50f,
    val drawnExplicit: Float = 0.55f,
    val drawnSuggestive: Float = 0.60f,
    /** The seductive score that backs the anime model's suggestive score on a drawing. */
    val drawnBacking: Float = 0.45f,
    val drawnSeductive: Float = 0.75f,
) {
    fun level(s: FilterScores): Float {
        val ph = s.porn + s.hentai
        return if (s.isDrawing) maxOf(
            ph / drawnPornHentai,
            s.animeExplicit / drawnExplicit,
            minOf(s.animeSuggestive / drawnSuggestive, backing(s.seductive, drawnBacking)),
            s.seductive / drawnSeductive,
        ) else maxOf(
            ph / photoPornHentai,
            s.sexy / photoSexy,
            s.seductive / photoSeductive,
            minOf(ph / photoBackedPornHentai, backing(s.seductive, photoBacking)),
            minOf(s.sexy / photoBackedSexy, backing(s.seductive, photoBacking)),
        )
    }

    fun isHit(s: FilterScores): Boolean = level(s) >= 1f // words on screen never act; they are only logged

    /**
     * Every threshold moved by [delta] (per-app setting, spec v3.1 F2): below 0 is stricter, above 0 looser. Kept between 0.05
     * and 1.0; thresholds that are switched off (above 1) stay off.
     */
    fun shifted(delta: Float): FilterRule {
        if (delta == 0f) return this
        fun m(v: Float) = if (v > 1f || v <= 0f) v else (v + delta).coerceIn(0.05f, 1f)
        return FilterRule(
            m(photoPornHentai), m(photoSexy), m(photoSeductive), m(photoBackedPornHentai), m(photoBackedSexy), m(photoBacking),
            m(drawnPornHentai), m(drawnExplicit), m(drawnSuggestive), m(drawnBacking), m(drawnSeductive),
        )
    }

    private fun backing(seductive: Float, needed: Float): Float = if (needed <= 0f) Float.MAX_VALUE else seductive / needed

    companion object {
        /** The values measured best (docs/FILTER-EVAL.md); the config's four thresholds default to these. */
        val TUNED = FilterRule()

        /** Tuned values for the rest; the config's four thresholds replace the matching ones. */
        fun from(pornHentai: Float, sexy: Float, anime: Float, seductive: Float?): FilterRule = FilterRule().let {
            it.copy(
                photoPornHentai = pornHentai,
                photoSexy = sexy,
                drawnSuggestive = anime,
                photoSeductive = seductive ?: Float.MAX_VALUE,
                drawnSeductive = seductive ?: Float.MAX_VALUE,
                photoBacking = if (seductive == null) Float.MAX_VALUE else it.photoBacking,
                drawnBacking = if (seductive == null) 0f else it.drawnBacking,
            )
        }
    }
}

sealed interface FilterAction {
    data object None : FilterAction

    /** Display warning overlay on top of [packageName]. */
    data class ShowWarning(
        val packageName: String,
        val scores: FilterScores,
    ) : FilterAction

    /** Clean second check or user switched away: dismiss warning overlay. */
    data class ClearWarning(
        val packageName: String,
        val reason: String = "Clean check",
    ) : FilterAction

    /** Second hit after 5 seconds: return to home screen and close the app for [duration] (30 seconds: long enough to pull you out). */
    data class ExitAndSuspend(
        val packageName: String,
        val duration: Duration = Duration.ofSeconds(30),
        val scores: FilterScores,
    ) : FilterAction
}

sealed interface FilterState {
    data object Idle : FilterState

    data class WarningActive(
        val packageName: String,
        val hitStartedAt: Instant,
        val initialScores: FilterScores,
    ) : FilterState
}

/**
 * Pure state machine implementing the screen filter decision flow (spec v3.1 F2):
 * sure -> show warning -> wait 5 seconds -> second check -> exit+suspend (30 s) or clear.
 *
 * Sure means the frames agree: [AGREE_FRAMES] of the last [AGREE_OF] frames of the same app (within [AGREE_WINDOW]) are hits.
 * One frame is never enough, except a very strong one (porn + hentai at [STRONG_LEVEL] or more), so a man talking or a fight
 * scene that one frame misreads never shows the warning. A still screen counts as the same frame again (see FilterService).
 */
class FilterDecisionMachine(
    val pornHentaiThreshold: Float = 0.50f,
    val sexyThreshold: Float = 0.50f,
    val animeThreshold: Float = 0.80f,
    /** Null while the seductive model only shows its score in test mode. */
    val seductiveThreshold: Float? = null,
    val warningGraceDuration: Duration = Duration.ofSeconds(5),
    val suspensionDuration: Duration = Duration.ofSeconds(30),
    /** Per-app move of every threshold (package -> delta, below 0 stricter). */
    val appOffsets: Map<String, Float> = emptyMap(),
    /** The personal second check trained on his labelled blocks (spec F5); null until one is installed. */
    val judge: FilterJudge? = null,
) {
    /** The hit rule, from the four thresholds above and the tuned values for the rest. */
    val rule: FilterRule = FilterRule.from(pornHentaiThreshold, sexyThreshold, animeThreshold, seductiveThreshold)

    private val appRules = HashMap<String, FilterRule>()

    /** The rule for one app: [rule] moved by the app's offset. */
    fun ruleFor(packageName: String): FilterRule = appRules.getOrPut(packageName) { rule.shifted(appOffsets[packageName] ?: 0f) }

    /** The frames that made the last warning sure: (time, hit), oldest first. Kept for the block's record (spec F3). */
    var agreedFrames: List<Pair<Instant, Boolean>> = emptyList()
        private set

    var state: FilterState = FilterState.Idle
        private set

    private val recent = ArrayDeque<Triple<String, Instant, Boolean>>()

    /** Whether this one frame is a hit by the thresholds (the base rule, no app offset). */
    fun frameHit(scores: FilterScores): Boolean = frameHit("", scores)

    /**
     * Whether this one frame is a hit in [packageName]: over the app's thresholds, and not a false alarm by the personal judge.
     * The judge never overrules a very strong frame.
     */
    fun frameHit(packageName: String, scores: FilterScores): Boolean {
        if (!ruleFor(packageName).isHit(scores)) return false
        if (scores.porn + scores.hentai >= STRONG_LEVEL) return true
        return judge?.isFalseAlarm(scores) != true
    }

    /** Records the frame and answers whether the recent frames agree it is a hit (or this one is very strong). */
    fun isHit(packageName: String, scores: FilterScores, now: Instant): Boolean {
        val hit = frameHit(packageName, scores)
        while (recent.isNotEmpty() && (recent.first().first != packageName || Duration.between(recent.first().second, now) > AGREE_WINDOW)) recent.removeFirst()
        recent.addLast(Triple(packageName, now, hit))
        while (recent.size > AGREE_OF) recent.removeFirst()
        if (hit && scores.porn + scores.hentai >= STRONG_LEVEL) return true
        return recent.count { it.third } >= AGREE_FRAMES
    }

    /**
     * Processes a frame prediction for the foreground [packageName] at timestamp [now].
     * Returns the [FilterAction] to be carried out by the system service.
     */
    fun processFrame(
        packageName: String,
        scores: FilterScores,
        now: Instant,
    ): FilterAction {
        val sure = isHit(packageName, scores, now)
        // The second check, 5 seconds after the warning, needs this frame to be a hit: the warning was already sure.
        val warned = (state as? FilterState.WarningActive)?.packageName == packageName
        val hit = if (warned) frameHit(packageName, scores) || sure else sure
        if (sure && !warned) {
            agreedFrames = recent.map { it.second to it.third }
            recent.clear()
        } // the 5-second second check goes by the frames after the warning

        return when (val current = state) {
            is FilterState.Idle -> {
                if (hit) {
                    state = FilterState.WarningActive(
                        packageName = packageName,
                        hitStartedAt = now,
                        initialScores = scores,
                    )
                    FilterAction.ShowWarning(packageName, scores)
                } else {
                    FilterAction.None
                }
            }

            is FilterState.WarningActive -> {
                // If user switched to another app, clear warning for previous app
                if (current.packageName != packageName) {
                    val prev = current.packageName
                    state = if (hit) {
                        FilterState.WarningActive(packageName, now, scores)
                    } else {
                        FilterState.Idle
                    }
                    if (hit) {
                        // Cleared for prev, immediately show for new app
                        FilterAction.ShowWarning(packageName, scores)
                    } else {
                        FilterAction.ClearWarning(prev, "Switched away from app")
                    }
                } else {
                    val elapsed = Duration.between(current.hitStartedAt, now)
                    if (elapsed < warningGraceDuration) {
                        // User has 5 seconds to scroll away or switch; keep warning active
                        FilterAction.None
                    } else {
                        // 5 seconds elapsed: second check evaluation
                        state = FilterState.Idle
                        if (hit) {
                            FilterAction.ExitAndSuspend(
                                packageName = packageName,
                                duration = suspensionDuration,
                                scores = scores,
                            )
                        } else {
                            FilterAction.ClearWarning(
                                packageName = packageName,
                                reason = "Second check clean",
                            )
                        }
                    }
                }
            }
        }
    }

    /** Resets the state machine when leaving a watched app or stopping service. */
    fun reset(): FilterAction {
        val prev = state
        state = FilterState.Idle
        recent.clear()
        return if (prev is FilterState.WarningActive) {
            FilterAction.ClearWarning(prev.packageName, "Service reset")
        } else {
            FilterAction.None
        }
    }

    companion object {
        const val AGREE_FRAMES = 3
        const val AGREE_OF = 4
        /** Frames come every half second when the phone keeps up, about one a second when it does not. */
        val AGREE_WINDOW: Duration = Duration.ofSeconds(6)
        /** porn + hentai this high on one frame acts at once. */
        const val STRONG_LEVEL = 0.95f
    }
}
