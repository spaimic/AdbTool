package com.spaimic.adbtool

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/** Reads a byte range of a file as a stream. */
class ChannelRangeStream(private val ch: FileChannel, start: Long, length: Long) : InputStream() {
    private var pos = start
    private val end = start + length

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= end) return -1
        val n = min(len.toLong(), end - pos).toInt()
        val r = ch.read(ByteBuffer.wrap(b, off, n), pos)
        if (r < 0) return -1
        pos += r
        return r
    }
}

/**
 * Streaming LZ4 *frame* decoder (what `lz4 file` and Samsung's .lz4 firmware images use).
 * Supports independent and linked blocks, block/content checksums (not verified), several frames.
 */
class Lz4InputStream(private val ch: FileChannel, start: Long, length: Long) : InputStream() {
    private var pos = start
    private val end = start + length

    private var inFrame = false
    private var blockIndep = true
    private var blockChecksum = false
    private var contentChecksum = false
    private var maxBlock = 0
    private var declared = -1L

    private var win = ByteArray(0)      // [history | current block output]
    private var src = ByteArray(0)
    private var hist = 0
    private var prevEnd = 0
    private var outPos = 0
    private var outEnd = 0

    /** Content size stored in the first frame header, or -1 if the encoder did not store one. */
    fun declaredSize(): Long {
        ensureHeader()
        return declared
    }

    // ------------------------------------------------------------ raw input

    private fun readFully(dst: ByteArray, n: Int) {
        if (pos + n > end) throw IOException("Unexpected end of LZ4 data")
        val bb = ByteBuffer.wrap(dst, 0, n)
        while (bb.hasRemaining()) {
            if (ch.read(bb, pos + bb.position()) < 0) throw IOException("Unexpected end of LZ4 data")
        }
        pos += n
    }

    private val tmp = ByteArray(8)

    private fun u8(): Int { readFully(tmp, 1); return tmp[0].toInt() and 0xff }

    private fun i32(): Int {
        readFully(tmp, 4)
        return (tmp[0].toInt() and 0xff) or ((tmp[1].toInt() and 0xff) shl 8) or
            ((tmp[2].toInt() and 0xff) shl 16) or ((tmp[3].toInt() and 0xff) shl 24)
    }

    private fun i64(): Long {
        readFully(tmp, 8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (tmp[i].toLong() and 0xff)
        return v
    }

    // ------------------------------------------------------------ frames and blocks

    private fun ensureHeader(): Boolean {
        while (!inFrame) {
            if (pos >= end) return false
            val magic = i32()
            if (magic and 0xFFFFFFF0.toInt() == 0x184D2A50) {       // skippable frame
                val n = i32()
                if (n < 0 || pos + n > end) throw IOException("Corrupt LZ4 skippable frame")
                pos += n
                continue
            }
            if (magic != 0x184D2204) throw IOException("Not an LZ4 frame")
            val flg = u8()
            val bd = u8()
            if ((flg ushr 6) != 1) throw IOException("Unsupported LZ4 version")
            blockIndep = flg and 0x20 != 0
            blockChecksum = flg and 0x10 != 0
            val hasSize = flg and 0x08 != 0
            contentChecksum = flg and 0x04 != 0
            if (flg and 0x01 != 0) throw IOException("LZ4 dictionaries are not supported")
            maxBlock = when ((bd ushr 4) and 7) {
                4 -> 64 * 1024
                5 -> 256 * 1024
                6 -> 1024 * 1024
                7 -> 4 * 1024 * 1024
                else -> throw IOException("Bad LZ4 block size")
            }
            val size = if (hasSize) i64() else -1L
            if (declared < 0 && size >= 0) declared = size
            u8()                                                    // header checksum
            if (win.size != 65536 + maxBlock) { win = ByteArray(65536 + maxBlock); src = ByteArray(maxBlock) }
            hist = 0
            prevEnd = 0
            inFrame = true
        }
        return true
    }

    private fun nextBlock(): Boolean {
        if (prevEnd > 0) {                                          // keep the last 64 KB as history
            if (blockIndep) hist = 0
            else {
                val keep = min(65536, prevEnd)
                if (prevEnd - keep > 0) System.arraycopy(win, prevEnd - keep, win, 0, keep)
                hist = keep
            }
            prevEnd = 0
        }
        while (true) {
            if (!ensureHeader()) return false
            val bs = i32()
            if (bs == 0) {                                          // end mark
                if (contentChecksum) i32()
                inFrame = false
                continue
            }
            val raw = bs and Int.MIN_VALUE != 0
            val size = bs and Int.MAX_VALUE
            if (size > maxBlock) throw IOException("Corrupt LZ4 block size")
            readFully(src, size)
            if (blockChecksum) i32()
            val base = hist
            val n = if (raw) {
                System.arraycopy(src, 0, win, base, size)
                size
            } else {
                try {
                    decode(size, base)
                } catch (e: IndexOutOfBoundsException) {
                    throw IOException("Corrupt LZ4 data")
                }
            }
            outPos = base
            outEnd = base + n
            prevEnd = outEnd
            return true
        }
    }

    private fun decode(n: Int, base: Int): Int {
        val s = src
        val d = win
        var ip = 0
        var op = base
        while (ip < n) {
            val token = s[ip++].toInt() and 0xff
            var lit = token ushr 4
            if (lit == 15) {
                var b: Int
                do { b = s[ip++].toInt() and 0xff; lit += b } while (b == 255)
            }
            if (ip + lit > n || op + lit > d.size) throw IOException("Corrupt LZ4 data")
            System.arraycopy(s, ip, d, op, lit)
            ip += lit
            op += lit
            if (ip >= n) break
            val offset = (s[ip].toInt() and 0xff) or ((s[ip + 1].toInt() and 0xff) shl 8)
            ip += 2
            var ml = token and 15
            if (ml == 15) {
                var b: Int
                do { b = s[ip++].toInt() and 0xff; ml += b } while (b == 255)
            }
            ml += 4
            var from = op - offset
            if (offset == 0 || from < 0 || op + ml > d.size) throw IOException("Corrupt LZ4 data")
            if (offset >= ml) {
                System.arraycopy(d, from, d, op, ml)
                op += ml
            } else {
                for (i in 0 until ml) d[op++] = d[from++]
            }
        }
        return op - base
    }

    // ------------------------------------------------------------ InputStream

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (outPos >= outEnd) {
            if (!nextBlock()) return -1
        }
        val n = min(len, outEnd - outPos)
        System.arraycopy(win, outPos, b, off, n)
        outPos += n
        return n
    }
}

object Lz4 {
    /** Decompressed size of an LZ4 file: from the frame header if present, otherwise by decoding it once. */
    fun measure(ch: FileChannel, start: Long, length: Long): Long {
        val s = Lz4InputStream(ch, start, length)
        val d = s.declaredSize()
        if (d >= 0) return d
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            total += n
        }
        return total
    }
}
