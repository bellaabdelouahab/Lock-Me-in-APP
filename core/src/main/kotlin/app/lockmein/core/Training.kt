package app.lockmein.core

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * The screen filter learns from his screens (FEATURES 3.20, his choice 2026-11-24): every block and every "Wrong?" goes to his
 * training server once a day, from 09:00 on an unmetered network (the office Wi-Fi), with its screenshot sealed for the trainer.
 * The server trains a new judge, keeps every version, and offers one only when it passes the held-out check; installing it is a
 * loosening, so it waits in the change gate like any other.
 */
object Training {
    /** Built in, so nothing needs pairing; another server can be set later. */
    const val DEFAULT_SERVER = "https://lockmein.abdelouahab.xyz"
    val SEND_FROM: LocalTime = LocalTime.of(9, 0)
    const val TRY_EVERY_MS = 15 * 60_000L
    const val MAX_PER_RUN = 300

    /** A "Wrong?" tap (or Wrong in the review) is a false alarm; a close he let stand (or Correct) is a real one. */
    fun label(record: BlockRecord): String = when (record.label) {
        BlockLabel.CLAIMED_FALSE, BlockLabel.WRONG -> "WRONG"
        BlockLabel.CORRECT, BlockLabel.NONE -> "CORRECT"
    }

    fun pending(records: List<BlockRecord>): List<BlockRecord> = records.filter { it.uploadedAtMillis == null }.sortedBy { it.atMillis }

    /** Whether to send now: something waits, today's batch is not done, it is 09:00 or later, and the network is unmetered. */
    fun due(now: ZonedDateTime, doneOn: LocalDate?, unmetered: Boolean, pending: Int): Boolean =
        pending > 0 && unmetered && !now.toLocalTime().isBefore(SEND_FROM) && doneOn != now.toLocalDate()
}

/**
 * Seals a screenshot for the trainer only: a fresh AES-256-GCM key per file, wrapped with the trainer's RSA key (OAEP, SHA-256 and
 * MGF1 SHA-256 spelled out: Android's default MGF1 is SHA-1). The relay that receives it cannot open it.
 * Format: u16 wrapped-key length | wrapped key | 12-byte IV | ciphertext and tag. server/trainer.py opens it.
 */
object Sealed {
    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    fun publicKey(pem: String) = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getMimeDecoder().decode(pem.replace(Regex("-----[A-Z ]+-----"), "").trim())),
    )

    fun seal(data: ByteArray, publicKeyPem: String, random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, publicKey(publicKeyPem), OAEP) }.doFinal(key)
        val body = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }.doFinal(data)
        return byteArrayOf((wrapped.size shr 8).toByte(), wrapped.size.toByte()) + wrapped + iv + body
    }

    /** Opens a sealed file with the private key (the trainer's side; here for the tests). */
    fun open(sealed: ByteArray, privateKey: java.security.PrivateKey): ByteArray {
        val n = ((sealed[0].toInt() and 0xFF) shl 8) or (sealed[1].toInt() and 0xFF)
        val key = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP) }.doFinal(sealed, 2, n)
        val iv = sealed.copyOfRange(2 + n, 14 + n)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
            .doFinal(sealed, 14 + n, sealed.size - 14 - n)
    }
}
