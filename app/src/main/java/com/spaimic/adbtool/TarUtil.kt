package com.spaimic.adbtool

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.math.min

class TarEntry(val name: String, val size: Long, val dataOffset: Long)

/** Minimal tar reader for Odin-style .tar / .tar.md5 files. Random access, nothing is extracted. */
object TarUtil {

    private fun readAt(ch: FileChannel, buf: ByteBuffer, pos: Long) {
        var p = pos
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n < 0) throw IOException("Unexpected end of file")
            p += n
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    fun list(ch: FileChannel): List<TarEntry> {
        val total = ch.size()
        val out = ArrayList<TarEntry>()
        var off = 0L
        while (off + 512 <= total) {
            val hdr = ByteBuffer.allocate(512)
            readAt(ch, hdr, off)
            val b = hdr.array()
            if (b.all { it == 0.toByte() }) break
            val name = cstr(b, 0, 100)
            val prefix = cstr(b, 345, 155)
            val size = cstr(b, 124, 12).trim().toLongOrNull(8)
                ?: throw IOException("Not a valid tar file (bad header at byte $off)")
            val type = b[156].toInt().toChar()
            val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (type == '0' || type == '\u0000') out.add(TarEntry(full.removePrefix("./"), size, off + 512))
            off += 512 + ((size + 511) / 512) * 512
        }
        return out
    }

    /** true = MD5 matches, false = mismatch, null = no MD5 line at the end of the file. */
    fun verifyMd5(ch: FileChannel): Boolean? {
        val total = ch.size()
        val tailLen = min(total, 1024L).toInt()
        val tail = ByteBuffer.allocate(tailLen)
        readAt(ch, tail, total - tailLen)
        val text = String(tail.array(), Charsets.ISO_8859_1)
        val trimmed = text.trimEnd('\n', '\r', ' ', '\u0000')
        // Odin appends "<md5>  <name>" right after the tar's zero blocks, often without a leading newline.
        val m = Regex("[0-9a-fA-F]{32}[ \\t]+[^\\n\\u0000]+$").find(trimmed) ?: return null
        val lineStart = m.range.first
        val hash = m.value.take(32).lowercase()

        val contentLen = total - tailLen + lineStart
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteBuffer.allocate(1 shl 20)
        var pos = 0L
        while (pos < contentLen) {
            buf.clear()
            buf.limit(min(buf.capacity().toLong(), contentLen - pos).toInt())
            readAt(ch, buf, pos)
            md.update(buf.array(), 0, buf.limit())
            pos += buf.limit()
        }
        return md.digest().joinToString("") { "%02x".format(it) } == hash
    }
}
