package com.spaimic.adbtool

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.KeyPair
import kotlin.math.min

private const val A_CNXN = 0x4e584e43
private const val A_AUTH = 0x48545541
private const val A_OPEN = 0x4e45504f
private const val A_OKAY = 0x59414b4f
private const val A_CLSE = 0x45534c43
private const val A_WRTE = 0x45545257

private const val AUTH_TOKEN = 1
private const val AUTH_SIGNATURE = 2
private const val AUTH_RSAPUBLICKEY = 3

private val EMPTY = ByteArray(0)

/** Minimal ADB host implementation over a claimed USB interface. One stream at a time, blocking. */
class AdbConnection(
    private val usb: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val keys: KeyPair
) {
    class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    @Volatile
    var closed = false
        private set
    var maxData = 4096
        private set
    var onAuthPrompt: (() -> Unit)? = null
    private var nextId = 1

    // ---------------------------------------------------------------- USB layer

    private fun bulkWrite(b: ByteArray, off: Int, len: Int) {
        var o = 0
        while (o < len) {
            if (closed) throw IOException("Disconnected")
            val r = usb.bulkTransfer(epOut, b, off + o, min(16384, len - o), 10000)
            if (r < 0) throw IOException("USB write failed")
            o += r
        }
    }

    private fun bulkReadFully(len: Int): ByteArray {
        val buf = ByteArray(len)
        var o = 0
        var fastFails = 0
        while (o < len) {
            if (closed) throw IOException("Disconnected")
            val t0 = SystemClock.elapsedRealtime()
            val r = usb.bulkTransfer(epIn, buf, o, min(16384, len - o), 1000)
            if (r < 0) {
                // A normal idle timeout takes ~1s; instant failures mean the device is gone.
                if (SystemClock.elapsedRealtime() - t0 < 500) {
                    if (++fastFails > 20) throw IOException("USB read failed (device gone?)")
                } else fastFails = 0
                continue
            }
            fastFails = 0
            o += r
        }
        return buf
    }

    // ---------------------------------------------------------------- Messages

    @Synchronized
    fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size) {
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd.inv())
        bulkWrite(h.array(), 0, 24)
        if (len > 0) bulkWrite(data, off, len)
    }

    fun readMessage(): Msg {
        val h = ByteBuffer.wrap(bulkReadFully(24)).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = h.int
        val a0 = h.int
        val a1 = h.int
        val len = h.int
        h.int // checksum (ignored)
        val magic = h.int
        if (magic != cmd.inv()) throw IOException("Bad ADB message magic")
        val data = if (len > 0) bulkReadFully(len) else EMPTY
        return Msg(cmd, a0, a1, data)
    }

    // ---------------------------------------------------------------- Handshake

    /** Returns the device banner (e.g. "device::...", "recovery::...", "sideload::..."). */
    fun connect(): String {
        send(A_CNXN, 0x01000001, 256 * 1024, "host::adbtool\u0000".toByteArray())
        var signed = false
        while (true) {
            val m = readMessage()
            when (m.cmd) {
                A_CNXN -> {
                    maxData = if (m.arg1 > 0) min(m.arg1, 65536) else 4096
                    return String(m.data).trim('\u0000')
                }
                A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                    if (!signed) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, AdbKeyStore.signToken(keys, m.data))
                        signed = true
                    } else {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, AdbKeyStore.androidPublicKey(keys, "adbtool@android"))
                        onAuthPrompt?.invoke()
                    }
                }
            }
        }
    }

    fun open(dest: String): AdbStream {
        val id = nextId++
        send(A_OPEN, id, 0, "$dest\u0000".toByteArray())
        while (true) {
            val m = readMessage()
            if (m.arg1 != id) continue
            if (m.cmd == A_OKAY) return AdbStream(this, id, m.arg0)
            if (m.cmd == A_CLSE) throw IOException("Device refused service: $dest")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try { usb.releaseInterface(iface) } catch (_: Exception) {}
        try { usb.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- Services

    fun shell(cmd: String, onOutput: (String) -> Unit) {
        val s = open("shell:$cmd")
        try {
            while (true) {
                val d = s.read() ?: break
                onOutput(String(d, Charsets.UTF_8))
            }
        } finally { s.close() }
    }

    /** target: "" (system), "recovery", "bootloader", ... */
    fun reboot(target: String) {
        val s = open("reboot:$target")
        try { while (s.read() != null) { /* wait for close */ } } finally { s.close() }
    }

    private fun syncHdr(id: String, n: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray()).putInt(n).array()

    fun push(input: InputStream, remote: String, mode: Int = 33206, progress: (Long) -> Unit) {
        val s = open("sync:")
        try {
            val spec = "$remote,$mode".toByteArray()
            s.write(syncHdr("SEND", spec.size) + spec)
            val buf = ByteArray(65536)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val pkt = ByteArray(8 + n)
                System.arraycopy(syncHdr("DATA", n), 0, pkt, 0, 8)
                System.arraycopy(buf, 0, pkt, 8, n)
                s.write(pkt)
                total += n
                progress(total)
            }
            s.write(syncHdr("DONE", (System.currentTimeMillis() / 1000).toInt()))
            val r = s.readExact(8)
            val id = String(r, 0, 4)
            val len = ByteBuffer.wrap(r, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "FAIL") throw IOException(String(s.readExact(len)))
            if (id != "OKAY") throw IOException("Unexpected sync reply: $id")
            s.write(syncHdr("QUIT", 0))
        } finally { s.close() }
    }

    fun pull(remote: String, out: OutputStream, progress: (Long) -> Unit): Long {
        val s = open("sync:")
        try {
            val p = remote.toByteArray()
            s.write(syncHdr("RECV", p.size) + p)
            var total = 0L
            while (true) {
                val h = s.readExact(8)
                val id = String(h, 0, 4)
                val len = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                when (id) {
                    "DATA" -> { out.write(s.readExact(len)); total += len; progress(total) }
                    "DONE" -> break
                    "FAIL" -> throw IOException(String(s.readExact(len)))
                    else -> throw IOException("Unexpected sync reply: $id")
                }
            }
            s.write(syncHdr("QUIT", 0))
            return total
        } finally { s.close() }
    }

    /** adb sideload: the device requests 64 KiB blocks by number; we serve them from the file. Returns true on DONEDONE. */
    fun sideload(file: FileChannel, size: Long, progress: (Long, Long) -> Unit): Boolean {
        val bs = 65536
        val s = open("sideload-host:$size:$bs")
        try {
            while (true) {
                val d = s.read() ?: return false
                val txt = String(d).trim()
                if (txt.startsWith("DONEDONE")) return true
                val block = txt.take(8).toLongOrNull() ?: throw IOException("Bad block request: $txt")
                val off = block * bs
                if (off >= size) throw IOException("Block $block out of range")
                val len = min(bs.toLong(), size - off).toInt()
                val bb = ByteBuffer.allocate(len)
                while (bb.hasRemaining()) {
                    if (file.read(bb, off + bb.position()) < 0) throw IOException("Unexpected end of file")
                }
                s.write(bb.array())
                progress(off + len, size)
            }
        } finally { s.close() }
    }
}

class AdbStream(private val conn: AdbConnection, private val localId: Int, private val remoteId: Int) {
    private var closed = false
    private var okayPending = false
    private val inbox = ArrayDeque<ByteArray>()
    private var cur = EMPTY
    private var curPos = 0

    private fun pump() {
        val m = conn.readMessage()
        if (m.arg1 != localId) return
        when (m.cmd) {
            A_OKAY -> okayPending = false
            A_WRTE -> { inbox.addLast(m.data); conn.send(A_OKAY, localId, remoteId) }
            A_CLSE -> closed = true
        }
    }

    fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            if (closed) throw IOException("Stream closed by device")
            val n = min(conn.maxData, data.size - off)
            conn.send(A_WRTE, localId, remoteId, data, off, n)
            okayPending = true
            while (okayPending && !closed) pump()
            off += n
        }
    }

    /** Next chunk from the device, or null once the stream is closed. */
    fun read(): ByteArray? {
        while (inbox.isEmpty() && !closed) pump()
        return inbox.removeFirstOrNull()
    }

    fun readExact(n: Int): ByteArray {
        val out = ByteArray(n)
        var o = 0
        while (o < n) {
            if (curPos >= cur.size) {
                cur = read() ?: throw IOException("Stream closed by device")
                curPos = 0
            }
            val c = min(n - o, cur.size - curPos)
            System.arraycopy(cur, curPos, out, o, c)
            o += c
            curPos += c
        }
        return out
    }

    fun close() {
        if (closed) return
        closed = true
        try { conn.send(A_CLSE, localId, remoteId) } catch (_: IOException) {}
    }
}
