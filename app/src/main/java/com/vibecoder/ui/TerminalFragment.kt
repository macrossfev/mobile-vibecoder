package com.vibecoder.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import com.vibecoder.ui.widget.VirtualKeyboardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TerminalFragment : Fragment() {

    companion object {
        private const val TAG = "TerminalFragment"
        private const val ARG_SERVER_JSON = "server_json"

        fun newInstance(server: ServerConfig) = TerminalFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERVER_JSON, Gson().toJson(server))
            }
        }
    }

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

        Log.d(TAG, "onViewCreated - sshManager initialized: ${::sshManager.isInitialized}")

        val currentServer = server
        if (currentServer == null) {
            showError("错误", "服务器配置无效")
            return
        }

        if (!::sshManager.isInitialized) {
            Log.d(TAG, "Getting SSHManager singleton instance")
            sshManager = SSHManager.getInstance()
        } else {
            Log.d(TAG, "Reusing existing SSHManager - connected: ${sshManager.isConnected()}, shellReady: ${sshManager.isShellReady()}")
        }

        if (!::prefsManager.isInitialized) {
            prefsManager = PreferencesManager(requireContext())
        }

        setupTerminal()
        setupInput()
        setupDirectionKeys()
        setupFunctionKeys()
        setupVirtualKeyboard()

        // 检查连接状态
        if (sshManager.isConnected() && sshManager.isShellReady()) {
            // 已连接且 Shell 就绪，只需要重新等待 WebView 就绪
            Log.d(TAG, "SSH still connected, just waiting for WebView")
            isTerminalReady = false
            // 调整 PTY 大小以匹配新屏幕尺寸
            sshManager.resizePty(terminalCols, terminalRows)
        } else if (sshManager.isConnected() && !sshManager.isShellReady()) {
            // SSH 连接但 Shell 未就绪，需要重新打开 Shell
            Log.d(TAG, "SSH connected but shell not ready, starting shell")
            startShell()
        } else {
            // 未连接，执行连接
            Log.d(TAG, "SSH not connected, initiating connection")
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

        // 长按方向键连续发送
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var upRepeat: Runnable? = null
        var downRepeat: Runnable? = null
        var leftRepeat: Runnable? = null
        var rightRepeat: Runnable? = null

        binding.btnUp.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendArrowKey("up")
                    upRepeat = object : Runnable {
                        override fun run() {
                            sendArrowKey("up")
                            handler.postDelayed(this, 80)
                        }
                    }
                    handler.postDelayed(upRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    upRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        binding.btnDown.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendArrowKey("down")
                    downRepeat = object : Runnable {
                        override fun run() {
                            sendArrowKey("down")
                            handler.postDelayed(this, 80)
                        }
                    }
                    handler.postDelayed(downRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    downRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        binding.btnLeft.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendArrowKey("left")
                    leftRepeat = object : Runnable {
                        override fun run() {
                            sendArrowKey("left")
                            handler.postDelayed(this, 80)
                        }
                    }
                    handler.postDelayed(leftRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    leftRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        binding.btnRight.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendArrowKey("right")
                    rightRepeat = object : Runnable {
                        override fun run() {
                            sendArrowKey("right")
                            handler.postDelayed(this, 80)
                        }
                    }
                    handler.postDelayed(rightRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rightRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFunctionKeys() {
        // 查看按钮: 进入tmux copy mode
        binding.btnView.setOnClickListener { tmuxEnterCopyMode() }

        // Esc键
        binding.btnEsc.setOnClickListener { sendEsc() }

        // 自定义快捷键 (支持三键组合)
        binding.btnCustom.setOnClickListener { sendCustomShortcut() }
        binding.btnCustom.setOnLongClickListener { editCustomShortcut(); true }

        // 快捷命令按钮: 点击直接执行保存的命令，长按设置
        binding.btnQuickCommand.setOnClickListener { executeQuickCommand() }
        binding.btnQuickCommand.setOnLongClickListener { editQuickCommand(); true }

        // 更新按钮文本
        updateCustomShortcutButton()
        updateQuickCommandButton()
    }

    private fun executeQuickCommand() {
        val command = prefsManager.getQuickCommand()
        if (command.isNullOrBlank()) {
            // 没有设置命令，提示用户长按设置
            Toast.makeText(requireContext(), "长按设置快捷命令", Toast.LENGTH_SHORT).show()
        } else {
            executeCommand(command)
        }
    }

    private fun editQuickCommand(): Boolean {
        val current = prefsManager.getQuickCommand() ?: ""

        val input = android.widget.EditText(requireContext()).apply {
            setText(current)
            setSingleLine()
            hint = "输入命令"
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("设置快捷命令")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val command = input.text.toString().trim()
                prefsManager.saveQuickCommand(command)
                updateQuickCommandButton()
                Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
        return true
    }

    private fun updateQuickCommandButton() {
        val command = prefsManager.getQuickCommand()
        if (!command.isNullOrBlank()) {
            // 显示简短命令名
            val display = if (command.length > 6) command.take(6) + ".." else command
            binding.btnQuickCommand.text = display
        } else {
            binding.btnQuickCommand.text = "快捷命令"
        }
    }

    private fun sendCustomShortcut() {
        val shortcut = prefsManager.getCustomShortcut() ?: "Ctrl+C"
        sendShortcut(shortcut)
    }

    private fun sendShortcut(shortcut: String) {
        if (!sshManager.isShellReady()) return

        val parts = shortcut.split("+")
        val key = parts.last().lowercase()
        val hasCtrl = parts.any { it.equals("Ctrl", ignoreCase = true) }
        val hasAlt = parts.any { it.equals("Alt", ignoreCase = true) }
        val hasShift = parts.any { it.equals("Shift", ignoreCase = true) }

        lifecycleScope.launch {
            if (key == "esc") {
                sshManager.writeToShellAsync("\u001B")
            } else if (key == "del") {
                sshManager.writeToShellAsync("\u007F")
            } else if (key == "tab") {
                sshManager.writeToShellAsync("\t")
            } else if (key == "enter") {
                sshManager.writeToShellAsync("\r")
            } else if (key.length == 1 && key[0].isLetter()) {
                val code = key[0].lowercaseChar()
                if (hasCtrl) {
                    sshManager.writeToShellAsync((code.code - 'a'.code + 1).toChar().toString())
                } else if (hasAlt) {
                    sshManager.writeToShellAsync("\u001B$code")
                } else {
                    sshManager.writeToShellAsync(if (hasShift) code.uppercaseChar().toString() else code.toString())
                }
            } else if (key.length == 1 && key[0].isDigit()) {
                sshManager.writeToShellAsync(key)
            }
        }
    }

    private fun editCustomShortcut() {
        val current = prefsManager.getCustomShortcut() ?: "Ctrl+C"

        // 预设选项
        val presetOptions = arrayOf(
            "Ctrl+C", "Ctrl+D", "Ctrl+Z", "Ctrl+A", "Ctrl+E",
            "Ctrl+L", "Ctrl+R", "Ctrl+W", "Ctrl+K", "Ctrl+U",
            "Alt+B", "Alt+F", "Ctrl+Shift+C", "Ctrl+Shift+V"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择快捷键组合")
            .setItems(presetOptions) { _, which ->
                prefsManager.saveCustomShortcut(presetOptions[which])
                updateCustomShortcutButton()
            }
            .setNeutralButton("自定义") { _, _ ->
                showCustomShortcutDialog()
            }
            .show()
    }

    private fun showCustomShortcutDialog() {
        val view = android.view.LayoutInflater.from(requireContext())
            .inflate(android.R.layout.two_line_list_item, null)

        val modifiersLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
        }

        val ctrlCb = android.widget.CheckBox(requireContext()).apply { text = "Ctrl" }
        val altCb = android.widget.CheckBox(requireContext()).apply { text = "Alt" }
        val shiftCb = android.widget.CheckBox(requireContext()).apply { text = "Shift" }

        modifiersLayout.addView(ctrlCb)
        modifiersLayout.addView(altCb)
        modifiersLayout.addView(shiftCb)

        val keyInput = android.widget.EditText(requireContext()).apply {
            hint = "按键 (如: C, D, Tab, Enter)"
            setSingleLine()
        }

        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(modifiersLayout)
            addView(keyInput)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("自定义快捷键")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val modifiers = mutableListOf<String>()
                if (ctrlCb.isChecked) modifiers.add("Ctrl")
                if (altCb.isChecked) modifiers.add("Alt")
                if (shiftCb.isChecked) modifiers.add("Shift")
                modifiers.add(keyInput.text.toString().trim().uppercase())

                val shortcut = modifiers.joinToString("+")
                if (shortcut.isNotBlank()) {
                    prefsManager.saveCustomShortcut(shortcut)
                    updateCustomShortcutButton()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateCustomShortcutButton() {
        val shortcut = prefsManager.getCustomShortcut() ?: "Ctrl+C"
        // 显示简短版本
        binding.btnCustom.text = if (shortcut.length > 8) shortcut.take(8) + ".." else shortcut
    }

    private fun openVoiceMode() {
        server?.let { srv ->
            val fragment = VoiceTerminalFragment.newInstance(srv)
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        }
    }

    // tmux 操作
    private var inTmuxCopyMode = false

    private fun tmuxEnterCopyMode() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            sshManager.writeToShellAsync("\u0002[")  // Ctrl+B [
            inTmuxCopyMode = true
        }
    }

    private fun tmuxExitCopyMode() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            sshManager.writeToShellAsync("q")
            inTmuxCopyMode = false
        }
    }

    private fun setupVirtualKeyboard() {
        val keyboard = binding.virtualKeyboard ?: return

        // 键盘按键回调
        keyboard.onKeyPressed = { key, _ ->
            if (sshManager.isShellReady()) {
                lifecycleScope.launch {
                    sshManager.writeToShellAsync(key)
                }
            }
        }

        keyboard.onEnterPressed = {
            sendEnter()
        }

        keyboard.onBackspacePressed = {
            if (sshManager.isShellReady()) {
                lifecycleScope.launch {
                    sshManager.writeToShellAsync("\u0008") // Backspace
                }
            }
        }

        // 默认隐藏，可以通过设置开启
        if (prefsManager.isVirtualKeyboardEnabled()) {
            keyboard.show()
        }
    }

    private fun inputText() {
        val command = binding.etCommand.text.toString()
        if (command.isEmpty()) return

        if (!sshManager.isShellReady()) {
            // 检查连接状态
            if (!sshManager.isConnected()) {
                Toast.makeText(requireContext(), "SSH未连接，正在重连...", Toast.LENGTH_SHORT).show()
                server?.let { connect(it) }
            } else {
                Toast.makeText(requireContext(), "Shell初始化中，请稍候...", Toast.LENGTH_SHORT).show()
            }
            return
        }

        lifecycleScope.launch {
            sshManager.writeToShellAsync(command)
            binding.etCommand.text?.clear()
        }
    }

    private fun executeCommand(command: String) {
        if (!sshManager.isShellReady()) {
            Toast.makeText(requireContext(), "Shell未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            sshManager.writeToShellAsync(command + "\n")
        }
    }

    private fun sendEnter() {
        if (!sshManager.isShellReady()) {
            if (!sshManager.isConnected()) {
                server?.let { connect(it) }
            }
            return
        }
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

    private fun sendDel() {
        if (!sshManager.isShellReady()) return
        lifecycleScope.launch {
            // DEL 键序列 (ASCII 127 或 Escape 序列)
            sshManager.writeToShellAsync("\u007F")
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

    private fun sendShiftPageUp() {
        if (!sshManager.isShellReady()) return
        // Shift + Page Up: 用于终端滚动
        lifecycleScope.launch {
            sshManager.writeToShellAsync("\u001B[5;2~")
        }
    }

    private fun sendShiftPageDown() {
        if (!sshManager.isShellReady()) return
        // Shift + Page Down: 用于终端滚动
        lifecycleScope.launch {
            sshManager.writeToShellAsync("\u001B[6;2~")
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
                        // Shell 就绪，tmux 附加在 TerminalJsInterface.onReady 中处理
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
        Log.d(TAG, "attachTmuxSession called with session: $sessionName")
        // 检测是否已在tmux会话中，如果是则不重复附加
        // 发送检测命令：如果$TMUX变量存在则已在tmux中
        val safeSession = sessionName.replace(" ", "_").replace("\"", "\\\"").replace("'", "'\\''")

        // 使用单行命令：检测TMUX变量，如果存在则跳过，否则附加或创建
        val tmuxCommand = "[ -n \"\$TMUX\" ] || tmux attach-session -t '$safeSession' 2>/dev/null || tmux new-session -s '$safeSession'\n"
        Log.d(TAG, "Sending tmux command: $tmuxCommand")

        lifecycleScope.launch {
            val result = sshManager.writeToShellAsync(tmuxCommand)
            Log.d(TAG, "tmux command sent: $result")
        }
    }

    private fun executeInitCommand(command: String) {
        if (command.isBlank()) return
        Log.d(TAG, "Executing init command: $command")
        lifecycleScope.launch {
            sshManager.writeToShellAsync(command + "\n")
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
            Log.d(TAG, "onReady called: cols=$cols, rows=$rows")
            terminalCols = cols
            terminalRows = rows
            isTerminalReady = true
            // 终端就绪后刷新缓冲区中的待处理数据
            activity?.runOnUiThread {
                flushPendingOutput()
                // 如果启用了 tmux 且 shell 就绪，尝试附加
                // attachTmuxSession 内部会检测是否已在tmux中，避免重复附加
                server?.let { srv ->
                    Log.d(TAG, "Server config: useTmux=${srv.useTmux}, tmuxSession=${srv.tmuxSession}, shellReady=${sshManager.isShellReady()}")

                    // 执行初始命令
                    if (srv.initCommand.isNotBlank() && sshManager.isShellReady()) {
                        uiHandler.postDelayed({
                            executeInitCommand(srv.initCommand)
                        }, 500)
                    }

                    if (srv.useTmux && sshManager.isShellReady()) {
                        Log.d(TAG, "Conditions met, attaching tmux session")
                        // 延迟一小段时间确保终端完全就绪
                        uiHandler.postDelayed({
                            attachTmuxSession(srv.tmuxSession)
                        }, 300)
                    } else {
                        Log.d(TAG, "Conditions not met for tmux: useTmux=${srv.useTmux}, shellReady=${sshManager.isShellReady()}")
                    }
                } ?: Log.d(TAG, "Server config is null")
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
        Log.d(TAG, "onDestroyView - retainInstance: $retainInstance, sshManager initialized: ${::sshManager.isInitialized}, connected: ${if (::sshManager.isInitialized) sshManager.isConnected() else "N/A"}")
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
        Log.d(TAG, "onDestroy - Fragment being destroyed")
        super.onDestroy()
        // 只有在 Fragment 真正销毁时才断开连接
        shellJob?.cancel()
        if (::sshManager.isInitialized) {
            sshManager.disconnect()
        }
    }
}