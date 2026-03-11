package com.vibecoder.voice

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI命令解释器
 * 将自然语言转换为shell命令
 */
class AICommandInterpreter(
    private val apiEndpoint: String,
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 将自然语言转换为命令
     */
    suspend fun interpret(input: String): Result<CommandResult> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = buildSystemPrompt()
            val response = callAI(systemPrompt, input)

            // 解析AI响应
            parseCommandFromResponse(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildSystemPrompt(): String {
        return """
你是一个Linux服务器运维助手。用户会用自然语言描述他们想做的事情，你需要将其转换为准确的shell命令。

规则：
1. 只返回JSON格式：{"command": "实际命令", "explanation": "简短说明", "dangerous": false}
2. dangerous字段：如果命令可能造成数据丢失或系统损坏，设为true
3. 命令要简洁实用，优先使用常见工具
4. 如果无法理解用户意图，返回：{"command": "", "explanation": "无法理解，请描述具体操作", "dangerous": false}

常见场景示例：
- "查看系统状态" → top -bn1 | head -20
- "查看内存使用" → free -h
- "查看磁盘" → df -h
- "重启nginx" → sudo systemctl restart nginx
- "查看端口8080" → netstat -tuln | grep 8080
- "查看最近的错误日志" → tail -100 /var/log/syslog | grep -i error
- "查看docker容器" → docker ps -a
- "查看pm2进程" → pm2 status
- "杀掉占用80端口的进程" → sudo lsof -t -i:80 | xargs sudo kill -9
- "查看CPU占用最高的进程" → ps aux --sort=-%cpu | head -10
- "查看内存占用最高的进程" → ps aux --sort=-%mem | head -10
- "清空日志文件" → truncate -s 0 /var/log/syslog

只返回JSON，不要其他文字。
        """.trimIndent()
    }

    private suspend fun callAI(systemPrompt: String, userMessage: String): String = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "temperature" to 0.3,
            "max_tokens" to 200
        ))

        val request = Request.Builder()
            .url("$apiEndpoint/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("API请求失败: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("响应体为空")
        val apiResponse = gson.fromJson(responseBody, APIResponse::class.java)

        apiResponse.choices.firstOrNull()?.message?.content ?: ""
    }

    private fun parseCommandFromResponse(response: String): Result<CommandResult> {
        return try {
            // 尝试从响应中提取JSON
            val jsonStart = response.indexOf('{')
            val jsonEnd = response.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val json = response.substring(jsonStart, jsonEnd)
                val result = gson.fromJson(json, CommandResult::class.java)
                Result.success(result)
            } else {
                Result.failure(Exception("无法解析AI响应"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("解析失败: ${e.message}"))
        }
    }

    /**
     * 命令解释结果
     */
    data class CommandResult(
        @SerializedName("command") val command: String,
        @SerializedName("explanation") val explanation: String,
        @SerializedName("dangerous") val dangerous: Boolean = false
    )

    /**
     * API响应结构
     */
    private data class APIResponse(
        @SerializedName("choices") val choices: List<Choice>
    )

    private data class Choice(
        @SerializedName("message") val message: Message
    )

    private data class Message(
        @SerializedName("content") val content: String
    )

    companion object {
        // 默认端点，支持OpenAI兼容API
        const val DEFAULT_ENDPOINT = "https://api.openai.com"
    }
}