package com.vibecoder.ui

import android.annotation.SuppressLint
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalFragment : Fragment() {

    private var _binding: FragmentTerminalBinding? = null
    private val binding get() = _binding!!

    private var server: ServerConfig? = null
    private lateinit var sshManager: SSHManager
    private lateinit var prefsManager: PreferencesManager

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

    // 自定义快捷键设置
    private var customKeyLabels = arrayOf("F1", "F2", "F3")
    private var customKeyCommands = arrayOf("", "", "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverJson = arguments?.getString(ARG_SERVER_JSON)
        if (!serverJson.isNullOrBlank()) {
            server = Gson().fromJson(serverJson, ServerConfig::class.java)
        }

        // 保存 Fragment 状态，防止重建
        retainInstance = true
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

        if (!::sshManager.isInitialized) {
            sshManager = SSHManager()
        }

        if (!::prefsManager.isInitialized) {
            prefsManager = PreferencesManager(requireContext())
        }

        // 加载自定义快捷键设置
        loadCustomKeys()

        setupTerminal()
        setupInput()
        setupDirectionKeys()
        setupCustomKeys()

        // 只有在未连接时才连接
        if (!sshManager.isConnected()) {
            connect(currentServer)
        }
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

    private fun setupDirectionKeys() {
        binding.btnUp.setOnClickListener { sendArrowKey("up") }
        binding.btnDown.setOnClickListener { sendArrowKey("down") }
        binding.btnLeft.setOnClickListener { sendArrowKey("left") }
        binding.btnRight.setOnClickListener { sendArrowKey("right") }

        binding.btnEsc.setOnClickListener { sendEsc() }
    }

    private fun setupCustomKeys() {
        // 设置按钮文本
        binding.btnCustom1.text = customKeyLabels[0]
        binding.btnCustom2.text = customKeyLabels[1]
        binding.btnCustom3.text = customKeyLabels[2]

        // 长按编辑
        binding.btnCustom1.setOnLongClickListener { editCustomKey(0); true }
        binding.btnCustom2.setOnLongClickListener { editCustomKey(1); true }
        binding.btnCustom3.setOnLongClickListener { editCustomKey(2); true }

        // 点击执行
        binding.btnCustom1.setOnClickListener { executeCustomKey(0) }
        binding.btnCustom2.setOnClickListener { executeCustomKey(1) }
        binding.btnCustom3.setOnClickListener { executeCustomKey(2) }
    }

    private fun loadCustomKeys() {
        customKeyLabels = prefsManager.getCustomKeyLabels()
        customKeyCommands = prefsManager.getCustomKeyCommands()
    }

    private fun saveCustomKeys() {
        prefsManager.saveCustomKeyLabels(customKeyLabels)
        prefsManager.saveCustomKeyCommands(customKeyCommands)
    }

    private fun editCustomKey(index: Int) {
        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 36, 48, 24)
        }

        val labelEdit = android.widget.EditText(requireContext()).apply {
            hint = "按钮名称"
            setText(customKeyLabels[index])
        }
        container.addView(labelEdit)

        val commandEdit = android.widget.EditText(requireContext()).apply {
            hint = "发送的内容（留空则发送功能键）"
            setText(customKeyCommands[index])
            setSingleLine(false)
            minLines = 2
        }
        container.addView(commandEdit)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑快捷键")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                customKeyLabels[index] = labelEdit.text.toString().ifBlank { "F${index + 1}" }
                customKeyCommands[index] = commandEdit.text.toString()
                saveCustomKeys()

                // 更新按钮
                when (index) {
                    0 -> binding.btnCustom1.text = customKeyLabels[0]
                    1 -> binding.btnCustom2.text = customKeyLabels[1]
                    2 -> binding.btnCustom3.text = customKeyLabels[2]
                }

                Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun executeCustomKey(index: Int) {
        val command = customKeyCommands[index]
        if (command.isNotBlank()) {
            // 发送自定义命令
            lifecycleScope.launch {
                sshManager.writeToShellAsync(command)
            }
        } else {
            // 默认发送功能键 F1-F3
            val keyCode = when (index) {
                0 -> "\u001BOP"   // F1
                1 -> "\u001BOQ"   // F2
                2 -> "\u001BOR"   // F3
                else -> return
            }
            lifecycleScope.launch {
                sshManager.writeToShellAsync(keyCode)
            }
        }
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

    private fun sendArrowKey(direction: String) {
        if (!sshManager.isShellReady()) return
        val sequence = when (direction) {
            "up" -> "\u001B[A"
            "down" -> "\u001B[B"
            "left" -> "\u001B[D"
            "right" -> "\u001B[C"
            else -> return
        }
        lifecycleScope.launch {
            sshManager.writeToShellAsync(sequence)
        }
    }

    private fun connect(serverConfig: ServerConfig) {
        lifecycleScope.launch {
            try {
                val result = sshManager.connect(serverConfig)

                result.fold(
                    onSuccess = {
                        startShell()
                    },
                    onFailure = { error ->
                        showError("连接失败", error.message ?: "未知错误")
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
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
                    onConnected = { },
                    cols = terminalCols,
                    rows = terminalRows
                )

                result.fold(
                    onSuccess = {
                        // Shell 就绪后，检查是否启用 tmux
                        server?.let { srv ->
                            if (srv.useTmux) {
                                // 延迟发送 tmux 命令，等待 shell 完全就绪
                                Thread.sleep(500)
                                attachTmuxSession(srv.tmuxSession)
                            }
                        }
                    },
                    onFailure = { error ->
                        if (isAdded && activity != null) {
                            activity?.runOnUiThread {
                                Toast.makeText(requireContext(), "Shell错误: ${error.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun attachTmuxSession(sessionName: String) {
        // tmux attach -t session || tmux new -s session
        val safeSession = sessionName.replace(" ", "_").replace("\"", "\\\"")
        val tmuxCommand = "tmux attach -t \"$safeSession\" 2>/dev/null || tmux new -s \"$safeSession\"\n"
        lifecycleScope.launch {
            sshManager.writeToShellAsync(tmuxCommand)
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
            // 通知 SSH 调整 PTY 大小
            sshManager.resizePty(cols, rows)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingOutput?.let { uiHandler.removeCallbacks(it) }
        flushBatch() // 刷新剩余输出
        // 注意：不要断开 SSH 连接，因为 retainInstance = true
        try {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            // ignore
        }
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 只有在 Fragment 真正销毁时才断开连接
        shellJob?.cancel()
        if (::sshManager.isInitialized) {
            sshManager.disconnect()
        }
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