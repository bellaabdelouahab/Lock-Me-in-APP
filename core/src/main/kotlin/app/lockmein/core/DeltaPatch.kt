package app.lockmein.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/**
 * Small updates (FEATURES 3.19): rebuilds the new APK from the one the phone has and a patch made by `scripts/delta.py`
 * (bsdiff with zlib). Streams: the old APK is read in place, the new one is written straight out, so memory stays small however
 * big the APK is. Refuses a patch made for another APK, and fails when the result is not exactly the new APK; the caller then
 * throws that output away and downloads the whole APK, so a bad patch can never reach the installer.
 */
object DeltaPatch {
    private val MAGIC = "LMIDELTA".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 8 + 4 + 8 + 32 + 32 + 8 + 8 + 8
    private const val CHUNK = 64 * 1024

    class Header(val newSize: Long, val oldSha256: ByteArray, val newSha256: ByteArray, val ctrl: Long, val diff: Long, val extra: Long)

    fun header(patch: File): Header = DataInputStream(FileInputStream(patch)).use { input ->
        val magic = ByteArray(8).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "not a Lock Me In patch" }
        require(input.readInt() == 1) { "a patch of a newer format" }
        val size = input.readLong()
        val old = ByteArray(32).also(input::readFully)
        val new = ByteArray(32).also(input::readFully)
        Header(size, old, new, input.readLong(), input.readLong(), input.readLong()).also {
            require(it.newSize >= 0 && it.ctrl >= 0 && it.diff >= 0 && it.extra >= 0) { "a broken patch" }
            require(HEADER_SIZE + it.ctrl + it.diff + it.extra == patch.length()) { "a broken patch" }
        }
    }

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }

    /**
     * Writes the new APK to [out]. Throws when [old] is not the APK the patch was made for (checked before anything is written)
     * or when what was written is not the new APK (checked at the end: the caller must then discard [out]).
     */
    fun apply(old: File, patch: File, out: OutputStream, progress: (Float) -> Unit = {}) {
        val head = header(patch)
        require(sha256(old).contentEquals(head.oldSha256)) { "the installed APK is not the one this patch is for" }
        val digest = MessageDigest.getInstance("SHA-256")
        fun section(offset: Long, length: Long): DataInputStream {
            val file = FileInputStream(patch)
            var skipped = 0L
            while (skipped < offset) skipped += file.skip(offset - skipped).also { require(it > 0) { "a broken patch" } }
            return DataInputStream(BufferedInputStream(InflaterInputStream(Limited(file, length)), CHUNK))
        }
        val start = HEADER_SIZE.toLong()
        section(start, head.ctrl).use { ctrl ->
            section(start + head.ctrl, head.diff).use { diff ->
                section(start + head.ctrl + head.diff, head.extra).use { extra ->
                    RandomAccessFile(old, "r").use { raf ->
                        val oldSize = raf.length()
                        val a = ByteArray(CHUNK)
                        val b = ByteArray(CHUNK)
                        var oldPos = 0L
                        var written = 0L
                        while (written < head.newSize) {
                            val x = ctrl.readLong()
                            val y = ctrl.readLong()
                            val z = ctrl.readLong()
                            require(x >= 0 && y >= 0 && written + x + y <= head.newSize) { "a broken patch" }
                            var left = x
                            while (left > 0) {
                                val n = minOf(left, CHUNK.toLong()).toInt()
                                diff.readFully(a, 0, n)
                                // bsdiff adds the old byte only where the old file has one.
                                java.util.Arrays.fill(b, 0, n, 0)
                                val from = maxOf(oldPos, 0L)
                                val until = minOf(oldPos + n, oldSize)
                                if (until > from) {
                                    raf.seek(from)
                                    raf.readFully(b, (from - oldPos).toInt(), (until - from).toInt())
                                }
                                for (i in 0 until n) a[i] = (a[i] + b[i]).toByte()
                                out.write(a, 0, n); digest.update(a, 0, n)
                                oldPos += n; left -= n; written += n
                            }
                            left = y
                            while (left > 0) {
                                val n = minOf(left, CHUNK.toLong()).toInt()
                                extra.readFully(a, 0, n)
                                out.write(a, 0, n); digest.update(a, 0, n)
                                left -= n; written += n
                            }
                            oldPos += z
                            if (head.newSize > 0) progress(written.toFloat() / head.newSize)
                        }
                    }
                }
            }
        }
        require(digest.digest().contentEquals(head.newSha256)) { "the rebuilt APK does not match the new one" }
    }

    /** Reads at most [left] bytes of [input]: one zlib section of the patch. */
    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            return super.read(b, off, minOf(len.toLong(), left).toInt()).also { if (it > 0) left -= it }
        }
        override fun available(): Int = minOf(super.available().toLong(), left).toInt()
    }
}
