package app.lockmein

import androidx.test.platform.app.InstrumentationRegistry
import app.lockmein.core.BlockLabel
import app.lockmein.core.BlockRecord
import app.lockmein.core.Bundle
import app.lockmein.core.StoredScores
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream

/** Spec v3.1 F3, F4 on a real phone: the saved screen is encrypted with a key-store key, a correct block loses it, 10 saves a day. */
class FeedbackStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.noBackupFilesDir, "feedback")

    @Before @After
    fun clean() { dir.deleteRecursively() }

    private fun record(i: Int) = BlockRecord("t$i", Instant.now().toEpochMilli() + i, "com.brave.browser", StoredScores(porn = 0.9f))

    @Test
    fun savedScreenIsEncryptedAndOnlyKeptForFalseAlarms() {
        val store = FeedbackStore(context)
        val jpeg = ByteArray(5000) { (it % 251).toByte() }
        store.add(record(1)); store.add(record(2))
        store.add(record(1)) // the same block twice keeps one
        assertEquals(2, store.records().size)

        assertTrue(store.saveForReview("t1", jpeg))
        assertTrue(store.saveForReview("t2", jpeg))
        val onDisk = File(dir, "t1.bin").readBytes()
        assertFalse("the file on disk is not the plain screenshot", onDisk.copyOfRange(12, 12 + 100).contentEquals(jpeg.copyOfRange(0, 100)))
        val saved = store.records().first { it.id == "t1" }
        assertEquals(BlockLabel.CLAIMED_FALSE, saved.label)
        assertArrayEquals(jpeg, store.image(saved))

        store.label("t1", BlockLabel.CORRECT, Instant.now())
        store.label("t2", BlockLabel.WRONG, Instant.now())
        assertFalse(File(dir, "t1.bin").exists())
        assertNull(store.image(store.records().first { it.id == "t1" }))
        assertTrue(File(dir, "t2.bin").exists())

        // The export holds the labels and the false alarm's screen, and opens only with its passphrase.
        val (bundle, count) = store.exportBundle("ABCD-EFGH-IJKL-MNOP")
        assertEquals(2, count)
        val names = ZipInputStream(Bundle.open(bundle, "ABCD-EFGH-IJKL-MNOP").inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("blocks.json", "images/t2.jpg"), names)
    }

    @Test
    fun noDailyLimitOnSaves() {
        val store = FeedbackStore(context)
        repeat(25) { store.add(record(it)) }
        repeat(25) { assertTrue(store.saveForReview("t$it", null)) }
        assertEquals(25, store.savesToday())
    }

    @Test
    fun aCloseKeepsItsScreenshotUntilTheServerHasIt() {
        val store = FeedbackStore(context)
        store.add(record(1))
        store.keepImage("t1", byteArrayOf(1, 2, 3))
        val kept = store.records().single { it.id == "t1" }
        assertTrue(kept.hasImage)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.image(kept))
        store.uploaded("t1", 1_000)
        val sent = store.records().single { it.id == "t1" }
        assertFalse(sent.hasImage)
        assertEquals(1_000L, sent.uploadedAtMillis)
        assertNull(store.image(kept))
        // Once sent, a late screenshot is not kept again.
        store.keepImage("t1", byteArrayOf(4))
        assertFalse(store.records().single { it.id == "t1" }.hasImage)
    }
}
