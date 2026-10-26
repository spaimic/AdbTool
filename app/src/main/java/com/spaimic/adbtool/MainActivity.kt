package com.spaimic.adbtool

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_PERMISSION = "com.spaimic.adbtool.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var cmdInput: EditText
    private lateinit var remotePath: EditText
    private lateinit var chkReboot: CheckBox
    private lateinit var controls: View

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var conn: AdbConnection? = null
    @Volatile private var odinConn: OdinConnection? = null
    private var pullRemote = ""
    private var pendingFlash: List<FlashItem>? = null
    private val slots = listOf(Slot("BL"), Slot("AP"), Slot("CP"), Slot("CSC"), Slot("USERDATA"))
    private var pickingSlot: Slot? = null
    private var waitingOdinPermission = false

    private val pushPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doPush(uri)
    }
    private val sideloadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doSideload(uri)
    }
    private val tarPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val slot = pickingSlot
        if (uri != null && slot != null) loadSlot(slot, uri)
    }
    private val pullPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) doPull(uri) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    val dev = deviceFrom(i)
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val odin = waitingOdinPermission
                    waitingOdinPermission = false
                    if (dev == null || !granted) {
                        pendingFlash = null
                        log("USB permission denied")
                    } else if (odin) odinFlash(dev)
                    else connect(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = deviceFrom(i)
                    if (d == null || d.vendorId == 0x04E8) odinConn?.close()
                    if (conn != null) {
                        conn?.close()
                        conn = null
                        setStatus("Disconnected (device unplugged)")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        status = findViewById(R.id.status)
        logView = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        progress = findViewById(R.id.progress)
        cmdInput = findViewById(R.id.cmdInput)
        remotePath = findViewById(R.id.remotePath)
        chkReboot = findViewById(R.id.chkReboot)
        controls = findViewById(R.id.controls)

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { onConnectClicked() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.btnRun).setOnClickListener { doShell() }
        findViewById<Button>(R.id.btnReboot).setOnClickListener { doReboot("") }
        findViewById<Button>(R.id.btnRecovery).setOnClickListener { doReboot("recovery") }
        findViewById<Button>(R.id.btnBootloader).setOnClickListener { doReboot("bootloader") }
        findViewById<Button>(R.id.btnPush).setOnClickListener { pushPicker.launch(arrayOf("*/*")) }
        buildSlotRows()
        findViewById<Button>(R.id.btnOdinFlash).setOnClickListener { confirmFlash() }
        findViewById<Button>(R.id.btnSideload).setOnClickListener { sideloadPicker.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.btnPull).setOnClickListener {
            val r = remotePath.text.toString().trim()
            if (r.isEmpty() || r.endsWith("/")) {
                log("Enter the full remote file path to pull, e.g. /sdcard/file.zip")
            } else {
                pullRemote = r
                pullPicker.launch(r.substringAfterLast('/'))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        conn?.close()
        odinConn?.close()
        exec.shutdownNow()
    }

    // ------------------------------------------------------------ helpers

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun log(s: String) = ui {
        logView.append(s + "\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun logRaw(s: String) = ui {
        logView.append(s)
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = ui { status.text = s }
    private fun showProgress(p: Int) = ui { progress.progress = p }

    /** Only one operation at a time: every button is disabled while one runs. */
    private fun beginOp(): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        ui { setEnabledRec(controls, false) }
        return true
    }

    private fun endOp() {
        busy.set(false)
        ui { setEnabledRec(controls, true) }
    }

    private fun setEnabledRec(v: View, enabled: Boolean) {
        if (v is Button || v is CheckBox || v is EditText) v.isEnabled = enabled
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledRec(v.getChildAt(i), enabled)
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"

    private fun modelFrom(banner: String): String =
        Regex("ro\\.product\\.model=([^;]*)").find(banner)?.groupValues?.get(1)?.ifEmpty { null } ?: "device"

    private fun reasonOf(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Disconnected") || m.contains("device gone") ||
                m.contains("USB write failed") || m.contains("USB read failed") -> "Device Unplugged!!"
            m.contains("Timed out") -> "Device stopped responding"
            else -> m
        }
    }

    private fun sizeText(b: Long): String =
        if (b >= 1024 * 1024) "${b / (1024 * 1024)} MB" else "${b / 1024} KB"

    private fun runOp(name: String, block: (AdbConnection) -> Unit) {
        val c = conn ?: run { log("Not connected"); return }
        if (!beginOp()) return
        exec.execute {
            try {
                block(c)
            } catch (e: Exception) {
                log("✗ $name failed: ${e.message}")
            } finally {
                endOp()
                showProgress(0)
            }
        }
    }

    // ------------------------------------------------------------ ADB connection

    private fun findAdbDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any {
            val i = d.getInterface(it)
            i.interfaceClass == 255 && i.interfaceSubclass == 66 && i.interfaceProtocol == 1
        }
    }

    private fun onConnectClicked() {
        if (conn != null) { log("Already connected"); return }
        val dev = findAdbDevice()
        if (dev == null) {
            log("No ADB device found. Use a USB OTG cable, enable USB debugging (or boot to recovery / ADB sideload).")
            return
        }
        if (usbManager.hasPermission(dev)) {
            connect(dev)
        } else {
            requestUsbPermission(dev)
        }
    }

    private fun requestUsbPermission(dev: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
        usbManager.requestPermission(dev, pi)
    }

    private fun connect(dev: UsbDevice) {
        if (!beginOp()) return
        setStatus("Connecting…")
        exec.execute {
            var c: AdbConnection? = null
            try {
                var iface: UsbInterface? = null
                for (n in 0 until dev.interfaceCount) {
                    val i = dev.getInterface(n)
                    if (i.interfaceClass == 255 && i.interfaceSubclass == 66 && i.interfaceProtocol == 1) { iface = i; break }
                }
                if (iface == null) throw IOException("No ADB interface")
                val eps = bulkEndpoints(iface) ?: throw IOException("ADB bulk endpoints not found")

                val usb = usbManager.openDevice(dev) ?: throw IOException("openDevice failed")
                if (!usb.claimInterface(iface, true)) { usb.close(); throw IOException("claimInterface failed") }

                c = AdbConnection(usb, iface, eps.first, eps.second, AdbKeyStore.load(this))
                c.onAuthPrompt = { setStatus("Tap “Allow” on the target device…") }
                val banner = c.connect()
                conn = c
                val model = modelFrom(banner)
                setStatus("Connected: $model")
                log("Connected: $model")
            } catch (e: Exception) {
                c?.close()
                setStatus("Not connected")
                log("✗ Connect failed: ${e.message}")
            } finally {
                endOp()
            }
        }
    }

    private fun disconnect() {
        conn?.close()
        conn = null
        setStatus("Not connected")
    }

    // ------------------------------------------------------------ ADB operations

    private fun doShell() {
        val cmd = cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        runOp("shell") { c ->
            log("$ $cmd")
            c.shell(cmd) { logRaw(it) }
            log("")
        }
    }

    private fun doReboot(target: String) {
        runOp("reboot") { c ->
            log("Rebooting ${target.ifEmpty { "system" }}…")
            try { c.reboot(target) } catch (_: IOException) { /* device drops off the bus */ }
            c.close()
            conn = null
            setStatus("Not connected")
        }
    }

    private fun doPush(uri: Uri) {
        val name = displayName(uri)
        var remote = remotePath.text.toString().trim().ifEmpty { "/sdcard/" }
        if (remote.endsWith("/")) remote += name
        runOp("push") { c ->
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            pfd.use { p ->
                val size = p.statSize
                log("Pushing $name → $remote (${sizeText(size)})")
                FileInputStream(p.fileDescriptor).use { ins ->
                    c.push(ins, remote) { done -> if (size > 0) showProgress((done * 100 / size).toInt()) }
                }
            }
            log("✓ Push complete")
        }
    }

    private fun doPull(uri: Uri) {
        val remote = pullRemote
        runOp("pull") { c ->
            log("Pulling $remote…")
            val out = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot write file")
            val total = out.use { c.pull(remote, it) { } }
            log("✓ Pull complete (${sizeText(total)})")
        }
    }

    private fun doSideload(uri: Uri) {
        val name = displayName(uri)
        runOp("sideload") { c ->
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            pfd.use { p ->
                val size = p.statSize
                if (size <= 0) throw IOException("Cannot determine file size")
                log("Sideloading $name (${sizeText(size)})…")
                FileInputStream(p.fileDescriptor).channel.use { ch ->
                    val done = c.sideload(ch, size) { sent, total -> showProgress((sent * 100 / total).toInt()) }
                    log(if (done) "✓ Sideload finished" else "Stream closed by device – check its screen")
                }
            }
        }
    }

    // ------------------------------------------------------------ Download Mode (Odin)

    private fun buildSlotRows() {
        val box = findViewById<LinearLayout>(R.id.slots)
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        for (slot in slots) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val pick = Button(this)
            pick.text = slot.label
            val label = TextView(this)
            label.text = "—"
            label.setPadding(16, 0, 16, 0)
            val clear = Button(this)
            clear.text = "✕"
            pick.setOnClickListener {
                pickingSlot = slot
                tarPicker.launch(arrayOf("*/*"))
            }
            clear.setOnClickListener {
                slot.uri = null
                slot.name = ""
                slot.entries = emptyList()
                label.text = "—"
            }
            slot.nameView = label
            row.addView(pick, LinearLayout.LayoutParams(0, wrap, 1.2f))
            row.addView(label, LinearLayout.LayoutParams(0, wrap, 2.5f))
            row.addView(clear, LinearLayout.LayoutParams(0, wrap, 0.7f))
            box.addView(row)
        }
    }

    private fun bulkEndpoints(i: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (n in 0 until i.endpointCount) {
            val e = i.getEndpoint(n)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
            }
        }
        return if (inEp != null && outEp != null) Pair(inEp, outEp) else null
    }

    /** Samsung Download Mode ("Gadget Serial") exposes a CDC-data interface with two bulk endpoints. */
    private fun odinInterface(d: UsbDevice): UsbInterface? {
        if (d.vendorId != 0x04E8) return null
        var fallback: UsbInterface? = null
        for (n in 0 until d.interfaceCount) {
            val i = d.getInterface(n)
            if (bulkEndpoints(i) == null) continue
            if (i.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return i
            val isAdb = i.interfaceClass == 255 && i.interfaceSubclass == 66
            if (!isAdb && i.interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE && fallback == null) fallback = i
        }
        return fallback
    }

    private fun describeUsb(): String {
        val devs = usbManager.deviceList.values
        if (devs.isEmpty()) return "no USB devices visible"
        return devs.joinToString("; ") { d ->
            "%04x:%04x [".format(d.vendorId, d.productId) +
                (0 until d.interfaceCount).joinToString(" ") { n ->
                    val i = d.getInterface(n)
                    "if${i.id}:class${i.interfaceClass}/${i.endpointCount}ep"
                } + "]"
        }
    }

    /** Maps a tar entry such as "system.img.ext4.lz4" to a partition of the device. */
    private fun partitionFor(entryName: String, pit: List<PitEntry>): PitEntry? {
        var base = entryName.substringAfterLast('/')
        if (base.endsWith(".lz4", ignoreCase = true)) base = base.dropLast(4)
        val names = ArrayList<String>()
        names.add(base)
        if (base.endsWith(".ext4", ignoreCase = true)) names.add(base.dropLast(5))
        for (n in names) {
            val p = pit.firstOrNull { it.flashName.equals(n, ignoreCase = true) }
            if (p != null) return p
        }
        for (n in names) {
            val stem = n.substringBefore('.')
            val p = pit.firstOrNull { it.name.equals(stem, ignoreCase = true) }
            if (p != null) return p
        }
        return null
    }

    private fun loadSlot(slot: Slot, uri: Uri) {
        if (!beginOp()) return
        val name = displayName(uri)
        exec.execute {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
                pfd.use { p ->
                    FileInputStream(p.fileDescriptor).channel.use { ch ->
                        if (name.endsWith(".md5", ignoreCase = true)) {
                            log("Checking $name…")
                            when (TarUtil.verifyMd5(ch) { pct -> showProgress(pct) }) {
                                true -> {}
                                false -> throw IOException("MD5 mismatch – the file is corrupt")
                                null -> {}
                            }
                        }
                        // Folders (meta-data/…) and .pit files are not flashed, like Odin.
                        val entries = TarUtil.list(ch).filter { !it.name.contains('/') && !it.name.endsWith(".pit", true) }
                        if (entries.isEmpty()) throw IOException("No flashable files found in the tar")
                        if (entries.any { it.lz4 }) log("Reading compressed images…")
                        for (e in entries) {
                            if (e.lz4) e.rawSize = Lz4.measure(ch, e.dataOffset, e.size)
                        }
                        slot.uri = uri
                        slot.name = name
                        slot.entries = entries
                        ui { slot.nameView?.text = "$name (${entries.size} files)" }
                    }
                }
            } catch (e: Exception) {
                log("✗ ${slot.label}: ${e.message}")
            } finally {
                endOp()
                showProgress(0)
            }
        }
    }

    private fun confirmFlash() {
        val items = slots.filter { it.uri != null }.map { FlashItem(it.label, it.uri!!, it.name, it.entries) }
        if (items.isEmpty()) { log("Select at least one file first"); return }
        val msg = items.joinToString("\n\n") { item ->
            "${item.slot}: ${item.name}\n" + item.entries.joinToString("\n") { "  • ${it.name} (${sizeText(it.rawSize)})" }
        }
        AlertDialog.Builder(this)
            .setTitle("Flash in Download Mode?")
            .setMessage("$msg\n\nThe target must already be in Download Mode. A wrong image can brick it.")
            .setPositiveButton("Flash") { _, _ -> startOdin(items) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOdin(items: List<FlashItem>) {
        val dev = usbManager.deviceList.values.firstOrNull { odinInterface(it) != null }
        if (dev == null) {
            log("❌ Error: Unable to flash, REASON : No Download Mode device found")
            log("USB devices seen: ${describeUsb()}")
            return
        }
        pendingFlash = items
        if (usbManager.hasPermission(dev)) {
            odinFlash(dev)
        } else {
            waitingOdinPermission = true
            requestUsbPermission(dev)
        }
    }

    private fun odinFlash(dev: UsbDevice) {
        val items = pendingFlash ?: return
        pendingFlash = null
        if (!beginOp()) return
        val reboot = chkReboot.isChecked
        exec.execute {
            var odin: OdinConnection? = null
            var current: String? = null
            try {
                val iface = odinInterface(dev) ?: throw IOException("No Odin interface")
                val eps = bulkEndpoints(iface) ?: throw IOException("Odin bulk endpoints not found")
                var ctrl: UsbInterface? = null
                for (n in 0 until dev.interfaceCount) {
                    val i = dev.getInterface(n)
                    if (i.interfaceClass == UsbConstants.USB_CLASS_COMM) { ctrl = i; break }
                }

                val usb = usbManager.openDevice(dev) ?: throw IOException("openDevice failed")
                if (ctrl != null) usb.claimInterface(ctrl, true)
                if (!usb.claimInterface(iface, true)) { usb.close(); throw IOException("claimInterface failed") }
                usb.setInterface(iface)
                val o = OdinConnection(usb, iface, ctrl, eps.first, eps.second)
                odin = o
                odinConn = o

                setStatus("Download Mode: flashing…")
                o.setupSerial()
                o.handshake()
                o.beginSession()
                val pit = OdinConnection.parsePit(o.downloadPit())

                // Map every file to a partition BEFORE writing anything.
                val plan = ArrayList<PlanStep>()
                for (item in items) {
                    for (e in item.entries) {
                        val p = partitionFor(e.name, pit)
                        if (p == null) {
                            current = e.name.substringAfterLast('/')
                            throw IOException("No matching partition on this device")
                        }
                        val wantModem = item.slot == "CP"
                        if ((p.binaryType == 1) != wantModem) {
                            current = p.name
                            throw IOException("It belongs to the ${if (wantModem) "AP/BL/CSC" else "CP"} slot, not ${item.slot}")
                        }
                        plan.add(PlanStep(item, e, p))
                    }
                }

                val total = plan.fold(0L) { a, st -> a + st.entry.rawSize }
                o.totalBytes(total)

                var done = 0L
                for (item in items) {
                    val pfd = contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("Cannot open file")
                    pfd.use { pf ->
                        FileInputStream(pf.fileDescriptor).channel.use { ch ->
                            for (st in plan.filter { it.item === item }) {
                                current = st.part.name
                                log("Flashing ${st.part.name}...")
                                val input: InputStream =
                                    if (st.entry.lz4) Lz4InputStream(ch, st.entry.dataOffset, st.entry.size)
                                    else ChannelRangeStream(ch, st.entry.dataOffset, st.entry.size)
                                val base = done
                                o.flash(input, st.entry.rawSize, st.part, st.part.binaryType == 1) { sent ->
                                    showProgress(((base + sent) * 100 / total).toInt())
                                }
                                if (input is Lz4InputStream && input.read() != -1) {
                                    throw IOException("Decompressed size differs from the file header")
                                }
                                done += st.entry.rawSize
                                log("✅ Successfully Flashed ${st.part.name}")
                                current = null
                            }
                        }
                    }
                }

                current = null
                o.endSession()
                if (reboot) o.reboot()
            } catch (e: Exception) {
                val why = reasonOf(e)
                val c = current
                log(
                    if (c != null) "❌ Error: Unable to flash $c, REASON : $why"
                    else "❌ Error: Unable to start flashing, REASON : $why"
                )
                try { odin?.endSession(2000) } catch (_: Exception) {}
            } finally {
                odin?.close()
                odinConn = null
                endOp()
                showProgress(0)
                if (conn == null) setStatus("Not connected")
            }
        }
    }
}

class Slot(val label: String) {
    var uri: Uri? = null
    var name: String = ""
    var entries: List<TarEntry> = emptyList()
    var nameView: TextView? = null
}

class FlashItem(val slot: String, val uri: Uri, val name: String, val entries: List<TarEntry>)

class PlanStep(val item: FlashItem, val entry: TarEntry, val part: PitEntry)
