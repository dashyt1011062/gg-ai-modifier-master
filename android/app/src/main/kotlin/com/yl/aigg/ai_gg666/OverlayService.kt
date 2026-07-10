package com.yl.aigg.ai_gg666

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class OverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1
        private const val SEARCH_RESULT_PAGE_SIZE = 50
        private const val PREF_FILTER_SYSTEM = "is_sys"
        private const val PREF_FILTER_LINUX = "is_elf"
        var isRunning = false
        @Volatile private var activeInstance: OverlayService? = null
        @Volatile private var uiButtonClicked = false

        fun setLuaPanelVisible(visible: Boolean) {
            val service = activeInstance ?: return
            service.handler.post {
                if (visible) service.showLastOrMainMenu() else service.closePanel()
            }
        }

        fun isLuaPanelVisible(): Boolean = activeInstance?.panel != null

        fun setLuaButtonVisible(visible: Boolean) {
            val service = activeInstance ?: return
            service.handler.post {
                if (service.ballView == null && visible) service.createBall()
                service.ballView?.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        fun isLuaButtonVisible(): Boolean = activeInstance?.ballView?.visibility == View.VISIBLE

        fun consumeLuaButtonClick(): Boolean {
            val clicked = uiButtonClicked
            uiButtonClicked = false
            return clicked
        }

        fun luaGotoAddress(address: Long) {
            if (address <= 0L) return
            val service = activeInstance ?: return
            service.handler.post {
                service.memoryEditorAddress = address
                service.showMemoryEditorPanel(address, service.memoryEditorType)
            }
        }

        fun showLuaWindow(window: LuaViewBridge.WindowSpec) {
            val service = activeInstance ?: return
            service.handler.post { service.showLuaWindowPanel(window) }
        }
    }

    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    // 搜索状态
    private var searchResults: List<Map<String, Any>> = emptyList()
    private var searchDataType = "dword"
    private val selectedIndices = mutableSetOf<Int>() // 选中的结果索引
    private val selectedSavedAddresses = mutableSetOf<Long>()
    private val selectedMemoryAddresses = mutableSetOf<Long>()
    // 搜索输入框的值（保持不丢失）
    private var savedSearchInput = ""
    private var savedFilterInput = ""
    private var savedRangeMin = ""
    private var savedRangeMax = ""
    private var savedScrollY = 0
    private var searchResultPage = 0
    private var searchResultFilter = ""
    private var focusedSearchResultIndex = -1
    private var memoryEditorAddress = 0L
    private var memoryEditorType = "dword"
    private var pendingProcessSelection: Map<String, Any>? = null

    private data class SavedMemoryItem(
        val address: Long,
        val type: String,
        val packageName: String,
        val label: String,
        val lastValue: String,
        val freeze: Boolean,
        val freezeType: Int = MemoryFreezer.FREEZE_NORMAL,
        val freezeFrom: String = "",
        val freezeTo: String = "",
    )

    private data class DebugWatchItem(
        val address: Long,
        val type: String,
        val label: String,
        val enabled: Boolean = true,
        val lastValue: String = "",
        val hitCount: Int = 0,
    )

    private var debugWatchItems = mutableListOf<DebugWatchItem>()

    // AI 对话历史（持久化在内存中，防止切换后消失）
    private val chatMessages = mutableListOf<Pair<String, String>>() // (sender, message)
    private var isAiResponding = false

    // 记住上次打开的面板
    private var lastPanel = ""
    private var activePanel = ""
    private var aggMainTab = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        activeInstance = this
        MemoryEngine.setContext(applicationContext)
        LuaEngine.setContext(this)
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val migration = prefs.edit()
        var needsMigration = false
        if (!prefs.contains(PREF_FILTER_SYSTEM) && prefs.contains("agg_filter_system")) {
            migration.putBoolean(PREF_FILTER_SYSTEM, prefs.getBoolean("agg_filter_system", true))
            needsMigration = true
        }
        if (!prefs.contains(PREF_FILTER_LINUX) && prefs.contains("agg_filter_linux")) {
            migration.putBoolean(PREF_FILTER_LINUX, prefs.getBoolean("agg_filter_linux", true))
            needsMigration = true
        }
        if (needsMigration) migration.apply()
        val regionCategories = prefs.getStringSet("memory_region_categories", null)
        if (!regionCategories.isNullOrEmpty()) MemoryEngine.setRegionCategories(regionCategories)
        val storedRangeFrom = prefs.getString("memory_range_from", "")
            ?.takeIf { it.isNotBlank() }?.toLongOrNull(16)
        val storedRangeTo = prefs.getString("memory_range_to", "")
            ?.takeIf { it.isNotBlank() }?.toLongOrNull(16)
        MemoryEngine.setCustomRange(storedRangeFrom, storedRangeTo)
        val configuredFreezeInterval = prefs.getLong("agg_freeze_interval_ms", 120L)
        MemoryFreezer.setFreezeInterval(if (prefs.getBoolean("agg_fast_freeze", false)) 60L else configuredFreezeInterval)
        debugWatchItems = loadDebugWatchItems()
        if (prefs.getBoolean("agg_remember_tab", true)) {
            aggMainTab = prefs.getInt("agg_last_tab", 1).coerceIn(0, 4)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBall()
        lastPanel = prefs.getString("last_panel", "") ?: ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        val resetOnExit = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            .getBoolean("agg_reset_on_exit", true)
        if (resetOnExit) {
            MemoryFreezer.clearAll()
            MemoryEngine.resetSearchState()
            searchResults = emptyList()
            selectedIndices.clear()
            debugWatchItems = debugWatchItems.map { it.copy(lastValue = "", hitCount = 0) }.toMutableList()
            persistDebugWatchItems()
        }
        isRunning = false
        if (activeInstance === this) activeInstance = null
        removeBall()
        super.onDestroy()
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "GG-AI 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID).setContentTitle("GG-AI").setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("GG-AI").setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).build()
        }
    }

    // ==================== 悬浮球 ====================

    private fun createBall() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val ballSize = dp(32)
        ballView = ImageView(this).apply {
            setImageResource(R.drawable.ic_gg_48dp)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = null
            setPadding(0, 0, 0, 0)
            elevation = 0f
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val maxX = (resources.displayMetrics.widthPixels - ballSize).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - ballSize).coerceAtLeast(0)
        ballParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("agg_ball_x", 0).coerceIn(0, maxX)
            y = prefs.getInt("agg_ball_y", dp(200)).coerceIn(0, maxY)
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var downAt = 0L
        var dragging = false
        ballView?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = ballParams?.x ?: 0
                    initialY = ballParams?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    downAt = android.os.SystemClock.uptimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) dragging = true
                    ballParams?.x = (initialX + dx).coerceIn(0, maxX)
                    ballParams?.y = (initialY + dy).coerceIn(0, maxY)
                    try { wm?.updateViewLayout(ballView, ballParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        uiButtonClicked = true
                        val held = android.os.SystemClock.uptimeMillis() - downAt
                        if (held >= 520L) showAggQuickPanel() else showMainMenu()
                    } else {
                        ballParams?.x = if ((ballParams?.x ?: 0) < maxX / 2) 0 else maxX
                        try { wm?.updateViewLayout(ballView, ballParams) } catch (_: Exception) {}
                        prefs.edit()
                            .putInt("agg_ball_x", ballParams?.x ?: 0)
                            .putInt("agg_ball_y", ballParams?.y ?: dp(200))
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
        try { wm?.addView(ballView, ballParams) } catch (_: Exception) {}
    }

    private fun showAggQuickPanel() {
        val pid = MemoryEngine.getAttachedPid()
        makeDraggablePanel("快捷操作", { content ->
            fun quick(label: String, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                setTextColor(Color.WHITE)
                textSize = 10.5f
                background = aggMenuDrawable(Color.argb(28, 255, 255, 255), 4, Color.parseColor("#66FFFFFF"))
                setOnClickListener { action() }
            }
            content.addView(TextView(this).apply {
                text = if (pid == null) "未选择进程" else "PID $pid · ${if (isTargetProcessPaused(pid)) "已暂停" else "运行中"}"
                setTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 9f
                setPadding(dp(6), dp(2), dp(6), dp(6))
            })
            content.addView(quick(if (pid != null && isTargetProcessPaused(pid)) "恢复目标进程" else "暂停目标进程") {
                if (pid == null) showProcessPanel()
                else {
                    val paused = isTargetProcessPaused(pid)
                    setTargetProcessPaused(pid, !paused)
                    closePanel()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(quick("打开搜索") { aggMainTab = 1; showMainMenu() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(quick("打开保存列表") { aggMainTab = 2; showMainMenu() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(quick("打开内存编辑器") { aggMainTab = 3; showMainMenu() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(quick("关闭悬浮服务") { stopSelf() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }, 280, 330, onBack = { closePanel() }, titleIcon = R.drawable.ic_gg_48dp)
    }

    private fun removeBall() {
        closePanel()
        try { ballView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        ballView = null
    }

    // ==================== 面板管理 ====================

    private fun saveLastPanel(name: String) {
        lastPanel = name
        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit().putString("last_panel", name).apply()
    }

    private fun closePanel() {
        // 关闭前保存滚动位置（仅当面板有 ScrollView 时才更新，避免菜单面板覆盖搜索面板的滚动位置）
        try {
            fun findScrollView(v: android.view.View): android.widget.ScrollView? {
                if (v is android.widget.ScrollView) return v
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val found = findScrollView(v.getChildAt(i))
                        if (found != null) return found
                    }
                }
                return null
            }
            if (activePanel == lastPanel && (activePanel == "search" || activePanel == "chat")) {
                panel?.let { findScrollView(it)?.let { sv -> if (sv.scrollY > 0) savedScrollY = sv.scrollY } }
            }
        } catch (_: Exception) {}
        try { panel?.let { wm?.removeView(it) } } catch (_: Exception) {}
        panel = null; panelParams = null; activePanel = ""
    }

    private fun showPanel(view: View, w: Int = 280, h: Int = 400) {
        closePanel()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val panelW = dp(w).coerceAtMost(screenW - dp(20))
        val panelH = dp(h).coerceAtMost(screenH - dp(20))
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        panelParams = WindowManager.LayoutParams(
            panelW,
            panelH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2
            y = (screenH - panelH) / 2
        }
        panel = view
        try { wm?.addView(panel, panelParams) } catch (_: Exception) {}
    }
    
    // 创建可获得焦点的面板（用于输入法）
    private fun showFocusablePanel(view: View, w: Int = 280, h: Int = 400) {
        closePanel()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val panelW = dp(w).coerceAtMost(screenW - dp(20))
        val panelH = dp(h).coerceAtMost(screenH - dp(20))
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        panelParams = WindowManager.LayoutParams(
            panelW,
            panelH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - panelW) / 2
            y = (screenH - panelH) / 2
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        panel = view
        try { wm?.addView(panel, panelParams) } catch (_: Exception) {}
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    // ==================== 可拖动面板包装 ====================

    private fun makeDraggablePanel(
        title: String,
        contentBuilder: (LinearLayout) -> Unit,
        w: Int = 280,
        h: Int = 400,
        onBack: (() -> Unit)? = null,
        titleIcon: Int? = null,
        bgColor: String = "#C0000000",
    ) {
        val dm = resources.displayMetrics
        val panelW = dp(w).coerceAtMost((dm.widthPixels - dp(16)).coerceAtLeast(1))
        val panelH = dp(h).coerceAtMost((dm.heightPixels - dp(20)).coerceAtLeast(1))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(runCatching { Color.parseColor(bgColor) }.getOrDefault(Color.parseColor("#C0000000")))
                setStroke(dp(1), Color.argb(110, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, 0, 0, 0)
        }
        if (titleIcon != null) {
            titleBar.addView(ImageView(this).apply {
                setImageResource(titleIcon)
                setColorFilter(Color.WHITE)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        } else {
            titleBar.addView(View(this), LinearLayout.LayoutParams(dp(12), dp(48)))
        }
        titleBar.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        titleBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close_white_24dp)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "关闭"
            setOnClickListener { onBack?.invoke() ?: showMainMenu() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(View(this).apply {
            setBackgroundColor(Color.argb(192, 255, 255, 255))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        contentBuilder(contentArea)
        root.addView(contentArea)

        showFocusablePanel(root, w, h)
        activePanel = lastPanel
        panelParams?.let { enableCompactPanelDrag(titleBar, it, panelW, panelH) }
    }

    private fun showFocusableFullscreenPanel(view: View) {
        val oldPanel = panel
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        panel = view.apply {
            alpha = 0f
            scaleX = 0.99f
            scaleY = 0.99f
        }
        try {
            wm?.addView(panel, panelParams)
            panel?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            oldPanel?.let { old ->
                handler.postDelayed({ try { wm?.removeView(old) } catch (_: Exception) {} }, 32L)
            }
        } catch (_: Exception) {
            oldPanel?.let { old -> try { wm?.removeView(old) } catch (_: Exception) {} }
        }
    }

    private fun showLuaWindowPanel(window: LuaViewBridge.WindowSpec) {
        if (window.tabs.isEmpty()) return
        window.activeIndex = window.activeIndex.coerceIn(0, window.tabs.lastIndex)
        val activeTab = window.tabs[window.activeIndex]
        val lockedBack: () -> Unit = if (activeTab.locked) {
            { Toast.makeText(this, "该脚本界面已锁定", Toast.LENGTH_SHORT).show() }
        } else {
            { showScriptPanel() }
        }

        makeDraggablePanel("AGG · ${activeTab.title}", { content ->
            fun scriptButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }

            if (window.tabs.size > 1) {
                val tabScroll = android.widget.HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                }
                val tabRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                window.tabs.forEachIndexed { index, tab ->
                    tabRow.addView(scriptButton(tab.title, accent = index == window.activeIndex) {
                        window.activeIndex = index
                        showLuaWindowPanel(window)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(4) })
                }
                tabScroll.addView(tabRow)
                content.addView(tabScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(5) })
            }

            fun scrollContainer(): Pair<ScrollView, LinearLayout> {
                val holder = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }
                val scroll = ScrollView(this).apply {
                    isFillViewport = true
                    addView(holder)
                }
                return scroll to holder
            }

            when (val spec = activeTab.view) {
                is LuaViewBridge.TextSpec -> {
                    val (scroll, holder) = scrollContainer()
                    holder.addView(TextView(this).apply {
                        text = spec.text
                        setTextColor(Color.parseColor("#E6E0E9"))
                        textSize = 11f
                        setTextIsSelectable(true)
                        setLineSpacing(0f, 1.15f)
                        setPadding(dp(8), dp(8), dp(8), dp(12))
                        background = aggMenuDrawable(Color.parseColor("#211F26"), 9, Color.parseColor("#3A3641"))
                    })
                    content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }

                is LuaViewBridge.ListSpec -> {
                    val (scroll, holder) = scrollContainer()
                    spec.items.forEachIndexed { index, item ->
                        holder.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(11), dp(8), dp(11), dp(8))
                            background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#3A3641"))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                            addView(TextView(this@OverlayService).apply {
                                text = item.title
                                setTextColor(Color.parseColor("#F3EDF7"))
                                textSize = 11.5f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            })
                            if (item.subtitle.isNotBlank()) addView(TextView(this@OverlayService).apply {
                                text = item.subtitle
                                setTextColor(Color.parseColor("#938F99"))
                                textSize = 9f
                                setPadding(0, dp(2), 0, 0)
                            })
                            setOnClickListener { LuaViewBridge.invokeAsync(item.callback) }
                        })
                    }
                    content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    spec.refreshCallback?.takeIf { it.isfunction() }?.let { callback ->
                        content.addView(scriptButton("刷新列表") { LuaViewBridge.invokeAsync(callback) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) })
                    }
                }

                is LuaViewBridge.SwitchSpec -> {
                    val (scroll, holder) = scrollContainer()
                    spec.items.forEach { item ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(10), dp(6), dp(7), dp(6))
                            background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#3A3641"))
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(4) }
                        }
                        row.addView(TextView(this).apply {
                            text = item.title
                            setTextColor(Color.parseColor("#F3EDF7"))
                            textSize = 11f
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        row.addView(android.widget.Switch(this).apply {
                            isChecked = item.checked
                            setOnCheckedChangeListener { _, checked ->
                                item.checked = checked
                                LuaViewBridge.invokeAsync(if (checked) item.openCallback else item.closeCallback)
                            }
                        })
                        holder.addView(row)
                    }
                    content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }

                is LuaViewBridge.MultiChoiceSpec -> {
                    val (scroll, holder) = scrollContainer()
                    spec.items.forEachIndexed { index, label ->
                        holder.addView(android.widget.CheckBox(this).apply {
                            text = label
                            isChecked = index in spec.selected
                            setTextColor(Color.parseColor("#E6E0E9"))
                            textSize = 10.5f
                            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
                            background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#3A3641"))
                            setPadding(dp(9), dp(4), dp(9), dp(4))
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) spec.selected.add(index) else spec.selected.remove(index)
                            }
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(3) }
                        })
                    }
                    content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    content.addView(scriptButton("确认选择", true) {
                        LuaViewBridge.invokeAsync(spec.callback, LuaViewBridge.selectedItemsTable(spec.items, spec.selected))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
                }

                is LuaViewBridge.PromptSpec -> {
                    val (scroll, holder) = scrollContainer()
                    val getters = mutableListOf<() -> org.luaj.vm2.LuaValue>()
                    spec.fields.forEach { field ->
                        holder.addView(TextView(this).apply {
                            text = field.label
                            setTextColor(Color.parseColor("#CAC4D0"))
                            textSize = 9.5f
                            setPadding(dp(4), dp(7), dp(4), dp(3))
                        })
                        when {
                            field.type == "checkbox" -> {
                                val check = android.widget.CheckBox(this).apply {
                                    text = if (field.defaultValue.toboolean()) "已启用" else "未启用"
                                    isChecked = field.defaultValue.toboolean()
                                    setTextColor(Color.parseColor("#E6E0E9"))
                                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
                                    setOnCheckedChangeListener { _, checked -> text = if (checked) "已启用" else "未启用" }
                                }
                                holder.addView(check, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
                                getters.add { org.luaj.vm2.LuaValue.valueOf(check.isChecked) }
                            }

                            field.type == "slider" && field.defaultValue.istable() -> {
                                val values = field.defaultValue.checktable()
                                val from = values.get("from").optint(0)
                                val to = values.get("to").optint(100)
                                val step = values.get("size").optint(1).coerceAtLeast(1)
                                var current = values.get("value").optint(from).coerceIn(minOf(from, to), maxOf(from, to))
                                val valueLabel = TextView(this).apply {
                                    text = current.toString()
                                    setTextColor(Color.parseColor("#D0BCFF"))
                                    textSize = 10f
                                    gravity = Gravity.END
                                }
                                val seek = android.widget.SeekBar(this).apply {
                                    max = ((to - from).coerceAtLeast(0) / step).coerceAtLeast(1)
                                    progress = ((current - from) / step).coerceIn(0, max)
                                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                                        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                                            current = from + progress * step
                                            valueLabel.text = current.toString()
                                        }
                                        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                                        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                                    })
                                }
                                holder.addView(valueLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)))
                                holder.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
                                getters.add { org.luaj.vm2.LuaValue.valueOf(current) }
                            }

                            field.options.isNotEmpty() && field.type != "chip" -> {
                                val spinner = Spinner(this).apply {
                                    adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, field.options).apply {
                                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                                    }
                                    val selected = field.defaultValue.optint(1).coerceIn(1, field.options.size) - 1
                                    setSelection(selected)
                                    background = aggMenuDrawable(Color.parseColor("#34313A"), 8, Color.parseColor("#49454F"))
                                }
                                holder.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
                                getters.add { org.luaj.vm2.LuaValue.valueOf(spinner.selectedItemPosition + 1) }
                            }

                            else -> {
                                val input = EditText(this).apply {
                                    val defaultText = when {
                                        field.defaultValue.isnil() -> ""
                                        field.defaultValue.istable() -> {
                                            val table = field.defaultValue.checktable()
                                            (1..table.length()).joinToString(",") { table.get(it).tojstring() }
                                        }
                                        else -> field.defaultValue.tojstring()
                                    }
                                    setText(defaultText)
                                    setSingleLine(field.type != "range_slider" && field.type != "chip")
                                    textSize = 10.5f
                                    setTextColor(Color.parseColor("#F3EDF7"))
                                    setHintTextColor(Color.parseColor("#938F99"))
                                    inputType = if (field.type == "number" || field.type == "speed") {
                                        android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                                    } else android.text.InputType.TYPE_CLASS_TEXT
                                    setPadding(dp(10), 0, dp(10), 0)
                                    background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#49454F"))
                                }
                                holder.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (input.isSingleLine) dp(42) else dp(64)))
                                getters.add {
                                    val raw = input.text.toString()
                                    if (field.type == "number" || field.type == "speed") {
                                        raw.toDoubleOrNull()?.let { org.luaj.vm2.LuaValue.valueOf(it) } ?: org.luaj.vm2.LuaValue.valueOf(raw)
                                    } else if (field.type == "range_slider" || field.type == "chip") {
                                        org.luaj.vm2.LuaTable().apply {
                                            raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, value ->
                                                set(index + 1, value.toDoubleOrNull()?.let { org.luaj.vm2.LuaValue.valueOf(it) } ?: org.luaj.vm2.LuaValue.valueOf(value))
                                            }
                                        }
                                    } else org.luaj.vm2.LuaValue.valueOf(raw)
                                }
                            }
                        }
                    }
                    content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    content.addView(scriptButton("提交", true) {
                        val values = org.luaj.vm2.LuaTable()
                        getters.forEachIndexed { index, getter -> values.set(index + 1, getter()) }
                        LuaViewBridge.invokeAsync(spec.callback, values)
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
                }

                is LuaViewBridge.WebSpec -> {
                    val web = android.webkit.WebView(this).apply {
                        setBackgroundColor(Color.parseColor("#17151B"))
                        settings.javaScriptEnabled = spec.callbacks.isNotEmpty()
                        settings.domStorageEnabled = true
                        spec.callbacks.forEach { (name, callback) ->
                            addJavascriptInterface(LuaViewBridge.JavascriptBridge(callback), name)
                        }
                        webViewClient = android.webkit.WebViewClient()
                        if (spec.source.trimStart().startsWith("http://") || spec.source.trimStart().startsWith("https://")) {
                            loadUrl(spec.source)
                        } else {
                            loadDataWithBaseURL(null, spec.source, "text/html", "UTF-8", null)
                        }
                    }
                    content.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            }
        }, 430, 590, onBack = lockedBack, titleIcon = R.drawable.ic_agg_script)
    }

    // ==================== 主菜单 ====================

    private fun showLastOrMainMenu() {
        when (lastPanel) {
            "process" -> showProcessPanel()
            "search" -> showAggSearchTab()
            "editor" -> showMemoryEditorPanel()
            "process_control" -> showProcessControlPanel()
            "memory_tools" -> showMemoryToolsPanel()
            "saved" -> showSavedListPanel()
            "chat" -> showAIChatPanel()
            "script" -> showScriptPanel()
            else -> showMainMenu()
        }
    }

    private fun showFullscreenPanel(view: View) {
        val oldPanel = panel
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        panel = view.apply {
            alpha = 0f
            scaleX = 0.99f
            scaleY = 0.99f
        }
        try {
            wm?.addView(panel, panelParams)
            panel?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
            oldPanel?.let { old ->
                handler.postDelayed({ try { wm?.removeView(old) } catch (_: Exception) {} }, 32L)
            }
        } catch (_: Exception) {
            oldPanel?.let { old -> try { wm?.removeView(old) } catch (_: Exception) {} }
        }
    }

    private fun fullscreenSectionTitle(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, dp(2), dp(10))
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(Color.parseColor("#2B1B14"))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@OverlayService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#7B6257"))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun fullscreenFeatureCard(title: String, subtitle: String, iconRes: Int, tag: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#FFFFFBF5"))
                setStroke(dp(1), Color.argb(105, 255, 255, 255))
            }
            elevation = dp(5).toFloat()
            setOnClickListener {
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(65).withEndAction {
                    scaleX = 1f
                    scaleY = 1f
                    onClick()
                }.start()
            }
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(Color.parseColor("#FFE4C7"))
                    }
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
                })
                addView(TextView(this@OverlayService).apply {
                    text = tag
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#5D4037"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(Color.parseColor("#FFF1E4"))
                        setStroke(dp(1), Color.parseColor("#22B97945"))
                    }
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                })
            })
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(Color.parseColor("#2B1B14"))
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(4))
            })
            addView(TextView(this@OverlayService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#7B6257"))
                textSize = 12f
            })
        }
    }

    private fun fullscreenStatusCard(): LinearLayout {
        val pid = MemoryEngine.getAttachedPid()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#FFFFFBF5"))
                setStroke(dp(1), Color.argb(105, 255, 255, 255))
            }
            elevation = dp(5).toFloat()
            addView(TextView(this@OverlayService).apply {
                text = "当前状态"
                setTextColor(Color.parseColor("#2B1B14"))
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@OverlayService).apply {
                text = if (pid != null) "● 已附加 PID:$pid" else "● 未附加进程"
                setTextColor(if (pid != null) Color.parseColor("#2E7D5B") else Color.parseColor("#C47A16"))
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            })
            addView(TextView(this@OverlayService).apply {
                text = "● 全屏菜单已启用 · 横竖屏自适应"
                setTextColor(Color.parseColor("#7B6257"))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
            addView(TextView(this@OverlayService).apply {
                text = "提示：点击收起可回到小悬浮球，功能入口仍保持原逻辑。"
                setTextColor(Color.parseColor("#7B6257"))
                textSize = 12f
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun fullscreenActionPill(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5D4037"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#FFE4C7"))
                setStroke(dp(1), Color.parseColor("#33B97945"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun parseAggAddress(rawText: String): Long? {
        val raw = rawText.trim()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLongOrNull(16)
            raw.endsWith("h", ignoreCase = true) -> raw.dropLast(1).toLongOrNull(16)
            raw.any { it in 'A'..'F' || it in 'a'..'f' } -> raw.toLongOrNull(16)
            else -> raw.toLongOrNull()
        }
    }

    private fun loadDebugWatchItems(): MutableList<DebugWatchItem> {
        val raw = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            .getString("agg_debug_watch_items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                DebugWatchItem(
                    address = item.optLong("address", 0L),
                    type = item.optString("type", "dword").takeIf { MemoryEngine.isSupportedType(it) } ?: "dword",
                    label = item.optString("label", "监视地址"),
                    enabled = item.optBoolean("enabled", true),
                    lastValue = item.optString("lastValue", ""),
                    hitCount = item.optInt("hitCount", 0),
                )
            }.filter { it.address > 0L }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persistDebugWatchItems() {
        val array = JSONArray()
        debugWatchItems.forEach { item ->
            array.put(JSONObject().apply {
                put("address", item.address)
                put("type", item.type)
                put("label", item.label)
                put("enabled", item.enabled)
                put("lastValue", item.lastValue)
                put("hitCount", item.hitCount)
            })
        }
        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
            .putString("agg_debug_watch_items", array.toString())
            .apply()
    }

    private fun showAggDebugAddPanel(existing: DebugWatchItem? = null) {
        val pid = MemoryEngine.getAttachedPid()
        if (pid == null || !MemoryEngine.isAttachedProcessAlive()) {
            showProcessPanel()
            return
        }
        makeDraggablePanel(if (existing == null) "添加断点监视" else "编辑断点监视", { content ->
            val addressInput = EditText(this).apply {
                hint = "地址，例如 0x1234ABCD"
                setText(existing?.let { "0x${it.address.toString(16).uppercase()}" }.orEmpty())
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 11f
                setPadding(dp(10), 0, dp(10), 0)
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            content.addView(addressInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

            val types = arrayOf("dword", "float", "double", "word", "byte", "qword")
            val typeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, types).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(types.indexOf(existing?.type ?: "dword").coerceAtLeast(0))
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            content.addView(typeSpinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })

            val labelInput = EditText(this).apply {
                hint = "名称"
                setText(existing?.label ?: "监视地址")
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 11f
                setPadding(dp(10), 0, dp(10), 0)
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            content.addView(labelInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })

            val enabled = android.widget.CheckBox(this).apply {
                text = "启用数值变化监视"
                setTextColor(Color.WHITE)
                textSize = 10f
                isChecked = existing?.enabled ?: true
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            content.addView(enabled, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

            val message = TextView(this).apply {
                text = "监视器会在断点页刷新时比较数值变化并累计命中次数。"
                setTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 9f
                setPadding(dp(4), dp(4), dp(4), dp(6))
            }
            content.addView(message)

            fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener { action() }
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("取消") { aggMainTab = 4; showMainMenu() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            actions.addView(button("保存") {
                val address = parseAggAddress(addressInput.text.toString())
                if (address == null || address <= 0L) {
                    message.text = "地址格式不正确"
                    message.setTextColor(Color.parseColor("#FFFF8A80"))
                    return@button
                }
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                val current = MemoryEngine.readMemory(address, type)?.toString().orEmpty()
                val updated = DebugWatchItem(
                    address = address,
                    type = type,
                    label = labelInput.text.toString().trim().ifBlank { "监视地址" },
                    enabled = enabled.isChecked,
                    lastValue = current,
                    hitCount = existing?.hitCount ?: 0,
                )
                if (existing == null) {
                    debugWatchItems.removeAll { it.address == address && it.type == type }
                    debugWatchItems.add(updated)
                } else {
                    val index = debugWatchItems.indexOf(existing)
                    if (index >= 0) debugWatchItems[index] = updated else debugWatchItems.add(updated)
                }
                persistDebugWatchItems()
                aggMainTab = 4
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 370, 390, onBack = { aggMainTab = 4; showMainMenu() }, titleIcon = R.drawable.ic_dbg)
    }

    private fun showAggDebugDetailsPanel(item: DebugWatchItem) {
        makeDraggablePanel("断点详情", { content ->
            val currentValue = MemoryEngine.readMemory(item.address, item.type)
            val bytes = MemoryEngine.readBytes(item.address, 16)
            val region = searchResultRegionInfo(item.address)
            val card = TextView(this).apply {
                text = buildString {
                    append(item.label).append('\n')
                    append("0x").append(item.address.toString(16).uppercase()).append("  [").append(item.type.uppercase()).append("]\n")
                    append("当前值：").append(currentValue ?: "不可读").append('\n')
                    append("区域：").append(region.first).append("  ").append(region.second).append('\n')
                    append("机器码：").append(bytes?.joinToString(" ") { "%02X".format(it) } ?: "不可读").append('\n')
                    append("命中次数：").append(item.hitCount)
                }
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = aggMenuDrawable(Color.argb(32, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            fun button(label: String, danger: Boolean = false, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(if (danger) Color.parseColor("#FFFF8A80") else Color.WHITE)
                textSize = 10f
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener { action() }
            }
            val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
            row1.addView(button("内存跳转") {
                memoryEditorAddress = item.address
                memoryEditorType = item.type
                aggMainTab = 3
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            row1.addView(button("修改值") {
                showWriteDialog(
                    "0x${item.address.toString(16).uppercase()}",
                    currentValue,
                    bytes?.joinToString(" ") { "%02X".format(it) }.orEmpty(),
                    item.type,
                    returnAction = { aggMainTab = 4; showMainMenu() },
                )
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            content.addView(row1)

            val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            row2.addView(button(if (item.enabled) "停用监视" else "启用监视") {
                val index = debugWatchItems.indexOf(item)
                if (index >= 0) debugWatchItems[index] = item.copy(enabled = !item.enabled)
                persistDebugWatchItems()
                aggMainTab = 4
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            row2.addView(button("删除", danger = true) {
                debugWatchItems.remove(item)
                persistDebugWatchItems()
                aggMainTab = 4
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            content.addView(row2)
        }, 390, 430, onBack = { aggMainTab = 4; showMainMenu() }, titleIcon = R.drawable.ic_dbg)
    }

    private fun showAggProcessFilterPanel(returnToProcess: Boolean = false) {
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        makeDraggablePanel("设置过滤应用", { content ->
            // 对齐 AGG 的 service_appdetector_item.xml：垂直布局、20dp 内边距、两个开关。
            content.setPadding(dp(20), dp(20), dp(20), dp(20))

            val filterSystem = android.widget.Switch(this).apply {
                text = "过滤系统应用进程"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                isChecked = prefs.getBoolean(PREF_FILTER_SYSTEM, true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(PREF_FILTER_SYSTEM, checked).apply()
                    Toast.makeText(this@OverlayService, "切换[$checked]", Toast.LENGTH_SHORT).show()
                }
            }
            val filterLinux = android.widget.Switch(this).apply {
                text = "过滤Linux进程"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                isChecked = prefs.getBoolean(PREF_FILTER_LINUX, true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(PREF_FILTER_LINUX, checked).apply()
                    Toast.makeText(this@OverlayService, "切换[$checked]", Toast.LENGTH_SHORT).show()
                }
            }

            content.addView(
                filterSystem,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            content.addView(
                filterLinux,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }, 360, 230, onBack = {
            if (returnToProcess) showProcessPanel() else { aggMainTab = 0; showMainMenu() }
        })
    }

    private fun showAggFreezeIntervalPanel() {
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        var interval = prefs.getLong("agg_freeze_interval_ms", MemoryFreezer.getFreezeInterval()).toInt()
        makeDraggablePanel("冻结间隔", { content ->
            val value = TextView(this).apply {
                text = "${interval} ms"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f
            }
            content.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            val seek = android.widget.SeekBar(this).apply {
                max = 498
                progress = ((interval.coerceIn(20, 5000) - 20) / 10).coerceIn(0, max)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        interval = 20 + progress * 10
                        value.text = "$interval ms"
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                })
            }
            content.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
            content.addView(TextView(this).apply {
                text = "数值越小冻结越及时，但会增加读写负载。推荐 80–200 ms。"
                setTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 9f
                setPadding(dp(5), dp(4), dp(5), dp(8))
            })
            content.addView(TextView(this).apply {
                text = "应用"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener {
                    prefs.edit().putLong("agg_freeze_interval_ms", interval.toLong()).apply()
                    MemoryFreezer.setFreezeInterval(interval.toLong())
                    aggMainTab = 0
                    showMainMenu()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }, 360, 300, onBack = { aggMainTab = 0; showMainMenu() }, titleIcon = R.drawable.ic_agg_lock)
    }

    private fun showAggInterfaceSettingsPanel() {
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        makeDraggablePanel("界面设置", { content ->
            fun settingSwitch(label: String, key: String, defaultValue: Boolean): android.widget.Switch {
                return android.widget.Switch(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    isChecked = prefs.getBoolean(key, defaultValue)
                }
            }
            val smallRows = settingSwitch("使用紧凑列表项", "agg_small_rows", true)
            val showHex = settingSwitch("显示十六进制解释", "agg_show_hex", true)
            val showMachine = settingSwitch("显示机器码", "agg_show_machine", true)
            val rememberTab = settingSwitch("记住最后标签页", "agg_remember_tab", true)
            listOf(smallRows, showHex, showMachine, rememberTab).forEach {
                content.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            }
            content.addView(TextView(this).apply {
                text = "应用"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener {
                    prefs.edit()
                        .putBoolean("agg_small_rows", smallRows.isChecked)
                        .putBoolean("agg_show_hex", showHex.isChecked)
                        .putBoolean("agg_show_machine", showMachine.isChecked)
                        .putBoolean("agg_remember_tab", rememberTab.isChecked)
                        .apply()
                    aggMainTab = 0
                    showMainMenu()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        }, 360, 360, onBack = { aggMainTab = 0; showMainMenu() }, titleIcon = R.drawable.ic_tune_white_24dp)
    }

    private fun showAggAssemblySettingsPanel() {
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val available = linkedSetOf("auto").apply {
            addAll(Build.SUPPORTED_ABIS.map { it.lowercase() })
            addAll(listOf("arm64", "arm", "thumb"))
        }.toList()
        makeDraggablePanel("设置汇编引擎", { content ->
            val current = prefs.getString("agg_assembly_engine", "auto") ?: "auto"
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, available).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(available.indexOf(current).coerceAtLeast(0))
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            content.addView(TextView(this).apply {
                text = "选择内存页和断点页使用的指令集解释。auto 会按当前设备 ABI 自动选择。"
                setTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 9.5f
                setPadding(dp(5), dp(3), dp(5), dp(8))
            })
            content.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            content.addView(TextView(this).apply {
                text = "应用"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener {
                    prefs.edit().putString("agg_assembly_engine", available[spinner.selectedItemPosition]).apply()
                    aggMainTab = 0
                    showMainMenu()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })
        }, 350, 270, onBack = { aggMainTab = 0; showMainMenu() }, titleIcon = R.drawable.ic_dbg)
    }

    private fun showAggAboutPanel() {
        makeDraggablePanel("关于", { content ->
            content.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_gg_48dp)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))
            content.addView(TextView(this).apply {
                text = buildString {
                    append("GG-AI · AGG 主悬浮窗复刻\n")
                    append("架构：").append(Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
                    append("Root：").append(RootManager.getRootStatus()).append('\n')
                    append("搜索：精确 / 范围 / 模糊 / AOB\n")
                    append("保存：冻结 / 导入 / 导出\n")
                    append("断点页：数值变化监视框架")
                }
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10.5f
                setLineSpacing(0f, 1.25f)
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(TextView(this).apply {
                text = "返回"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener { aggMainTab = 0; showMainMenu() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }, 360, 360, onBack = { aggMainTab = 0; showMainMenu() }, titleIcon = R.drawable.ic_gg_48dp)
    }

    private fun showAggResultFilterPanel() {
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        makeDraggablePanel("搜索结果过滤", { content ->
            content.setPadding(0, 0, 0, 0)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(16))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(body)
            }
            content.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))

            fun field(initial: String, hintText: String): EditText = EditText(this).apply {
                setText(initial)
                hint = hintText
                setSingleLine(true)
                textSize = 10.5f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#938F99"))
                setPadding(dp(8), 0, dp(8), 0)
                background = aggMenuDrawable(Color.argb(34, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
            }

            fun divider(): View = View(this).apply {
                setBackgroundColor(Color.parseColor("#C0FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            }

            fun toggleInputRow(
                label: String,
                initial: String,
                hintText: String,
            ): Pair<android.widget.CheckBox, EditText> {
                val input = field(initial, hintText)
                val check = android.widget.CheckBox(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 10f
                    gravity = Gravity.CENTER_VERTICAL
                    isChecked = initial.isNotBlank()
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    setOnCheckedChangeListener { _, checked ->
                        input.isEnabled = checked
                        input.alpha = if (checked) 1f else 0.45f
                    }
                }
                input.isEnabled = check.isChecked
                input.alpha = if (check.isChecked) 1f else 0.45f
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(check, LinearLayout.LayoutParams(dp(92), dp(44)))
                    addView(input, LinearLayout.LayoutParams(0, dp(40), 1f))
                }
                body.addView(row)
                return check to input
            }

            val maxRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            maxRow.addView(TextView(this).apply {
                text = "最大显示记录："
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(108), dp(44)))
            val maxShow = field(prefs.getInt("agg_filter_max_show", 250).toString(), "250").apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            maxRow.addView(maxShow, LinearLayout.LayoutParams(0, dp(40), 1f))
            body.addView(maxRow)

            val keywordRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            keywordRow.addView(TextView(this).apply {
                text = "关键字："
                setTextColor(Color.WHITE)
                textSize = 10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(72), dp(44)))
            val keyword = field(searchResultFilter, "地址、数值、类型或机器码")
            keywordRow.addView(keyword, LinearLayout.LayoutParams(0, dp(40), 1f))
            body.addView(keywordRow)
            body.addView(divider())

            val addrMinInitial = prefs.getString("agg_filter_addr_min", "") ?: ""
            val addrMaxInitial = prefs.getString("agg_filter_addr_max", "") ?: ""
            val (addrMinCheck, addrMin) = toggleInputRow("地址 ≥", addrMinInitial, "起始地址")
            val (addrMaxCheck, addrMax) = toggleInputRow("地址 ≤", addrMaxInitial, "结束地址")
            body.addView(divider())

            val valueMinInitial = prefs.getString("agg_filter_value_min", "") ?: ""
            val valueMaxInitial = prefs.getString("agg_filter_value_max", "") ?: ""
            val (valueMinCheck, valueMin) = toggleInputRow("数值 ≥", valueMinInitial, "最小数值")
            val (valueMaxCheck, valueMax) = toggleInputRow("数值 ≤", valueMaxInitial, "最大数值")
            body.addView(divider())

            val types = arrayOf("全部", "dword", "float", "double", "word", "byte", "qword")
            val storedType = prefs.getString("agg_filter_type", "全部") ?: "全部"
            val typeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, types).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(types.indexOf(storedType).coerceAtLeast(0))
                background = aggMenuDrawable(Color.argb(34, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
            }
            val typeCheck = android.widget.CheckBox(this).apply {
                text = "类型："
                setTextColor(Color.WHITE)
                textSize = 10f
                isChecked = storedType != "全部"
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnCheckedChangeListener { _, checked ->
                    typeSpinner.isEnabled = checked
                    typeSpinner.alpha = if (checked) 1f else 0.45f
                }
            }
            typeSpinner.isEnabled = typeCheck.isChecked
            typeSpinner.alpha = if (typeCheck.isChecked) 1f else 0.45f
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(typeCheck, LinearLayout.LayoutParams(dp(92), dp(44)))
                addView(typeSpinner, LinearLayout.LayoutParams(0, dp(40), 1f))
            })

            fun unsupportedChoice(label: String, summary: String): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    alpha = 0.48f
                    addView(android.widget.CheckBox(this@OverlayService).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        isEnabled = false
                        buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    }, LinearLayout.LayoutParams(dp(92), dp(44)))
                    addView(TextView(this@OverlayService).apply {
                        text = summary
                        setTextColor(Color.parseColor("#CAC4D0"))
                        textSize = 9f
                        gravity = Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                }
            }
            body.addView(unsupportedChoice("{x}", "小数位过滤暂不可用"))
            body.addView(unsupportedChoice("指针：", "指针权限过滤暂不可用"))
            body.addView(divider())

            val showHex = android.widget.CheckBox(this).apply {
                text = "十六进制格式（little-endian）"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                isChecked = prefs.getBoolean("agg_show_hex", true)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val showReverseHex = android.widget.CheckBox(this).apply {
                text = "反向十六进制格式（big-endian）"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                isEnabled = false
                alpha = 0.48f
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val showString = android.widget.CheckBox(this).apply {
                text = "字符串表达式"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                isEnabled = false
                alpha = 0.48f
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val showJava = android.widget.CheckBox(this).apply {
                text = "Java 字符串表达式"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                isEnabled = false
                alpha = 0.48f
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val showArm = android.widget.CheckBox(this).apply {
                text = "ARM / Thumb / ARM8 操作码"
                setTextColor(Color.WHITE)
                textSize = 9.5f
                isChecked = prefs.getBoolean("agg_show_machine", true)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            body.addView(showHex, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            body.addView(showReverseHex, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            body.addView(showString, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            body.addView(showJava, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))
            body.addView(showArm, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

            fun actionButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
                setOnClickListener { action() }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(actionButton("清除") {
                prefs.edit()
                    .remove("agg_filter_addr_min")
                    .remove("agg_filter_addr_max")
                    .remove("agg_filter_value_min")
                    .remove("agg_filter_value_max")
                    .putInt("agg_filter_max_show", 250)
                    .putString("agg_filter_type", "全部")
                    .putBoolean("agg_show_hex", true)
                    .putBoolean("agg_show_machine", true)
                    .apply()
                searchResultFilter = ""
                aggMainTab = 1
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            actions.addView(actionButton("应用") {
                val limit = maxShow.text.toString().toIntOrNull()?.coerceIn(1, 10000) ?: 250
                prefs.edit()
                    .putInt("agg_filter_max_show", limit)
                    .putString("agg_filter_addr_min", if (addrMinCheck.isChecked) addrMin.text.toString().trim() else "")
                    .putString("agg_filter_addr_max", if (addrMaxCheck.isChecked) addrMax.text.toString().trim() else "")
                    .putString("agg_filter_value_min", if (valueMinCheck.isChecked) valueMin.text.toString().trim() else "")
                    .putString("agg_filter_value_max", if (valueMaxCheck.isChecked) valueMax.text.toString().trim() else "")
                    .putString("agg_filter_type", if (typeCheck.isChecked) types[typeSpinner.selectedItemPosition] else "全部")
                    .putBoolean("agg_show_hex", showHex.isChecked)
                    .putBoolean("agg_show_machine", showArm.isChecked)
                    .apply()
                searchResultFilter = keyword.text.toString().trim()
                aggMainTab = 1
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
            body.addView(actions)
        }, 400, 620, onBack = { aggMainTab = 1; showMainMenu() }, titleIcon = R.drawable.ic_tune_white_24dp)
    }

    private fun showMainMenu() {
        saveLastPanel("menu")
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val isLandscape = screenW > screenH
        val orientationSuffix = if (isLandscape) "land" else "port"
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val defaultPanelWidthDp = if (isLandscape) 760 else 408
        val defaultPanelHeightDp = if (isLandscape) 438 else 586
        val panelWidthDp = prefs.getInt("agg_window_width_$orientationSuffix", defaultPanelWidthDp)
        val panelHeightDp = prefs.getInt("agg_window_height_$orientationSuffix", defaultPanelHeightDp)
        val panelW = dp(panelWidthDp).coerceAtMost((screenW - dp(10)).coerceAtLeast(1))
        val panelH = dp(panelHeightDp).coerceAtMost((screenH - dp(10)).coerceAtLeast(1))

        val attachedPid = MemoryEngine.getAttachedPid()
        val pid = attachedPid?.takeIf { MemoryEngine.isAttachedProcessAlive() }
        if (attachedPid != null && pid == null) {
            MemoryEngine.detachProcess()
            clearAttachedProcessInfo()
        }
        val processName = prefs.getString("attached_name", null)?.takeIf { it.isNotBlank() }
        val packageName = prefs.getString("attached_package", null)?.takeIf { it.isNotBlank() }
        val displayName = if (pid != null) (processName ?: packageName ?: "已选择进程") else "未选择进程"

        val root = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setBackgroundResource(R.drawable.agg_window_background)
            elevation = dp(12).toFloat()
        }
        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }

        fun divider(horizontal: Boolean = true): View = View(this).apply {
            setBackgroundColor(Color.parseColor("#C0FFFFFF"))
            layoutParams = if (horizontal) {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            } else {
                LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            }
        }

        fun iconView(resId: Int, size: Int = 48, padding: Int = 12, action: (() -> Unit)? = null): ImageView {
            return ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
                setColorFilter(Color.WHITE)
                if (action != null) setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            }
        }

        fun smallCounter(value: Int): TextView = TextView(this).apply {
            text = value.toString()
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 8f
            setPadding(dp(3), 0, dp(3), 0)
            setBackgroundResource(R.drawable.agg_counter)
            visibility = if (value > 0) View.VISIBLE else View.GONE
            minWidth = dp(14)
            minHeight = dp(14)
        }

        fun tabBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(if (selected) Color.argb(58, 98, 0, 238) else Color.TRANSPARENT)
        }

        val tabIcons = intArrayOf(
            R.drawable.ic_tune_white_24dp,
            R.drawable.ic_magnify_white_24dp,
            R.drawable.ic_content_save_white_24dp,
            R.drawable.ic_format_list_bulleted_white_24dp,
            R.drawable.ic_dbg,
        )
        val tabNames = arrayOf("配置", "搜索", "保存", "内存", "断点")
        val tabViews = mutableListOf<View>()
        val tabCounters = mutableMapOf<Int, TextView>()
        lateinit var renderTab: (Int) -> Unit

        val appFrame = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(if (isLandscape) 54 else 48), dp(48))
            setOnClickListener { showProcessPanel() }
        }
        appFrame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gg_48dp)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        if (!packageName.isNullOrBlank()) {
            val targetIcon = try { packageManager.getApplicationIcon(packageName) } catch (_: Exception) { null }
            if (targetIcon != null) {
                appFrame.addView(ImageView(this).apply {
                    setImageDrawable(targetIcon)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    }
                }, android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.START))
            }
        }

        fun createTab(index: Int, vertical: Boolean): View {
            val frame = android.widget.FrameLayout(this).apply {
                background = tabBackground(index == aggMainTab)
                setOnClickListener { renderTab(index) }
                contentDescription = tabNames[index]
            }
            frame.addView(ImageView(this).apply {
                setImageResource(tabIcons[index])
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12), dp(if (vertical) 8 else 12), dp(12), dp(if (vertical) 16 else 12))
            }, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            if (vertical) {
                frame.addView(TextView(this).apply {
                    text = tabNames[index]
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                }, android.widget.FrameLayout.LayoutParams(dp(56), dp(18), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }
            val count = when (index) {
                1 -> searchResults.size
                2 -> loadSavedMemoryItems().size
                4 -> debugWatchItems.size
                else -> 0
            }
            val counter = smallCounter(count)
            tabCounters[index] = counter
            frame.addView(counter, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ))
            frame.layoutParams = LinearLayout.LayoutParams(dp(if (vertical) 56 else 48), dp(if (vertical) 56 else 48))
            tabViews.add(frame)
            return frame
        }

        val topMore = iconView(R.drawable.ic_menu_white_24dp) {
            when (aggMainTab) {
                0 -> showAggInterfaceSettingsPanel()
                1 -> showAggResultFilterPanel()
                2 -> showSavedListPanel()
                3 -> showMemoryEditorPanel()
                else -> showAggDebugAddPanel()
            }
        }
        val closeButton = iconView(R.drawable.ic_close_white_24dp) { closePanel() }

        if (isLandscape) {
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.MATCH_PARENT)
            }
            rail.addView(appFrame, LinearLayout.LayoutParams(dp(56), dp(48)))
            for (i in tabNames.indices) rail.addView(createTab(i, true))
            rail.addView(View(this), LinearLayout.LayoutParams(dp(1), 0, 1f))
            rail.addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            root.addView(rail)
            root.addView(divider(horizontal = false))
            root.addView(mainColumn)
        } else {
            val tabsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            }
            tabsRow.addView(appFrame)
            for (i in tabNames.indices) tabsRow.addView(createTab(i, false))
            tabsRow.addView(topMore)
            tabsRow.addView(closeButton)
            mainColumn.addView(tabsRow)
            root.addView(mainColumn)
        }

        val toolbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val toolbarMore = iconView(R.drawable.ic_menu_white_24dp, 45, 12) {
            when (aggMainTab) {
                0 -> showAggInterfaceSettingsPanel()
                1 -> showAggResultFilterPanel()
                2 -> showSavedListPanel()
                3 -> showMemoryEditorPanel()
                else -> showAggDebugAddPanel()
            }
        }
        toolbarRow.addView(toolbar)
        toolbarRow.addView(toolbarMore, LinearLayout.LayoutParams(dp(45), dp(48)))
        toolbarRow.addView(divider(horizontal = false), LinearLayout.LayoutParams(dp(1), dp(48)))
        if (isLandscape) toolbarRow.addView(iconView(R.drawable.ic_close_white_24dp) { closePanel() })
        mainColumn.addView(toolbarRow)
        mainColumn.addView(divider())

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
        }
        val processInitiallyPaused = pid != null && isTargetProcessPaused(pid)
        val pauseButton = iconView(
            if (processInitiallyPaused) android.R.drawable.ic_media_play else R.drawable.ic_pause_white_18dp,
            32,
            7,
        ) {
            val currentPid = MemoryEngine.getAttachedPid()
            if (currentPid == null) {
                showProcessPanel()
            } else {
                val paused = isTargetProcessPaused(currentPid)
                if (setTargetProcessPaused(currentPid, !paused)) {
                    Toast.makeText(this, if (paused) "进程已恢复" else "进程已暂停", Toast.LENGTH_SHORT).show()
                    showMainMenu()
                }
            }
        }
        val appNameText = TextView(this).apply {
            text = displayName
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2), 0, dp(6), 0)
        }
        val infoFilter = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val valueFormat = TextView(this).apply {
            text = "h,D,F"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(3), 0, dp(3), 0)
        }
        val foundCount = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(2), 0, dp(2), 0)
        }
        val infoMenu = iconView(R.drawable.ic_menu_white_24dp, 32, 7) {
            when (aggMainTab) {
                0 -> showProcessControlPanel()
                1 -> showAggResultFilterPanel()
                2 -> showSavedListPanel()
                3 -> showMemoryEditorPanel()
                else -> showAggDebugAddPanel()
            }
        }
        val infoRefresh = iconView(R.drawable.ic_refresh_white_18dp, 32, 7) { renderTab(aggMainTab) }
        infoRow.addView(pauseButton, LinearLayout.LayoutParams(dp(32), dp(26)))
        infoRow.addView(appNameText, LinearLayout.LayoutParams(0, dp(26), 1.05f))
        infoRow.addView(infoFilter, LinearLayout.LayoutParams(0, dp(26), 1f))
        infoRow.addView(valueFormat, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)))
        infoRow.addView(foundCount, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)))
        infoRow.addView(infoMenu, LinearLayout.LayoutParams(dp(32), dp(26)))
        infoRow.addView(infoRefresh, LinearLayout.LayoutParams(dp(32), dp(26)))
        mainColumn.addView(infoRow)
        mainColumn.addView(divider())

        val content = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        mainColumn.addView(content)
        val statusBar = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "8.40.0  +  Ch,Jh,Ca,Cd,Cb,A,S,O"
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        mainColumn.addView(statusBar)

        fun toolbarAction(icon: Int, rotation: Float = 0f, action: () -> Unit) {
            val view = iconView(icon, 48, 12, action).apply { this.rotation = rotation }
            toolbar.addView(view, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        fun sectionTitle(parent: LinearLayout, title: String) {
            parent.addView(TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#FFB8B8B8"))
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(8), dp(8), dp(4))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        fun actionRow(parent: LinearLayout, title: String, summary: String = "", action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(3), dp(6), dp(3))
                background = GradientDrawable().apply {
                    setColor(Color.argb(18, 255, 255, 255))
                    setStroke(dp(0), Color.TRANSPARENT)
                }
                setOnClickListener { action() }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 1
                })
                if (summary.isNotBlank()) addView(TextView(this@OverlayService).apply {
                    text = summary
                    setTextColor(Color.parseColor("#FFB8B8B8"))
                    textSize = 9f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "›"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
            }, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT))
            parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (summary.isBlank()) 44 else 52)))
            parent.addView(divider())
        }

        fun toggleRow(
            parent: LinearLayout,
            title: String,
            summary: String,
            key: String,
            defaultValue: Boolean,
            onChanged: ((Boolean) -> Unit)? = null,
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(3), dp(6), dp(3))
                background = GradientDrawable().apply { setColor(Color.argb(18, 255, 255, 255)) }
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply {
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 12f
                })
                addView(TextView(this@OverlayService).apply {
                    text = summary
                    setTextColor(Color.parseColor("#FFB8B8B8"))
                    textSize = 9f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val switch = android.widget.Switch(this).apply {
                isChecked = prefs.getBoolean(key, defaultValue)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    onChanged?.invoke(checked)
                }
            }
            row.addView(switch, LinearLayout.LayoutParams(dp(56), dp(46)))
            row.setOnClickListener { switch.isChecked = !switch.isChecked }
            parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
            parent.addView(divider())
        }

        fun emptyHint(text: String): TextView = TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        fun renderConfigPage() {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(holder)
            }

            fun configSection(title: String) {
                holder.addView(TextView(this).apply {
                    text = title
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    setBackgroundColor(Color.parseColor("#4A4458"))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }

            fun configItem(title: String, enabled: Boolean = true, action: () -> Unit) {
                val item = TextView(this).apply {
                    text = title
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(if (enabled) Color.WHITE else Color.parseColor("#77727C"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(dp(20), dp(10), dp(12), dp(10))
                    isEnabled = enabled
                    setOnClickListener { action() }
                    setBackgroundColor(Color.TRANSPARENT)
                }
                holder.addView(item, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                holder.addView(View(this).apply {
                    setBackgroundColor(Color.argb(52, 255, 255, 255))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            }

            fun unavailable(title: String) {
                configItem(title) {
                    Toast.makeText(this, "$title：当前内存引擎尚未实现该原生能力", Toast.LENGTH_SHORT).show()
                }
            }

            fun togglePref(title: String, key: String, defaultValue: Boolean, changed: ((Boolean) -> Unit)? = null) {
                configItem(title) {
                    val next = !prefs.getBoolean(key, defaultValue)
                    prefs.edit().putBoolean(key, next).apply()
                    changed?.invoke(next)
                    Toast.makeText(this, "$title：[${if (next) "开启" else "关闭"}]", Toast.LENGTH_SHORT).show()
                    renderTab(0)
                }
            }

            configSection("操作")
            configItem("重置") {
                resetSearchSession()
                Toast.makeText(this, "已重置搜索状态", Toast.LENGTH_SHORT).show()
                renderTab(0)
            }
            configItem("退出") { closePanel() }
            configItem("结束游戏") {
                if (pid == null) showProcessPanel() else showProcessControlPanel()
            }

            configSection("AGG功能设置")
            configItem("模块功能设置") { showMemoryToolsPanel() }
            configItem("弹窗高斯模糊") { showAggWindowSettingsPanel() }
            configItem("设置汇编引擎") { showAggAssemblySettingsPanel() }
            configItem("进程过滤") { showAggProcessFilterPanel() }
            configItem("窗口设置") { showAggWindowSettingsPanel() }
            configItem("退出账号") {
                Toast.makeText(this, "当前版本未接入 AGG 账号系统", Toast.LENGTH_SHORT).show()
            }

            configSection("游戏设置")
            configItem("设置搜索范围") { showRegionPanel() }
            unavailable("变速器拦截")
            unavailable("变速器功能")
            unavailable("显示时间跳跃面板")
            unavailable("随机数拦截")
            unavailable("随机数功能")
            unavailable("从游戏中隐藏")
            unavailable("ptrace 绕过模式")
            unavailable("跳过内存")
            togglePref("快速冻结", "agg_fast_freeze", false) { enabled ->
                MemoryFreezer.setFreezeInterval(if (enabled) 60L else prefs.getLong("agg_freeze_interval_ms", 120L))
            }
            configItem("冻结间隔") { showAggFreezeIntervalPanel() }

            configSection("全局设置")
            togglePref("自动暂停游戏", "agg_autopause", false)
            togglePref("搜索助手", "agg_search_helper", true)
            configItem("保存列表更新间隔") { showAggFreezeIntervalPanel() }
            unavailable("变速器速度")
            unavailable("变速器速度参数")
            togglePref("退出时重置", "agg_reset_on_exit", true)
            togglePref("检查库文件", "agg_check_libs", true)
            unavailable("数据保存在内存中")
            unavailable("内存访问")
            togglePref("深度读取", "agg_deep_read", true)
            unavailable("调用方式")
            unavailable("waitpid 调用")
            unavailable("设置路径")
            configItem("su 命令") {
                Toast.makeText(this, RootManager.getRootStatus(), Toast.LENGTH_LONG).show()
            }
            unavailable("虚拟空间 Root")
            unavailable("防止卸载")

            configSection("界面设置")
            togglePref("使用通知", "agg_use_notification", true)
            unavailable("快捷键")
            unavailable("历史记录限制")
            unavailable("键盘")
            togglePref("允许建议", "agg_suggestions", true)
            togglePref("忽略未知字符", "agg_ignore_unknown_chars", false)
            unavailable("状态栏缩进")
            togglePref("显示数值类型", "agg_visible_type", true)
            togglePref("小列表项目", "agg_small_rows", false)
            configItem("背景") { showAggInterfaceSettingsPanel() }
            configItem("工具栏") { showAggInterfaceSettingsPanel() }
            configItem("工具栏按钮") { showAggInterfaceSettingsPanel() }
            togglePref("填充工具栏", "agg_fill_toolbar", false)
            configItem("图标大小") { showAggInterfaceSettingsPanel() }
            configItem("悬浮图标") { showAggInterfaceSettingsPanel() }
            togglePref("隐藏图标", "agg_hide_icons", false)
            togglePref("动画加速", "agg_acceleration", true)
            togglePref("使用声音效果", "agg_sound_effects", true)
            unavailable("语言")
            unavailable("数字区域设置")

            configSection("其他")
            unavailable("禁用保护")
            configItem("清除历史记录") {
                searchResults = emptyList()
                selectedIndices.clear()
                selectedSavedAddresses.clear()
                selectedMemoryAddresses.clear()
                MemoryEngine.resetSearchState()
                Toast.makeText(this, "历史记录已清除", Toast.LENGTH_SHORT).show()
                renderTab(0)
            }
            unavailable("重置忽略项")
            unavailable("最后统计")
            unavailable("显示 Logcat")
            unavailable("写入区域日志")
            unavailable("截图")
            configItem("关于") { showAggAboutPanel() }
            unavailable("更新日志")

            content.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        fun renderSearchPage() {
            if (searchResults.isEmpty()) {
                val helper = if (prefs.getBoolean("agg_search_helper", true)) {
                    "要搜索已知值，点击工具栏放大镜进行搜索。\n\n未知值或加密数值可使用模糊搜索；联合搜索可使用分号分隔。"
                } else {
                    "没有搜索结果"
                }
                content.addView(emptyHint(helper), android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                return
            }

            val regions = MemoryEngine.getMemoryRegions()
            val showMachine = prefs.getBoolean("agg_show_machine", true) && prefs.getBoolean("agg_deep_read", true)
            val showHex = prefs.getBoolean("agg_show_hex", true)
            val maxShow = prefs.getInt("agg_filter_max_show", 250).coerceIn(1, 10000)
            val minAddress = parseAggAddress(prefs.getString("agg_filter_addr_min", "") ?: "")
            val maxAddress = parseAggAddress(prefs.getString("agg_filter_addr_max", "") ?: "")
            val minValue = prefs.getString("agg_filter_value_min", "")?.toDoubleOrNull()
            val maxValue = prefs.getString("agg_filter_value_max", "")?.toDoubleOrNull()
            val typeFilter = prefs.getString("agg_filter_type", "全部") ?: "全部"
            val keyword = searchResultFilter.trim().lowercase()

            val filtered = searchResults.withIndex().filter { indexed ->
                val item = indexed.value
                val address = (item["addressInt"] as? Number)?.toLong() ?: return@filter false
                val numericValue = (item["value"] as? Number)?.toDouble() ?: item["value"]?.toString()?.toDoubleOrNull()
                val type = item["type"]?.toString() ?: searchDataType
                if (minAddress != null && address < minAddress) return@filter false
                if (maxAddress != null && address > maxAddress) return@filter false
                if (minValue != null && (numericValue == null || numericValue < minValue)) return@filter false
                if (maxValue != null && (numericValue == null || numericValue > maxValue)) return@filter false
                if (typeFilter != "全部" && type != typeFilter) return@filter false
                if (keyword.isNotBlank()) {
                    val searchable = listOf(
                        item["address"], item["value"], item["type"], item["machineCode"],
                        item["pointerTargetText"], item["pointerExpression"],
                    ).joinToString(" ") { it?.toString().orEmpty() }.lowercase()
                    if (!searchable.contains(keyword)) return@filter false
                }
                true
            }
            foundCount.text = "(${filtered.size})"

            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(holder)
            }

            fun removeResult(index: Int) {
                if (index !in searchResults.indices) return
                val oldSelected = selectedIndices.toList()
                searchResults = searchResults.toMutableList().also { it.removeAt(index) }
                selectedIndices.clear()
                oldSelected.forEach { selected ->
                    when {
                        selected < index -> selectedIndices.add(selected)
                        selected > index -> selectedIndices.add(selected - 1)
                    }
                }
                renderTab(1)
            }

            filtered.take(maxShow).forEach { indexed ->
                val index = indexed.index
                val item = indexed.value
                val address = (item["addressInt"] as? Number)?.toLong() ?: return@forEach
                val addressText = item["address"]?.toString() ?: "0x${address.toString(16).uppercase()}"
                val type = item["type"]?.toString() ?: searchDataType
                val rawValue = item["value"]
                val valueText = rawValue?.toString() ?: "?"
                val frozen = MemoryFreezer.isFrozen(address)
                val watched = debugWatchItems.any { it.address == address && it.type == type }
                val regionCode = aggRegionCode(address, regions)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    if (frozen) setBackgroundColor(Color.argb(50, 0, 150, 136))
                    setOnClickListener { showSearchResultActions(index, item) }
                    setOnLongClickListener {
                        copyToClipboard(addressText)
                        Toast.makeText(this@OverlayService, "已复制 $addressText", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                val check = android.widget.CheckBox(this).apply {
                    isChecked = index in selectedIndices
                    gravity = Gravity.CENTER
                    setPadding(0, 0, dp(12), 0)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedIndices.add(index) else selectedIndices.remove(index)
                    }
                }
                row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))

                val values = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), dp(4), dp(2))
                }
                val primary = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                if (watched) {
                    primary.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_debug)
                    }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(3) })
                }
                primary.addView(TextView(this).apply {
                    text = addressText
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, 0, dp(12), 0)
                })
                primary.addView(TextView(this).apply {
                    text = valueText
                    setTextColor(if (frozen) Color.parseColor("#80CBC4") else Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                values.addView(primary)

                val interpretation = buildString {
                    if (showHex && rawValue is Number) append("0x${rawValue.toLong().toString(16).uppercase()}h; ")
                    if (showMachine && item["machineCode"]?.toString().orEmpty().isNotBlank()) {
                        append(item["machineCode"]?.toString().orEmpty()).append("; ")
                    }
                    append(formatSearchResultInterpretations(item))
                }
                values.addView(TextView(this).apply {
                    text = interpretation
                    setTextColor(Color.parseColor("#D0CBD4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(values, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
                    addView(TextView(this@OverlayService).apply {
                        text = aggTypeCode(type)
                        gravity = Gravity.END
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                    addView(TextView(this@OverlayService).apply {
                        text = regionCode
                        gravity = Gravity.END
                        setTextColor(Color.parseColor("#D0CBD4"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    })
                }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))

                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_delete_white_24dp)
                    setColorFilter(Color.WHITE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { removeResult(index) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))

                holder.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                holder.addView(divider())
            }

            content.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        fun renderSavedPage() {
            val items = loadSavedMemoryItems()
            if (items.isEmpty()) {
                selectedSavedAddresses.clear()
                content.addView(emptyHint("保存列表为空"), android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                return
            }
            selectedSavedAddresses.retainAll(items.map { it.address }.toSet())
            val regions = MemoryEngine.getMemoryRegions()
            val showHex = prefs.getBoolean("agg_show_hex", true)
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(holder)
            }

            items.forEach { item ->
                val frozen = MemoryFreezer.isFrozen(item.address) || item.freeze
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    if (frozen) setBackgroundColor(Color.argb(50, 0, 150, 136))
                    setOnClickListener { showSavedFreezeSettingsPanel(item) }
                    setOnLongClickListener { showRenameSavedItemPanel(item); true }
                }

                row.addView(android.widget.CheckBox(this).apply {
                    isChecked = item.address in selectedSavedAddresses
                    gravity = Gravity.CENTER
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedSavedAddresses.add(item.address) else selectedSavedAddresses.remove(item.address)
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))

                row.addView(ImageView(this).apply {
                    setImageResource(if (frozen) R.drawable.ic_agg_lock else R.drawable.ic_nolock_24dp)
                    setColorFilter(if (frozen) Color.parseColor("#80CBC4") else Color.WHITE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        Thread {
                            val ok = if (frozen) {
                                MemoryFreezer.unfreeze(item.address)
                            } else {
                                MemoryFreezer.freeze(item.address, item.lastValue, item.type)
                            }
                            if (ok) {
                                val refreshed = loadSavedMemoryItems().map { current ->
                                    if (current.address == item.address && current.packageName == item.packageName) {
                                        current.copy(freeze = !frozen)
                                    } else current
                                }
                                persistSavedMemoryItems(refreshed)
                            }
                            handler.post { if (panel === root && aggMainTab == 2) renderTab(2) }
                        }.start()
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))

                val values = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                values.addView(TextView(this).apply {
                    text = item.label.ifBlank { "0x${item.address.toString(16).uppercase()}" }
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                values.addView(TextView(this).apply {
                    text = buildString {
                        append("0x${item.address.toString(16).uppercase()}:  ")
                        if (showHex) item.lastValue.toLongOrNull()?.let { append("0x${it.toString(16).uppercase()}h;  ") }
                        append(item.lastValue)
                    }
                    setTextColor(if (frozen) Color.parseColor("#80CBC4") else Color.parseColor("#D0CBD4"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(values, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(TextView(this@OverlayService).apply {
                        text = aggTypeCode(item.type)
                        gravity = Gravity.END
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                    addView(TextView(this@OverlayService).apply {
                        text = aggRegionCode(item.address, regions)
                        gravity = Gravity.END
                        setTextColor(Color.parseColor("#D0CBD4"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    })
                }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT))

                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_delete_white_24dp)
                    setColorFilter(Color.WHITE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        MemoryFreezer.unfreeze(item.address)
                        val remaining = loadSavedMemoryItems().filterNot {
                            it.address == item.address && it.packageName == item.packageName
                        }
                        persistSavedMemoryItems(remaining)
                        selectedSavedAddresses.remove(item.address)
                        renderTab(2)
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))

                holder.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                holder.addView(divider())
            }

            content.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        fun renderMemoryPage() {
            if (pid == null) {
                content.addView(emptyHint("请先选择目标进程"), android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                return
            }
            if (memoryEditorAddress <= 0L) {
                memoryEditorAddress = (MemoryEngine.getMemoryRegions().firstOrNull()?.get("startAddress") as? Number)?.toLong() ?: 1L
            }
            val regions = MemoryEngine.getMemoryRegions()
            val showMachine = prefs.getBoolean("agg_show_machine", true) && prefs.getBoolean("agg_deep_read", true)
            val showHex = prefs.getBoolean("agg_show_hex", true)
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            holder.addView(emptyHint("正在读取内存…"))
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(holder)
            }
            content.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))

            Thread {
                val rows = MemoryEngine.readMemoryWindow(memoryEditorAddress, if (isLandscape) 34 else 24, memoryEditorType)
                handler.post {
                    if (panel !== root || aggMainTab != 3) return@post
                    holder.removeAllViews()
                    if (rows.isEmpty()) {
                        holder.addView(emptyHint("该地址不可读"))
                        return@post
                    }
                    val validAddresses = rows.mapNotNull { (it["addressInt"] as? Number)?.toLong() }.toSet()
                    selectedMemoryAddresses.retainAll(validAddresses)
                    rows.forEach { item ->
                        val address = (item["addressInt"] as? Number)?.toLong() ?: return@forEach
                        val addressText = item["address"]?.toString() ?: "0x${address.toString(16).uppercase()}"
                        val value = item["value"]
                        val machine = item["machineCode"]?.toString().orEmpty()
                        val frozen = MemoryFreezer.isFrozen(address)
                        val watched = debugWatchItems.any { it.address == address && it.type == memoryEditorType }

                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            minimumHeight = dp(48)
                            if (frozen) setBackgroundColor(Color.argb(50, 0, 150, 136))
                            setOnClickListener {
                                showWriteDialog(addressText, value, machine, memoryEditorType, returnAction = {
                                    aggMainTab = 3
                                    showMainMenu()
                                })
                            }
                            setOnLongClickListener {
                                debugWatchItems.removeAll { it.address == address && it.type == memoryEditorType }
                                debugWatchItems.add(DebugWatchItem(address, memoryEditorType, "监视 $addressText", true, value?.toString().orEmpty()))
                                persistDebugWatchItems()
                                Toast.makeText(this@OverlayService, "已加入断点监视", Toast.LENGTH_SHORT).show()
                                true
                            }
                        }
                        row.addView(android.widget.CheckBox(this).apply {
                            isChecked = address in selectedMemoryAddresses
                            gravity = Gravity.CENTER
                            buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) selectedMemoryAddresses.add(address) else selectedMemoryAddresses.remove(address)
                            }
                        }, LinearLayout.LayoutParams(dp(48), dp(48)))

                        val values = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(0, dp(2), dp(4), dp(2))
                        }
                        val firstLine = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        if (watched) {
                            firstLine.addView(ImageView(this).apply { setImageResource(R.drawable.ic_debug) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(3) })
                        }
                        firstLine.addView(TextView(this).apply {
                            text = addressText
                            setTextColor(Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            typeface = android.graphics.Typeface.MONOSPACE
                            setPadding(0, 0, dp(12), 0)
                        })
                        firstLine.addView(TextView(this).apply {
                            text = value?.toString() ?: "?"
                            setTextColor(if (frozen) Color.parseColor("#80CBC4") else Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        values.addView(firstLine)
                        values.addView(TextView(this).apply {
                            text = buildString {
                                if (showHex && value is Number) append("0x${value.toLong().toString(16).uppercase()}h; ")
                                if (showMachine && machine.isNotBlank()) append(machine).append("; ")
                                append(aggTypeCode(memoryEditorType)).append(":").append(value?.toString() ?: "?")
                            }
                            setTextColor(Color.parseColor("#D0CBD4"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            typeface = android.graphics.Typeface.MONOSPACE
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        row.addView(values, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        row.addView(TextView(this).apply {
                            text = aggRegionCode(address, regions)
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                            setTextColor(Color.WHITE)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, 0, dp(6), 0)
                        }, LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT))

                        holder.addView(row, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                        holder.addView(divider())
                    }
                }
            }.start()
        }

        fun renderDebugPage() {
            if (pid == null) {
                content.addView(emptyHint("请先选择目标进程"), android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ))
                return
            }
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(holder)
            }
            content.addView(scroll, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            if (debugWatchItems.isEmpty()) {
                holder.addView(emptyHint("断点列表为空"))
                return
            }

            fun buildRows(items: List<DebugWatchItem>) {
                holder.removeAllViews()
                items.forEach { item ->
                    val container = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        if (!item.enabled) alpha = 0.55f
                    }
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        visibility = View.GONE
                        setPadding(dp(10), dp(4), dp(10), dp(7))
                    }
                    fun detailChip(label: String): TextView = TextView(this).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                        setPadding(dp(9), dp(4), dp(9), dp(4))
                        background = aggMenuDrawable(Color.parseColor("#4A4458"), 12, Color.parseColor("#79747E"))
                    }
                    details.addView(detailChip(aggTypeCode(item.type)))
                    details.addView(detailChip("命中 ${item.hitCount}"), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(5) })
                    details.addView(detailChip(if (item.enabled) "启用" else "停用"), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(5) })

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(48)
                        setPadding(dp(8), 0, 0, 0)
                        setOnClickListener { showAggDebugDetailsPanel(item) }
                        setOnLongClickListener { showAggDebugAddPanel(item); true }
                    }
                    row.addView(TextView(this).apply {
                        text = "0x${item.address.toString(16).uppercase()}"
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = android.graphics.Typeface.MONOSPACE
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(this).apply {
                        text = item.label
                        setTextColor(Color.parseColor("#D0CBD4"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(TextView(this).apply {
                        text = item.lastValue.ifBlank { "?" }
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setTextColor(if (item.hitCount > 0) Color.parseColor("#FFD180") else Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.START
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
                    row.addView(TextView(this).apply {
                        text = prefs.getString("agg_assembly_engine", "auto") ?: "auto"
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                        background = aggMenuDrawable(Color.parseColor("#4A4458"), 4, Color.parseColor("#79747E"))
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
                    row.addView(ImageView(this).apply {
                        setImageResource(R.drawable.ic_expand)
                        setColorFilter(Color.WHITE)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setOnClickListener {
                            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                            rotation = if (details.visibility == View.VISIBLE) 180f else 0f
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                    container.addView(row)
                    container.addView(details)
                    holder.addView(container, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ))
                    holder.addView(divider())
                }
            }

            buildRows(debugWatchItems)
            Thread {
                var changed = false
                val refreshed = debugWatchItems.map { item ->
                    val current = if (item.enabled) MemoryEngine.readMemory(item.address, item.type)?.toString().orEmpty() else item.lastValue
                    val hit = item.enabled && item.lastValue.isNotBlank() && current.isNotBlank() && current != item.lastValue
                    if (hit || current != item.lastValue) changed = true
                    item.copy(
                        lastValue = current.ifBlank { item.lastValue },
                        hitCount = item.hitCount + if (hit) 1 else 0,
                    )
                }.toMutableList()
                handler.post {
                    if (panel !== root || aggMainTab != 4) return@post
                    debugWatchItems = refreshed
                    if (changed) persistDebugWatchItems()
                    buildRows(refreshed)
                }
            }.start()
        }

        renderTab = { requested ->
            aggMainTab = requested.coerceIn(0, 4)
            if (prefs.getBoolean("agg_remember_tab", true)) {
                prefs.edit().putInt("agg_last_tab", aggMainTab).apply()
            }
            tabViews.forEachIndexed { index, view -> view.background = tabBackground(index == aggMainTab) }
            val dynamicCounts = mapOf(
                1 to searchResults.size,
                2 to loadSavedMemoryItems().size,
                4 to debugWatchItems.size,
            )
            tabCounters.forEach { (index, counter) ->
                val count = dynamicCounts[index] ?: 0
                counter.text = count.toString()
                counter.visibility = if (count > 0) View.VISIBLE else View.GONE
            }
            toolbar.removeAllViews()
            content.removeAllViews()
            when (aggMainTab) {
                0 -> {
                    infoFilter.text = "配置"
                    valueFormat.visibility = View.GONE
                    foundCount.text = ""
                    toolbarAction(R.drawable.ic_tune_white_24dp) { showProcessPanel() }
                    toolbarAction(R.drawable.ic_refresh_white_18dp) { renderTab(0) }
                    renderConfigPage()
                    statusBar.text = "配置  ·  进程、范围与界面设置"
                }
                1 -> {
                    val hasAdvancedFilter = searchResultFilter.isNotBlank() ||
                            !prefs.getString("agg_filter_addr_min", "").isNullOrBlank() ||
                            !prefs.getString("agg_filter_addr_max", "").isNullOrBlank() ||
                            !prefs.getString("agg_filter_value_min", "").isNullOrBlank() ||
                            !prefs.getString("agg_filter_value_max", "").isNullOrBlank() ||
                            (prefs.getString("agg_filter_type", "全部") ?: "全部") != "全部"
                    infoFilter.text = if (hasAdvancedFilter) "已启用过滤器" else "无过滤器"
                    valueFormat.visibility = View.VISIBLE
                    foundCount.text = "(${searchResults.size})"
                    fun selectedResults(): List<Map<String, Any>> = selectedIndices
                        .filter { it in searchResults.indices }
                        .map { searchResults[it] }
                    toolbarAction(R.drawable.ic_magnify_white_24dp) { showAggSearchDialog() }
                    toolbarAction(R.drawable.ic_tune_white_24dp) { showAggResultFilterPanel() }
                    toolbarAction(R.drawable.ic_agg_select_all) {
                        if (selectedIndices.size == searchResults.size && searchResults.isNotEmpty()) selectedIndices.clear()
                        else {
                            selectedIndices.clear()
                            selectedIndices.addAll(searchResults.indices)
                        }
                        renderTab(1)
                    }
                    toolbarAction(R.drawable.ic_agg_edit) {
                        val selected = selectedResults()
                        when (selected.size) {
                            0 -> Toast.makeText(this, "请先勾选结果", Toast.LENGTH_SHORT).show()
                            1 -> {
                                val item = selected.first()
                                showWriteDialog(
                                    item["address"]?.toString() ?: return@toolbarAction,
                                    item["value"],
                                    item["machineCode"]?.toString().orEmpty(),
                                    searchResultType(item),
                                    returnAction = { showAggSearchTab() },
                                )
                            }
                            else -> showBatchEditDialog(selected)
                        }
                    }
                    toolbarAction(R.drawable.ic_content_save_white_24dp) {
                        val selected = selectedResults()
                        if (selected.isEmpty()) Toast.makeText(this, "请先勾选结果", Toast.LENGTH_SHORT).show()
                        else {
                            val count = addResultsToSavedList(selected)
                            Toast.makeText(this, "已保存 $count 条", Toast.LENGTH_SHORT).show()
                            renderTab(1)
                        }
                    }
                    toolbarAction(R.drawable.ic_agg_lock) {
                        val selected = selectedResults()
                        if (selected.isEmpty()) {
                            Toast.makeText(this, "请先勾选结果", Toast.LENGTH_SHORT).show()
                        } else {
                            val unfreeze = selected.all { item ->
                                (item["addressInt"] as? Number)?.toLong()?.let { MemoryFreezer.isFrozen(it) } == true
                            }
                            Thread {
                                var changed = 0
                                selected.forEach { item ->
                                    val address = (item["addressInt"] as? Number)?.toLong() ?: return@forEach
                                    val type = searchResultType(item)
                                    val value = item["value"] ?: return@forEach
                                    val ok = if (unfreeze) MemoryFreezer.unfreeze(address) else MemoryFreezer.freeze(address, value, type)
                                    if (ok) changed++
                                }
                                handler.post {
                                    Toast.makeText(this@OverlayService, if (unfreeze) "已解冻 $changed 条" else "已冻结 $changed 条", Toast.LENGTH_SHORT).show()
                                    if (panel === root) renderTab(1)
                                }
                            }.start()
                        }
                    }
                    toolbarAction(R.drawable.ic_refresh_white_18dp) {
                        if (searchResults.isEmpty()) showAggSearchDialog() else {
                            Thread {
                                val refreshed = searchResults.map { item ->
                                    val address = (item["addressInt"] as? Number)?.toLong()
                                    val type = item["type"]?.toString() ?: searchDataType
                                    if (address == null) item else item.toMutableMap().apply {
                                        MemoryEngine.readMemory(address, type)?.let { put("value", it) }
                                    }
                                }
                                handler.post {
                                    searchResults = refreshed
                                    if (panel === root) renderTab(1)
                                }
                            }.start()
                        }
                    }
                    renderSearchPage()
                    statusBar.text = "搜索  ·  ${searchResults.size} 个结果  ·  已选 ${selectedIndices.size}"
                }
                2 -> {
                    val savedItems = loadSavedMemoryItems()
                    val count = savedItems.size
                    infoFilter.text = "保存列表"
                    valueFormat.visibility = View.VISIBLE
                    foundCount.text = "($count)"
                    toolbarAction(R.drawable.ic_agg_lock) { showSavedListPanel() }
                    toolbarAction(R.drawable.ic_agg_back) { showSavedListImportPanel() }
                    toolbarAction(R.drawable.ic_agg_copy) {
                        if (savedItems.isEmpty()) Toast.makeText(this, "保存列表为空", Toast.LENGTH_SHORT).show()
                        else showSavedListExportPanel(savedItems)
                    }
                    toolbarAction(R.drawable.ic_refresh_white_18dp) {
                        if (savedItems.isEmpty() || pid == null) {
                            renderTab(2)
                        } else {
                            Thread {
                                val refreshed = savedItems.map { item ->
                                    val current = MemoryEngine.readMemory(item.address, item.type)?.toString()
                                    if (current == null) item else item.copy(
                                        lastValue = current,
                                        freeze = MemoryFreezer.isFrozen(item.address),
                                    )
                                }
                                persistSavedMemoryItems(refreshed)
                                handler.post {
                                    if (panel === root && aggMainTab == 2) renderTab(2)
                                }
                            }.start()
                        }
                    }
                    renderSavedPage()
                    statusBar.text = "保存  ·  $count 个地址"
                }
                3 -> {
                    infoFilter.text = "0x${memoryEditorAddress.coerceAtLeast(0L).toString(16).uppercase()}"
                    valueFormat.visibility = View.VISIBLE
                    foundCount.text = ""
                    val pageCount = if (isLandscape) 28 else 18
                    val pageStep = pageCount.toLong() * MemoryEngine.getTypeSize(memoryEditorType)
                    toolbarAction(R.drawable.ic_agg_back) {
                        memoryEditorAddress = (memoryEditorAddress - pageStep).coerceAtLeast(1L)
                        renderTab(3)
                    }
                    toolbarAction(R.drawable.ic_agg_back, 180f) {
                        memoryEditorAddress += pageStep
                        renderTab(3)
                    }
                    toolbarAction(R.drawable.ic_agg_edit) {
                        val types = listOf("dword", "float", "double", "word", "byte", "qword")
                        val next = (types.indexOf(memoryEditorType).coerceAtLeast(0) + 1) % types.size
                        memoryEditorType = types[next]
                        renderTab(3)
                    }
                    toolbarAction(R.drawable.ic_format_list_bulleted_white_24dp) { showMemoryEditorPanel() }
                    toolbarAction(R.drawable.ic_refresh_white_18dp) { renderTab(3) }
                    renderMemoryPage()
                    statusBar.text = "内存编辑器  ·  ${memoryEditorType.uppercase()}"
                }
                else -> {
                    val totalHits = debugWatchItems.sumOf { it.hitCount }
                    infoFilter.text = prefs.getString("agg_assembly_engine", "auto") ?: "auto"
                    valueFormat.visibility = View.GONE
                    foundCount.text = "(${debugWatchItems.size})"
                    toolbarAction(R.drawable.ic_dbg) { showAggDebugAddPanel() }
                    toolbarAction(R.drawable.ic_agg_edit) {
                        if (debugWatchItems.isEmpty()) showAggDebugAddPanel()
                        else showAggDebugAddPanel(debugWatchItems.first())
                    }
                    toolbarAction(R.drawable.ic_refresh_white_18dp) { renderTab(4) }
                    toolbarAction(R.drawable.ic_close_white_24dp) {
                        if (debugWatchItems.isEmpty()) return@toolbarAction
                        debugWatchItems.clear()
                        persistDebugWatchItems()
                        renderTab(4)
                    }
                    renderDebugPage()
                    statusBar.text = "断点  ·  ${debugWatchItems.size} 项  ·  命中 $totalHits"
                }
            }
        }

        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (prefs.getBoolean("agg_window_secure", false)) {
            windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_SECURE
        }
        val blurEnabled = prefs.getBoolean("agg_window_blur", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (blurEnabled) windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        val defaultX = ((screenW - panelW) / 2).coerceAtLeast(0)
        val defaultY = ((screenH - panelH) / 2).coerceAtLeast(0)
        val params = WindowManager.LayoutParams(
            panelW,
            panelH,
            overlayType(),
            windowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("agg_window_x_$orientationSuffix", defaultX)
                .coerceIn(0, (screenW - panelW).coerceAtLeast(0))
            y = prefs.getInt("agg_window_y_$orientationSuffix", defaultY)
                .coerceIn(0, (screenH - panelH).coerceAtLeast(0))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurEnabled) {
                setBlurBehindRadius(dp(prefs.getInt("agg_window_blur_radius", 18)))
            }
        }

        val oldPanel = panel
        panelParams = params
        panel = root.apply {
            alpha = 0f
            scaleX = 0.98f
            scaleY = 0.98f
        }
        try {
            wm?.addView(panel, params)
            activePanel = "menu"
            enableCompactPanelDrag(appFrame, params, panelW, panelH) { x, y ->
                prefs.edit()
                    .putInt("agg_window_x_$orientationSuffix", x)
                    .putInt("agg_window_y_$orientationSuffix", y)
                    .apply()
            }
            renderTab(aggMainTab)
            panel?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
            oldPanel?.let { old -> handler.postDelayed({ try { wm?.removeView(old) } catch (_: Exception) {} }, 24L) }
        } catch (_: Exception) {
            oldPanel?.let { old -> try { wm?.removeView(old) } catch (_: Exception) {} }
        }
    }

    private fun showAggWindowSettingsPanel() {
        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val suffix = if (isLandscape) "land" else "port"
        val defaultWidth = if (isLandscape) 760 else 408
        val defaultHeight = if (isLandscape) 438 else 586
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        var widthDp = prefs.getInt("agg_window_width_$suffix", defaultWidth)
        var heightDp = prefs.getInt("agg_window_height_$suffix", defaultHeight)
        var blurRadiusDp = prefs.getInt("agg_window_blur_radius", 18)

        makeDraggablePanel("悬浮窗设置", { content ->
            fun valueTitle(title: String, value: () -> String): Pair<TextView, android.widget.SeekBar> {
                val titleView = TextView(this).apply {
                    text = "$title：${value()}"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    setPadding(dp(5), dp(5), dp(5), 0)
                }
                val seek = android.widget.SeekBar(this).apply {
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }
                content.addView(titleView)
                content.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
                return titleView to seek
            }

            val (widthTitle, widthSeek) = valueTitle("宽度", { "${widthDp}dp" })
            val minWidth = 300
            val maxWidth = if (isLandscape) 1000 else 720
            widthSeek.max = maxWidth - minWidth
            widthSeek.progress = (widthDp - minWidth).coerceIn(0, widthSeek.max)
            widthSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    widthDp = minWidth + progress
                    widthTitle.text = "宽度：${widthDp}dp"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })

            val (heightTitle, heightSeek) = valueTitle("高度", { "${heightDp}dp" })
            val minHeight = 300
            val maxHeight = if (isLandscape) 720 else 900
            heightSeek.max = maxHeight - minHeight
            heightSeek.progress = (heightDp - minHeight).coerceIn(0, heightSeek.max)
            heightSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    heightDp = minHeight + progress
                    heightTitle.text = "高度：${heightDp}dp"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })

            val secureSwitch = android.widget.Switch(this).apply {
                text = "FLAG_SECURE 防截图"
                setTextColor(Color.WHITE)
                textSize = 11f
                isChecked = prefs.getBoolean("agg_window_secure", false)
            }
            content.addView(secureSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

            val blurSwitch = android.widget.Switch(this).apply {
                text = "背景模糊"
                setTextColor(Color.WHITE)
                textSize = 11f
                isChecked = prefs.getBoolean("agg_window_blur", false)
                isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            }
            content.addView(blurSwitch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

            val (blurTitle, blurSeek) = valueTitle("模糊半径", { "${blurRadiusDp}dp" })
            blurSeek.max = 50
            blurSeek.progress = blurRadiusDp.coerceIn(0, 50)
            blurSeek.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            blurSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    blurRadiusDp = progress
                    blurTitle.text = "模糊半径：${blurRadiusDp}dp"
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            })

            fun settingsButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(Color.argb(42, 255, 255, 255))
                    setStroke(dp(1), Color.parseColor("#FFB8B8B8"))
                }
                setOnClickListener { action() }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(settingsButton("恢复默认") {
                prefs.edit()
                    .remove("agg_window_width_$suffix")
                    .remove("agg_window_height_$suffix")
                    .remove("agg_window_x_$suffix")
                    .remove("agg_window_y_$suffix")
                    .putBoolean("agg_window_secure", false)
                    .putBoolean("agg_window_blur", false)
                    .putInt("agg_window_blur_radius", 18)
                    .apply()
                aggMainTab = 0
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            actions.addView(settingsButton("应用") {
                prefs.edit()
                    .putInt("agg_window_width_$suffix", widthDp)
                    .putInt("agg_window_height_$suffix", heightDp)
                    .putBoolean("agg_window_secure", secureSwitch.isChecked)
                    .putBoolean("agg_window_blur", blurSwitch.isChecked)
                    .putInt("agg_window_blur_radius", blurRadiusDp)
                    .apply()
                aggMainTab = 0
                showMainMenu()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 390, 520, onBack = { aggMainTab = 0; showMainMenu() }, titleIcon = R.drawable.ic_tune_white_24dp)
    }

    private fun showMainMenuLegacy() {
        saveLastPanel("menu")
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val isLandscape = screenW > screenH
        val panelW = dp(if (isLandscape) 760 else 408).coerceAtMost((screenW - dp(20)).coerceAtLeast(1))
        val panelH = dp(if (isLandscape) 438 else 586).coerceAtMost((screenH - dp(28)).coerceAtLeast(1))
        val pid = MemoryEngine.getAttachedPid()
        val savedName = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            .getString("attached_name", null)
            ?.takeIf { it.isNotBlank() }
        val processText = if (pid != null) "${savedName ?: "已附加进程"} · PID $pid" else "未附加进程"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = aggMenuDrawable(
                Color.argb(247, 14, 19, 29),
                24,
                Color.parseColor("#46536A")
            )
            elevation = dp(18).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#202A3D"), Color.parseColor("#161D2A"))
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(23).toFloat(), dp(23).toFloat(),
                    dp(23).toFloat(), dp(23).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
            }
        }

        val dragZone = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@OverlayService).apply {
                text = "G"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#6D7CFF"), Color.parseColor("#A15EFF"))
                ).apply { cornerRadius = dp(13).toFloat() }
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@OverlayService).apply {
                    text = "GG-AI  TOOLBOX"
                    setTextColor(Color.parseColor("#F5F7FF"))
                    textSize = 15.5f
                    letterSpacing = 0.05f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@OverlayService).apply {
                    text = processText
                    setTextColor(if (pid != null) Color.parseColor("#5BE3A7") else Color.parseColor("#A9B4C8"))
                    textSize = 10.5f
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                })
            })
        }
        header.addView(dragZone)
        header.addView(aggHeaderButton("—", false) { closePanel() }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(aggHeaderButton("×", true) { stopSelf() }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(7) })
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(body)

        val navigation = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = aggMenuDrawable(Color.parseColor("#151C28"), 18, Color.parseColor("#2D394D"))
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(dp(126), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(12) }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply { bottomMargin = dp(10) }
            }
        }
        val navParams = if (isLandscape) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(6) }
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(5) }
        }
        navigation.addView(aggNavButton("01", "进程", isLandscape, Color.parseColor("#6D7CFF")) { showProcessPanel() }, navParams)
        navigation.addView(aggNavButton("02", "搜索", isLandscape, Color.parseColor("#45C8FF")) { showAggSearchTab() }, copyLayoutParams(navParams))
        navigation.addView(aggNavButton("03", "AI", isLandscape, Color.parseColor("#A66CFF")) { showAIChatPanel() }, copyLayoutParams(navParams))
        navigation.addView(aggNavButton("04", "脚本", isLandscape, Color.parseColor("#4DD8A3")) { showScriptPanel() }, copyLayoutParams(navParams).apply { marginEnd = 0; bottomMargin = 0 })
        body.addView(navigation)

        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        }
        body.addView(workspace)

        val aiHero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#293A78"), Color.parseColor("#4A2C72"))
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#6678D7"))
            }
            setOnClickListener { pressAndRun(this) { showAIChatPanel() } }
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@OverlayService).apply {
                    text = "AI 智能助手"
                    setTextColor(Color.WHITE)
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@OverlayService).apply {
                    text = "描述目标，自动辅助搜索、分析与操作"
                    setTextColor(Color.parseColor("#D5DBFF"))
                    textSize = 11f
                    setPadding(0, dp(4), 0, 0)
                })
            })
            addView(TextView(this@OverlayService).apply {
                text = "ASK  AI  ›"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(Color.argb(46, 255, 255, 255), 999, Color.argb(78, 255, 255, 255))
                setPadding(dp(12), dp(8), dp(12), dp(8))
            })
        }
        workspace.addView(aiHero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (isLandscape) 94 else 88)))

        workspace.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(11), dp(2), dp(8))
            addView(TextView(this@OverlayService).apply {
                text = "QUICK TOOLS"
                setTextColor(Color.parseColor("#E7ECF7"))
                textSize = 11f
                letterSpacing = 0.08f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OverlayService).apply {
                text = if (pid != null) "● ONLINE" else "○ STANDBY"
                setTextColor(if (pid != null) Color.parseColor("#5BE3A7") else Color.parseColor("#7F8BA0"))
                textSize = 10f
            })
        })

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        firstRow.addView(aggToolCard("进程附加", "选择目标应用", "PID", Color.parseColor("#6D7CFF")) { showProcessPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6) })
        firstRow.addView(aggToolCard("内存搜索", "精确 / 模糊 / 范围", "SCAN", Color.parseColor("#45C8FF")) { showAggSearchTab() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) })
        workspace.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        secondRow.addView(aggToolCard("AI 对话", "多轮辅助分析", "CHAT", Color.parseColor("#A66CFF")) { showAIChatPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(6) })
        secondRow.addView(aggToolCard("Lua 脚本", "运行与管理脚本", "LUA", Color.parseColor("#4DD8A3")) { showScriptPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) })
        workspace.addView(secondRow)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        footer.addView(aggFooterAction("打开主界面") {
            try {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
        footer.addView(aggFooterAction("关闭悬浮窗", true) { stopSelf() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) })
        workspace.addView(footer)

        val params = WindowManager.LayoutParams(
            panelW,
            panelH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW - panelW) / 2).coerceAtLeast(0)
            y = ((screenH - panelH) / 2).coerceAtLeast(0)
        }

        val oldPanel = panel
        panelParams = params
        panel = root.apply {
            alpha = 0f
            scaleX = 0.96f
            scaleY = 0.96f
        }
        try {
            wm?.addView(panel, params)
            enableCompactPanelDrag(dragZone, params, panelW, panelH)
            panel?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(150)?.start()
            oldPanel?.let { old ->
                handler.postDelayed({ try { wm?.removeView(old) } catch (_: Exception) {} }, 32L)
            }
        } catch (_: Exception) {
            oldPanel?.let { old -> try { wm?.removeView(old) } catch (_: Exception) {} }
        }
    }

    private fun aggMenuDrawable(fill: Int, radiusDp: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    private fun aggHeaderButton(label: String, danger: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (danger) Color.parseColor("#FF9DA7") else Color.parseColor("#D8E0EF"))
            textSize = 17f
            background = aggMenuDrawable(
                if (danger) Color.parseColor("#34202A") else Color.parseColor("#222C3B"),
                11,
                if (danger) Color.parseColor("#59303A") else Color.parseColor("#344156")
            )
            setOnClickListener { onClick() }
        }
    }

    private fun aggNavButton(tag: String, title: String, showTitle: Boolean, accentColor: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = if (showTitle) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = aggMenuDrawable(Color.parseColor("#1B2432"), 13, Color.parseColor("#2B384B"))
            setOnClickListener { pressAndRun(this) { onClick() } }
            addView(TextView(this@OverlayService).apply {
                text = tag
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 9.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(accentColor, 9, Color.argb(0, 0, 0, 0))
            }, LinearLayout.LayoutParams(dp(31), dp(31)).apply { if (showTitle) marginEnd = dp(8) })
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(Color.parseColor("#DDE5F3"))
                textSize = if (showTitle) 11.5f else 8.5f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                if (!showTitle) setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun aggToolCard(title: String, subtitle: String, tag: String, accentColor: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = aggMenuDrawable(Color.parseColor("#192230"), 16, Color.parseColor("#2E3B50"))
            setOnClickListener { pressAndRun(this) { onClick() } }
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply {
                    text = tag
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = aggMenuDrawable(accentColor, 8, Color.argb(0, 0, 0, 0))
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                })
                addView(TextView(this@OverlayService).apply {
                    text = "›"
                    gravity = Gravity.END
                    setTextColor(Color.parseColor("#738199"))
                    textSize = 20f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
            addView(TextView(this@OverlayService).apply {
                text = title
                setTextColor(Color.parseColor("#F4F7FD"))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(7), 0, 0)
            })
            addView(TextView(this@OverlayService).apply {
                text = subtitle
                setTextColor(Color.parseColor("#94A1B7"))
                textSize = 10.5f
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    private fun aggFooterAction(title: String, danger: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            setTextColor(if (danger) Color.parseColor("#FFABB3") else Color.parseColor("#C9D3E5"))
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = aggMenuDrawable(
                if (danger) Color.parseColor("#2C1D26") else Color.parseColor("#18212E"),
                12,
                if (danger) Color.parseColor("#56313C") else Color.parseColor("#2B384B")
            )
            setOnClickListener { onClick() }
        }
    }

    private fun copyLayoutParams(source: LinearLayout.LayoutParams): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(source).apply {
            marginStart = source.marginStart
            marginEnd = source.marginEnd
            topMargin = source.topMargin
            bottomMargin = source.bottomMargin
        }
    }

    private fun pressAndRun(view: View, action: () -> Unit) {
        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(65L).withEndAction {
            view.scaleX = 1f
            view.scaleY = 1f
            action()
        }.start()
    }

    private fun enableCompactPanelDrag(
        handle: View,
        params: WindowManager.LayoutParams,
        panelW: Int,
        panelH: Int,
        onPositionChanged: ((Int, Int) -> Unit)? = null,
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) dragging = true
                    params.x = (initialX + dx).coerceIn(0, (resources.displayMetrics.widthPixels - panelW).coerceAtLeast(0))
                    params.y = (initialY + dy).coerceIn(0, (resources.displayMetrics.heightPixels - panelH).coerceAtLeast(0))
                    try { panel?.let { wm?.updateViewLayout(it, params) } } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) handle.performClick()
                    else onPositionChanged?.invoke(params.x, params.y)
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 进程面板 ====================

    private fun isTargetProcessPaused(pid: Int): Boolean {
        val state = RootManager.executeRootCommand("grep '^State:' /proc/$pid/status | cut -c 8")
        return state?.trim()?.startsWith("T") == true
    }

    private fun setTargetProcessPaused(pid: Int, paused: Boolean): Boolean {
        val signal = if (paused) "STOP" else "CONT"
        return RootManager.executeRootCommand("kill -$signal $pid") != null
    }

    private fun showProcessControlPanel() {
        val pid = MemoryEngine.getAttachedPid()
        if (pid == null || !MemoryEngine.isAttachedProcessAlive()) {
            showProcessPanel()
            return
        }
        saveLastPanel("process_control")
        makeDraggablePanel("进程控制", { content ->
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val processName = prefs.getString("attached_name", "目标进程") ?: "目标进程"
            val packageName = prefs.getString("attached_package", "") ?: ""
            val statusCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 11, Color.parseColor("#49454F"))
            }
            val title = TextView(this).apply {
                text = processName
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val details = TextView(this).apply {
                text = "$packageName  ·  PID $pid"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(0, dp(3), 0, 0)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val stateView = TextView(this).apply {
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(7), 0, 0)
            }
            statusCard.addView(title)
            statusCard.addView(details)
            statusCard.addView(stateView)
            content.addView(statusCard)

            fun refreshState() {
                Thread {
                    val alive = MemoryEngine.isAttachedProcessAlive(force = true)
                    val paused = alive && isTargetProcessPaused(pid)
                    handler.post {
                        stateView.text = when {
                            !alive -> "● 进程已结束"
                            paused -> "● 已暂停"
                            else -> "● 正在运行"
                        }
                        stateView.setTextColor(
                            when {
                                !alive -> Color.parseColor("#FFB4AB")
                                paused -> Color.parseColor("#FFD8A8")
                                else -> Color.parseColor("#C8F7DC")
                            }
                        )
                    }
                }.start()
            }

            fun controlButton(label: String, danger: Boolean = false, accent: Boolean = false, action: (TextView) -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10.5f
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(
                        when {
                            danger -> Color.parseColor("#FFB4AB")
                            accent -> Color.parseColor("#231A2E")
                            else -> Color.parseColor("#E6E0E9")
                        }
                    )
                    background = aggMenuDrawable(
                        when {
                            danger -> Color.parseColor("#35232A")
                            accent -> Color.parseColor("#D0BCFF")
                            else -> Color.parseColor("#302D35")
                        },
                        9,
                        when {
                            danger -> Color.parseColor("#68404A")
                            accent -> Color.parseColor("#E8DEF8")
                            else -> Color.parseColor("#49454F")
                        },
                    )
                    setOnClickListener { action(this) }
                }
            }

            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            row1.addView(controlButton("暂停进程") { button ->
                button.text = "正在暂停…"
                Thread {
                    val success = setTargetProcessPaused(pid, true)
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "进程已暂停" else "暂停失败", Toast.LENGTH_SHORT).show()
                        button.text = "暂停进程"
                        refreshState()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            row1.addView(controlButton("恢复运行", accent = true) { button ->
                button.text = "正在恢复…"
                Thread {
                    val success = setTargetProcessPaused(pid, false)
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "进程已恢复" else "恢复失败", Toast.LENGTH_SHORT).show()
                        button.text = "恢复运行"
                        refreshState()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
            content.addView(row1)

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row2.addView(controlButton("切换暂停状态") { button ->
                button.text = "正在切换…"
                Thread {
                    val success = setTargetProcessPaused(pid, !isTargetProcessPaused(pid))
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "状态已切换" else "切换失败", Toast.LENGTH_SHORT).show()
                        button.text = "切换暂停状态"
                        refreshState()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            row2.addView(controlButton("刷新状态") { refreshState() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
            content.addView(row2)

            var killArmed = false
            val killButton = controlButton("结束目标进程", danger = true) { button ->
                if (!killArmed) {
                    killArmed = true
                    button.text = "再次点击确认结束"
                    handler.postDelayed({
                        killArmed = false
                        if (button.isAttachedToWindow) button.text = "结束目标进程"
                    }, 3000L)
                    return@controlButton
                }
                Thread {
                    val success = RootManager.executeRootCommand("kill -KILL $pid") != null
                    if (success) {
                        MemoryEngine.detachProcess()
                        clearAttachedProcessInfo()
                        searchResults = emptyList()
                        selectedIndices.clear()
                    }
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "目标进程已结束" else "结束进程失败", Toast.LENGTH_SHORT).show()
                        if (success) showProcessPanel() else refreshState()
                    }
                }.start()
            }
            content.addView(killButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(8) })

            content.addView(TextView(this).apply {
                text = "暂停使用 SIGSTOP，恢复使用 SIGCONT。进程暂停后内存搜索和读写可能等待目标恢复。"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 9f
                setPadding(dp(5), dp(8), dp(5), dp(2))
            })
            refreshState()
        }, 370, 390, onBack = { showMainMenu() }, titleIcon = R.drawable.ic_agg_apps)
    }

    private fun showMemoryToolsPanel() {
        val pid = MemoryEngine.getAttachedPid()
        if (pid == null || !MemoryEngine.isAttachedProcessAlive()) {
            showProcessPanel()
            return
        }
        saveLastPanel("memory_tools")
        makeDraggablePanel("内存工具", { content ->
            fun parseAddress(text: String): Long? {
                val raw = text.trim()
                return when {
                    raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLongOrNull(16)
                    raw.matches(Regex("[0-9A-Fa-f]{6,}")) -> raw.toLongOrNull(16)
                    else -> raw.toLongOrNull()
                }
            }

            fun toolInput(hintText: String, initial: String = ""): EditText {
                return EditText(this).apply {
                    hint = hintText
                    setText(initial)
                    setSingleLine(true)
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#F3EDF7"))
                    setHintTextColor(Color.parseColor("#938F99"))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    setPadding(dp(10), 0, dp(10), 0)
                    background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#49454F"))
                }
            }

            fun sectionTitle(textValue: String, subtitle: String): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(3), dp(4), dp(3), dp(5))
                    addView(TextView(this@OverlayService).apply {
                        text = textValue
                        setTextColor(Color.parseColor("#F3EDF7"))
                        textSize = 11.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@OverlayService).apply {
                        text = subtitle
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 8.8f
                    })
                }
            }

            fun actionButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }

            val region = MemoryEngine.getMemoryRegions().firstOrNull()
            val defaultStart = (region?.get("startAddress") as? Number)?.toLong()?.let { "0x${it.toString(16).uppercase()}" } ?: ""
            val defaultEnd = (region?.get("endAddress") as? Number)?.toLong()?.let { "0x${it.toString(16).uppercase()}" } ?: ""
            val status = TextView(this).apply {
                text = "PID $pid · 单次复制上限 16 MB，转储上限 256 MB"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(dp(5), dp(2), dp(5), dp(6))
            }
            content.addView(status)

            val copyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(8))
                background = aggMenuDrawable(Color.parseColor("#211F26"), 10, Color.parseColor("#49454F"))
            }
            copyCard.addView(sectionTitle("复制内存", "把一段原始字节复制到另一个地址"))
            val copyAddressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val copyFrom = toolInput("来源地址", defaultStart)
            val copyTo = toolInput("目标地址")
            copyAddressRow.addView(copyFrom, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            copyAddressRow.addView(copyTo, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            copyCard.addView(copyAddressRow)
            val copyFooter = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            val copyBytes = toolInput("字节数，例如 4096", "4096")
            copyFooter.addView(copyBytes, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
            copyFooter.addView(actionButton("执行复制", true) {
                val from = parseAddress(copyFrom.text.toString())
                val to = parseAddress(copyTo.text.toString())
                val bytes = copyBytes.text.toString().trim().toIntOrNull()
                if (from == null || to == null || bytes == null || bytes !in 1..(16 * 1024 * 1024)) {
                    status.text = "复制参数无效：检查来源、目标和字节数"
                    status.setTextColor(Color.parseColor("#FFB4AB"))
                    return@actionButton
                }
                status.text = "正在复制 $bytes 字节…"
                status.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    val success = MemoryEngine.copyMemory(from, to, bytes)
                    handler.post {
                        status.text = if (success) "复制完成：0x${from.toString(16).uppercase()} → 0x${to.toString(16).uppercase()}，$bytes 字节" else "复制失败，请检查地址是否可读写"
                        status.setTextColor(if (success) Color.parseColor("#C8F7DC") else Color.parseColor("#FFB4AB"))
                    }
                }.start()
            }, LinearLayout.LayoutParams(dp(92), dp(40)))
            copyCard.addView(copyFooter)
            content.addView(copyCard)

            val dumpCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(8))
                background = aggMenuDrawable(Color.parseColor("#211F26"), 10, Color.parseColor("#49454F"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) }
            }
            dumpCard.addView(sectionTitle("转储内存", "保存指定地址范围的原始二进制文件"))
            val dumpAddressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val dumpFrom = toolInput("起始地址", defaultStart)
            val dumpTo = toolInput("结束地址", defaultEnd)
            dumpAddressRow.addView(dumpFrom, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(3) })
            dumpAddressRow.addView(dumpTo, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(3) })
            dumpCard.addView(dumpAddressRow)
            val dumpFooter = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            val dumpName = toolInput("文件名", "memory_${System.currentTimeMillis()}.bin")
            dumpFooter.addView(dumpName, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
            dumpFooter.addView(actionButton("开始转储", true) {
                val from = parseAddress(dumpFrom.text.toString())
                val to = parseAddress(dumpTo.text.toString())
                val safeName = dumpName.text.toString().trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "memory_dump.bin" }
                if (from == null || to == null || to <= from || to - from > 256L * 1024L * 1024L) {
                    status.text = "转储范围无效，范围必须大于 0 且不超过 256 MB"
                    status.setTextColor(Color.parseColor("#FFB4AB"))
                    return@actionButton
                }
                val directory = getExternalFilesDir("dumps") ?: java.io.File(filesDir, "dumps")
                val outputFile = java.io.File(directory, safeName)
                status.text = "正在转储 ${(to - from) / 1024} KB…"
                status.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    val written = MemoryEngine.dumpMemory(from, to, outputFile)
                    handler.post {
                        if (written >= 0L) {
                            status.text = "已转储 $written 字节\n${outputFile.absolutePath}"
                            status.setTextColor(Color.parseColor("#C8F7DC"))
                            copyToClipboard(outputFile.absolutePath)
                            Toast.makeText(this@OverlayService, "路径已复制", Toast.LENGTH_SHORT).show()
                        } else {
                            status.text = "转储失败，请检查地址范围和读取权限"
                            status.setTextColor(Color.parseColor("#FFB4AB"))
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(dp(92), dp(40)))
            dumpCard.addView(dumpFooter)
            content.addView(dumpCard)

            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            bottom.addView(actionButton("内存编辑器") { showMemoryEditorPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            bottom.addView(actionButton("刷新区域") { showMemoryToolsPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(bottom)
        }, 400, 540, onBack = { showMainMenu() }, titleIcon = R.drawable.ic_agg_memory)
    }

    private fun showProcessPanel() {
        saveLastPanel("process")
        makeDraggablePanel("选择进程", { content ->
            content.setPadding(dp(8), dp(8), dp(8), dp(8))
            val attachedPid = MemoryEngine.getAttachedPid()
            if (pendingProcessSelection?.get("pid") != attachedPid) {
                pendingProcessSelection = null
            }

            val searchCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = aggMenuDrawable(Color.parseColor("#241F2A"), 10, Color.parseColor("#625B71"))
            }
            val searchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val queryInput = EditText(this).apply {
                hint = "搜索"
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 12f
                setSingleLine(true)
                setPadding(dp(10), 0, dp(8), 0)
                background = aggMenuDrawable(Color.parseColor("#1F1B24"), 8, Color.parseColor("#79747E"))
            }
            searchRow.addView(queryInput, LinearLayout.LayoutParams(0, dp(42), 1f))
            val searchButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_agg_search)
                setColorFilter(Color.parseColor("#E8DEF8"))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = aggMenuDrawable(Color.parseColor("#4A4458"), 9, Color.parseColor("#675F72"))
            }
            searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) })
            searchCard.addView(searchRow)

            val helper = TextView(this).apply {
                text = if (attachedPid != null) "已选中[1]项 · 当前 PID $attachedPid" else "已选中[0]项"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(dp(3), dp(3), dp(3), 0)
            }
            searchCard.addView(helper)
            content.addView(searchCard)

            val status = TextView(this).apply {
                text = "正在扫描运行进程…"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                setPadding(dp(5), dp(5), dp(5), dp(4))
            }
            content.addView(status)

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), 0, dp(4))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            }
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), 0)
            }
            val filterButton = TextView(this).apply {
                text = "过滤"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E8DEF8"))
                textSize = 10f
                background = aggMenuDrawable(Color.parseColor("#302D35"), 18, Color.parseColor("#49454F"))
                setOnClickListener { showAggProcessFilterPanel(returnToProcess = true) }
            }
            footer.addView(filterButton, LinearLayout.LayoutParams(dp(62), dp(38)))
            footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val detachButton = TextView(this).apply {
                text = "分离"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FFB4AB"))
                textSize = 10f
                background = aggMenuDrawable(Color.parseColor("#35232A"), 18, Color.parseColor("#68404A"))
                setOnClickListener {
                    MemoryEngine.detachProcess()
                    clearAttachedProcessInfo()
                    pendingProcessSelection = null
                    searchResults = emptyList()
                    selectedIndices.clear()
                    helper.text = "已选中[0]项"
                    loadProcs(list, status, helper, queryInput.text.toString())
                }
            }
            footer.addView(detachButton, LinearLayout.LayoutParams(dp(62), dp(38)).apply { marginEnd = dp(7) })
            val confirmButton = ImageView(this).apply {
                setImageResource(android.R.drawable.checkbox_on_background)
                setColorFilter(Color.parseColor("#231A2E"))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = aggMenuDrawable(Color.parseColor("#D0BCFF"), 24, Color.parseColor("#E8DEF8"))
                contentDescription = "确认选择进程"
                setOnClickListener {
                    attachPendingProcess(status, helper)
                }
            }
            footer.addView(confirmButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(footer)

            searchButton.setOnClickListener {
                loadProcs(list, status, helper, queryInput.text.toString())
            }
            queryInput.setOnEditorActionListener { _, _, _ ->
                loadProcs(list, status, helper, queryInput.text.toString())
                true
            }

            loadProcs(list, status, helper)
        }, 382, 548, titleIcon = R.drawable.ic_agg_apps)
    }

    private fun attachPendingProcess(status: TextView, helper: TextView) {
        val selected = pendingProcessSelection
        if (selected == null) {
            val currentPid = MemoryEngine.getAttachedPid()
            if (currentPid != null) {
                showAggSearchTab()
            } else {
                status.text = "请先在列表中选择一个进程"
                status.setTextColor(Color.parseColor("#FFB4AB"))
            }
            return
        }
        val pid = selected["pid"] as? Int ?: return
        val pkg = selected["packageName"]?.toString().orEmpty()
        val name = selected["processName"]?.toString().orEmpty().ifBlank { pkg }
        status.text = "正在附加 $name…"
        status.setTextColor(Color.parseColor("#D0BCFF"))
        Thread {
            val ok = MemoryEngine.attachProcess(pid)
            handler.post {
                if (ok) {
                    saveAttachedProcess(pid, pkg, name)
                    searchResults = emptyList()
                    selectedIndices.clear()
                    savedSearchInput = ""
                    savedFilterInput = ""
                    savedRangeMin = ""
                    savedRangeMax = ""
                    helper.text = "已选中[1]项 · PID $pid · ${MemoryEngine.getIoModeLabel()}"
                    status.text = "已附加 $name"
                    status.setTextColor(Color.parseColor("#C8F7DC"))
                    showAggSearchTab()
                } else {
                    clearAttachedProcessInfo()
                    val reason = MemoryEngine.getAttachError().ifBlank { "请检查 Root 权限和进程状态" }
                    status.text = "附加失败：$reason"
                    status.setTextColor(Color.parseColor("#FFB4AB"))
                }
            }
        }.start()
    }

    private fun loadProcs(
        list: LinearLayout,
        status: TextView,
        helper: TextView,
        query: String = "",
    ) {
        status.text = "正在扫描运行进程…"
        status.setTextColor(Color.parseColor("#CAC4D0"))
        list.removeAllViews()
        Thread {
            val keyword = query.trim().lowercase()
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val filterSystem = prefs.getBoolean(PREF_FILTER_SYSTEM, true)
            val filterLinux = prefs.getBoolean(PREF_FILTER_LINUX, true)
            val allProcesses = ProcessManager.getProcessList(this@OverlayService)
            val procs = allProcesses.filter { process ->
                val pkg = process["packageName"]?.toString().orEmpty()
                val name = process["processName"]?.toString().orEmpty()
                val rawName = process["rawProcessName"]?.toString().orEmpty()
                val appLabel = process["appLabel"]?.toString().orEmpty()
                val isSystem = process["isSystem"] as? Boolean ?: false
                val isLinux = process["isLinux"] as? Boolean ?: false
                val uid = (process["uid"] as? Number)?.toInt()?.toString().orEmpty()
                (!filterSystem || !isSystem) &&
                        (!filterLinux || !isLinux) &&
                        (keyword.isEmpty() || listOf(pkg, name, rawName, appLabel, uid).any {
                            it.lowercase().contains(keyword)
                        })
            }
            handler.post {
                val currentPid = MemoryEngine.getAttachedPid()
                val pendingPid = (pendingProcessSelection?.get("pid") as? Number)?.toInt()
                helper.text = when {
                    pendingPid != null -> "已选中[1]项 · PID $pendingPid"
                    currentPid != null -> "已选中[1]项 · 当前 PID $currentPid"
                    else -> "已选中[0]项"
                }
                status.text = "${procs.size} 个运行进程"
                status.setTextColor(Color.parseColor("#CAC4D0"))

                if (procs.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "没有找到匹配的运行进程\n可在“过滤”中关闭系统应用或 Linux 进程过滤"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 11f
                        setPadding(dp(8), dp(34), dp(8), dp(34))
                    })
                    return@post
                }

                for (proc in procs) {
                    val name = proc["processName"]?.toString().orEmpty()
                    val pkg = proc["packageName"]?.toString().orEmpty()
                    val rawName = proc["rawProcessName"]?.toString().orEmpty()
                    val pid = proc["pid"] as Int
                    val uid = (proc["uid"] as? Number)?.toInt() ?: -1
                    val isSystem = proc["isSystem"] as? Boolean ?: false
                    val isLinux = proc["isLinux"] as? Boolean ?: false
                    val isMain = proc["isMainProcess"] as? Boolean ?: false
                    val isSelected = pendingPid == pid || (pendingPid == null && currentPid == pid)

                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        background = aggMenuDrawable(
                            if (isSelected) Color.parseColor("#493E58") else Color.parseColor("#29252E"),
                            10,
                            if (isSelected) Color.parseColor("#D0BCFF") else Color.TRANSPARENT,
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            marginStart = dp(12)
                            marginEnd = dp(12)
                            bottomMargin = dp(5)
                        }
                    }

                    val icon = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        try {
                            setImageDrawable(packageManager.getApplicationIcon(pkg))
                        } catch (_: Exception) {
                            setImageResource(R.drawable.ic_agg_apps)
                            setColorFilter(Color.parseColor("#D0BCFF"))
                        }
                    }
                    card.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })

                    val texts = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    texts.addView(TextView(this).apply {
                        text = name
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.parseColor("#F3EDF7"))
                        textSize = 13f
                    })
                    texts.addView(TextView(this).apply {
                        text = buildString {
                            append(
                                when {
                                    isLinux -> "Linux进程"
                                    isSystem -> "系统应用"
                                    else -> "应用进程"
                                }
                            )
                            if (isMain) append(" · 主进程")
                            else if (rawName.contains(':')) append(" · 子进程")
                            if (uid >= 0) append(" · UID ").append(uid)
                        }
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.parseColor("#CAC4D0"))
                        textSize = 10f
                    })
                    texts.addView(TextView(this).apply {
                        text = rawName.ifBlank { pkg }
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 9f
                    })
                    card.addView(texts)
                    card.addView(TextView(this).apply {
                        text = "PID\n$pid"
                        gravity = Gravity.CENTER
                        setTextColor(if (isSelected) Color.parseColor("#EADDFF") else Color.parseColor("#CAC4D0"))
                        textSize = 10f
                    }, LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT))

                    card.setOnClickListener {
                        pendingProcessSelection = proc
                        helper.text = "已选中[1]项 · PID $pid"
                        loadProcs(list, status, helper, query)
                    }
                    card.setOnLongClickListener {
                        pendingProcessSelection = proc
                        attachPendingProcess(status, helper)
                        true
                    }
                    list.addView(card)
                }
            }
        }.start()
    }

    // 保存附加的进程信息，供主应用读取
    private fun saveAttachedProcess(pid: Int, packageName: String, processName: String) {
        try {
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("attached_pid", pid)
                putString("attached_package", packageName)
                putString("attached_name", processName)
                putLong("attached_time", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun clearAttachedProcessInfo() {
        try {
            getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
                .remove("attached_pid")
                .remove("attached_package")
                .remove("attached_name")
                .remove("attached_time")
                .apply()
        } catch (_: Exception) {}
    }

    private fun showMemoryEditorPanel(
        initialAddress: Long = memoryEditorAddress,
        initialType: String = memoryEditorType,
    ) {
        val attachedPid = MemoryEngine.getAttachedPid()
        if (attachedPid == null || !MemoryEngine.isAttachedProcessAlive()) {
            showProcessPanel()
            return
        }

        if (initialAddress > 0L) memoryEditorAddress = initialAddress
        if (MemoryEngine.isSupportedType(initialType)) memoryEditorType = initialType
        if (memoryEditorAddress <= 0L) {
            memoryEditorAddress = (MemoryEngine.getMemoryRegions().firstOrNull()?.get("startAddress") as? Number)?.toLong() ?: 1L
        }
        saveLastPanel("editor")

        makeDraggablePanel("内存编辑器", { content ->
            val pageItems = 36
            val types = arrayOf("dword", "float", "double", "byte", "word", "qword")
            val addressInput = EditText(this).apply {
                setText("0x${memoryEditorAddress.toString(16).uppercase()}")
                hint = "输入十六进制地址"
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 11.5f
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setPadding(dp(11), 0, dp(10), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            val typeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, types).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val typeSpinner = Spinner(this).apply {
                adapter = typeAdapter
                setSelection(types.indexOf(memoryEditorType).coerceAtLeast(0))
                background = aggMenuDrawable(Color.parseColor("#34313A"), 9, Color.parseColor("#49454F"))
            }
            val jumpButton = TextView(this).apply {
                text = "跳转"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#231A2E"))
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(Color.parseColor("#D0BCFF"), 9, Color.parseColor("#E8DEF8"))
            }
            val addressRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(addressInput, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(typeSpinner, LinearLayout.LayoutParams(dp(78), dp(40)).apply { marginStart = dp(5) })
                addView(jumpButton, LinearLayout.LayoutParams(dp(58), dp(40)).apply { marginStart = dp(5) })
            }
            content.addView(addressRow)

            val status = TextView(this).apply {
                text = "正在读取内存…"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(dp(4), dp(5), dp(4), dp(5))
            }
            content.addView(status)

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            }
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            fun parseEditorAddress(raw: String): Long? {
                val text = raw.trim()
                return when {
                    text.startsWith("0x", ignoreCase = true) -> text.substring(2).toLongOrNull(16)
                    text.matches(Regex("[0-9A-Fa-f]{6,}")) -> text.toLongOrNull(16)
                    else -> text.toLongOrNull()
                }
            }

            fun renderRows(rows: List<Map<String, Any>>) {
                list.removeAllViews()
                if (rows.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "该地址不可读或已离开有效内存区域"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#FFB4AB"))
                        textSize = 11f
                        setPadding(dp(8), dp(42), dp(8), dp(42))
                    })
                    status.text = "读取失败"
                    status.setTextColor(Color.parseColor("#FFB4AB"))
                    return
                }
                status.text = "${rows.first()["address"]} — ${rows.last()["address"]}  ·  ${rows.size} 项"
                status.setTextColor(Color.parseColor("#C8F7DC"))
                for ((index, item) in rows.withIndex()) {
                    val address = (item["addressInt"] as Number).toLong()
                    val addressText = item["address"] as String
                    val value = item["value"]
                    val rawBytes = item["machineCode"] as? String ?: ""
                    val frozen = MemoryFreezer.isFrozen(address)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(7), dp(5), dp(7), dp(5))
                        background = aggMenuDrawable(
                            if (frozen) Color.parseColor("#332B3D") else if (index % 2 == 0) Color.parseColor("#25222B") else Color.parseColor("#211F26"),
                            7,
                            if (frozen) Color.parseColor("#B69DF8") else Color.parseColor("#343039"),
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(42),
                        ).apply { bottomMargin = dp(2) }
                        setOnClickListener {
                            showWriteDialog(
                                addressText,
                                value,
                                rawBytes,
                                memoryEditorType,
                                returnAction = { showMemoryEditorPanel(address, memoryEditorType) },
                            )
                        }
                        setOnLongClickListener {
                            copyToClipboard(addressText)
                            Toast.makeText(this@OverlayService, "已复制 $addressText", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                    row.addView(TextView(this).apply {
                        text = addressText
                        setTextColor(Color.parseColor("#D0BCFF"))
                        textSize = 9.5f
                        typeface = android.graphics.Typeface.MONOSPACE
                        maxLines = 1
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f))
                    row.addView(TextView(this).apply {
                        text = value.toString()
                        setTextColor(if (frozen) Color.parseColor("#C8F7DC") else Color.parseColor("#F3EDF7"))
                        textSize = 10.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        maxLines = 1
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f))
                    row.addView(TextView(this).apply {
                        text = rawBytes
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 8.5f
                        typeface = android.graphics.Typeface.MONOSPACE
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        maxLines = 1
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
                    list.addView(row)
                }
            }

            fun loadPage() {
                addressInput.setText("0x${memoryEditorAddress.toString(16).uppercase()}")
                status.text = "正在读取 ${memoryEditorType.uppercase()} 数据…"
                status.setTextColor(Color.parseColor("#D0BCFF"))
                list.removeAllViews()
                Thread {
                    val rows = MemoryEngine.readMemoryWindow(memoryEditorAddress, pageItems, memoryEditorType)
                    handler.post { renderRows(rows) }
                }.start()
            }

            jumpButton.setOnClickListener {
                val parsed = parseEditorAddress(addressInput.text.toString())
                val selectedType = typeSpinner.selectedItem.toString()
                if (parsed == null || parsed <= 0L) {
                    status.text = "地址格式不正确"
                    status.setTextColor(Color.parseColor("#FFB4AB"))
                } else {
                    memoryEditorAddress = parsed
                    memoryEditorType = selectedType
                    loadPage()
                }
            }

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            fun editorButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.5f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }
            controls.addView(editorButton("上一页") {
                val step = pageItems.toLong() * MemoryEngine.getTypeSize(memoryEditorType)
                memoryEditorAddress = (memoryEditorAddress - step).coerceAtLeast(1L)
                loadPage()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(2) })
            controls.addView(editorButton("刷新") { loadPage() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            controls.addView(editorButton("下一页") {
                val step = pageItems.toLong() * MemoryEngine.getTypeSize(memoryEditorType)
                memoryEditorAddress += step
                loadPage()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            controls.addView(editorButton("返回搜索", true) { showAggSearchTab() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2) })
            content.addView(controls)

            loadPage()
        }, 410, 590, onBack = { showMainMenu() }, titleIcon = R.drawable.ic_agg_edit)
    }

    private fun savedItemKey(item: SavedMemoryItem): String =
        "${item.packageName}:${item.address}:${item.type}"

    private fun loadSavedMemoryItems(): MutableList<SavedMemoryItem> {
        val raw = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            .getString("saved_memory_items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                SavedMemoryItem(
                    address = item.optString("address").toLongOrNull()
                        ?: item.optLong("address", 0L),
                    type = item.optString("type", "dword"),
                    packageName = item.optString("packageName", ""),
                    label = item.optString("label", "保存项 ${index + 1}"),
                    lastValue = item.optString("lastValue", "0"),
                    freeze = item.optBoolean("freeze", false),
                    freezeType = item.optInt("freezeType", MemoryFreezer.FREEZE_NORMAL)
                        .coerceIn(MemoryFreezer.FREEZE_NORMAL, MemoryFreezer.FREEZE_IN_RANGE),
                    freezeFrom = item.optString("freezeFrom", ""),
                    freezeTo = item.optString("freezeTo", ""),
                )
            }.filterTo(mutableListOf()) { it.address > 0L && MemoryEngine.isSupportedType(it.type) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persistSavedMemoryItems(items: List<SavedMemoryItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("address", item.address.toString())
                put("type", item.type)
                put("packageName", item.packageName)
                put("label", item.label)
                put("lastValue", item.lastValue)
                put("freeze", item.freeze)
                put("freezeType", item.freezeType)
                put("freezeFrom", item.freezeFrom)
                put("freezeTo", item.freezeTo)
            })
        }
        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
            .putString("saved_memory_items", array.toString())
            .apply()
    }

    private fun savedListDirectory(): java.io.File {
        return (getExternalFilesDir("saved_lists") ?: java.io.File(filesDir, "saved_lists")).apply { mkdirs() }
    }

    private fun savedItemsToJson(items: List<SavedMemoryItem>): JSONArray {
        return JSONArray().apply {
            for (item in items) {
                put(JSONObject().apply {
                    put("address", item.address.toString())
                    put("type", item.type)
                    put("packageName", item.packageName)
                    put("label", item.label)
                    put("lastValue", item.lastValue)
                    put("freeze", item.freeze)
                    put("freezeType", item.freezeType)
                    put("freezeFrom", item.freezeFrom)
                    put("freezeTo", item.freezeTo)
                })
            }
        }
    }

    private fun exportSavedMemoryItems(items: List<SavedMemoryItem>, rawName: String, asText: Boolean): java.io.File? {
        if (items.isEmpty()) return null
        val safeBase = rawName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
            .ifBlank { "saved_list_${System.currentTimeMillis()}" }
        val extension = if (asText) ".txt" else ".json"
        val fileName = if (safeBase.endsWith(extension, ignoreCase = true)) safeBase else safeBase.substringBeforeLast('.', safeBase) + extension
        val output = java.io.File(savedListDirectory(), fileName)
        return try {
            if (asText) {
                output.bufferedWriter().use { writer ->
                    writer.appendLine("# GG-AI SAVED LIST v1")
                    writer.appendLine("# address\ttype\tvalue\tfreeze\tfreezeType\tfreezeFrom\tfreezeTo\tlabel\tpackageName")
                    for (item in items) {
                        fun clean(value: String): String = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
                        writer.append("0x${item.address.toString(16).uppercase()}").append('\t')
                            .append(item.type).append('\t')
                            .append(clean(item.lastValue)).append('\t')
                            .append(item.freeze.toString()).append('\t')
                            .append(item.freezeType.toString()).append('\t')
                            .append(clean(item.freezeFrom)).append('\t')
                            .append(clean(item.freezeTo)).append('\t')
                            .append(clean(item.label)).append('\t')
                            .appendLine(clean(item.packageName))
                    }
                }
            } else {
                val root = JSONObject().apply {
                    put("format", "GG-AI-SAVED-LIST")
                    put("version", 1)
                    put("createdAt", System.currentTimeMillis())
                    put("items", savedItemsToJson(items))
                }
                output.writeText(root.toString(2))
            }
            output
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSavedMemoryItems(file: java.io.File): List<SavedMemoryItem> {
        if (!file.isFile || file.length() <= 0L || file.length() > 16L * 1024L * 1024L) return emptyList()
        return try {
            val text = file.readText()
            if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                val trimmed = text.trim()
                val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
                MutableList(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    SavedMemoryItem(
                        address = item.optString("address").removePrefix("0x").removePrefix("0X").let { raw ->
                            raw.toLongOrNull() ?: raw.toLongOrNull(16) ?: item.optLong("address", 0L)
                        },
                        type = item.optString("type", "dword").lowercase(),
                        packageName = item.optString("packageName", ""),
                        label = item.optString("label", "导入项 ${index + 1}"),
                        lastValue = item.optString("lastValue", item.optString("value", "0")),
                        freeze = item.optBoolean("freeze", false),
                        freezeType = item.optInt("freezeType", MemoryFreezer.FREEZE_NORMAL)
                            .coerceIn(MemoryFreezer.FREEZE_NORMAL, MemoryFreezer.FREEZE_IN_RANGE),
                        freezeFrom = item.optString("freezeFrom", ""),
                        freezeTo = item.optString("freezeTo", ""),
                    )
                }.filter { it.address > 0L && MemoryEngine.isSupportedType(it.type) }
            } else {
                text.lineSequence().mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val addressRaw = parts[0].trim().removePrefix("0x").removePrefix("0X")
                    val address = addressRaw.toLongOrNull(16) ?: addressRaw.toLongOrNull() ?: return@mapNotNull null
                    val type = parts[1].trim().lowercase()
                    if (!MemoryEngine.isSupportedType(type)) return@mapNotNull null
                    SavedMemoryItem(
                        address = address,
                        type = type,
                        packageName = parts.getOrNull(8)?.trim().orEmpty(),
                        label = parts.getOrNull(7)?.trim().takeUnless { it.isNullOrEmpty() } ?: "地址 0x${address.toString(16).uppercase()}",
                        lastValue = parts.getOrNull(2)?.trim().orEmpty().ifBlank { "0" },
                        freeze = parts.getOrNull(3)?.trim()?.toBooleanStrictOrNull() ?: false,
                        freezeType = parts.getOrNull(4)?.trim()?.toIntOrNull()
                            ?.coerceIn(MemoryFreezer.FREEZE_NORMAL, MemoryFreezer.FREEZE_IN_RANGE)
                            ?: MemoryFreezer.FREEZE_NORMAL,
                        freezeFrom = parts.getOrNull(5)?.trim().orEmpty(),
                        freezeTo = parts.getOrNull(6)?.trim().orEmpty(),
                    )
                }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeImportedSavedItems(imported: List<SavedMemoryItem>, append: Boolean): Int {
        if (imported.isEmpty()) return 0
        val currentPackage = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            .getString("attached_package", "")
            ?.takeIf { it.isNotBlank() }
            ?: "pid:${MemoryEngine.getAttachedPid() ?: 0}"
        val base = if (append) loadSavedMemoryItems() else mutableListOf()
        var changed = 0
        for (source in imported) {
            val item = source.copy(packageName = source.packageName.ifBlank { currentPackage })
            val index = base.indexOfFirst { savedItemKey(it) == savedItemKey(item) }
            if (index >= 0) base[index] = item else base.add(item)
            changed++
        }
        persistSavedMemoryItems(base)
        return changed
    }

    private fun addResultsToSavedList(results: List<Map<String, Any>>): Int {
        if (results.isEmpty()) return 0
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val packageName = prefs.getString("attached_package", "")
            ?.takeIf { it.isNotBlank() }
            ?: "pid:${MemoryEngine.getAttachedPid() ?: 0}"
        val items = loadSavedMemoryItems()
        var changed = 0

        for (result in results) {
            val address = (result["addressInt"] as? Number)?.toLong()
                ?: (result["address"] as? String)
                    ?.removePrefix("0x")
                    ?.removePrefix("0X")
                    ?.toLongOrNull(16)
                ?: continue
            val type = (result["type"] as? String)
                ?.takeIf { MemoryEngine.isSupportedType(it) }
                ?: searchDataType.takeIf { MemoryEngine.isSupportedType(it) }
                ?: "dword"
            val addressText = "0x${address.toString(16).uppercase()}"
            val candidate = SavedMemoryItem(
                address = address,
                type = type,
                packageName = packageName,
                label = "地址 $addressText",
                lastValue = result["value"]?.toString() ?: "0",
                freeze = MemoryFreezer.isFrozen(address),
                freezeType = MemoryFreezer.getFreezeType(address) ?: MemoryFreezer.FREEZE_NORMAL,
            )
            val index = items.indexOfFirst { savedItemKey(it) == savedItemKey(candidate) }
            if (index >= 0) {
                val old = items[index]
                items[index] = candidate.copy(label = old.label)
            } else {
                items.add(candidate)
            }
            changed++
        }

        persistSavedMemoryItems(items)
        return changed
    }

    private fun freezeModeLabel(mode: Int): String {
        return when (mode) {
            MemoryFreezer.FREEZE_MAY_INCREASE -> "只许增大"
            MemoryFreezer.FREEZE_MAY_DECREASE -> "只许减小"
            MemoryFreezer.FREEZE_IN_RANGE -> "限制范围"
            else -> "固定数值"
        }
    }

    private fun showSavedFreezeSettingsPanel(item: SavedMemoryItem) {
        makeDraggablePanel("冻结设置", { content ->
            content.addView(TextView(this).apply {
                text = "${item.label}\n0x${item.address.toString(16).uppercase()}  ·  ${item.type.uppercase()}"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(6), dp(3), dp(6), dp(7))
            })

            var selectedMode = item.freezeType
            val modeGroup = android.widget.RadioGroup(this).apply {
                orientation = android.widget.RadioGroup.VERTICAL
                setPadding(dp(5), dp(3), dp(5), dp(3))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            val modes = listOf(
                MemoryFreezer.FREEZE_NORMAL to "固定数值 · 不允许变化",
                MemoryFreezer.FREEZE_MAY_INCREASE to "只许增大 · 下降时恢复",
                MemoryFreezer.FREEZE_MAY_DECREASE to "只许减小 · 上升时恢复",
                MemoryFreezer.FREEZE_IN_RANGE to "限制范围 · 超出时拉回边界",
            )
            for ((mode, label) in modes) {
                modeGroup.addView(android.widget.RadioButton(this).apply {
                    id = View.generateViewId()
                    tag = mode
                    text = label
                    textSize = 10f
                    setTextColor(Color.parseColor("#E6E0E9"))
                    buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
                    isChecked = mode == selectedMode
                    setOnCheckedChangeListener { _, checked -> if (checked) selectedMode = mode }
                })
            }
            content.addView(modeGroup)

            val rangeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            fun rangeInput(hintText: String, initial: String): EditText {
                return EditText(this).apply {
                    hint = hintText
                    setText(initial)
                    setSingleLine(true)
                    textSize = 11f
                    setTextColor(Color.parseColor("#F3EDF7"))
                    setHintTextColor(Color.parseColor("#938F99"))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    setPadding(dp(10), 0, dp(10), 0)
                    background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#49454F"))
                }
            }
            val fromInput = rangeInput("范围下限", item.freezeFrom.ifBlank { item.lastValue })
            val toInput = rangeInput("范围上限", item.freezeTo.ifBlank { item.lastValue })
            rangeRow.addView(fromInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            rangeRow.addView(toInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
            content.addView(rangeRow)

            val state = TextView(this).apply {
                text = "区间输入仅在“限制范围”模式生效"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 9f
                setPadding(dp(4), dp(5), dp(4), dp(2))
            }
            content.addView(state)

            fun panelButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            actions.addView(panelButton("取消") { showSavedListPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
            actions.addView(panelButton("启用冻结", true) {
                val current = MemoryEngine.readMemory(item.address, item.type)
                    ?: parseMemoryValue(item.lastValue, item.type)
                if (current == null) {
                    state.text = "无法读取或解析当前值"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return@panelButton
                }
                val from = if (selectedMode == MemoryFreezer.FREEZE_IN_RANGE) {
                    parseMemoryValue(fromInput.text.toString().trim(), item.type)
                } else null
                val to = if (selectedMode == MemoryFreezer.FREEZE_IN_RANGE) {
                    parseMemoryValue(toInput.text.toString().trim(), item.type)
                } else null
                if (selectedMode == MemoryFreezer.FREEZE_IN_RANGE && (from == null || to == null)) {
                    state.text = "请输入有效的范围上下限"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return@panelButton
                }
                state.text = "正在启用 ${freezeModeLabel(selectedMode)}…"
                state.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    val success = MemoryFreezer.freeze(item.address, current, item.type, selectedMode, from, to)
                    if (success) {
                        val items = loadSavedMemoryItems()
                        val index = items.indexOfFirst { savedItemKey(it) == savedItemKey(item) }
                        if (index >= 0) {
                            items[index] = item.copy(
                                lastValue = current.toString(),
                                freeze = true,
                                freezeType = selectedMode,
                                freezeFrom = from?.toString() ?: "",
                                freezeTo = to?.toString() ?: "",
                            )
                            persistSavedMemoryItems(items)
                        }
                    }
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "已启用 ${freezeModeLabel(selectedMode)}" else "冻结失败", Toast.LENGTH_SHORT).show()
                        if (success) showSavedListPanel()
                        else {
                            state.text = "冻结失败，请检查进程、地址和值类型"
                            state.setTextColor(Color.parseColor("#FFB4AB"))
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
            content.addView(actions)
        }, 380, 440, onBack = { showSavedListPanel() }, titleIcon = R.drawable.ic_agg_lock)
    }

    private fun showSavedListExportPanel(items: List<SavedMemoryItem>) {
        makeDraggablePanel("导出保存列表", { content ->
            content.addView(TextView(this).apply {
                text = "将导出 ${items.size} 条保存项，包含名称、数值、类型和冻结设置"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                setPadding(dp(5), dp(3), dp(5), dp(7))
            })
            val nameInput = EditText(this).apply {
                setText("saved_list_${System.currentTimeMillis()}")
                setSingleLine(true)
                textSize = 11f
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                hint = "文件名"
                setPadding(dp(11), 0, dp(11), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            content.addView(nameInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

            val formats = arrayOf("JSON 完整备份", "TXT 可读文本")
            val spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, formats).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                background = aggMenuDrawable(Color.parseColor("#34313A"), 9, Color.parseColor("#49454F"))
            }
            content.addView(spinner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(7) })

            val state = TextView(this).apply {
                text = "保存目录：${savedListDirectory().absolutePath}"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 8.8f
                maxLines = 3
                setPadding(dp(5), dp(7), dp(5), dp(3))
            }
            content.addView(state)

            fun button(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(button("取消") { showSavedListPanel() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            actions.addView(button("导出", true) {
                val file = exportSavedMemoryItems(items, nameInput.text.toString(), spinner.selectedItemPosition == 1)
                if (file == null) {
                    state.text = "导出失败，请检查保存项和文件名"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                } else {
                    state.text = "已导出 ${items.size} 条\n${file.absolutePath}"
                    state.setTextColor(Color.parseColor("#C8F7DC"))
                    copyToClipboard(file.absolutePath)
                    Toast.makeText(this@OverlayService, "导出成功，路径已复制", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            content.addView(actions)
        }, 370, 310, onBack = { showSavedListPanel() }, titleIcon = R.drawable.ic_agg_copy)
    }

    private fun showSavedListImportPanel() {
        makeDraggablePanel("导入保存列表", { content ->
            val status = TextView(this).apply {
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(dp(5), dp(2), dp(5), dp(6))
            }
            content.addView(status)
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            }
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            fun importButton(label: String, danger: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9f
                    setTextColor(if (danger) Color.parseColor("#FFB4AB") else Color.parseColor("#E6E0E9"))
                    background = aggMenuDrawable(
                        if (danger) Color.parseColor("#35232A") else Color.parseColor("#302D35"),
                        7,
                        if (danger) Color.parseColor("#68404A") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }

            fun renderFiles() {
                list.removeAllViews()
                val files = savedListDirectory().listFiles()
                    ?.filter { it.isFile && (it.extension.equals("json", true) || it.extension.equals("txt", true)) }
                    ?.sortedByDescending { it.lastModified() }
                    .orEmpty()
                status.text = "找到 ${files.size} 个列表文件 · 追加会合并同地址，替换会清空当前列表"
                if (files.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "没有可导入文件\n请先使用“导出”生成列表文件"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 10.5f
                        setPadding(dp(8), dp(42), dp(8), dp(42))
                    })
                    return
                }
                for (file in files) {
                    val parsed = parseSavedMemoryItems(file)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(7), dp(8), dp(7))
                        background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#3A3641"))
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                    }
                    row.addView(TextView(this).apply {
                        text = file.name
                        setTextColor(Color.parseColor("#F3EDF7"))
                        textSize = 11f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                    row.addView(TextView(this).apply {
                        text = "${parsed.size} 条 · ${file.length()} 字节 · ${file.extension.uppercase()}"
                        setTextColor(if (parsed.isEmpty()) Color.parseColor("#FFB4AB") else Color.parseColor("#938F99"))
                        textSize = 8.8f
                        setPadding(0, dp(2), 0, dp(5))
                    })
                    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    buttons.addView(importButton("追加") {
                        val count = mergeImportedSavedItems(parsed, append = true)
                        Toast.makeText(this@OverlayService, "已追加 $count 条", Toast.LENGTH_SHORT).show()
                        showSavedListPanel()
                    }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(2) })
                    buttons.addView(importButton("替换") {
                        val count = mergeImportedSavedItems(parsed, append = false)
                        MemoryFreezer.clearAll()
                        Toast.makeText(this@OverlayService, "已替换为 $count 条", Toast.LENGTH_SHORT).show()
                        showSavedListPanel()
                    }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
                    buttons.addView(importButton("删除文件", danger = true) {
                        if (file.delete()) renderFiles()
                        else Toast.makeText(this@OverlayService, "删除失败", Toast.LENGTH_SHORT).show()
                    }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(2) })
                    row.addView(buttons)
                    list.addView(row)
                }
            }

            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            footer.addView(importButton("刷新文件") { renderFiles() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            footer.addView(importButton("返回列表") { showSavedListPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(footer)
            renderFiles()
        }, 390, 520, onBack = { showSavedListPanel() }, titleIcon = R.drawable.ic_agg_lock)
    }

    private fun showSavedListPanel() {
        saveLastPanel("saved")
        makeDraggablePanel("保存列表", { content ->
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val currentPackage = prefs.getString("attached_package", "") ?: ""
            val currentPid = MemoryEngine.getAttachedPid()
            var allItems = loadSavedMemoryItems()
            val liveValues = mutableMapOf<String, Any>()

            fun visibleItems(): List<SavedMemoryItem> {
                return if (currentPackage.isBlank()) allItems
                else allItems.filter { it.packageName == currentPackage || it.packageName == "pid:$currentPid" }
            }

            val status = TextView(this).apply {
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                setPadding(dp(6), dp(2), dp(6), dp(6))
            }
            content.addView(status)

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            }
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            fun replaceItem(old: SavedMemoryItem, updated: SavedMemoryItem) {
                val key = savedItemKey(old)
                val index = allItems.indexOfFirst { savedItemKey(it) == key }
                if (index >= 0) {
                    allItems[index] = updated
                    persistSavedMemoryItems(allItems)
                }
            }

            fun itemAction(iconRes: Int, label: String, tint: String, action: () -> Unit): LinearLayout {
                return LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = aggMenuDrawable(Color.parseColor("#34313A"), 8, Color.parseColor("#49454F"))
                    setOnClickListener { pressAndRun(this) { action() } }
                    addView(ImageView(this@OverlayService).apply {
                        setImageResource(iconRes)
                        setColorFilter(Color.parseColor(tint))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                    }, LinearLayout.LayoutParams(dp(17), dp(17)))
                    addView(TextView(this@OverlayService).apply {
                        text = label
                        setTextColor(Color.parseColor(tint))
                        textSize = 8f
                        gravity = Gravity.CENTER
                    })
                }
            }

            fun render() {
                list.removeAllViews()
                val items = visibleItems()
                status.text = if (currentPackage.isBlank()) {
                    "全部保存项 ${items.size} 条 · 选择进程后可读取和修改"
                } else {
                    "$currentPackage · ${items.size} 条保存项"
                }
                if (items.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "暂无保存项\n在搜索结果中勾选地址并点击“保存”"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 11f
                        setPadding(dp(8), dp(42), dp(8), dp(42))
                    })
                    return
                }

                for (item in items) {
                    val key = savedItemKey(item)
                    val addressText = "0x${item.address.toString(16).uppercase()}"
                    val liveValue = liveValues[key]?.toString() ?: item.lastValue
                    val canOperate = currentPid != null &&
                            (item.packageName == currentPackage || item.packageName == "pid:$currentPid") &&
                            MemoryEngine.isAttachedProcessAlive()
                    val isFrozen = canOperate && MemoryFreezer.isFrozen(item.address)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(7), dp(6), dp(7), dp(6))
                        background = aggMenuDrawable(
                            if (isFrozen) Color.parseColor("#332B3D") else Color.parseColor("#25222B"),
                            9,
                            if (isFrozen) Color.parseColor("#B69DF8") else Color.parseColor("#3A3641"),
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(4) }
                        setOnClickListener {
                            if (canOperate) {
                                showWriteDialog(
                                    addressText,
                                    liveValue,
                                    dataType = item.type,
                                    returnAction = { showSavedListPanel() },
                                )
                            } else {
                                Toast.makeText(this@OverlayService, "请先附加对应进程", Toast.LENGTH_SHORT).show()
                            }
                        }
                        setOnLongClickListener {
                            showRenameSavedItemPanel(item)
                            true
                        }
                    }
                    row.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(LinearLayout(this@OverlayService).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            addView(TextView(this@OverlayService).apply {
                                text = item.label
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setTextColor(Color.parseColor("#F3EDF7"))
                                textSize = 11.5f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            })
                            addView(TextView(this@OverlayService).apply {
                                text = buildString {
                                    append("$addressText  ·  ${item.type.uppercase()}")
                                    if (item.freeze || isFrozen) append("  ·  ${freezeModeLabel(item.freezeType)}")
                                    if (item.freezeType == MemoryFreezer.FREEZE_IN_RANGE && item.freezeFrom.isNotBlank() && item.freezeTo.isNotBlank()) {
                                        append(" [${item.freezeFrom}, ${item.freezeTo}]")
                                    }
                                }
                                setTextColor(if (item.freeze || isFrozen) Color.parseColor("#B69DF8") else Color.parseColor("#938F99"))
                                textSize = 9.5f
                                typeface = android.graphics.Typeface.MONOSPACE
                                setPadding(0, dp(2), 0, 0)
                            })
                        })
                        addView(TextView(this@OverlayService).apply {
                            text = liveValue
                            maxLines = 1
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                            setTextColor(if (isFrozen) Color.parseColor("#D0BCFF") else Color.parseColor("#E6E0E9"))
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }, LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
                    })

                    val operations = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(7), 0, 0)
                    }
                    operations.addView(itemAction(R.drawable.ic_agg_edit, "重命名", "#E8DEF8") {
                        showRenameSavedItemPanel(item)
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
                    operations.addView(itemAction(R.drawable.ic_agg_lock, if (isFrozen) "解冻" else "冻结设置", if (isFrozen) "#FFB4AB" else "#C8F7DC") {
                        if (!canOperate) {
                            Toast.makeText(this@OverlayService, "请先附加对应进程", Toast.LENGTH_SHORT).show()
                            return@itemAction
                        }
                        if (!isFrozen) {
                            showSavedFreezeSettingsPanel(item.copy(lastValue = liveValue))
                            return@itemAction
                        }
                        Thread {
                            val success = MemoryFreezer.unfreeze(item.address)
                            if (success) replaceItem(item, item.copy(freeze = false, lastValue = liveValue))
                            handler.post {
                                Toast.makeText(this@OverlayService, if (success) "已解冻" else "操作失败", Toast.LENGTH_SHORT).show()
                                render()
                            }
                        }.start()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
                    operations.addView(itemAction(R.drawable.ic_agg_close, "删除", "#FFB4AB") {
                        allItems.removeAll { savedItemKey(it) == key }
                        MemoryFreezer.unfreeze(item.address)
                        persistSavedMemoryItems(allItems)
                        render()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
                    row.addView(operations)
                    list.addView(row)
                }
            }

            fun refreshValues() {
                val items = visibleItems()
                if (items.isEmpty() || currentPid == null || !MemoryEngine.isAttachedProcessAlive()) {
                    render()
                    return
                }
                status.text = "正在刷新保存项数值…"
                Thread {
                    val updated = allItems.toMutableList()
                    for (item in items) {
                        if (item.packageName != currentPackage && item.packageName != "pid:$currentPid") continue
                        val value = MemoryEngine.readMemory(item.address, item.type) ?: continue
                        liveValues[savedItemKey(item)] = value
                        val index = updated.indexOfFirst { savedItemKey(it) == savedItemKey(item) }
                        if (index >= 0) updated[index] = item.copy(lastValue = value.toString())
                    }
                    allItems = updated
                    persistSavedMemoryItems(allItems)
                    handler.post { render() }
                }.start()
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            fun savedButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }
            actions.addView(savedButton("刷新") { refreshValues() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            actions.addView(savedButton("恢复冻结") {
                val items = visibleItems().filter { it.freeze }
                if (items.isEmpty()) {
                    Toast.makeText(this@OverlayService, "没有标记为冻结的保存项", Toast.LENGTH_SHORT).show()
                    return@savedButton
                }
                Thread {
                    var count = 0
                    for (item in items) {
                        val value = MemoryEngine.readMemory(item.address, item.type)
                            ?: parseMemoryValue(item.lastValue, item.type)
                        val from = item.freezeFrom.takeIf { it.isNotBlank() }?.let { parseMemoryValue(it, item.type) }
                        val to = item.freezeTo.takeIf { it.isNotBlank() }?.let { parseMemoryValue(it, item.type) }
                        if (value != null && MemoryFreezer.freeze(item.address, value, item.type, item.freezeType, from, to)) count++
                    }
                    handler.post {
                        Toast.makeText(this@OverlayService, "已恢复冻结 $count/${items.size} 条", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(savedButton("返回搜索", true) { showAggSearchTab() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(actions)

            var clearArmed = false
            val fileActions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            fileActions.addView(savedButton("导出") {
                val items = visibleItems()
                if (items.isEmpty()) Toast.makeText(this@OverlayService, "没有可导出的保存项", Toast.LENGTH_SHORT).show()
                else showSavedListExportPanel(items)
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3) })
            fileActions.addView(savedButton("导入") { showSavedListImportPanel() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            val clearButton = savedButton("清空当前") {
                if (!clearArmed) {
                    clearArmed = true
                    Toast.makeText(this@OverlayService, "再次点击确认清空当前进程保存项", Toast.LENGTH_SHORT).show()
                    handler.postDelayed({ clearArmed = false }, 3000L)
                    return@savedButton
                }
                val keys = visibleItems().map { savedItemKey(it) }.toSet()
                for (item in visibleItems()) MemoryFreezer.unfreeze(item.address)
                allItems.removeAll { savedItemKey(it) in keys }
                persistSavedMemoryItems(allItems)
                clearArmed = false
                render()
            }
            clearButton.setTextColor(Color.parseColor("#FFB4AB"))
            fileActions.addView(clearButton, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(3) })
            content.addView(fileActions)

            render()
            refreshValues()
        }, 390, 570, onBack = { showMainMenu() }, titleIcon = R.drawable.ic_agg_lock)
    }

    private fun showRenameSavedItemPanel(item: SavedMemoryItem) {
        makeDraggablePanel("重命名保存项", { content ->
            content.addView(TextView(this).apply {
                text = "0x${item.address.toString(16).uppercase()}  ·  ${item.type.uppercase()}"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(5), dp(3), dp(5), dp(7))
            })
            val input = EditText(this).apply {
                setText(item.label)
                setSelectAllOnFocus(true)
                setSingleLine(true)
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 12f
                setPadding(dp(12), 0, dp(12), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(TextView(this).apply {
                text = "取消"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E6E0E9"))
                textSize = 10f
                background = aggMenuDrawable(Color.parseColor("#302D35"), 9, Color.parseColor("#49454F"))
                setOnClickListener { showSavedListPanel() }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(4) })
            actions.addView(TextView(this).apply {
                text = "保存名称"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#231A2E"))
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(Color.parseColor("#D0BCFF"), 9, Color.parseColor("#E8DEF8"))
                setOnClickListener {
                    val label = input.text.toString().trim()
                    if (label.isEmpty()) return@setOnClickListener
                    val items = loadSavedMemoryItems()
                    val index = items.indexOfFirst { savedItemKey(it) == savedItemKey(item) }
                    if (index >= 0) {
                        items[index] = item.copy(label = label)
                        persistSavedMemoryItems(items)
                    }
                    showSavedListPanel()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(4) })
            content.addView(actions)
        }, 350, 220, onBack = { showSavedListPanel() }, titleIcon = R.drawable.ic_agg_edit)
    }

    // ==================== 搜索面板 ====================

    private var currentSearchMode = "exact"

    private fun showAggSearchTab() {
        aggMainTab = 1
        showMainMenu()
    }

    private fun showAggSearchDialog() {
        val pid = MemoryEngine.getAttachedPid()
        if (pid == null || !MemoryEngine.isAttachedProcessAlive()) {
            showProcessPanel()
            return
        }
        saveLastPanel("agg_searcher")
        makeDraggablePanel("搜索", { content ->
            content.setPadding(0, 0, 0, 0)
            val scrollBody = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(scrollBody)
            }
            content.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))

            val message = TextView(this).apply {
                text = if (searchResults.isEmpty()) {
                    "请输入要搜索的数值"
                } else {
                    "找到 ${searchResults.size} 个结果。再次搜索会在当前结果中继续筛选。"
                }
                setTextColor(Color.parseColor("#F3EDF7"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, 0, dp(7))
            }
            scrollBody.addView(message)

            fun smallLabel(value: String): TextView = TextView(this).apply {
                text = value
                setTextColor(Color.parseColor("#E6E0E9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
            }

            fun field(initial: String = "", hintValue: String = ""): EditText = EditText(this).apply {
                setText(initial)
                hint = hintValue
                setSingleLine(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#938F99"))
                setPadding(dp(9), 0, dp(9), 0)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                background = aggMenuDrawable(Color.argb(34, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
            }

            fun spinner(values: Array<String>): Spinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, values).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                background = aggMenuDrawable(Color.argb(34, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
            }

            fun button(label: String, action: () -> Unit): TextView = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
                setOnClickListener { action() }
            }

            val typeHint = TextView(this).apply {
                setTextColor(Color.parseColor("#CAC4D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, dp(2))
            }
            scrollBody.addView(typeHint)

            val operators = arrayOf("=", "≠", ">", "<", "范围", "地址", "AOB")
            val operatorSpinner = spinner(operators)
            val initialOperator = when (currentSearchMode) {
                "range" -> "范围"
                "addr" -> "地址"
                "machine" -> "AOB"
                else -> "="
            }
            operatorSpinner.setSelection(operators.indexOf(initialOperator).coerceAtLeast(0))

            val valueInput = field(savedSearchInput, "输入数值")
            val valueRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(smallLabel("值："), LinearLayout.LayoutParams(dp(34), dp(48)))
                addView(operatorSpinner, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(5) })
                addView(valueInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            val converter = button("HEX") {
                val raw = valueInput.text.toString().trim()
                when {
                    raw.startsWith("0x", true) -> raw.substring(2).toLongOrNull(16)?.let {
                        valueInput.setText(it.toString())
                    }
                    raw.toLongOrNull() != null -> valueInput.setText(
                        "0x${raw.toLong().toString(16).uppercase()}"
                    )
                }
            }
            valueRow.addView(converter, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) })
            scrollBody.addView(valueRow)

            val maskInput = field(savedFilterInput, "例如：FF ?? 10 20")
            val maskRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(smallLabel("蒙版："), LinearLayout.LayoutParams(dp(56), dp(46)))
                addView(maskInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            scrollBody.addView(maskRow)
            val maskView = TextView(this).apply {
                text = "AOB 支持空格分隔字节，?? 表示通配字节"
                setTextColor(Color.parseColor("#938F99"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(56), 0, 0, dp(3))
            }
            scrollBody.addView(maskView)

            val offsetInput = field("", "例如：0x10")
            val offsetLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(smallLabel("偏移量："), LinearLayout.LayoutParams(dp(62), dp(46)))
                addView(offsetInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            scrollBody.addView(offsetLayout)
            val hexInput = android.widget.CheckBox(this).apply {
                text = "HEX"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = true
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            scrollBody.addView(hexInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42),
            ))

            val types = arrayOf("dword", "float", "double", "word", "byte", "qword")
            val typeLabels = arrayOf("D: Dword", "F: Float", "E: Double", "W: Word", "B: Byte", "Q: Qword")
            val typeSpinner = spinner(typeLabels).apply {
                setSelection(types.indexOf(searchDataType).coerceAtLeast(0))
            }
            val typeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(smallLabel("类型："), LinearLayout.LayoutParams(dp(56), dp(46)))
                addView(typeSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            scrollBody.addView(typeRow)

            val optionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val encrypted = android.widget.CheckBox(this).apply {
                text = "此值被加密"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnClickListener {
                    if (isChecked) {
                        isChecked = false
                        Toast.makeText(this@OverlayService, "当前扫描器暂不支持加密值搜索", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val ordered = android.widget.CheckBox(this).apply {
                text = "按顺序"
                visibility = View.GONE
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnClickListener {
                    if (isChecked) {
                        isChecked = false
                        Toast.makeText(this@OverlayService, "当前扫描器暂不支持有序组搜索", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            optionRow.addView(encrypted, LinearLayout.LayoutParams(0, dp(48), 1f))
            optionRow.addView(ordered, LinearLayout.LayoutParams(0, dp(48), 1f))
            scrollBody.addView(optionRow)

            fun typeRangeHint(type: String): String = when (type) {
                "byte" -> "输入从 -128 到 127 的值"
                "word" -> "输入从 -32768 到 32767 的值"
                "dword" -> "输入从 -2147483648 到 2147483647 的值"
                "qword" -> "输入 64 位整数值"
                "float" -> "输入单精度浮点值"
                "double" -> "输入双精度浮点值"
                else -> "输入要搜索的值"
            }

            fun updateModeRows() {
                val mode = operators[operatorSpinner.selectedItemPosition.coerceIn(operators.indices)]
                maskRow.visibility = if (mode == "AOB") View.VISIBLE else View.GONE
                maskView.visibility = maskRow.visibility
                offsetLayout.visibility = if (mode == "地址") View.VISIBLE else View.GONE
                hexInput.visibility = if (mode == "地址") View.VISIBLE else View.GONE
                typeRow.visibility = if (mode == "AOB" || mode == "地址") View.GONE else View.VISIBLE
                typeHint.visibility = typeRow.visibility
                currentSearchMode = when (mode) {
                    "范围", ">", "<" -> "range"
                    "地址" -> "addr"
                    "AOB" -> "machine"
                    else -> "exact"
                }
            }

            operatorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    updateModeRows()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            typeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    typeHint.text = typeRangeHint(types[position.coerceIn(types.indices)])
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            typeHint.text = typeRangeHint(types[typeSpinner.selectedItemPosition.coerceIn(types.indices)])
            updateModeRows()

            fun runSearch(block: () -> List<Map<String, Any>>) {
                savedSearchInput = valueInput.text.toString().trim()
                savedFilterInput = maskInput.text.toString().trim()
                searchDataType = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                message.text = "正在搜索…"
                message.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    val autoPause = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
                        .getBoolean("agg_autopause", false)
                    val wasPaused = autoPause && isTargetProcessPaused(pid)
                    val pausedByUs = autoPause && !wasPaused && setTargetProcessPaused(pid, true)
                    val result = try {
                        block()
                    } catch (_: Exception) {
                        emptyList()
                    } finally {
                        if (pausedByUs) setTargetProcessPaused(pid, false)
                    }
                    handler.post {
                        searchResults = result
                        selectedIndices.clear()
                        searchResultPage = 0
                        Toast.makeText(this@OverlayService, "找到 ${result.size} 个结果", Toast.LENGTH_SHORT).show()
                        showAggSearchTab()
                    }
                }.start()
            }

            fun parseCurrentValue(type: String): Number? {
                val raw = valueInput.text.toString().trim()
                return if (type == "float" || type == "double") {
                    raw.toDoubleOrNull()
                } else {
                    when {
                        raw.startsWith("0x", true) -> raw.substring(2).toLongOrNull(16)
                        else -> raw.toLongOrNull()
                    }
                }
            }

            fun performSearch() {
                val mode = operators[operatorSpinner.selectedItemPosition.coerceIn(operators.indices)]
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                val raw = valueInput.text.toString().trim()
                if (raw.isBlank()) {
                    message.text = "请输入搜索内容"
                    message.setTextColor(Color.parseColor("#FFB4AB"))
                    return
                }
                when (mode) {
                    "AOB" -> runSearch {
                        MemoryEngine.searchAob(raw, maskInput.text.toString().trim().takeIf { it.isNotBlank() })
                    }
                    "地址" -> {
                        val base = parseAggAddress(raw)
                        if (base == null) {
                            message.text = "地址格式不正确"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        val offsetRaw = offsetInput.text.toString().trim()
                        val offset = when {
                            offsetRaw.isBlank() -> 0L
                            offsetRaw.startsWith("-0x", true) -> -(offsetRaw.substring(3).toLongOrNull(16) ?: 0L)
                            offsetRaw.startsWith("0x", true) -> offsetRaw.substring(2).toLongOrNull(16) ?: 0L
                            else -> offsetRaw.toLongOrNull() ?: 0L
                        }
                        runSearch { MemoryEngine.searchAob("0x${(base + offset).toString(16)}") }
                    }
                    "范围" -> {
                        val parts = raw.split('~', ';', ',', '～').map { it.trim() }.filter { it.isNotBlank() }
                        if (parts.size < 2) {
                            message.text = "范围格式示例：1~100"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        val low = if (type == "float" || type == "double") parts[0].toDoubleOrNull() else parseMemoryValue(parts[0], type) as? Number
                        val high = if (type == "float" || type == "double") parts[1].toDoubleOrNull() else parseMemoryValue(parts[1], type) as? Number
                        if (low == null || high == null || low.toDouble() > high.toDouble()) {
                            message.text = "范围数值格式不正确"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        runSearch { MemoryEngine.searchByRange(low, high, type) }
                    }
                    "≠" -> {
                        val target = parseCurrentValue(type)
                        if (target == null || searchResults.isEmpty()) {
                            message.text = "“≠”需要先有搜索结果，再在当前结果中筛选"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        val previous = searchResults.toList()
                        runSearch {
                            previous.mapNotNull { item ->
                                val address = (item["addressInt"] as? Number)?.toLong() ?: return@mapNotNull null
                                val value = MemoryEngine.readMemory(address, type) as? Number ?: return@mapNotNull null
                                val differs = if (type == "float" || type == "double") {
                                    value.toDouble() != target.toDouble()
                                } else value.toLong() != target.toLong()
                                if (!differs) null else item.toMutableMap().apply { this["value"] = value; this["type"] = type }
                            }
                        }
                    }
                    ">", "<" -> {
                        val value = parseCurrentValue(type)
                        if (value == null) {
                            message.text = "数值格式不正确"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        val min: Number
                        val max: Number
                        if (type == "float" || type == "double") {
                            min = if (mode == ">") value.toDouble() else -Double.MAX_VALUE
                            max = if (mode == ">") Double.MAX_VALUE else value.toDouble()
                        } else {
                            min = if (mode == ">") value.toLong() else Long.MIN_VALUE
                            max = if (mode == ">") Long.MAX_VALUE else value.toLong()
                        }
                        runSearch { MemoryEngine.searchByRange(min, max, type) }
                    }
                    else -> {
                        val value = parseCurrentValue(type)
                        if (value == null) {
                            message.text = "数值格式不正确"
                            message.setTextColor(Color.parseColor("#FFB4AB"))
                            return
                        }
                        runSearch {
                            val refine = searchResults.isNotEmpty() && searchDataType == type
                            if (refine) {
                                MemoryEngine.filterResults(
                                    searchResults.mapNotNull { (it["addressInt"] as? Number)?.toLong() },
                                    value,
                                    type,
                                )
                            } else {
                                MemoryEngine.searchExact(value, type)
                            }
                        }
                    }
                }
            }

            val fuzzyText = TextView(this).apply {
                text = if (searchResults.isEmpty()) "模糊搜索：首次操作会建立值快照" else "模糊搜索：在当前快照中继续筛选"
                setTextColor(Color.parseColor("#CAC4D0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(4), 0, dp(3))
            }
            scrollBody.addView(fuzzyText)

            val fuzzyRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fuzzyRow1.addView(button("无变化") {
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                runSearch { MemoryEngine.searchFuzzy("unchanged", type) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            fuzzyRow1.addView(button("有变化") {
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                runSearch { MemoryEngine.searchFuzzy("changed", type) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            scrollBody.addView(fuzzyRow1)

            val fuzzyRow2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            fuzzyRow2.addView(button("增加了") {
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                runSearch { MemoryEngine.searchFuzzy("increased", type) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            fuzzyRow2.addView(button("减少了") {
                val type = types[typeSpinner.selectedItemPosition.coerceIn(types.indices)]
                runSearch { MemoryEngine.searchFuzzy("decreased", type) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            scrollBody.addView(fuzzyRow2)

            scrollBody.addView(button(
                "内存范围：${MemoryEngine.getSelectedRegionCategories().joinToString(", ").ifBlank { "全部" }}"
            ) { showRegionPanel() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
            ).apply { topMargin = dp(6) })

            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            footer.addView(button("取消") { showAggSearchTab() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            footer.addView(button("更多") { showAggResultFilterPanel() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            footer.addView(button("搜索") { performSearch() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            scrollBody.addView(footer)

            valueInput.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            valueInput.setOnEditorActionListener { _, _, _ ->
                performSearch()
                true
            }
        }, 390, 610, onBack = { showAggSearchTab() }, titleIcon = R.drawable.ic_magnify_white_24dp, bgColor = "#C0000000")
    }

    private fun showSearchPanelLegacyCard() {
        saveLastPanel("search")
        val dm = resources.displayMetrics
        val isLandscape = dm.widthPixels > dm.heightPixels
        val panelWDp = if (isLandscape) 780 else 380
        val panelHDp = if (isLandscape) 500 else 610
        val attachedPid = MemoryEngine.getAttachedPid()
        val pid = attachedPid?.takeIf { MemoryEngine.isAttachedProcessAlive() }
        if (pid == null) {
            if (attachedPid != null) {
                MemoryEngine.detachProcess()
                clearAttachedProcessInfo()
            }
            showProcessPanel()
            return
        }

        makeDraggablePanel("内存搜索", { content ->
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val processName = prefs.getString("attached_name", "目标进程") ?: "目标进程"

            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(6), dp(5))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
                setOnClickListener { showProcessPanel() }
            }
            statusRow.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_agg_apps)
                setColorFilter(Color.parseColor("#D0BCFF"))
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })
            statusRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@OverlayService).apply {
                    text = processName
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#F3EDF7"))
                    textSize = 11.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@OverlayService).apply {
                    text = "PID $pid  ·  ${searchResults.size} 个结果"
                    setTextColor(Color.parseColor("#CAC4D0"))
                    textSize = 9.5f
                })
            })
            statusRow.addView(TextView(this).apply {
                text = "范围"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#C8F7DC"))
                textSize = 10f
                background = aggMenuDrawable(Color.parseColor("#294236"), 8, Color.parseColor("#4D705E"))
                setOnClickListener { showRegionPanel() }
            }, LinearLayout.LayoutParams(dp(50), dp(30)).apply { marginEnd = dp(4) })
            statusRow.addView(TextView(this).apply {
                text = "切换"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E8DEF8"))
                textSize = 10f
                background = aggMenuDrawable(Color.parseColor("#4A4458"), 8, Color.parseColor("#675F72"))
            }, LinearLayout.LayoutParams(dp(50), dp(30)))
            content.addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(5) })

            val types = arrayOf("dword", "float", "double", "byte", "word", "qword")
            val typeAdapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, types) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.parseColor("#F3EDF7"))
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setPadding(dp(8), 0, dp(8), 0)
                    }
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(Color.parseColor("#F3EDF7"))
                        setBackgroundColor(Color.parseColor("#4A4458"))
                        textSize = 12f
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    }
                }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val typeSpinner = Spinner(this).apply {
                adapter = typeAdapter
                background = aggMenuDrawable(Color.parseColor("#34313A"), 9, Color.parseColor("#49454F"))
                val index = types.indexOf(searchDataType).takeIf { it >= 0 } ?: 0
                setSelection(index)
            }

            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(5))
            }
            val modes = listOf(
                "精确" to "exact",
                "模糊" to "fuzzy",
                "范围" to "range",
                "地址" to "addr",
                "特征码" to "machine",
            )
            for ((label, mode) in modes) {
                modeRow.addView(TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTypeface(null, if (currentSearchMode == mode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (currentSearchMode == mode) Color.parseColor("#231A2E") else Color.parseColor("#CAC4D0"))
                    background = aggMenuDrawable(
                        if (currentSearchMode == mode) Color.parseColor("#D0BCFF") else Color.parseColor("#2B2930"),
                        8,
                        if (currentSearchMode == mode) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F")
                    )
                    setOnClickListener {
                        currentSearchMode = mode
                        showAggSearchTab()
                    }
                }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(3) })
            }
            modeRow.addView(typeSpinner, LinearLayout.LayoutParams(dp(if (isLandscape) 92 else 76), dp(34)))
            content.addView(modeRow)

            val status = TextView(this).apply {
                text = when {
                    searchResults.isNotEmpty() -> "当前结果 ${searchResults.size} 条，可继续筛选"
                    currentSearchMode == "fuzzy" -> "首次点击建立快照，再按变化继续筛选"
                    else -> "输入条件后开始搜索"
                }
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10.5f
                setPadding(dp(4), dp(2), dp(4), dp(4))
            }

            val actionBarContainer = LinearLayout(this).apply {
                tag = "search_action_bar"
                orientation = LinearLayout.VERTICAL
            }
            val resultList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "search_result_list"
            }
            val resultScroll = ScrollView(this).apply {
                isFillViewport = true
                addView(resultList)
            }

            val controlPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = aggMenuDrawable(Color.parseColor("#211F26"), 10, Color.parseColor("#3A3641"))
                tag = resultList
                addView(status)
                buildSearchInputArea(this, status, typeSpinner, resultList, 0)
            }

            if (isLandscape) {
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                body.addView(controlPanel, LinearLayout.LayoutParams(dp(310), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(5) })
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = aggMenuDrawable(Color.parseColor("#211F26"), 10, Color.parseColor("#3A3641"))
                    addView(actionBarContainer)
                    addView(resultScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                content.addView(body)
            } else {
                content.addView(controlPanel)
                content.addView(actionBarContainer)
                content.addView(resultScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }

            content.tag = resultList
            if (searchResults.isNotEmpty()) {
                updateSearchResults(resultList, searchResults, actionBarContainer, preserveSelection = true)
            } else {
                resultList.addView(TextView(this).apply {
                    text = "暂无搜索结果\n首次搜索后可在当前结果中继续缩小范围"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 11f
                    setPadding(dp(8), dp(36), dp(8), dp(36))
                })
            }

            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            fun footerButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10.5f
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F")
                    )
                    setOnClickListener { action() }
                }
            }
            footer.addView(footerButton("新搜索") {
                resetSearchSession()
                showAggSearchTab()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(3) })
            footer.addView(footerButton("刷新") {
                refreshSearchValues()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
            footer.addView(footerButton("保存列表") {
                showSavedListPanel()
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(1); marginEnd = dp(1) })
            footer.addView(footerButton("选择进程", true) { showProcessPanel() }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(3) })
            content.addView(footer)

            if (savedScrollY > 0) {
                resultScroll.post { resultScroll.scrollY = savedScrollY; savedScrollY = 0 }
            }
        }, panelWDp, panelHDp, titleIcon = R.drawable.ic_agg_memory)
    }

    private fun resetSearchSession() {
        searchResults = emptyList()
        selectedIndices.clear()
        savedSearchInput = ""
        savedFilterInput = ""
        savedRangeMin = ""
        savedRangeMax = ""
        savedScrollY = 0
        searchResultPage = 0
        searchResultFilter = ""
        focusedSearchResultIndex = -1
        MemoryEngine.resetSearchState()
    }

    private fun showRegionPanel() {
        saveLastPanel("search")
        makeDraggablePanel("内存范围", { content ->
            content.setPadding(0, 0, 0, 0)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(body)
            }
            content.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))

            val previousCategories = MemoryEngine.getSelectedRegionCategories()
            val selected = previousCategories.toMutableSet()
            val previousRange = MemoryEngine.getCustomRange()
            var summaryCache: List<Map<String, Any>> = emptyList()

            fun input(initial: String, hintText: String): EditText = EditText(this).apply {
                setText(initial)
                hint = hintText
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#938F99"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8), 0, dp(8), 0)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            fun label(textValue: String): TextView = TextView(this).apply {
                text = textValue
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
            }
            fun button(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
                text = textValue
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minWidth = dp(48)
                background = aggMenuDrawable(Color.argb(38, 255, 255, 255), 4, Color.parseColor("#B8B2BD"))
                setOnClickListener { action() }
            }

            val categoryContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            lateinit var rangeButton: TextView
            fun updateRangeTitle() {
                rangeButton.text = if (selected.size == 7) {
                    "全部内存"
                } else {
                    selected.joinToString(",") { id ->
                        when (id) {
                            "heap" -> "Ch"
                            "java" -> "Jh"
                            "anonymous" -> "A"
                            "stack" -> "S"
                            "app" -> "Cd"
                            "system" -> "O"
                            else -> "O"
                        }
                    }.ifBlank { "选择内存范围" }
                }
            }
            fun renderCategories() {
                categoryContainer.removeAllViews()
                if (summaryCache.isEmpty()) {
                    categoryContainer.addView(TextView(this).apply {
                        text = "正在读取内存区域…"
                        setTextColor(Color.parseColor("#CAC4D0"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                    return
                }
                summaryCache.forEach { item ->
                    val id = item["id"] as String
                    val labelText = item["label"] as String
                    val count = (item["count"] as Number).toInt()
                    val check = android.widget.CheckBox(this).apply {
                        text = "$labelText  ($count)"
                        isChecked = id in selected
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(id) else selected.remove(id)
                            updateRangeTitle()
                        }
                    }
                    categoryContainer.addView(check, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(48),
                    ))
                }
            }

            rangeButton = button("全部内存") {
                categoryContainer.visibility = if (categoryContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
            updateRangeTitle()
            body.addView(rangeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))
            body.addView(categoryContainer)

            val fromInput = input(previousRange.first?.let { "0x${it.toString(16).uppercase()}" }.orEmpty(), "起始地址")
            val toInput = input(previousRange.second?.let { "0x${it.toString(16).uppercase()}" }.orEmpty(), "结束地址")
            val fromRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("从："), LinearLayout.LayoutParams(dp(42), dp(48)))
                addView(fromInput, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("区域") {
                    val first = MemoryEngine.getMemoryRegions().minByOrNull { (it["startAddress"] as Number).toLong() }
                    val address = (first?.get("startAddress") as? Number)?.toLong()
                    if (address != null) fromInput.setText("0x${address.toString(16).uppercase()}")
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) })
            }
            body.addView(fromRow)
            val toRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("至："), LinearLayout.LayoutParams(dp(42), dp(48)))
                addView(toInput, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(button("区域") {
                    val last = MemoryEngine.getMemoryRegions().maxByOrNull { (it["endAddress"] as Number).toLong() }
                    val address = (last?.get("endAddress") as? Number)?.toLong()
                    if (address != null) toInput.setText("0x${address.toString(16).uppercase()}")
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(5) })
            }
            body.addView(toRow)

            val nearbyAddress = input("", "附近地址")
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("地址："), LinearLayout.LayoutParams(dp(56), dp(48)))
                addView(nearbyAddress, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
            val beforeCheck = android.widget.CheckBox(this).apply {
                text = "之前"
                isChecked = true
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val afterCheck = android.widget.CheckBox(this).apply {
                text = "之后"
                isChecked = true
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(beforeCheck, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(afterCheck, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
            val distanceInput = input("", "距离，例如 0x1000")
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("距离："), LinearLayout.LayoutParams(dp(56), dp(48)))
                addView(distanceInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            })

            Thread {
                val summary = MemoryEngine.getRegionCategorySummary()
                handler.post {
                    summaryCache = summary
                    renderCategories()
                    updateRangeTitle()
                }
            }.start()

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(button("取消") { showAggSearchDialog() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            actions.addView(button("应用") {
                if (selected.isEmpty()) {
                    Toast.makeText(this@OverlayService, "至少选择一个内存范围", Toast.LENGTH_SHORT).show()
                    return@button
                }
                var rangeFrom = parseAggAddress(fromInput.text.toString())
                var rangeTo = parseAggAddress(toInput.text.toString())
                val nearby = parseAggAddress(nearbyAddress.text.toString())
                val distance = parseAggAddress(distanceInput.text.toString())
                if (nearby != null && distance != null && distance > 0L) {
                    rangeFrom = if (beforeCheck.isChecked) (nearby - distance).coerceAtLeast(0L) else nearby
                    rangeTo = if (afterCheck.isChecked) nearby + distance else nearby + 1L
                }
                if (rangeFrom != null && rangeTo != null && rangeTo <= rangeFrom) {
                    Toast.makeText(this@OverlayService, "结束地址必须大于起始地址", Toast.LENGTH_SHORT).show()
                    return@button
                }
                Thread {
                    val categoriesOk = MemoryEngine.setRegionCategories(selected)
                    val rangeOk = categoriesOk && MemoryEngine.setCustomRange(rangeFrom, rangeTo)
                    if (!rangeOk) {
                        MemoryEngine.setRegionCategories(previousCategories)
                        MemoryEngine.setCustomRange(previousRange.first, previousRange.second)
                    } else {
                        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
                            .putStringSet("memory_region_categories", selected.toSet())
                            .putString("memory_range_from", rangeFrom?.toString(16).orEmpty())
                            .putString("memory_range_to", rangeTo?.toString(16).orEmpty())
                            .apply()
                        resetSearchSession()
                    }
                    handler.post {
                        Toast.makeText(
                            this@OverlayService,
                            if (rangeOk) "内存范围已应用" else "范围内没有可读写内存",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (rangeOk) showAggSearchDialog()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            body.addView(actions)
        }, 390, 600, onBack = { showAggSearchDialog() }, titleIcon = R.drawable.ic_agg_memory)
    }

    private fun refreshSearchValues() {
        if (searchResults.isEmpty()) {
            Toast.makeText(this, "当前没有可刷新的结果", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val refreshed = searchResults.map { item ->
                val address = (item["addressInt"] as? Number)?.toLong()
                val type = (item["type"] as? String)?.takeIf { MemoryEngine.isSupportedType(it) } ?: searchDataType
                if (address == null) return@map item
                val value = MemoryEngine.readMemory(address, type) ?: return@map item
                item.toMutableMap().apply {
                    this["value"] = value
                    this["type"] = type
                    this["isFrozen"] = MemoryFreezer.isFrozen(address)
                }
            }
            searchResults = refreshed
            handler.post {
                refreshCurrentPanel()
                Toast.makeText(this@OverlayService, "已刷新 ${refreshed.size} 条结果", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleSelectedFreeze(
        results: List<Map<String, Any>>,
        resultList: LinearLayout,
        actionBarContainer: LinearLayout?,
    ) {
        val selectedResults = selectedIndices.filter { it in results.indices }.map { results[it] }
        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "请先勾选结果", Toast.LENGTH_SHORT).show()
            return
        }

        val shouldUnfreeze = selectedResults.all { item ->
            val address = (item["addressInt"] as? Number)?.toLong()
            address != null && MemoryFreezer.isFrozen(address)
        }
        Thread {
            var successCount = 0
            for (item in selectedResults) {
                val address = (item["addressInt"] as? Number)?.toLong() ?: continue
                val type = (item["type"] as? String)?.takeIf { MemoryEngine.isSupportedType(it) } ?: searchDataType
                val value = item["value"] ?: continue
                val success = if (shouldUnfreeze) {
                    MemoryFreezer.unfreeze(address)
                } else {
                    MemoryFreezer.freeze(address, value, type)
                }
                if (success) successCount++
            }
            searchResults = searchResults.map { item ->
                val address = (item["addressInt"] as? Number)?.toLong()
                if (address == null) item
                else item.toMutableMap().apply { this["isFrozen"] = MemoryFreezer.isFrozen(address) }
            }
            handler.post {
                Toast.makeText(
                    this@OverlayService,
                    if (shouldUnfreeze) "已解冻 $successCount 条" else "已冻结 $successCount 条",
                    Toast.LENGTH_SHORT,
                ).show()
                updateSearchResults(resultList, searchResults, actionBarContainer, preserveSelection = true)
            }
        }.start()
    }

    private fun showSearchPanelLegacy() {
        saveLastPanel("search")
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val density = dm.density
        val isLandscape = screenW > screenH

        // 横屏：宽度88%屏幕，高度93%屏幕；竖屏：保持原样
        val panelWDp = if (isLandscape) ((screenW * 0.88f) / density).toInt().coerceIn(650, 900) else 320
        val panelHDp = if (isLandscape) ((screenH * 0.93f) / density).toInt().coerceIn(380, 550) else 520

        makeDraggablePanel("内存搜索", { content ->
            val pid = MemoryEngine.getAttachedPid()

            if (isLandscape) {
                // ========== 横屏模式：左右两栏布局 ==========
                val mainRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }

                // ---- 左栏：搜索控件 ----
                val leftPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                }

                // 状态行
                val status = TextView(this).apply {
                    text = if (pid != null) "PID:$pid" else "⚠未附加"
                    setTextColor(if (pid != null) Color.parseColor("#66BB6A") else Color.parseColor("#FF8F00"))
                    textSize = 11f
                }
                leftPanel.addView(status)

                // 类型选择
                val types = arrayOf("dword", "float", "double", "byte", "word", "qword")
                val typeSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, types)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                leftPanel.addView(typeSpinner)

                // 模式切换按钮
                val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
                fun modeBtn(label: String, mode: String): Button {
                    return Button(this).apply {
                        text = label; textSize = 10f; setTextColor(Color.parseColor("#FFF3E0"))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setColor(if (currentSearchMode == mode) Color.parseColor("#8D6E63") else Color.parseColor("#333333"))
                        }
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(2) }
                        setOnClickListener { currentSearchMode = mode; showAggSearchTab() }
                    }
                }
                modeRow.addView(modeBtn("精确", "exact"))
                modeRow.addView(modeBtn("模糊", "fuzzy"))
                modeRow.addView(modeBtn("范围", "range"))
                modeRow.addView(modeBtn("地址", "addr"))
                modeRow.addView(modeBtn("机器码", "machine"))
                leftPanel.addView(modeRow)

                // 分割线
                leftPanel.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#E8DDD5"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(4) }
                })

                // 输入区域（根据模式）
                buildSearchInputArea(leftPanel, status, typeSpinner, null, dp(4))

                // 弹性空间
                leftPanel.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                })

                // 底部按钮
                val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                bar.addView(iconBtn(R.drawable.shuaxing) {
                    searchResults = emptyList(); selectedIndices.clear(); status.text = "已重置"
                    val resultList = leftPanel.tag as? LinearLayout
                    resultList?.removeAllViews()
                    resultList?.addView(TextView(this@OverlayService).apply {
                        text = "暂无结果"; setTextColor(Color.parseColor("#8D6E63")); textSize = 12f
                        setPadding(dp(8), dp(20), dp(8), dp(8)); gravity = Gravity.CENTER
                    })
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                leftPanel.addView(bar)

                mainRow.addView(leftPanel)

                // 分割线（竖向）
                mainRow.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#E8DDD5"))
                    layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                })

                // ---- 右栏：搜索结果 ----
                val rightPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    setPadding(dp(4), dp(4), dp(8), dp(4))
                }

                val resultTitle = TextView(this).apply {
                    text = "搜索结果"; setTextColor(Color.parseColor("#A1887F")); textSize = 11f
                    setPadding(0, 0, 0, dp(4))
                }
                rightPanel.addView(resultTitle)

                // 固定操作栏容器
                val actionBarContainer = LinearLayout(this).apply {
                    tag = "search_action_bar"
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                rightPanel.addView(actionBarContainer)

                val rsv = ScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val rl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0) }
                rsv.addView(rl)

                if (searchResults.isNotEmpty()) {
                    status.text = "${searchResults.size}个结果"
                    updateSearchResults(rl, searchResults, actionBarContainer)
                } else {
                    rl.addView(TextView(this).apply {
                        text = "暂无结果"; setTextColor(Color.parseColor("#8D6E63")); textSize = 12f
                        setPadding(dp(8), dp(20), dp(8), dp(8)); gravity = Gravity.CENTER
                    })
                }

                rightPanel.addView(rsv)
                // 恢复滚动位置
                if (savedScrollY > 0) {
                    rsv.post { rsv.scrollY = savedScrollY; savedScrollY = 0 }
                }
                mainRow.addView(rightPanel)

                content.addView(mainRow)

                // 保存 rl 引用供回调使用（横屏模式下需要设置到 leftPanel.tag）
                content.tag = rl
                leftPanel.tag = rl
            } else {
                // ========== 竖屏模式：原有上下布局 ==========
                val topRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(8), dp(4), dp(8), dp(2))
                }
                val status = TextView(this).apply {
                    text = if (pid != null) "PID:$pid" else "⚠未附加"
                    setTextColor(if (pid != null) Color.parseColor("#66BB6A") else Color.parseColor("#FF8F00"))
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                topRow.addView(status)

                val types = arrayOf("dword", "float", "double", "byte", "word", "qword")
                val typeSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, types)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                topRow.addView(typeSpinner)
                content.addView(topRow)

                val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
                fun modeBtn(label: String, mode: String): Button {
                    return Button(this).apply {
                        text = label; textSize = 10f; setTextColor(Color.parseColor("#FFF3E0"))
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setColor(if (currentSearchMode == mode) Color.parseColor("#8D6E63") else Color.parseColor("#333333"))
                        }
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(2) }
                        setOnClickListener { currentSearchMode = mode; showAggSearchTab() }
                    }
                }
                modeRow.addView(modeBtn("精确", "exact"))
                modeRow.addView(modeBtn("模糊", "fuzzy"))
                modeRow.addView(modeBtn("范围", "range"))
                modeRow.addView(modeBtn("地址", "addr"))
                modeRow.addView(modeBtn("机器码", "machine"))
                content.addView(modeRow)

                // 输入区域（在结果列表上面）
                buildSearchInputArea(content, status, typeSpinner, null, dp(12))

                // 固定操作栏容器
                val actionBarContainer = LinearLayout(this).apply {
                    tag = "search_action_bar"
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                content.addView(actionBarContainer)

                // 结果列表
                val rsv = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
                val rl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(2)) }
                rsv.addView(rl)
                // 保存 rl 引用供回调使用
                content.tag = rl
                if (searchResults.isNotEmpty()) {
                    status.text = "${searchResults.size}个结果"
                    updateSearchResults(rl, searchResults, actionBarContainer)
                }
                content.addView(rsv)
                // 恢复滚动位置
                if (savedScrollY > 0) {
                    rsv.post { rsv.scrollY = savedScrollY; savedScrollY = 0 }
                }

                val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(2), dp(8), dp(4)) }
                bar.addView(iconBtn(R.drawable.shuaxing) {
                    searchResults = emptyList(); selectedIndices.clear(); status.text = "已重置"
                    rl.removeAllViews()
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(bar)
            }
        }, panelWDp, panelHDp, titleIcon = R.drawable.neichun, bgColor = "#723d09")
    }

    // 构建搜索输入区域（横屏/竖屏复用）
    private fun buildSearchInputArea(parent: LinearLayout, status: TextView, typeSpinner: Spinner, rl: LinearLayout?, inputPadding: Int) {
        val resultList = rl ?: (parent.tag as? LinearLayout)

        fun inputField(hintText: String, value: String, numeric: Boolean = true): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(value)
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 12f
                setSingleLine(true)
                inputType = if (numeric) {
                    android.text.InputType.TYPE_CLASS_NUMBER or
                            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                            android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                } else {
                    android.text.InputType.TYPE_CLASS_TEXT
                }
                setPadding(dp(11), 0, dp(10), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
        }

        fun actionButton(label: String, iconRes: Int = R.drawable.ic_agg_search, primary: Boolean = true, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = label
                background = aggMenuDrawable(
                    if (primary) Color.parseColor("#D0BCFF") else Color.parseColor("#4A4458"),
                    9,
                    if (primary) Color.parseColor("#E8DEF8") else Color.parseColor("#675F72")
                )
                setPadding(dp(5), dp(2), dp(5), dp(2))
                setOnClickListener { pressAndRun(this) { action() } }
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(if (primary) Color.parseColor("#231A2E") else Color.parseColor("#E8DEF8"))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(if (primary) Color.parseColor("#231A2E") else Color.parseColor("#F3EDF7"))
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
        }

        fun runSearch(message: String, clearVisible: Boolean = true, block: () -> List<Map<String, Any>>) {
            if (MemoryEngine.getAttachedPid() == null) {
                status.text = "请先选择进程"
                status.setTextColor(Color.parseColor("#FFB4AB"))
                return
            }
            if (clearVisible) resultList?.removeAllViews()
            selectedIndices.clear()
            searchResultPage = 0
            searchResultFilter = ""
            focusedSearchResultIndex = -1
            status.text = message
            status.setTextColor(Color.parseColor("#D0BCFF"))
            Thread {
                val started = System.currentTimeMillis()
                val result = try { block() } catch (_: Exception) { emptyList() }
                searchResults = result
                handler.post {
                    status.text = "${result.size} 个结果"
                    status.setTextColor(if (result.isEmpty()) Color.parseColor("#FFB4AB") else Color.parseColor("#C8F7DC"))
                }
                notifySearchComplete(result.size, started)
            }.start()
        }

        when (currentSearchMode) {
            "exact" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(inputPadding, dp(2), inputPadding, dp(3))
                }
                val input = inputField("输入数值", savedSearchInput)
                row.addView(input)
                val sameTypeRefine = searchResults.isNotEmpty() && searchDataType == typeSpinner.selectedItem.toString()
                row.addView(actionButton(if (sameTypeRefine) "再次搜索" else "首次搜索") {
                    val text = input.text.toString().trim()
                    val type = typeSpinner.selectedItem.toString()
                    val value = parseMemoryValue(text, type)
                    if (value == null) {
                        status.text = "数值格式不正确"
                        status.setTextColor(Color.parseColor("#FFB4AB"))
                        return@actionButton
                    }
                    savedSearchInput = text
                    val refine = searchResults.isNotEmpty() && searchDataType == type
                    val previous = searchResults.mapNotNull { (it["addressInt"] as? Number)?.toLong() }
                    if (!refine) {
                        MemoryEngine.resetSearchState()
                        searchResults = emptyList()
                    }
                    searchDataType = type
                    runSearch(if (refine) "正在当前结果中继续筛选…" else "正在扫描内存…") {
                        if (refine) MemoryEngine.filterResults(previous, value, type)
                        else MemoryEngine.searchExact(value, type)
                    }
                }, LinearLayout.LayoutParams(dp(66), dp(40)).apply { marginStart = dp(5) })
                parent.addView(row)
                parent.addView(TextView(this).apply {
                    text = if (sameTypeRefine) "再次搜索只处理当前地址，点击底部“新搜索”可重新全盘扫描" else "首次搜索会扫描当前进程的可读写内存区域"
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 9f
                    setPadding(dp(3), 0, dp(3), dp(3))
                })
            }

            "fuzzy" -> {
                val grid = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(inputPadding, dp(2), inputPadding, dp(3))
                }
                val options = listOf(
                    Triple("变大", "increased", "#FFB4AB"),
                    Triple("变小", "decreased", "#C8F7DC"),
                    Triple("未改变", "unchanged", "#A9C7FF"),
                    Triple("已改变", "changed", "#FFD8A8"),
                )
                for ((label, comparison, tint) in options) {
                    grid.addView(TextView(this).apply {
                        text = label
                        gravity = Gravity.CENTER
                        textSize = 10f
                        setTextColor(Color.parseColor(tint))
                        background = aggMenuDrawable(Color.parseColor("#302D35"), 8, Color.parseColor("#49454F"))
                        setOnClickListener {
                            val selectedType = typeSpinner.selectedItem.toString()
                            searchDataType = selectedType
                            runSearch("正在执行模糊搜索…") { MemoryEngine.searchFuzzy(comparison, selectedType) }
                        }
                    }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
                }
                parent.addView(grid)
                parent.addView(TextView(this).apply {
                    text = "第一次操作建立值快照，之后按变化条件持续缩小结果"
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 9f
                    setPadding(dp(3), 0, dp(3), dp(3))
                })
            }

            "range" -> {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(inputPadding, dp(2), inputPadding, dp(3))
                }
                val minInput = inputField("最小值", savedRangeMin)
                val maxInput = inputField("最大值", savedRangeMax)
                row.addView(minInput)
                row.addView(TextView(this).apply {
                    text = "—"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#CAC4D0"))
                }, LinearLayout.LayoutParams(dp(22), dp(40)))
                row.addView(maxInput)
                row.addView(actionButton("范围搜索") {
                    val type = typeSpinner.selectedItem.toString()
                    val low = parseMemoryValue(minInput.text.toString(), type) as? Number
                    val high = parseMemoryValue(maxInput.text.toString(), type) as? Number
                    if (low == null || high == null || low.toDouble() > high.toDouble()) {
                        status.text = "范围格式不正确"
                        status.setTextColor(Color.parseColor("#FFB4AB"))
                        return@actionButton
                    }
                    savedRangeMin = minInput.text.toString()
                    savedRangeMax = maxInput.text.toString()
                    searchDataType = type
                    MemoryEngine.resetSearchState()
                    searchResults = emptyList()
                    runSearch("正在范围扫描…") { MemoryEngine.searchByRange(low, high, type) }
                }, LinearLayout.LayoutParams(dp(66), dp(40)).apply { marginStart = dp(5) })
                parent.addView(row)
            }

            "addr", "machine" -> {
                val isAddress = currentSearchMode == "addr"
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(inputPadding, dp(2), inputPadding, dp(3))
                }
                val input = inputField(
                    if (isAddress) "0x7ABC1234" else "48 89 5C 24 ?? 57 48",
                    savedSearchInput,
                    numeric = false,
                )
                row.addView(input)
                row.addView(actionButton(if (isAddress) "读取地址" else "特征码") {
                    val value = input.text.toString().trim()
                    if (value.isEmpty()) {
                        status.text = if (isAddress) "请输入地址" else "请输入特征码"
                        status.setTextColor(Color.parseColor("#FFB4AB"))
                        return@actionButton
                    }
                    savedSearchInput = value
                    searchDataType = if (isAddress) "dword" else "aob"
                    MemoryEngine.resetSearchState()
                    searchResults = emptyList()
                    runSearch(if (isAddress) "正在读取地址…" else "正在扫描特征码…") {
                        MemoryEngine.searchAob(value)
                    }
                }, LinearLayout.LayoutParams(dp(66), dp(40)).apply { marginStart = dp(5) })
                parent.addView(row)
            }
        }
    }

    private fun buildSearchInputAreaLegacy(parent: LinearLayout, status: TextView, typeSpinner: Spinner, rl: LinearLayout?, inputPadding: Int) {
        // 获取结果列表容器：优先使用传入的 rl，否则从 parent.tag 获取
        fun getResultList(): LinearLayout? = rl ?: (parent.tag as? LinearLayout)

        when (currentSearchMode) {
            "exact" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                val inp = EditText(this).apply {
                    hint = "输入数值"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                row.addView(inp)
                row.addView(iconBtn(R.drawable.bt_shuousuo) {
                    val v = inp.text.toString().trim()
                    if (v.isEmpty()) { status.text = "请输入数值"; return@iconBtn }
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "请先选择进程"; return@iconBtn }
                    val dtype = typeSpinner.selectedItem.toString()
                    val nv = parseMemoryValue(v, dtype)
                    if (nv == null) { status.text = "数值格式不正确"; return@iconBtn }
                    savedSearchInput = v
                    searchDataType = dtype
                    status.text = "开始扫描..."
                    val resultList = getResultList()
                    resultList?.removeAllViews(); searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchExact(nv, dtype)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                })
                parent.addView(row)

                val fRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                val fInp = EditText(this).apply {
                    hint = "新值(过滤)"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedFilterInput)
                }
                fRow.addView(fInp)
                fRow.addView(iconBtn(R.drawable.bt_guolv) {
                    val v = fInp.text.toString().trim()
                    if (v.isEmpty() || searchResults.isEmpty()) { status.text = "请先完成首次搜索"; return@iconBtn }
                    val nv = parseMemoryValue(v, searchDataType)
                    if (nv == null) { status.text = "数值格式不正确"; return@iconBtn }
                    savedFilterInput = v
                    status.text = "继续筛选..."
                    val resultList = getResultList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.filterResults(searchResults.mapNotNull { (it["addressInt"] as? Number)?.toLong() }, nv, searchDataType)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                })
                parent.addView(fRow)
            }
            "fuzzy" -> {
                val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                fun fuzzyBtn(label: String, cmp: String, color: String): Button {
                    return Button(this).apply {
                        text = label; textSize = 11f; setTextColor(Color.parseColor("#FFF3E0"))
                        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor(color)) }
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3); bottomMargin = dp(2) }
                        setOnClickListener {
                            if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@setOnClickListener }
                            val dtype = typeSpinner.selectedItem.toString(); searchDataType = dtype
                            status.text = "模糊搜索..."
                            Thread {
                                try {
                                    val t = System.currentTimeMillis()
                                    val res = MemoryEngine.searchFuzzy(cmp, dtype)
                                    searchResults = res
                                    notifySearchComplete(res.size, t)
                                } catch (_: Exception) {}
                            }.start()
                        }
                    }
                }
                val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r1.addView(fuzzyBtn("变大", "increased", "#E53935"))
                r1.addView(fuzzyBtn("变小", "decreased", "#43A047"))
                r1.addView(fuzzyBtn("没变", "unchanged", "#1E88E5"))
                r1.addView(fuzzyBtn("改变", "changed", "#FB8C00"))
                grid.addView(r1)
                parent.addView(grid)
            }
            "range" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                val minInp = EditText(this).apply {
                    hint = "Min"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedRangeMin)
                }
                val maxInp = EditText(this).apply {
                    hint = "Max"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedRangeMax)
                }
                row.addView(minInp)
                row.addView(TextView(this).apply { text = "~"; setTextColor(Color.parseColor("#FFF3E0")); setPadding(dp(4), dp(6), dp(4), dp(6)) })
                row.addView(maxInp)
                row.addView(smallBtn("扫描") {
                    val dtype = typeSpinner.selectedItem.toString()
                    val lo = parseMemoryValue(minInp.text.toString(), dtype) as? Number
                    val hi = parseMemoryValue(maxInp.text.toString(), dtype) as? Number
                    if (lo == null || hi == null || lo.toDouble() > hi.toDouble()) {
                        status.text = "范围格式不正确"
                        return@smallBtn
                    }
                    savedRangeMin = minInp.text.toString()
                    savedRangeMax = maxInp.text.toString()
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "请先选择进程"; return@smallBtn }
                    searchDataType = dtype
                    status.text = "开始范围扫描..."
                    val resultList = getResultList()
                    resultList?.removeAllViews(); searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchByRange(lo, hi, dtype)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                })
                parent.addView(row)
            }
            "addr" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                val inp = EditText(this).apply {
                    hint = "0x728B3A4D"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                row.addView(inp)
                row.addView(smallBtn("读取") {
                    val addr = inp.text.toString().trim()
                    if (addr.isEmpty()) { status.text = "❌输入地址"; return@smallBtn }
                    savedSearchInput = addr
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@smallBtn }
                    searchDataType = "addr"
                    status.text = "读取中..."
                    val resultList = getResultList()
                    resultList?.removeAllViews(); searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchAob(addr)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                })
                parent.addView(row)
            }
            "machine" -> {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(inputPadding, dp(2), inputPadding, dp(2)) }
                val inp = EditText(this).apply {
                    hint = "48 89 5C 24 ?? CC"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#8B4513")) }
                    setPadding(dp(8), dp(6), dp(8), dp(6)); textSize = 13f
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(savedSearchInput)
                }
                row.addView(inp)
                row.addView(iconBtn(R.drawable.bt_shuousuo) {
                    val pattern = inp.text.toString().trim()
                    if (pattern.isEmpty()) { status.text = "❌输入机器码"; return@iconBtn }
                    savedSearchInput = pattern
                    if (MemoryEngine.getAttachedPid() == null) { status.text = "❌请先附加"; return@iconBtn }
                    searchDataType = "aob"
                    status.text = "扫描中..."
                    val resultList = getResultList()
                    resultList?.removeAllViews(); searchResults = emptyList()
                    Thread {
                        val t = System.currentTimeMillis()
                        val res = MemoryEngine.searchAob(pattern)
                        searchResults = res
                        notifySearchComplete(res.size, t)
                    }.start()
                })
                parent.addView(row)
            }
        }
    }
    
    // 搜索完成通知：尝试更新当前面板UI，若面板已关闭则等重新打开时自动显示结果
    private fun notifySearchComplete(count: Int, startTime: Long) {
        val elapsed = String.format("%.2f", (System.currentTimeMillis() - startTime) / 1000.0)
        try {
            handler.post {
                try {
                    // 尝试更新当前面板的UI
                    refreshCurrentPanel()
                    Toast.makeText(this, "${count}个结果 ${elapsed}s", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // 刷新当前搜索面板的结果显示
    private fun refreshCurrentPanel() {
        val content = panel as? android.view.ViewGroup ?: return
        // 找到 ScrollView 中的 rl（结果列表容器）
        fun findResultList(v: android.view.ViewGroup): LinearLayout? {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child is android.widget.ScrollView) {
                    val inner = child.getChildAt(0)
                    if (inner is LinearLayout) return inner
                }
                if (child is android.view.ViewGroup) {
                    val found = findResultList(child)
                    if (found != null) return found
                }
            }
            return null
        }
        // 找到操作栏容器
        fun findActionBarContainer(v: android.view.ViewGroup): LinearLayout? {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child is LinearLayout && child.tag == "search_action_bar") return child
                if (child is android.view.ViewGroup) {
                    val found = findActionBarContainer(child)
                    if (found != null) return found
                }
            }
            return null
        }
        val rl = findResultList(content) ?: return
        val abc = findActionBarContainer(content)
        if (searchResults.isNotEmpty()) {
            updateSearchResults(rl, searchResults, abc, preserveSelection = true)
        }
    }

    private fun updateSearchResults(
        rl: LinearLayout,
        results: List<Map<String, Any>>,
        actionBarContainer: LinearLayout? = null,
        preserveSelection: Boolean = false,
    ) {
        rl.removeAllViews()
        actionBarContainer?.removeAllViews()
        if (!preserveSelection) {
            selectedIndices.clear()
            searchResultPage = 0
            focusedSearchResultIndex = -1
        }
        selectedIndices.retainAll(results.indices.toSet())

        if (results.isEmpty()) {
            actionBarContainer?.visibility = View.GONE
            rl.addView(TextView(this).apply {
                text = "未找到结果"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#938F99"))
                textSize = 11f
                setPadding(dp(8), dp(34), dp(8), dp(34))
            })
            return
        }

        val keyword = searchResultFilter.trim().lowercase()
        val filteredResults = results.withIndex().filter { indexed ->
            if (keyword.isEmpty()) return@filter true
            val item = indexed.value
            val searchable = buildString {
                append(item["address"] ?: "")
                append(' ')
                append(item["value"] ?: "")
                append(' ')
                append(item["type"] ?: "")
                append(' ')
                append(item["machineCode"] ?: "")
                append(' ')
                append(item["pointerTargetText"] ?: "")
                append(' ')
                append(item["pointerExpression"] ?: "")
            }.lowercase()
            searchable.contains(keyword)
        }
        val pageCount = maxOf(1, (filteredResults.size + SEARCH_RESULT_PAGE_SIZE - 1) / SEARCH_RESULT_PAGE_SIZE)
        searchResultPage = searchResultPage.coerceIn(0, pageCount - 1)
        val pageStart = searchResultPage * SEARCH_RESULT_PAGE_SIZE
        val pageItems = filteredResults.drop(pageStart).take(SEARCH_RESULT_PAGE_SIZE)
        val regions = MemoryEngine.getMemoryRegions()

        fun selectedItems(): List<Map<String, Any>> = selectedIndices
            .sorted()
            .mapNotNull { results.getOrNull(it) }

        fun compactButton(
            label: String,
            accent: Boolean = false,
            danger: Boolean = false,
            action: () -> Unit,
        ): TextView {
            return TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 9.5f
                setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(
                    when {
                        danger -> Color.parseColor("#FFB4AB")
                        accent -> Color.parseColor("#231A2E")
                        else -> Color.parseColor("#E6E0E9")
                    }
                )
                background = aggMenuDrawable(
                    when {
                        danger -> Color.parseColor("#35232A")
                        accent -> Color.parseColor("#D0BCFF")
                        else -> Color.parseColor("#302D35")
                    },
                    7,
                    when {
                        danger -> Color.parseColor("#68404A")
                        accent -> Color.parseColor("#E8DEF8")
                        else -> Color.parseColor("#49454F")
                    },
                )
                setPadding(dp(11), 0, dp(11), 0)
                setOnClickListener { pressAndRun(this) { action() } }
            }
        }

        fun renderActionBar() {
            val container = actionBarContainer ?: return
            container.removeAllViews()
            container.visibility = View.VISIBLE

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5))
                background = aggMenuDrawable(Color.parseColor("#211F26"), 10, Color.parseColor("#49454F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(5) }
            }

            val summaryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            summaryRow.addView(TextView(this).apply {
                text = "搜索结果 ${results.size}"
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 11.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            summaryRow.addView(TextView(this).apply {
                val visibleText = if (keyword.isEmpty()) "已搜索：${results.size}" else "筛选：${filteredResults.size}/${results.size}"
                text = "$visibleText  ·  已选择 ${selectedIndices.size}"
                setTextColor(if (selectedIndices.isEmpty()) Color.parseColor("#CAC4D0") else Color.parseColor("#D0BCFF"))
                textSize = 9.5f
            })
            card.addView(summaryRow)

            val filterRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            val filterInput = EditText(this).apply {
                hint = "筛选地址、数值、类型或机器码"
                setText(searchResultFilter)
                setSelection(text.length)
                setSingleLine(true)
                textSize = 10.5f
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                setPadding(dp(10), 0, dp(8), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 7, Color.parseColor("#49454F"))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        searchResultFilter = text.toString()
                        searchResultPage = 0
                        updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
                        true
                    } else false
                }
            }
            filterRow.addView(filterInput, LinearLayout.LayoutParams(0, dp(34), 1f))
            filterRow.addView(compactButton("筛选", accent = true) {
                searchResultFilter = filterInput.text.toString()
                searchResultPage = 0
                updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
            }, LinearLayout.LayoutParams(dp(52), dp(34)).apply { marginStart = dp(4) })
            if (searchResultFilter.isNotEmpty()) {
                filterRow.addView(compactButton("清除") {
                    searchResultFilter = ""
                    searchResultPage = 0
                    updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
                }, LinearLayout.LayoutParams(dp(48), dp(34)).apply { marginStart = dp(4) })
            }
            card.addView(filterRow)

            val actionScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun addAction(view: TextView) {
                actions.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(4) })
            }

            addAction(compactButton("新搜索", danger = true) {
                resetSearchSession()
                showAggSearchTab()
            })
            val allPageSelected = pageItems.isNotEmpty() && pageItems.all { selectedIndices.contains(it.index) }
            addAction(compactButton(if (allPageSelected) "取消本页" else "全选本页") {
                if (allPageSelected) pageItems.forEach { selectedIndices.remove(it.index) }
                else pageItems.forEach { selectedIndices.add(it.index) }
                updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
            })

            val chosen = selectedItems()
            if (chosen.isEmpty()) {
                addAction(compactButton("刷新") { refreshSearchValues() })
                addAction(compactButton("保存列表") { showSavedListPanel() })
                addAction(compactButton("内存范围") { showRegionPanel() })
                addAction(compactButton("指针搜索") { showPointerSearchPanel(results) })
            } else {
                addAction(compactButton("保存到列表", accent = true) {
                    val count = addResultsToSavedList(chosen)
                    Toast.makeText(this, "已保存 $count 条", Toast.LENGTH_SHORT).show()
                })
                addAction(compactButton("复制") { showSearchResultCopyPanel(chosen) })
                addAction(compactButton("修改") {
                    if (chosen.size == 1) {
                        val item = chosen.first()
                        val address = item["address"]?.toString() ?: return@compactButton
                        showWriteDialog(
                            address,
                            item["value"],
                            item["machineCode"]?.toString() ?: "",
                            searchResultType(item),
                            returnAction = { showAggSearchTab() },
                        )
                    } else {
                        showBatchEditDialog(chosen)
                    }
                })
                addAction(compactButton("冻结/解冻") { toggleSelectedFreeze(results, rl, actionBarContainer) })
                addAction(compactButton("指针") { showPointerSearchPanel(chosen) })
                addAction(compactButton("AI") {
                    addResultsToAIChat(chosen)
                    Toast.makeText(this, "已添加 ${chosen.size} 条到 AI", Toast.LENGTH_SHORT).show()
                })
            }
            actionScroll.addView(actions)
            card.addView(actionScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)))
            container.addView(card)
        }

        renderActionBar()

        rl.addView(TextView(this).apply {
            val from = if (pageItems.isEmpty()) 0 else pageStart + 1
            val to = pageStart + pageItems.size
            text = "显示 $from–$to / ${filteredResults.size}  ·  点击选择，长按打开结果操作"
            setTextColor(Color.parseColor("#938F99"))
            textSize = 9f
            setPadding(dp(5), dp(2), dp(5), dp(4))
        })

        fun addGotoRow(targetPage: Int, address: Long) {
            rl.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(42)
                setPadding(dp(10), 0, dp(10), 0)
                background = aggMenuDrawable(Color.parseColor("#292630"), 7, Color.parseColor("#49454F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(42),
                ).apply { bottomMargin = dp(2) }
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(R.drawable.ic_agg_memory)
                    setColorFilter(Color.parseColor("#D0BCFF"))
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(7) })
                addView(TextView(this@OverlayService).apply {
                    text = "转到：0x${address.toString(16).uppercase()}"
                    setTextColor(Color.parseColor("#E8DEF8"))
                    textSize = 10.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@OverlayService).apply {
                    text = if (targetPage < searchResultPage) "上一段" else "下一段"
                    setTextColor(Color.parseColor("#CAC4D0"))
                    textSize = 9f
                })
                setOnClickListener {
                    searchResultPage = targetPage
                    selectedIndices.clear()
                    updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
                }
            })
        }

        if (searchResultPage > 0 && pageItems.isNotEmpty()) {
            val previousAddress = searchResultAddress(filteredResults[(pageStart - SEARCH_RESULT_PAGE_SIZE).coerceAtLeast(0)].value)
            addGotoRow(searchResultPage - 1, previousAddress)
        }

        var lastRegionKey: String? = null
        for (indexed in pageItems) {
            val index = indexed.index
            val result = indexed.value
            val address = searchResultAddress(result)
            if (address <= 0L) continue
            val addressText = result["address"]?.toString() ?: "0x${address.toString(16).uppercase()}"
            val resultType = searchResultType(result)
            val value = result["value"]
            val machineCode = result["machineCode"]?.toString() ?: ""
            val frozen = MemoryFreezer.isFrozen(address)
            val regionInfo = searchResultRegionInfo(address, regions)

            if (regionInfo.first != lastRegionKey) {
                lastRegionKey = regionInfo.first
                rl.addView(TextView(this).apply {
                    text = regionInfo.first
                    setTextColor(Color.parseColor("#CAC4D0"))
                    textSize = 8.8f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(7), dp(5), dp(7), dp(3))
                })
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(2), 0, dp(7), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                ).apply { bottomMargin = dp(1) }
            }
            val checkBox = android.widget.CheckBox(this).apply {
                isChecked = selectedIndices.contains(index)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D0BCFF"))
                setPadding(0, 0, 0, 0)
            }
            row.addView(checkBox, LinearLayout.LayoutParams(dp(38), dp(48)))

            val center = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val addressLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val addressView = TextView(this).apply {
                text = addressText
                textSize = 10.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addressLine.addView(addressView)
            addressLine.addView(TextView(this).apply {
                text = regionInfo.second
                textSize = 8.2f
                setTextColor(Color.parseColor("#938F99"))
                maxLines = 1
            })
            center.addView(addressLine)
            val interpretationView = TextView(this).apply {
                text = formatSearchResultInterpretations(result)
                setTextColor(Color.parseColor("#938F99"))
                textSize = 8.6f
                typeface = android.graphics.Typeface.MONOSPACE
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), dp(4), 0)
            }
            center.addView(interpretationView)
            row.addView(center, LinearLayout.LayoutParams(0, dp(48), 1f))

            val valueColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            val valueView = TextView(this).apply {
                text = if (result["pointerTarget"] != null && value is Number) {
                    "0x${value.toLong().toString(16).uppercase()}"
                } else {
                    value?.toString() ?: "?"
                }
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
            }
            valueColumn.addView(valueView)
            valueColumn.addView(TextView(this).apply {
                text = buildString {
                    append(resultType.uppercase())
                    val pointerOffset = (result["pointerOffset"] as? Number)?.toLong()
                    if (pointerOffset != null) append(" +0x${pointerOffset.toString(16).uppercase()}")
                    if (frozen) append("  🔒")
                }
                setTextColor(if (frozen) Color.parseColor("#D0BCFF") else Color.parseColor("#938F99"))
                textSize = 8.2f
                gravity = Gravity.END
            })
            row.addView(valueColumn, LinearLayout.LayoutParams(dp(92), dp(48)))

            fun refreshRowStyle() {
                val selected = selectedIndices.contains(index)
                val focused = focusedSearchResultIndex == index
                row.background = aggMenuDrawable(
                    when {
                        selected -> Color.parseColor("#494252")
                        focused -> Color.parseColor("#263933")
                        frozen -> Color.parseColor("#302A37")
                        else -> Color.parseColor("#211F26")
                    },
                    5,
                    when {
                        focused -> Color.parseColor("#8DE3B8")
                        selected -> Color.parseColor("#D0BCFF")
                        frozen -> Color.parseColor("#67507D")
                        else -> Color.parseColor("#343039")
                    },
                )
                addressView.setTextColor(
                    when {
                        focused -> Color.parseColor("#C8F7DC")
                        selected -> Color.parseColor("#D0BCFF")
                        else -> Color.parseColor("#F3EDF7")
                    }
                )
                valueView.setTextColor(if (selected || frozen) Color.parseColor("#D0BCFF") else Color.parseColor("#E6E0E9"))
            }
            refreshRowStyle()

            checkBox.setOnCheckedChangeListener { _, checked ->
                if (checked) selectedIndices.add(index) else selectedIndices.remove(index)
                refreshRowStyle()
                renderActionBar()
            }
            row.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
            row.setOnLongClickListener {
                focusedSearchResultIndex = index
                refreshRowStyle()
                showSearchResultActions(index, result)
                true
            }
            rl.addView(row)
        }

        if (searchResultPage < pageCount - 1 && pageItems.isNotEmpty()) {
            val nextIndex = ((searchResultPage + 1) * SEARCH_RESULT_PAGE_SIZE).coerceAtMost(filteredResults.lastIndex)
            addGotoRow(searchResultPage + 1, searchResultAddress(filteredResults[nextIndex].value))
        }

        if (pageItems.isEmpty()) {
            rl.addView(TextView(this).apply {
                text = "没有匹配当前筛选条件的结果"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#938F99"))
                textSize = 10.5f
                setPadding(dp(8), dp(32), dp(8), dp(32))
            })
        }
    }

    private fun searchResultAddress(result: Map<String, Any>): Long {
        return (result["addressInt"] as? Number)?.toLong()
            ?: result["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
            ?: 0L
    }

    private fun searchResultType(result: Map<String, Any>): String {
        return (result["type"] as? String)
            ?.takeIf { MemoryEngine.isSupportedType(it) }
            ?: searchDataType.takeIf { MemoryEngine.isSupportedType(it) }
            ?: "dword"
    }

    private fun aggTypeCode(type: String): String = when (type.lowercase()) {
        "dword" -> "D"
        "float" -> "F"
        "double" -> "E"
        "word" -> "W"
        "byte" -> "B"
        "qword" -> "Q"
        else -> type.take(1).uppercase()
    }

    private fun aggRegionCode(
        address: Long,
        regions: List<Map<String, Any>> = MemoryEngine.getMemoryRegions(),
    ): String {
        val region = regions.firstOrNull { item ->
            val start = (item["startAddress"] as? Number)?.toLong() ?: return@firstOrNull false
            val end = (item["endAddress"] as? Number)?.toLong() ?: return@firstOrNull false
            address in start until end
        } ?: return "O"
        return when (region["category"]?.toString()) {
            "heap" -> "Ch"
            "java" -> "Jh"
            "anonymous" -> "A"
            "stack" -> "S"
            "app" -> "Cd"
            "system" -> "O"
            else -> "O"
        }
    }

    private fun searchResultRegionInfo(
        address: Long,
        regions: List<Map<String, Any>> = MemoryEngine.getMemoryRegions(),
    ): Pair<String, String> {
        val region = regions.firstOrNull { item ->
            val start = (item["startAddress"] as? Number)?.toLong() ?: return@firstOrNull false
            val end = (item["endAddress"] as? Number)?.toLong() ?: return@firstOrNull false
            address in start until end
        } ?: return "未知区域" to ""
        val start = (region["startAddress"] as? Number)?.toLong() ?: 0L
        val rawName = region["name"]?.toString()?.trim().orEmpty()
        val category = region["category"]?.toString().orEmpty()
        val label = when {
            rawName.isNotEmpty() -> rawName.substringAfterLast('/').take(28)
            category == "heap" -> "原生堆"
            category == "java" -> "Java / Ashmem"
            category == "stack" -> "线程栈"
            category == "app" -> "应用代码与数据"
            category == "system" -> "系统代码与数据"
            category == "anonymous" -> "匿名内存"
            else -> "其他可写区域"
        }
        return label to "+0x${(address - start).coerceAtLeast(0).toString(16).uppercase()}"
    }

    private fun formatSearchResultInterpretations(result: Map<String, Any>): String {
        val pointerExpression = result["pointerExpression"]?.toString().orEmpty()
        val pointerTarget = result["pointerTargetText"]?.toString().orEmpty()
        if (pointerExpression.isNotEmpty()) {
            val depth = (result["pointerDepth"] as? Number)?.toInt()
            val offsets = result["pointerOffsetsText"]?.toString().orEmpty()
            return buildString {
                append("PTR")
                if (depth != null) append(" L$depth")
                append(' ').append(pointerExpression)
                if (offsets.isNotBlank()) append("  ").append(offsets)
                if (pointerTarget.isNotBlank()) append(" → ").append(pointerTarget)
            }
        }
        val machineCode = result["machineCode"]?.toString().orEmpty()
        val bytes = machineCode.split(Regex("\\s+"))
            .mapNotNull { token -> token.takeIf { it.length == 2 }?.toIntOrNull(16)?.toByte() }
            .take(8)
            .toByteArray()
        if (bytes.isEmpty()) return "值类型：${searchResultType(result).uppercase()}"

        val buffer = java.nio.ByteBuffer.wrap(bytes.copyOf(8)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteValue = bytes[0].toInt() and 0xFF
        val wordValue = buffer.getShort(0).toInt() and 0xFFFF
        val dwordValue = buffer.getInt(0)
        val floatValue = buffer.getFloat(0)
        val qwordValue = buffer.getLong(0)
        return "B:$byteValue  W:$wordValue  D:$dwordValue  F:${String.format(java.util.Locale.US, "%.4g", floatValue)}  Q:$qwordValue"
    }

    private fun showPointerSearchPanel(targetResults: List<Map<String, Any>>) {
        val targets = targetResults.map { searchResultAddress(it) }.filter { it > 0L }.distinct()
        if (targets.isEmpty()) {
            Toast.makeText(this, "没有有效的目标地址", Toast.LENGTH_SHORT).show()
            return
        }
        makeDraggablePanel("指针搜索 · ${targets.size} 个目标", { content ->
            content.addView(TextView(this).apply {
                text = "查找满足“指针值 + 偏移 = 目标地址”的 DWORD/QWORD 地址"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(dp(5), dp(2), dp(5), dp(6))
            })

            fun pointerInput(hintText: String, initial: String = ""): EditText {
                return EditText(this).apply {
                    hint = hintText
                    setText(initial)
                    setSingleLine(true)
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#F3EDF7"))
                    setHintTextColor(Color.parseColor("#938F99"))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    setPadding(dp(10), 0, dp(10), 0)
                    background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#49454F"))
                }
            }

            fun parseLongValue(rawText: String, blankValue: Long): Long? {
                val raw = rawText.trim()
                if (raw.isEmpty()) return blankValue
                return when {
                    raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLongOrNull(16)
                    raw.endsWith("h", ignoreCase = true) -> raw.dropLast(1).toLongOrNull(16)
                    raw.any { it in 'A'..'F' || it in 'a'..'f' } -> raw.toLongOrNull(16)
                    else -> raw.toLongOrNull()
                }
            }

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val maxOffset = pointerInput("最大偏移，例如 0x400", "0x400")
            val depthInput = pointerInput("链深度 1-4", "2")
            topRow.addView(maxOffset, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            topRow.addView(depthInput, LinearLayout.LayoutParams(0, dp(42), 0.55f).apply { marginStart = dp(3) })
            content.addView(topRow)
            val rangeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            val memoryFrom = pointerInput("扫描起始地址（留空=全部）")
            val memoryTo = pointerInput("扫描结束地址（留空=全部）")
            rangeRow.addView(memoryFrom, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(3) })
            rangeRow.addView(memoryTo, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(3) })
            content.addView(rangeRow)
            val limitInput = pointerInput("结果上限（默认 500）", "500")
            content.addView(limitInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) })

            val preview = targets.take(4).joinToString("  ") { "0x${it.toString(16).uppercase()}" }
            val state = TextView(this).apply {
                text = "目标：$preview${if (targets.size > 4) " …" else ""}"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 8.8f
                maxLines = 3
                setPadding(dp(5), dp(7), dp(5), dp(3))
            }
            content.addView(state)

            fun button(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F"),
                    )
                    setOnClickListener { action() }
                }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            actions.addView(button("取消") { showAggSearchTab() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            actions.addView(button("开始搜索", true) {
                val offset = parseLongValue(maxOffset.text.toString(), 0x400L)
                val from = parseLongValue(memoryFrom.text.toString(), 0L)
                val to = parseLongValue(memoryTo.text.toString(), -1L)
                val limit = limitInput.text.toString().trim().toIntOrNull() ?: 500
                val depth = depthInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 4) ?: 2
                if (offset == null || offset < 0L || from == null || to == null || (to > 0L && to <= from)) {
                    state.text = "参数无效，请检查偏移和地址范围"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return@button
                }
                state.text = "正在扫描 $depth 级指针链…"
                state.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    val pointers = MemoryEngine.searchPointerChains(targets, offset, depth, from, to, limit)
                    handler.post {
                        searchResults = pointers
                        selectedIndices.clear()
                        searchResultFilter = ""
                        searchResultPage = 0
                        focusedSearchResultIndex = -1
                        Toast.makeText(this@OverlayService, "找到 ${pointers.size} 条指针链", Toast.LENGTH_SHORT).show()
                        showAggSearchTab()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            content.addView(actions)
        }, 390, 430, onBack = { showAggSearchTab() }, titleIcon = R.drawable.ic_agg_memory)
    }

    private fun showSearchResultCopyPanel(results: List<Map<String, Any>>) {
        if (results.isEmpty()) return
        makeDraggablePanel("复制 · ${results.size} 条", { content ->
            content.addView(TextView(this).apply {
                text = "选择要复制的内容"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10.5f
                setPadding(dp(5), dp(3), dp(5), dp(7))
            })

            fun copyChoice(label: String, valueBuilder: (Map<String, Any>) -> String): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(Color.parseColor("#F3EDF7"))
                    textSize = 11f
                    setPadding(dp(13), 0, dp(13), 0)
                    background = aggMenuDrawable(Color.parseColor("#25222B"), 8, Color.parseColor("#49454F"))
                    setOnClickListener {
                        copyToClipboard(results.joinToString("\n") { valueBuilder(it) })
                        Toast.makeText(this@OverlayService, "已复制 $label", Toast.LENGTH_SHORT).show()
                        showAggSearchTab()
                    }
                }
            }

            content.addView(copyChoice("地址") { it["address"]?.toString() ?: "" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(copyChoice("数值") { it["value"]?.toString() ?: "" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(copyChoice("机器码") { it["machineCode"]?.toString() ?: "" }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { bottomMargin = dp(4) })
            content.addView(copyChoice("完整行") { item ->
                val address = item["address"]?.toString() ?: ""
                "$address  [${searchResultType(item).uppercase()}]  = ${item["value"]}  ${item["machineCode"] ?: ""}"
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        }, 320, 270, onBack = { showAggSearchTab() }, titleIcon = R.drawable.ic_agg_copy)
    }

    private fun showSearchResultActions(index: Int, result: Map<String, Any>) {
        val address = searchResultAddress(result)
        if (address <= 0L) return
        val addressText = result["address"]?.toString() ?: "0x${address.toString(16).uppercase()}"
        val type = searchResultType(result)
        val value = result["value"]
        val machineCode = result["machineCode"]?.toString() ?: ""
        val frozen = MemoryFreezer.isFrozen(address)

        makeDraggablePanel("结果操作", { content ->
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
                addView(TextView(this@OverlayService).apply {
                    text = addressText
                    setTextColor(Color.parseColor("#F3EDF7"))
                    textSize = 11.5f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@OverlayService).apply {
                    text = "${searchResultRegionInfo(address).first}  ·  ${type.uppercase()}  ·  $value"
                    setTextColor(Color.parseColor("#CAC4D0"))
                    textSize = 9.5f
                    setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@OverlayService).apply {
                    text = formatSearchResultInterpretations(result)
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 8.7f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(3), 0, 0)
                })
            })

            fun itemAction(label: String, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTextColor(Color.parseColor("#E6E0E9"))
                    background = aggMenuDrawable(Color.parseColor("#302D35"), 8, Color.parseColor("#49454F"))
                    setOnClickListener { action() }
                }
            }

            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row1.addView(itemAction("修改值") {
                showWriteDialog(addressText, value, machineCode, type, returnAction = { showAggSearchTab() })
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            row1.addView(itemAction("地址跳转") {
                memoryEditorAddress = address
                memoryEditorType = type
                showMemoryEditorPanel(address, type)
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(row1)

            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row2.addView(itemAction("保存到列表") {
                val count = addResultsToSavedList(listOf(result))
                Toast.makeText(this@OverlayService, "已保存 $count 条", Toast.LENGTH_SHORT).show()
                showAggSearchTab()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            row2.addView(itemAction(if (frozen) "解除冻结" else "冻结") {
                Thread {
                    val success = if (frozen) MemoryFreezer.unfreeze(address)
                    else value != null && MemoryFreezer.freeze(address, value, type)
                    handler.post {
                        Toast.makeText(this@OverlayService, if (success) "操作成功" else "操作失败", Toast.LENGTH_SHORT).show()
                        showAggSearchTab()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(row2)

            val row3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            row3.addView(itemAction("复制") { showSearchResultCopyPanel(listOf(result)) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            row3.addView(itemAction("添加到 AI") {
                addResultsToAIChat(listOf(result))
                showAIChatPanel()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(row3)

            content.addView(itemAction("查找指向此地址的指针") {
                showPointerSearchPanel(listOf(result))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { topMargin = dp(6) })
        }, 340, 390, onBack = {
            focusedSearchResultIndex = index
            showAggSearchTab()
        }, titleIcon = R.drawable.ic_agg_memory)
    }

    private fun updateSearchResultsLegacy(
        rl: LinearLayout,
        results: List<Map<String, Any>>,
        actionBarContainer: LinearLayout? = null,
        preserveSelection: Boolean = false,
    ) {
        rl.removeAllViews()
        actionBarContainer?.removeAllViews()
        if (!preserveSelection) selectedIndices.clear()

        if (results.isEmpty()) {
            if (actionBarContainer != null) {
                actionBarContainer.visibility = android.view.View.GONE
            }
            rl.addView(TextView(this).apply { text = "未找到结果"; setTextColor(Color.parseColor("#A1887F")); textSize = 11f; setPadding(dp(8), dp(4), dp(8), dp(4)) })
            return
        }

        // 批量操作按钮栏
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
        }

        val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val displayLimit = if (isLandscape) 120 else 80
        val displayCount = minOf(results.size, displayLimit)

        fun actionBtn(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                contentDescription = label
                background = aggMenuDrawable(Color.parseColor("#4A4458"), 10, Color.parseColor("#665F73"))
                setPadding(dp(4), dp(3), dp(4), dp(3))
                layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(3) }
                setOnClickListener { pressAndRun(this) { onClick() } }
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.parseColor("#E8DEF8"))
                    layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
        }

        // 全选/取消全选
        actionBar.addView(actionBtn(R.drawable.ic_agg_select_all, "全选") {
            if (selectedIndices.size >= displayCount) {
                selectedIndices.clear()
            } else {
                selectedIndices.clear()
                for (i in 0 until displayCount) {
                    selectedIndices.add(i)
                }
            }
            updateSearchResults(rl, results, actionBarContainer, preserveSelection = true)
        })

        // 复制地址
        actionBar.addView(actionBtn(R.drawable.ic_agg_copy, "地址") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val addrs = selectedIndices.map { results[it]["address"] as String }.joinToString("\n")
            copyToClipboard(addrs)
            Toast.makeText(this, "已复制${selectedIndices.size}个地址", Toast.LENGTH_SHORT).show()
        })

        // 复制机器码
        actionBar.addView(actionBtn(R.drawable.ic_agg_copy, "机器码") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val codes = selectedIndices.map { results[it]["machineCode"] as? String ?: "" }.joinToString("\n")
            copyToClipboard(codes)
            Toast.makeText(this, "已复制${selectedIndices.size}条机器码", Toast.LENGTH_SHORT).show()
        })

        // 复制值
        actionBar.addView(actionBtn(R.drawable.ic_agg_copy, "值") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val vals = selectedIndices.map { "${results[it]["value"]}" }.joinToString("\n")
            copyToClipboard(vals)
            Toast.makeText(this, "已复制${selectedIndices.size}个值", Toast.LENGTH_SHORT).show()
        })

        // saved list action
        val savedActionLabel = "保存"
        actionBar.addView(actionBtn(R.drawable.ic_agg_lock, savedActionLabel) {
            val chosen = selectedIndices.mapNotNull { index -> results.getOrNull(index) }
            val count = addResultsToSavedList(chosen)
            Toast.makeText(this, "已保存 " + count, Toast.LENGTH_SHORT).show()
        })
        // 添加到保存列表与 AI 对话
        actionBar.addView(actionBtn(R.drawable.ai, "AI") {
            if (selectedIndices.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            val selectedResults = selectedIndices.mapNotNull { index -> results.getOrNull(index) }
            addResultsToAIChat(selectedResults)
            Toast.makeText(this, "已添加${selectedResults.size}条到AI", Toast.LENGTH_SHORT).show()
        })

        // 编辑选中项（支持单条和多条）
        actionBar.addView(actionBtn(R.drawable.ic_agg_edit, "修改") {
            val selectedResults = selectedIndices.filter { it in results.indices }.map { results[it] }
            if (selectedResults.isEmpty()) { Toast.makeText(this, "请先勾选", Toast.LENGTH_SHORT).show(); return@actionBtn }
            showBatchEditDialog(selectedResults)
        })
        actionBar.addView(actionBtn(R.drawable.ic_agg_lock, "冻结") {
            toggleSelectedFreeze(results, rl, actionBarContainer)
        })
        actionBar.addView(actionBtn(R.drawable.ic_agg_refresh, "刷新") { refreshSearchValues() })

        if (actionBarContainer != null) {
            actionBarContainer.addView(actionBar)
            actionBarContainer.visibility = android.view.View.VISIBLE
        } else {
            rl.addView(actionBar)
        }

        rl.addView(TextView(this).apply {
            text = "显示 $displayCount / ${results.size} · 点击结果修改，勾选后可批量操作"
            setTextColor(Color.parseColor("#938F99"))
            textSize = 9.5f
            setPadding(dp(4), dp(2), dp(4), dp(5))
        })

        for (index in 0 until displayCount) {
            val r = results[index]
            val addr = r["address"] as String
            val addressLong = (r["addressInt"] as? Number)?.toLong()
                ?: addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                ?: continue
            val v = r["value"]
            val resultType = (r["type"] as? String)
                ?.takeIf { MemoryEngine.isSupportedType(it) }
                ?: searchDataType
            val mc = r["machineCode"] as? String ?: ""
            val isFrozen = MemoryFreezer.isFrozen(addressLong)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5))
                background = aggMenuDrawable(
                    if (isFrozen) Color.parseColor("#332B3D") else Color.parseColor("#25222B"),
                    8,
                    if (isFrozen) Color.parseColor("#B69DF8") else Color.parseColor("#3A3641")
                )
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                setOnClickListener {
                    val sv = rl.parent as? android.widget.ScrollView
                    savedScrollY = sv?.scrollY ?: 0
                    showWriteDialog(addr, v, mc, resultType)
                }
            }

            val topLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val checkBox = android.widget.CheckBox(this).apply {
                isChecked = selectedIndices.contains(index)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#67507D"))
                setPadding(0, 0, dp(6), 0)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIndices.add(index) else selectedIndices.remove(index)
                }
            }
            topLine.addView(checkBox)
            topLine.addView(TextView(this).apply {
                text = "#${index + 1}  $addr"
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            topLine.addView(TextView(this).apply {
                text = resultType.uppercase()
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#D0BCFF"))
                textSize = 8f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(Color.parseColor("#3A3345"), 6, Color.parseColor("#5C526A"))
            }, LinearLayout.LayoutParams(dp(50), dp(24)).apply { marginStart = dp(4); marginEnd = dp(5) })
            topLine.addView(TextView(this).apply {
                text = "$v"
                setTextColor(if (isFrozen) Color.parseColor("#D0BCFF") else Color.parseColor("#E6E0E9"))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f)
            })
            row.addView(topLine)

            if (mc.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = "机器码  $mc"
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 9.5f
                    setPadding(dp(38), dp(3), dp(4), 0)
                    typeface = android.graphics.Typeface.MONOSPACE
                })
            }

            val opLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(40), dp(8), 0, 0)
            }
            opLine.addView(iconBtn(R.drawable.ic_agg_edit, "修改") {
                val sv = rl.parent as? android.widget.ScrollView
                savedScrollY = sv?.scrollY ?: 0
                showWriteDialog(addr, v, mc, resultType)
            }, LinearLayout.LayoutParams(dp(54), dp(44)).apply { marginEnd = dp(6) })
            opLine.addView(iconBtn(R.drawable.ic_agg_memory, "浏览") {
                memoryEditorAddress = addressLong
                memoryEditorType = resultType
                showMemoryEditorPanel(addressLong, resultType)
            }, LinearLayout.LayoutParams(dp(54), dp(44)).apply { marginEnd = dp(6) })
            opLine.addView(iconBtn(R.drawable.ic_agg_lock, if (isFrozen) "解冻" else "冻结") {
                Thread {
                    val success = if (isFrozen) {
                        MemoryFreezer.unfreeze(addressLong)
                    } else {
                        v != null && MemoryFreezer.freeze(addressLong, v, resultType)
                    }
                    if (success) {
                        searchResults = searchResults.map { item ->
                            val itemAddress = (item["addressInt"] as? Number)?.toLong()
                            if (itemAddress == addressLong) {
                                item.toMutableMap().apply { this["isFrozen"] = !isFrozen }
                            } else item
                        }
                    }
                    handler.post {
                        Toast.makeText(
                            this@OverlayService,
                            if (success) (if (isFrozen) "已解冻 $addr" else "已冻结 $addr") else "操作失败",
                            Toast.LENGTH_SHORT
                        ).show()
                        updateSearchResults(rl, searchResults, actionBarContainer, preserveSelection = true)
                    }
                }.start()
            }, LinearLayout.LayoutParams(dp(54), dp(44)))
            row.addView(opLine)

            rl.addView(row)
        }
    }

    // 复制到剪贴板
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("search_results", text)
        clipboard.setPrimaryClip(clip)
    }

    // 将选中的搜索结果添加到AI对话
    private fun addResultsToAIChat(selectedResults: List<Map<String, Any>>) {
        val message = buildString {
            appendLine("📊 搜索结果分析请求：")
            appendLine()
            appendLine("数据类型: $searchDataType")
            appendLine("选中 ${selectedResults.size} 条结果：")
            appendLine()
            for ((i, r) in selectedResults.withIndex()) {
                val addr = r["address"] as String
                val v = r["value"]
                val mc = r["machineCode"] as? String ?: ""
                if (mc.isNotEmpty()) {
                    appendLine("${i+1}. 地址: $addr, 机器码: $mc, 值: $v")
                } else {
                    appendLine("${i+1}. 地址: $addr, 值: $v")
                }
            }
            appendLine()
            appendLine("请帮我分析这些内存地址的含义，以及可能的修改方案。")
        }

        // 添加到聊天记录
        chatMessages.add(Pair("👤 我", message))

        // 如果AI对话面板是打开的，直接刷新显示
        // 否则在下次打开时会自动显示
        Toast.makeText(this, "已添加到AI对话，请打开AI对话面板查看", Toast.LENGTH_SHORT).show()
    }

    private fun miniBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FFF3E0"))
            textSize = 11f
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#9A5A22"))
                setStroke(dp(1), Color.argb(42, 255, 255, 255))
            }
            setPadding(dp(10), dp(0), dp(10), dp(0))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun showWriteDialog(
        addr: String,
        curVal: Any?,
        machineCode: String = "",
        dataType: String = searchDataType,
        returnAction: (() -> Unit)? = null,
    ) {
        val address = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        if (address == null) {
            Toast.makeText(this, "地址格式不正确", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedType = dataType.takeIf { MemoryEngine.isSupportedType(it) } ?: "dword"
        val wasFrozen = MemoryFreezer.isFrozen(address)
        fun returnToCaller() {
            if (returnAction != null) returnAction() else showAggSearchTab()
        }

        makeDraggablePanel("编辑内存", { content ->
            content.setPadding(dp(20), dp(20), dp(20), 0)
            val message = TextView(this).apply {
                text = "$addr  [${normalizedType.uppercase()}]  当前值：${curVal ?: "?"}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(2), 0, dp(2), dp(5))
            }
            content.addView(message)
            if (machineCode.isNotBlank()) {
                content.addView(TextView(this).apply {
                    text = machineCode.take(120)
                    setTextColor(Color.parseColor("#FFB8B8B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 2
                    setPadding(dp(2), 0, dp(2), dp(5))
                })
            }

            fun input(hintText: String, initial: String = ""): EditText = EditText(this).apply {
                hint = hintText
                setText(initial)
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#FFB8B8B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(9), 0, dp(9), 0)
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            fun label(textValue: String): TextView = TextView(this).apply {
                text = textValue
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER_VERTICAL
            }

            val changeValue = android.widget.CheckBox(this).apply {
                isChecked = true
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val valueInput = input("输入新值", curVal?.toString().orEmpty()).apply { setSelectAllOnFocus(true) }
            val valueRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(changeValue, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(label("数值"), LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(valueInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(valueRow)

            val incrementInput = input("增量，例如 1")
            val addNotReplace = android.widget.CheckBox(this).apply {
                text = "相加而非替换"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val incrementRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("增量"), LinearLayout.LayoutParams(dp(84), dp(48)))
                addView(incrementInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(incrementRow)
            content.addView(addNotReplace, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))

            fun fillButton(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
                text = textValue
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                minWidth = dp(48)
                setPadding(dp(8), 0, dp(8), 0)
                background = aggMenuDrawable(Color.argb(30, 255, 255, 255), 3, Color.parseColor("#88FFFFFF"))
                setOnClickListener { action() }
            }
            val fillScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val fillRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun addFill(label: String, action: () -> Unit) {
                fillRow.addView(fillButton(label, action), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(48),
                ).apply { marginEnd = dp(5) })
            }
            addFill("填充") { valueInput.setText(curVal?.toString().orEmpty()) }
            addFill("U8") {
                Toast.makeText(this, "U8 字符串写入需要字节编辑模式", Toast.LENGTH_SHORT).show()
            }
            addFill("U16") {
                Toast.makeText(this, "U16 字符串写入需要字节编辑模式", Toast.LENGTH_SHORT).show()
            }
            addFill("HEX") {
                val raw = valueInput.text.toString().trim()
                when {
                    raw.startsWith("0x", true) -> raw.substring(2).toLongOrNull(16)?.let { valueInput.setText(it.toString()) }
                    raw.toLongOrNull() != null -> valueInput.setText("0x${raw.toLong().toString(16).uppercase()}")
                }
            }
            addFill("HEX+U8") {
                Toast.makeText(this, "HEX+U8 组合填充暂不支持当前数值类型", Toast.LENGTH_SHORT).show()
            }
            addFill("HEX+U16") {
                Toast.makeText(this, "HEX+U16 组合填充暂不支持当前数值类型", Toast.LENGTH_SHORT).show()
            }
            addFill("HEX+U8+U16") {
                Toast.makeText(this, "组合填充暂不支持当前数值类型", Toast.LENGTH_SHORT).show()
            }
            fillScroll.addView(fillRow)
            content.addView(fillScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ))

            val freezeCheck = android.widget.CheckBox(this).apply {
                text = "冻结"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isChecked = wasFrozen
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val freezeLabels = arrayOf("固定值", "允许增加", "允许减少", "限制范围")
            val freezeSpinner = Spinner(this).apply {
                adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_item, freezeLabels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(MemoryFreezer.getFreezeType(address)?.coerceIn(0, 3) ?: 0)
                background = aggMenuDrawable(Color.argb(35, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
            }
            val freezeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(freezeCheck, LinearLayout.LayoutParams(dp(92), dp(48)))
                addView(freezeSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(freezeRow)

            val freezeFrom = input("范围起点", curVal?.toString().orEmpty())
            val freezeTo = input("范围终点", curVal?.toString().orEmpty())
            val rangeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(freezeFrom, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
                addView(freezeTo, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            }
            content.addView(rangeRow)

            val saveAs = android.widget.CheckBox(this).apply {
                text = "另存为"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            val nameInput = input("名称", "地址 $addr")
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(saveAs, LinearLayout.LayoutParams(dp(92), dp(48)))
                addView(nameInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(nameRow)

            content.addView(TextView(this).apply {
                text = "更多"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener { showMemoryEditorPanel(address, normalizedType) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(5) })

            val state = TextView(this).apply {
                text = "可同时修改、冻结并保存到列表"
                setTextColor(Color.parseColor("#FFB8B8B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(3), dp(4), dp(3), dp(4))
            }
            content.addView(state)

            fun addNumbers(base: Any?, delta: Any, type: String): Any? {
                val b = (base as? Number)?.toDouble() ?: base?.toString()?.toDoubleOrNull() ?: return null
                val d = (delta as? Number)?.toDouble() ?: delta.toString().toDoubleOrNull() ?: return null
                val result = b + d
                return if (type == "float" || type == "double") result else result.toLong()
            }

            fun updateResult(value: Any, frozen: Boolean) {
                searchResults = searchResults.map { item ->
                    val itemAddress = (item["addressInt"] as? Number)?.toLong()
                    if (itemAddress == address) item.toMutableMap().apply {
                        this["value"] = value
                        this["type"] = normalizedType
                        this["isFrozen"] = frozen
                    } else item
                }
            }

            fun persistNamedItem(value: Any, frozen: Boolean) {
                if (!saveAs.isChecked) return
                val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
                val packageName = prefs.getString("attached_package", "")
                    ?.takeIf { it.isNotBlank() } ?: "pid:${MemoryEngine.getAttachedPid() ?: 0}"
                val items = loadSavedMemoryItems()
                val candidate = SavedMemoryItem(
                    address = address,
                    type = normalizedType,
                    packageName = packageName,
                    label = nameInput.text.toString().trim().ifBlank { "地址 $addr" },
                    lastValue = value.toString(),
                    freeze = frozen,
                    freezeType = freezeSpinner.selectedItemPosition.coerceIn(0, 3),
                    freezeFrom = freezeFrom.text.toString().trim(),
                    freezeTo = freezeTo.text.toString().trim(),
                )
                val index = items.indexOfFirst { savedItemKey(it) == savedItemKey(candidate) }
                if (index >= 0) items[index] = candidate else items.add(candidate)
                persistSavedMemoryItems(items)
            }

            fun execute() {
                state.text = "正在应用…"
                state.setTextColor(Color.WHITE)
                Thread applyEdit@{
                    val current = MemoryEngine.readMemory(address, normalizedType) ?: curVal
                    var target: Any? = current
                    var success = true
                    if (changeValue.isChecked) {
                        val sourceText = incrementInput.text.toString().trim().takeIf { it.isNotEmpty() }
                            ?: valueInput.text.toString().trim()
                        val parsed = parseMemoryValue(sourceText, normalizedType)
                        if (parsed == null) {
                            handler.post {
                                state.text = "数值或增量格式不正确"
                                state.setTextColor(Color.parseColor("#FFFF8A80"))
                            }
                            return@applyEdit
                        }
                        target = if (addNotReplace.isChecked || incrementInput.text.toString().isNotBlank()) {
                            addNumbers(current, parsed, normalizedType)
                        } else parsed
                        if (target == null) success = false
                        else success = MemoryEngine.writeMemory(address, target, normalizedType)
                    }

                    if (success && freezeCheck.isChecked) {
                        val freezeType = freezeSpinner.selectedItemPosition.coerceIn(0, 3)
                        val from = if (freezeType == MemoryFreezer.FREEZE_IN_RANGE) parseMemoryValue(freezeFrom.text.toString(), normalizedType) else null
                        val to = if (freezeType == MemoryFreezer.FREEZE_IN_RANGE) parseMemoryValue(freezeTo.text.toString(), normalizedType) else null
                        success = target != null && MemoryFreezer.freeze(address, target, normalizedType, freezeType, from, to)
                    } else if (success && wasFrozen && !freezeCheck.isChecked) {
                        MemoryFreezer.unfreeze(address)
                    }

                    val readBack = if (success) MemoryEngine.readMemory(address, normalizedType) ?: target else target
                    if (success && readBack != null) {
                        val frozen = MemoryFreezer.isFrozen(address)
                        updateResult(readBack, frozen)
                        persistNamedItem(readBack, frozen)
                    }
                    handler.post {
                        if (success && readBack != null) {
                            Toast.makeText(this@OverlayService, "已应用 $addr = $readBack", Toast.LENGTH_SHORT).show()
                            returnToCaller()
                        } else {
                            state.text = "应用失败，请检查地址、类型和冻结范围"
                            state.setTextColor(Color.parseColor("#FFFF8A80"))
                        }
                    }
                }.start()
            }

            fun button(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
                text = textValue
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = aggMenuDrawable(Color.argb(42, 255, 255, 255), 4, Color.parseColor("#FFB8B8B8"))
                setOnClickListener { action() }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            actions.addView(button("取消") { returnToCaller() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            actions.addView(button("应用") { execute() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 390, 610, onBack = { returnToCaller() }, titleIcon = R.drawable.ic_agg_edit)
    }

    private fun showWriteDialogLegacy(
        addr: String,
        curVal: Any?,
        machineCode: String = "",
        dataType: String = searchDataType,
    ) {
        makeDraggablePanel("修改内存值", { content ->
            content.addView(TextView(this).apply { text = "地址: $addr"; setTextColor(Color.parseColor("#A1887F")); textSize = 12f; setPadding(dp(12), dp(8), dp(12), dp(2)) })
            if (machineCode.isNotEmpty()) {
                content.addView(TextView(this).apply { text = "机器码: $machineCode"; setTextColor(Color.parseColor("#8D6E63")); textSize = 11f; setPadding(dp(12), dp(2), dp(12), dp(2)) })
            }
            content.addView(TextView(this).apply { text = "当前值: $curVal"; setTextColor(Color.parseColor("#A1887F")); textSize = 12f; setPadding(dp(12), dp(2), dp(12), dp(8)) })

            val inp = EditText(this).apply {
                hint = "输入新值"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#8B4513")) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(curVal.toString())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
            }
            content.addView(inp)

            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
            bar.addView(smallBtn("取消") { showAggSearchTab() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("确认修改") {
                val nv = inp.text.toString().trim()
                if (nv.isEmpty()) return@smallBtn
                val addrLong = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return@smallBtn
                val numVal = parseMemoryValue(nv, dataType)
                if (numVal == null) {
                    Toast.makeText(this@OverlayService, "数值格式不正确", Toast.LENGTH_SHORT).show()
                    return@smallBtn
                }
                Thread {
                    var success = MemoryEngine.writeMemory(addrLong, numVal, dataType)
                    if (success && MemoryFreezer.isFrozen(addrLong)) {
                        success = MemoryFreezer.freeze(addrLong, numVal, dataType)
                    }
                    // 修改完成后更新列表中该地址的值
                    if (success) {
                        searchResults = searchResults.map { r ->
                            val rAddr = (r["addressInt"] as? Number)?.toLong()
                            if (rAddr == addrLong) {
                                r.toMutableMap().apply {
                                    this["value"] = numVal
                                    this["type"] = dataType
                                }
                            } else r
                        }
                    }
                    handler.post {
                        if (success) {
                            Toast.makeText(this@OverlayService, "✅ 修改成功: $addr = $numVal", Toast.LENGTH_SHORT).show()
                            showAggSearchTab()
                        } else {
                            Toast.makeText(this@OverlayService, "❌ 修改失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 280, 280, onBack = { showAggSearchTab() })
    }

    // 批量编辑对话框（支持单条和多条）
    private fun showBatchEditDialog(selectedResults: List<Map<String, Any>>) {
        if (selectedResults.isEmpty()) {
            Toast.makeText(this, "没有选中的结果", Toast.LENGTH_SHORT).show()
            return
        }
        val count = selectedResults.size

        makeDraggablePanel("批量编辑 · $count 条", { content ->
            val preview = selectedResults.take(8).joinToString("\n") { item ->
                val address = item["address"] as? String ?: "?"
                val type = (item["type"] as? String ?: searchDataType).uppercase()
                "$address  [$type]  = ${item["value"]}"
            } + if (count > 8) "\n… 另有 ${count - 8} 条" else ""

            val previewCard = TextView(this).apply {
                text = preview
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
                maxLines = 9
            }
            content.addView(previewCard)

            val input = EditText(this).apply {
                hint = "输入新值；多个值用分号分隔"
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 13f
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setPadding(dp(12), 0, dp(12), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(7) })

            val state = TextView(this).apply {
                text = "单值应用到全部；7;13;43 会按顺序循环写入"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 9.5f
                setPadding(dp(3), dp(5), dp(3), dp(3))
            }
            content.addView(state)

            fun applyBatch(freezeAfter: Boolean) {
                val rawValues = input.text.toString().split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (rawValues.isEmpty()) {
                    state.text = "请输入新值"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return
                }
                state.text = if (freezeAfter) "正在批量写入并冻结…" else "正在批量写入…"
                state.setTextColor(Color.parseColor("#D0BCFF"))

                Thread {
                    var successCount = 0
                    val updated = mutableMapOf<Long, Pair<Any, String>>()
                    for ((position, item) in selectedResults.withIndex()) {
                        val address = (item["addressInt"] as? Number)?.toLong()
                            ?: (item["address"] as? String)
                                ?.removePrefix("0x")
                                ?.removePrefix("0X")
                                ?.toLongOrNull(16)
                            ?: continue
                        val type = (item["type"] as? String)
                            ?.takeIf { MemoryEngine.isSupportedType(it) }
                            ?: searchDataType
                        val parsed = parseMemoryValue(rawValues[position % rawValues.size], type) ?: continue
                        var success = MemoryEngine.writeMemory(address, parsed, type)
                        if (success && (freezeAfter || MemoryFreezer.isFrozen(address))) {
                            success = MemoryFreezer.freeze(address, parsed, type)
                        }
                        if (success) {
                            successCount++
                            val readBack = MemoryEngine.readMemory(address, type) ?: parsed
                            updated[address] = readBack to type
                        }
                    }

                    if (updated.isNotEmpty()) {
                        searchResults = searchResults.map { item ->
                            val address = (item["addressInt"] as? Number)?.toLong()
                            val change = if (address != null) updated[address] else null
                            if (address != null && change != null) {
                                item.toMutableMap().apply {
                                    this["value"] = change.first
                                    this["type"] = change.second
                                    this["isFrozen"] = MemoryFreezer.isFrozen(address)
                                }
                            } else item
                        }
                    }

                    handler.post {
                        selectedIndices.clear()
                        Toast.makeText(
                            this@OverlayService,
                            if (freezeAfter) "已修改并冻结 $successCount/$count 条" else "已修改 $successCount/$count 条",
                            Toast.LENGTH_SHORT,
                        ).show()
                        showAggSearchTab()
                    }
                }.start()
            }

            fun batchButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
                    setTypeface(null, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                    setTextColor(if (accent) Color.parseColor("#231A2E") else Color.parseColor("#E6E0E9"))
                    background = aggMenuDrawable(
                        if (accent) Color.parseColor("#D0BCFF") else Color.parseColor("#302D35"),
                        9,
                        if (accent) Color.parseColor("#E8DEF8") else Color.parseColor("#49454F")
                    )
                    setOnClickListener { action() }
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            actions.addView(batchButton("取消") { showAggSearchTab() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            actions.addView(batchButton("批量写入", true) { applyBatch(false) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(batchButton("写入并冻结") { applyBatch(true) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 380, 390, onBack = { showAggSearchTab() }, titleIcon = R.drawable.ic_agg_edit)
    }

    private fun showBatchEditDialogLegacy(selectedResults: List<Map<String, Any>>) {
        val count = selectedResults.size
        makeDraggablePanel("编辑 $count 条数据", { content ->
            // 显示选中的地址列表（机器码+值）
            val addrList = selectedResults.joinToString("\n") { r ->
                val addr = r["address"] as String
                val v = r["value"]
                val mc = r["machineCode"] as? String ?: ""
                if (mc.isNotEmpty()) "$mc = $v" else "$addr = $v"
            }
            content.addView(TextView(this).apply {
                text = addrList; setTextColor(Color.parseColor("#A1887F")); textSize = 11f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                maxLines = 6
            })

            // 输入新值
            content.addView(TextView(this).apply {
                text = "输入新值（将应用到所有 $count 条数据）："; setTextColor(Color.parseColor("#FFF3E0")); textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            })

            val inp = EditText(this).apply {
                hint = "输入新值"; setTextColor(Color.parseColor("#FFF3E0")); setHintTextColor(Color.parseColor("#BCAAA4"))
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.parseColor("#8B4513")) }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12); marginEnd = dp(12) }
            }
            content.addView(inp)

            // 按钮
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(8)) }
            bar.addView(smallBtn("取消") { showAggSearchTab() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(smallBtn("确认修改") {
                val nv = inp.text.toString()
                if (nv.isEmpty()) return@smallBtn

                Thread {
                    var successCount = 0
                    for (r in selectedResults) {
                        val addr = r["address"] as String
                        val addrLong = (r["addressInt"] as? Number)?.toLong()
                            ?: addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
                            ?: continue
                        val itemType = (r["type"] as? String)
                            ?.takeIf { MemoryEngine.isSupportedType(it) }
                            ?: searchDataType
                        val numVal = parseMemoryValue(nv, itemType) ?: continue
                        var success = MemoryEngine.writeMemory(addrLong, numVal, itemType)
                        if (success && MemoryFreezer.isFrozen(addrLong)) {
                            success = MemoryFreezer.freeze(addrLong, numVal, itemType)
                        }
                        if (success) {
                            successCount++
                            // 更新 searchResults 中对应地址的值
                            searchResults = searchResults.map { sr ->
                                val srAddr = (sr["addressInt"] as? Number)?.toLong()
                                if (srAddr == addrLong) {
                                    sr.toMutableMap().apply {
                                        this["value"] = numVal
                                        this["type"] = itemType
                                    }
                                } else sr
                            }
                        }
                    }
                    handler.post {
                        if (successCount == count) {
                            Toast.makeText(this@OverlayService, "✅ 成功修改 $successCount 条数据", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@OverlayService, "⚠️ 修改完成：成功 $successCount/$count 条", Toast.LENGTH_SHORT).show()
                        }
                        selectedIndices.clear()
                        showAggSearchTab()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 300, 350)
    }

    // ==================== 跳转到主应用 ====================

    private fun jumpToPage(page: String) {
        closePanel()
        try {
            // 保存到 SharedPreferences 作为备用
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            prefs.edit().putString("pending_page", page).apply()
            
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("page", page)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== AI 对话面板 ====================

    private fun showAIChatPanel() {
        saveLastPanel("chat")
        makeDraggablePanel("AI 对话", { content ->
            // AI 对话深色主题覆盖
            content.setBackgroundColor(Color.parseColor("#723d09"))

            // 获取附加进程信息
            val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
            val attachedPid = prefs.getInt("attached_pid", -1)
            val attachedName = prefs.getString("attached_name", "")
            val attachedPackage = prefs.getString("attached_package", "")

            // 状态显示
            val status = TextView(this).apply {
                text = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "✅ 已附加: $attachedName"
                } else {
                    "⚠️ 未附加进程，请先附加游戏"
                }
                setTextColor(if (attachedPid != -1) Color.parseColor("#66BB6A") else Color.parseColor("#FF8F00"))
                textSize = 11f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            // 分割线
            content.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#8B4513"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })

            // 消息显示区域
            val messageArea = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#723d09"))
                }
            }
            val messageList = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            // 如果是首次打开，添加欢迎消息
            if (chatMessages.isEmpty()) {
                val welcomeMsg = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "🤖 AI 助手已就绪！\n\n当前已附加: $attachedName\n\n请告诉我你想修改什么游戏数据？\n\n💡 提示：如果切换其他附加进程后，记得清空聊天，以免 AI 读错上下文"
                } else {
                    "🤖 AI 助手\n\n⚠️ 请先附加游戏进程\n点击返回 → 附加进程"
                }
                chatMessages.add(Pair("🤖 AI", welcomeMsg))
            }

            // 恢复所有历史消息
            for ((sender, msg) in chatMessages) {
                val isUser = sender == "👤 我"
                messageList.addView(createMessageBubble(sender, msg, isUser))
            }

            messageArea.addView(messageList)
            content.addView(messageArea)

            // 恢复滚动位置，或滚动到底部
            if (savedScrollY > 0) {
                val restoreY = savedScrollY
                savedScrollY = 0
                messageArea.post { messageArea.scrollY = restoreY }
                // WebView 异步加载后再次恢复（内容高度变化会导致位置偏移）
                messageArea.postDelayed({ messageArea.scrollY = restoreY }, 500)
                messageArea.postDelayed({ messageArea.scrollY = restoreY }, 1500)
            } else {
                messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                messageArea.postDelayed({ messageArea.fullScroll(ScrollView.FOCUS_DOWN) }, 500)
                messageArea.postDelayed({ messageArea.fullScroll(ScrollView.FOCUS_DOWN) }, 1500)
            }

            // 输入区域
            val inputArea = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setBackgroundColor(Color.parseColor("#723d09"))
            }

            val inputField = EditText(this).apply {
                hint = "输入你的需求..."
                setTextColor(Color.parseColor("#FFF3E0"))
                setHintTextColor(Color.parseColor("#BCAAA4"))
                textSize = 13f
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#8B4513"))
                    setStroke(dp(1), Color.WHITE)
                }
                setPadding(dp(5), dp(5), dp(5), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                isFocusable = true
                isFocusableInTouchMode = true

                setOnClickListener {
                    requestFocus()
                    post {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                    }
                }

                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        post {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                        }
                    }
                }

                post {
                    requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
            }

            val sendBtn = TextView(this).apply {
                text = "发送"
                setTextColor(Color.parseColor("#FFF3E0"))
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#A1612D"))
                }
                setPadding(dp(5), dp(5), dp(5), dp(5))
                setOnClickListener {
                    val userInput = inputField.text.toString().trim()
                    if (userInput.isNotEmpty() && !isAiResponding) {
                        // 添加用户消息到历史
                        chatMessages.add(Pair("👤 我", userInput))
                        messageList.addView(createMessageBubble("👤 我", userInput, true))
                        inputField.text.clear()

                        // 隐藏输入法
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(inputField.windowToken, 0)

                        // 创建流式输出气泡
                        isAiResponding = true
                        val streamBubble = LinearLayout(this@OverlayService).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = dp(8) }
                        }
                        streamBubble.addView(TextView(this@OverlayService).apply {
                            text = "🤖 AI"
                            setTextColor(Color.parseColor("#8D6E63"))
                            textSize = 11f
                            setPadding(0, 0, 0, dp(2))
                        })
                        val streamText = TextView(this@OverlayService).apply {
                            text = "正在思考..."
                            setTextColor(Color.parseColor("#FFF3E0"))
                            textSize = 12f
                            background = GradientDrawable().apply {
                                cornerRadius = dp(8).toFloat()
                                setColor(Color.parseColor("#8B4513"))
                            }
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                        }
                        streamBubble.addView(streamText)
                        messageList.addView(streamBubble)
                        messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

                        // 调用 LLM API（支持 function calling）
                        Thread {
                            try {
                                val result = callLlmApi(userInput, attachedName ?: "")
                                handler.post {
                                    chatMessages.add(Pair("🤖 AI", result))
                                    isAiResponding = false
                                    streamBubble.removeView(streamText)
                                    streamBubble.addView(createMarkdownWebView(result))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                            } catch (e: Exception) {
                                handler.post {
                                    val errorMsg = "❌ 请求失败: ${e.message}\n\n请检查设置中的 API 配置"
                                    chatMessages.add(Pair("🤖 AI", errorMsg))
                                    isAiResponding = false
                                    streamBubble.removeView(streamText)
                                    streamBubble.addView(createMarkdownWebView(errorMsg))
                                    messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }
                                }
                            }
                        }.start()
                    }
                }
            }

            inputArea.addView(inputField)
            inputArea.addView(sendBtn)
            content.addView(inputArea)

            // 底部按钮栏：保存聊天 + 清空聊天 + 关闭窗口
            val actionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val smallBtnStyle = { text: String, onClick: () -> Unit ->
                TextView(this).apply {
                    this.text = text; setTextColor(Color.parseColor("#FFF3E0")); textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(Color.parseColor("#A1612D")) }
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    setOnClickListener { onClick() }
                }
            }
            actionBar.addView(smallBtnStyle("保存") { saveChatToStorage() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(smallBtnStyle("清空") {
                chatMessages.clear()
                messageList.removeAllViews()
                val welcomeMsg = if (attachedPid != -1 && !attachedName.isNullOrEmpty()) {
                    "🤖 AI 助手已就绪！\n\n当前已附加: $attachedName\n\n请告诉我你想修改什么游戏数据？\n\n💡 提示：如果切换其他附加进程后，记得清空聊天，以免 AI 读错上下文"
                } else {
                    "🤖 AI 助手\n\n⚠️ 请先附加游戏进程"
                }
                chatMessages.add(Pair("🤖 AI", welcomeMsg))
                messageList.addView(createMessageBubble("🤖 AI", welcomeMsg, false))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(smallBtnStyle("关闭") { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(actionBar)

            // 滚动到底部
            messageArea.post { messageArea.fullScroll(ScrollView.FOCUS_DOWN) }

        }, 320, 550, titleIcon = R.drawable.ai)
    }

    // 创建消息气泡（用户消息用 TextView，AI 消息用 WebView 渲染 Markdown/LaTeX/Mermaid）
    private fun createMessageBubble(sender: String, message: String, isUser: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }

            // 发送者标签
            addView(TextView(this@OverlayService).apply {
                text = sender
                setTextColor(if (isUser) Color.parseColor("#FFCC80") else Color.parseColor("#FFB74D"))
                textSize = 11f
                setPadding(0, 0, 0, dp(2))
            })

            if (isUser) {
                // 用户消息用 TextView
                addView(TextView(this@OverlayService).apply {
                    text = message
                    setTextColor(Color.parseColor("#FFF3E0"))
                    textSize = 12f
                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#8B4513"))
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                })
            } else {
                // AI 消息统一用 WebView 渲染（支持 Markdown、代码块、Mermaid 图表）
                addView(createMarkdownWebView(message))
            }
        }
    }

    /**
     * LaTeX 预处理（在转义之前处理原始 Markdown 内容）
     * 参考 chatbox 的 latex.ts：标准化 LaTeX 分隔符，保护代码块不被误解析
     */
    private fun preprocessLaTeX(content: String): String {
        var result = content

        // Step 1: 保护代码块（用占位符替换，防止内部被处理）
        val codeBlocks = mutableListOf<String>()
        val codeBlockRegex = Regex("(```[\\s\\S]*?```|`[^`\\n]+`)")
        result = codeBlockRegex.replace(result) { match ->
            codeBlocks.add(match.value)
            "<<CODE_BLOCK_${codeBlocks.size - 1}>>"
        }

        // Step 2: 保护已有的 LaTeX 表达式
        val latexExpressions = mutableListOf<String>()
        val latexRegex = Regex("(\\$\\$[\\s\\S]*?\\$\\$|\\$[^$\\n]*?\\$|\\\\\\[[\\s\\S]*?\\\\]|\\\\\\(.*?\\\\\\))")
        result = latexRegex.replace(result) { match ->
            latexExpressions.add(match.value)
            "<<LATEX_${latexExpressions.size - 1}>>"
        }

        // Step 3: 转义货币符号（$后跟数字的情况）
        result = result.replace(Regex("\\$(?=\\d)"), "\\$")

        // Step 4: 恢复 LaTeX 表达式
        result = result.replace(Regex("<<LATEX_(\\d+)>>")) { match ->
            val index = match.groupValues[1].toInt()
            latexExpressions[index]
        }

        // Step 5: 恢复代码块
        result = result.replace(Regex("<<CODE_BLOCK_(\\d+)>>")) { match ->
            val index = match.groupValues[1].toInt()
            codeBlocks[index]
        }

        // Step 6: 标准化括号分隔符 \[...\] -> $$...$$，\(...\) -> $...$
        val bracketRegex = Regex("(```[\\S\\s]*?```|`.*?`)|\\\\\\[([\\S\\s]*?[^\\\\])\\\\]|\\\\\\((.*?)\\\\\\)")
        result = bracketRegex.replace(result) { match ->
            when {
                match.groupValues[1].isNotEmpty() -> match.groupValues[1] // 代码块，跳过
                match.groupValues[2].isNotEmpty() -> "$$${match.groupValues[2]}$$" // \[...\] -> $$...$$
                match.groupValues[3].isNotEmpty() -> "$${match.groupValues[3]}$" // \(...\) -> $...$
                else -> match.value
            }
        }

        return result
    }

    /**
     * 创建 WebView 渲染 Markdown/LaTeX/Mermaid
     */
    private fun createMarkdownWebView(markdownContent: String): WebView {
        // 仅转义 JS 字符串必须转义的字符：反斜杠、单引号、换行
        // $ 和 ` 不转义（占位符方式注入，不会触发 Kotlin 模板插值）
        val escapedContent = markdownContent
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val html = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        font-size: 14px;
        line-height: 1.6;
        color: #FFF3E0;
        background: #723d09;
        padding: 10px;
        word-wrap: break-word;
        overflow-wrap: break-word;
    }
    h1, h2, h3, h4, h5, h6 {
        color: #FFCC80;
        margin: 12px 0 6px 0;
        font-weight: 600;
    }
    h1 { font-size: 20px; }
    h2 { font-size: 17px; }
    h3 { font-size: 15px; }
    p { margin: 6px 0; }
    a { color: #FFB74D; text-decoration: none; }
    code {
        background: #8B4513;
        color: #FFCC80;
        padding: 2px 6px;
        border-radius: 4px;
        font-family: 'Courier New', monospace;
        font-size: 13px;
    }
    pre {
        background: #5D2F0A;
        border: 1px solid #8B4513;
        border-radius: 8px;
        padding: 12px;
        margin: 8px 0;
        overflow-x: auto;
    }
    pre code {
        background: none;
        padding: 0;
        color: #FFF3E0;
        font-size: 13px;
    }
    blockquote {
        border-left: 4px solid #FFB74D;
        padding-left: 12px;
        margin: 8px 0;
        color: #BCAAA4;
    }
    ul, ol { margin: 6px 0; padding-left: 24px; }
    li { margin: 3px 0; }
    table {
        border-collapse: collapse;
        width: 100%;
        margin: 8px 0;
    }
    th, td {
        border: 1px solid #8B4513;
        padding: 6px 10px;
        text-align: left;
    }
    th { background: #5D2F0A; color: #FFCC80; }
    hr { border: none; border-top: 1px solid #8B4513; margin: 12px 0; }
    img { max-width: 100%; border-radius: 8px; }
    strong { color: #FFF3E0; }
    em { color: #BCAAA4; }
    .mermaid {
        max-width: 100%;
        max-height: 400px;
        overflow: auto;
        -webkit-overflow-scrolling: touch;
        background: #5D2F0A;
        border-radius: 8px;
        padding: 8px;
        margin: 8px 0;
    }
    .mermaid svg {
        max-width: 100%;
        height: auto;
    }
    .table-wrapper {
        overflow-x: auto;
        -webkit-overflow-scrolling: touch;
        max-width: 100%;
    }
    /* 聊天气泡 */
    .msg { margin-bottom: 16px; }
    .msg-header { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; }
    .msg-icon { font-size: 14px; }
    .msg-sender { font-size: 12px; color: #BCAAA4; }
    .msg-time { font-size: 10px; color: #BCAAA4; }
    .msg-user .msg-header { justify-content: flex-end; }
    .bubble { padding: 10px 14px; border-radius: 12px; max-width: 85%; word-break: break-word; }
    .bubble-user { background: #8B4513; border: 1px solid #FFFFFF; border-radius: 12px; padding: 10px 14px; margin-left: auto; }
    .bubble-ai { background: #A1612D; border: 1px solid #FFFFFF; border-radius: 12px; padding: 10px 14px; }
    .msg-user .msg-sender { color: #FFCC80; }
    .msg-ai .msg-sender { color: #FFB74D; }
</style>
<!-- Prism.js 代码高亮 (本地) -->
<link rel="stylesheet" href="file:///android_asset/css/prism-tomorrow.min.css">
<script src="file:///android_asset/js/prism.min.js"></script>
<!-- KaTeX for LaTeX (本地) -->
<link rel="stylesheet" href="file:///android_asset/css/katex.min.css">
<script src="file:///android_asset/js/katex.min.js"></script>
<script src="file:///android_asset/js/auto-render.min.js"></script>
<!-- Marked for Markdown (本地) -->
<script src="file:///android_asset/js/marked.min.js"></script>
<!-- Mermaid for diagrams (本地) -->
<script src="file:///android_asset/js/mermaid.min.js"></script>
</head>
<body>
<div id="content"></div>
<script>
(function() {
    // 初始化 Mermaid
    mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        themeVariables: {
            primaryColor: '#8D6E63',
            primaryTextColor: '#E0E0E0',
            primaryBorderColor: '#E8DDD5',
            lineColor: '#8D6E63',
            secondaryColor: '#FFF9F0',
            tertiaryColor: '#FDFBF7'
        }
    });

    // 配置 marked + Prism.js 代码高亮
    var renderer = new marked.Renderer();
    renderer.code = function(code, lang) {
        var codeText = (typeof code === 'object') ? code.text : code;
        var langStr = (typeof code === 'object') ? code.lang : lang;
        var escaped = codeText.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        if (langStr && Prism.languages[langStr]) {
            try {
                var highlighted = Prism.highlight(codeText, Prism.languages[langStr], langStr);
                return '<pre class="language-' + langStr + '"><code class="language-' + langStr + '">' + highlighted + '</code></pre>';
            } catch(e) {}
        }
        return '<pre class="language-' + (langStr || 'none') + '"><code class="language-' + (langStr || 'none') + '">' + escaped + '</code></pre>';
    };
    marked.setOptions({ breaks: true, gfm: true, renderer: renderer });

    var rawContent = '___CONTENT_PLACEHOLDER___';

    // ====== LaTeX 保护：在 marked 解析前，用占位符保护 LaTeX 表达式 ======
    var latexStore = [];
    function protectLaTeX(text) {
        // 保护代码块
        var codeBlocks = [];
        text = text.replace(/(```[\s\S]*?```|`[^`\n]+`)/g, function(m, c) {
            codeBlocks.push(c);
            return '\x00CODE' + (codeBlocks.length-1) + '\x00';
        });
        // 保护 $$...$$ (display)
        text = text.replace(/\$\$([\s\S]*?)\$\$/g, function(m, inner) {
            latexStore.push('$$' + inner + '$$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 保护 $...$ (inline)
        text = text.replace(/\$([^\$\n]+?)\$/g, function(m, inner) {
            latexStore.push('$' + inner + '$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 保护 \[...\] 和 \(...\)
        text = text.replace(/\\\[([\s\S]*?)\\\]/g, function(m, inner) {
            latexStore.push('$$' + inner + '$$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        text = text.replace(/\\\((.*?)\\\)/g, function(m, inner) {
            latexStore.push('$' + inner + '$');
            return '\x00LATEX' + (latexStore.length-1) + '\x00';
        });
        // 恢复代码块
        text = text.replace(/\x00CODE(\d+)\x00/g, function(m, i) { return codeBlocks[parseInt(i)]; });
        return text;
    }

    // ====== 第一步：保护 LaTeX ======
    var protectedContent = protectLaTeX(rawContent);

    // ====== 第二步：marked 解析 Markdown ======
    var htmlContent = marked.parse(protectedContent);

    // ====== 第三步：恢复 LaTeX 占位符 ======
    htmlContent = htmlContent.replace(/\x00LATEX(\d+)\x00/g, function(m, i) {
        return '<span class="katex-placeholder" data-latex="' +
            latexStore[parseInt(i)].replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;') +
            '"></span>';
    });

    // ====== 第四步：注入 DOM ======
    document.getElementById('content').innerHTML = htmlContent;

    // ====== 第五步：分离 mermaid 代码块，转为 <div class="mermaid"> ======
    var mermaidBlocks = document.querySelectorAll('code.language-mermaid');
    mermaidBlocks.forEach(function(block, idx) {
        var pre = block.parentElement;
        var div = document.createElement('div');
        div.className = 'mermaid';
        div.textContent = block.textContent;
        pre.parentNode.replaceChild(div, pre);
    });

    // ====== 第六步：表格滚动包裹 ======
    document.querySelectorAll('#content table').forEach(function(table) {
        var wrapper = document.createElement('div');
        wrapper.className = 'table-wrapper';
        table.parentNode.insertBefore(wrapper, table);
        wrapper.appendChild(table);
    });

    // ====== 第七步：渲染 LaTeX (KaTeX) ======
    document.querySelectorAll('.katex-placeholder').forEach(function(el) {
        var latex = el.getAttribute('data-latex');
        try {
            var isDisplay = latex.substring(0, 2) === '$$';
            var tex = isDisplay ? latex.substring(2, latex.length - 2) : latex.substring(1, latex.length - 1);
            katex.render(tex, el, { displayMode: isDisplay, throwOnError: false });
        } catch(e) {
            el.textContent = latex;
            el.style.color = '#FF5252';
        }
    });

    // ====== 第八步：渲染 Mermaid（异步）并通知高度 ======
    var notifyDone = function() {
        // 告知 WebView 内容已全部渲染完毕，可以测量高度
        window.__renderDone = true;
    };

    if (mermaidBlocks.length > 0) {
        // 兼容 mermaid v10 (run) 和 v8/v9 (init)
        if (typeof mermaid.run === 'function') {
            mermaid.run().then(function() { notifyDone(); }).catch(function(e) {
                console.error('Mermaid error:', e);
                notifyDone();
            });
        } else if (typeof mermaid.init === 'function') {
            try { mermaid.init(undefined, document.querySelectorAll('.mermaid')); } catch(e) { console.error(e); }
            notifyDone();
        } else {
            notifyDone();
        }
    } else {
        notifyDone();
    }
})();
</script>
</body>
</html>
""".trimIndent().replace("___CONTENT_PLACEHOLDER___", escapedContent)

        return WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#723d09"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 等待渲染完成（含 mermaid 异步）后再测量高度
                    view?.evaluateJavascript(
                        """
                        (function() {
                            function checkAndMeasure() {
                                if (window.__renderDone) {
                                    return document.body.scrollHeight;
                                }
                                // 每 100ms 检查一次，最多 5 秒
                                var tries = 0;
                                var timer = setInterval(function() {
                                    tries++;
                                    if (window.__renderDone || tries > 50) {
                                        clearInterval(timer);
                                        var h = document.body.scrollHeight;
                                        window.__measuredHeight = h;
                                    }
                                }, 100);
                                return -1;
                            }
                            return checkAndMeasure();
                        })();
                        """.trimIndent()
                    ) { value ->
                        try {
                            val initial = value.replace("\"", "").toFloatOrNull() ?: 0f
                            if (initial > 0) {
                                // 渲染已完成，直接调整
                                val layoutParams = this@apply.layoutParams
                                layoutParams.height = (initial * resources.displayMetrics.density).toInt() + dp(20)
                                this@apply.layoutParams = layoutParams
                            } else {
                                // 等待异步渲染完成后轮询高度
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                val checkRunnable = object : Runnable {
                                    override fun run() {
                                        view?.evaluateJavascript("window.__measuredHeight || document.body.scrollHeight") { v ->
                                            try {
                                                val h = v.replace("\"", "").toFloatOrNull() ?: 0f
                                                if (h > 0) {
                                                    val lp = this@apply.layoutParams
                                                    lp.height = (h * resources.displayMetrics.density).toInt() + dp(20)
                                                    this@apply.layoutParams = lp
                                                } else {
                                                    handler.postDelayed(this, 200)
                                                }
                                            } catch (_: Exception) {}
                                        }
                                    }
                                }
                                handler.postDelayed(checkRunnable, 200)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        }
    }

    // ==================== 真实 LLM API 调用 ====================

    private fun callLlmApi(userInput: String, attachedApp: String): String {
        // 从 SharedPreferences 读取 LLM 配置（由主应用保存）
        val configPrefs = getSharedPreferences("gg_llm_config", Context.MODE_PRIVATE)
        val configJson = configPrefs.getString("config", null)

        var baseUrl = ""
        var apiKey = ""
        var model = "deepseek-chat"

        if (configJson != null) {
            try {
                val json = JSONObject(configJson)
                baseUrl = json.optString("baseUrl", "")
                apiKey = json.optString("apiKey", "")
                model = json.optString("model", "deepseek-chat")
            } catch (e: Exception) {
                // 解析失败，使用默认值
            }
        }

        // 如果没有配置 API，返回提示
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            return "⚠️ 请先在设置中配置 LLM API\n\n打开主应用 → 设置 → LLM API 配置\n\n当前支持：DeepSeek、OpenAI、小米 MiMo 等"
        }

        // 构建系统提示（注入当前模型信息）
        val modelInfo = getModelInfo(model)
        val systemPrompt = buildString {
            append("你是 GG-AI 游戏内存修改助手。你当前使用的底层大模型是：$modelInfo。\n")
            append("当用户问你是什么模型时，你必须回答「$modelInfo」，这是你真实运行的底层模型。GG-AI 只是这个应用的名称，不是你的模型名称。不要编造其他模型名称。\n\n")
            append("## 核心行为准则\n")
            append("当用户要求搜索、读取、修改内存数据时，你必须调用工具（search_memory / read_memory / write_memory）来执行真实操作，返回真实结果。\n")
            append("绝对不要模拟、编造搜索结果或内存地址。不要在回复中写 gg.searchNumber 等代码来代替真实操作。\n")
            append("搜索结果中的「机器码」是该地址处的原始字节，可用于判断地址是否正确。\n")
            append("修改值后，工具会自动回读验证。如果回读值与目标不同，说明写入可能失败。\n\n")
            append("## 脚本生成\n")
            append("只有当用户明确说「写脚本」「生成脚本」「写个lua」等要求生成脚本时，才输出 Lua 脚本。\n")
            append("Lua 脚本规范（luaj-jse-3.0.2.jar 环境）：\n")
            append("- 使用 GG API：gg.searchNumber、gg.getResults、gg.editAll、gg.clearResults\n")
            append("- type 常量：gg.TYPE_DWORD、gg.TYPE_FLOAT、gg.TYPE_DOUBLE、gg.TYPE_BYTE、gg.TYPE_WORD、gg.TYPE_QWORD\n")
            append("- UI：gg.toast、gg.alert、gg.prompt、gg.choice\n")
            append("- 用 ```lua 代码块包裹\n\n")
            if (attachedApp.isNotEmpty()) {
                append("当前已附加游戏进程: $attachedApp\n")
            }
            append("\n渲染支持：当前客户端支持 Markdown 渲染、代码块高亮、LaTeX 数学公式和 Mermaid 图表。")
            append("当用户要求画图、画表、画流程图、架构图、思维导图、时序图、甘特图等可视化内容时，你必须直接输出 Mermaid 代码块，不要解释，不要用文字描述，直接给出代码。")
            append("用 ```mermaid 代码块包裹，客户端会自动渲染成图表。\n")
            append("⚠️ Mermaid 版本为 8.14.0，必须严格使用该版本兼容语法：\n")
            append("- 流程图用 `graph TD` 或 `graph LR`（不要用 flowchart）\n")
            append("- 支持的图表类型：graph、sequenceDiagram、classDiagram、stateDiagram、gantt、pie\n")
            append("- 不支持：mindmap、timeline、quadrantChart、block-beta、sankey-beta、xychart-beta 等新类型\n")
            append("- 不要使用 `%%{init: ...}%%` 配置指令\n")
            append("- 节点文本中的特殊字符用双引号包裹，如 A[\"(特殊)文本\"]\n")
            append("示例：\n")
            append("```mermaid\ngraph TD\n    A[搜索金币值] --> B[消费金币]\n    B --> C[再次搜索]\n    C --> D[确认地址]\n    D --> E[修改值]\n```\n\n")
            append("LaTeX 公式支持：用 ${'$'}...${'$'} 包裹行内公式，${'$'}${'$'}...${'$'}${'$'} 包裹独立公式。示例：${'$'}E=mc^2${'$'}，${'$'}${'$'}\\int_0^1 x dx = \\frac{1}{2}${'$'}${'$'}\n\n")
            append("回复格式：\n")
            append("- 使用简洁友好的中文\n")
            append("- 操作步骤用编号列出\n")
            append("- 执行结果用 ✅ 或 ❌ 标记\n")
            append("- 地址和数值用代码格式显示\n")
            append("- 用户要求画图时，直接输出 mermaid 代码块，不要多余文字")
        }

        // 构建消息历史（最近 10 条）
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        val recentMessages = chatMessages.takeLast(10)
        for ((sender, msg) in recentMessages) {
            val role = if (sender == "👤 我") "user" else "assistant"
            messages.put(JSONObject().apply {
                put("role", role)
                put("content", msg)
            })
        }

        // 添加当前用户消息
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userInput)
        })

        // 发送 HTTP 请求
        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000

        // 定义工具
        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "search_memory")
                    put("description", "在游戏内存中搜索数值。支持精确搜索、范围搜索、AOB搜索。返回匹配的内存地址列表。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("mode", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索模式：exact(精确)、range(范围)、aob(AOB特征码)")
                                put("enum", JSONArray().apply { put("exact"); put("range"); put("aob") })
                            })
                            put("value", JSONObject().apply {
                                put("type", "string")
                                put("description", "搜索值。exact模式填数值如'750000'；range模式填'最小值,最大值'如'100,200'；aob模式填特征码如'48 8B 05 ?? ??'")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型：dword(整数4字节)、float(浮点)、double(双精度)、byte(1字节)、word(2字节)、qword(8字节)")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("mode"); put("value") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "read_memory")
                    put("description", "从指定内存地址读取值。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply {
                                put("type", "string")
                                put("description", "内存地址，十六进制如'0x12345678'")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("address"); put("type") })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "write_memory")
                    put("description", "向指定内存地址写入值。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("address", JSONObject().apply {
                                put("type", "string")
                                put("description", "内存地址，十六进制如'0x12345678'")
                            })
                            put("value", JSONObject().apply {
                                put("type", "string")
                                put("description", "要写入的值")
                            })
                            put("type", JSONObject().apply {
                                put("type", "string")
                                put("description", "数据类型")
                                put("enum", JSONArray().apply { put("dword"); put("float"); put("double"); put("byte"); put("word"); put("qword") })
                            })
                        })
                        put("required", JSONArray().apply { put("address"); put("value"); put("type") })
                    })
                })
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("tools", tools)
            put("temperature", 0.7)
            put("max_tokens", 2048)
        }

        // 发送请求并获取响应
        val responseText = doHttpPost(url, apiKey, requestBody)
        val responseJson = JSONObject(responseText)
        val choices = responseJson.getJSONArray("choices")
        if (choices.length() == 0) return "❌ 未收到 AI 回复"

        val msg = choices.getJSONObject(0).getJSONObject("message")

        // 检查是否有工具调用
        if (msg.has("tool_calls") && !msg.isNull("tool_calls")) {
            // 将 assistant 消息（含 tool_calls）加入消息列表
            messages.put(msg)

            val toolCalls = msg.getJSONArray("tool_calls")
            for (i in 0 until toolCalls.length()) {
                val toolCall = toolCalls.getJSONObject(i)
                val callId = toolCall.getString("id")
                val func = toolCall.getJSONObject("function")
                val funcName = func.getString("name")
                val funcArgs = JSONObject(func.getString("arguments"))

                val result = executeToolCall(funcName, funcArgs)

                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", callId)
                    put("content", result)
                })
            }

            // 用工具结果再次调用 LLM
            val finalRequestBody = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }
            val finalResponseText = doHttpPost(url, apiKey, finalRequestBody)
            val finalJson = JSONObject(finalResponseText)
            val finalChoices = finalJson.getJSONArray("choices")
            if (finalChoices.length() > 0) {
                return finalChoices.getJSONObject(0).getJSONObject("message").getString("content")
            }
            return "❌ 未收到 AI 回复"
        }

        return msg.optString("content", "❌ 未收到 AI 回复")
    }

    private fun doHttpPost(url: URL, apiKey: String, body: JSONObject): String {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
        writer.write(body.toString())
        writer.flush()
        writer.close()
        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            throw Exception("HTTP $code: $err")
        }
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val text = reader.readText()
        reader.close()
        conn.disconnect()
        return text
    }

    private fun executeToolCall(name: String, args: JSONObject): String {
        return try {
            when (name) {
                "search_memory" -> {
                    val mode = args.optString("mode", "exact")
                    val value = args.optString("value", "")
                    val type = args.optString("type", "dword")
                    if (MemoryEngine.getAttachedPid() == null) return "❌ 未附加游戏进程，请先附加"
                    when (mode) {
                        "exact" -> {
                            val numVal: Any = if (type == "float" || type == "double") value.toDoubleOrNull() ?: 0.0 else value.toLongOrNull() ?: 0
                            val res = MemoryEngine.searchExact(numVal, type)
                            if (res.isEmpty()) return "搜索完成，未找到结果"
                            val sb = StringBuilder("找到 ${res.size} 个结果：\n")
                            for ((idx, r) in res.take(50).withIndex()) {
                                val addr = r["address"] ?: ""
                                val mc = r["machineCode"] ?: ""
                                val v = r["value"] ?: ""
                                sb.append("${idx + 1}. $addr [$mc] = $v\n")
                            }
                            if (res.size > 50) sb.append("... 共 ${res.size} 个结果")
                            sb.append("\n请分析机器码判断哪个地址是目标数据，再使用 write_memory 修改。")
                            sb.toString()
                        }
                        "range" -> {
                            val parts = value.split(",")
                            if (parts.size != 2) return "❌ 范围格式错误，应为 '最小值,最大值'"
                            val lo = parts[0].trim().toLongOrNull() ?: 0
                            val hi = parts[1].trim().toLongOrNull() ?: 0
                            val res = MemoryEngine.searchByRange(lo, hi, type)
                            if (res.isEmpty()) return "搜索完成，未找到结果"
                            val sb = StringBuilder("找到 ${res.size} 个结果：\n")
                            for ((idx, r) in res.take(50).withIndex()) {
                                sb.append("${idx + 1}. 地址: ${r["address"]}, 值: ${r["value"]}\n")
                            }
                            sb.toString()
                        }
                        "aob" -> {
                            val res = MemoryEngine.searchAob(value)
                            if (res.isEmpty()) return "搜索完成，未找到结果"
                            val sb = StringBuilder("找到 ${res.size} 个结果：\n")
                            for ((idx, r) in res.take(50).withIndex()) {
                                sb.append("${idx + 1}. 地址: ${r["address"]}\n")
                            }
                            sb.toString()
                        }
                        else -> "❌ 未知搜索模式: $mode"
                    }
                }
                "read_memory" -> {
                    val address = args.optString("address", "")
                    val type = args.optString("type", "dword")
                    if (MemoryEngine.getAttachedPid() == null) return "❌ 未附加游戏进程"
                    val addrLong = address.removePrefix("0x").toLongOrNull(16) ?: return "❌ 无效地址: $address"
                    val value = MemoryEngine.readMemory(addrLong, type)
                    "地址 $address 的值: $value (类型: $type)"
                }
                "write_memory" -> {
                    val address = args.optString("address", "")
                    val value = args.optString("value", "")
                    val type = args.optString("type", "dword")
                    if (MemoryEngine.getAttachedPid() == null) return "❌ 未附加游戏进程"
                    val addrLong = address.removePrefix("0x").toLongOrNull(16) ?: return "❌ 无效地址: $address"
                    val numVal: Any = if (type == "float" || type == "double") value.toDoubleOrNull() ?: 0.0 else value.toLongOrNull() ?: 0
                    val success = MemoryEngine.writeMemory(addrLong, numVal, type)
                    if (success) {
                        // 写入后验证
                        val readBack = MemoryEngine.readMemory(addrLong, type)
                        "✅ 已写入 $value 到地址 $address，回读验证: $readBack"
                    } else {
                        "❌ 写入失败，可能是地址不可写或权限不足"
                    }
                }
                else -> "❌ 未知工具: $name"
            }
        } catch (e: Exception) {
            "❌ 执行出错: ${e.message}"
        }
    }

    // 流式调用 LLM API
    private fun callLlmApiStream(userInput: String, attachedApp: String, onChunk: (String) -> Unit) {
        val configPrefs = getSharedPreferences("gg_llm_config", Context.MODE_PRIVATE)
        val configJson = configPrefs.getString("config", null)

        var baseUrl = ""
        var apiKey = ""
        var model = "deepseek-chat"

        if (configJson != null) {
            try {
                val json = JSONObject(configJson)
                baseUrl = json.optString("baseUrl", "")
                apiKey = json.optString("apiKey", "")
                model = json.optString("model", "deepseek-chat")
            } catch (_: Exception) {}
        }

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            onChunk("⚠️ 请先在设置中配置 LLM API")
            return
        }

        val modelInfo = getModelInfo(model)
        val systemPrompt = buildString {
            append("你是 GG-AI 游戏内存修改助手。你当前使用的底层大模型是：$modelInfo。\n")
            append("当用户问你是什么模型时，你必须回答「$modelInfo」。GG-AI 只是应用名称，不是模型名称。\n\n")
            append("当用户要求搜索/读取/修改内存时，调用 search_memory/read_memory/write_memory 工具执行真实操作，绝对不要模拟结果。\n")
            append("只有用户明确要求写脚本时才输出 Lua 脚本（luaj-jse-3.0.2.jar），用 ```lua 包裹。\n\n")
            if (attachedApp.isNotEmpty()) append("当前已附加游戏进程: $attachedApp\n")
            append("\n渲染支持：客户端支持 Markdown、LaTeX 公式（${'$'}...${'$'} / ${'$'}${'$'}...${'$'}${'$'}）和 Mermaid 图表。")
            append("用户要求画图时，必须直接输出 ```mermaid 代码块，不要解释。")
            append("Mermaid 版本 8.14.0，只支持 graph/sequenceDiagram/classDiagram/stateDiagram/gantt/pie，不要用 flowchart/mindmap/timeline 等新类型，不要用 %%{init}%% 指令，特殊字符用双引号包裹。")
            append("\n使用简洁友好的中文回复，操作步骤用编号列出。")
        }

        val messages = JSONArray()
        messages.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
        for ((sender, msg) in chatMessages.takeLast(10)) {
            messages.put(JSONObject().apply { put("role", if (sender == "👤 我") "user" else "assistant"); put("content", msg) })
        }
        messages.put(JSONObject().apply { put("role", "user"); put("content", userInput) })

        val url = URL("${baseUrl.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 120000

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", 2048)
            put("stream", true)
        }

        val writer = OutputStreamWriter(conn.outputStream, Charsets.UTF_8)
        writer.write(requestBody.toString())
        writer.flush()
        writer.close()

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: "未知错误"
            throw Exception("HTTP $responseCode: $errorStream")
        }

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line!!.trim()
            if (l.isEmpty() || !l.startsWith("data: ")) continue
            val data = l.substring(6)
            if (data == "[DONE]") break
            try {
                val json = JSONObject(data)
                val delta = json.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
                if (delta.has("content") && !delta.isNull("content")) {
                    val content = delta.getString("content")
                    if (content.isNotEmpty() && content != "null") {
                        onChunk(content)
                    }
                }
            } catch (_: Exception) {}
        }
        reader.close()
        conn.disconnect()
    }

    // 根据模型名称返回公司+版本信息
    private fun getModelInfo(modelName: String): String {
        val m = modelName.lowercase()
        return when {
            m.contains("deepseek-reasoner") || m.contains("deepseek-r1") -> "DeepSeek-R1（深度求索公司，推理增强模型）"
            m.contains("deepseek") -> "DeepSeek-V3（深度求索公司，通用对话模型）"
            m.contains("mimo") -> "MiMo-v2.5-Pro（小米公司，大语言模型）"
            m.contains("gpt-4o") -> "GPT-4o（OpenAI 公司，多模态模型）"
            m.contains("gpt-4") -> "GPT-4（OpenAI 公司，大语言模型）"
            m.contains("gpt-3.5") -> "GPT-3.5-Turbo（OpenAI 公司，大语言模型）"
            m.contains("claude") -> "Claude（Anthropic 公司，大语言模型）"
            m.contains("qwen") || m.contains("tongyi") -> "通义千问（阿里巴巴公司，大语言模型）"
            m.contains("glm") || m.contains("chatglm") -> "ChatGLM（智谱AI公司，大语言模型）"
            else -> modelName
        }
    }

    // ==================== 保存聊天到存储 ====================

    private fun saveChatToStorage() {
        if (chatMessages.isEmpty()) {
            handler.post {
                Toast.makeText(this, "没有聊天记录可保存", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val prefs = getSharedPreferences("gg_overlay_chat", Context.MODE_PRIVATE)
            val editor = prefs.edit()

            // 保存为 JSON 数组
            val jsonArray = JSONArray()
            for ((sender, msg) in chatMessages) {
                jsonArray.put(JSONObject().apply {
                    put("sender", sender)
                    put("message", msg)
                    put("timestamp", System.currentTimeMillis())
                })
            }

            // 使用时间戳作为 key
            val sessionId = "chat_${System.currentTimeMillis()}"
            editor.putString(sessionId, jsonArray.toString())
            editor.putString("latest_session_id", sessionId)
            editor.apply()

            handler.post {
                Toast.makeText(this, "✅ 聊天已保存，可在主应用查看", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            handler.post {
                Toast.makeText(this, "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 脚本库面板 ====================

    private fun showScriptPanel() {
        saveLastPanel("script")
        makeDraggablePanel("脚本库", { content ->
            val status = TextView(this).apply {
                text = "正在加载脚本..."
                setTextColor(Color.parseColor("#FFF3E0"))
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(4))
            }
            content.addView(status)

            val sv = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            sv.addView(list)
            content.addView(sv)

            // 加载脚本的函数
            fun loadScripts() {
                list.removeAllViews()
                status.text = "正在加载脚本..."
                Thread {
                    val scripts = loadScriptsFromStorage()
                    handler.post {
                        status.text = "找到 ${scripts.size} 个脚本"
                        for (script in scripts) {
                            val item = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                setPadding(dp(12), dp(10), dp(12), dp(10))
                                background = GradientDrawable().apply {
                                    cornerRadius = dp(8).toFloat()
                                    setColor(Color.parseColor("#8B4513"))
                                }
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = dp(6) }
                            }
                            val nameText = TextView(this).apply {
                                text = script["name"] ?: "未知脚本"
                                setTextColor(Color.parseColor("#FFF3E0"))
                                textSize = 14f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }
                            item.addView(nameText)

                            val runBtn = smallBtn("▶ 运行") {
                                val scriptContent = script["content"] ?: ""
                                if (scriptContent.isNotEmpty()) {
                                    status.text = "正在运行: ${script["name"]}..."
                                    Thread {
                                        try {
                                            LuaEngine.setContext(this@OverlayService)
                                            val output = LuaEngine.executeScript(scriptContent)
                                            // 自动保存日志
                                            saveScriptLog(script["name"] ?: "脚本", output)
                                            handler.post {
                                                status.text = "✅ ${script["name"]} 执行完成"
                                                Toast.makeText(this@OverlayService, "✅ ${script["name"]} 执行完成，日志已保存", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            handler.post {
                                                status.text = "❌ 执行失败: ${e.message}"
                                            }
                                        }
                                    }.start()
                                }
                            }
                            item.addView(runBtn)
                            list.addView(item)
                        }

                        if (scripts.isEmpty()) {
                            list.addView(TextView(this).apply {
                                text = "暂无脚本\n请在主应用脚本库中创建"
                                setTextColor(Color.parseColor("#A1887F"))
                                textSize = 13f
                                setPadding(dp(12), dp(20), dp(12), dp(8))
                                gravity = Gravity.CENTER
                            })
                        }
                    }
                }.start()
            }

            // 首次加载
            loadScripts()

            // 底部按钮：刷新 + 关闭窗口
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(8)) }
            bar.addView(iconBtn(R.drawable.shuaxing) { loadScripts() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(iconBtn(R.drawable.ck_gb) { closePanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 300, 420, titleIcon = R.drawable.jiaoben, bgColor = "#723d09")
    }

    /**
     * 保存脚本运行日志
     */
    private fun saveScriptLog(scriptName: String, output: String) {
        try {
            val now = java.text.SimpleDateFormat("MMddHHmm", java.util.Locale.getDefault()).format(java.util.Date())
            val timeStr = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

            // 保存到 gg_script_logs SharedPreferences（与 MainActivity 的 getOverlayScriptLogs 对应）
            val prefs = getSharedPreferences("gg_script_logs", Context.MODE_PRIVATE)
            val existing = prefs.getString("logs", "[]") ?: "[]"
            val logsArray = org.json.JSONArray(existing)

            val logEntry = org.json.JSONObject().apply {
                put("name", "${now}_$scriptName")
                put("scriptName", scriptName)
                put("time", timeStr)
                put("output", output)
            }
            logsArray.put(logEntry)

            prefs.edit().putString("logs", logsArray.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * 从存储加载脚本列表
     */
    private fun loadScriptsFromStorage(): List<Map<String, String>> {
        val scripts = mutableListOf<Map<String, String>>()

        try {
            // 从 SharedPreferences 读取（由主应用同步，已包含内置脚本）
            val prefs = getSharedPreferences("gg_scripts", Context.MODE_PRIVATE)
            val scriptsJson = prefs.getString("scripts", "[]") ?: "[]"
            val jsonArray = org.json.JSONArray(scriptsJson)
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                scripts.add(mapOf(
                    "id" to json.optString("id", ""),
                    "name" to json.optString("name", "未知"),
                    "content" to json.optString("content", ""),
                    "description" to json.optString("description", "")
                ))
            }
        } catch (_: Exception) {}

        // 如果没有从主应用同步到脚本，添加内置脚本作为备用
        if (scripts.none { it["id"] == "builtin_test" }) {
            // 添加内置脚本
            scripts.add(mapOf(
            "id" to "builtin_test",
            "name" to "运行测试",
            "content" to """-- 游戏修改器 Lua 测试脚本
function searchData(value, valueType)
    gg.clearResults()
    gg.searchNumber(value, valueType)
    local count = gg.getResultCount()
    gg.toast("搜索完成，找到 " .. count .. " 条结果")
    return count
end
function menu1_search()
    local choice = gg.choice({
        " 搜索整数 9999",
        " 搜索浮点 1.0",
        " 搜索双精度 3.14"
    }, nil, "【数值搜索】请选择搜索类型")
    if choice == nil then
        gg.toast("已取消")
        return
    end
    if choice == 1 then
        searchData(9999, gg.TYPE_DWORD)
    elseif choice == 2 then
        searchData("1.0", gg.TYPE_FLOAT)
    elseif choice == 3 then
        searchData("3.14", gg.TYPE_DOUBLE)
    end
end
function menu2_advanced()
    local choice = gg.choice({
        " 修改搜索结果为 88888",
        " 冻结当前结果",
        " 清除所有结果"
    }, nil, "【高级操作】请选择操作")
    if choice == nil then
        gg.toast("已取消")
        return
    end
    if choice == 1 then
        local count = gg.getResultCount()
        if count > 0 then
            local results = gg.getResults(count)
            for i, v in ipairs(results) do
                results[i].value = 88888
                results[i].flags = gg.TYPE_DWORD
            end
            gg.setValues(results)
            gg.toast("已修改 " .. count .. " 条数据为 88888")
        else
            gg.toast("没有搜索结果")
        end
    elseif choice == 2 then
        local count = gg.getResultCount()
        if count > 0 then
            local results = gg.getResults(count)
            for i, v in ipairs(results) do
                results[i].freeze = true
            end
            gg.addListItems(results)
            gg.toast("已冻结 " .. count .. " 条数据")
        else
            gg.toast("没有搜索结果")
        end
    elseif choice == 3 then
        gg.clearResults()
        gg.clearList()
        gg.toast("已清除所有结果")
    end
end
function mainMenu()
    while true do
        local main = gg.choice({
            " 数值搜索",
            " 高级操作",
            " 退出脚本"
        }, nil, "=== Lua 测试脚本 v1.0 ===")
        if main == nil or main == 3 then
            gg.toast("脚本已退出")
            break
        elseif main == 1 then
            menu1_search()
        elseif main == 2 then
            menu2_advanced()
        end
    end
end
gg.toast("Lua 测试脚本已加载")
gg.sleep(1000)
mainMenu()""",
            "description" to "Lua 菜单弹窗 + 数据搜索测试"
        ))
        }

        return scripts
    }

    /**
     * 显示脚本运行输出对话框
     */

    // ==================== UI 工具 ====================

    private fun parseMemoryValue(text: String, type: String): Any? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return if (type == "float" || type == "double") {
            value.toDoubleOrNull()
        } else {
            when {
                value.startsWith("-0x", ignoreCase = true) ->
                    value.substring(3).toLongOrNull(16)?.let { -it }
                value.startsWith("0x", ignoreCase = true) ->
                    value.substring(2).toLongOrNull(16)
                value.endsWith("h", ignoreCase = true) ->
                    value.dropLast(1).toLongOrNull(16)
                else -> value.toLongOrNull()
            }
        }
    }

    private fun menuBtn(text: String, iconRes: Int? = null, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(5) }
            setOnClickListener { pressAndRun(this) { onClick() } }
            if (iconRes != null) {
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.parseColor("#D0BCFF"))
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(9) }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                })
            }
            addView(TextView(this@OverlayService).apply {
                this.text = text
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#F3EDF7"))
            textSize = 10.5f
            background = aggMenuDrawable(Color.parseColor("#4A4458"), 9, Color.parseColor("#675F72"))
            minHeight = 0
            minWidth = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { pressAndRun(this) { onClick() } }
        }
    }

    private fun iconBtn(iconRes: Int, label: String = "", onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = aggMenuDrawable(Color.parseColor("#4A4458"), 9, Color.parseColor("#675F72"))
            setPadding(dp(5), dp(3), dp(5), dp(3))
            setOnClickListener { pressAndRun(this) { onClick() } }
            addView(ImageView(this@OverlayService).apply {
                setImageResource(iconRes)
                setColorFilter(Color.parseColor("#E8DEF8"))
                layoutParams = LinearLayout.LayoutParams(dp(19), dp(19))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            })
            if (label.isNotEmpty()) {
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(Color.parseColor("#F3EDF7"))
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
            }
        }
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
