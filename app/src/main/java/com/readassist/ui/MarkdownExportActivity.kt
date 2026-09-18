package com.readassist.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatEntity
import com.readassist.export.ConversationExportItem
import com.readassist.export.MarkdownExportManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MarkdownExportActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_MODE = "export_mode"
        private const val EXTRA_MESSAGE_IDS = "message_ids"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_ARCHIVED = "archived"
        private const val EXTRA_BOOK_NAME = "book_name"
        private const val EXTRA_APP_PACKAGE = "app_package"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_ANSWER = "answer"
        private const val EXTRA_TIMESTAMP = "timestamp"

        private const val MODE_MESSAGE_IDS = "message_ids"
        private const val MODE_SESSION = "session"
        private const val MODE_ALL = "all"
        private const val MODE_ARCHIVED_STATE = "archived_state"
        private const val MODE_DIRECT = "direct"

        fun forMessageIds(context: Context, ids: LongArray): Intent =
            baseIntent(context, MODE_MESSAGE_IDS).putExtra(EXTRA_MESSAGE_IDS, ids)

        fun forSession(context: Context, sessionId: String): Intent =
            baseIntent(context, MODE_SESSION).putExtra(EXTRA_SESSION_ID, sessionId)

        fun forAll(context: Context): Intent = baseIntent(context, MODE_ALL)

        fun forArchivedState(context: Context, archived: Boolean): Intent =
            baseIntent(context, MODE_ARCHIVED_STATE).putExtra(EXTRA_ARCHIVED, archived)

        fun forCurrentConversation(
            context: Context,
            bookName: String,
            appPackage: String,
            question: String,
            answer: String,
            timestamp: Long = System.currentTimeMillis()
        ): Intent = baseIntent(context, MODE_DIRECT).apply {
            putExtra(EXTRA_BOOK_NAME, bookName)
            putExtra(EXTRA_APP_PACKAGE, appPackage)
            putExtra(EXTRA_QUESTION, question)
            putExtra(EXTRA_ANSWER, answer)
            putExtra(EXTRA_TIMESTAMP, timestamp)
        }

        private fun baseIntent(context: Context, mode: String) =
            Intent(context, MarkdownExportActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    private lateinit var exportManager: MarkdownExportManager

    private val directoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            Toast.makeText(this, R.string.export_folder_not_authorized, Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }

        try {
            exportManager.saveExportRoot(uri)
            performExport()
        } catch (e: Exception) {
            showFailure(e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportManager = MarkdownExportManager(this)

        if (exportManager.hasWritableExportRoot()) {
            performExport()
        } else {
            Toast.makeText(this, R.string.export_select_export_folder, Toast.LENGTH_LONG).show()
            directoryLauncher.launch(exportManager.getInitialDirectoryUri())
        }
    }

    private fun performExport() {
        lifecycleScope.launch {
            try {
                val items = resolveItems()
                val result = exportManager.export(items, resolveFileLabel(items))
                Toast.makeText(
                    this@MarkdownExportActivity,
                    getString(
                        R.string.export_markdown_success,
                        result.conversationCount,
                        result.dateDirectory,
                        result.fileName
                    ),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                showFailure(e)
            }
        }
    }

    private suspend fun resolveItems(): List<ConversationExportItem> {
        val app = application as ReadAssistApplication
        val messages = when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_MESSAGE_IDS -> {
                val ids = intent.getLongArrayExtra(EXTRA_MESSAGE_IDS)?.toList().orEmpty()
                require(ids.isNotEmpty()) { getString(R.string.export_select_at_least_one) }
                app.chatRepository.getMessagesByIds(ids)
            }
            MODE_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
                require(sessionId.isNotBlank()) { getString(R.string.invalid_session_id) }
                app.chatRepository.getChatMessages(sessionId).first()
            }
            MODE_ALL -> app.chatRepository.getMessagesForExport(null)
            MODE_ARCHIVED_STATE -> app.chatRepository.getMessagesForExport(
                intent.getBooleanExtra(EXTRA_ARCHIVED, false)
            )
            MODE_DIRECT -> return listOf(
                ConversationExportItem(
                    id = 0,
                    bookName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty(),
                    appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE).orEmpty(),
                    question = intent.getStringExtra(EXTRA_QUESTION).orEmpty(),
                    answer = intent.getStringExtra(EXTRA_ANSWER).orEmpty(),
                    timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                ).also {
                    require(it.question.isNotBlank() && it.answer.isNotBlank()) {
                        getString(R.string.export_no_complete_conversation)
                    }
                }
            )
            else -> error("无效的导出请求")
        }

        require(messages.isNotEmpty()) { getString(R.string.no_sessions_to_export) }
        return messages.map { it.toExportItem() }
    }

    private fun resolveFileLabel(items: List<ConversationExportItem>): String {
        val distinctBooks = items.map { it.bookName }.filter { it.isNotBlank() }.distinct()
        return if (distinctBooks.size == 1) distinctBooks.first() else getString(R.string.history_title)
    }

    private fun showFailure(error: Exception) {
        Toast.makeText(
            this,
            getString(R.string.export_failed, error.message ?: getString(R.string.error_unknown)),
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun ChatEntity.toExportItem() = ConversationExportItem(
        id = id,
        bookName = bookName,
        appPackage = appPackage,
        question = userMessage,
        answer = aiResponse,
        timestamp = timestamp,
        isBookmarked = isBookmarked
    )
}
