package com.wislvault

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.wislvault.R
import com.wislvault.databinding.FragmentSecondBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class VaultActivity : Fragment() {

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

        TransferManager.init(requireContext())

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

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_vault, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == R.id.action_transfers) {
                    findNavController().navigate(R.id.action_VaultActivity_to_TransfersActivity)
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

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
        val nm = requireContext().getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                android.app.NotificationManager.IMPORTANCE_LOW
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
        TransferManager.startUpload(uri, serverUrl, requireContext().applicationContext)
        findNavController().navigate(R.id.action_VaultActivity_to_TransfersActivity)
    }

    private fun deleteFile(file: FileInfo) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encoded = URLEncoder.encode(file.name, "UTF-8")
                    val conn =
                        URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
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
                        val conn =
                            URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
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
        TransferManager.startDownload(file, serverUrl, requireContext().applicationContext)
        findNavController().navigate(R.id.action_VaultActivity_to_TransfersActivity)
    }

    private fun downloadSelected() {
        val toDownload = selectedFiles.mapNotNull { name -> currentFiles.find { it.name == name } }
        exitSelectionMode()
        toDownload.forEach { TransferManager.startDownload(it, serverUrl, requireContext().applicationContext) }
        findNavController().navigate(R.id.action_VaultActivity_to_TransfersActivity)
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    private fun showOptions(file: FileInfo) {
        val options = arrayOf(getString(R.string.option_download), getString(R.string.option_info), getString(
            R.string.option_delete))
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
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.forLanguageTag("pt-BR"))
            formatter.format(parser.parse(file.modified)!!)
        }.getOrElse { file.modified }

        val msg = "${getString(R.string.info_name)}: ${file.name}\n${getString(R.string.info_type)}: $ext\n${getString(
            R.string.info_size)}: ${TransferManager.formatSize(file.size)}\n${getString(R.string.info_date)}: $date"
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
            item.findViewById<TextView>(R.id.tvItemSize).text = TransferManager.formatSize(file.size)

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

    data class FileInfo(val name: String, val size: Long, val modified: String)

    companion object {
        private const val NOTIF_CHANNEL_ID = "wisl_downloads"
        private val notifIdCounter = AtomicInteger(1000)
    }
}
