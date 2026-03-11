package com.vibecoder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
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

/**
 * 服务器监控Fragment
 */
class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val args: MonitorFragmentArgs by navArgs()
    private lateinit var server: ServerConfig

    private val sshManager = SSHManager()
    private var monitorJob: Job? = null

    // 历史数据用于图表
    private val cpuHistory = mutableListOf<Float>()
    private val memoryHistory = mutableListOf<Float>()
    private val maxHistorySize = 60

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        server = args.server
        binding.tvServerName.text = server.name
        binding.tvServerAddress.text = server.getDisplayAddress()

        setupQuickCommands()
        connectAndMonitor()
    }

    private fun setupQuickCommands() {
        binding.btnRefresh.setOnClickListener {
            fetchStatus()
        }

        binding.btnTerminal.setOnClickListener {
            // 导航到终端
            val action = MonitorFragmentDirections.actionMonitorToTerminal(server)
            // findNavController().navigate(action)
        }

        // 快捷命令按钮
        binding.btnCpuInfo.setOnClickListener {
            executeQuickCommand("ps aux --sort=-%cpu | head -10")
        }

        binding.btnMemInfo.setOnClickListener {
            executeQuickCommand("ps aux --sort=-%mem | head -10")
        }

        binding.btnDiskInfo.setOnClickListener {
            executeQuickCommand("df -h")
        }

        binding.btnNetworkInfo.setOnClickListener {
            executeQuickCommand("netstat -tuln")
        }

        binding.btnProcesses.setOnClickListener {
            executeQuickCommand("ps aux | head -20")
        }

        binding.btnUptime.setOnClickListener {
            executeQuickCommand("uptime")
        }
    }

    private fun connectAndMonitor() {
        lifecycleScope.launch {
            updateConnectionStatus("正在连接...")

            val result = sshManager.connect(server)

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
                delay(5000) // 每5秒刷新
            }
        }
    }

    private suspend fun fetchStatus() {
        val result = sshManager.fetchServerStatus()

        result.fold(
            onSuccess = { status ->
                updateUI(status)
            },
            onFailure = { error ->
                requireActivity().runOnUiThread {
                    updateConnectionStatus("获取状态失败: ${error.message}")
                }
            }
        )
    }

    private fun updateUI(status: ServerStatusInfo) {
        requireActivity().runOnUiThread {
            // 更新时间
            binding.tvLastUpdate.text = "更新时间: ${
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            }"

            // CPU
            binding.progressCpu.progress = status.cpuUsage.toInt()
            binding.tvCpuUsage.text = "CPU: %.1f%%".format(status.cpuUsage)
            updateCpuHistory(status.cpuUsage)

            // 内存
            val memPercent = status.memoryPercent
            binding.progressMemory.progress = memPercent.toInt()
            binding.tvMemoryUsage.text = "内存: %.1f%% (%d/%d MB)".format(
                memPercent, status.memoryUsedMB, status.memoryTotalMB
            )
            updateMemoryHistory(memPercent)

            // 磁盘
            binding.progressDisk.progress = status.diskPercent.toInt()
            binding.tvDiskUsage.text = "磁盘: %.1f%% (%.1f/%.1f GB)".format(
                status.diskPercent, status.diskUsedGB, status.diskTotalGB
            )

            // 运行时间和负载
            binding.tvUptime.text = "运行时间: ${status.uptime}"
            binding.tvLoadAverage.text = "负载: ${status.loadAverage}"

            // 健康状态
            val isHealthy = status.isHealthy
            binding.tvHealthStatus.text = if (isHealthy) "健康" else "警告"
            binding.tvHealthStatus.setTextColor(
                if (isHealthy) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt()
            )

            // 更新历史图表
            updateChart()
        }
    }

    private fun updateCpuHistory(value: Float) {
        cpuHistory.add(value)
        if (cpuHistory.size > maxHistorySize) {
            cpuHistory.removeAt(0)
        }
    }

    private fun updateMemoryHistory(value: Float) {
        memoryHistory.add(value)
        if (memoryHistory.size > maxHistorySize) {
            memoryHistory.removeAt(0)
        }
    }

    private fun updateChart() {
        // 简单的文本图表展示
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
        requireActivity().runOnUiThread {
            binding.tvConnectionStatus.text = status
        }
    }

    private fun executeQuickCommand(command: String) {
        lifecycleScope.launch {
            val result = sshManager.executeCommand(command)

            result.fold(
                onSuccess = { output ->
                    showCommandOutput(command, output)
                },
                onFailure = { error ->
                    showCommandOutput(command, "错误: ${error.message}")
                }
            )
        }
    }

    private fun showCommandOutput(command: String, output: String) {
        requireActivity().runOnUiThread {
            binding.tvCommandOutput.text = "$ $command\n$output"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        monitorJob?.cancel()
        sshManager.disconnect()
        _binding = null
    }
}