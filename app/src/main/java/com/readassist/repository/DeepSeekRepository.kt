package com.readassist.repository

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.readassist.model.AiPlatform
import com.readassist.network.ApiResult
import com.readassist.network.DeepSeekContentPart
import com.readassist.network.DeepSeekImageUrl
import com.readassist.network.DeepSeekMessage
import com.readassist.network.DeepSeekRequest
import com.readassist.network.DeepSeekResponse
import com.readassist.network.NetworkModule
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class DeepSeekRepository(private val preferenceManager: PreferenceManager) {

    private val apiService = NetworkModule.deepSeekApiService
    private val requestCache = mutableMapOf<String, CachedResponse>()

    companion object {
        private const val TAG = "DeepSeekRepository"
        private const val MAX_TEXT_LENGTH = 2000
        private const val MAX_RETRY_COUNT = 3
        private const val CACHE_DURATION_MS = 3600_000L
        private const val MAX_CACHE_SIZE = 5
        private const val SYSTEM_PROMPT = "你是一个专业的阅读助手，请用用户指定的语言简明准确地回答问题。"
    }

    suspend fun sendMessage(
        userText: String,
        context: List<ChatContext> = emptyList(),
        modelId: String
    ): ApiResult<String> {
        if (userText.isBlank()) {
            return ApiResult.Error(IllegalArgumentException("文本内容不能为空"))
        }
        if (userText.length > MAX_TEXT_LENGTH) {
            return ApiResult.Error(IllegalArgumentException("文本过长，请选择较短内容"))
        }

        val apiKey = preferenceManager.getApiKey(AiPlatform.DEEPSEEK)
        if (apiKey.isNullOrBlank()) {
            return ApiResult.Error(IllegalArgumentException("DeepSeek API Key 未设置"))
        }

        val messages = mutableListOf(DeepSeekMessage("system", SYSTEM_PROMPT))
        context.takeLast(10).forEach { item ->
            messages.add(DeepSeekMessage("user", item.userMessage))
            messages.add(DeepSeekMessage("assistant", item.aiResponse))
        }
        val prompt = preferenceManager.getPromptTemplate().replace("[TEXT]", userText)
        messages.add(DeepSeekMessage("user", prompt))

        val request = DeepSeekRequest(model = modelId, messages = messages)
        val cacheKey = hash("text|$modelId|$prompt|${context.joinToString { it.userMessage + it.aiResponse }}")
        return executeRequest(apiKey, request, cacheKey)
    }

    suspend fun sendImage(
        bitmap: Bitmap,
        prompt: String,
        context: List<ChatContext> = emptyList(),
        modelId: String
    ): ApiResult<String> {
        if (bitmap.isRecycled) {
            return ApiResult.Error(IllegalArgumentException("图片已被回收，请重新截屏"))
        }

        val apiKey = preferenceManager.getApiKey(AiPlatform.DEEPSEEK)
        if (apiKey.isNullOrBlank()) {
            return ApiResult.Error(IllegalArgumentException("DeepSeek API Key 未设置"))
        }

        val base64Image = try {
            bitmapToBase64(bitmap)
        } catch (e: Exception) {
            return ApiResult.Error(Exception("图片处理失败：${e.message}"))
        }

        val messages = mutableListOf(DeepSeekMessage("system", SYSTEM_PROMPT))
        context.takeLast(3).forEach { item ->
            messages.add(DeepSeekMessage("user", item.userMessage))
            messages.add(DeepSeekMessage("assistant", item.aiResponse))
        }

        val promptTemplate = preferenceManager.getPromptTemplate()
        val basePrompt = if (prompt.isBlank() || prompt.contains("请分析") || prompt.contains("Please analyze")) {
            promptTemplate.replace("[TEXT]", "图片中的文字内容")
        } else {
            promptTemplate.replace("[TEXT]", prompt)
        }
        val finalPrompt = basePrompt + """

如果发给你的信息里有图片，请按以下要求分析截图：
1. 如果截图里有高亮、画圈、下划线或手写文字，请优先输出相关部分的文字并解读；如果都没有，则概括核心观点并给出洞见。
2. 不要解读阅读界面的功能按钮。
3. 不要大段抄录原文。
4. 回答简明，不超过500字。
""".trimEnd()

        val imageContent = listOf(
            DeepSeekContentPart(type = "text", text = finalPrompt),
            DeepSeekContentPart(
                type = "image_url",
                image_url = DeepSeekImageUrl("data:image/jpeg;base64,$base64Image")
            )
        )
        messages.add(DeepSeekMessage("user", imageContent))

        val request = DeepSeekRequest(
            model = modelId,
            messages = messages,
            temperature = 0.1f,
            max_tokens = 2048
        )
        val cacheKey = hash("image|$modelId|$finalPrompt|${hash(base64Image)}")
        return executeRequest(apiKey, request, cacheKey)
    }

    private suspend fun executeRequest(
        apiKey: String,
        request: DeepSeekRequest,
        cacheKey: String
    ): ApiResult<String> {
        getCached(cacheKey)?.let { return ApiResult.Success(it) }

        var lastException: Exception? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                val response = apiService.chatCompletions("Bearer $apiKey", request)
                if (response.isSuccessful) {
                    val body: DeepSeekResponse? = response.body()
                    body?.error?.let { return ApiResult.Error(Exception("DeepSeek API错误：${it.message}")) }
                    val content = body?.choices?.firstOrNull()?.message?.content
                    if (content.isNullOrBlank()) {
                        return ApiResult.Error(Exception("DeepSeek 返回空内容"))
                    }
                    putCached(cacheKey, content)
                    return ApiResult.Success(content)
                }

                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "DeepSeek request failed: ${response.code()} $errorBody")
                when (response.code()) {
                    400 -> return ApiResult.Error(Exception("请求格式错误，请确认所选模型支持当前输入"))
                    401 -> return ApiResult.Error(Exception("DeepSeek API Key 无效，请检查设置"))
                    402 -> return ApiResult.Error(Exception("DeepSeek 账户余额不足"))
                    429 -> {
                        lastException = Exception("DeepSeek 请求频率过高")
                        if (attempt < MAX_RETRY_COUNT - 1) delay(1000L * (attempt + 1))
                    }
                    500, 502, 503 -> {
                        lastException = Exception("DeepSeek 服务暂时不可用")
                        if (attempt < MAX_RETRY_COUNT - 1) delay(1000L * (attempt + 1))
                    }
                    else -> return ApiResult.NetworkError("DeepSeek 网络请求失败（${response.code()}）")
                }
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek request exception", e)
                lastException = e
                if (attempt < MAX_RETRY_COUNT - 1) delay(1000L * (attempt + 1))
            }
        }

        return ApiResult.Error(lastException ?: Exception("DeepSeek 请求失败"))
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        return ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)) {
                throw IllegalStateException("图片压缩失败")
            }
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun hash(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun getCached(key: String): String? {
        val cached = requestCache[key] ?: return null
        if (System.currentTimeMillis() - cached.timestamp < CACHE_DURATION_MS) {
            return cached.response
        }
        requestCache.remove(key)
        return null
    }

    private fun putCached(key: String, response: String) {
        if (requestCache.size >= MAX_CACHE_SIZE) {
            requestCache.minByOrNull { it.value.timestamp }?.key?.let(requestCache::remove)
        }
        requestCache[key] = CachedResponse(response, System.currentTimeMillis())
    }

    private data class CachedResponse(val response: String, val timestamp: Long)
}
