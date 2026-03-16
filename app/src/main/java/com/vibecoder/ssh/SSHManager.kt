package com.vibecoder.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.vibecoder.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class SSHManager private constructor() {

    private var session: Session? = null
    private var shellChannel: ChannelShell? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    companion object {
        private const val TAG = "SSHManager"

        @Volatile
        private var instance: SSHManager? = null

        fun getInstance(): SSHManager {
            return instance ?: synchronized(this) {
                instance ?: SSHManager().also { instance = it }
            }
        }

        fun getCurrentInstance(): SSHManager? = instance

        // ==================== SSH密钥生成 ====================

        enum class KeyType(val displayName: String, val jschType: Int, val defaultSize: Int) {
            ED25519("Ed25519 (推荐)", KeyPair.ED25519, 256),
            RSA_2048("RSA 2048位", KeyPair.RSA, 2048),
            RSA_4096("RSA 4096位", KeyPair.RSA, 4096)
        }

        data class GeneratedKeyPair(
            val keyType: KeyType,
            val publicKey: String,
            val privateKey: String,
            val fingerprint: String
        )

        fun generateKeyPair(keyType: KeyType, comment: String = "vibecoder"): GeneratedKeyPair {
            val jsch = JSch()
            return try {
                Log.d(TAG, "开始生成 ${keyType.displayName} 密钥")
                val kpair = KeyPair.genKeyPair(jsch, keyType.jschType, keyType.defaultSize)

                val privateKeyOut = ByteArrayOutputStream()
                kpair.writePrivateKey(privateKeyOut)
                val privateKeyStr = privateKeyOut.toString("UTF-8")

                val publicKeyOut = ByteArrayOutputStream()
                kpair.writePublicKey(publicKeyOut, comment)
                val publicKeyStr = publicKeyOut.toString("UTF-8").trim()

                val fingerprint = calculateFingerprint(kpair)
                kpair.dispose()

                Log.d(TAG, "密钥生成成功: ${keyType.displayName}")
                GeneratedKeyPair(keyType, publicKeyStr, privateKeyStr, fingerprint)
            } catch (e: Exception) {
                Log.e(TAG, "生成 ${keyType.displayName} 密钥失败: ${e.message}", e)
                if (keyType == KeyType.ED25519) {
                    Log.w(TAG, "Ed25519 不支持，自动回退到 RSA 2048")
                    try { generateKeyPair(KeyType.RSA_2048, comment) }
                    catch (fallbackError: Exception) { throw Exception("无法生成密钥: ${fallbackError.message}") }
                } else {
                    throw Exception("生成密钥失败: ${e.message}")
                }
            }
        }

        fun getSupportedKeyTypes(): List<KeyType> = KeyType.entries

        fun validatePrivateKey(privateKey: String): Boolean = privateKey.contains("PRIVATE KEY")

        private fun calculateFingerprint(keyPair: KeyPair): String {
            val bos = ByteArrayOutputStream()
            keyPair.writePublicKey(bos, "")
            val hash = MessageDigest.getInstance("SHA256").digest(bos.toByteArray())
            return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(hash)}"
        }
    }

    /**
     * 建立SSH连接
     */
    suspend fun connect(config: ServerConfig): Result<Session> = withContext(Dispatchers.IO) {
        try {
            disconnect()

            val jsch = JSch()

            // 设置密钥认证
            if (!config.privateKey.isNullOrBlank()) {
                if (!config.passphrase.isNullOrBlank()) {
                    jsch.addIdentity("key", config.privateKey.toByteArray(), null, config.passphrase.toByteArray())
                } else {
                    jsch.addIdentity("key", config.privateKey.toByteArray(), null, null)
                }
            }

            session = jsch.getSession(config.username, config.host, config.port).apply {
                if (!config.password.isNullOrBlank()) {
                    setPassword(config.password)
                }

                setConfig("StrictHostKeyChecking", "no")
                setConfig("UserKnownHostsFile", "no")
                // 增强保活设置 - 尽可能长时间保持连接
                setConfig("KeepAlive", "yes")
                setConfig("ServerAliveInterval", "10")   // 每10秒发送心跳
                setConfig("ServerAliveCountMax", "1000") // 1000次无响应才断开（约2.7小时）
                setConfig("TCPKeepAlive", "yes")

                timeout = 60000  // 连接超时60秒
            }

            Log.d(TAG, "Connecting to ${config.host}:${config.port}")

            session?.connect(30000)

            val currentSession = session ?: throw Exception("无法创建会话")
            Result.success(currentSession)
        } catch (e: Exception) {
            Log.e(TAG, "连接失败", e)
            val message = when {
                e.message?.contains("Auth", ignoreCase = true) == true -> "认证失败: 请检查用户名、密码或密钥"
                e.message?.contains("Connection refused", ignoreCase = true) == true -> "连接被拒绝: 请检查主机和端口"
                e.message?.contains("timeout", ignoreCase = true) == true -> "连接超时: 请检查网络"
                else -> "SSH连接失败: ${e.message}"
            }
            Result.failure(Exception(message))
        }
    }

    /**
     * 执行单条命令
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val currentSession = session ?: return@withContext Result.failure(Exception("未连接到服务器"))

            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)

            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()

            channel.outputStream = output
            channel.setExtOutputStream(error)

            channel.connect(30000)

            while (!channel.isClosed) {
                Thread.sleep(100)
            }

            channel.disconnect()

            val result = output.toString(StandardCharsets.UTF_8.name()) + error.toString(StandardCharsets.UTF_8.name())
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败", e)
            Result.failure(e)
        }
    }

    /**
     * 打开交互式Shell
     * @param cols 终端列数
     * @param rows 终端行数
     */
    suspend fun openShell(
        onOutput: (String) -> Unit,
        onConnected: () -> Unit,
        cols: Int = 80,
        rows: Int = 24
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentSession = session ?: return@withContext Result.failure(Exception("未连接到服务器"))

            if (!currentSession.isConnected) {
                return@withContext Result.failure(Exception("Session未连接"))
            }

            val channel = currentSession.openChannel("shell") as ChannelShell
            channel.setPty(true)
            // 使用传入的终端尺寸，像素宽高根据列数和行数计算（假设字体 8x16）
            val pixelWidth = cols * 8
            val pixelHeight = rows * 16
            channel.setPtyType("xterm", cols, rows, pixelWidth, pixelHeight)
            channel.setEnv("TERM", "xterm-256color")
            channel.setEnv("LANG", "en_US.UTF-8")

            val os = channel.outputStream
            val inputStr = channel.inputStream

            channel.connect(30000)

            if (!channel.isConnected) {
                return@withContext Result.failure(Exception("Shell连接失败"))
            }

            shellChannel = channel
            outputStream = os
            inputStream = inputStr

            withContext(Dispatchers.Main) { onConnected() }

            val buffer = ByteArray(8192)
            while (currentSession.isConnected && channel.isConnected) {
                try {
                    val available = inputStr.available()
                    if (available > 0) {
                        val read = inputStr.read(buffer, 0, minOf(available, buffer.size))
                        if (read > 0) {
                            val output = String(buffer, 0, read, StandardCharsets.UTF_8)
                            // 调试：打印 SSH 原始输出
                            val debugOutput = output
                                .replace("\u001B", "\\e")
                                .replace("\r", "\\r")
                                .replace("\n", "\\n")
                            Log.d(TAG, "SSH output ($read bytes): $debugOutput")
                            onOutput(output)
                        }
                    } else {
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    break
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Shell错误", e)
            Result.failure(e)
        }
    }

    /**
     * 向Shell发送命令（在IO线程执行）
     */
    suspend fun writeToShellAsync(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val os = outputStream ?: return@withContext false
            os.write(command.toByteArray(StandardCharsets.UTF_8))
            os.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送命令失败", e)
            false
        }
    }

    /**
     * 发送回车键（在IO线程执行）
     */
    suspend fun sendEnterAsync(): Boolean = withContext(Dispatchers.IO) {
        try {
            val os = outputStream ?: return@withContext false
            // 只发送 \r（回车），PTY 会自动处理换行
            os.write("\r".toByteArray(StandardCharsets.UTF_8))
            os.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送回车失败", e)
            false
        }
    }

    /**
     * 发送Ctrl+C
     */
    suspend fun sendCtrlC() = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(byteArrayOf(3))
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "发送Ctrl+C失败", e)
        }
    }

    /**
     * 发送Ctrl+D
     */
    suspend fun sendCtrlD() = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(byteArrayOf(4))
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "发送Ctrl+D失败", e)
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            shellChannel?.disconnect()
            session?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败", e)
        } finally {
            shellChannel = null
            session = null
            inputStream = null
            outputStream = null
        }
    }

    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        val connected = session?.isConnected == true
        Log.d(TAG, "isConnected check: $connected (session: ${session != null}, isConnected: ${session?.isConnected})")
        return connected
    }

    /**
     * 检查 Shell 是否就绪
     */
    fun isShellReady(): Boolean {
        val ready = outputStream != null && shellChannel?.isConnected == true
        Log.d(TAG, "isShellReady check: $ready (outputStream: ${outputStream != null}, shellChannel: ${shellChannel?.isConnected})")
        return ready
    }

    /**
     * 调整 PTY 大小（横竖屏切换时调用）
     */
    fun resizePty(cols: Int, rows: Int) {
        try {
            shellChannel?.setPtySize(cols, rows, cols * 8, rows * 16)
            Log.d(TAG, "PTY resized to $cols x $rows")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resize PTY", e)
        }
    }

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
            Log.e(TAG, "获取状态失败", e)
            Result.failure(e)
        }
    }
}

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