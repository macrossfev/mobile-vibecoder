package com.vibecoder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.vibecoder.data.ServerConfig
import com.vibecoder.databinding.FragmentMonitorBinding
import com.vibecoder.ssh.SSHManager
import com.vibecoder.ssh.ServerStatusInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private var server: ServerConfig? = null
    private val sshManager = SSHManager()
    private var monitorJob: Job? = null

    private val cpuHistory = mutableListOf<Float>()
    private val memoryHistory = mutableListOf<Float>()
    private val maxHistorySize = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 使用Gson解析JSON字符串
        val serverJson = arguments?.getString(ARG_SERVER_JSON)
        if (!serverJson.isNullOrBlank()) {
            server = Gson().fromJson(serverJson, ServerConfig::class.java)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentServer = server
        if (currentServer == null) {
            updateConnectionStatus("服务器配置无效")
            return
        }

        binding.tvServerName.text = currentServer.name
        binding.tvServerAddress.text = currentServer.getDisplayAddress()

        setupQuickCommands()
        connectAndMonitor(currentServer)
    }

    private fun setupQuickCommands() {
        binding.btnRefresh.setOnClickListener { lifecycleScope.launch { fetchStatus() } }
        binding.btnTerminal.setOnClickListener { openTerminal() }

        binding.btnCpuInfo.setOnClickListener { executeQuickCommand("ps aux --sort=-%cpu | head -10") }
        binding.btnMemInfo.setOnClickListener { executeQuickCommand("ps aux --sort=-%mem | head -10") }
        binding.btnDiskInfo.setOnClickListener { executeQuickCommand("df -h") }
        binding.btnNetworkInfo.setOnClickListener { executeQuickCommand("netstat -tuln") }
        binding.btnProcesses.setOnClickListener { executeQuickCommand("ps aux | head -20") }
        binding.btnUptime.setOnClickListener { executeQuickCommand("uptime") }
    }

    private fun openTerminal() {
        val currentServer = server ?: return
        val fragment = TerminalFragment.newInstance(currentServer)
        parentFragmentManager.beginTransaction()
            .replace(com.vibecoder.R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun connectAndMonitor(serverConfig: ServerConfig) {
        lifecycleScope.launch {
            updateConnectionStatus("正在连接...")

            val result = sshManager.connect(serverConfig)

            result.fold(
                onSuccess = {
                    updateConnectionStatus("已连接")
                    startMonitoring()
                },
                onFailure = { error ->
                    updateConnectionStatus("连接失败: ${error.message}")
                }
            )
        }
    }

    private fun startMonitoring() {
        monitorJob = lifecycleScope.launch {
            while (true) {
                fetchStatus()
                delay(5000)
            }
        }
    }

    private suspend fun fetchStatus() {
        val result = sshManager.fetchServerStatus()

        result.fold(
            onSuccess = { status -> updateUI(status) },
            onFailure = { error ->
                activity?.runOnUiThread {
                    updateConnectionStatus("获取状态失败: ${error.message}")
                }
            }
        )
    }

    private fun updateUI(status: ServerStatusInfo) {
        activity?.runOnUiThread {
            if (_binding == null) return@runOnUiThread
            binding.tvLastUpdate.text = "更新时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"

            binding.progressCpu.progress = status.cpuUsage.toInt()
            binding.tvCpuUsage.text = "CPU: %.1f%%".format(status.cpuUsage)
            updateCpuHistory(status.cpuUsage)

            val memPercent = status.memoryPercent
            binding.progressMemory.progress = memPercent.toInt()
            binding.tvMemoryUsage.text = "内存: %.1f%% (%d/%d MB)".format(memPercent, status.memoryUsedMB, status.memoryTotalMB)
            updateMemoryHistory(memPercent)

            binding.progressDisk.progress = status.diskPercent.toInt()
            binding.tvDiskUsage.text = "磁盘: %.1f%% (%.1f/%.1f GB)".format(status.diskPercent, status.diskUsedGB, status.diskTotalGB)

            binding.tvUptime.text = "运行时间: ${status.uptime}"
            binding.tvLoadAverage.text = "负载: ${status.loadAverage}"

            val isHealthy = status.cpuUsage < 90 && memPercent < 90 && status.diskPercent < 90
            binding.tvHealthStatus.text = if (isHealthy) "健康" else "警告"
            binding.tvHealthStatus.setTextColor(if (isHealthy) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())

            updateChart()
        }
    }

    private fun updateCpuHistory(value: Float) {
        cpuHistory.add(value)
        if (cpuHistory.size > maxHistorySize) cpuHistory.removeAt(0)
    }

    private fun updateMemoryHistory(value: Float) {
        memoryHistory.add(value)
        if (memoryHistory.size > maxHistorySize) memoryHistory.removeAt(0)
    }

    private fun updateChart() {
        if (_binding == null) return
        val chartBuilder = StringBuilder()

        if (cpuHistory.isNotEmpty()) {
            val lastCpu = cpuHistory.last()
            val lastMem = memoryHistory.lastOrNull() ?: 0f

            chartBuilder.append("CPU: ")
            chartBuilder.append(createBar(lastCpu))
            chartBuilder.append(" %.0f%%\n".format(lastCpu))

            chartBuilder.append("MEM: ")
            chartBuilder.append(createBar(lastMem))
            chartBuilder.append(" %.0f%%".format(lastMem))
        }

        binding.tvChart.text = chartBuilder.toString()
    }

    private fun createBar(percent: Float): String {
        val bars = (percent / 5).toInt().coerceIn(0, 20)
        return "█".repeat(bars) + "░".repeat(20 - bars)
    }

    private fun updateConnectionStatus(status: String) {
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.tvConnectionStatus.text = status
            }
        }
    }

    private fun executeQuickCommand(command: String) {
        lifecycleScope.launch {
            val result = sshManager.executeCommand(command)

            result.fold(
                onSuccess = { output -> showCommandOutput(command, output) },
                onFailure = { error -> showCommandOutput(command, "错误: ${error.message}") }
            )
        }
    }

    private fun showCommandOutput(command: String, output: String) {
        activity?.runOnUiThread {
            if (_binding != null) {
                binding.tvCommandOutput.text = "$ $command\n$output"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        monitorJob?.cancel()
        sshManager.disconnect()
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_JSON = "server_json"

        fun newInstance(server: ServerConfig) = MonitorFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SERVER_JSON, Gson().toJson(server))
            }
        }
    }
}