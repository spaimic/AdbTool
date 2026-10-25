package com.spaimic.adbtool

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min

class PitEntry(
    val binaryType: Int,
    val deviceType: Int,
    val id: Int,
    val name: String,
    val flashName: String
)

/** Samsung Odin ("LOKE") protocol used in Download Mode. Written after Heimdall's implementation. */
class OdinConnection(
    private val usb: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    private class Resp(val type: Int, val data: Int)

    var log: (String) -> Unit = {}

    @Volatile private var closed = false
    private var packetSize = 131072
    private var seqMax = 800
    private val rbuf = ByteArray(2048)

    // ------------------------------------------------------------ USB layer

    private fun send(b: ByteArray) {
        var o = 0
        while (o < b.size) {
            if (closed) throw IOException("Disconnected")
            val r = usb.bulkTransfer(epOut, b, o, min(16384, b.size - o), 10000)
            if (r < 0) throw IOException("USB write failed")
            o += r
        }
    }

    private fun recv(timeoutMs: Long = 30000): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var fast = 0
        while (true) {
            if (closed) throw IOException("Disconnected")
            val t0 = SystemClock.elapsedRealtime()
            val r = usb.bulkTransfer(epIn, rbuf, rbuf.size, 1000)
            if (r > 0) return rbuf.copyOf(r)
            if (r < 0 && SystemClock.elapsedRealtime() - t0 < 500) {
                fast++
                if (fast > 20) throw IOException("USB read failed (device gone?)")
            }
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("Timed out waiting for the device")
        }
    }

    /** Control packets are always 1024 bytes of little-endian ints (type, subtype, data...). */
    private fun pkt(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putInt(x)
        return b.array()
    }

    private fun resp(expect: Int, timeoutMs: Long = 30000): Resp {
        val d = recv(timeoutMs)
        if (d.size < 8) throw IOException("Short response (${d.size} bytes)")
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val r = Resp(bb.int, bb.int)
        if (r.type != expect) {
            throw IOException(
                "Unexpected reply 0x${Integer.toHexString(r.type)} (wanted 0x${Integer.toHexString(expect)}, data ${r.data})"
            )
        }
        return r
    }

    fun close() {
        if (closed) return
        closed = true
        try { usb.releaseInterface(iface) } catch (_: Exception) {}
        try { usb.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ session

    fun handshake() {
        send("ODIN".toByteArray())
        val d = recv(5000)
        if (d.size < 4 || String(d, 0, 4) != "LOKE") {
            throw IOException("Device did not answer LOKE – is it in Download Mode?")
        }
    }

    /** Returns the device's reported default-packet-size field (0 = old protocol). */
    fun beginSession(): Int {
        send(pkt(0x64, 0, 4))
        val r = resp(0x64)
        if (r.data != 0) {
            send(pkt(0x64, 5, 1048576))
            resp(0x64)
            packetSize = 1048576
            seqMax = 30
        }
        return r.data
    }

    fun downloadPit(): ByteArray {
        send(pkt(0x65, 1))
        val size = resp(0x65).data
        if (size <= 0 || size > 1_000_000) throw IOException("Bad PIT size: $size")
        val out = ByteArray(size)
        val parts = (size + 499) / 500
        for (i in 0 until parts) {
            send(pkt(0x65, 2, i))
            val d = recv()
            val n = min(min(d.size, 500), size - i * 500)
            System.arraycopy(d, 0, out, i * 500, n)
        }
        send(pkt(0x65, 3))
        resp(0x65)
        return out
    }

    fun totalBytes(total: Long) {
        send(pkt(0x64, 2, total.toInt(), (total ushr 32).toInt()))
        resp(0x64)
    }

    /** Flashes one file (a region of [ch]) to the partition described by [e]. */
    fun flash(ch: FileChannel, dataOffset: Long, size: Long, e: PitEntry, progress: (Long) -> Unit) {
        send(pkt(0x66, 0))
        resp(0x66)

        val seqBytes = seqMax.toLong() * packetSize
        val seqCount = ((size + seqBytes - 1) / seqBytes).toInt()
        val buf = ByteArray(packetSize)
        var sent = 0L

        for (s in 0 until seqCount) {
            val isLast = s == seqCount - 1
            val seqStart = s * seqBytes
            val effective = if (isLast) size - seqStart else seqBytes
            val packets = ((effective + packetSize - 1) / packetSize).toInt()

            send(pkt(0x66, 2, packets * packetSize))
            resp(0x66)

            for (p in 0 until packets) {
                val want = min(packetSize.toLong(), effective - p.toLong() * packetSize).toInt()
                val bb = ByteBuffer.wrap(buf, 0, want)
                var pos = dataOffset + seqStart + p.toLong() * packetSize
                while (bb.hasRemaining()) {
                    val n = ch.read(bb, pos)
                    if (n < 0) throw IOException("Unexpected end of file")
                    pos += n
                }
                java.util.Arrays.fill(buf, want, buf.size, 0.toByte())
                send(buf)
                resp(0x66)
                sent += want
                progress(sent)
            }

            send(pkt(0x66, 3, 0, effective.toInt(), 0, e.deviceType, e.id, if (isLast) 1 else 0))
            val r = resp(0x66, 300000)
            log("  device status: ${r.data}")
        }
    }

    fun endSession() {
        send(pkt(0x67, 0))
        resp(0x67)
    }

    fun reboot() {
        send(pkt(0x67, 1))
        resp(0x67)
    }

    companion object {
        fun parsePit(b: ByteArray): List<PitEntry> {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (b.size < 28 || bb.getInt(0) != 0x12349876) throw IOException("Invalid PIT data")
            val count = bb.getInt(4)
            val out = ArrayList<PitEntry>()
            for (i in 0 until count) {
                val o = 28 + i * 132
                if (o + 132 > b.size) break
                out.add(
                    PitEntry(
                        binaryType = bb.getInt(o),
                        deviceType = bb.getInt(o + 4),
                        id = bb.getInt(o + 8),
                        name = cstr(b, o + 36),
                        flashName = cstr(b, o + 68)
                    )
                )
            }
            return out
        }

        private fun cstr(b: ByteArray, off: Int): String {
            var end = off
            while (end < off + 32 && b[end].toInt() != 0) end++
            return String(b, off, end - off, Charsets.UTF_8)
        }
    }
}
