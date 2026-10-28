package com.spaimic.adbtool

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.pow

/** Browses the target device's files over ADB; "Pull" copies a file or folder to this device's Download folder. */
class FileBrowserActivity : ComponentActivity() {

    private class PullItem(val remote: String, val rel: String, val name: String, val size: Long, val link: Boolean)
    private class PullCancelled : IOException("Cancelled")

    private lateinit var pathText: TextView
    private lateinit var statusText: TextView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnUp: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnCancel: Button
    private lateinit var adapter: RemoteAdapter

    private val exec = Executors.newSingleThreadExecutor()
    private var path = "/sdcard"
    @Volatile private var cancel = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        pathText = findViewById(R.id.pathText)
        statusText = findViewById(R.id.statusText)
        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        btnUp = findViewById(R.id.btnUp)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCancel = findViewById(R.id.btnCancel)

        adapter = RemoteAdapter({ openEntry(it) }, { pull(it) })
        val list = findViewById<RecyclerView>(R.id.fileList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnUp.setOnClickListener { goUp() }
        btnRefresh.setOnClickListener { if (!AdbSession.busy.get()) load(path) }
        btnCancel.setOnClickListener {
            cancel = true
            btnCancel.isEnabled = false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    AdbSession.busy.get() -> toast("Wait for the current operation to finish")
                    path != "/" -> goUp()
                    else -> finish()
                }
            }
        })

        if (!connected()) {
            toast("Not connected to the target device")
            finish()
            return
        }
        load(path)
    }

    override fun onResume() {
        super.onResume()
        if (!connected() && !AdbSession.busy.get()) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        exec.shutdown()
    }

    // ------------------------------------------------------------ helpers

    private fun connected(): Boolean {
        val c = AdbSession.conn
        return c != null && !c.closed
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun status(s: String) = ui { statusText.text = s }

    private fun join(dir: String, name: String) = if (dir.endsWith("/")) dir + name else "$dir/$name"

    private fun setBusy(busy: Boolean, pulling: Boolean = false) = ui {
        btnUp.isEnabled = !busy
        btnRefresh.isEnabled = !busy
        adapter.setBusy(busy)
        btnCancel.visibility = if (pulling) View.VISIBLE else View.GONE
        btnCancel.isEnabled = true
    }

    private fun goUp() {
        if (AdbSession.busy.get() || path == "/") return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        load(if (parent.isEmpty()) "/" else parent)
    }

    private fun openEntry(e: DirEntry) {
        if (AdbSession.busy.get()) return
        if (e.isDir || e.isLink) load(join(path, e.name))
    }

    private fun connectionLost() {
        val c = AdbSession.conn
        if (c != null && c.closed) AdbSession.conn = null
        if (AdbSession.conn == null) {
            toast("Device Unplugged!!")
            finish()
        }
    }

    // ------------------------------------------------------------ listing

    private fun load(p: String) {
        val c = AdbSession.conn
        if (c == null || c.closed) { connectionLost(); return }
        if (!AdbSession.busy.compareAndSet(false, true)) return
        setBusy(true)
        status("Loading…")
        exec.execute {
            try {
                val s = c.sync()
                val items = try { s.list(p) } finally { s.close() }
                val sorted = items.sortedWith(
                    compareByDescending<DirEntry> { it.isDir || it.isLink }.thenBy { it.name.lowercase() }
                )
                ui {
                    path = p
                    pathText.text = p
                    adapter.submit(sorted)
                    emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                    statusText.text = "${sorted.size} items"
                }
            } catch (e: Exception) {
                status("❌ Error: Unable to open $p, REASON : ${AdbSession.reason(e)}")
            } finally {
                AdbSession.busy.set(false)
                setBusy(false)
                ui { if (!connected()) connectionLost() }
            }
        }
    }

    // ------------------------------------------------------------ pulling

    private fun scan(s: SyncSession, remote: String, rel: String, out: MutableList<PullItem>, depth: Int) {
        if (depth > 40) return
        for (x in s.list(remote)) {
            if (cancel) throw PullCancelled()
            val child = join(remote, x.name)
            if (x.isDir) scan(s, child, "$rel/${x.name}", out, depth + 1)
            else if (x.isFile || x.isLink) out.add(PullItem(child, rel, x.name, x.size, x.isLink))
        }
    }

    private fun isTransportError(c: AdbConnection, ex: IOException): Boolean {
        val m = ex.message ?: ""
        return c.closed || m.contains("Stream closed") || m.contains("Disconnected") ||
            m.contains("USB") || m.contains("Unexpected end")
    }

    private fun pull(e: DirEntry) {
        if (Build.VERSION.SDK_INT < 29) { toast("Pulling needs Android 10 or newer on this device"); return }
        val c = AdbSession.conn
        if (c == null || c.closed) { connectionLost(); return }
        if (!AdbSession.busy.compareAndSet(false, true)) return
        cancel = false
        val remote = join(path, e.name)
        setBusy(true, pulling = true)
        status("Pulling ${e.name}…")

        exec.execute {
            var s: SyncSession? = null
            var result: String
            try {
                val sess = c.sync()
                s = sess
                var asDir = e.isDir
                if (e.isLink) asDir = sess.list(remote).isNotEmpty()

                val plan = ArrayList<PullItem>()
                if (asDir) {
                    status("Scanning ${e.name}…")
                    scan(sess, remote, e.name, plan, 0)
                } else {
                    plan.add(PullItem(remote, "", e.name, e.size, e.isLink))
                }

                if (plan.isEmpty()) {
                    result = "Folder is empty – nothing to pull"
                } else {
                    val total = plan.fold(0L) { a, i -> a + i.size }
                    var done = 0L
                    var okCount = 0
                    var lastUi = 0L
                    val failures = ArrayList<String>()

                    for ((index, item) in plan.withIndex()) {
                        if (cancel) throw PullCancelled()
                        status("Pulling ${item.name} (${index + 1}/${plan.size})")
                        val target = createDownload(item.rel, item.name)
                        var ok = false
                        try {
                            BufferedOutputStream(target.second, 256 * 1024).use { out ->
                                sess.recv(item.remote, out) { got ->
                                    if (cancel) throw PullCancelled()
                                    val now = System.currentTimeMillis()
                                    if (now - lastUi > 150 && total > 0) {
                                        lastUi = now
                                        val pct = ((done + got) * 100 / total).toInt().coerceIn(0, 100)
                                        ui { progress.progress = pct }
                                    }
                                }
                            }
                            ok = true
                        } catch (ex: IOException) {
                            if (ex is PullCancelled || isTransportError(c, ex)) throw ex
                            if (!item.link) failures.add(if (asDir) "${item.name}: ${ex.message}" else (ex.message ?: "failed"))
                        } finally {
                            if (ok) finishDownload(target.first) else deleteDownload(target.first)
                        }
                        if (ok) okCount++
                        done += item.size
                    }

                    result = when {
                        failures.isEmpty() ->
                            "✅ Successfully Pulled ${e.name}" + if (asDir) " ($okCount files)" else ""
                        !asDir -> "❌ Error: Unable to pull ${e.name}, REASON : ${failures.first()}"
                        else -> "⚠ Pulled $okCount of ${plan.size} files from ${e.name}. First error: ${failures.first()}"
                    }
                }
                sess.close()
            } catch (ex: Exception) {
                result = if (ex is PullCancelled) "Cancelled"
                else "❌ Error: Unable to pull ${e.name}, REASON : ${AdbSession.reason(ex)}"
                s?.abort()
            }
            AdbSession.busy.set(false)
            ui {
                progress.progress = 0
                statusText.text = result
                setBusy(false)
                toast(result)
                if (!connected()) connectionLost()
            }
        }
    }

    // ------------------------------------------------------------ writing to Download

    private fun createDownload(rel: String, name: String): Pair<Uri, OutputStream> {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, if (rel.isEmpty()) "Download/" else "Download/$rel/")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create the file in Download")
        val out = contentResolver.openOutputStream(uri)
        if (out == null) {
            contentResolver.delete(uri, null, null)
            throw IOException("Cannot write to Download")
        }
        return Pair(uri, out)
    }

    private fun finishDownload(uri: Uri) {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }

    private fun deleteDownload(uri: Uri) {
        try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }
}

