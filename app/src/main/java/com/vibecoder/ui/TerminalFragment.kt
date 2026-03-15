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
import com.vibecoder.ui.widget.ScrollBallView
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

    // 快速输入设置 (3个可自定义)
    private var quickInputLabels = arrayOf("快1", "快2", "快3")
    private var quickInputContents = arrayOf("docker ps -a", "kubectl get pods", "htop")

    // 功能键设置 (F1-F3 可自定义组合键)
    private var functionKeyModifiers = arrayOf("", "", "")  // Ctrl, Shift, Alt
    private var functionKeyChars = arrayOf("c", "d", "z")   // 字母或数字

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
            Log.d(TAG, "Creating new SSHManager instance")
            sshManager = SSHManager()
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
        setupQuickInput()
        setupScrollButtons()
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

        binding.btnEsc.setOnClickListener { sendEsc() }
        binding.btnDel.setOnClickListener { sendDel() }
    }

    private fun setupFunctionKeys() {
        // 加载设置
        loadFunctionKeys()

        // F1-F3 功能键
        binding.btnF1?.setOnClickListener { sendFunctionKey(0) }
        binding.btnF2?.setOnClickListener { sendFunctionKey(1) }
        binding.btnF3?.setOnClickListener { sendFunctionKey(2) }

        // 长按编辑功能键
        binding.btnF1?.setOnLongClickListener { editFunctionKey(0); true }
        binding.btnF2?.setOnLongClickListener { editFunctionKey(1); true }
        binding.btnF3?.setOnLongClickListener { editFunctionKey(2); true }
    }

    private fun setupQuickInput() {
        // 加载设置
        loadQuickInput()

        // 快速输入按钮
        binding.btnQuickInput1?.setOnClickListener { insertQuickInput(0) }
        binding.btnQuickInput2?.setOnClickListener { insertQuickInput(1) }
        binding.btnQuickInput3?.setOnClickListener { insertQuickInput(2) }

        // 长按编辑快速输入
        binding.btnQuickInput1?.setOnLongClickListener { editQuickInput(0); true }
        binding.btnQuickInput2?.setOnLongClickListener { editQuickInput(1); true }
        binding.btnQuickInput3?.setOnLongClickListener { editQuickInput(2); true }

        // 设置按钮文本
        binding.btnQuickInput1?.text = quickInputLabels[0]
        binding.btnQuickInput2?.text = quickInputLabels[1]
        binding.btnQuickInput3?.text = quickInputLabels[2]
    }

    private fun loadFunctionKeys() {
        val modifiers = prefsManager.getFunctionKeyModifiers()
        val chars = prefsManager.getFunctionKeyChars()
        if (modifiers.isNotEmpty()) functionKeyModifiers = modifiers
        if (chars.isNotEmpty()) functionKeyChars = chars
        updateFunctionKeyLabels()
    }

    private fun saveFunctionKeys() {
        prefsManager.saveFunctionKeyModifiers(functionKeyModifiers)
        prefsManager.saveFunctionKeyChars(functionKeyChars)
    }

    private fun loadQuickInput() {
        val labels = prefsManager.getQuickInputLabels()
        val contents = prefsManager.getQuickInputContents()
        if (labels.isNotEmpty()) quickInputLabels = labels
        if (contents.isNotEmpty()) quickInputContents = contents
    }

    private fun saveQuickInput() {
        prefsManager.saveQuickInputLabels(quickInputLabels)
        prefsManager.saveQuickInputContents(quickInputContents)
    }

    private fun updateFunctionKeyLabels() {
        binding.btnF1?.text = getFunctionKeyLabel(0)
        binding.btnF2?.text = getFunctionKeyLabel(1)
        binding.btnF3?.text = getFunctionKeyLabel(2)
    }

    private fun getFunctionKeyLabel(index: Int): String {
        val modifier = functionKeyModifiers.getOrNull(index) ?: ""
        val char = functionKeyChars.getOrNull(index) ?: ""
        return if (modifier.isNotEmpty()) "$modifier+$char" else char.uppercase()
    }

    private fun sendFunctionKey(index: Int) {
        if (!sshManager.isShellReady()) return
        val modifier = functionKeyModifiers.getOrNull(index) ?: ""
        val char = functionKeyChars.getOrNull(index)?.lowercase() ?: return

        val sequence = when {
            modifier == "Ctrl" && char.length == 1 && char[0].isLetter() -> {
                (char[0].code - 'a'.code + 1).toChar().toString()
            }
            modifier == "Alt" && char.length == 1 -> {
                "\u001B$char"
            }
            modifier == "Shift" && char.length == 1 -> {
                char.uppercase()
            }
            else -> char
        }

        lifecycleScope.launch {
            sshManager.writeToShellAsync(sequence)
        }
    }

    private fun editFunctionKey(index: Int) {
        // 第一步：选择修饰键
        val modifiers = arrayOf("无", "Ctrl", "Alt", "Shift")
        val currentModifier = functionKeyModifiers.getOrNull(index) ?: ""
        val modifierIndex = modifiers.indexOf(currentModifier).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("F${index + 1} - 选择修饰键")
            .setSingleChoiceItems(modifiers, modifierIndex) { dialog1, whichModifier ->
                functionKeyModifiers[index] = if (whichModifier == 0) "" else modifiers[whichModifier]
                dialog1.dismiss()

                // 第二步：选择字母或数字
                showCharSelectionDialog(index)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCharSelectionDialog(index: Int) {
        val chars = ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }
        val charArray = chars.toTypedArray()
        val currentChar = functionKeyChars.getOrNull(index) ?: "c"
        val charIndex = charArray.indexOf(currentChar).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("F${index + 1} - 选择按键")
            .setSingleChoiceItems(charArray, charIndex) { dialog2, whichChar ->
                functionKeyChars[index] = charArray[whichChar]
                saveFunctionKeys()
                updateFunctionKeyLabels()
                dialog2.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun insertQuickInput(index: Int) {
        val content = quickInputContents.getOrNull(index) ?: return
        val currentText = binding.etCommand.text.toString()
        binding.etCommand.setText(currentText + content)
        binding.etCommand.setSelection(binding.etCommand.text?.length ?: 0)
    }

    private fun editQuickInput(index: Int) {
        val dialogView = android.view.LayoutInflater.from(requireContext())
            .inflate(android.R.layout.two_line_list_item, null)
        val labelInput = android.widget.EditText(requireContext()).apply {
            hint = "标签名称"
            setText(quickInputLabels[index])
        }
        val contentInput = android.widget.EditText(requireContext()).apply {
            hint = "输入内容"
            setText(quickInputContents[index])
        }
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(labelInput)
            addView(contentInput)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("编辑快速输入 ${index + 1}")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                quickInputLabels[index] = labelInput.text.toString().ifBlank { "快${index + 1}" }
                quickInputContents[index] = contentInput.text.toString()
                saveQuickInput()
                // 更新按钮文本
                when (index) {
                    0 -> binding.btnQuickInput1?.text = quickInputLabels[0]
                    1 -> binding.btnQuickInput2?.text = quickInputLabels[1]
                    2 -> binding.btnQuickInput3?.text = quickInputLabels[2]
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupScrollButtons() {
        binding.btnScrollUp?.setOnClickListener { scrollTerminalUp() }
        binding.btnScrollDown?.setOnClickListener { scrollTerminalDown() }

        // 长按连续滚动
        var scrollUpRepeat: Runnable? = null
        var scrollDownRepeat: Runnable? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        binding.btnScrollUp?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollTerminalUp()
                    scrollUpRepeat = object : Runnable {
                        override fun run() {
                            scrollTerminalUp()
                            handler.postDelayed(this, 100)
                        }
                    }
                    handler.postDelayed(scrollUpRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollUpRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }

        binding.btnScrollDown?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollTerminalDown()
                    scrollDownRepeat = object : Runnable {
                        override fun run() {
                            scrollTerminalDown()
                            handler.postDelayed(this, 100)
                        }
                    }
                    handler.postDelayed(scrollDownRepeat!!, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scrollDownRepeat?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun scrollTerminalUp() {
        activity?.runOnUiThread {
            binding.terminalView.evaluateJavascript("scrollTerminalUp(3)", null)
        }
    }

    private fun scrollTerminalDown() {
        activity?.runOnUiThread {
            binding.terminalView.evaluateJavascript("scrollTerminalDown(3)", null)
        }
    }

    private fun scrollTerminalPageUp() {
        activity?.runOnUiThread {
            binding.terminalView.evaluateJavascript("scrollTerminalPageUp()", null)
        }
    }

    private fun scrollTerminalPageDown() {
        activity?.runOnUiThread {
            binding.terminalView.evaluateJavascript("scrollTerminalPageDown()", null)
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