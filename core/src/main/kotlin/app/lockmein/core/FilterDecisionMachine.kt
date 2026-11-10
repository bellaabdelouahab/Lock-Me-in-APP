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

    /**
     * Hit rule: porn + hentai >= [pornHentaiThreshold], OR sexy >= [sexyThreshold], OR (a drawing and)
     * animeSuggestive >= [animeThreshold], OR a sexual word on screen.
     */
    fun isHit(
        pornHentaiThreshold: Float = 0.50f,
        sexyThreshold: Float = 0.50f,
        animeThreshold: Float = 0.80f,
        seductiveThreshold: Float? = null,
    ): Boolean = level(pornHentaiThreshold, sexyThreshold, animeThreshold, seductiveThreshold) >= 1f // words alone never act; they are only logged

    /** How close the frame is to a hit: the largest score / threshold. 1 or more is a hit. A null seductive threshold leaves that model out. */
    fun level(pornHentaiThreshold: Float, sexyThreshold: Float, animeThreshold: Float, seductiveThreshold: Float? = null): Float =
        maxOf(
            (porn + hentai) / pornHentaiThreshold,
            sexy / sexyThreshold,
            animeSuggestive / animeThreshold,
            seductiveThreshold?.let { seductive / it } ?: 0f,
        )

    override fun toString(): String =
        "porn=%.2f, hentai=%.2f, sexy=%.2f, drawings=%.2f, neutral=%.2f, anime=%.2f, seductive=%.2f%s".format(
            porn, hentai, sexy, drawings, neutral, animeSuggestive, seductive, textMatch?.let { ", text=\"$it\"" } ?: "",
        )

    companion object {
        /** Measured on 306 photos: with this gate the anime model never ran on a photo of a normally dressed person. */
        const val DRAWING_GATE = 0.5f
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
 * Pure state machine implementing the screen filter decision flow:
 * First hit -> show warning -> wait 5 seconds -> second check -> exit+suspend (30 min) or clear.
 *
 * A hit is one clear frame, or a run of borderline ones: [NEAR_FRAMES] frames within [NEAR_WINDOW] that each reach
 * [NEAR_LEVEL] of a threshold. That catches seductive videos where no single frame is clear.
 */
class FilterDecisionMachine(
    val pornHentaiThreshold: Float = 0.50f,
    val sexyThreshold: Float = 0.50f,
    val animeThreshold: Float = 0.80f,
    /** Null while the seductive model only shows its score in test mode. */
    val seductiveThreshold: Float? = null,
    val warningGraceDuration: Duration = Duration.ofSeconds(5),
    val suspensionDuration: Duration = Duration.ofSeconds(30),
) {
    var state: FilterState = FilterState.Idle
        private set

    private val near = ArrayDeque<Pair<String, Instant>>()

    /** True for a clear frame, or the [NEAR_FRAMES]th borderline frame of the same app within [NEAR_WINDOW]. */
    fun isHit(packageName: String, scores: FilterScores, now: Instant): Boolean {
        if (scores.isHit(pornHentaiThreshold, sexyThreshold, animeThreshold, seductiveThreshold)) return true
        while (near.isNotEmpty() && (near.first().first != packageName || Duration.between(near.first().second, now) > NEAR_WINDOW)) near.removeFirst()
        if (scores.level(pornHentaiThreshold, sexyThreshold, animeThreshold, seductiveThreshold) < NEAR_LEVEL) return false
        near.addLast(packageName to now)
        return near.size >= NEAR_FRAMES
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
        val hit = isHit(packageName, scores, now)
        if (hit && state is FilterState.Idle) near.clear() // the 5-second second check needs its own evidence

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
        near.clear()
        return if (prev is FilterState.WarningActive) {
            FilterAction.ClearWarning(prev.packageName, "Service reset")
        } else {
            FilterAction.None
        }
    }

    companion object {
        const val NEAR_LEVEL = 0.75f
        const val NEAR_FRAMES = 3
        val NEAR_WINDOW: Duration = Duration.ofSeconds(6)
    }
}
