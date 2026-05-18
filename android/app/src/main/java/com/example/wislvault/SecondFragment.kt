package com.example.wislvault

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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

class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!

    private lateinit var serverUrl: String

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startUpload(it) }
    }

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
        fetchFiles()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
                showError("Erro ao listar arquivos: ${e.message}")
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
                showError("Erro no upload: ${e.message}")
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
                showError("Erro ao deletar: ${e.message}")
            }
        }
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    private fun showOptions(file: FileInfo) {
        val options = arrayOf("Baixar", "Ver Informações", "Deletar")
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

    private fun downloadFile(file: FileInfo) {
        val encoded = URLEncoder.encode(file.name, "UTF-8")
        val request = DownloadManager.Request(Uri.parse("$serverUrl/files/$encoded"))
            .setTitle(file.name)
            .setDescription("Baixando...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
        val dm = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
        Toast.makeText(requireContext(), "Download iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun showInfo(file: FileInfo) {
        val ext = file.name.substringAfterLast('.', "").uppercase().ifEmpty { "—" }
        val date = runCatching {
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            val formatter = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR"))
            formatter.format(parser.parse(file.modified)!!)
        }.getOrElse { file.modified }

        val msg = "Nome: ${file.name}\nTipo: $ext\nTamanho: ${formatSize(file.size)}\nData do upload: $date"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Informações")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDelete(file: FileInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Deletar arquivo")
            .setMessage("Tem certeza que deseja deletar \"${file.name}\"?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Deletar") { _, _ -> deleteFile(file) }
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
        binding.listContainer.removeAllViews()

        if (files.isEmpty()) {
            val tv = TextView(requireContext()).apply {
                text = "Nenhum arquivo no servidor."
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
            item.findViewById<TextView>(R.id.tvItemName).text = file.name
            item.findViewById<TextView>(R.id.tvItemSize).text = formatSize(file.size)
            item.findViewById<ImageButton>(R.id.btnOptions).setOnClickListener { showOptions(file) }
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
}
