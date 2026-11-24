package app.lockmein.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The model scores of a frame, as stored with a block (plain numbers, so the laptop can read them). */
@Serializable
data class StoredScores(
    val porn: Float = 0f,
    val hentai: Float = 0f,
    val sexy: Float = 0f,
    val drawings: Float = 0f,
    val neutral: Float = 1f,
    val animeSuggestive: Float = 0f,
    val animeExplicit: Float = 0f,
    val seductive: Float = 0f,
) {
    fun toScores() = FilterScores(drawings, hentai, neutral, porn, sexy, animeSuggestive, animeExplicit, seductive = seductive)

    companion object {
        fun of(s: FilterScores) = StoredScores(s.porn, s.hentai, s.sexy, s.drawings, s.neutral, s.animeSuggestive, s.animeExplicit, s.seductive)
    }
}

@Serializable
enum class BlockLabel {
    /** Nobody said anything: metadata and scores only, no image. */
    NONE,

    /** He tapped "Wrong block?" then "Save for review": the screenshot is kept, waiting for the review window. */
    CLAIMED_FALSE,

    /** Labelled in the review window: a false alarm. The image is kept as training data. */
    WRONG,

    /** Labelled in the review window: the block was right. The image is deleted, the metadata kept. */
    CORRECT,
}

/** One block by the screen filter (spec v3.1 F3). */
@Serializable
data class BlockRecord(
    val id: String,
    val atMillis: Long,
    val pkg: String,
    /** The scores of the frame that closed the app. */
    val scores: StoredScores,
    /** The frames that agreed before the warning: milliseconds before the block, and whether each was a hit. */
    val agreed: List<AgreedFrame> = emptyList(),
    /** The rule's thresholds in force for this app, as text. */
    val rule: String = "",
    val appOffset: Float = 0f,
    val modelVersion: String = "",
    val judgeVersion: String? = null,
    val label: BlockLabel = BlockLabel.NONE,
    val hasImage: Boolean = false,
    val labelledAtMillis: Long? = null,
    /** When it went to the training server (FEATURES 3.20); its image is deleted from the phone then. */
    val uploadedAtMillis: Long? = null,
) {
    val at: Instant get() = Instant.ofEpochMilli(atMillis)
    val waiting: Boolean get() = label == BlockLabel.CLAIMED_FALSE
}

@Serializable
data class AgreedFrame(val msBefore: Long, val hit: Boolean)

/** What may be kept and when (spec v3.1 F3, F4). Pure, so it is tested here. */
object Feedback {
    /** Most records kept; the oldest unlabelled ones go first, then the oldest of the rest. */
    const val MAX_RECORDS = 500

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(records: List<BlockRecord>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BlockRecord.serializer()), records)

    fun decode(text: String): List<BlockRecord> =
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BlockRecord.serializer()), text) }.getOrDefault(emptyList())

    /** Adds [record], dropping the oldest when over [MAX_RECORDS]: plain blocks first, waiting items last. Returns (kept, dropped ids). */
    fun add(records: List<BlockRecord>, record: BlockRecord): Pair<List<BlockRecord>, List<String>> {
        val all = records + record
        if (all.size <= MAX_RECORDS) return all to emptyList()
        val over = all.size - MAX_RECORDS
        val dropOrder = all.sortedWith(compareBy<BlockRecord> { if (it.waiting) 1 else 0 }.thenBy { it.atMillis })
        val dropped = dropOrder.take(over).map { it.id }.toSet()
        return all.filter { it.id !in dropped } to dropped.toList()
    }

    /**
     * After labelling. Both keep their image until it goes to the training server (his choice, 2026-11-24: the model learns from
     * the right closes too, FEATURES 3.20); it was "a correct block loses it" before.
     */
    fun label(records: List<BlockRecord>, id: String, label: BlockLabel, now: Instant): List<BlockRecord> = records.map {
        if (it.id != id) it else it.copy(label = label, labelledAtMillis = now.toEpochMilli())
    }
}

/** The daily review window, 19:30 to 20:00 (spec v3.1 F4). The queue opens only inside it. */
object ReviewWindow {
    val START: LocalTime = LocalTime.of(19, 30)
    val END: LocalTime = LocalTime.of(20, 0)

    fun isOpen(now: Instant, zone: ZoneId): Boolean {
        val t = now.atZone(zone).toLocalTime()
        return !t.isBefore(START) && t.isBefore(END)
    }

