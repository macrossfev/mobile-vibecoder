package com.vibecoder.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 数据存储管理
 */
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // 服务器列表
    fun getServers(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveServers(servers: List<ServerConfig>) {
        prefs.edit().putString(KEY_SERVERS, gson.toJson(servers)).apply()
    }

    fun addServer(server: ServerConfig): Long {
        val servers = getServers().toMutableList()
        val newId = (servers.maxOfOrNull { it.id } ?: 0) + 1
        servers.add(server.copy(id = newId))
        saveServers(servers)
        return newId
    }

    fun updateServer(server: ServerConfig) {
        val servers = getServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
            saveServers(servers)
        }
    }

    fun deleteServer(serverId: Long) {
        val servers = getServers().filter { it.id != serverId }
        saveServers(servers)
    }

    // 快捷命令
    fun getQuickCommands(): List<QuickCommand> {
        val json = prefs.getString(KEY_QUICK_COMMANDS, null) ?: return getDefaultQuickCommands()
        val type = object : TypeToken<List<QuickCommand>>() {}.type
        return gson.fromJson(json, type)
    }

    fun saveQuickCommands(commands: List<QuickCommand>) {
        prefs.edit().putString(KEY_QUICK_COMMANDS, gson.toJson(commands)).apply()
    }

    // 命令历史
    fun getCommandHistory(serverId: Long): List<CommandHistory> {
        val json = prefs.getString("${KEY_HISTORY}_$serverId", null) ?: return emptyList()
        val type = object : TypeToken<List<CommandHistory>>() {}.type
        return gson.fromJson(json, type)
    }

    fun addCommandToHistory(history: CommandHistory) {
        val historyList = getCommandHistory(history.serverId).toMutableList()
        // 去重：移除相同命令的旧记录
        historyList.removeAll { it.command == history.command }
        historyList.add(0, history)
        // 只保留最近100条
        val trimmed = historyList.take(100)
        prefs.edit().putString("${KEY_HISTORY}_${history.serverId}", gson.toJson(trimmed)).apply()
    }

    // 设置
    fun getFontSize(): Int = prefs.getInt(KEY_FONT_SIZE, 14)
    fun setFontSize(size: Int) = prefs.edit().putInt(KEY_FONT_SIZE, size).apply()

    fun getVoiceProvider(): String = prefs.getString(KEY_VOICE_PROVIDER, "system") ?: "system"
    fun setVoiceProvider(provider: String) = prefs.edit().putString(KEY_VOICE_PROVIDER, provider).apply()

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)
    fun setApiKey(key: String?) = prefs.edit().putString(KEY_API_KEY, key).apply()

    fun getApiEndpoint(): String? = prefs.getString(KEY_API_ENDPOINT, null)
    fun setApiEndpoint(endpoint: String?) = prefs.edit().putString(KEY_API_ENDPOINT, endpoint).apply()

    fun isKeepScreenOn(): Boolean = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
    fun setKeepScreenOn(on: Boolean) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()

    // 自定义快捷键
    fun getCustomKeyLabels(): Array<String> {
        val json = prefs.getString(KEY_CUSTOM_LABELS, null) ?: return arrayOf("F1", "F2", "F3")
        val type = object : TypeToken<List<String>>() {}.type
        val list: List<String> = gson.fromJson(json, type)
        return list.toTypedArray()
    }

    fun saveCustomKeyLabels(labels: Array<String>) {
        prefs.edit().putString(KEY_CUSTOM_LABELS, gson.toJson(labels.toList())).apply()
    }

    fun getCustomKeyCommands(): Array<String> {
        val json = prefs.getString(KEY_CUSTOM_COMMANDS, null) ?: return arrayOf("", "", "")
        val type = object : TypeToken<List<String>>() {}.type
        val list: List<String> = gson.fromJson(json, type)
        return list.toTypedArray()
    }

    fun saveCustomKeyCommands(commands: Array<String>) {
        prefs.edit().putString(KEY_CUSTOM_COMMANDS, gson.toJson(commands.toList())).apply()
    }

    private fun getDefaultQuickCommands(): List<QuickCommand> {
        return listOf(
            QuickCommand(1, "查看系统状态", "top -bn1 | head -20"),
            QuickCommand(2, "磁盘使用", "df -h"),
            QuickCommand(3, "内存状态", "free -h"),
            QuickCommand(4, "网络连接", "netstat -tuln"),
            QuickCommand(5, "查看日志", "tail -100 /var/log/syslog"),
            QuickCommand(6, "Docker状态", "docker ps -a"),
            QuickCommand(7, "PM2状态", "pm2 status"),
            QuickCommand(8, "Nginx日志", "tail -100 /var/log/nginx/error.log")
        )
    }

    // 虚拟键盘设置
    fun isVirtualKeyboardEnabled(): Boolean = prefs.getBoolean(KEY_VIRTUAL_KEYBOARD, false)
    fun setVirtualKeyboardEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_VIRTUAL_KEYBOARD, enabled).apply()

    // 快速输入设置
    fun getQuickInputLabels(): Array<String> {
        val json = prefs.getString(KEY_QUICK_INPUT_LABELS, null) ?: return arrayOf("快1", "快2", "快3")
        val type = object : TypeToken<List<String>>() {}.type
        val list: List<String> = gson.fromJson(json, type)
        return list.toTypedArray()
    }

    fun saveQuickInputLabels(labels: Array<String>) {
        prefs.edit().putString(KEY_QUICK_INPUT_LABELS, gson.toJson(labels.toList())).apply()
    }

    fun getQuickInputContents(): Array<String> {
        val json = prefs.getString(KEY_QUICK_INPUT_CONTENTS, null)
            ?: return arrayOf("tmux attach -t ", "kubectl get pods", "htop")
        val type = object : TypeToken<List<String>>() {}.type
        val list: List<String> = gson.fromJson(json, type)
        return list.toTypedArray()
    }

    fun saveQuickInputContents(contents: Array<String>) {
        prefs.edit().putString(KEY_QUICK_INPUT_CONTENTS, gson.toJson(contents.toList())).apply()
    }

    // 语音输出开关
    fun isVoiceOutputEnabled(): Boolean = prefs.getBoolean(KEY_VOICE_OUTPUT, false)
    fun setVoiceOutputEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_VOICE_OUTPUT, enabled).apply()

    // 快捷命令 (单个自定义命令)
    fun getQuickCommand(): String? = prefs.getString(KEY_QUICK_COMMAND, null)
    fun saveQuickCommand(command: String) = prefs.edit().putString(KEY_QUICK_COMMAND, command).apply()

    // 自定义快捷键 (单个)
    fun getCustomShortcut(): String? = prefs.getString(KEY_CUSTOM_SHORTCUT, null)
    fun saveCustomShortcut(shortcut: String) = prefs.edit().putString(KEY_CUSTOM_SHORTCUT, shortcut).apply()

    companion object {
        private const val PREFS_NAME = "vibecoder_prefs"
        private const val KEY_SERVERS = "servers"
        private const val KEY_QUICK_COMMANDS = "quick_commands"
        private const val KEY_HISTORY = "command_history"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_VOICE_PROVIDER = "voice_provider"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_ENDPOINT = "api_endpoint"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CUSTOM_LABELS = "custom_key_labels"
        private const val KEY_CUSTOM_COMMANDS = "custom_key_commands"
        private const val KEY_VIRTUAL_KEYBOARD = "virtual_keyboard"
        private const val KEY_QUICK_INPUT_LABELS = "quick_input_labels"
        private const val KEY_QUICK_INPUT_CONTENTS = "quick_input_contents"
        private const val KEY_VOICE_OUTPUT = "voice_output"
        private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
        private const val KEY_CUSTOM_SHORTCUT = "custom_shortcut"
        private const val KEY_QUICK_COMMAND = "quick_command"
    }
}