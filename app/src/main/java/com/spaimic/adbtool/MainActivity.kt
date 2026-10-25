package com.spaimic.adbtool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
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

    private val pushPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doPush(uri)
    }
    private val sideloadPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) doSideload(uri)
    }
    private val pullPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) doPull(uri) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    val dev = deviceFrom(i)
                    if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(dev)
                    else log("USB permission denied")
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
    private fun setProgress(p: Int) = ui { progress.progress = p }

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
                setProgress(0)
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
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName), flags)
            usbManager.requestPermission(dev, pi)
        }
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
                    c.push(ins, remote) { done -> if (size > 0) setProgress((done * 100 / size).toInt()) }
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
                    val done = c.sideload(ch, size) { sent, total -> setProgress((sent * 100 / total).toInt()) }
                    log(if (done) "✓ Sideload finished" else "Stream closed by device – check its screen")
                }
            }
        }
    }
}
