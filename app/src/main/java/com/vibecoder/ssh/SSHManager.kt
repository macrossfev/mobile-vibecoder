package com.vibecoder.ssh

import com.jcraft.jsch.*
import com.vibecoder.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

/**
 * SSH连接状态
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val session: Session) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * SSH会话管理器
 */
class SSHManager {

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val jsch = JSch()

    /**
     * 建立SSH连接
     */
    suspend fun connect(config: ServerConfig): Result<Session> = withContext(Dispatchers.IO) {
        try {
            // 如果已连接，先断开
            disconnect()

            val newSession = jsch.getSession(config.username, config.host, config.port).apply {
                // 设置认证方式
                when {
                    config.privateKey != null -> {
                        if (config.passphrase != null) {
                            jsch.addIdentity("key", config.privateKey.toByteArray(), null, config.passphrase.toByteArray())
                        } else {
                            jsch.addIdentity("key", config.privateKey.toByteArray(), null, null)
                        }
                    }
                    config.password != null -> {
                        setPassword(config.password)
                    }
                }

                // 连接配置
                Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    put("UserKnownHostsFile", "/dev/null")
                }.let { setConfig(it) }

                timeout = 30000
                connect()
            }

            session = newSession
            Result.success(newSession)
        } catch (e: JSchException) {
            Result.failure(Exception("SSH连接失败: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行单条命令并返回结果
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val currentSession = session ?: return@withContext Result.failure(
                Exception("未连接到服务器")
            )

            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.connect()

            val output = channel.inputStream.bufferedReader().readText()
            val error = channel.errStream.bufferedReader().readText()

            channel.disconnect()

            Result.success(output + error)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 打开交互式Shell
     */
    suspend fun openShell(
        onOutput: (String) -> Unit,
        onConnected: () -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentSession = session ?: return@withContext Result.failure(
                Exception("未连接到服务器")
            )

            channel = (currentSession.openChannel("shell") as ChannelShell).apply {
                setPty(true)
                setPtyType("xterm-256color", 80, 24, 800, 600)
                connect()
            }

            inputStream = channel!!.inputStream
            outputStream = channel!!.outputStream

            onConnected()

            // 读取输出
            val buffer = ByteArray(4096)
            while (channel?.isConnected == true) {
                val read = inputStream?.read(buffer) ?: -1
                if (read > 0) {
                    val output = String(buffer, 0, read, Charsets.UTF_8)
                    onOutput(output)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 向Shell发送命令
     */
    fun writeToShell(command: String) {
        try {
            outputStream?.write("$command\n".toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 发送Ctrl+C
     */
    fun sendCtrlC() {
        try {
            outputStream?.write(byteArrayOf(3))
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 发送Ctrl+D
     */
    fun sendCtrlD() {
        try {
            outputStream?.write(byteArrayOf(4))
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            channel?.disconnect()
            session?.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            channel = null
            session = null
            inputStream = null
            outputStream = null
        }
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean = session?.isConnected == true

    /**
     * 获取服务器状态信息
     */
    suspend fun fetchServerStatus(): Result<ServerStatusInfo> = withContext(Dispatchers.IO) {
        try {
            val commands = mapOf(
                "cpu" to "top -bn1 | grep 'Cpu(s)' | awk '{print \$2}' | cut -d'%' -f1",
                "mem" to "free -m | awk 'NR==2{print \$3\",\" \$2}'",
                "disk" to "df -h / | awk 'NR==2{print \$3\",\" \$2\",\" \$5}'",
                "uptime" to "uptime -p",
                "load" to "cat /proc/loadavg | awk '{print \$1\",\"\$2\",\"\$3}'"
            )

            val results = mutableMapOf<String, String>()
            commands.forEach { (key, cmd) ->
                executeCommand(cmd).getOrNull()?.trim()?.let { results[key] = it }
            }

            val cpu = results["cpu"]?.toFloatOrNull() ?: 0f
            val (memUsed, memTotal) = results["mem"]?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: listOf(0L, 1L)
            val diskParts = results["disk"]?.split(",") ?: listOf("0", "1", "0%")
            val uptime = results["uptime"] ?: ""
            val load = results["load"] ?: ""

            Result.success(
                ServerStatusInfo(
                    cpuUsage = cpu,
                    memoryUsedMB = memUsed,
                    memoryTotalMB = memTotal,
                    diskUsedGB = diskParts.getOrNull(0)?.replace("G", "")?.toFloatOrNull() ?: 0f,
                    diskTotalGB = diskParts.getOrNull(1)?.replace("G", "")?.toFloatOrNull() ?: 1f,
                    diskPercent = diskParts.getOrNull(2)?.replace("%", "")?.toFloatOrNull() ?: 0f,
                    uptime = uptime,
                    loadAverage = load
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * 服务器状态信息
 */
data class ServerStatusInfo(
    val cpuUsage: Float,
    val memoryUsedMB: Long,
    val memoryTotalMB: Long,
    val diskUsedGB: Float,
    val diskTotalGB: Float,
    val diskPercent: Float,
    val uptime: String,
    val loadAverage: String
) {
    val memoryPercent: Float
        get() = if (memoryTotalMB > 0) (memoryUsedMB.toFloat() / memoryTotalMB) * 100 else 0f
}