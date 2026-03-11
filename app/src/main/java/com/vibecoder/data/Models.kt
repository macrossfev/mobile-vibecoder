package com.vibecoder.data

import java.io.Serializable

/**
 * SSH服务器配置
 */
data class ServerConfig(
    val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val lastConnected: Long = 0,
    val isFavorite: Boolean = false,
    val color: String = "#4CAF50"
) : Serializable {

    fun getDisplayAddress(): String {
        return if (port == 22) host else "$host:$port"
    }
}

/**
 * 服务器运行状态
 */
data class ServerStatus(
    val serverId: Long,
    val cpuUsage: Float = 0f,
    val memoryUsage: Float = 0f,
    val memoryTotal: Long = 0,
    val memoryUsed: Long = 0,
    val diskUsage: Float = 0f,
    val diskTotal: Long = 0,
    val diskUsed: Long = 0,
    val uptime: String = "",
    val loadAverage: String = "",
    val networkIn: Long = 0,
    val networkOut: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isHealthy: Boolean
        get() = cpuUsage < 90 && memoryUsage < 90 && diskUsage < 90
}

/**
 * 进程信息
 */
data class ProcessInfo(
    val pid: Int,
    val user: String,
    val cpu: Float,
    val memory: Float,
    val command: String
)

/**
 * 快捷命令
 */
data class QuickCommand(
    val id: Long = 0,
    val name: String,
    val command: String,
    val serverId: Long? = null, // null表示全局命令
    val order: Int = 0
)

/**
 * 执行历史记录
 */
data class CommandHistory(
    val id: Long = 0,
    val command: String,
    val serverId: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val exitCode: Int? = null
)