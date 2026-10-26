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

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var conn: AdbConnection? = null
    private var pullRemote = ""
    private lateinit var chkReboot: CheckBox
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

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"

    private fun runOp(name: String, block: (AdbConnection) -> Unit) {
        val c = conn ?: run { log("Not connected"); return }
        if (!busy.compareAndSet(false, true)) { log("Busy – wait for the current operation"); return }
        exec.execute {
            try {
                block(c)
            } catch (e: Exception) {
                log("✗ $name failed: ${e.message}")
            } finally {
                busy.set(false)
                showProgress(0)
            }
        }
    }

    // ------------------------------------------------------------ connection

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
        if (!busy.compareAndSet(false, true)) return
        setStatus("Connecting…")
        exec.execute {
            var c: AdbConnection? = null
            try {
                var iface: android.hardware.usb.UsbInterface? = null
                for (n in 0 until dev.interfaceCount) {
                    val i = dev.getInterface(n)
                    if (i.interfaceClass == 255 && i.interfaceSubclass == 66 && i.interfaceProtocol == 1) { iface = i; break }
                }
                if (iface == null) throw IOException("No ADB interface")
                var epIn: android.hardware.usb.UsbEndpoint? = null
                var epOut: android.hardware.usb.UsbEndpoint? = null
                for (n in 0 until iface.endpointCount) {
                    val e = iface.getEndpoint(n)
                    if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
                    }
                }
                if (epIn == null || epOut == null) throw IOException("ADB bulk endpoints not found")

                val usb = usbManager.openDevice(dev) ?: throw IOException("openDevice failed")
                if (!usb.claimInterface(iface, true)) { usb.close(); throw IOException("claimInterface failed") }

                c = AdbConnection(usb, iface, epIn, epOut, AdbKeyStore.load(this))
                c.onAuthPrompt = { setStatus("Tap “Allow” on the target device…") }
                val banner = c.connect()
                conn = c
                setStatus("Connected: $banner")
                log("Connected: $banner")
            } catch (e: Exception) {
                c?.close()
                setStatus("Not connected")
                log("✗ Connect failed: ${e.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun disconnect() {
        conn?.close()
        conn = null
        setStatus("Not connected")
    }

    // ------------------------------------------------------------ operations

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
                log("Pushing $name → $remote ($size bytes)")
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
            log("✓ Pull complete ($total bytes)")
        }
    }

    private fun doSideload(uri: Uri) {
        val name = displayName(uri)
        runOp("sideload") { c ->
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            pfd.use { p ->
                val size = p.statSize
                if (size <= 0) throw IOException("Cannot determine file size")
                log("Sideloading $name ($size bytes)…")
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

    private fun loadSlot(slot: Slot, uri: Uri) {
        if (!busy.compareAndSet(false, true)) { log("Busy – wait for the current operation"); return }
        val name = displayName(uri)
        exec.execute {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
                pfd.use { p ->
                    FileInputStream(p.fileDescriptor).channel.use { ch ->
                        if (name.endsWith(".md5", ignoreCase = true)) {
                            log("Verifying MD5 of $name…")
                            when (TarUtil.verifyMd5(ch)) {
                                true -> log("✓ MD5 OK")
                                false -> throw IOException("MD5 mismatch – the file is corrupt")
                                null -> log("No MD5 line found, skipping check")
                            }
                        }
                        val entries = TarUtil.list(ch)
                        if (entries.isEmpty()) throw IOException("No files found in the tar")
                        val lz4 = entries.firstOrNull { it.name.endsWith(".lz4", ignoreCase = true) }
                        if (lz4 != null) throw IOException("${lz4.name}: LZ4-compressed images are not supported yet")
                        slot.uri = uri
                        slot.name = name
                        slot.entries = entries
                        ui { slot.nameView?.text = "$name (${entries.size} files)" }
                    }
                }
            } catch (e: Exception) {
                log("✗ ${slot.label}: ${e.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun confirmFlash() {
        val items = slots.filter { it.uri != null }.map { FlashItem(it.label, it.uri!!, it.name, it.entries) }
        if (items.isEmpty()) { log("Select at least one file first"); return }
        val msg = items.joinToString("\n\n") { item ->
            "${item.slot}: ${item.name}\n" + item.entries.joinToString("\n") { "  • ${it.name} (${it.size / 1024} KB)" }
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
            log("No Download Mode device found. Boot the target into Download Mode and connect it via OTG.")
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
        if (!busy.compareAndSet(false, true)) { log("Busy – wait for the current operation"); return }
        val reboot = chkReboot.isChecked
        exec.execute {
            var odin: OdinConnection? = null
            try {
                val iface = odinInterface(dev) ?: throw IOException("No Odin interface")
                val eps = bulkEndpoints(iface) ?: throw IOException("Odin bulk endpoints not found")
                var ctrl: UsbInterface? = null
                for (n in 0 until dev.interfaceCount) {
                    val i = dev.getInterface(n)
                    if (i.interfaceClass == UsbConstants.USB_CLASS_COMM) { ctrl = i; break }
                }
                log("Data interface #${iface.id}: IN 0x${Integer.toHexString(eps.first.address)}, " +
                    "OUT 0x${Integer.toHexString(eps.second.address)}, max packet ${eps.first.maxPacketSize}")

                val usb = usbManager.openDevice(dev) ?: throw IOException("openDevice failed")
                if (ctrl != null) usb.claimInterface(ctrl, true)
                if (!usb.claimInterface(iface, true)) { usb.close(); throw IOException("claimInterface failed") }
                usb.setInterface(iface)
                val o = OdinConnection(usb, iface, ctrl, eps.first, eps.second)
                odin = o
                o.log = { log(it) }

                setStatus("Download Mode: flashing…")
                log("Setting up serial lines…")
                o.setupSerial()
                log("Handshake…")
                o.handshake()
                log("LOKE received. Starting session…")
                val dflt = o.beginSession()
                log("Session open (0x${Integer.toHexString(dflt)}). Reading PIT…")
                val pit = OdinConnection.parsePit(o.downloadPit())
                log("PIT: ${pit.size} partitions")

                // Map every file to a partition BEFORE writing anything.
                val plan = ArrayList<PlanStep>()
                for (item in items) {
                    for (e in item.entries) {
                        val base = e.name.substringAfterLast('/')
                        val p = pit.firstOrNull { it.flashName.equals(base, ignoreCase = true) }
                            ?: pit.firstOrNull { it.name.equals(base.substringBeforeLast('.'), ignoreCase = true) }
                            ?: throw IOException("${e.name}: no matching partition in the device PIT – nothing was flashed")
                        val wantModem = item.slot == "CP"
                        if ((p.binaryType == 1) != wantModem) {
                            throw IOException(
                                "${e.name} maps to ${p.name}, which is a ${if (p.binaryType == 1) "modem (CP)" else "non-modem"} " +
                                    "partition, but it is in the ${item.slot} slot – nothing was flashed"
                            )
                        }
                        plan.add(PlanStep(item, e, p))
                        log("${item.slot}: ${e.name} → ${p.name} (id ${p.id}, dev ${p.deviceType})")
                    }
                }

                val total = plan.fold(0L) { a, st -> a + st.entry.size }
                o.totalBytes(total)

                var done = 0L
                for (item in items) {
                    val pfd = contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("Cannot open file")
                    pfd.use { pf ->
                        FileInputStream(pf.fileDescriptor).channel.use { ch ->
                            for (st in plan.filter { it.item === item }) {
                                log("Flashing ${st.entry.name}…")
                                val base = done
                                o.flash(ch, st.entry.dataOffset, st.entry.size, st.part, st.part.binaryType == 1) { sent ->
                                    showProgress(((base + sent) * 100 / total).toInt())
                                }
                                done += st.entry.size
                                log("✓ ${st.part.name}")
                            }
                        }
                    }
                }

                o.endSession()
                if (reboot) {
                    o.reboot()
                    log("✓ Flash complete – rebooting")
                } else {
                    log("✓ Flash complete – device is still in Download Mode")
                }
            } catch (e: Exception) {
                log("✗ Download Mode flash failed: ${e.message}")
                try { odin?.endSession(2000) } catch (_: Exception) {}
            } finally {
                odin?.close()
                busy.set(false)
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
