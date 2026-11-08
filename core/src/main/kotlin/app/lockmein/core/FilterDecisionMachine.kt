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
) {
    /**
     * Hit rule: porn + hentai >= [pornHentaiThreshold] (default 0.50)
     * OR sexy >= [sexyThreshold] (default 0.50).
     */
    fun isHit(pornHentaiThreshold: Float = 0.50f, sexyThreshold: Float = 0.50f): Boolean {
        return (porn + hentai >= pornHentaiThreshold) || (sexy >= sexyThreshold)
    }

    override fun toString(): String =
        "porn=%.2f, hentai=%.2f, sexy=%.2f, drawings=%.2f, neutral=%.2f".format(
            porn, hentai, sexy, drawings, neutral
        )
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

    /** Second hit after 5 seconds: return to home screen and suspend app for [duration]. */
    data class ExitAndSuspend(
        val packageName: String,
        val duration: Duration = Duration.ofMinutes(30),
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
 */
class FilterDecisionMachine(
    val pornHentaiThreshold: Float = 0.50f,
    val sexyThreshold: Float = 0.50f,
    val warningGraceDuration: Duration = Duration.ofSeconds(5),
    val suspensionDuration: Duration = Duration.ofMinutes(30),
) {
    var state: FilterState = FilterState.Idle
        private set

    /**
     * Processes a frame prediction for the foreground [packageName] at timestamp [now].
     * Returns the [FilterAction] to be carried out by the system service.
     */
    fun processFrame(
        packageName: String,
        scores: FilterScores,
        now: Instant,
    ): FilterAction {
        val hit = scores.isHit(pornHentaiThreshold, sexyThreshold)

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
        return if (prev is FilterState.WarningActive) {
            FilterAction.ClearWarning(prev.packageName, "Service reset")
        } else {
            FilterAction.None
        }
    }
}
