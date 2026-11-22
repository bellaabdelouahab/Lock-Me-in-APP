package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/**
 * The personal second check (spec v3.1 F5): a small logistic judge on the three frozen models' scores, trained off the phone
 * (scripts/judge/train.py) on his labelled blocks plus public examples of real hits. It only answers "is this hit a false alarm";
 * it can never make a hit out of a clean frame, and never overrules a very strong frame (see FilterDecisionMachine).
 * Installing one loosens the filter, so it goes through the change gate; taking it off tightens and is instant.
 */
@Serializable
data class FilterJudge(
    /** Shown in the app and the logs, e.g. "judge-2027-01-10". */
    val version: String,
    /** One weight per [FEATURES] entry, in that order. */
    val weights: List<Float>,
    val bias: Float,
    /** A hit whose false-alarm probability is at least this is not counted. */
    val cutoff: Float,
) {
    fun falseAlarm(s: FilterScores): Float {
        val x = features(s)
        var z = bias.toDouble()
        for (i in x.indices) z += weights[i] * x[i]
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    fun isFalseAlarm(s: FilterScores): Boolean = falseAlarm(s) >= cutoff

    fun problem(): String? = when {
        version.isBlank() -> "The judge has no version."
        weights.size != FEATURES.size -> "The judge needs ${FEATURES.size} weights, it has ${weights.size}."
        weights.any { it.isNaN() || it.isInfinite() } || bias.isNaN() || bias.isInfinite() -> "The judge's weights are not numbers."
        cutoff !in 0.5f..0.999f -> "The judge's cutoff must be between 0.5 and 0.999."
        else -> null
    }

    companion object {
        /** Same names and order as scripts/judge/train.py. */
        val FEATURES = listOf(
            "porn", "hentai", "sexy", "drawings", "neutral", "anime_suggestive", "anime_explicit", "seductive", "is_drawing", "porn_hentai",
        )

        fun features(s: FilterScores): DoubleArray = doubleArrayOf(
            s.porn.toDouble(), s.hentai.toDouble(), s.sexy.toDouble(), s.drawings.toDouble(), s.neutral.toDouble(),
            s.animeSuggestive.toDouble(), s.animeExplicit.toDouble(), s.seductive.toDouble(),
            if (s.isDrawing) 1.0 else 0.0, (s.porn + s.hentai).toDouble(),
        )
    }
}
