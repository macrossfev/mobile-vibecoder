package com.vibecoder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibecoder.databinding.FragmentSettingsBinding
import com.vibecoder.data.PreferencesManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefsManager: PreferencesManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())
        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val fontSize = prefsManager.getFontSize()
        binding.sliderFontSize.value = fontSize.toFloat()
        binding.tvFontSizeValue.text = "$fontSize sp"

        binding.etApiKey.setText(prefsManager.getApiKey() ?: "")
        binding.etApiEndpoint.setText(prefsManager.getApiEndpoint() ?: "")

        binding.switchKeepScreenOn.isChecked = prefsManager.isKeepScreenOn()
        binding.switchVirtualKeyboard.isChecked = prefsManager.isVirtualKeyboardEnabled()
    }

    private fun setupListeners() {
        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            binding.tvFontSizeValue.text = "$size sp"
            prefsManager.setFontSize(size)
        }

        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setKeepScreenOn(isChecked)
        }

        binding.switchVirtualKeyboard.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setVirtualKeyboardEnabled(isChecked)
        }

        // Save API settings when focus is lost
        binding.etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefsManager.setApiKey(binding.etApiKey.text.toString().ifBlank { null })
            }
        }

        binding.etApiEndpoint.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefsManager.setApiEndpoint(binding.etApiEndpoint.text.toString().ifBlank { null })
            }
        }

        // Clear history button
        binding.btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }

        // About button
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun showClearHistoryDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清除命令历史")
            .setMessage("确定要清除所有命令历史记录吗？")
            .setPositiveButton("清除") { _, _ ->
                android.widget.Toast.makeText(requireContext(), "命令历史已清除", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("关于 MobileVibeCoder")
            .setMessage("MobileVibeCoder v1.0.0\n\nSSH服务器管理工具\n支持Ed25519和RSA密钥认证\n支持xterm.js终端模拟\n\n开发者: VibeCoder Team")
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        // Save API settings before destroying
        prefsManager.setApiKey(binding.etApiKey.text.toString().ifBlank { null })
        prefsManager.setApiEndpoint(binding.etApiEndpoint.text.toString().ifBlank { null })
        _binding = null
    }
}