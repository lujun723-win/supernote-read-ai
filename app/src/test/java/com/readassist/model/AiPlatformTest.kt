package com.readassist.model

import com.google.gson.Gson
import com.readassist.network.DeepSeekContentPart
import com.readassist.network.DeepSeekImageUrl
import com.readassist.network.DeepSeekMessage
import com.readassist.network.DeepSeekRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlatformTest {
    @Test
    fun deepSeekDefaultModelSupportsVision() {
        val model = AiModel.getDefaultModelForPlatform(AiPlatform.DEEPSEEK)

        assertEquals("deepseek-v4-flash-vision-exp", model?.id)
        assertTrue(model?.supportsVision == true)
    }

    @Test
    fun deepSeekTextModelsAreNotMarkedAsVisionModels() {
        val models = AiModel.getDefaultModels().filter { it.platform == AiPlatform.DEEPSEEK }

        assertFalse(models.first { it.id == "deepseek-v4-flash" }.supportsVision)
        assertFalse(models.first { it.id == "deepseek-v4-pro" }.supportsVision)
    }

    @Test
    fun deepSeekKeyValidationRequiresSkPrefix() {
        val pattern = AiPlatform.DEEPSEEK.keyValidationPattern.toRegex()

        assertTrue("sk-1234567890abcdef".matches(pattern))
        assertFalse("1234567890abcdef".matches(pattern))
    }

    @Test
    fun deepSeekVisionRequestUsesOpenAiCompatibleImageShape() {
        val request = DeepSeekRequest(
            model = "deepseek-v4-flash-vision-exp",
            messages = listOf(
                DeepSeekMessage(
                    role = "user",
                    content = listOf(
                        DeepSeekContentPart(type = "text", text = "分析截图"),
                        DeepSeekContentPart(
                            type = "image_url",
                            image_url = DeepSeekImageUrl("data:image/jpeg;base64,AAAA")
                        )
                    )
                )
            )
        )

        val json = Gson().toJson(request)
        assertTrue(json.contains("\"type\":\"image_url\""))
        assertTrue(json.contains("\"url\":\"data:image/jpeg;base64,AAAA\""))
        assertTrue(json.contains("\"detail\":\"original\""))
    }
}
