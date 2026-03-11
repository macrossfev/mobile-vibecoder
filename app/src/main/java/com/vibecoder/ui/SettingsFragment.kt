package com.vibecoder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibecoder.databinding.FragmentSettingsBinding
import com.vibecoder.data.PreferencesManager

/**
 * 设置Fragment
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefsManager: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedInstanceState: Bundle?
    ): View {
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
        // 字体大小
        val fontSize = prefsManager.getFontSize()
        binding.sliderFontSize.value = fontSize.toFloat()
        binding.tvFontSizeValue.text = "$fontSize sp"

        // 语音提供商
        val voiceProvider = prefsManager.getVoiceProvider()
        updateVoiceProviderDisplay(voiceProvider)

        // API设置
        binding.etApiKey.setText(prefsManager.getApiKey() ?: "")
        binding.etApiEndpoint.setText(prefsManager.getApiEndpoint() ?: "")

        // 屏幕常亮
        binding.switchKeepScreenOn.isChecked = prefsManager.isKeepScreenOn()
    }

    private fun setupListeners() {
        // 字体大小
        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            val size = value.toInt()
            binding.tvFontSizeValue.text = "$size sp"
            prefsManager.setFontSize(size)
        }

        // 语音提供商选择
        binding.btnSelectVoiceProvider.setOnClickListener {
            val providers = arrayOf("系统语音识别", "Whisper API", "百度语音", "讯飞语音")
            val values = arrayOf("system", "whisper", "baidu", "xunfei")

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择语音识别服务")
                .setSingleChoiceItems(providers, values.indexOf(prefsManager.getVoiceProvider())) { dialog, which ->
                    prefsManager.setVoiceProvider(values[which])
                    updateVoiceProviderDisplay(values[which])
                    dialog.dismiss()
                }
                .show()
        }

        // API Key
        binding.etApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefsManager.setApiKey(binding.etApiKey.text.toString().ifBlank { null })
            }
        }

        // API Endpoint
        binding.etApiEndpoint.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefsManager.setApiEndpoint(binding.etApiEndpoint.text.toString().ifBlank { null })
            }
        }

        // 屏幕常亮
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setKeepScreenOn(isChecked)
        }

        // 关于
        binding.btnAbout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("关于 VibeCoder")
                .setMessage("VibeCoder v1.0.0\n\n语音驱动的SSH终端\n让你的服务器管理更轻松")
                .setPositiveButton("确定", null)
                .show()
        }

        // 清除历史
        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除历史记录")
                .setMessage("确定要清除所有命令历史记录吗？")
                .setPositiveButton("清除") { _, _ ->
                    // 清除所有历史
                    requireContext().getSharedPreferences("vibecoder_prefs", 0)
                        .edit()
                        .apply {
                            prefsManager.getServers().forEach { server ->
                                remove("command_history_${server.id}")
                            }
                        }
                        .apply()
                }
                .setNegativeButton("取消", null)
                .show()
        }
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
        super.onDestroyView()
        _binding = null
    }
}