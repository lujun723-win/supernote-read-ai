/**
 * 重构后的悬浮窗服务类，替代原来的FloatingWindowService
 *
 * 重构目的：
 * 1. 使用模块化设计，将巨大的服务类拆分为多个专注于特定功能的管理器类
 * 2. 提高代码可维护性，每个管理器类只负责一项特定功能
 * 3. 符合单一职责原则，降低代码复杂度
 * 4. 便于功能扩展和测试
 *
 * 优势：
 * 1. 代码结构更加清晰，易于理解和维护
 * 2. 各个功能模块之间的依赖关系更加明确
 * 3. 更容易添加新功能或修改现有功能
 * 4. 代码复用性更高，减少了冗余代码
 */
package com.readassist.service

import android.app.Service
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatEntity
import com.readassist.network.ApiResult
import com.readassist.service.managers.AiCommunicationManager
import com.readassist.service.managers.AiConfigurationManager
import com.readassist.service.managers.ChatWindowManager
import com.readassist.service.managers.FloatingButtonManager
import com.readassist.service.managers.ScreenshotManager
import com.readassist.service.managers.RegionSelectionManager
import com.readassist.service.managers.RegionSelection
import com.readassist.service.managers.SessionManager
import com.readassist.service.managers.TextSelectionManager
import com.readassist.service.managers.DictionaryWindowManager
import com.readassist.service.managers.VocabularyOverlayManager
import com.readassist.dictionary.OfflineDictionaryManager
import com.readassist.dictionary.OfflineOcrManager
import com.readassist.dictionary.VocabularyHintIndex
import com.readassist.dictionary.VocabularyHintSelector
import com.readassist.model.DictionaryCaptureMode
import com.readassist.model.AiCaptureMode
import com.readassist.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import com.readassist.utils.DeviceUtils
import com.readassist.utils.DeviceType
import android.widget.Toast
import com.readassist.utils.PreferenceManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.readassist.ui.DeviceSetupActivity
import com.readassist.R
import android.view.WindowManager
import android.os.Handler

/**
 * 重构后的悬浮窗服务
 */
