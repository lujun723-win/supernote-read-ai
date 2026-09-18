package com.readassist.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConversationExportItem(
    val id: Long,
    val bookName: String,
    val appPackage: String,
    val question: String,
    val answer: String,
    val timestamp: Long,
    val isBookmarked: Boolean = false
)

object MarkdownExportFormatter {
    fun format(items: List<ConversationExportItem>, exportedAt: Long = System.currentTimeMillis()): String {
        require(items.isNotEmpty()) { "没有可导出的对话" }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val exportedAtFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        return buildString {
            appendLine("# ReadAssist 阅读记录")
            appendLine()
            appendLine("- 导出时间：${exportedAtFormat.format(Date(exportedAt))}")
            appendLine("- 对话数量：${items.size}")

            items.sortedBy { it.timestamp }
                .groupBy { it.bookName.ifBlank { "未知文档" } }
                .forEach { (bookName, bookItems) ->
                    appendLine()
                    appendLine("## $bookName")

                    bookItems.groupBy { dayFormat.format(Date(it.timestamp)) }
                        .forEach { (day, dayItems) ->
                            appendLine()
                            appendLine("### $day")

                            dayItems.forEach { item ->
                                appendLine()
                                appendLine("#### ${timeFormat.format(Date(item.timestamp))}")
                                appendLine()
                                appendLine("**问题**")
                                appendLine()
                                appendLine(item.question.trim())
                                appendLine()
                                appendLine("**AI 回答**")
                                appendLine()
                                appendLine(item.answer.trim())
                                if (item.isBookmarked) {
                                    appendLine()
                                    appendLine("> 已收藏")
                                }
                            }
                        }
                }
        }
    }
}

class MarkdownExportManager(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "markdown_export"
        private const val KEY_EXPORT_ROOT_URI = "export_root_uri"
        private const val EXPORT_ROOT_NAME = "EXPORT"
    }

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInitialDirectoryUri(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$EXPORT_ROOT_NAME"
    )

    fun saveExportRoot(uri: Uri) {
        val directory = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("无法读取所选目录")
        require(directory.name.equals(EXPORT_ROOT_NAME, ignoreCase = true)) {
            "请选择 Supernote 根目录下的 EXPORT 文件夹"
        }
        require(directory.canWrite()) { "所选 EXPORT 文件夹不可写" }

        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        preferences.edit().putString(KEY_EXPORT_ROOT_URI, uri.toString()).apply()
    }

    fun hasWritableExportRoot(): Boolean = getWritableExportRoot() != null

    fun export(items: List<ConversationExportItem>, fileLabel: String): ExportResult {
        require(items.isNotEmpty()) { "没有可导出的对话" }
        val root = getWritableExportRoot()
            ?: throw IllegalStateException("请先授权访问 Supernote 的 EXPORT 文件夹")

        val exportedAt = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(exportedAt))
        val dateDirectory = root.findFile(day)?.takeIf { it.isDirectory }
            ?: root.createDirectory(day)
            ?: throw IllegalStateException("无法创建 EXPORT/$day")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(exportedAt))
        val safeLabel = sanitizeFileName(fileLabel)
        val fileName = "ReadAssist_${safeLabel}_$timestamp.md"
        val outputFile = dateDirectory.createFile("text/markdown", fileName)
            ?: throw IllegalStateException("无法创建 Markdown 文件")

        val content = MarkdownExportFormatter.format(items, exportedAt)
        context.contentResolver.openOutputStream(outputFile.uri, "w")?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入 Markdown 文件")

        return ExportResult(day, outputFile.name ?: fileName, items.size)
    }

    private fun getWritableExportRoot(): DocumentFile? {
        val uri = preferences.getString(KEY_EXPORT_ROOT_URI, null)?.let(Uri::parse) ?: return null
        val hasWritePermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!hasWritePermission) return null

        return DocumentFile.fromTreeUri(context, uri)?.takeIf {
            it.name.equals(EXPORT_ROOT_NAME, ignoreCase = true) && it.canWrite()
        }
    }

    private fun sanitizeFileName(value: String): String {
        val sanitized = value
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
            .trim(' ', '.', '_')
            .take(60)
        return sanitized.ifBlank { "阅读记录" }
    }
}

data class ExportResult(
    val dateDirectory: String,
    val fileName: String,
    val conversationCount: Int
)
