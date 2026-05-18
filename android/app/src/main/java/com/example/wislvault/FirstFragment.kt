package com.example.wislvault

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.wislvault.databinding.FragmentFirstBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnConnect.setOnClickListener { connect() }
        binding.editServerUrl.setOnEditorActionListener { _, _, _ -> connect(); true }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
                    R.id.action_FirstFragment_to_SecondFragment,
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
}