class FloatingWindowServiceNew : Service(),
    ScreenshotManager.ScreenshotCallbacks,
    TextSelectionManager.TextSelectionCallbacks,
    ChatWindowManager.ChatWindowCallbacks,
    FloatingButtonManager.FloatingButtonCallbacks,
    RegionSelectionManager.Callbacks,
    DictionaryWindowManager.Callbacks {

    companion object {
        private const val TAG = "FloatingWindowServiceNew"
    }

    override fun attachBaseContext(base: Context?) {
        Log.d(TAG, "🔧 Service attachBaseContext started")

        if (base == null) {
            super.attachBaseContext(base)
            return
        }

        try {
            // 应用语言设置到Service
            val localizedContext = createLocalizedContext(base)
            super.attachBaseContext(localizedContext)

            Log.d(TAG, "✅ Service attachBaseContext completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Service attachBaseContext failed", e)
            super.attachBaseContext(base)
        }
    }

    /**
     * 创建本地化Context
     */
    private fun createLocalizedContext(context: Context): Context {
        return try {
            val prefs = context.getSharedPreferences("readassist_prefs", Context.MODE_PRIVATE)
            val languageCode = prefs.getString("app_language", "system") ?: "system"

            val locale = when (languageCode) {
                "zh" -> java.util.Locale.CHINESE
                "en" -> java.util.Locale.ENGLISH
                else -> getSystemLocale(context)
            }

            Log.d(TAG, "🌐 Service applying language: $languageCode -> $locale")

            java.util.Locale.setDefault(locale)

            val configuration = android.content.res.Configuration(context.resources.configuration)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                configuration.setLocales(android.os.LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                configuration.locale = locale
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                context.createConfigurationContext(configuration)
            } else {
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
                context
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create localized context", e)
            context
        }
    }

    /**
     * 获取系统默认语言
     */
    private fun getSystemLocale(context: Context): java.util.Locale {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }

    // 组件管理器
    private lateinit var floatingButtonManager: FloatingButtonManager
    private lateinit var chatWindowManager: ChatWindowManager
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var regionSelectionManager: RegionSelectionManager
    private lateinit var textSelectionManager: TextSelectionManager
    private lateinit var sessionManager: SessionManager
    private lateinit var aiConfigurationManager: AiConfigurationManager
    private lateinit var aiCommunicationManager: AiCommunicationManager
    private lateinit var dictionaryWindowManager: DictionaryWindowManager
    private lateinit var vocabularyOverlayManager: VocabularyOverlayManager
    private lateinit var vocabularyHintSelector: VocabularyHintSelector
    private lateinit var offlineDictionaryManager: OfflineDictionaryManager
    private val offlineOcrManager = OfflineOcrManager()

    // 应用实例和协程作用域
    private lateinit var app: ReadAssistApplication
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 广播接收器
    private val textDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TextAccessibilityService.ACTION_TEXT_DETECTED -> {
                    val text = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
                    val appPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
                    val bookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""

                    Log.d(TAG, "📬 接收到ACTION_TEXT_DETECTED广播: app='$appPackage', book='$bookName'")
                    Log.d(TAG, "📬 文本内容: '${text.take(100)}...'")

                    textSelectionManager.handleTextDetected(text, appPackage, bookName)
                }
                TextAccessibilityService.ACTION_TEXT_SELECTED -> {
                    val text = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
                    val appPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
                    val bookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""

                    // 获取文本选择位置信息
                    val selectionX = intent.getIntExtra("SELECTION_X", -1)
                    val selectionY = intent.getIntExtra("SELECTION_Y", -1)
                    val selectionWidth = intent.getIntExtra("SELECTION_WIDTH", -1)
                    val selectionHeight = intent.getIntExtra("SELECTION_HEIGHT", -1)

                    Log.d(TAG, "📬 接收到ACTION_TEXT_SELECTED广播: app='$appPackage', book='$bookName'")
                    Log.d(TAG, "📬 文本内容: '${text.take(100)}...'")
                    Log.d(TAG, "📬 选择位置: x=$selectionX, y=$selectionY, w=$selectionWidth, h=$selectionHeight")

                    if (isDictionaryTextSelectionPending && text.isNotBlank()) {
                        isDictionaryTextSelectionPending = false
                        showDictionaryAndLookup(text)
                        return
                    }
                    if (isAiTextSelectionPending && text.isNotBlank()) {
                        showAiWithSelectedText(text)
                        return
                    }

                    textSelectionManager.handleTextSelected(
                        text, appPackage, bookName,
                        selectionX, selectionY, selectionWidth, selectionHeight
                    )
                }
                "com.readassist.TEXT_SELECTION_ACTIVE" -> {
                    textSelectionManager.handleTextSelectionActive()
                }
                "com.readassist.TEXT_SELECTION_INACTIVE" -> {
                    textSelectionManager.handleTextSelectionInactive()
                }
                "com.readassist.RECHECK_SCREENSHOT_PERMISSION" -> {
                    screenshotManager.recheckScreenshotPermission()
                }
                "com.readassist.SCREENSHOT_PERMISSION_GRANTED" -> {
                    screenshotManager.handlePermissionGranted()
                }
                "com.readassist.SCREENSHOT_PERMISSION_DENIED" -> {
                    screenshotManager.handlePermissionDenied()
                }
                "com.readassist.SCREENSHOT_PERMISSION_ERROR" -> {
                    val errorMessage = intent.getStringExtra("ERROR_MESSAGE") ?: "未知错误"
                    screenshotManager.handlePermissionError(errorMessage)
                }
            }
        }
    }

    private val screenshotTakenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TextAccessibilityService.ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY) {
                val uriString = intent.getStringExtra(TextAccessibilityService.EXTRA_SCREENSHOT_URI)
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    Log.d(TAG, "Screenshot taken via accessibility: $uri")
                    serviceScope.launch {
                        processScreenshotFromUri(uri)
                    }
                }
            }
        }
    }


    // 前台服务直接剪贴板检测广播接收器
    private val directClipboardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isRegionCaptureInProgress || isVocabularyCaptureInProgress) {
                Log.d(TAG, "区域截图进行中，忽略剪贴板弹窗广播")
                return
            }

            when (intent?.action) {
                "com.readassist.SHOW_CHAT_WITH_CLIPBOARD" -> {
                    val clipboardText = intent.getStringExtra("clipboard_text") ?: ""

                    Log.d(TAG, "📋 接收到前台服务剪贴板检测: text='${clipboardText.take(50)}...'")

                    if (clipboardText.isNotBlank()) {
                        if (isDictionaryTextSelectionPending) {
                            isDictionaryTextSelectionPending = false
                            showDictionaryAndLookup(clipboardText)
                            return
                        }
                        if (isAiTextSelectionPending) {
                            showAiWithSelectedText(clipboardText)
                            return
                        }
                        Log.d(TAG, "📋 当前没有文字选择请求，忽略剪贴板弹窗广播")
                    } else {
                        Log.d(TAG, "📋 前台服务剪贴板内容为空")
                    }
                }
            }
        }
    }

    // Hover触发的剪贴板检查广播接收器
    private val hoverClipboardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isRegionCaptureInProgress || isVocabularyCaptureInProgress) {
                Log.d(TAG, "区域截图进行中，忽略悬停剪贴板广播")
                return
            }

            Log.e(TAG, "🔴🔴🔴 hoverClipboardReceiver.onReceive() 被调用")
            Log.e(TAG, "🔴 Intent action: ${intent?.action}")
            Log.e(TAG, "🔴 Intent extras: ${intent?.extras}")

            when (intent?.action) {
                "com.readassist.CLIPBOARD_CHANGED" -> {
                    if (isDictionaryTextSelectionPending) {
                        dictionaryCopySignalSeen = true
                        Log.d(TAG, "📖 字典等待态检测到复制菜单")
                    }
                    if (isAiTextSelectionPending) {
                        aiCopySignalSeen = true
                        Log.d(TAG, "🤖 AI 文字选择等待态检测到复制菜单")
                    }
                }
                "com.readassist.CHECK_CLIPBOARD_FROM_HOVER" -> {
                    val source = intent.getStringExtra("source") ?: "unknown"
                    val packageName = intent.getStringExtra("package") ?: "unknown"
                    val timestamp = intent.getLongExtra("timestamp", 0L)

                    Log.e(TAG, "🔴🔴🔴 接收到Hover剪贴板检查请求: source=$source, package=$packageName, timestamp=$timestamp")

                    if (isDictionaryTextSelectionPending && !dictionaryCopySignalSeen) {
                        Log.d(TAG, "📖 尚未检测到复制菜单，不读取旧剪贴板")
                        return
                    }

                    if (isDictionaryTextSelectionPending) {
                        openDictionaryWindowAndReadClipboard()
                        return
                    }
                    if (isAiTextSelectionPending && !aiCopySignalSeen) {
                        Log.d(TAG, "🤖 尚未检测到新复制信号，不读取旧剪贴板")
                        return
                    }
                    if (isAiTextSelectionPending) {
                        openAiWindowAndReadClipboard()
                        return
                    }
                    Log.d(TAG, "📋 当前没有文字选择请求，忽略悬停剪贴板检查")
                }
                else -> {
                    Log.e(TAG, "🔴 收到未知的广播action: ${intent?.action}")
                }
            }
        }
    }


    private var pendingScreenshot: Uri? = null
    private var pendingScreenshotBitmap: Bitmap? = null
    private var isRegionCaptureInProgress = false
    private var isVocabularyCaptureInProgress = false
    private enum class RegionCapturePurpose { AI, DICTIONARY }
    private var regionCapturePurpose = RegionCapturePurpose.AI
    private var isAiTextSelectionPending = false
    private var isDictionaryTextSelectionPending = false

    private lateinit var preferenceManager: PreferenceManager

    // 新增：记录上一次截屏的文件路径
    private var lastScreenshotFile: File? = null

    private val clipboardAccessHandler = Handler(Looper.getMainLooper())
    private data class ClipboardSnapshot(val timestamp: Long, val textHash: Int)
    private var dictionaryClipboardBaseline: ClipboardSnapshot? = null
    private var dictionarySelectionStartedAt = 0L
    private var dictionaryCopySignalSeen = false
    private var aiSelectionStartedAt = 0L
    private var aiCopySignalSeen = false
    private var pendingAiClipboardRead: Runnable? = null
    private var pendingDictionaryClipboardRead: Runnable? = null
    private val systemClipboardManager by lazy {
        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val textCaptureClipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if ((!isDictionaryTextSelectionPending && !isAiTextSelectionPending) ||
            isRegionCaptureInProgress || isVocabularyCaptureInProgress
        ) {
            return@OnPrimaryClipChangedListener
        }
        val clip = systemClipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isEmpty()) return@OnPrimaryClipChangedListener
        if (isDictionaryTextSelectionPending) {
            Log.d(TAG, "📖 字典等待态收到新的剪贴板文本: ${text.take(80)}")
            showDictionaryAndLookup(text)
        } else if (isAiTextSelectionPending) {
            Log.d(TAG, "🤖 AI 文字选择等待态收到新的剪贴板文本: ${text.take(80)}")
            showAiWithSelectedText(text)
        }
    }

    /**
     * 检查掌阅设备是否需要配置SAF权限
     */
    private fun checkIReaderSetup() {
        if (screenshotManager.checkIReaderSetupRequired()) {
            serviceScope.launch {
                delay(2000) // 延迟2秒显示提示，避免与其他启动流程冲突

                Toast.makeText(
                    this@FloatingWindowServiceNew,
                    getString(R.string.ireader_device_detected),
                    Toast.LENGTH_LONG
                ).show()

                // 可选：自动打开设置界面
                // showIReaderSetupDialog()
            }
        }
    }

    /**
     * 显示掌阅设备设置对话框
     */
    private fun showIReaderSetupDialog() {
        val intent = Intent(this, DeviceSetupActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    override fun onCreate() {
        Log.e(TAG, "🔴🔴🔴 FloatingWindowServiceNew.onCreate() 开始执行")
        Log.e(TAG, "🔴 服务创建时间: ${System.currentTimeMillis()}")
        Log.e(TAG, "🔴 当前线程: ${Thread.currentThread().name}")

        try {
            super.onCreate()
            Log.e(TAG, "🔴 super.onCreate() 执行成功")

            Log.d(TAG, "FloatingWindowService created")
            Log.e(TAG, "🔴 开始初始化核心组件")

            // 初始化核心组件
            Log.e(TAG, "🔴 开始初始化ReadAssistApplication")
            app = application as ReadAssistApplication
            Log.e(TAG, "🔴 ReadAssistApplication初始化成功")

            // 初始化preferenceManager
            Log.e(TAG, "🔴 开始初始化PreferenceManager")
            preferenceManager = PreferenceManager(applicationContext)
            Log.e(TAG, "🔴 PreferenceManager初始化成功")

            // 初始化各个管理器
            Log.e(TAG, "🔴 开始初始化各个管理器")
            initializeManagers()
            Log.e(TAG, "🔴 各个管理器初始化完成")

            // 注册广播接收器
            Log.e(TAG, "🔴 开始注册广播接收器")
            registerReceivers()
            Log.e(TAG, "🔴 广播接收器注册完成")

            // 创建通知渠道和前台服务通知
            Log.e(TAG, "🔴 开始设置前台服务")
            setupForegroundService()
            Log.e(TAG, "🔴 前台服务设置完成")

            // 检查掌阅设备是否需要配置SAF权限
            Log.e(TAG, "🔴 检查掌阅设备配置")
            checkIReaderSetup()
            Log.e(TAG, "🔴 掌阅设备检查完成")

            // 检查截屏权限状态，如果处于中间状态则重置
            Log.e(TAG, "🔴 启动截屏权限检查协程")
            serviceScope.launch {
                delay(500) // 从2000ms减少到500ms
                Log.e(TAG, "🔴 执行启动后权限检查...")

                if (app.preferenceManager.isScreenshotPermissionGranted()) {
                    // 如果权限已授予，但截屏功能不可用，则尝试重置
                    if (!screenshotManager.isScreenshotServiceReady()) {
                        Log.w(TAG, "🔴 检测到权限状态异常，执行重置")
                        screenshotManager.forceResetPermission()
                    }
                }
            }

            // 标记服务已启动
            Log.e(TAG, "🔴 标记服务已启动")
            getSharedPreferences("service_prefs", MODE_PRIVATE)
                .edit().putBoolean("is_floating_service_running", true).apply()
            Log.e(TAG, "🔴 服务启动标记已设置")

            Log.e(TAG, "🔴🔴🔴 FloatingWindowServiceNew.onCreate() 执行完成")

        } catch (e: Exception) {
            Log.e(TAG, "🔴🔴🔴 FloatingWindowServiceNew.onCreate() 发生异常: ${e.message}", e)
            Log.e(TAG, "🔴 异常堆栈: ${e.stackTraceToString()}")
            throw e // 重新抛出异常，让系统知道服务启动失败
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "🔴🔴🔴 FloatingWindowServiceNew.onStartCommand() 被调用")
        Log.e(TAG, "🔴 Intent: $intent")
        Log.e(TAG, "🔴 Flags: $flags, StartId: $startId")

        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "🔴 没有悬浮窗权限，但继续运行服务以支持截屏功能")
            // 不停止服务，因为用户可能只需要截屏功能，不需要悬浮窗
            // 只是不显示悬浮按钮，但保持服务运行以支持截屏
        } else {
            Log.d(TAG, "🔴 悬浮窗权限已授予，可以显示悬浮按钮")
            // 只有在有悬浮窗权限时才创建悬浮按钮
            try {
                floatingButtonManager.createButton()
                Log.e(TAG, "🔴 悬浮按钮创建成功")
            } catch (e: Exception) {
                Log.e(TAG, "🔴 创建悬浮按钮时发生异常: ${e.message}", e)
            }
        }

        // 确保服务不会因为内存不足而被系统杀死
        return START_STICKY
    }

    /**
     * 初始化管理器
     */
    private fun initializeManagers() {
        Log.e(TAG, "🔴 initializeManagers() 开始执行")
        Log.e(TAG, "🔴 初始化会话管理器")

        // 初始化会话管理器
        sessionManager = SessionManager(
            chatRepository = app.chatRepository
        )
        Log.e(TAG, "🔴 会话管理器初始化成功")

        // 初始化文本选择管理器
        Log.e(TAG, "🔴 初始化文本选择管理器")
        textSelectionManager = TextSelectionManager()
        textSelectionManager.setCallbacks(this)
        Log.e(TAG, "🔴 文本选择管理器初始化成功")

        // 初始化截屏管理器
        Log.e(TAG, "🔴 初始化截屏管理器")
        screenshotManager = ScreenshotManager(
            context = this,
            preferenceManager = preferenceManager,
            coroutineScope = serviceScope,
            callbacks = this
        )
        Log.e(TAG, "🔴 截屏管理器初始化成功")

        // 初始化聊天窗口管理器
        Log.e(TAG, "🔴 初始化聊天窗口管理器")
        chatWindowManager = ChatWindowManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            preferenceManager = preferenceManager,
            coroutineScope = serviceScope,
            callbacks = this
        )
        chatWindowManager.setTextSelectionManager(textSelectionManager)
        Log.e(TAG, "🔴 聊天窗口管理器初始化成功")

        regionSelectionManager = RegionSelectionManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager,
            callbacks = this
        )

        offlineDictionaryManager = app.offlineDictionaryManager
        dictionaryWindowManager = DictionaryWindowManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager,
            preferenceManager = preferenceManager,
            callbacks = this
        )
        vocabularyOverlayManager = VocabularyOverlayManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        )
        vocabularyHintSelector = VocabularyHintSelector(VocabularyHintIndex(applicationContext))

        // 初始化AI配置管理器
        aiConfigurationManager = AiConfigurationManager(
            context = this,
            preferenceManager = preferenceManager
        )

        // 初始化AI通信管理器
        aiCommunicationManager = AiCommunicationManager(
            chatRepository = app.chatRepository,
            geminiRepository = app.geminiRepository,
            preferenceManager = preferenceManager
        )

        // 初始化悬浮按钮管理器
        floatingButtonManager = FloatingButtonManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            preferenceManager = preferenceManager,
            callbacks = this
        )
        floatingButtonManager.createButton()


        screenshotManager.initialize()
    }

    /**
     * 注册广播接收器
     */
    private fun registerReceivers() {
        Log.e(TAG, "🔴 registerReceivers() 开始执行")
        Log.e(TAG, "🔴 注册文本检测广播接收器")
        val textFilter = IntentFilter().apply {
            addAction(TextAccessibilityService.ACTION_TEXT_DETECTED)
            addAction(TextAccessibilityService.ACTION_TEXT_SELECTED)
            addAction("com.readassist.TEXT_SELECTION_ACTIVE")
            addAction("com.readassist.TEXT_SELECTION_INACTIVE")
            addAction("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
            addAction("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_DENIED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_ERROR")
        }
        registerReceiver(textDetectedReceiver, textFilter)
        LocalBroadcastManager.getInstance(this).registerReceiver(textDetectedReceiver, textFilter)
        Log.e(TAG, "🔴 文本检测广播接收器注册成功")

        Log.e(TAG, "🔴 注册截屏广播接收器")
        val screenshotFilter = IntentFilter(TextAccessibilityService.ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY)
        registerReceiver(screenshotTakenReceiver, screenshotFilter)
        Log.e(TAG, "🔴 截屏广播接收器注册成功")


        // 注册前台服务剪贴板检测广播接收器
        Log.e(TAG, "🔴 注册直接剪贴板检测广播接收器")
        val directClipboardFilter = IntentFilter("com.readassist.SHOW_CHAT_WITH_CLIPBOARD")
        registerReceiver(directClipboardReceiver, directClipboardFilter)
        Log.e(TAG, "🔴 直接剪贴板检测广播接收器注册成功")

        // 注册Hover触发的剪贴板检查广播接收器
        Log.e(TAG, "🔴 注册Hover剪贴板检查广播接收器")
        val hoverClipboardFilter = IntentFilter().apply {
            addAction("com.readassist.CHECK_CLIPBOARD_FROM_HOVER")
            addAction("com.readassist.CLIPBOARD_CHANGED")
        }
        registerReceiver(hoverClipboardReceiver, hoverClipboardFilter)
        Log.e(TAG, "🔴 Hover剪贴板检查广播接收器注册成功")

        systemClipboardManager.addPrimaryClipChangedListener(textCaptureClipboardListener)
        Log.e(TAG, "🔴 文字选择剪贴板监听器注册成功")


        Log.e(TAG, "🔴 registerReceivers() 执行完成")
    }

    /**
     * 设置前台服务
     */
    private fun setupForegroundService() {
        val channelId = "read_assist_foreground_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "ReadAssist前台服务",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.readassist_running))
            .setContentText(getString(R.string.tap_to_manage_app))
            .setSmallIcon(com.readassist.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

        // 启动前台服务，防止系统杀死
        startForeground(1001, notification)
    }


    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 当服务被销毁时调用
     */
    override fun onDestroy() {
        Log.e(TAG, "onDestroy called")
        try {
            // 注销广播接收器
            unregisterReceiver(textDetectedReceiver)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(textDetectedReceiver)
            unregisterReceiver(screenshotTakenReceiver)
            unregisterReceiver(directClipboardReceiver)
            unregisterReceiver(hoverClipboardReceiver)
            systemClipboardManager.removePrimaryClipChangedListener(textCaptureClipboardListener)
            Log.e(TAG, "已注销所有广播接收器")
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }

        // 销毁管理器
        if (::screenshotManager.isInitialized) {
            screenshotManager.destroy()
        }
        if (::regionSelectionManager.isInitialized) {
            regionSelectionManager.cleanup()
        }
        if (::dictionaryWindowManager.isInitialized) {
            dictionaryWindowManager.hide()
        }
        if (::vocabularyOverlayManager.isInitialized) {
            vocabularyOverlayManager.hide()
        }
        chatWindowManager?.hideChatWindow()
        floatingButtonManager?.removeButton()
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null

        super.onDestroy()

        // 标记服务已停止
        getSharedPreferences("service_prefs", MODE_PRIVATE)
            .edit().putBoolean("is_floating_service_running", false).apply()
    }

    /**
     * 处理悬浮按钮点击
     */
    private fun handleFloatingButtonClick() {
        Log.e(TAG, "[日志追踪] handleFloatingButtonClick 被调用")
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        aiSelectionStartedAt = 0L
        isDictionaryTextSelectionPending = false
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        floatingButtonManager.setDictionaryWaiting(false)
        dictionaryWindowManager.hide()
        if (!aiConfigurationManager.isConfigurationValid()) {
            showConfigurationRequiredDialog()
            floatingButtonManager.restoreDefaultState()
            return
        }
        val appPackageName = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()
        sessionManager.setCurrentApp(appPackageName)
        sessionManager.setCurrentBook(bookName)
        app.preferenceManager.setString("current_app_package", appPackageName)
        app.preferenceManager.setString("current_book_name", bookName)

        chatWindowManager.showChatWindow()
        updateClipboardUIWithAutoSelection()
        floatingButtonManager.restoreDefaultState()
    }

    /**
     * 辅助方法：收集AccessibilityNodeInfo中的所有文本
     */
    private fun collectAllText(node: android.view.accessibility.AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return

        // 添加节点自己的文本
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }

        // 遍历所有子节点
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                collectAllText(child, result)
                child?.recycle()  // 重要：回收不再使用的AccessibilityNodeInfo对象
            } catch (e: Exception) {
                Log.e(TAG, "收集文本时出错", e)
            }
        }
    }

    /**
     * 辅助方法：查找具有焦点的节点
     */
    private fun findFocusedNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isFocused) return node

        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                val focusedNode = findFocusedNode(child)
                if (focusedNode != null) {
                    return focusedNode
                }
                child?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "查找焦点节点时出错", e)
            }
        }

        return null
    }

    /**
     * 辅助方法：查找工具栏或标题栏中的文本
     */
    private fun findToolbarTexts(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()

        val results = mutableListOf<String>()

        // 查找类名包含Toolbar, ActionBar, TitleBar等的节点
        val className = node.className?.toString() ?: ""
        if (className.contains("Toolbar", ignoreCase = true) ||
            className.contains("ActionBar", ignoreCase = true) ||
            className.contains("TitleBar", ignoreCase = true) ||
            className.contains("Header", ignoreCase = true)) {

            // 收集这个节点下的所有文本
            collectAllText(node, results)
            return results
        }

        // 递归查找子节点
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                results.addAll(findToolbarTexts(child))
                child?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "查找工具栏文本时出错", e)
            }
        }

        return results
    }

    /**
     * 显示配置必需对话框
     */
    private fun showConfigurationRequiredDialog() {
        aiConfigurationManager.showConfigurationRequiredDialog(
            onOpenMainApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            },
            onQuickConfig = {
                chatWindowManager.showChatWindow()
                showQuickConfigurationDialog()
            }
        )
    }

    /**
     * 显示快速配置对话框
     */
    private fun showQuickConfigurationDialog() {
        aiConfigurationManager.showPlatformSelectionDialog { platform ->
            showApiKeyInputDialog(platform)
        }
    }

    /**
     * 显示API Key输入对话框
     */
    private fun showApiKeyInputDialog(platform: com.readassist.model.AiPlatform) {
        aiConfigurationManager.showApiKeyInputDialog(platform) { success ->
            if (success) {
                // 更新AI配置UI
                chatWindowManager.updateAiConfigurationStatus()
            }
        }
    }

    /**
     * 发送用户消息
     */
    private fun sendUserMessage(message: String) {
        Log.d(TAG, "=== sendUserMessage 开始 ===")
        Log.e(TAG, "原始输入框内容: $message")
        // 检查是否有有效内容
        val hasUserInput = message.isNotBlank()
        val hasScreenshot = pendingScreenshotBitmap != null && !pendingScreenshotBitmap!!.isRecycled

        // 如果都没有内容，显示错误并返回
        if (!hasUserInput && !hasScreenshot) {
            Log.e(TAG, "❌ 没有任何内容可发送 (无输入、无截图)")
            return
        }

        // 使用用户输入的内容作为最终文本（剪贴板内容已经包含在输入框中）
        val finalText = message
        Log.e(TAG, "最终要发送的文本内容: " + finalText.replace("\n", "\\n"))

        // 最终文本为空时不发送
        if (finalText.isBlank()) {
            Log.e(TAG, "❌ 最终文本为空，不发送消息")
            return
        }

        // 圈选完成后，下一次发送默认携带该图片。
        val currentPendingBitmap = if (chatWindowManager.isRegionScreenshotAttached()) {
            pendingScreenshotBitmap
        } else {
            null
        }
        Log.e(TAG, "待处理截图状态: ${currentPendingBitmap != null}, 是否已回收: ${currentPendingBitmap?.isRecycled}")
        if (currentPendingBitmap != null && !currentPendingBitmap.isRecycled) {
            Log.d(TAG, "✅ 携带待处理截图，尺寸: ${currentPendingBitmap.width}x${currentPendingBitmap.height}")
            pendingScreenshotBitmap = null
            chatWindowManager.setPendingScreenshotAvailable(false)
            sendImageMessageToAI(finalText, currentPendingBitmap)
        } else {
            if (currentPendingBitmap != null) {
                Log.w(TAG, "⚠️ 截图已回收，作为纯文本消息发送")
                pendingScreenshotBitmap = null
                chatWindowManager.setPendingScreenshotAvailable(false)
            }
            Log.d(TAG, "🔤 无待处理截图，仅发送文本")
            sendTextMessageToAI(finalText)
        }
        Log.d(TAG, "=== sendUserMessage 结束 ===")
    }

    /**
     * 发送文本消息到AI
     */
    private fun sendTextMessageToAI(message: String) {
        Log.d(TAG, "发送纯文本消息: $message")
        serviceScope.launch {
            var loadingMessageVisible = false
            try {
                // 获取当前会话ID
                val sessionId = sessionManager.getCurrentSessionId()
                if (sessionId.isEmpty()) {
                    Log.e(TAG, "会话ID为空，无法发送消息")
                    return@launch
                }

                // 获取当前应用包名和书籍名称
                val appPackage = textSelectionManager.getCurrentAppPackage()
                val bookName = textSelectionManager.getCurrentBookName()

                // 检查 API Key
                val apiKey = preferenceManager.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "API Key 未设置")
                    return@launch
                }
                Log.d(TAG, "API Key 已设置，长度: ${apiKey.length}")

                // 当前窗口只显示本次问题和回答。
                chatWindowManager.clearChatHistory()
                chatWindowManager.addUserMessage(message)

                // 添加加载动画
                chatWindowManager.addLoadingMessage(getString(R.string.ai_thinking_message))
                loadingMessageVisible = true

                // 发送消息到AI并获取响应
                val result = aiCommunicationManager.sendTextMessage(
                    sessionId = sessionId,
                    message = message,
                    appPackage = appPackage,
                    bookName = bookName
                )

                // 移除加载动画
                chatWindowManager.removeLastMessage()
                loadingMessageVisible = false

                when (result) {
                    is ApiResult.Success -> {
                        // 添加AI响应到聊天窗口
                        chatWindowManager.addAiMessage(result.data)

                        // 保存消息到数据库
                        val chatEntity = ChatEntity(
                            sessionId = sessionId,
                            bookName = bookName,
                            appPackage = appPackage,
                            userMessage = message,
                            aiResponse = result.data,
                            promptTemplate = "",
                            timestamp = System.currentTimeMillis()
                        )
                        try {
                            app.chatRepository.saveChatEntity(chatEntity)
                            app.chatRepository.updateSession(sessionId, bookName, appPackage)
                            sessionManager.markCurrentSessionHasMessages()
                        } catch (e: Exception) {
                            Log.e(TAG, "保存文字会话失败", e)
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "发送消息失败", result.exception)
                        chatWindowManager.addAiMessage("发送失败：${result.exception.message ?: "未知错误"}", true)
                    }
                    is ApiResult.NetworkError -> {
                        Log.e(TAG, "发送消息网络错误：${result.message}")
                        chatWindowManager.addAiMessage("网络错误：${result.message}", true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
                if (loadingMessageVisible) {
                    chatWindowManager.removeLastMessage()
                }
                chatWindowManager.addAiMessage("发送失败：${e.message ?: "未知错误"}", true)
            }
        }
    }

    /**
     * 发送图片消息到AI
     */
    private fun sendImageMessageToAI(message: String, bitmap: Bitmap) {
        Log.e(TAG, "sendImageMessageToAI 被调用，bitmap: $bitmap, isRecycled: ${bitmap.isRecycled}")
        Log.d(TAG, "=== sendImageMessageToAI 开始 ===")
        Log.d(TAG, "📤 发送图片消息，尺寸: ${bitmap.width}x${bitmap.height}")

        // 创建一个标记以跟踪是否已回收图片
        val bitmapRef = AtomicReference(bitmap)

        serviceScope.launch {
            var loadingMessageVisible = false
            try {
                // 检查bitmap是否已回收
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ 图片已被回收，无法发送")
                    return@launch
                }

                Log.d(TAG, "✅ 图片检查通过，继续处理")

                // 确保会话ID已初始化
                val sessionId = ensureSessionInitialized()

                // 获取应用和书籍信息
                val appPackage = sessionManager.getSanitizedAppPackage()
                val bookName = sessionManager.getSanitizedBookName()

                // 当前窗口只显示本次问题和回答。
                chatWindowManager.clearChatHistory()
                chatWindowManager.addUserMessage("$message 📸")

                // 添加加载消息
                chatWindowManager.addLoadingMessage(getString(R.string.analyzing_image_message))
                loadingMessageVisible = true

                Log.d(TAG, "🚀 调用AI服务分析图片...")

                // 发送图片消息到AI
                val result = aiCommunicationManager.sendImageMessage(
                    sessionId = sessionId,
                    message = message,
                    bitmap = bitmapRef.get(),  // 使用引用，确保最新状态
                    appPackage = appPackage,
                    bookName = bookName
                )

                Log.d(TAG, "✅ AI服务返回结果")

                // 移除加载消息
                chatWindowManager.removeLastMessage()
                loadingMessageVisible = false

                // 处理结果
                when (result) {
                    is com.readassist.network.ApiResult.Success -> {
                        sessionManager.markCurrentSessionHasMessages()
                        if (result.data.isBlank()) {
                            chatWindowManager.addAiMessage("🤖 AI分析结果为空，可能是图片内容无法识别", true)
                        } else {
                            chatWindowManager.addAiMessage(result.data)
                        }
                    }
                    is com.readassist.network.ApiResult.Error -> {
                        val errorMessage = result.exception.message ?: "未知错误"
                        chatWindowManager.addAiMessage("🚫 图片分析失败：$errorMessage", true)
                    }
                    is com.readassist.network.ApiResult.NetworkError -> {
                        chatWindowManager.addAiMessage("🌐 网络错误：${result.message}", true)
                    }
                }

                // 图片处理完毕，回收图片
                val currentBitmap = bitmapRef.getAndSet(null)
                if (currentBitmap != null && !currentBitmap.isRecycled) {
                    Log.d(TAG, "♻️ 图片处理完毕，回收图片")
                    currentBitmap.recycle()
                }

            } catch (e: Exception) {
                Log.e(TAG, "发送图片消息异常", e)
                if (loadingMessageVisible) {
                    chatWindowManager.removeLastMessage()
                }
                chatWindowManager.addAiMessage("❌ 发送失败：${e.message}", true)

                // 出错也需要回收图片
                val currentBitmap = bitmapRef.getAndSet(null)
                if (currentBitmap != null && !currentBitmap.isRecycled) {
                    Log.d(TAG, "♻️ 发生异常，回收图片")
                    currentBitmap.recycle()
                }
            }

            Log.d(TAG, "=== sendImageMessageToAI 结束 ===")
        }
    }

    /**
     * 确保会话ID已初始化
     */
    private suspend fun ensureSessionInitialized(): String {
        // 首先检查是否需要更新会话
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()

        // 更新会话信息
        withContext(Dispatchers.IO) {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }

        // 确保会话ID已初始化并返回
        return sessionManager.ensureSessionIdInitialized()
    }

    // === TextSelectionManager.TextSelectionCallbacks 实现 ===

    override fun onTextDetected(text: String, appPackage: String, bookName: String) {
        // 更新会话信息
        serviceScope.launch {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }
    }

    override fun onValidTextSelected(text: String, appPackage: String, bookName: String,
                                   bounds: Rect?, position: Pair<Int, Int>?) {
        // 保存选择位置信息到截屏管理器
        screenshotManager.setTextSelectionBounds(bounds)
        screenshotManager.setTextSelectionPosition(position)

        // 如果聊天窗口已显示，将选中文本导入到输入框
        if (chatWindowManager.isVisible()) {
            chatWindowManager.importTextToInputField(text)
        }

        // 更新会话信息
        serviceScope.launch {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }
    }

    override fun onInvalidTextSelected(text: String) {
        // 无效文本不做处理
    }

    override fun onTextSelectionActive() {
        // 移动悬浮按钮到选择区域附近
        floatingButtonManager.moveToSelectionArea(screenshotManager.getTextSelectionPosition())

        // 更新按钮外观
        floatingButtonManager.updateAppearanceForSelection(true)
    }

    override fun onTextSelectionInactive() {
        // 如果按钮在边缘，恢复到原始位置
        if (floatingButtonManager.isAtEdge()) {
            floatingButtonManager.restoreToEdge()
        }

        // 恢复按钮外观
        floatingButtonManager.updateAppearanceForSelection(false)
    }

    /**
     * 从辅助功能服务请求文本时的回调
     */
    override fun onRequestTextFromAccessibilityService() {
        Log.d(TAG, "从辅助功能服务请求文本")

        // 发送广播请求获取选中文本
        val intent = Intent("com.readassist.REQUEST_SELECTED_TEXT")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // 也从文本选择管理器中获取当前应用和书籍信息
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()

        // 更新会话管理器中的应用和书籍信息
        sessionManager.setCurrentApp(appPackage)
        sessionManager.setCurrentBook(bookName)

        Log.d(TAG, "📱 从文本选择管理器获取应用信息: 包名=$appPackage, 书籍=$bookName")
    }

    // === ScreenshotManager.ScreenshotCallbacks 实现 ===

    override fun onScreenshotStarted() {
        // 截屏时不隐藏悬浮按钮，保持可见状态
        // floatingButtonManager.setButtonVisibility(false)
    }

    override fun onScreenshotSuccess(bitmap: Bitmap) {
        Log.e(TAG, "📸 [统一流程] 截屏成功，保存到文件系统等待FileObserver触发。尺寸: ${bitmap.width}x${bitmap.height}")

        // 恢复UI状态
        if (!isVocabularyCaptureInProgress) {
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
        }

        Log.d(TAG, "✅ [统一流程] 截屏成功，PixelCopy已保存到系统目录，等待FileObserver触发弹窗")
    }

    override fun onScreenshotComplete(uri: Uri) {
        Log.e(TAG, "📸 onScreenshotComplete in FloatingWindowServiceNew called with URI: $uri")
        // 确保在这里调用 processScreenshot
        processScreenshot(uri)
    }

    override fun onScreenshotTaken(uri: Uri) {
        Log.e(TAG, "📸 收到截屏: $uri")
        // 这里可以添加额外的处理逻辑
    }

    private fun processScreenshot(uri: Uri) {
        Log.e(TAG, "📸 开始处理截屏: $uri")
        val isRegionCapture = isRegionCaptureInProgress
        val isVocabularyCapture = isVocabularyCaptureInProgress

        // 使用协程进行异步处理，避免阻塞主线程
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 智能重试机制：尝试解码图片，确保文件完整性
                val bitmap = decodeScreenshotWithRetry(uri, maxRetries = 3)

                if (bitmap == null) {
                    Log.e(TAG, "❌ 重试后仍无法解码截屏图片: $uri")
                    withContext(Dispatchers.Main) {
                        onScreenshotFailed("无法解码截屏图片")
                    }
                    return@launch
                }

                Log.e(TAG, "✅ 成功解码截屏图片: ${bitmap.width}x${bitmap.height}")

                withContext(Dispatchers.Main) {
                    // 在处理新截屏前，删除上一次的截屏文件
                    lastScreenshotFile?.let { file ->
                        if (file.exists()) {
                            val deleted = file.delete()
                            Log.e(TAG, "🗑️ 删除上一次截屏文件: ${file.absolutePath}, 结果: $deleted")
                        }
                    }

                    // 记录本次截屏文件
                    if (uri.scheme == "file") {
                        lastScreenshotFile = File(uri.path!!)
                    } else {
                        lastScreenshotFile = null // content uri 不处理
                    }

                    if (isRegionCapture && regionCapturePurpose == RegionCapturePurpose.DICTIONARY) {
                        processDictionaryRegionBitmap(bitmap)
                        return@withContext
                    }
                    if (isVocabularyCapture) {
                        processVocabularyPageBitmap(bitmap)
                        return@withContext
                    }

                    // 统一流程：通过FileObserver触发的弹窗逻辑
                    Log.e(TAG, "📢 [统一流程] FileObserver触发弹窗，开始完整处理")

                    // 保存或更新截屏图片
                    pendingScreenshotBitmap?.recycle()
                    pendingScreenshotBitmap = bitmap
                    chatWindowManager.setPendingScreenshotAvailable(true)

                    // 显示聊天窗口
                    floatingButtonManager.setButtonVisibility(true)
                    floatingButtonManager.restoreDefaultState()
                    chatWindowManager.showChatWindow()

                    // 设置输入框提示（不自动导入内容）
                    val selectedText = textSelectionManager.getLastDetectedText()
                    val hasSelectedText = selectedText.isNotEmpty() && selectedText.length > 10

                    val promptText = if (hasSelectedText) {
                        getString(R.string.selected_text_with_screenshot, selectedText)
                    } else {
                        getString(R.string.analyze_screenshot_prompt)
                    }

                    // 只设置提示文本，不导入到输入框内容
                    chatWindowManager.setInputHint(promptText)

                    // 区域截图是独占流程，不读取或自动勾选剪贴板。
                    if (!isRegionCapture) {
                        updateClipboardUIWithAutoSelection()
                    }

                    Log.d(TAG, "✅ [统一流程] FileObserver弹窗处理完成")
                    if (isRegionCapture) {
                        finishRegionCapture()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理截屏失败", e)
                withContext(Dispatchers.Main) {
                    onScreenshotFailed("截屏处理失败：${e.message}")
                }
            }
        }
    }

    private fun processDictionaryRegionBitmap(bitmap: Bitmap) {
        offlineOcrManager.recognize(
            bitmap = bitmap,
            language = preferenceManager.getOcrLanguage(),
            onSuccess = { text ->
                if (!bitmap.isRecycled) bitmap.recycle()
                finishRegionCapture()
                floatingButtonManager.setButtonVisibility(true)
                floatingButtonManager.restoreDefaultState()
                if (text.isBlank()) {
                    dictionaryWindowManager.show()
                    dictionaryWindowManager.showError(getString(R.string.dictionary_ocr_empty))
                } else {
                    showDictionaryAndLookup(text)
                }
            },
            onFailure = { error ->
                if (!bitmap.isRecycled) bitmap.recycle()
                finishRegionCapture()
                floatingButtonManager.setButtonVisibility(true)
                floatingButtonManager.restoreDefaultState()
                dictionaryWindowManager.show()
                dictionaryWindowManager.showError(
                    getString(R.string.dictionary_ocr_failed, error.message ?: error.javaClass.simpleName)
                )
            }
        )
    }

    private fun processVocabularyPageBitmap(bitmap: Bitmap) {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        offlineOcrManager.recognizeWords(
            bitmap = bitmap,
            onSuccess = { words ->
                if (!bitmap.isRecycled) bitmap.recycle()
                serviceScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        vocabularyHintSelector.select(words, preferenceManager.getVocabularyLevel())
                    }
                    withContext(Dispatchers.Main) {
                        finishVocabularyCapture()
                        floatingButtonManager.setButtonVisibility(true)
                        floatingButtonManager.restoreDefaultState()
                        result.onSuccess { hints ->
                            if (hints.isEmpty()) {
                                Toast.makeText(
                                    this@FloatingWindowServiceNew,
                                    getString(R.string.vocabulary_no_hints),
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                vocabularyOverlayManager.show(hints, sourceWidth, sourceHeight)
                                Toast.makeText(
                                    this@FloatingWindowServiceNew,
                                    resources.getQuantityString(
                                        R.plurals.vocabulary_hints_found,
                                        hints.size,
                                        hints.size
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }.onFailure { error ->
                            Toast.makeText(
                                this@FloatingWindowServiceNew,
                                getString(
                                    R.string.vocabulary_scan_failed,
                                    error.message ?: error.javaClass.simpleName
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            onFailure = { error ->
                if (!bitmap.isRecycled) bitmap.recycle()
                finishVocabularyCapture()
                floatingButtonManager.setButtonVisibility(true)
                floatingButtonManager.restoreDefaultState()
                Toast.makeText(
                    this,
                    getString(R.string.vocabulary_scan_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    /**
     * 解码截屏图片
     * 优先使用SAF访问，确保权限兼容性
     * 针对Supernote设备增加特殊处理
     */
    private suspend fun decodeScreenshotWithRetry(uri: Uri, maxRetries: Int = 2): Bitmap? {
        val isSupernote = DeviceUtils.getDeviceType() == DeviceType.SUPERNOTE
        val actualMaxRetries = if (isSupernote) maxRetries + 2 else maxRetries // Supernote设备增加重试次数

        repeat(actualMaxRetries) { attempt ->
            try {
                Log.d(TAG, "尝试解码截屏图片 (第${attempt + 1}次): $uri")

                val bitmap = when {
                    // 对于file://类型的URI，优先使用SAF访问
                    uri.scheme == "file" -> {
                        val filePath = uri.path
                        if (filePath != null) {
                            Log.d(TAG, "检测到file:// URI，使用SAF访问: $filePath")
                            decodeFromFilePathViaSAF(filePath)
                        } else {
                            null
                        }
                    }
                    // 对于content://类型的URI，直接使用
                    uri.scheme == "content" -> {
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }
                    }
                    else -> {
                        Log.w(TAG, "未知的URI scheme: ${uri.scheme}")
                        null
                    }
                }

                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    Log.d(TAG, "✅ 第${attempt + 1}次尝试成功解码: ${bitmap.width}x${bitmap.height}")

                    // 对于Supernote设备，暂时禁用质量验证，因为可能误判有效截屏
                    // TODO: 后续可以根据实际使用情况优化验证逻辑
                    if (isSupernote) {
                        Log.d(TAG, "🔴 Supernote设备截屏，跳过质量验证以避免误判")
                    }
                    return bitmap
                } else {
                    Log.w(TAG, "第${attempt + 1}次尝试解码失败，图片可能未完全写入")
                }

            } catch (e: Exception) {
                Log.w(TAG, "第${attempt + 1}次尝试解码异常: ${e.message}")

                // 对于Supernote设备，记录更详细的错误信息
                if (isSupernote) {
                    Log.e(TAG, "🔴 Supernote设备解码异常详情", e)
                }
            }

            // 如果不是最后一次尝试，等待短时间再重试
            if (attempt < actualMaxRetries - 1) {
                val delayTime = if (isSupernote) 200L + (attempt * 100L) else 100L // Supernote设备延长等待时间
                Log.d(TAG, "等待 ${delayTime}ms 后重试...")
                delay(delayTime)
            }
        }

        Log.e(TAG, "❌ 所有重试尝试都失败了")
        return null
    }

    /**
     * 验证Supernote截屏是否有效
     * 修复：放宽验证条件，避免误判有效截屏
     */
    private fun isValidSupernoteScreenshot(bitmap: Bitmap): Boolean {
        try {
            // 检查图片尺寸是否合理（Supernote通常是1920x2560或类似分辨率）
            if (bitmap.width < 50 || bitmap.height < 50) {
                Log.w(TAG, "🔴 图片尺寸过小: ${bitmap.width}x${bitmap.height}")
                return false
            }

            // 更宽松的验证：只检查是否为完全空白（全黑或全白）
            // 对于Supernote设备，即使是单色背景也可能是有效内容
            val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val corner1 = bitmap.getPixel(10, 10)
            val corner2 = bitmap.getPixel(bitmap.width - 10, 10)
            val corner3 = bitmap.getPixel(10, bitmap.height - 10)
            val corner4 = bitmap.getPixel(bitmap.width - 10, bitmap.height - 10)

            // 只有当所有采样点都是相同颜色且为纯黑或纯白时，才认为是无效截屏
            val allPixels = listOf(centerPixel, corner1, corner2, corner3, corner4)
            val isAllSameColor = allPixels.all { it == centerPixel }
            val isPureBlackOrWhite = centerPixel == Color.BLACK || centerPixel == Color.WHITE

            if (isAllSameColor && isPureBlackOrWhite) {
                Log.w(TAG, "🔴 疑似无效截屏（所有采样点都是相同颜色: ${String.format("#%08X", centerPixel)}）")
                return false
            }

            Log.d(TAG, "✅ Supernote截屏质量验证通过")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "验证Supernote截屏时发生异常", e)
            return true // 如果验证失败，假设图片有效
        }
    }

    /**
     * 从文件路径通过SAF解码图片
     */
    private fun decodeFromFilePathViaSAF(filePath: String): Bitmap? {
        return try {
            Log.d(TAG, "通过SAF访问截屏文件: $filePath")

            val deviceScreenshotManager = screenshotManager.getDeviceScreenshotManager()
            val fileName = java.io.File(filePath).name

            // 根据文件路径确定设备类型
            val deviceConfig = when {
                filePath.contains("/storage/emulated/0/SCREENSHOT/") -> {
                    deviceScreenshotManager.getScreenshotDirectoryConfigs()
                        .find { it.deviceType == com.readassist.utils.DeviceType.SUPERNOTE }
                }
                filePath.contains("/storage/emulated/0/iReader/saveImage/") -> {
                    deviceScreenshotManager.getScreenshotDirectoryConfigs()
                        .find { it.deviceType == com.readassist.utils.DeviceType.IREADER }
                }
                else -> null
            }

            if (deviceConfig != null && deviceScreenshotManager.hasDirectoryAccess(deviceConfig)) {
                val directory = deviceScreenshotManager.getScreenshotDirectory(deviceConfig)

                directory?.listFiles()?.forEach { file ->
                    if (file.name == fileName) {
                        Log.d(TAG, "通过SAF找到文件: ${file.name}")
                        file.uri?.let { uri ->
                            return contentResolver.openInputStream(uri)?.use { inputStream ->
                                BitmapFactory.decodeStream(inputStream)
                            }
                        }
                    }
                }
            }

            Log.w(TAG, "SAF无法访问文件: $fileName")
            null
        } catch (e: Exception) {
            Log.w(TAG, "SAF访问异常: ${e.message}")
            null
        }
    }





    override fun onScreenshotFailed(error: String) {
        Log.e(TAG, "❌ 截屏失败: $error")
        if (isVocabularyCaptureInProgress) {
            if (error == getString(R.string.need_screenshot_permission)) {
                Log.d(TAG, "生词扫描等待系统截屏授权")
                return
            }
            finishVocabularyCapture()
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
            Toast.makeText(
                this,
                getString(R.string.vocabulary_scan_failed, error),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val failedPurpose = if (isRegionCaptureInProgress) regionCapturePurpose else RegionCapturePurpose.AI

        // 请求系统截屏授权是区域截图流程的中间状态，不是失败终点。
        // 保持独占状态和临时目录监控，授权返回后继续使用已保存的裁剪范围。
        if (isRegionCaptureInProgress && error == getString(R.string.need_screenshot_permission)) {
            Log.d(TAG, "区域截图等待系统授权，保持区域状态和截图监控")
            return
        }

        finishRegionCapture()

        if (failedPurpose == RegionCapturePurpose.DICTIONARY) {
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
            dictionaryWindowManager.show()
            dictionaryWindowManager.showError("截屏失败：$error")
            return
        }

        // 恢复UI状态
        floatingButtonManager.restoreDefaultState()

        // 根据错误类型提供不同的处理方式
        val errorMessage = when {
            error.contains("无法解码") -> {
                Log.e(TAG, "🔴 Supernote设备截屏解码失败，可能是设备兼容性问题")
                "截屏解码失败，这可能是设备兼容性问题。建议重试或重启应用。"
            }
            error.contains("权限") -> {
                Log.e(TAG, "🔐 截屏权限问题")
                "需要截屏权限才能继续。请点击重新授权。"
            }
            error.contains("超时") -> {
                Log.e(TAG, "⏰ 截屏超时")
                "截屏超时，请检查设备性能或重试。"
            }
            else -> {
                Log.e(TAG, "❓ 其他截屏错误: $error")
                "截屏失败：$error"
            }
        }

        // 显示错误消息并提供操作建议
        chatWindowManager.showChatWindow()
        chatWindowManager.addSystemMessage(errorMessage)

        // 对于Supernote设备的特殊处理
        if (DeviceUtils.getDeviceType() == DeviceType.SUPERNOTE && error.contains("无法解码")) {
            chatWindowManager.addSystemMessage("💡 Supernote设备建议：\n1. 尝试重新点击悬浮按钮\n2. 检查是否有足够的存储空间\n3. 重启应用后重试")
        }

        // 显示Toast提示（简短版本）
        Toast.makeText(this, "截屏失败，请查看对话窗口了解详情", Toast.LENGTH_SHORT).show()
    }

    override fun onScreenshotCancelled() {
        Log.e(TAG, "📸 截屏已取消")
        if (isVocabularyCaptureInProgress) {
            finishVocabularyCapture()
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
            return
        }
        val cancelledPurpose = if (isRegionCaptureInProgress) regionCapturePurpose else RegionCapturePurpose.AI
        finishRegionCapture()
        // 恢复界面显示
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        if (cancelledPurpose == RegionCapturePurpose.DICTIONARY) {
            dictionaryWindowManager.show()
            dictionaryWindowManager.showError(getString(R.string.region_screenshot_cancelled))
        }
    }

    override fun onScreenshotMessage(message: String) {
        Log.e(TAG, "📸 截屏消息: $message")
        chatWindowManager.addSystemMessage(message)
    }

    override fun onPermissionRequesting() {
        Log.e(TAG, "🔐 正在请求截屏权限...")
        if (isVocabularyCaptureInProgress ||
            (isRegionCaptureInProgress && regionCapturePurpose == RegionCapturePurpose.DICTIONARY)
        ) {
            Toast.makeText(this, getString(R.string.requesting_screenshot_permission), Toast.LENGTH_SHORT).show()
        } else {
            chatWindowManager.showChatWindow()
            chatWindowManager.addLoadingMessage(getString(R.string.requesting_screenshot_permission))
        }
    }

    override fun onPermissionGranted() {
        Log.e(TAG, "✅ 截屏权限已授予")
        if (isVocabularyCaptureInProgress) {
            chatWindowManager.hideChatWindow()
            dictionaryWindowManager.hide()
            vocabularyOverlayManager.hide()
            floatingButtonManager.setButtonVisibility(false)
            serviceScope.launch {
                delay(350)
                screenshotManager.performScreenshot()
            }
        } else if (isRegionCaptureInProgress) {
            chatWindowManager.removeLastMessage()
            // 授权界面返回后先移除所有 ReadAssist 覆盖层，再按已保存的区域截屏。
            chatWindowManager.hideChatWindow()
            floatingButtonManager.setButtonVisibility(false)
            serviceScope.launch {
                delay(350)
                screenshotManager.performScreenshot()
            }
        } else {
            chatWindowManager.removeLastMessage()
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
        }
    }

    override fun onPermissionDenied() {
        Log.e(TAG, "❌ 截屏权限被拒绝")
        if (isVocabularyCaptureInProgress) {
            finishVocabularyCapture()
            floatingButtonManager.setButtonVisibility(true)
            floatingButtonManager.restoreDefaultState()
            Toast.makeText(this, getString(R.string.screenshot_permission_not_granted), Toast.LENGTH_LONG).show()
            return
        }
        val deniedPurpose = if (isRegionCaptureInProgress) regionCapturePurpose else RegionCapturePurpose.AI
        finishRegionCapture()
        // 隐藏加载消息
        chatWindowManager.removeLastMessage()

        // 恢复UI
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        if (deniedPurpose == RegionCapturePurpose.DICTIONARY) {
            dictionaryWindowManager.show()
            dictionaryWindowManager.showError(getString(R.string.screenshot_permission_not_granted))
        }
    }

    // === ChatWindowManager.ChatWindowCallbacks 实现 ===

    /**
     * 聊天窗口显示回调
     */
    override fun onChatWindowShown() {
        Log.d(TAG, "聊天窗口已显示")
        // 注册勾选项监听
        chatWindowManager.setOnCheckStateChangedListener(object : ChatWindowManager.OnCheckStateChangedListener {
            override fun onCheckStateChanged() {
                updateInputHintByCheckState()
            }
        })
        serviceScope.launch {
            // 从偏好设置读取之前保存的应用和书籍信息
            val savedAppPackage = app.preferenceManager.getString("current_app_package", "com.readassist")
            val savedBookName = app.preferenceManager.getString("current_book_name", "阅读笔记")

            // 只有在保存的值为默认值时，才尝试从文本选择管理器获取最新值
            val currentAppPackage = if (savedAppPackage == "com.readassist") {
                textSelectionManager.getCurrentAppPackage()
            } else {
                savedAppPackage
            }

            val currentBookName = if (savedBookName == "阅读笔记") {
                textSelectionManager.getCurrentBookName()
            } else {
                savedBookName
            }

            Log.d(TAG, "📱 聊天窗口显示时的应用信息: 包名=$currentAppPackage, 书籍=$currentBookName")

            // 保存应用和书籍信息到偏好设置
            app.preferenceManager.setString("current_app_package", currentAppPackage)
            app.preferenceManager.setString("current_book_name", currentBookName)

            // 更新会话管理器中的应用和书籍信息
            sessionManager.setCurrentApp(currentAppPackage)
            sessionManager.setCurrentBook(currentBookName)

            // 强制更新会话状态（重要修改）
            val sessionChanged = sessionManager.updateSessionIfNeeded(currentAppPackage, currentBookName)

            // 无论会话是否变更，都要确保会话ID已初始化（重要修改）
            val sessionId = sessionManager.ensureSessionIdInitialized()
            Log.d(TAG, "✅ 聊天窗口显示时使用的会话ID: $sessionId")

            if (sessionChanged) {
                Log.d(TAG, "会话已变更，加载新会话消息")
                loadChatHistory()
            } else {
                Log.d(TAG, "会话未变更，继续使用当前会话")
            }

            // 更新聊天窗口标题
            chatWindowManager.updateWindowTitle()

            updateClipboardUI()

            val hasPendingScreenshot = pendingScreenshotBitmap != null && !pendingScreenshotBitmap!!.isRecycled
            chatWindowManager.setPendingScreenshotAvailable(hasPendingScreenshot)

            // 新增：根据勾选项动态设置输入框hint
            updateInputHintByCheckState()
        }
    }

    /**
     * 加载聊天历史记录
     */
    private suspend fun loadChatHistory() {
        try {
            Log.d(TAG, "开始加载聊天历史记录")

            // 获取当前会话ID
            val sessionId = sessionManager.getCurrentSessionId()
            if (sessionId.isEmpty()) {
                Log.d(TAG, "会话ID为空，无法加载历史记录")
                return
            }

            // 从数据库加载消息
            val messagesFlow = app.chatRepository.getChatMessages(sessionId)
            val messageList = withContext(Dispatchers.IO) {
                messagesFlow.first()
            }

            Log.d(TAG, "加载了 ${messageList.size} 条历史消息")

            // 转换为聊天项
            val chatItems = messageList.takeLast(1).map { entity ->
                listOf(
                    // 用户消息
                    ChatItem(
                        userMessage = entity.userMessage,
                        aiMessage = "",
                        isUserMessage = true,
                        isLoading = false,
                        isError = false
                    ),
                    // AI 回复
                    ChatItem(
                        userMessage = "",
                        aiMessage = entity.aiResponse,
                        isUserMessage = false,
                        isLoading = false,
                        isError = false
                    )
                )
            }.flatten()

            // 更新聊天窗口
            withContext(Dispatchers.Main) {
                if (chatItems.isEmpty()) {
                    // 如果没有历史记录，添加欢迎消息
                    chatWindowManager.clearChatHistory()
                } else {
                    // 更新聊天历史
                    chatWindowManager.updateChatHistory(chatItems)
                }
            }

            Log.d(TAG, "聊天历史记录加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "加载聊天历史失败", e)
        }
    }

    override fun onChatWindowHidden() {
        // 如果按钮不在边缘，移动到边缘
        if (!floatingButtonManager.isAtEdge() && !floatingButtonManager.isMoved()) {
            floatingButtonManager.restoreToEdge()
        }
    }

    override fun onMessageSend(message: String) {
        Log.e(TAG, "!!! ✅ FloatingWindowServiceNew.onMessageSend CALLED with message: $message")
        Log.e(TAG, "输入框内容: $message")
        // 统一调用 sendUserMessage，由它来判断是发送图片还是文本
        sendUserMessage(message)
    }

    override fun onExportCurrentConversation(question: String, answer: String) {
        val intent = com.readassist.ui.MarkdownExportActivity.forCurrentConversation(
            context = this,
            bookName = textSelectionManager.getCurrentBookName(),
            appPackage = textSelectionManager.getCurrentAppPackage(),
            question = question,
            answer = answer
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onNewChatButtonClick() {
        pendingScreenshotBitmap?.takeIf { !it.isRecycled }?.recycle()
        pendingScreenshotBitmap = null
        chatWindowManager.setPendingScreenshotAvailable(false)
        chatWindowManager.clearInputText()

        // 请求新会话
        sessionManager.requestNewSession()

        // 获取当前应用包名和书籍名称
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()

        // 立即更新会话管理器中的应用和书籍信息
        sessionManager.setCurrentApp(appPackage)
        sessionManager.setCurrentBook(bookName)

        // 生成新的会话ID并记录
        serviceScope.launch {
            val newSessionId = sessionManager.ensureSessionIdInitialized()
            Log.d(TAG, "🆕 创建了新会话ID: $newSessionId, 应用=$appPackage, 书籍=$bookName")

            // 记录分解情况以便调试
            app.chatRepository.logSessionIdParts(newSessionId)
        }

        // 清空聊天历史
        chatWindowManager.clearChatHistory()
    }

    private fun startRegionCapture(purpose: RegionCapturePurpose) {
        if (isRegionCaptureInProgress || isVocabularyCaptureInProgress) return
        regionCapturePurpose = purpose
        isRegionCaptureInProgress = true
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        aiSelectionStartedAt = 0L
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null
        isDictionaryTextSelectionPending = false
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        floatingButtonManager.setDictionaryWaiting(false)
        screenshotManager.startMonitoring()
        chatWindowManager.hideChatWindow()
        dictionaryWindowManager.hide()
        floatingButtonManager.setButtonVisibility(false)
        regionSelectionManager.show()
    }

    override fun onRegionScreenshotAttachmentChanged(attached: Boolean) {
        if (attached) return
        pendingScreenshotBitmap?.takeIf { !it.isRecycled }?.recycle()
        pendingScreenshotBitmap = null
        chatWindowManager.setPendingScreenshotAvailable(false)
        updateInputHintByCheckState()
    }

    override fun onRegionSelected(selection: RegionSelection) {
        serviceScope.launch {
            // Give the E-ink screen enough time to remove the selection overlay.
            delay(350)
            val cropPaddingDp = if (regionCapturePurpose == RegionCapturePurpose.DICTIONARY) 0 else 16
            val cropMask = if (regionCapturePurpose == RegionCapturePurpose.DICTIONARY) {
                selection.path
            } else {
                null
            }
            screenshotManager.performScreenshot(selection.bounds, cropPaddingDp, cropMask)
        }
    }

    override fun onRegionSelectionCancelled() {
        finishRegionCapture()
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
    }

    override fun onConfigStatusClick(platform: com.readassist.model.AiPlatform?) {
        if (platform != null) {
            showApiKeyInputDialog(platform)
        } else {
            showQuickConfigurationDialog()
        }
    }

    override fun onShowApiKeyDialog(platform: com.readassist.model.AiPlatform) {
        showApiKeyInputDialog(platform)
    }

    /**
     * 实现FloatingButtonCallbacks接口的方法
     */
    override fun onAiButtonClick() {
        handleFloatingButtonClick()
    }

    override fun onAiButtonDoubleClick() {
        if (!aiConfigurationManager.isConfigurationValid()) {
            showConfigurationRequiredDialog()
            return
        }
        when (preferenceManager.getAiCaptureMode()) {
            AiCaptureMode.TEXT_SELECTION -> startAiTextSelection()
            AiCaptureMode.REGION_SCREENSHOT -> startRegionCapture(RegionCapturePurpose.AI)
        }
    }

    override fun onAiCaptureModeToggleRequested() {
        val mode = preferenceManager.getAiCaptureMode().next()
        preferenceManager.setAiCaptureMode(mode)
        chatWindowManager.updateAiCaptureModeButton(mode)
        Toast.makeText(
            this,
            when (mode) {
                AiCaptureMode.TEXT_SELECTION -> {
                    if (DeviceUtils.getDeviceType() == DeviceType.SUPERNOTE) {
                        R.string.supernote_text_selection_manual_required
                    } else {
                        R.string.ai_capture_mode_text_selected
                    }
                }
                AiCaptureMode.REGION_SCREENSHOT -> R.string.ai_capture_mode_region_selected
            },
            if (mode == AiCaptureMode.TEXT_SELECTION &&
                DeviceUtils.getDeviceType() == DeviceType.SUPERNOTE
            ) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun startAiTextSelection() {
        preferenceManager.setAiCaptureMode(AiCaptureMode.TEXT_SELECTION)
        isDictionaryTextSelectionPending = false
        floatingButtonManager.setDictionaryWaiting(false)
        dictionaryWindowManager.hide()
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        beginAiTextSelection()
        if (DeviceUtils.getDeviceType() == DeviceType.SUPERNOTE) {
            Toast.makeText(
                this,
                getString(R.string.supernote_text_selection_manual_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun beginAiTextSelection() {
        aiSelectionStartedAt = System.currentTimeMillis()
        aiCopySignalSeen = false
        isAiTextSelectionPending = true
        chatWindowManager.hideChatWindow()
        Toast.makeText(this, getString(R.string.ai_wait_selection), Toast.LENGTH_LONG).show()
        Log.d(TAG, "🤖 AI 文字选择开始时间: $aiSelectionStartedAt")
    }

    override fun onDictionaryButtonClick() {
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        aiSelectionStartedAt = 0L
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null
        isDictionaryTextSelectionPending = false
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        floatingButtonManager.setDictionaryWaiting(false)
        chatWindowManager.hideChatWindow()
        dictionaryWindowManager.show(showKeyboard = false)
        if (offlineDictionaryManager.isImporting) {
            dictionaryWindowManager.showError(getString(R.string.dictionary_import_in_progress))
        } else if (offlineDictionaryManager.listDictionaries().isEmpty()) {
            dictionaryWindowManager.showError(getString(R.string.dictionary_no_data))
        }
        pendingDictionaryClipboardRead = Runnable {
            pendingDictionaryClipboardRead = null
            val text = readClipboardSnapshot().second
            if (!text.isNullOrBlank() && dictionaryWindowManager.setQueryIfEmpty(text)) {
                Log.d(TAG, "📖 已将当前剪贴板文字填入查词框，长度=${text.trim().length}")
            }
        }.also { clipboardAccessHandler.postDelayed(it, 250) }
    }

    override fun onDictionaryButtonDoubleClick() {
        when (preferenceManager.getDictionaryCaptureMode()) {
            DictionaryCaptureMode.REGION_OCR -> startDictionaryRegionOcr()
            DictionaryCaptureMode.TEXT_SELECTION -> startDictionaryTextSelection()
            DictionaryCaptureMode.VOCABULARY_HINTS -> startVocabularyPageScan()
        }
    }

    override fun onDictionaryTextSelectionRequested() {
        startDictionaryTextSelection()
    }

    override fun onDictionaryRegionOcrRequested() {
        startDictionaryRegionOcr()
    }

    override fun onVocabularyHintsRequested() {
        startVocabularyPageScan()
    }

    override fun onVocabularyHintsClearRequested() {
        vocabularyOverlayManager.hide()
        Toast.makeText(this, getString(R.string.vocabulary_hints_cleared), Toast.LENGTH_SHORT).show()
    }

    private fun startDictionaryTextSelection() {
        preferenceManager.setDictionaryCaptureMode(DictionaryCaptureMode.TEXT_SELECTION)
        floatingButtonManager.refreshDictionaryModeLabel()
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        chatWindowManager.hideChatWindow()
        dictionarySelectionStartedAt = System.currentTimeMillis()
        beginDictionaryTextSelection(readClipboardSnapshot())
    }

    private fun beginDictionaryTextSelection(baseline: Pair<ClipboardSnapshot?, String?>) {
        dictionaryClipboardBaseline = baseline.first
        dictionaryCopySignalSeen = false
        isDictionaryTextSelectionPending = true
        dictionaryWindowManager.hide()
        floatingButtonManager.setDictionaryWaiting(true)
        Toast.makeText(this, getString(R.string.dictionary_wait_selection), Toast.LENGTH_LONG).show()
        Log.d(TAG, "📖 已记录本次查词的剪贴板基线: ${baseline.first}")
    }

    private fun openDictionaryWindowAndReadClipboard() {
        if (!isDictionaryTextSelectionPending) return
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        dictionaryWindowManager.show(showKeyboard = false)
        pendingDictionaryClipboardRead = Runnable {
            pendingDictionaryClipboardRead = null
            if (!isDictionaryTextSelectionPending) return@Runnable
            val (snapshot, text) = readClipboardSnapshot()
            val changed = snapshot != null && snapshot != dictionaryClipboardBaseline &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    snapshot.timestamp >= dictionarySelectionStartedAt)
            if (changed && !text.isNullOrBlank()) {
                dictionaryCopySignalSeen = false
                showDictionaryAndLookup(text)
            } else {
                dictionaryCopySignalSeen = false
                isDictionaryTextSelectionPending = false
                floatingButtonManager.setDictionaryWaiting(false)
                dictionaryWindowManager.hide()
                Log.d(TAG, "📖 剪贴板未变化，按取消处理")
            }
        }.also { clipboardAccessHandler.postDelayed(it, 250) }
    }

    private fun openAiWindowAndReadClipboard() {
        if (!isAiTextSelectionPending) return
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        chatWindowManager.showChatWindow()
        pendingAiClipboardRead = Runnable {
            pendingAiClipboardRead = null
            if (!isAiTextSelectionPending) return@Runnable
            val (snapshot, text) = readClipboardSnapshot()
            val changed = snapshot != null &&
                snapshot.timestamp >= aiSelectionStartedAt &&
                aiSelectionStartedAt > 0L
            if (changed && !text.isNullOrBlank()) {
                showAiWithSelectedText(text)
            } else {
                isAiTextSelectionPending = false
                aiCopySignalSeen = false
                aiSelectionStartedAt = 0L
                chatWindowManager.hideChatWindow()
                Log.d(TAG, "🤖 剪贴板未变化，按取消处理")
            }
        }.also { clipboardAccessHandler.postDelayed(it, 250) }
    }

    private fun showAiWithSelectedText(text: String) {
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        aiSelectionStartedAt = 0L
        if (!chatWindowManager.isShowing()) {
            chatWindowManager.showChatWindow()
        }
        chatWindowManager.importTextToInputField(text)
        updateClipboardUI()
    }

    private fun readClipboardSnapshot(): Pair<ClipboardSnapshot?, String?> {
        val clip = systemClipboardManager.primaryClip ?: return null to null
        if (clip.itemCount == 0) return null to null
        val text = clip.getItemAt(0).coerceToText(this).toString()
        val timestamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            clip.description.timestamp
        } else {
            -1L
        }
        return ClipboardSnapshot(timestamp, text.hashCode()) to text
    }

    private fun startDictionaryRegionOcr() {
        preferenceManager.setDictionaryCaptureMode(DictionaryCaptureMode.REGION_OCR)
        floatingButtonManager.refreshDictionaryModeLabel()
        startRegionCapture(RegionCapturePurpose.DICTIONARY)
    }

    private fun startVocabularyPageScan() {
        if (isRegionCaptureInProgress || isVocabularyCaptureInProgress) return
        preferenceManager.setDictionaryCaptureMode(DictionaryCaptureMode.VOCABULARY_HINTS)
        floatingButtonManager.refreshDictionaryModeLabel()
        isVocabularyCaptureInProgress = true
        isAiTextSelectionPending = false
        aiCopySignalSeen = false
        aiSelectionStartedAt = 0L
        pendingAiClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingAiClipboardRead = null
        isDictionaryTextSelectionPending = false
        pendingDictionaryClipboardRead?.let(clipboardAccessHandler::removeCallbacks)
        pendingDictionaryClipboardRead = null
        floatingButtonManager.setDictionaryWaiting(false)
        vocabularyOverlayManager.hide()
        screenshotManager.startMonitoring()
        chatWindowManager.hideChatWindow()
        dictionaryWindowManager.hide()
        floatingButtonManager.setButtonVisibility(false)
        Toast.makeText(this, getString(R.string.vocabulary_scanning), Toast.LENGTH_SHORT).show()
        serviceScope.launch {
            delay(350)
            screenshotManager.performScreenshot()
        }
    }

    override fun onDictionaryLookupRequested(query: String) {
        if (offlineDictionaryManager.isImporting) {
            dictionaryWindowManager.showError(getString(R.string.dictionary_import_in_progress))
            return
        }
        if (offlineDictionaryManager.listDictionaries().isEmpty()) {
            dictionaryWindowManager.showError(getString(R.string.dictionary_no_data))
            return
        }
        dictionaryWindowManager.showLoading(query)
        serviceScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { offlineDictionaryManager.lookup(query) }
            }
            result.onSuccess { dictionaryWindowManager.showResults(query, it) }
                .onFailure { dictionaryWindowManager.showError(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun showDictionaryAndLookup(query: String) {
        isDictionaryTextSelectionPending = false
        floatingButtonManager.setDictionaryWaiting(false)
        dictionaryWindowManager.show(query, requestLookup = true)
    }

    override fun onHistoryButtonClick() {
        chatWindowManager.hideChatWindow()
        val intent = Intent(this, com.readassist.ui.HistoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private suspend fun processScreenshotFromUri(uri: Uri) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
            }
            onScreenshotSuccess(bitmap)

            // Clean up the screenshot file after processing
            withContext(Dispatchers.IO) {
                try {
                    val rowsDeleted = contentResolver.delete(uri, null, null)
                    if (rowsDeleted > 0) {
                        Log.d(TAG, "Screenshot file deleted successfully: $uri")
                    } else {
                        Log.d(TAG, "Screenshot file not found for deletion: $uri")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to delete screenshot due to security exception: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete screenshot: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process screenshot from URI: $uri", e)
            onScreenshotFailed("Failed to load screenshot from URI.")
        }
    }

    // 获取当天剪贴板内容（如无返回null）
    private fun getTodayClipboardContent(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            // 判断是否为今天的内容（简单判断：内容非空且不为默认提示）
            if (text.isNotBlank()) {
                // 返回完整内容
                return text
            }
        }
        return null
    }

    // 获取用于UI显示的剪贴板内容（截断显示）
    private fun getClipboardContentForDisplay(): String? {
        val fullContent = getTodayClipboardContent()
        return if (fullContent != null && fullContent.length > 30) {
            fullContent.take(30) + "..."
        } else {
            fullContent
        }
    }

    // 更新剪贴板UI显示
    private fun updateClipboardUI() {
        // 只更新UI显示内容，不进行自动勾选检测
        val displayContent = getClipboardContentForDisplay()
        chatWindowManager.updateClipboardInfo(displayContent)
    }

    // 检查剪贴板和截屏内容变化并自动勾选（独立方法，只在需要时调用）
    private fun updateClipboardUIWithAutoSelection() {
        // 先检查剪贴板和截屏内容是否有变化，并根据情况自动勾选
        checkAndUpdateSelectionState()

        // 再更新UI显示内容
        val displayContent = getClipboardContentForDisplay()
        chatWindowManager.updateClipboardInfo(displayContent)
    }

    /**
     * 检查剪贴板和截屏内容变化并自动勾选相应选项
     */
    private fun checkAndUpdateSelectionState() {
        var hasNewClipboard = false

        // 检查剪贴板内容变化
        val currentClipboardContent = getTodayClipboardContent()
        Log.d(TAG, "🔍 [调试] 当前剪贴板内容: '${currentClipboardContent?.take(50)}${if (currentClipboardContent?.length ?: 0 > 50) "..." else ""}'")

        if (currentClipboardContent != null) {
            val currentHash = currentClipboardContent.hashCode().toString()
            val lastHash = preferenceManager.getLastClipboardHash()

            Log.d(TAG, "🔍 [调试] 当前哈希值: $currentHash, 上次哈希值: $lastHash")

            if (currentHash != lastHash) {
                Log.d(TAG, "✅ [调试] 剪贴板内容有变化，将自动勾选")
                hasNewClipboard = true
                // 保存新的哈希值
                preferenceManager.setLastClipboardHash(currentHash)
                Log.d(TAG, "🔍 [调试] 已保存新的哈希值: $currentHash")
            } else {
                Log.d(TAG, "ℹ️ [调试] 剪贴板内容无变化，不勾选")
            }
        } else {
            Log.d(TAG, "⚠️ [调试] 剪贴板内容为空或获取失败")
        }

        // 根据检测结果自动勾选相应选项
        Log.d(TAG, "🔧 [调试] 执行自动勾选操作 - 剪贴板: $hasNewClipboard")

        // 记录勾选前的状态
        val beforeState = chatWindowManager.isSendClipboardChecked()
        Log.d(TAG, "🔧 [调试] 勾选前状态: $beforeState")

        chatWindowManager.setSendClipboardChecked(hasNewClipboard)

        // 记录勾选后的状态
        val afterState = chatWindowManager.isSendClipboardChecked()
        Log.d(TAG, "🔧 [调试] 勾选后状态: $afterState")

        Log.d(TAG, "📊 [调试] 最终自动勾选状态 - 剪贴板: $hasNewClipboard")
    }

    private fun updateInputHintByCheckState() {
        val hasScreenshot = pendingScreenshotBitmap != null && !pendingScreenshotBitmap!!.isRecycled
        val hint = if (hasScreenshot) {
            getString(R.string.analyze_screenshot_prompt)
        } else {
            getString(R.string.input_hint_long)
        }

        // 只设置提示文本，不自动导入内容
        chatWindowManager.setInputHint(hint)
    }

    /**
     * 开始新对话
     */
    private fun startNewChat() {
        sessionManager.requestNewSession()
        chatWindowManager.clearChatHistory()
    }

    /**
     * 显示AI API Key对话框
     */
    private fun showApiKeyDialog(platform: com.readassist.model.AiPlatform) {
        chatWindowManager.showApiKeyInputDialog(platform)
    }

    private fun finishRegionCapture() {
        if (!isRegionCaptureInProgress) return

        isRegionCaptureInProgress = false
        screenshotManager.stopMonitoring()
    }

    private fun finishVocabularyCapture() {
        if (!isVocabularyCaptureInProgress) return
        isVocabularyCaptureInProgress = false
        screenshotManager.stopMonitoring()
    }

}
