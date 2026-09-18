package com.readassist.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.databinding.ActivitySessionDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SessionDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_BOOK_NAME = "extra_book_name"
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var app: ReadAssistApplication
    private lateinit var sessionId: String
    private lateinit var bookName: String
    private lateinit var adapter: ChatMessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ReadAssistApplication

        // 获取会话ID
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""
        bookName = intent.getStringExtra(EXTRA_BOOK_NAME) ?: getString(R.string.unknown_book)

        if (sessionId.isEmpty()) {
            Toast.makeText(this, getString(R.string.invalid_session_id), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 设置标题栏
        supportActionBar?.title = bookName
        supportActionBar?.subtitle = getString(R.string.session_detail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 初始化RecyclerView
        setupRecyclerView()

        // 加载会话消息
        loadSessionMessages()

        // 设置导出按钮
        binding.fabExport.setOnClickListener {
            exportSession()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.session_detail_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_export -> {
                exportSession()
                true
            }
            R.id.menu_delete -> {
                confirmDeleteSession()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onBookmarkToggle = { message, isBookmarked ->
                toggleMessageBookmark(message.id, isBookmarked)
            },
            onSelectionCountChanged = { count ->
                supportActionBar?.subtitle = if (count == 0) {
                    getString(R.string.session_detail)
                } else {
                    getString(R.string.selected_conversation_count, count)
                }
            }
        )
        binding.recyclerViewMessages.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewMessages.adapter = adapter
    }

    private fun loadSessionMessages() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            app.chatRepository.getChatMessages(sessionId).collectLatest { messages ->
                adapter.submitList(messages)
                binding.progressBar.visibility = View.GONE

                // 如果消息列表为空，显示空视图
                binding.emptyView.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE

                // 滚动到底部
                if (messages.isNotEmpty()) {
                    binding.recyclerViewMessages.post {
                        binding.recyclerViewMessages.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            }
        }
    }

    private fun toggleMessageBookmark(messageId: Long, isBookmarked: Boolean) {
        lifecycleScope.launch {
            app.chatRepository.toggleBookmark(messageId, isBookmarked)
            Toast.makeText(
                this@SessionDetailActivity,
                if (isBookmarked) getString(R.string.message_bookmarked) else getString(R.string.message_unbookmarked),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportSession() {
        val selectedIds = adapter.getSelectedMessageIds()
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, R.string.export_select_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(MarkdownExportActivity.forMessageIds(this, selectedIds))
    }

    private fun confirmDeleteSession() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_session_title))
            .setMessage(getString(R.string.delete_session_message))
            .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                deleteSession()
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun deleteSession() {
        lifecycleScope.launch {
            app.chatRepository.deleteSession(sessionId)
            Toast.makeText(this@SessionDetailActivity, getString(R.string.session_deleted), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
