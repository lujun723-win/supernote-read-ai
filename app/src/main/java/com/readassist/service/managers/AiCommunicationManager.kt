package com.readassist.service.managers

import android.graphics.Bitmap
import android.util.Log
import com.readassist.model.AiModel
import com.readassist.model.AiPlatform
import com.readassist.network.ApiResult
import com.readassist.repository.ChatRepository
import com.readassist.repository.AiServiceManager
import com.readassist.repository.DeepSeekRepository
import com.readassist.repository.GeminiRepository
import com.readassist.repository.SiliconFlowRepository
import com.readassist.service.ChatItem
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/**
 * 管理与AI服务的通信
 */
class AiCommunicationManager(
    private val chatRepository: ChatRepository,
    private val geminiRepository: GeminiRepository,
    private val preferenceManager: PreferenceManager
) {
    companion object {
        private const val TAG = "AiCommunicationManager"
    }

    private val aiServiceManager = AiServiceManager(
        preferenceManager = preferenceManager,
        geminiRepository = geminiRepository,
        siliconFlowRepository = SiliconFlowRepository(preferenceManager),
        deepSeekRepository = DeepSeekRepository(preferenceManager)
    )

    /**
     * 发送文本消息到AI
     */
    suspend fun sendTextMessage(
        sessionId: String,
        message: String,
        appPackage: String,
        bookName: String
    ): ApiResult<String> {
        return try {
            Log.d(TAG, "发送文本消息 - 会话ID: $sessionId, 应用: $appPackage, 书籍: $bookName")
            Log.d(TAG, "消息内容: ${message.take(100)}...")

            val result = aiServiceManager.sendMessage(
                userText = message,
                context = getRecentChatContext(sessionId)
            )

            when (result) {
                is ApiResult.Success -> {
                    Log.d(TAG, "文本消息发送成功")
                    ApiResult.Success(result.data)
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "文本消息发送失败", result.exception)
                    ApiResult.Error<String>(result.exception)
                }
                is ApiResult.NetworkError -> {
                    Log.e(TAG, "发送文本消息网络错误: ${result.message}")
                    ApiResult.NetworkError<String>(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送文本消息异常", e)
            ApiResult.Error(e)
        }
    }

    /**
     * 发送图片消息到AI
     */
    suspend fun sendImageMessage(
        sessionId: String,
        message: String,
        bitmap: Bitmap,
        appPackage: String,
        bookName: String
    ): ApiResult<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.e(TAG, "🚀🚀🚀 AI通信管理器: sendImageMessage 收到请求 🚀🚀🚀")
                Log.e(TAG, "🖼️ 最终验证 - 图片尺寸: ${bitmap.width}x${bitmap.height}")
                Log.e(TAG, "🖼️ 最终验证 - 图片HashCode: ${bitmap.hashCode()}")
                Log.e(TAG, "🖼️ 最终验证 - 图片是否已回收: ${bitmap.isRecycled}")
                Log.e(TAG, "🪜 调用栈: ${Throwable().stackTraceToString()}")

                Log.d(TAG, "=== 开始发送图片消息 ===")
                Log.d(TAG, "发送图片消息 - 会话ID: $sessionId, 应用: $appPackage, 书籍: $bookName")
                Log.d(TAG, "消息内容: ${message.take(100)}...")

                // 首先检查传入的bitmap是否有效
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ 传入的图片已被回收，无法使用")
                    return@withContext ApiResult.Error<String>(Exception("图片已被回收，请重新截屏"))
                }

                Log.d(TAG, "✅ 传入的图片有效，尺寸: ${bitmap.width}x${bitmap.height}")
                Log.d(TAG, "图片内存占用: ${bitmap.byteCount} bytes")
                Log.d(TAG, "图片配置: ${bitmap.config}")

                // 重要：不创建副本，直接使用传入的图片，避免额外的内存消耗
                // 注意：调用方应负责最终回收图片

                // 检查API Key
                val apiKey = preferenceManager.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "API Key未设置")
                    return@withContext ApiResult.Error<String>(Exception("API Key未设置"))
                }

                // 检查当前模型是否支持视觉
                val currentModel = preferenceManager.getCurrentAiModel()
                if (currentModel?.supportsVision != true) {
                    Log.e(TAG, "当前模型不支持图像分析: ${currentModel?.displayName}")
                    return@withContext ApiResult.Error<String>(Exception("当前模型(${currentModel?.displayName})不支持图像分析"))
                }

                // 获取聊天上下文
                val chatContext = getRecentChatContext(sessionId)

                // 再次检查图片是否仍然有效
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ 发送前图片已被回收")
                    return@withContext ApiResult.Error<String>(Exception("图片处理过程中被回收，请重新截屏"))
                }

                // 根据当前平台发送图片分析
                Log.d(TAG, "🚀 开始发送图片到AI分析...")
                val result = aiServiceManager.sendImage(
                    bitmap = bitmap,  // 直接使用传入的图片
                    prompt = message,
                    context = chatContext
                )

                Log.d(TAG, "✅ AI分析完成，处理结果")

                // 注意：不在这里回收图片，由调用方负责回收

                when (result) {
                    is ApiResult.Success -> {
                        // 保存消息到数据库
                        val aiResponse = result.data
                        if (aiResponse.isNotBlank()) {
                            try {
                                saveMessageToDatabase(sessionId, message, aiResponse, appPackage, bookName)
                                Log.d(TAG, "✅ 图片消息发送成功，已保存到数据库")
                            } catch (e: Exception) {
                                Log.e(TAG, "保存消息到数据库失败", e)
                            }
                        } else {
                            Log.w(TAG, "⚠️ AI返回空白内容")
                        }
                        Log.d(TAG, "=== 图片消息发送完成 ===")
                        result
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "❌ 图片消息发送失败", result.exception)
                        Log.d(TAG, "=== 图片消息发送失败 ===")
                        result
                    }
                    is ApiResult.NetworkError -> {
                        Log.e(TAG, "❌ 发送图片消息网络错误: ${result.message}")
                        Log.d(TAG, "=== 图片消息发送失败(网络) ===")
                        result
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 发送图片消息异常", e)
                Log.d(TAG, "=== 图片消息发送异常 ===")
                ApiResult.Error(e)
            }
        }
    }

    /**
     * 保存消息到数据库
     */
    private suspend fun saveMessageToDatabase(
        sessionId: String,
        userMessage: String,
        aiResponse: String,
        appPackage: String,
        bookName: String
    ) {
        try {
            Log.d(TAG, "保存消息 - 会话ID: $sessionId, 应用: $appPackage, 书籍: $bookName")

            // 直接使用ChatRepository的API保存消息，但先使用ChatEntity构建完整的消息对象
            val chatEntity = com.readassist.database.ChatEntity(
                sessionId = sessionId,
                bookName = bookName,
                appPackage = appPackage,
                userMessage = userMessage,
                aiResponse = aiResponse,
                promptTemplate = preferenceManager.getPromptTemplate(),
                timestamp = System.currentTimeMillis()
            )

            // 使用数据访问对象直接保存实体
            val messageId = chatRepository.saveChatEntity(chatEntity)

            if (messageId > 0) {
                Log.d(TAG, "消息保存成功: ${chatEntity.copy(id = messageId)}")

                // 确保会话记录也被更新
                chatRepository.updateSession(sessionId, bookName, appPackage)
            } else {
                Log.e(TAG, "消息保存失败，返回的ID无效: $messageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存消息异常", e)
        }
    }

    /**
     * 获取最近的聊天上下文
     */
    private suspend fun getRecentChatContext(sessionId: String): List<com.readassist.repository.ChatContext> {
        return try {
            val messagesFlow = chatRepository.getChatMessages(sessionId)

            // 使用Flow的first()操作符获取第一个元素
            val messages = messagesFlow.first()

            // 获取最近的3轮对话（最多6条消息）
            val recentMessages = messages.takeLast(3)

            // 转换为ChatContext
            recentMessages.map { entity ->
                com.readassist.repository.ChatContext(
                    userMessage = entity.userMessage,
                    aiResponse = entity.aiResponse
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取聊天上下文失败", e)
            emptyList()
        }
    }

    /**
     * 从聊天历史中获取上下文
     */
    fun getRecentChatContext(chatHistory: List<ChatItem>): List<com.readassist.repository.ChatContext> {
        val messages = mutableListOf<com.readassist.repository.ChatContext>()

        // 从后向前遍历，找出最近的3轮对话
        var count = 0
        var i = chatHistory.size - 1

        while (i >= 0 && count < 3) {
            val item = chatHistory[i]

            if (item.isUserMessage) {
                // 找对应的AI回复
                val nextIndex = i + 1
                if (nextIndex < chatHistory.size) {
                    val aiItem = chatHistory[nextIndex]
                    if (!aiItem.isUserMessage && !aiItem.isLoading) {
                        messages.add(0, com.readassist.repository.ChatContext(
                            userMessage = item.userMessage,
                            aiResponse = aiItem.aiMessage
                        ))
                        count++
                    }
                }
            }

            i--
        }

        return messages
    }

    /**
     * 检查AI配置是否有效
     */
    fun isAiConfigurationValid(): Boolean {
        return preferenceManager.isCurrentConfigurationValid()
    }

    /**
     * 获取当前AI平台
     */
    fun getCurrentPlatform(): AiPlatform {
        return preferenceManager.getCurrentAiPlatform()
    }

    /**
     * 获取当前AI模型
     */
    fun getCurrentModel(): AiModel? {
        return preferenceManager.getCurrentAiModel()
    }

    /**
     * 设置AI平台
     */
    fun setAiPlatform(platform: AiPlatform) {
        preferenceManager.setCurrentAiPlatform(platform)
    }

    /**
     * 设置AI模型
     */
    fun setAiModel(model: AiModel) {
        preferenceManager.setCurrentAiModel(model.id)
    }

    /**
     * 设置API Key
     */
    fun setApiKey(platform: AiPlatform, apiKey: String) {
        preferenceManager.setApiKey(platform, apiKey)
    }

    /**
     * 检查API Key是否已设置
     */
    fun hasApiKey(platform: AiPlatform): Boolean {
        return preferenceManager.hasApiKey(platform)
    }

    /**
     * 当前模型是否支持图片分析
     */
    fun currentModelSupportsVision(): Boolean {
        return preferenceManager.getCurrentAiModel()?.supportsVision == true
    }
}