    /** The next time it opens (now, if open). */
    fun nextOpening(now: Instant, zone: ZoneId): ZonedDateTime {
        val z = now.atZone(zone)
        if (isOpen(now, zone)) return z
        val today = z.toLocalDate().atTime(START).atZone(zone)
        return if (z.isBefore(today)) today else z.toLocalDate().plusDays(1).atTime(START).atZone(zone)
    }

    fun closesAt(now: Instant, zone: ZoneId): ZonedDateTime = now.atZone(zone).toLocalDate().atTime(END).atZone(zone)
}

/**
 * Eval mode (spec v3.1 F2): the filter's verdict on a fixed, labelled folder of screenshots. A screenshot is a still screen, and a
 * still screen counts as the same frame again, so one hit frame is a close.
 */
data class EvalItem(val name: String, val risky: Boolean, val scores: FilterScores, val closed: Boolean) {
    /** Xiaomi and Android name screenshots after the app: Screenshot_2026-11-20-21-03-11-123_com.brave.browser.jpg. */
    val pkg: String get() = EvalReport.packageOf(name)
    val topClass: String get() = listOf("drawings" to scores.drawings, "hentai" to scores.hentai, "neutral" to scores.neutral, "porn" to scores.porn, "sexy" to scores.sexy).maxBy { it.second }.first
}

data class EvalCount(val total: Int, val closed: Int) {
    val share: Double get() = if (total == 0) 0.0 else closed.toDouble() / total
}

data class EvalReport(val items: List<EvalItem>) {
    val normal: EvalCount get() = count(items.filter { !it.risky })
    val risky: EvalCount get() = count(items.filter { it.risky })
    val falseCloses: List<EvalItem> get() = items.filter { !it.risky && it.closed }
    val misses: List<EvalItem> get() = items.filter { it.risky && !it.closed }

    /** Spec target: at most 1 false close in 100 normal screens. */
    val meetsTarget: Boolean get() = normal.total > 0 && normal.closed * 100 <= normal.total

    fun byApp(): Map<String, Pair<EvalCount, EvalCount>> = items.groupBy { it.pkg }.mapValues { (_, v) -> count(v.filter { !it.risky }) to count(v.filter { it.risky }) }
    fun byClass(): Map<String, Pair<EvalCount, EvalCount>> = items.groupBy { it.topClass }.mapValues { (_, v) -> count(v.filter { !it.risky }) to count(v.filter { it.risky }) }

    private fun count(list: List<EvalItem>) = EvalCount(list.size, list.count { it.closed })

    companion object {
        private val PACKAGE = Regex("""_([a-zA-Z][\w]*(?:\.[a-zA-Z][\w]*)+)\.(?:jpe?g|png|webp)$""")

        fun packageOf(name: String): String = PACKAGE.find(name)?.groupValues?.get(1) ?: "unknown app"

        /** Normal folder names; anything else under the chosen folder that says risky counts as risky. */
        fun isRiskyFolder(name: String): Boolean = name.lowercase().let { it.startsWith("risk") || it == "nsfw" || it == "block" }
        fun isNormalFolder(name: String): Boolean = name.lowercase().let { it.startsWith("normal") || it == "safe" || it == "sfw" || it == "clean" }
    }
}

/**
 * The export for training (spec F4): bytes encrypted with AES-256-GCM under a key made from a passphrase (PBKDF2-SHA256).
 * Layout: "LMIB1" | salt (16) | iv (12) | ciphertext with tag. scripts/judge/decrypt.py opens it.
 */
object Bundle {
    const val MAGIC = "LMIB1"
    const val ROUNDS = 200_000

    fun seal(data: ByteArray, passphrase: String, random: java.security.SecureRandom = java.security.SecureRandom()): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key(passphrase, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        return MAGIC.toByteArray() + salt + iv + cipher.doFinal(data)
    }

    fun open(sealed: ByteArray, passphrase: String): ByteArray {
        require(sealed.size > 33 && String(sealed, 0, 5) == MAGIC) { "not a Lock Me In export" }
        val salt = sealed.copyOfRange(5, 21)
        val iv = sealed.copyOfRange(21, 33)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, key(passphrase, salt), javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(sealed, 33, sealed.size - 33)
    }

    private fun key(passphrase: String, salt: ByteArray) = javax.crypto.spec.SecretKeySpec(
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, ROUNDS, 256)).encoded,
        "AES",
    )
}
