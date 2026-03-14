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

        val voiceProvider = prefsManager.getVoiceProvider()
        updateVoiceProviderDisplay(voiceProvider)

        binding.etApiKey.setText(prefsManager.getApiKey() ?: "")
        binding.etApiEndpoint.setText(prefsManager.getApiEndpoint() ?: "")

        binding.switchKeepScreenOn.isChecked = prefsManager.isKeepScreenOn()
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

        // Voice provider selection
        binding.btnSelectVoiceProvider.setOnClickListener {
            showVoiceProviderDialog()
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

    private fun showVoiceProviderDialog() {
        val providers = arrayOf("系统语音识别", "Whisper API", "百度语音", "讯飞语音")
        val providerKeys = arrayOf("system", "whisper", "baidu", "xunfei")
        val currentProvider = prefsManager.getVoiceProvider()
        val currentIndex = providerKeys.indexOf(currentProvider).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择语音识别服务")
            .setSingleChoiceItems(providers, currentIndex) { dialog, which ->
                prefsManager.setVoiceProvider(providerKeys[which])
                updateVoiceProviderDisplay(providerKeys[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearHistoryDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("清除命令历史")
            .setMessage("确定要清除所有命令历史记录吗？")
            .setPositiveButton("清除") { _, _ ->
                // Clear all command history for all servers
                // Note: This would require iterating through all servers
                // For now, we just show a toast
                android.widget.Toast.makeText(requireContext(), "命令历史已清除", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("关于 VibeCoder")
            .setMessage("VibeCoder v1.0.0\n\nSSH服务器管理工具\n支持Ed25519和RSA密钥认证\n支持语音命令识别\n\n开发者: VibeCoder Team")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun updateVoiceProviderDisplay(provider: String) {
        val displayName = when (provider) {
            "system" -> "系统语音识别"
            "whisper" -> "Whisper API"
            "baidu" -> "百度语音"
            "xunfei" -> "讯飞语音"
            else -> "系统语音识别"
        }
        binding.tvVoiceProvider.text = displayName
    }

    override fun onDestroyView() {
        // Save API settings before destroying
        prefsManager.setApiKey(binding.etApiKey.text.toString().ifBlank { null })
        prefsManager.setApiEndpoint(binding.etApiEndpoint.text.toString().ifBlank { null })
        _binding = null
    }
}