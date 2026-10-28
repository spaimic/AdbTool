package com.spaimic.adbtool

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val ctrl: UsbInterface?,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    private class Resp(val type: Int, val data: Int)
    private class OdinTimeout(msg: String) : IOException(msg)

    /** Protocol trace; goes to logcat only (tag "AdbTool.Odin"), never to the user-facing log. */
    var log: (String) -> Unit = { Log.d("AdbTool.Odin", it) }

    @Volatile private var closed = false
    val isOpen: Boolean get() = !closed
    private var packetSize = 131072
    private var seqMax = 800
    private val rbuf = ByteArray(2048)

    /** How control packets must be framed for this device: 0 = plain, 1 = + empty transfer, 2 = + 1024 padding bytes. */
    private var ctlMode = 0
    private var zlpData = false
    private var warnedIndex = false

    // ------------------------------------------------------------ USB layer

    private fun send(b: ByteArray) {
        if (b.size <= 2048) log("  → ${hex(b)}${if (b.size != 1024) " (${b.size}B)" else ""}")
        var o = 0
        while (o < b.size) {
            if (closed) throw IOException("Disconnected")
            val r = usb.bulkTransfer(epOut, b, o, min(16384, b.size - o), 10000)
            if (r < 0) throw IOException("USB write failed")
            o += r
        }
    }

    /** Zero-length bulk transfer (terminates a transfer that ended on a packet boundary). */
    private fun zlp() {
        log("  → (empty transfer)")
        try { usb.bulkTransfer(epOut, ByteArray(1), 0, 1000) } catch (_: Exception) {}
    }

    private fun recv(timeoutMs: Long = 15000): ByteArray {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var fast = 0
        while (true) {
            if (closed) throw IOException("Disconnected")
            val t0 = SystemClock.elapsedRealtime()
            val r = usb.bulkTransfer(epIn, rbuf, rbuf.size, 500)
            if (r > 0) {
                val d = rbuf.copyOf(r)
                log("  ← ${d.size}B ${hex(d)}")
                return d
            }
            if (r < 0 && SystemClock.elapsedRealtime() - t0 < 250) {
                fast++
                if (fast > 40) throw IOException("USB read failed (device gone?)")
            }
            if (SystemClock.elapsedRealtime() > deadline) throw OdinTimeout("Timed out waiting for the device")
        }
    }

    private fun drain() {
        for (i in 0 until 5) {
            val r = usb.bulkTransfer(epIn, rbuf, rbuf.size, 60)
            if (r <= 0) break
            log("  (discarded ${r}B stale data)")
        }
    }

    private fun hex(d: ByteArray) = d.take(12).joinToString(" ") { "%02x".format(it) }

    /** Control packets are always 1024 bytes of little-endian ints (type, subtype, data...). */
    private fun pkt(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) b.putInt(x)
        return b.array()
    }

    private fun resp(expect: Int, timeoutMs: Long = 15000): Resp {
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

    /**
     * Sends a control packet and reads the answer. If the device stays silent, it retries the framing
     * variants (empty transfer, padding) and remembers which one made it answer.
     */
    private fun <T> ask(req: ByteArray, timeoutMs: Long = 15000, probe: Boolean = true, read: (Long) -> T): T {
        drain()
        when (ctlMode) {
            0 -> send(req)
            1 -> { send(req); zlp() }
            else -> send(req + ByteArray(1024))
        }
        if (ctlMode == 0 && probe) {
            try { return read(2500) } catch (_: OdinTimeout) {}
            for (mode in 1..2) {
                log("  no reply – trying ${if (mode == 1) "an empty transfer" else "a padded packet"}")
                if (mode == 1) zlp() else send(ByteArray(1024))
                try {
                    val r = read(3000)
                    ctlMode = mode
                    log("  (device needs framing mode $mode)")
                    return r
                } catch (_: OdinTimeout) {}
            }
        }
        return read(timeoutMs)
    }

    fun close() {
        if (closed) return
        closed = true
        try { usb.releaseInterface(iface) } catch (_: Exception) {}
        try { if (ctrl != null) usb.releaseInterface(ctrl) } catch (_: Exception) {}
        try { usb.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ session

    /** CDC-ACM setup: 115200 8N1 line coding, then raise DTR+RTS (what a PC COM-port driver does on open). */
    fun setupSerial() {
        if (ctrl == null) { log("  (no CDC control interface found)"); return }
        val lc = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
        val r1 = usb.controlTransfer(0x21, 0x20, 0, ctrl.id, lc, lc.size, 1000)
        val r2 = usb.controlTransfer(0x21, 0x22, 0x03, ctrl.id, null, 0, 1000)
        log("  line coding: $r1, DTR/RTS: $r2")
    }

    fun handshake() {
        drain()
        for (attempt in 1..3) {
            send("ODIN".toByteArray())
            try {
                val d = recv(4000)
                if (d.size >= 4 && String(d, 0, 4) == "LOKE") return
                log("  unexpected reply: ${hex(d)}")
            } catch (e: OdinTimeout) {
                log("  no answer to ODIN (attempt $attempt/3)")
            }
        }
        throw IOException("Device never answered LOKE after 3 tries")
    }

    /** Returns the device's reported default-packet-size field (0 = old protocol). */
    fun beginSession(): Int {
        val r = ask(pkt(0x64, 0, 4)) { resp(0x64, it) }
        if (r.data != 0) {
            ask(pkt(0x64, 5, 1048576)) { resp(0x64, it) }
            packetSize = 1048576
            seqMax = 30
        }
        return r.data
    }

    fun downloadPit(): ByteArray {
        val size = ask(pkt(0x65, 1)) { resp(0x65, it) }.data
        log("  PIT size: $size bytes")
        if (size <= 0 || size > 1_000_000) throw IOException("Bad PIT size: $size")
        val out = ByteArray(size)
        val parts = (size + 499) / 500
        for (i in 0 until parts) {
            val d = ask(pkt(0x65, 2, i)) { recv(it) }
            val n = min(min(d.size, 500), size - i * 500)
            System.arraycopy(d, 0, out, i * 500, n)
        }
        ask(pkt(0x65, 3)) { resp(0x65, it) }
        return out
    }

    fun totalBytes(total: Long) {
        ask(pkt(0x64, 2, total.toInt(), (total ushr 32).toInt())) { resp(0x64, it) }
    }

    /** Flashes [size] bytes read from [input] to the partition described by [e]. */
    fun flash(input: InputStream, size: Long, e: PitEntry, modem: Boolean, progress: (Long) -> Unit) {
        ask(pkt(0x66, 0)) { resp(0x66, it) }

        val seqBytes = seqMax.toLong() * packetSize
        val seqCount = ((size + seqBytes - 1) / seqBytes).toInt()
        val buf = ByteArray(packetSize)
        var sent = 0L

        for (s in 0 until seqCount) {
            val isLast = s == seqCount - 1
            val seqStart = s * seqBytes
            val effective = if (isLast) size - seqStart else seqBytes
            val packets = ((effective + packetSize - 1) / packetSize).toInt()

            ask(pkt(0x66, 2, packets * packetSize)) { resp(0x66, it) }

            for (p in 0 until packets) {
                val want = min(packetSize.toLong(), effective - p.toLong() * packetSize).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(buf, got, want - got)
                    if (n < 0) throw IOException("Image data ended early")
                    got += n
                }
                java.util.Arrays.fill(buf, want, buf.size, 0.toByte())
                sendPart(buf, p)
                sent += want
                progress(sent)
            }

            val last = if (isLast) 1 else 0
            val endPkt = if (modem) pkt(0x66, 3, 1, effective.toInt(), 0, e.deviceType, last)
            else pkt(0x66, 3, 0, effective.toInt(), 0, e.deviceType, e.id, last)
            val r = ask(endPkt, 300000, probe = false) { resp(0x66, it) }
            log("  device status: ${r.data}")
            if (r.data != 0) throw IOException("Device rejected the data (status ${r.data})")
        }
    }

    /** Data packets are acknowledged with response type 0x00 whose data field is the part index. */
    private fun sendPart(buf: ByteArray, index: Int) {
        send(buf)
        if (zlpData) zlp()
        val r: Resp = try {
            resp(0x00, if (zlpData) 30000 else 5000)
        } catch (e: OdinTimeout) {
            if (zlpData) throw e
            log("  no part ack – trying an empty transfer")
            zlp()
            val r2 = resp(0x00, 30000)
            zlpData = true
            log("  (device needs empty transfers after data packets)")
            r2
        }
        if (r.data != index && !warnedIndex) {
            warnedIndex = true
            log("  note: part ack index ${r.data}, expected $index")
        }
    }

    fun endSession(timeoutMs: Long = 15000) {
        ask(pkt(0x67, 0), timeoutMs, probe = false) { resp(0x67, it) }
    }

    fun reboot() {
        ask(pkt(0x67, 1), 15000, probe = false) { resp(0x67, it) }
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
