package com.readassist.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExportFormatterTest {
    @Test
    fun `formats selected conversations as grouped markdown`() {
        val items = listOf(
            ConversationExportItem(
                id = 1,
                bookName = "启示录",
                appPackage = "com.supernote",
                question = "第一个问题",
                answer = "第一个回答",
                timestamp = 1_789_562_096_000,
                isBookmarked = true
            ),
            ConversationExportItem(
                id = 2,
                bookName = "启示录",
                appPackage = "com.supernote",
                question = "第二个问题",
                answer = "第二个回答",
                timestamp = 1_789_562_156_000
            )
        )

        val markdown = MarkdownExportFormatter.format(items, exportedAt = 1_789_562_216_000)

        assertTrue(markdown.startsWith("# ReadAssist 阅读记录"))
        assertTrue(markdown.contains("## 启示录"))
        assertEquals(1, Regex("### 2026-").findAll(markdown).count())
        assertTrue(markdown.contains("**问题**\n\n第一个问题"))
        assertTrue(markdown.contains("**AI 回答**\n\n第二个回答"))
        assertTrue(markdown.contains("> 已收藏"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty exports`() {
        MarkdownExportFormatter.format(emptyList())
    }
}
