package com.vibecoder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vibecoder.R
import com.vibecoder.data.CommandHistory
import com.vibecoder.data.PreferencesManager
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentTerminalBinding
import com.vibecoder.ssh.SSHManager
import com.vibecoder.voice.AICommandInterpreter
import com.vibecoder.voice.VoiceInputManager
import com.vibecoder.voice.VoiceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 终端Fragment
 */
class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private val args: TerminalFragmentArgs by navArgs()
    private lateinit var server: ServerConfig

    private lateinit var sshManager: SSHManager
    private lateinit var prefsManager: PreferencesManager
    private lateinit var voiceManager: VoiceInputManager

    private var shellJob: Job? = null
    private var commandHistoryIndex = -1
    private val commandHistory = mutableListOf<String>()
    private val outputBuffer = StringBuilder()

    private val RECORD_AUDIO_PERMISSION = 1002

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        server = args.server
        sshManager = SSHManager()
        prefsManager = PreferencesManager(requireContext())
        voiceManager = VoiceInputManager(requireContext())

        setupTerminal()
        setupInput()
        setupVoice()
        connect()
    }

    private fun setupTerminal() {
        // 设置终端文本大小
        binding.terminalView.textSize = prefsManager.getFontSize().toFloat()

        // 保持屏幕常亮
        if (prefsManager.isKeepScreenOn()) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupInput() {
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                sendCommand()
                true
            } else {
                false
            }
        }

        binding.btnSend.setOnClickListener {
            sendCommand()
        }

        // 历史命令导航
        binding.etCommand.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateHistory(-1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateHistory(1)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // 加载历史命令
        loadCommandHistory()
    }

    private fun setupVoice() {
        binding.btnVoice.setOnClickListener {
            if (checkAudioPermission()) {
                startVoiceInput()
            } else {
                requestAudioPermission()
            }
        }

        // 快捷键按钮
        binding.btnCtrlC.setOnClickListener {
            sshManager.sendCtrlC()
            appendOutput("^C\n")
        }

        binding.btnCtrlD.setOnClickListener {
            sshManager.sendCtrlD()
            appendOutput("^D\n")
        }

        binding.btnClear.setOnClickListener {
            outputBuffer.clear()
            binding.terminalView.text = ""
        }
    }

    private fun connect() {
        updateStatus("正在连接 ${server.name}...")

        lifecycleScope.launch {
            val result = sshManager.connect(server)

            result.fold(
                onSuccess = {
                    updateStatus("已连接")
                    startShell()
                },
                onFailure = { error ->
                    updateStatus("连接失败: ${error.message}")
                    showError("连接失败", error.message ?: "未知错误")
                }
            )
        }
    }

    private fun startShell() {
        shellJob = lifecycleScope.launch {
            sshManager.openShell(
                onOutput = { output ->
                    appendOutput(output)
                },
                onConnected = {
                    requireActivity().runOnUiThread {
                        updateStatus("Shell就绪")
                    }
                }
            )
        }
    }

    private fun sendCommand() {
        val command = binding.etCommand.text.toString().trim()
        if (command.isEmpty()) return

        if (!sshManager.isConnected()) {
            Toast.makeText(requireContext(), "未连接到服务器", Toast.LENGTH_SHORT).show()
            return
        }

        // 发送命令
        sshManager.writeToShell(command)
        appendOutput("$command\n")

        // 保存到历史
        commandHistory.add(0, command)
        commandHistoryIndex = -1
        prefsManager.addCommandToHistory(
            CommandHistory(command = command, serverId = server.id)
        )

        // 清空输入
        binding.etCommand.text?.clear()
    }

    private fun appendOutput(text: String) {
        requireActivity().runOnUiThread {
            outputBuffer.append(text)
            // 限制缓冲区大小
            if (outputBuffer.length > MAX_BUFFER_SIZE) {
                outputBuffer.delete(0, outputBuffer.length - MAX_BUFFER_SIZE)
            }
            binding.terminalView.text = outputBuffer.toString()

            // 滚动到底部
            binding.scrollView.post {
                binding.scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateStatus(status: String) {
        requireActivity().runOnUiThread {
            binding.tvStatus.text = status
        }
    }

    private fun navigateHistory(direction: Int) {
        if (commandHistory.isEmpty()) return

        commandHistoryIndex = (commandHistoryIndex + direction).coerceIn(-1, commandHistory.size - 1)

        if (commandHistoryIndex == -1) {
            binding.etCommand.text?.clear()
        } else {
            binding.etCommand.setText(commandHistory[commandHistoryIndex])
            binding.etCommand.setSelection(binding.etCommand.text?.length ?: 0)
        }
    }

    private fun loadCommandHistory() {
        val history = prefsManager.getCommandHistory(server.id)
        commandHistory.clear()
        commandHistory.addAll(history.map { it.command }.reversed())
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
        if (!voiceManager.isAvailable()) {
            Toast.makeText(requireContext(), "语音识别不可用", Toast.LENGTH_SHORT).show()
            return
        }

        binding.voiceIndicator.isVisible = true

        lifecycleScope.launch {
            voiceManager.startListening().collect { result ->
                when (result) {
                    is VoiceResult.Partial -> {
                        binding.voiceIndicator.text = "识别中: ${result.text}"
                    }
                    is VoiceResult.Final -> {
                        binding.voiceIndicator.isVisible = false
                        handleVoiceInput(result.text)
                    }
                    is VoiceResult.Error -> {
                        binding.voiceIndicator.isVisible = false
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                    is VoiceResult.Ready -> {
                        binding.voiceIndicator.text = "请说话..."
                    }
                }
            }
        }
    }

    private fun handleVoiceInput(text: String) {
        // 直接作为命令输入，或使用AI理解
        val options = arrayOf("直接执行", "AI理解后执行", "取消")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音输入: \"$text\"")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        binding.etCommand.setText(text)
                        sendCommand()
                    }
                    1 -> interpretVoiceCommand(text)
                }
            }
            .show()
    }

    private fun interpretVoiceCommand(text: String) {
        val apiKey = prefsManager.getApiKey()
        val apiEndpoint = prefsManager.getApiEndpoint() ?: AICommandInterpreter.DEFAULT_ENDPOINT

        if (apiKey.isNullOrBlank()) {
            Toast.makeText(requireContext(), "请先配置API Key", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val interpreter = AICommandInterpreter(apiEndpoint, apiKey)
            binding.voiceIndicator.isVisible = true
            binding.voiceIndicator.text = "正在理解..."

            val result = interpreter.interpret(text)

            binding.voiceIndicator.isVisible = false

            result.fold(
                onSuccess = { commandResult ->
                    if (commandResult.command.isBlank()) {
                        Toast.makeText(requireContext(), commandResult.explanation, Toast.LENGTH_SHORT).show()
                    } else {
                        showCommandPreview(commandResult)
                    }
                },
                onFailure = { error ->
                    Toast.makeText(requireContext(), "理解失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun showCommandPreview(commandResult: AICommandInterpreter.CommandResult) {
        val message = buildString {
            append("命令: ${commandResult.command}\n\n")
            append("说明: ${commandResult.explanation}")
            if (commandResult.dangerous) {
                append("\n\n⚠️ 此命令可能有风险!")
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI理解结果")
            .setMessage(message)
            .setPositiveButton("执行") { _, _ ->
                binding.etCommand.setText(commandResult.command)
                sendCommand()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showError(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("重试") { _, _ -> connect() }
            .setNegativeButton("返回") { _, _ ->
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            .setCancelable(false)
            .show()
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
        shellJob?.cancel()
        sshManager.disconnect()
        voiceManager.cancel()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        _binding = null
    }

    companion object {
        private const val MAX_BUFFER_SIZE = 100000
    }
}