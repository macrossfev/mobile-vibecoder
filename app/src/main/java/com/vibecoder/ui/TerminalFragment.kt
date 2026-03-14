package com.vibecoder.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.vibecoder.R
import com.vibecoder.data.PreferencesManager
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentTerminalBinding
import com.vibecoder.ssh.SSHManager
import com.vibecoder.voice.AICommandInterpreter
import com.vibecoder.voice.VoiceInputManager
import com.vibecoder.voice.VoiceResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private var server: ServerConfig? = null
    private lateinit var sshManager: SSHManager
    private lateinit var prefsManager: PreferencesManager
    private lateinit var voiceManager: VoiceInputManager

    private var shellJob: Job? = null
    private var commandHistoryIndex = -1
    private val commandHistory = mutableListOf<String>()
    private var terminalCols = 80
    private var terminalRows = 24

    // 输出缓冲
    private val outputBuffer = StringBuilder()
    private var isTerminalReady = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingOutput: Runnable? = null
    private val batchBuffer = StringBuilder()
    private var lastFlushTime = 0L
    private val FLUSH_INTERVAL = 50L

    // 权限请求
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceInput()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverJson = arguments?.getString(ARG_SERVER_JSON)
        if (!serverJson.isNullOrBlank()) {
            server = Gson().fromJson(serverJson, ServerConfig::class.java)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTerminalBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentServer = server
        if (currentServer == null) {
            showError("错误", "服务器配置无效")
            return
        }

        sshManager = SSHManager()
        prefsManager = PreferencesManager(requireContext())
        voiceManager = VoiceInputManager(requireContext())

        setupTerminal()
        setupInput()
        setupVoice()
        connect(currentServer)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupTerminal() {
        binding.terminalView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        binding.terminalView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isTerminalReady = true
            }
        }

        binding.terminalView.webChromeClient = WebChromeClient()
        binding.terminalView.addJavascriptInterface(TerminalJsInterface(), "AndroidTerminal")
        binding.terminalView.loadUrl("file:///android_asset/terminal.html")

        if (prefsManager.isKeepScreenOn()) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setupInput() {
        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                sendEnter()
                true
            } else {
                false
            }
        }

        binding.btnSend.setOnClickListener { inputText() }
        binding.btnEnter.setOnClickListener { sendEnter() }

        binding.etCommand.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { navigateHistory(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { navigateHistory(1); true }
                    KeyEvent.KEYCODE_ENTER -> { sendEnter(); true }
                    else -> false
                }
            } else {
                false
            }
        }

        loadCommandHistory()
    }

    private fun inputText() {
        val command = binding.etCommand.text.toString()
        if (command.isEmpty()) return

        if (!sshManager.isShellReady()) {
            Toast.makeText(requireContext(), "Shell未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            sshManager.writeToShellAsync(command)
            binding.etCommand.text?.clear()
        }
    }

    private fun sendEnter() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            sshManager.sendEnterAsync()
        }
    }

    private fun sendTab() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            sshManager.writeToShellAsync("\t")
        }
    }

    private fun sendEsc() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            sshManager.writeToShellAsync("\u001B")
        }
    }

    private fun setupVoice() {
        binding.btnVoice.setOnClickListener {
            if (checkAudioPermission()) startVoiceInput() else requestAudioPermission()
        }

        binding.btnCtrlC.setOnClickListener {
            lifecycleScope.launch { sshManager.sendCtrlC() }
        }

        binding.btnCtrlD.setOnClickListener {
            lifecycleScope.launch { sshManager.sendCtrlD() }
        }

        binding.btnTab.setOnClickListener { sendTab() }
        binding.btnEsc.setOnClickListener { sendEsc() }
    }

    private fun connect(serverConfig: ServerConfig) {
        updateStatus("正在连接 ${serverConfig.name}...")

        lifecycleScope.launch {
            try {
                val result = sshManager.connect(serverConfig)

                result.fold(
                    onSuccess = {
                        updateStatus("SSH已连接，正在打开Shell...")
                        startShell()
                    },
                    onFailure = { error ->
                        updateStatus("连接失败: ${error.message}")
                        showError("连接失败", error.message ?: "未知错误")
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus("连接异常: ${e.message}")
                showError("连接异常", e.message ?: "未知错误")
            }
        }
    }

    private fun startShell() {
        shellJob = lifecycleScope.launch {
            try {
                // 等待终端就绪
                while (!isTerminalReady) {
                    Thread.sleep(50)
                }

                val result = sshManager.openShell(
                    onOutput = { output ->
                        if (isAdded && activity != null && isTerminalReady) {
                            appendOutput(output)
                        } else if (isAdded && activity != null) {
                            // 终端未就绪时缓冲
                            synchronized(outputBuffer) {
                                outputBuffer.append(output)
                            }
                        }
                    },
                    onConnected = {
                        if (isAdded && activity != null) {
                            activity?.runOnUiThread {
                                updateStatus("Shell就绪")
                            }
                        }
                    },
                    cols = terminalCols,
                    rows = terminalRows
                )

                result.fold(
                    onSuccess = { },
                    onFailure = { error ->
                        if (isAdded && activity != null) {
                            activity?.runOnUiThread {
                                updateStatus("Shell错误: ${error.message}")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 批量输出到终端（减少 UI 线程压力）
    private fun appendOutput(text: String) {
        synchronized(batchBuffer) {
            batchBuffer.append(text)
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        val now = System.currentTimeMillis()
        if (now - lastFlushTime >= FLUSH_INTERVAL) {
            // 立即刷新
            flushBatch()
        } else {
            // 延迟刷新
            pendingOutput?.let { uiHandler.removeCallbacks(it) }
            pendingOutput = Runnable { flushBatch() }
            uiHandler.postDelayed(pendingOutput!!, FLUSH_INTERVAL)
        }
    }

    private fun flushBatch() {
        val text = synchronized(batchBuffer) {
            val t = batchBuffer.toString()
            batchBuffer.clear()
            t
        }
        if (text.isEmpty()) return

        lastFlushTime = System.currentTimeMillis()

        // 调试：打印实际发送的内容（转义控制字符）
        val debugText = text
            .replace("\u001B", "\\e")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        android.util.Log.d("TerminalOutput", "Sending ${text.length} chars: $debugText")

        activity?.runOnUiThread {
            try {
                val base64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                binding.terminalView.evaluateJavascript("writeToTerminalBase64('$base64')", null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 刷新缓冲区（终端就绪时调用）
    private fun flushPendingOutput() {
        val text = synchronized(outputBuffer) {
            val t = outputBuffer.toString()
            outputBuffer.clear()
            t
        }
        if (text.isNotEmpty() && isTerminalReady) {
            synchronized(batchBuffer) {
                batchBuffer.append(text)
            }
            flushBatch()
        }
    }

    private fun updateStatus(status: String) {
        if (!isAdded || activity == null) return
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.tvStatus.text = status
            }
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
        server?.let { srv ->
            val history = prefsManager.getCommandHistory(srv.id)
            commandHistory.clear()
            commandHistory.addAll(history.map { it.command }.reversed())
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startVoiceInput() {
        if (!voiceManager.isAvailable()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("语音识别不可用")
                .setMessage("您的设备不支持语音识别。\n\n请使用输入法直接输入命令。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        binding.voiceIndicator.isVisible = true

        lifecycleScope.launch {
            voiceManager.startListening().collect { result ->
                when (result) {
                    is VoiceResult.Partial -> binding.voiceIndicator.text = "识别中: ${result.text}"
                    is VoiceResult.Final -> {
                        binding.voiceIndicator.isVisible = false
                        handleVoiceInput(result.text)
                    }
                    is VoiceResult.Error -> {
                        binding.voiceIndicator.isVisible = false
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                    is VoiceResult.Ready -> binding.voiceIndicator.text = "请说话..."
                }
            }
        }
    }

    private fun handleVoiceInput(text: String) {
        val options = arrayOf("直接执行", "AI理解后执行")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("语音输入: \"$text\"")
            .setItems(options) { _, which ->
                if (which == 0) {
                    binding.etCommand.setText(text)
                    inputText()
                    sendEnter()
                } else {
                    interpretVoiceCommand(text)
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
            if (commandResult.dangerous) append("\n\n⚠️ 此命令可能有风险!")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("AI理解结果")
            .setMessage(message)
            .setPositiveButton("执行") { _, _ ->
                binding.etCommand.setText(commandResult.command)
                inputText()
                sendEnter()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showError(title: String, message: String) {
        if (!isAdded || activity == null) return
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("重试") { _, _ -> server?.let { connect(it) } }
                .setNegativeButton("返回") { _, _ ->
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                .setCancelable(false)
                .show()
        }
    }

    // JavaScript 接口类
    inner class TerminalJsInterface {
        @JavascriptInterface
        fun onReady(cols: Int, rows: Int) {
            terminalCols = cols
            terminalRows = rows
            isTerminalReady = true
            // 终端就绪后刷新缓冲区中的待处理数据
            activity?.runOnUiThread {
                flushPendingOutput()
            }
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) {
            terminalCols = cols
            terminalRows = rows
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingOutput?.let { uiHandler.removeCallbacks(it) }
        flushBatch() // 刷新剩余输出
        shellJob?.cancel()
        sshManager.disconnect()
        voiceManager.cancel()
        try {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            // ignore
        }
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_JSON = "server_json"

        fun newInstance(server: ServerConfig) = TerminalFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERVER_JSON, Gson().toJson(server))
            }
        }
    }
}