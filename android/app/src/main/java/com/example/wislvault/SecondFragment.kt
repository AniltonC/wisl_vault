package com.example.wislvault

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.wislvault.databinding.FragmentSecondBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private lateinit var serverUrl: String

    private var isSelectionMode = false
    private val selectedFiles = mutableSetOf<String>()
    private var currentFiles = listOf<FileInfo>()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startUpload(it) }
    }

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification will appear if granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverUrl = arguments?.getString("serverUrl") ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            val dp80 = (80 * resources.displayMetrics.density).toInt()
            binding.fab.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = navBar.bottom + dp16
                marginEnd = dp16
            }
            binding.scrollFiles.setPadding(0, 0, 0, dp80 + navBar.bottom)
            insets
        }

        binding.fab.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.tvError.setOnClickListener { binding.tvError.isVisible = false }

        binding.btnCancelSelection.setOnClickListener { exitSelectionMode() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnDownloadSelected.setOnClickListener { downloadSelected() }
        binding.btnSelectAll.setOnClickListener { selectAll() }

        ensureNotifChannel()
        ensureNotifPermission()
        fetchFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun ensureNotifChannel() {
        val nm = requireContext().getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun ensureNotifPermission() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun enterSelectionMode(file: FileInfo) {
        isSelectionMode = true
        selectedFiles.clear()
        selectedFiles.add(file.name)
        binding.selectionBar.isVisible = true
        binding.fab.isVisible = false
        updateItemIcons()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedFiles.clear()
        binding.selectionBar.isVisible = false
        binding.fab.isVisible = true
        updateItemIcons()
    }

    private fun toggleSelection(file: FileInfo) {
        if (!selectedFiles.remove(file.name)) {
            selectedFiles.add(file.name)
        }
        if (selectedFiles.isEmpty()) exitSelectionMode() else updateItemIcons()
    }

    private fun selectAll() {
        selectedFiles.clear()
        selectedFiles.addAll(currentFiles.map { it.name })
        updateItemIcons()
    }

    private fun updateItemIcons() {
        for (i in 0 until binding.listContainer.childCount) {
            val child = binding.listContainer.getChildAt(i) ?: continue
            val name = child.tag as? String ?: continue
            val btn = child.findViewById<ImageButton>(R.id.btnOptions) ?: continue
            val iconRes = when {
                !isSelectionMode -> R.drawable.ic_more_vert
                selectedFiles.contains(name) -> R.drawable.ic_check_circle
                else -> R.drawable.ic_radio_button_unchecked
            }
            btn.setImageResource(iconRes)
        }
    }

    // ── Network ──────────────────────────────────────────────────────────────

    private fun fetchFiles() {
        binding.tvError.isVisible = false
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val conn = URL("$serverUrl/files").openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                    parseFiles(conn.inputStream.bufferedReader().readText())
                }
                showFiles(files)
            } catch (e: Exception) {
                showError(getString(R.string.error_list_files, e.message))
            }
        }
    }

    private fun startUpload(uri: Uri) {
        val cr = requireContext().contentResolver
        val fileName = getFileName(uri) ?: "arquivo"
        val mimeType = cr.getType(uri) ?: "application/octet-stream"
        val fileSize = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L

        lifecycleScope.launch {
            binding.layoutProgress.isVisible = true
            binding.fab.isEnabled = false
            binding.tvFileName.text = fileName
            binding.tvError.isVisible = false
            binding.progressBar.progress = 0
            binding.tvPercent.text = "0%"
            binding.tvSpeed.text = ""
            binding.tvEta.text = ""

            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            try {
                withContext(Dispatchers.IO) {
                    val fileId = UUID.randomUUID().toString()
                    val boundary = "WiSLBoundary${fileId.replace("-", "")}"

                    val conn = URL("$serverUrl/upload").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(256 * 1024)
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 3_600_000

                    conn.outputStream.use { out ->
                        val tracked = object : OutputStream() {
                            var written = 0L

                            override fun write(b: Int) { out.write(b); tick(++written) }

                            override fun write(b: ByteArray, off: Int, len: Int) {
                                out.write(b, off, len)
                                written += len
                                tick(written)
                            }

                            override fun flush() = out.flush()
                            override fun close() = out.close()

                            fun tick(loaded: Long) {
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate < 150) return
                                lastUpdate = now
                                val elapsed = (now - startTime) / 1000.0
                                val pct = if (fileSize > 0) ((loaded.toDouble() / fileSize) * 100).toInt() else 0
                                val speed = if (elapsed > 0.5 && loaded > 0) loaded / elapsed else 0.0
                                val eta = if (speed > 0 && fileSize > 0) ((fileSize - loaded) / speed).toLong() else null
                                lifecycleScope.launch(Dispatchers.Main) {
                                    if (_binding == null) return@launch
                                    binding.progressBar.progress = pct
                                    binding.tvPercent.text = "$pct%"
                                    if (speed > 0) {
                                        binding.tvSpeed.text = formatSize(speed.toLong()) + "/s"
                                        binding.tvEta.text = eta?.let { formatTime(it) + " restantes" } ?: ""
                                    }
                                }
                            }
                        }
                        writeMultipart(tracked, boundary, uri, fileName, mimeType, fileId)
                    }

                    val code = conn.responseCode
                    if (code !in 200..299) throw Exception("HTTP $code")
                }
                fetchFiles()
            } catch (e: Exception) {
                showError(getString(R.string.error_upload, e.message))
            } finally {
                if (_binding != null) {
                    binding.layoutProgress.isVisible = false
                    binding.fab.isEnabled = true
                }
            }
        }
    }

    private fun deleteFile(file: FileInfo) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(file.name, "UTF-8")
                    val conn = URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
                    conn.requestMethod = "DELETE"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val code = conn.responseCode
                    if (code !in 200..299) throw Exception("HTTP $code")
                }
                fetchFiles()
            } catch (e: Exception) {
                showError(getString(R.string.error_delete, e.message))
            }
        }
    }

    private fun deleteSelected() {
        val toDelete = selectedFiles.toList()
        exitSelectionMode()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    toDelete.forEach { name ->
                        val encoded = URLEncoder.encode(name, "UTF-8")
                        val conn = URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
                        conn.requestMethod = "DELETE"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.responseCode
                    }
                }
                fetchFiles()
            } catch (e: Exception) {
                showError(getString(R.string.error_delete, e.message))
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadFile(file: FileInfo) {
        val notifId = notifIdCounter.getAndIncrement()
        val nm = requireContext().getSystemService(NotificationManager::class.java)
        val ctx = requireContext()
        val strRemaining = getString(R.string.time_remaining)
        val strComplete = getString(R.string.download_complete)
        val strError = getString(R.string.error_download)

        Toast.makeText(requireContext(), getString(R.string.download_initiated), Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val encoded = URLEncoder.encode(file.name, "UTF-8")
            val conn = URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 3_600_000

            var mediaUri: Uri? = null

            try {
                conn.connect()
                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

                val totalBytes = conn.contentLengthLong
                val mimeType = conn.contentType ?: "application/octet-stream"

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                mediaUri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Cannot create file in Downloads")

                val startTime = System.currentTimeMillis()
                var downloaded = 0L
                var lastUpdate = 0L

                val builder = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(file.name)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)

                conn.inputStream.use { input ->
                    ctx.contentResolver.openOutputStream(mediaUri!!)!!.use { output ->
                        val buf = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 200) {
                                lastUpdate = now
                                val elapsed = (now - startTime) / 1000.0
                                val speed = if (elapsed > 0.5 && downloaded > 0) downloaded / elapsed else 0.0
                                val eta = if (speed > 0 && totalBytes > 0) ((totalBytes - downloaded) / speed).toLong() else null
                                val pct = if (totalBytes > 0) ((downloaded.toDouble() / totalBytes) * 100).toInt() else 0

                                val text = buildString {
                                    append(formatSize(downloaded))
                                    if (totalBytes > 0) append(" / ${formatSize(totalBytes)}")
                                    if (speed > 0) append(" • ${formatSize(speed.toLong())}/s")
                                    if (eta != null) append(" • ${formatTime(eta)} $strRemaining")
                                }

                                builder.setContentText(text).setProgress(100, pct, totalBytes <= 0)
                                nm.notify(notifId, builder.build())
                            }
                        }
                    }
                }

                val doneValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                ctx.contentResolver.update(mediaUri!!, doneValues, null, null)

                nm.notify(notifId, NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(file.name)
                    .setContentText(strComplete)
                    .setAutoCancel(true)
                    .build())

            } catch (e: Exception) {
                mediaUri?.let { ctx.contentResolver.delete(it, null, null) }
                nm.cancel(notifId)
                withContext(Dispatchers.Main) {
                    if (_binding != null) showError(String.format(strError, e.message))
                }
            }
        }
    }

    private fun downloadSelected() {
        val toDownload = selectedFiles.mapNotNull { name -> currentFiles.find { it.name == name } }
        exitSelectionMode()
        toDownload.forEach { downloadFile(it) }
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    private fun showOptions(file: FileInfo) {
        val options = arrayOf(getString(R.string.option_download), getString(R.string.option_info), getString(R.string.option_delete))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> downloadFile(file)
                    1 -> showInfo(file)
                    2 -> confirmDelete(file)
                }
            }
            .show()
    }

    private fun showInfo(file: FileInfo) {
        val ext = file.name.substringAfterLast('.', "").uppercase().ifEmpty { "—" }
        val date = runCatching {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.forLanguageTag("pt-BR"))
            formatter.format(parser.parse(file.modified)!!)
        }.getOrElse { file.modified }

        val msg = "${getString(R.string.info_name)}: ${file.name}\n${getString(R.string.info_type)}: $ext\n${getString(R.string.info_size)}: ${formatSize(file.size)}\n${getString(R.string.info_date)}: $date"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_info_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(file: FileInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_confirm, file.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteFile(file) }
            .show()
    }

    private fun confirmDeleteSelected() {
        val count = selectedFiles.size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_delete_selected_title)
            .setMessage(getString(R.string.dialog_delete_selected_confirm, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.btn_delete) { _, _ -> deleteSelected() }
            .show()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun writeMultipart(
        out: OutputStream,
        boundary: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileId: String
    ) {
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write("$value\r\n".toByteArray())
        }

        field("file_id", fileId)
        field("chunk_index", "0")
        field("total_chunks", "1")

        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
        out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())

        requireContext().contentResolver.openInputStream(uri)?.use { it.copyTo(out, bufferSize = 256 * 1024) }

        out.write("\r\n--$boundary--\r\n".toByteArray())
        out.flush()
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    }

    private fun parseFiles(json: String): List<FileInfo> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            FileInfo(obj.getString("name"), obj.optLong("size", 0), obj.optString("modified", ""))
        }
    }

    private fun showFiles(files: List<FileInfo>) {
        currentFiles = files
        binding.listContainer.removeAllViews()

        if (files.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = getString(R.string.no_files)
                setTextColor(0xFF94A3B8.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            }
            binding.listContainer.addView(tv)
            return
        }

        files.forEach { file ->
            val item = layoutInflater.inflate(R.layout.item_file, binding.listContainer, false)
            item.tag = file.name
            item.findViewById<TextView>(R.id.tvItemName).text = file.name
            item.findViewById<TextView>(R.id.tvItemSize).text = formatSize(file.size)

            val btn = item.findViewById<ImageButton>(R.id.btnOptions)
            val iconRes = when {
                !isSelectionMode -> R.drawable.ic_more_vert
                selectedFiles.contains(file.name) -> R.drawable.ic_check_circle
                else -> R.drawable.ic_radio_button_unchecked
            }
            btn.setImageResource(iconRes)

            btn.setOnClickListener {
                if (isSelectionMode) toggleSelection(file) else showOptions(file)
            }
            item.setOnClickListener {
                if (isSelectionMode) toggleSelection(file)
            }
            item.setOnLongClickListener {
                if (!isSelectionMode) enterSelectionMode(file)
                true
            }

            binding.listContainer.addView(item)
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.isVisible = true
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(0, units.size - 1)
        return "%.1f %s".format(bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }

    private fun formatTime(seconds: Long): String {
        if (seconds < 0) return "--"
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3600) return "${seconds / 60}m ${seconds % 60}s"
        return "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    data class FileInfo(val name: String, val size: Long, val modified: String)

    companion object {
        private const val NOTIF_CHANNEL_ID = "wisl_downloads"
        private val notifIdCounter = AtomicInteger(1000)
    }
}
