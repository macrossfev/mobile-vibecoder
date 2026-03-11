package com.vibecoder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibecoder.R
import com.vibecoder.data.PreferencesManager
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentServerListBinding
import com.vibecoder.databinding.DialogAddServerBinding
import com.vibecoder.voice.AICommandInterpreter
import com.vibecoder.voice.VoiceInputManager
import com.vibecoder.voice.VoiceResult
import kotlinx.coroutines.launch

/**
 * 服务器列表Fragment
 */
class ServerListFragment : Fragment() {

    private var _binding: FragmentServerListBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefsManager: PreferencesManager
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var voiceManager: VoiceInputManager

    private val RECORD_AUDIO_PERMISSION = 1001

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServerListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefsManager = PreferencesManager(requireContext())
        voiceManager = VoiceInputManager(requireContext())

        setupRecyclerView()
        setupFab()
        loadServers()
    }

    private fun setupRecyclerView() {
        serverAdapter = ServerAdapter(
            onServerClick = { server ->
                openTerminal(server)
            },
            onServerLongClick = { server ->
                showServerOptions(server)
            },
            onMonitorClick = { server ->
                openMonitor(server)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = serverAdapter
        }
    }

    private fun setupFab() {
        binding.fabAddServer.setOnClickListener {
            showAddServerDialog()
        }

        // 语音快捷按钮
        binding.fabVoice.setOnClickListener {
            if (checkAudioPermission()) {
                startVoiceInput()
            } else {
                requestAudioPermission()
            }
        }
    }

    private fun loadServers() {
        val servers = prefsManager.getServers()
        serverAdapter.submitList(servers)

        binding.emptyView.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddServerDialog() {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加服务器")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val server = ServerConfig(
                    name = dialogBinding.etName.text.toString(),
                    host = dialogBinding.etHost.text.toString(),
                    port = dialogBinding.etPort.text.toString().toIntOrNull() ?: 22,
                    username = dialogBinding.etUsername.text.toString(),
                    password = dialogBinding.etPassword.text.toString()
                )
                prefsManager.addServer(server)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showServerOptions(server: ServerConfig) {
        val options = arrayOf("编辑", "删除", "复制连接信息")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(server.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> editServer(server)
                    1 -> deleteServer(server)
                    2 -> copyServerInfo(server)
                }
            }
            .show()
    }

    private fun editServer(server: ServerConfig) {
        val dialogBinding = DialogAddServerBinding.inflate(layoutInflater).apply {
            etName.setText(server.name)
            etHost.setText(server.host)
            etPort.setText(server.port.toString())
            etUsername.setText(server.username)
            etPassword.setText(server.password)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑服务器")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val updated = server.copy(
                    name = dialogBinding.etName.text.toString(),
                    host = dialogBinding.etHost.text.toString(),
                    port = dialogBinding.etPort.text.toString().toIntOrNull() ?: 22,
                    username = dialogBinding.etUsername.text.toString(),
                    password = dialogBinding.etPassword.text.toString()
                )
                prefsManager.updateServer(updated)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteServer(server: ServerConfig) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除服务器 \"${server.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                prefsManager.deleteServer(server.id)
                loadServers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyServerInfo(server: ServerConfig) {
        // 复制到剪贴板
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(
            "Server Info",
            "${server.username}@${server.host}:${server.port}"
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun openTerminal(server: ServerConfig) {
        val action = ServerListFragmentDirections.actionServerListToTerminal(server)
        findNavController().navigate(action)
    }

    private fun openMonitor(server: ServerConfig) {
        val action = ServerListFragmentDirections.actionServerListToMonitor(server)
        findNavController().navigate(action)
    }

    // ===== 语音输入 =====

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION
        )
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(requireContext(), "语音识别不可用", Toast.LENGTH_SHORT).show()
            return
        }

        binding.voiceStatus.visibility = View.VISIBLE
        binding.voiceStatus.text = "正在监听..."

        lifecycleScope.launch {
            voiceManager.startListening().collect { result ->
                when (result) {
                    is VoiceResult.Partial -> {
                        binding.voiceStatus.text = "识别中: ${result.text}"
                    }
                    is VoiceResult.Final -> {
                        binding.voiceStatus.visibility = View.GONE
                        handleVoiceCommand(result.text)
                    }
                    is VoiceResult.Error -> {
                        binding.voiceStatus.visibility = View.GONE
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                    is VoiceResult.Ready -> {
                        binding.voiceStatus.text = "请说话..."
                    }
                }
            }
        }
    }

    private fun handleVoiceCommand(text: String) {
        val servers = prefsManager.getServers()
        if (servers.isEmpty()) {
            Toast.makeText(requireContext(), "请先添加服务器", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示识别到的文本
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音命令")
            .setMessage("识别结果: \"$text\"\n\n选择要执行的服务器:")
            .setItems(servers.map { it.name }.toTypedArray()) { _, which ->
                val server = servers[which]
                interpretAndExecute(text, server)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun interpretAndExecute(voiceText: String, server: ServerConfig) {
        val apiKey = prefsManager.getApiKey()
        val apiEndpoint = prefsManager.getApiEndpoint() ?: AICommandInterpreter.DEFAULT_ENDPOINT

        if (apiKey.isNullOrBlank()) {
            Toast.makeText(requireContext(), "请先配置API Key", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val interpreter = AICommandInterpreter(apiEndpoint, apiKey)

            binding.voiceStatus.visibility = View.VISIBLE
            binding.voiceStatus.text = "正在理解命令..."

            val result = interpreter.interpret(voiceText)

            binding.voiceStatus.visibility = View.GONE

            result.fold(
                onSuccess = { commandResult ->
                    showCommandPreview(server, commandResult)
                },
                onFailure = { error ->
                    Toast.makeText(requireContext(), "理解失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun showCommandPreview(server: ServerConfig, commandResult: AICommandInterpreter.CommandResult) {
        val message = buildString {
            append("命令: ${commandResult.command}\n\n")
            append("说明: ${commandResult.explanation}\n\n")
            if (commandResult.dangerous) {
                append("⚠️ 警告: 此命令可能有风险！")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("确认执行")
            .setMessage(message)
            .setPositiveButton("执行") { _, _ ->
                openTerminalWithCommand(server, commandResult.command)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openTerminalWithCommand(server: ServerConfig, command: String) {
        // TODO: 打开终端并预填命令
        openTerminal(server)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        voiceManager.cancel()
    }
}