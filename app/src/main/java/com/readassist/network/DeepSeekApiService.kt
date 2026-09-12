package com.readassist.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface DeepSeekApiService {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: DeepSeekRequest
    ): Response<DeepSeekResponse>
}

data class DeepSeekRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 2048,
    val stream: Boolean = false
)

data class DeepSeekMessage(
    val role: String,
    val content: Any
)

data class DeepSeekContentPart(
    val type: String,
    val text: String? = null,
    val image_url: DeepSeekImageUrl? = null
)

data class DeepSeekImageUrl(
    val url: String,
    val detail: String = "original"
)

data class DeepSeekResponse(
    val choices: List<DeepSeekChoice>?,
    val error: DeepSeekError?
)

data class DeepSeekChoice(
    val message: DeepSeekResponseMessage?
)

data class DeepSeekResponseMessage(
    val content: String?
)

data class DeepSeekError(
    val message: String
)
