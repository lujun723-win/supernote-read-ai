package com.readassist.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatSessionEntity
import com.readassist.databinding.ActivityHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var app: ReadAssistApplication
    private lateinit var adapter: SessionAdapter
    private var isShowingArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        app = application as ReadAssistApplication

        // 设置标题栏
        supportActionBar?.title = getString(R.string.history_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 初始化RecyclerView
        setupRecyclerView()

        // 加载历史会话数据
        loadSessionData()

        // 设置导出按钮
        binding.fabExport.setOnClickListener {
            showExportOptions()
        }

        // 设置返回按钮
        binding.btnBackToMain.setOnClickListener {
            finish()
        }

        // 设置过滤按钮
        binding.toggleArchived.setOnClickListener {
            isShowingArchived = !isShowingArchived
            binding.toggleArchived.text = if (isShowingArchived) getString(R.string.hide_archived_sessions) else getString(R.string.show_archived_sessions)
            loadSessionData()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.menu_export_all -> {
                exportAllHistory()
                true
            }
            R.id.menu_search -> {
                // TODO: 实现搜索功能
                Toast.makeText(this, getString(R.string.search_feature_coming_soon), Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = SessionAdapter(
            onItemClick = { session ->
                // 打开会话详情
                openSessionDetail(session)
            },
            onItemLongClick = { session ->
                // 显示操作菜单
                showSessionOptions(session)
                true
            }
        )
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter
    }

    private fun loadSessionData() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE

            // 根据过滤状态加载不同的会话数据
            val sessionsFlow = if (isShowingArchived) {
                app.chatRepository.getAllSessions()
            } else {
                app.chatRepository.getActiveSessions()
            }

            sessionsFlow.collectLatest { sessions ->
                adapter.submitList(sessions)
                binding.progressBar.visibility = View.GONE
                binding.emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openSessionDetail(session: ChatSessionEntity) {
        val intent = android.content.Intent(this, SessionDetailActivity::class.java).apply {
            putExtra(SessionDetailActivity.EXTRA_SESSION_ID, session.sessionId)
            putExtra(SessionDetailActivity.EXTRA_BOOK_NAME, session.bookName)
        }
        startActivity(intent)
    }

    private fun showSessionOptions(session: ChatSessionEntity) {
        val options = arrayOf(
            if (session.isArchived) getString(R.string.unarchive_session) else getString(R.string.archive_session),
            getString(R.string.export_session),
            getString(R.string.delete_session)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.session_actions))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleArchiveStatus(session)
                    1 -> exportSession(session)
                    2 -> confirmDeleteSession(session)
                }
            }
            .show()
    }

    private fun toggleArchiveStatus(session: ChatSessionEntity) {
        lifecycleScope.launch {
            app.chatRepository.archiveSession(session.sessionId, !session.isArchived)
            Toast.makeText(
                this@HistoryActivity,
                if (!session.isArchived) getString(R.string.session_archived) else getString(R.string.session_unarchived),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun exportSession(session: ChatSessionEntity) {
        startActivity(MarkdownExportActivity.forSession(this, session.sessionId))
    }

    private fun exportAllHistory() {
        startActivity(MarkdownExportActivity.forAll(this))
    }

    private fun confirmDeleteSession(session: ChatSessionEntity) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_session_title))
            .setMessage(getString(R.string.delete_session_message))
            .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                deleteSession(session)
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }

    private fun deleteSession(session: ChatSessionEntity) {
        lifecycleScope.launch {
            app.chatRepository.deleteSession(session.sessionId)
            Toast.makeText(this@HistoryActivity, getString(R.string.session_deleted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportOptions() {
        val options = arrayOf(
            getString(R.string.export_all_history),
            getString(R.string.export_active_sessions),
            getString(R.string.export_archived_sessions)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_options_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportAllHistory()
                    1 -> exportFilteredSessions(false)
                    2 -> exportFilteredSessions(true)
                }
            }
            .show()
    }

    private fun exportFilteredSessions(archived: Boolean) {
        startActivity(MarkdownExportActivity.forArchivedState(this, archived))
    }
}
