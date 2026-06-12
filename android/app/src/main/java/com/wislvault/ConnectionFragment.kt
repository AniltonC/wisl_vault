package com.wislvault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.wislvault.R
import com.wislvault.databinding.FragmentConnectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ConnectionFragment : Fragment() {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnConnect.setOnClickListener { connect() }
        binding.editServerUrl.setOnEditorActionListener { _, _, _ -> connect(); true }
        autoConnect()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun autoConnect() {
        binding.sectionLoading.isVisible = true
        binding.cardConnect.isVisible = false
        binding.tvError.isVisible = false

        lifecycleScope.launch {
            val success = tryConnect(DEFAULT_URL)
            if (_binding == null) return@launch
            if (success) {
                findNavController().navigate(
                    R.id.action_ConnectionFragment_to_VaultFragment,
                    bundleOf("serverUrl" to DEFAULT_URL)
                )
            } else {
                binding.sectionLoading.isVisible = false
                binding.cardConnect.isVisible = true
                binding.tvError.text = getString(R.string.error_wislvault_unavailable)
                binding.tvError.isVisible = true
            }
        }
    }

    private fun connect() {
        val url = binding.editServerUrl.text?.toString()?.trim()?.trimEnd('/') ?: return
        if (url.isEmpty()) return

        binding.tvError.isVisible = false
        binding.btnConnect.isEnabled = false
        binding.progressConnect.isVisible = true

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val conn = URL("$url/files").openConnection() as HttpURLConnection
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 8_000
                    val code = conn.responseCode
                    if (code != 200) throw Exception("HTTP $code")
                }
                findNavController().navigate(
                    R.id.action_ConnectionFragment_to_VaultFragment,
                    bundleOf("serverUrl" to url)
                )
            } catch (e: Exception) {
                binding.tvError.text = getString(R.string.error_connect, e.message)
                binding.tvError.isVisible = true
            } finally {
                if (_binding != null) {
                    binding.btnConnect.isEnabled = true
                    binding.progressConnect.isVisible = false
                }
            }
        }
    }

    private suspend fun tryConnect(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = URL("$url/files").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val DEFAULT_URL = "http://wislvault.local/api"
    }
}
