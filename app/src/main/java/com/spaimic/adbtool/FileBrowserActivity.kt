package com.spaimic.adbtool

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.pow

/** Browses the target device's files over ADB and manages them (pull, push, create, delete, rename, chmod, SHA-256). */
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
    private lateinit var btnPush: Button
    private lateinit var btnNew: Button
    private lateinit var adapter: RemoteAdapter

    private val exec = Executors.newSingleThreadExecutor()
    private var path = "/sdcard"
    @Volatile private var cancel = false

    private val pushPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) confirmPush(uris)
    }

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
        btnPush = findViewById(R.id.btnPush)
        btnNew = findViewById(R.id.btnNew)

        adapter = RemoteAdapter({ openEntry(it) }, { pull(it) }, { e, anchor -> showMenu(e, anchor) })
        val list = findViewById<RecyclerView>(R.id.fileList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        btnUp.setOnClickListener { goUp() }
        btnRefresh.setOnClickListener { if (!AdbSession.busy.get()) load(path) }
        btnCancel.setOnClickListener {
            cancel = true
            btnCancel.isEnabled = false
        }
        btnPush.setOnClickListener { if (!AdbSession.busy.get()) pushPicker.launch(arrayOf("*/*")) }
        btnNew.setOnClickListener { v -> if (!AdbSession.busy.get()) showNewMenu(v) }
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

    /** Single-quotes a string for the Android shell. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun validName(n: String) = n.isNotEmpty() && n != "." && n != ".." && !n.contains('/') && !n.contains('\u0000')

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"

    private fun runShell(c: AdbConnection, cmd: String): String {
        val sb = StringBuilder()
        c.shell(cmd) { sb.append(it) }
        return sb.toString().trim()
    }

    private fun setBusy(busy: Boolean, pulling: Boolean = false) = ui {
        btnUp.isEnabled = !busy
        btnRefresh.isEnabled = !busy
        btnPush.isEnabled = !busy
        btnNew.isEnabled = !busy
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

    /**
     * Runs one operation on the target with the screen locked. [block] returns the message to show;
     * an exception becomes "❌ Error: <errorText>, REASON : …". The list is refreshed afterwards.
     */
    private fun task(
        errorText: String,
        busyText: String,
        pulling: Boolean = false,
        refresh: Boolean = true,
        block: (AdbConnection) -> String
    ) {
        val c = AdbSession.conn
        if (c == null || c.closed) { connectionLost(); return }
        if (!AdbSession.busy.compareAndSet(false, true)) return
        cancel = false
        setBusy(true, pulling)
        status(busyText)
        exec.execute {
            var msg: String
            try {
                msg = block(c)
            } catch (ex: Exception) {
                msg = if (ex is PullCancelled) "Cancelled" else "❌ Error: $errorText, REASON : ${AdbSession.reason(ex)}"
            }
            AdbSession.busy.set(false)
            ui {
                progress.progress = 0
                statusText.text = msg
                setBusy(false)
                toast(msg)
                if (!connected()) connectionLost() else if (refresh) load(path, msg)
            }
        }
    }

    // ------------------------------------------------------------ listing

    private fun load(p: String, keepStatus: String? = null) {
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
                    statusText.text = keepStatus ?: "${sorted.size} items"
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

    // ------------------------------------------------------------ pull

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
        val remote = join(path, e.name)
        task("Unable to pull ${e.name}", "Pulling ${e.name}…", pulling = true, refresh = false) { c ->
            val sess = c.sync()
            try {
                var asDir = e.isDir
                if (e.isLink) asDir = sess.list(remote).isNotEmpty()

                val plan = ArrayList<PullItem>()
                if (asDir) {
                    status("Scanning ${e.name}…")
                    scan(sess, remote, e.name, plan, 0)
                } else {
                    plan.add(PullItem(remote, "", e.name, e.size, e.isLink))
                }

                val result: String
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
                        !asDir -> throw IOException(failures.first())
                        else -> "⚠ Pulled $okCount of ${plan.size} files from ${e.name}. First error: ${failures.first()}"
                    }
                }
                sess.close()
                result
            } catch (ex: Exception) {
                sess.abort()
                throw ex
            }
        }
    }

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

    // ------------------------------------------------------------ push

    private fun confirmPush(uris: List<Uri>) {
        val names = uris.map { displayName(it) }
        val clashes = names.filter { adapter.has(it) }
        if (clashes.isEmpty()) {
            pushFiles(uris, names)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Overwrite?")
                .setMessage("Already in $path:\n" + clashes.joinToString("\n") { "• $it" })
                .setPositiveButton("Overwrite") { _, _ -> pushFiles(uris, names) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun pushFiles(uris: List<Uri>, names: List<String>) {
        val dir = path
        task("Unable to push to $dir", "Pushing…") { c ->
            val sizes = uris.map { u -> contentResolver.openFileDescriptor(u, "r")?.use { it.statSize } ?: 0L }
            val total = sizes.sum()
            var before = 0L
            var lastUi = 0L
            for ((i, uri) in uris.withIndex()) {
                if (cancel) throw PullCancelled()
                status("Pushing ${names[i]} (${i + 1}/${uris.size})")
                val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open ${names[i]}")
                pfd.use { p ->
                    FileInputStream(p.fileDescriptor).use { ins ->
                        c.push(ins, join(dir, names[i])) { done ->
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 150 && total > 0) {
                                lastUi = now
                                val pct = ((before + done) * 100 / total).toInt().coerceIn(0, 100)
                                ui { progress.progress = pct }
                            }
                        }
                    }
                }
                before += sizes[i]
            }
            "✅ Successfully Pushed " + if (uris.size == 1) names[0] else "${uris.size} files"
        }
    }

    // ------------------------------------------------------------ create

    private fun askName(title: String, initial: String, positive: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setSingleLine(true)
        input.setText(initial)
        input.setSelection(0, initial.substringBeforeLast('.', initial).length)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(positive) { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "New folder")
        m.menu.add(0, 2, 1, "New file")
        m.setOnMenuItemClickListener {
            create(it.itemId == 1)
            true
        }
        m.show()
    }

    private fun create(folder: Boolean) {
        askName(if (folder) "New folder" else "New file", "", "Create") { name ->
            if (!validName(name)) { toast("Invalid name"); return@askName }
            if (adapter.has(name)) { toast("$name already exists"); return@askName }
            val target = join(path, name)
            task("Unable to create $name", "Creating $name…") { c ->
                val out = runShell(c, (if (folder) "mkdir " else "touch ") + q(target))
                if (out.isNotEmpty()) throw IOException(out)
                "✅ Created $name"
            }
        }
    }

    // ------------------------------------------------------------ ⋮ menu

    private fun showMenu(e: DirEntry, anchor: View) {
        if (AdbSession.busy.get()) return
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Delete")
        m.menu.add(0, 2, 1, "Rename")
        m.menu.add(0, 3, 2, "Permissions")
        if (!e.isDir) m.menu.add(0, 4, 3, "SHA-256 checksum")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> confirmDelete(e)
                2 -> rename(e)
                3 -> showPermissions(e)
                4 -> checksum(e)
            }
            true
        }
        m.show()
    }

    private fun confirmDelete(e: DirEntry) {
        val what = if (e.isDir) "the folder \"${e.name}\" and everything inside it" else "\"${e.name}\""
        AlertDialog.Builder(this)
            .setTitle("Delete?")
            .setMessage("Delete $what from the target device? This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val target = join(path, e.name)
                task("Unable to delete ${e.name}", "Deleting ${e.name}…") { c ->
                    val out = runShell(c, (if (e.isDir) "rm -rf " else "rm -f ") + q(target))
                    if (out.isNotEmpty()) throw IOException(out)
                    "✅ Deleted ${e.name}"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rename(e: DirEntry) {
        askName("Rename", e.name, "Rename") { name ->
            if (name == e.name) return@askName
            if (!validName(name)) { toast("Invalid name"); return@askName }
            if (adapter.has(name)) { toast("$name already exists"); return@askName }
            val from = join(path, e.name)
            val to = join(path, name)
            task("Unable to rename ${e.name}", "Renaming ${e.name}…") { c ->
                val out = runShell(c, "mv ${q(from)} ${q(to)}")
                if (out.isNotEmpty()) throw IOException(out)
                "✅ Renamed ${e.name} → $name"
            }
        }
    }

    private fun checksum(e: DirEntry) {
        val target = join(path, e.name)
        task("Unable to read the checksum of ${e.name}", "Calculating SHA-256 of ${e.name}…", refresh = false) { c ->
            val out = runShell(c, "sha256sum ${q(target)}")
            val hash = out.lines().map { it.trim().take(64) }.firstOrNull { Regex("[0-9a-fA-F]{64}").matches(it) }
                ?: throw IOException(out.ifEmpty { "sha256sum is not available on the target" })
            ui { showHash(e.name, hash.lowercase()) }
            "✅ SHA-256 of ${e.name} calculated"
        }
    }

    private fun showHash(name: String, hash: String) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val t = TextView(this)
        t.text = hash
        t.typeface = Typeface.MONOSPACE
        t.textSize = 14f
        t.setTextIsSelectable(true)
        t.setPadding(pad, pad / 2, pad, 0)
        AlertDialog.Builder(this)
            .setTitle("SHA-256 – $name")
            .setView(t)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SHA-256", hash))
                toast("Copied")
            }
            .show()
    }

    private fun showPermissions(e: DirEntry) {
        val mode = e.mode and 0x1FF
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val cell = (8 * dp).toInt()

        val grid = GridLayout(this)
        grid.columnCount = 4
        for (t in arrayOf("", "Read", "Write", "Execute")) {
            val tv = TextView(this)
            tv.text = t
            tv.gravity = Gravity.CENTER
            tv.setPadding(cell, cell, cell, cell)
            grid.addView(tv)
        }
        val who = arrayOf("Owner", "Group", "Others")
        val boxes = ArrayList<CheckBox>()
        val octal = TextView(this)
        octal.typeface = Typeface.MONOSPACE
        octal.setPadding(0, cell, 0, cell)

        fun bitOf(index: Int) = 1 shl (8 - index)
        fun mask(): Int {
            var m = 0
            for ((i, b) in boxes.withIndex()) if (b.isChecked) m = m or bitOf(i)
            return m
        }
        fun refreshOctal() { octal.text = "Mode: " + String.format("%03o", mask()) }

        for (w in 0..2) {
            val label = TextView(this)
            label.text = who[w]
            label.setPadding(cell, cell, cell, cell)
            grid.addView(label)
            for (b in 0..2) {
                val cb = CheckBox(this)
                cb.isChecked = (mode and bitOf(w * 3 + b)) != 0
                cb.setOnCheckedChangeListener { _, _ -> refreshOctal() }
                boxes.add(cb)
                grid.addView(cb)
            }
        }
        refreshOctal()

        val recursive = CheckBox(this)
        recursive.text = "Apply to everything inside"

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(grid)
        box.addView(octal)
        if (e.isDir) box.addView(recursive)

        AlertDialog.Builder(this)
            .setTitle("Permissions – ${e.name}")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val oct = String.format("%03o", mask())
                val rec = e.isDir && recursive.isChecked
                val target = join(path, e.name)
                task("Unable to change permissions of ${e.name}", "Changing permissions…") { c ->
                    val out = runShell(c, "chmod ${if (rec) "-R " else ""}$oct ${q(target)}")
                    if (out.isNotEmpty()) throw IOException(out)
                    "✅ Permissions of ${e.name} set to $oct"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ---------------------------------------------------------------- list adapter

private class RemoteAdapter(
    private val onOpen: (DirEntry) -> Unit,
    private val onPull: (DirEntry) -> Unit,
    private val onMenu: (DirEntry, View) -> Unit
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

    fun has(name: String): Boolean = items.any { it.name == name }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.fileIcon)
        val name: TextView = v.findViewById(R.id.fileName)
        val meta: TextView = v.findViewById(R.id.fileMeta)
        val pull: Button = v.findViewById(R.id.btnPull)
        val more: ImageButton = v.findViewById(R.id.btnMore)
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
        holder.more.isEnabled = !busy
        holder.pull.setOnClickListener { onPull(e) }
        holder.more.setOnClickListener { onMenu(e, holder.more) }
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
