package app.lockmein

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.lockmein.core.BlockLabel
import app.lockmein.core.BlockRecord
import app.lockmein.core.Feedback
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The screen filter's blocks and the screenshots saved with "Wrong block?" (spec v3.1 F3, F4). In the app's no-backup storage,
 * never in the gallery or shared storage; each screenshot encrypted with AES-GCM under a key that lives in Android's key store
 * (the same scheme Jetpack Security's EncryptedFile uses, without the deprecated library).
 */
class FeedbackStore(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "feedback")
    private val index = File(dir, "blocks.json")
    private val saves = File(dir, "saves.txt")

    @Synchronized
    fun records(): List<BlockRecord> = if (index.exists()) Feedback.decode(index.readText()) else emptyList()

    @Synchronized
    private fun write(records: List<BlockRecord>) {
        dir.mkdirs()
        val tmp = File(dir, "blocks.json.tmp")
        tmp.writeText(Feedback.encode(records))
        tmp.renameTo(index)
    }

    /** Records a block: metadata and scores only (spec: blocks with no tap keep no image). Adding the same block twice keeps one. */
    @Synchronized
    fun add(record: BlockRecord) {
        val current = records()
        if (current.any { it.id == record.id }) return
        val (kept, dropped) = Feedback.add(current, record)
        dropped.forEach { image(it).delete() }
        write(kept)
    }

    fun savesToday(zone: ZoneId = ZoneId.systemDefault()): Int = savedDays().count { it == LocalDate.now(zone) }

    private fun savedDays(): List<LocalDate> =
        if (saves.exists()) saves.readLines().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() } else emptyList()

    /**
     * "Save for review": keeps [jpeg] encrypted with the block [id] and marks it as claimed false. Refused (false) over the daily
     * limit or when the block is gone.
     */
    @Synchronized
    fun saveForReview(id: String, jpeg: ByteArray?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val today = LocalDate.now(zone)
        val days = savedDays()
        if (!Feedback.canSave(days, today)) return false
        val list = records()
        if (list.none { it.id == id }) return false
        val stored = jpeg != null && runCatching { dir.mkdirs(); image(id).writeBytes(encrypt(jpeg)) }.isSuccess
        write(list.map { if (it.id == id) it.copy(label = BlockLabel.CLAIMED_FALSE, hasImage = stored) else it })
        dir.mkdirs()
        saves.writeText((days.filter { it.isAfter(today.minusDays(7)) } + today).joinToString("\n"))
        return true
    }

    /** The decrypted screenshot of a saved block, or null. Only for the review screen. */
    fun image(record: BlockRecord): ByteArray? =
        if (!record.hasImage) null else runCatching { decrypt(image(record.id).readBytes()) }.getOrNull()

    @Synchronized
    fun label(id: String, label: BlockLabel, now: Instant) {
        val updated = Feedback.label(records(), id, label, now)
        if (updated.firstOrNull { it.id == id }?.hasImage == false) image(id).delete()
        write(updated)
    }

    /**
     * The labelled blocks for training on the laptop (spec F4): a zip of blocks.json and the false alarms' screenshots, encrypted
     * with [app.lockmein.core.Bundle] under [passphrase]. scripts/judge/decrypt.py opens it.
     */
    fun exportBundle(passphrase: String): Pair<ByteArray, Int> {
        val labelled = records().filter { it.label == BlockLabel.WRONG || it.label == BlockLabel.CORRECT || it.label == BlockLabel.NONE }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("blocks.json")); z.write(Feedback.encode(labelled).toByteArray()); z.closeEntry()
            for (r in labelled.filter { it.hasImage && it.label == BlockLabel.WRONG }) {
                val img = image(r) ?: continue
                z.putNextEntry(ZipEntry("images/${r.id}.jpg")); z.write(img); z.closeEntry()
            }
        }
        return app.lockmein.core.Bundle.seal(zip.toByteArray(), passphrase) to labelled.size
    }

    private fun image(id: String) = File(dir, "$id.bin")

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(data)
    }

    private fun decrypt(data: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }.doFinal(data, 12, data.size - 12)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "lock_me_in_feedback"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** The screenshot kept in memory for "Save for review": the last frame that was a hit, at most this long ago. */
        const val PENDING_MS = 2 * 60_000L
    }
}