// ---------------------------------------------------------------- list adapter

private class RemoteAdapter(
    private val onOpen: (DirEntry) -> Unit,
    private val onPull: (DirEntry) -> Unit
) : RecyclerView.Adapter<RemoteAdapter.VH>() {

    private var items: List<DirEntry> = emptyList()
    private var busy = false

    fun submit(list: List<DirEntry>) {
        items = list
        notifyDataSetChanged()
    }

    fun setBusy(b: Boolean) {
        busy = b
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.fileIcon)
        val name: TextView = v.findViewById(R.id.fileName)
        val meta: TextView = v.findViewById(R.id.fileMeta)
        val pull: Button = v.findViewById(R.id.btnPull)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_remote_file, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.name.text = e.name
        holder.meta.text = when {
            e.isDir -> "Folder"
            e.isLink -> "Link"
            else -> "${readableSize(e.size)} • ${readableDate(e.mtime * 1000)}"
        }
        holder.icon.setImageResource(iconFor(e))
        holder.pull.isEnabled = !busy
        holder.pull.setOnClickListener { onPull(e) }
        holder.itemView.setOnClickListener { onOpen(e) }
    }

    private fun iconFor(e: DirEntry): Int {
        if (e.isDir || e.isLink) return R.drawable.ic_folder
        return when (e.name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> R.drawable.ic_image
            "mp4", "mkv", "webm", "3gp", "mov", "avi" -> R.drawable.ic_video
            "mp3", "wav", "ogg", "flac", "m4a" -> R.drawable.ic_audio
            "zip", "rar", "7z", "tar", "gz", "lz4" -> R.drawable.ic_archive
            "apk" -> R.drawable.ic_apk
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> R.drawable.ic_document
            "txt", "md", "json", "xml", "log", "kt", "java", "py", "js" -> R.drawable.ic_text
            else -> R.drawable.ic_file
        }
    }

    private fun readableSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val g = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format("%.1f %s", bytes / 1024.0.pow(g.toDouble()), units[g])
    }

    private fun readableDate(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
