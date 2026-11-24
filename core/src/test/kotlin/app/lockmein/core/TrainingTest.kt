package app.lockmein.core

import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingTest {
    private fun rec(id: String, label: BlockLabel, uploaded: Long? = null) =
        BlockRecord(id = id, atMillis = id.hashCode().toLong(), pkg = "p", scores = StoredScores(), label = label, uploadedAtMillis = uploaded)

    @Test
    fun wrongTapsAreFalseAlarmsAndClosesHeLetStandAreReal() {
        assertEquals("WRONG", Training.label(rec("a", BlockLabel.CLAIMED_FALSE)))
        assertEquals("WRONG", Training.label(rec("b", BlockLabel.WRONG)))
        assertEquals("CORRECT", Training.label(rec("c", BlockLabel.NONE)))
        assertEquals("CORRECT", Training.label(rec("d", BlockLabel.CORRECT)))
        assertEquals(listOf("a"), Training.pending(listOf(rec("a", BlockLabel.NONE), rec("b", BlockLabel.NONE, uploaded = 5))).map { it.id })
    }

    @Test
    fun sendsOnceADayFromNineOnAnUnmeteredNetwork() {
        val nine = ZonedDateTime.of(2026, 11, 25, 9, 0, 0, 0, ZoneOffset.UTC)
        assertTrue(Training.due(nine, null, unmetered = true, pending = 3))
        assertFalse(Training.due(nine.minusMinutes(1), null, true, 3), "before 09:00")
        assertFalse(Training.due(nine, null, unmetered = false, pending = 3), "mobile data")
        assertFalse(Training.due(nine, null, true, pending = 0), "nothing to send")
        assertFalse(Training.due(nine.plusHours(5), LocalDate.of(2026, 11, 25), true, 3), "already sent today")
        assertTrue(Training.due(nine.plusDays(1), LocalDate.of(2026, 11, 25), true, 3), "next day")
    }

    @Test
    fun aSealedScreenshotOpensOnlyWithTheTrainersKey() {
        val pem = File(javaClass.getResource("/training/public.pem")!!.toURI()).readText()
        val private = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(
            File(javaClass.getResource("/training/private.pem")!!.toURI()).readText().replace(Regex("-----[A-Z ]+-----"), "").trim(),
        )))
        val data = ByteArray(50_000) { (it * 31).toByte() }
        val sealed = Sealed.seal(data, pem)
        assertContentEquals(data, Sealed.open(sealed, private))
        // A changed byte anywhere in the body fails the tag.
        assertFailsWith<Exception> { Sealed.open(sealed.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }, private) }
        // The trainer's Python opens what the phone seals: the file the script check below reads.
        System.getProperty("sealed.out")?.let { File(it).writeBytes(sealed) }
    }
}
