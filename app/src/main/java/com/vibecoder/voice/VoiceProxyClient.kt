package com.vibecoder.voice

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 语音代理客户端
 * 调用服务端API获取提炼后的语音播报内容
 */
class VoiceProxyClient(
    private var baseUrl: String = "http://192.168.1.100:8765"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * 完整状态响应
     */
    data class StatusResponse(
        @SerializedName("session") val session: String,
        @SerializedName("current_output") val currentOutput: String,
        @SerializedName("state") val state: String,
        @SerializedName("speech_content") val speechContent: String,
        @SerializedName("last_update") val lastUpdate: Double
    )

    /**
     * 语音内容响应
     */
    data class SpeechResponse(
        @SerializedName("content") val content: String,
        @SerializedName("state") val state: String
    )

    /**
     * 原始输出响应
     */
    data class OutputResponse(
        @SerializedName("output") val output: String
    )

    /**
     * 获取完整状态
     */
    suspend fun getStatus(): Result<StatusResponse> = withContext(Dispatchers.IO) {
        safeApiCall("$baseUrl/status") { response ->
            gson.fromJson(response.body?.string(), StatusResponse::class.java)
        }
    }

    /**
     * 获取语音播报内容（已提炼）
     */
    suspend fun getSpeech(): Result<SpeechResponse> = withContext(Dispatchers.IO) {
        safeApiCall("$baseUrl/speech") { response ->
            gson.fromJson(response.body?.string(), SpeechResponse::class.java)
        }
    }

    /**
     * 获取原始输出
     */
    suspend fun getOutput(): Result<OutputResponse> = withContext(Dispatchers.IO) {
        safeApiCall("$baseUrl/output") { response ->
            gson.fromJson(response.body?.string(), OutputResponse::class.java)
        }
    }

    /**
     * 清除语音内容缓存
     */
    suspend fun refresh(): Result<Boolean> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("$baseUrl/refresh")
                .post("".toRequestBody(null))
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: Response) {
                    continuation.resume(Result.success(response.isSuccessful))
                }
            })
        }
    }

    /**
     * 检查服务是否可用
     */
    suspend fun isAvailable(): Boolean {
        return try {
            getStatus().isSuccess
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 通用API调用封装
     */
    private inline fun <T> safeApiCall(
        url: String,
        crossinline transform: (Response) -> T
    ): Result<T> {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val data = transform(response)
                Result.success(data)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 更新服务器地址
     */
    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl
    }

    companion object {
        private const val TAG = "VoiceProxyClient"

        // Claude状态常量
        const val STATE_IDLE = "idle"
        const val STATE_WORKING = "working"
        const val STATE_COMPLETED = "completed"
    }
}