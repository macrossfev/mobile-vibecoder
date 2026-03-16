package com.vibecoder.ui

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.vibecoder.R
import com.vibecoder.data.PreferencesManager
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentVoiceTerminalBinding
import com.vibecoder.ssh.SSHManager
import com.vibecoder.voice.VoiceInputManager
import com.vibecoder.voice.VoiceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.*

class VoiceTerminalFragment : Fragment() {

    companion object {
        private const val ARG_SERVER_JSON = "server_json"

        fun newInstance(server: ServerConfig) = VoiceTerminalFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERVER_JSON, Gson().toJson(server))
            }
        }
    }

    private var _binding: FragmentVoiceTerminalBinding? = null
    private val binding get() = _binding!!

    private var server: ServerConfig? = null
    private lateinit var sshManager: SSHManager
    private lateinit var prefsManager: PreferencesManager
    private lateinit var voiceInputManager: VoiceInputManager
    private var textToSpeech: TextToSpeech? = null

    private var shellJob: Job? = null
    private val outputBuffer = StringBuilder()
    private var voiceJob: Job? = null

    private var voiceOutputEnabled = true
    private var isClaudeWorking = false
    private val workingIndicatorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastOutputTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverJson = arguments?.getString(ARG_SERVER_JSON)
        if (!serverJson.isNullOrBlank()) {
            server = Gson().fromJson(serverJson, ServerConfig::class.java)
        }
        retainInstance = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoiceTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentServer = server
        if (currentServer == null) {
            showError("错误", "服务器配置无效")
            return
        }

        if (!::sshManager.isInitialized) {
            sshManager = SSHManager.getInstance()
        }

        if (!::prefsManager.isInitialized) {
            prefsManager = PreferencesManager(requireContext())
        }

        voiceOutputEnabled = prefsManager.isVoiceOutputEnabled()

        setupVoiceInput()
        setupTextToSpeech()
        setupUI()
        connect(currentServer)
    }

    private fun setupVoiceInput() {
        voiceInputManager = VoiceInputManager(requireContext())
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.CHINESE
            }
        }
    }

    private fun setupUI() {
        // 语音输入按钮 - 长按录音
        binding.voiceInputBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.tvVoiceHint.text = "正在聆听..."
                    startVoiceInput()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    voiceInputManager.stopListening()
                    binding.tvVoiceHint.text = "按住按钮说话"
                    true
                }
                else -> false
            }
        }

        // 发送按钮
        binding.btnSend.setOnClickListener {
            sendCommand()
        }

        // 回车键发送
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }

        // 语音输出开关
        binding.btnToggleVoiceOutput.setOnClickListener {
            voiceOutputEnabled = !voiceOutputEnabled
            prefsManager.setVoiceOutputEnabled(voiceOutputEnabled)
            updateVoiceOutputIcon()
            Toast.makeText(requireContext(),
                if (voiceOutputEnabled) "语音输出已开启" else "语音输出已关闭",
                Toast.LENGTH_SHORT).show()
        }

        updateVoiceOutputIcon()
    }

    private fun startVoiceInput() {
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            voiceInputManager.startListening().collect { result ->
                when (result) {
                    is VoiceResult.Partial -> {
                        binding.etCommand.setText(result.text)
                    }
                    is VoiceResult.Final -> {
                        binding.etCommand.setText(result.text)
                    }
                    is VoiceResult.Error -> {
                        binding.tvVoiceHint.text = result.message
                    }
                    VoiceResult.Ready -> {
                        // 语音识别就绪
                    }
                }
            }
        }
    }

    private fun updateVoiceOutputIcon() {
        binding.btnToggleVoiceOutput.setImageResource(
            if (voiceOutputEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
    }

    private fun sendCommand() {
        val command = binding.etCommand.text.toString()
        if (command.isEmpty()) return

        if (!sshManager.isShellReady()) {
            Toast.makeText(requireContext(), "SSH未连接", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            sshManager.writeToShellAsync(command + "\n")
            binding.etCommand.text?.clear()
        }

        appendOutput(">$command\n")
    }

    private fun connect(serverConfig: ServerConfig) {
        // 检查是否已经连接（复用 TerminalFragment 的连接）
        if (sshManager.isConnected() && sshManager.isShellReady()) {
            binding.tvStatus.text = "已连接"
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot)
            return
        }

        binding.tvStatus.text = "连接中..."
        binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting)

        lifecycleScope.launch {
            try {
                val result = sshManager.connect(serverConfig)

                result.fold(
                    onSuccess = {
                        binding.tvStatus.text = "已连接"
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_dot)
                        startShell()
                    },
                    onFailure = { error ->
                        binding.tvStatus.text = "连接失败"
                        showError("连接失败", error.message ?: "未知错误")
                    }
                )
            } catch (e: Exception) {
                showError("连接异常", e.message ?: "未知错误")
            }
        }
    }

    private fun startShell() {
        shellJob = lifecycleScope.launch {
            try {
                sshManager.openShell(
                    onOutput = { output ->
                        if (isAdded && activity != null) {
                            appendOutput(output)
                        }
                    },
                    onConnected = { },
                    cols = 80,
                    rows = 24
                )

                // 执行初始命令
                server?.let { srv ->
                    if (srv.initCommand.isNotBlank()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            executeInitCommand(srv.initCommand)
                        }, 500)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun executeInitCommand(command: String) {
        if (command.isBlank()) return
        lifecycleScope.launch {
            sshManager.writeToShellAsync(command + "\n")
        }
    }

    private fun appendOutput(text: String) {
        activity?.runOnUiThread {
            // 检测工作状态
            detectWorkingState(text)
            lastOutputTime = System.currentTimeMillis()

            outputBuffer.append(text)
            val displayText = formatOutput(outputBuffer.toString())
            binding.tvOutput.text = displayText

            binding.outputScroll.post {
                binding.outputScroll.fullScroll(View.FOCUS_DOWN)
            }

            // 只在非工作状态且有新内容时朗读
            if (voiceOutputEnabled && text.isNotBlank() && !isClaudeWorking) {
                speakNewContent(text)
            }
        }
    }

    private fun formatOutput(raw: String): String {
        val ansiRegex = "\u001B\\[[;\\d]*[ -/]*[@-~]".toRegex()
        var result = raw.replace(ansiRegex, "")

        result = result.replace("\u0002", "")
            .replace("\u0015", "")
            .replace("\u0004", "")

        result = result.replace("\r\n", "\n").replace("\r", "\n")

        // 过滤Claude Code的工具调用和中间状态
        result = filterClaudeOutput(result)

        if (result.length > 50000) {
            result = result.takeLast(40000)
        }

        return result
    }

    /**
     * 过滤Claude Code输出，只保留最终文字回答
     */
    private fun filterClaudeOutput(text: String): String {
        val lines = text.split("\n")
        val resultLines = mutableListOf<String>()
        var inCodeBlock = false
        var inToolBlock = false
        var braceCount = 0

        for (line in lines) {
            val trimmed = line.trim()

            // 代码块处理
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) continue

            // 工具调用块检测 - 以 ● 或 ○ 开头的行
            if (trimmed.startsWith("●") || trimmed.startsWith("○")) {
                inToolBlock = true
                continue
            }

            // 检测JSON/工具输出（包含大量花括号）
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
            if (braceCount > 0 || (inToolBlock && braceCount == 0 && trimmed.isEmpty())) {
                if (braceCount == 0) inToolBlock = false
                continue
            }

            // 跳过进度动画字符
            if (trimmed.any { it in "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏█░▓" }) continue

            // 跳过特定关键词行
            val skipKeywords = listOf(
                "orbiting", "scanning", "analyzing", "processing",
                "Error:", "Traceback", "File \"", "line ",
                "ModuleNotFoundError", "ImportError"
            )
            if (skipKeywords.any { trimmed.contains(it, ignoreCase = true) }) continue

            // 跳过tmux状态栏格式
            if (trimmed.matches(Regex("^\\[.*\\]\\s*\\d+:\\[.*\\].*$"))) continue

            // 跳过文件权限格式行
            if (trimmed.matches(Regex("^[-d][rwx-]{9}\\s+.*"))) continue

            // 跳过空行（连续的只保留一个）
            if (trimmed.isEmpty()) {
                if (resultLines.isEmpty() || resultLines.last().isNotEmpty()) {
                    resultLines.add("")
                }
                continue
            }

            // 跳过命令提示符和命令回显
            if (trimmed.startsWith(">") && trimmed.length < 100) continue
            if (trimmed.matches(Regex("^[#$>~]\\s*\\w+.*"))) continue

            // 保留有效内容
            resultLines.add(line)
        }

        // 清理结果：移除开头和结尾的空行，合并连续空行
        return resultLines
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    /**
     * 检测Claude是否正在工作
     */
    private fun detectWorkingState(text: String) {
        val workingPatterns = listOf(
            Regex("[●○]\\s*(thinking|bash|update|read|write|edit|search)", RegexOption.IGNORE_CASE),
            Regex("orbiting", RegexOption.IGNORE_CASE),
            Regex("⠋|⠙|⠹|⠸|⠼|⠴|⠦|⠧|⠇|⠏")
        )

        val isWorking = workingPatterns.any { it.containsMatchIn(text) }

        if (isWorking && !isClaudeWorking) {
            isClaudeWorking = true
            updateWorkingStatus(true)
        } else if (isWorking) {
            // 重置空闲计时器
            lastOutputTime = System.currentTimeMillis()
            workingIndicatorHandler.removeCallbacks(checkIdleRunnable)
            workingIndicatorHandler.postDelayed(checkIdleRunnable, 2000)
        }
    }

    private val checkIdleRunnable = Runnable {
        if (isClaudeWorking && System.currentTimeMillis() - lastOutputTime > 2000) {
            isClaudeWorking = false
            updateWorkingStatus(false)
        }
    }

    private fun updateWorkingStatus(working: Boolean) {
        activity?.runOnUiThread {
            if (working) {
                binding.tvStatus.text = "正在工作..."
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting)
            } else {
                binding.tvStatus.text = "已完成"
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot)
            }
        }
    }

    private fun speakNewContent(text: String) {
        val cleanText = text
            .replace("\u001B\\[[;\\d]*[ -/]*[@-~]".toRegex(), "")
            .replace(Regex("[\\[\\]\\(\\)\\{\\}\\|\\<\\>]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanText.isNotBlank() && cleanText.length > 1) {
            val speakText = if (cleanText.length > 200) {
                cleanText.take(200) + "..."
            } else {
                cleanText
            }
            textToSpeech?.speak(speakText, TextToSpeech.QUEUE_ADD, null, null)
        }
    }

    private fun showError(title: String, message: String) {
        if (!isAdded || activity == null) return
        activity?.runOnUiThread {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("重试") { _, _ -> server?.let { connect(it) } }
                .setNegativeButton("返回") { _, _ ->
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shellJob?.cancel()
        voiceJob?.cancel()
        workingIndicatorHandler.removeCallbacks(checkIdleRunnable)
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        if (::sshManager.isInitialized) {
            sshManager.disconnect()
        }
    }
}