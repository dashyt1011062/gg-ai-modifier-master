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
        var isRunning = false
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
    // 搜索输入框的值（保持不丢失）
    private var savedSearchInput = ""
    private var savedFilterInput = ""
    private var savedRangeMin = ""
    private var savedRangeMax = ""
    private var savedScrollY = 0

    private data class SavedMemoryItem(
        val address: Long,
        val type: String,
        val packageName: String,
        val label: String,
        val lastValue: String,
        val freeze: Boolean,
    )

    // AI 对话历史（持久化在内存中，防止切换后消失）
    private val chatMessages = mutableListOf<Pair<String, String>>() // (sender, message)
    private var isAiResponding = false

    // 记住上次打开的面板
    private var lastPanel = ""
    private var activePanel = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        MemoryEngine.setContext(applicationContext)
        LuaEngine.setContext(this)
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val regionCategories = prefs.getStringSet("memory_region_categories", null)
        if (!regionCategories.isNullOrEmpty()) MemoryEngine.setRegionCategories(regionCategories)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createBall()
        lastPanel = prefs.getString("last_panel", "") ?: ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        isRunning = false
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
        ballView = ImageView(this).apply {
            setImageResource(R.drawable.xfc)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4A4458"))
                setStroke(dp(1), Color.parseColor("#81778F"))
            }
            elevation = dp(5).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        ballParams = WindowManager.LayoutParams(
            dp(40),
            dp(40),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { 
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(200)
        }

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var dragging = false
        ballView?.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = ballParams?.x ?: 0; iy = ballParams?.y ?: 0; tx = e.rawX; ty = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - tx) > dp(4) || kotlin.math.abs(e.rawY - ty) > dp(4)) dragging = true
                    val maxX = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - dp(40)).coerceAtLeast(0)
                    ballParams?.x = (ix + (e.rawX - tx).toInt()).coerceIn(0, maxX)
                    ballParams?.y = (iy + (e.rawY - ty).toInt()).coerceIn(0, maxY)
                    try { wm?.updateViewLayout(ballView, ballParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        showMainMenu()
                    } else {
                        val maxX = (resources.displayMetrics.widthPixels - dp(40)).coerceAtLeast(0)
                        ballParams?.x = if ((ballParams?.x ?: 0) < maxX / 2) 0 else maxX
                        try { wm?.updateViewLayout(ballView, ballParams) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }
        try { wm?.addView(ballView, ballParams) } catch (_: Exception) {}
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

    private fun makeDraggablePanel(title: String, contentBuilder: (LinearLayout) -> Unit, w: Int = 280, h: Int = 400, onBack: (() -> Unit)? = null, titleIcon: Int? = null, bgColor: String = "#FDFBF7") {
        val dm = resources.displayMetrics
        val panelW = dp(w).coerceAtMost((dm.widthPixels - dp(16)).coerceAtLeast(1))
        val panelH = dp(h).coerceAtMost((dm.heightPixels - dp(20)).coerceAtLeast(1))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = aggMenuDrawable(
                Color.parseColor("#211F26"),
                16,
                Color.parseColor("#4A4458")
            )
            elevation = dp(12).toFloat()
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(15).toFloat(), dp(15).toFloat(),
                    dp(15).toFloat(), dp(15).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#2B2930"))
            }
        }

        if (titleIcon != null) {
            titleBar.addView(ImageView(this).apply {
                setImageResource(titleIcon)
                setColorFilter(Color.parseColor("#E9E1F2"))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(4) })
        }

        titleBar.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F5EFFA"))
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        })

        fun topIcon(res: Int, tint: String, action: () -> Unit): ImageView {
            return ImageView(this).apply {
                setImageResource(res)
                setColorFilter(Color.parseColor(tint))
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = aggMenuDrawable(Color.TRANSPARENT, 8, Color.TRANSPARENT)
                setOnClickListener { pressAndRun(this) { action() } }
            }
        }

        titleBar.addView(topIcon(R.drawable.ic_agg_back, "#DDD5E7") { onBack?.invoke() ?: showMainMenu() }, LinearLayout.LayoutParams(dp(30), dp(30)))
        titleBar.addView(topIcon(R.drawable.ic_agg_minimize, "#DDD5E7") { closePanel() }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(4) })
        titleBar.addView(topIcon(R.drawable.ic_agg_close, "#FFB4AB") { stopSelf() }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(4) })
        root.addView(titleBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))

        val contentArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = aggMenuDrawable(Color.parseColor("#17151B"), 12, Color.parseColor("#302C37"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
        }
        contentBuilder(contentArea)
        root.addView(contentArea)

        root.alpha = 0f
        root.scaleX = 0.98f
        root.scaleY = 0.98f
        showFocusablePanel(root, w, h)
        activePanel = lastPanel
        panelParams?.let { enableCompactPanelDrag(titleBar, it, panelW, panelH) }
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start()
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

    // ==================== 主菜单 ====================

    private fun showLastOrMainMenu() {
        when (lastPanel) {
            "process" -> showProcessPanel()
            "search" -> showSearchPanel()
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

    private fun showMainMenu() {
        saveLastPanel("menu")
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val isLandscape = screenW > screenH
        val panelW = dp(if (isLandscape) 700 else 380).coerceAtMost((screenW - dp(16)).coerceAtLeast(1))
        val panelH = dp(if (isLandscape) 420 else 570).coerceAtMost((screenH - dp(20)).coerceAtLeast(1))
        val attachedPid = MemoryEngine.getAttachedPid()
        val pid = attachedPid?.takeIf { MemoryEngine.isAttachedProcessAlive() }
        if (attachedPid != null && pid == null) {
            MemoryEngine.detachProcess()
            clearAttachedProcessInfo()
        }
        val prefs = getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        val processName = prefs.getString("attached_name", null)?.takeIf { it.isNotBlank() }
        val packageName = prefs.getString("attached_package", null)?.takeIf { it.isNotBlank() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(1), dp(1), dp(1))
            background = aggMenuDrawable(Color.parseColor("#211F26"), 18, Color.parseColor("#4A4458"))
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dp(17).toFloat(), dp(17).toFloat(),
                    dp(17).toFloat(), dp(17).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#2B2930"))
            }
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.xfc)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = aggMenuDrawable(Color.parseColor("#4A4458"), 10, Color.parseColor("#675F72"))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@OverlayService).apply {
                text = "GG-AI"
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                letterSpacing = 0.04f
            })
            addView(TextView(this@OverlayService).apply {
                text = if (pid != null) "已连接 · PID $pid" else "未选择进程"
                setTextColor(if (pid != null) Color.parseColor("#C8F7DC") else Color.parseColor("#CAC4D0"))
                textSize = 9.5f
            })
        })
        fun headerIcon(res: Int, tint: String, action: () -> Unit): ImageView {
            return ImageView(this).apply {
                setImageResource(res)
                setColorFilter(Color.parseColor(tint))
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = aggMenuDrawable(Color.TRANSPARENT, 8, Color.TRANSPARENT)
                setOnClickListener { pressAndRun(this) { action() } }
            }
        }
        header.addView(headerIcon(R.drawable.ic_agg_minimize, "#E6E0E9") { closePanel() }, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(headerIcon(R.drawable.ic_agg_close, "#FFB4AB") { stopSelf() }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(4) })
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        val body = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(body)

        fun navigationItem(iconRes: Int, label: String, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = aggMenuDrawable(Color.parseColor("#2B2930"), 9, Color.parseColor("#3A3641"))
                setOnClickListener { pressAndRun(this) { action() } }
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.parseColor("#D0BCFF"))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { if (!isLandscape) marginEnd = dp(5) })
                addView(TextView(this@OverlayService).apply {
                    text = label
                    setTextColor(Color.parseColor("#E6E0E9"))
                    textSize = if (isLandscape) 8.5f else 9.5f
                    gravity = Gravity.CENTER
                    if (isLandscape) setPadding(0, dp(2), 0, 0)
                })
            }
        }

        val navigation = LinearLayout(this).apply {
            orientation = if (isLandscape) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = aggMenuDrawable(Color.parseColor("#17151B"), 11, Color.parseColor("#302C37"))
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) }
            } else {
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(6) }
            }
        }
        val navLp = if (isLandscape) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(3) }
        } else {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(3) }
        }
        navigation.addView(navigationItem(R.drawable.ic_agg_apps, "进程") { showProcessPanel() }, navLp)
        navigation.addView(navigationItem(R.drawable.ic_agg_memory, "搜索") { showSearchPanel() }, LinearLayout.LayoutParams(navLp))
        navigation.addView(navigationItem(R.drawable.ic_agg_lock, "保存") { showSavedListPanel() }, LinearLayout.LayoutParams(navLp))
        navigation.addView(navigationItem(R.drawable.ic_agg_ai, "AI") { showAIChatPanel() }, LinearLayout.LayoutParams(navLp))
        navigation.addView(navigationItem(R.drawable.ic_agg_script, "脚本") { showScriptPanel() }, LinearLayout.LayoutParams(navLp).apply { marginEnd = 0; bottomMargin = 0 })
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

        val processCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            background = aggMenuDrawable(Color.parseColor("#25222B"), 12, if (pid != null) Color.parseColor("#4D705E") else Color.parseColor("#49454F"))
            setOnClickListener { if (pid != null) showSearchPanel() else showProcessPanel() }
        }
        processCard.addView(ImageView(this).apply {
            setImageResource(if (pid != null) R.drawable.ic_agg_memory else R.drawable.ic_agg_apps)
            setColorFilter(if (pid != null) Color.parseColor("#C8F7DC") else Color.parseColor("#D0BCFF"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = aggMenuDrawable(Color.parseColor("#34313A"), 10, Color.parseColor("#49454F"))
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(9) })
        processCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@OverlayService).apply {
                text = if (pid != null) (processName ?: "目标进程") else "选择运行中的应用"
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(Color.parseColor("#F3EDF7"))
                textSize = 12.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@OverlayService).apply {
                text = if (pid != null) "${packageName ?: "目标进程"} · PID $pid" else "附加后即可搜索、修改和冻结内存"
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 9.5f
                setPadding(0, dp(2), 0, 0)
            })
        })
        processCard.addView(TextView(this).apply {
            text = if (pid != null) "进入搜索" else "选择进程"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#231A2E"))
            textSize = 9.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = aggMenuDrawable(Color.parseColor("#D0BCFF"), 8, Color.parseColor("#E8DEF8"))
        }, LinearLayout.LayoutParams(dp(72), dp(34)).apply { marginStart = dp(7) })
        workspace.addView(processCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)))

        workspace.addView(TextView(this).apply {
            text = "快捷工具"
            setTextColor(Color.parseColor("#CAC4D0"))
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(3), dp(7), dp(3), dp(5))
        })

        fun toolCard(iconRes: Int, title: String, subtitle: String, tint: String, action: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#3A3641"))
                setOnClickListener { pressAndRun(this) { action() } }
                addView(ImageView(this@OverlayService).apply {
                    setImageResource(iconRes)
                    setColorFilter(Color.parseColor(tint))
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = aggMenuDrawable(Color.parseColor("#34313A"), 9, Color.parseColor("#49454F"))
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) })
                addView(LinearLayout(this@OverlayService).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@OverlayService).apply {
                        text = title
                        setTextColor(Color.parseColor("#F3EDF7"))
                        textSize = 11.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    addView(TextView(this@OverlayService).apply {
                        text = subtitle
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 8.8f
                        maxLines = 1
                    })
                })
                addView(TextView(this@OverlayService).apply {
                    text = "›"
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 18f
                }, LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.MATCH_PARENT))
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        row1.addView(toolCard(R.drawable.ic_agg_apps, "进程列表", "查看并切换目标进程", "#D0BCFF") { showProcessPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(3) })
        row1.addView(toolCard(R.drawable.ic_agg_memory, "内存搜索", "精确、模糊、范围和特征码", "#A9C7FF") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(3) })
        workspace.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        row2.addView(toolCard(R.drawable.ic_agg_ai, "AI 助手", "分析结果并生成操作建议", "#FFD8A8") { showAIChatPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(3) })
        row2.addView(toolCard(R.drawable.ic_agg_script, "脚本管理", "运行和管理 Lua 自动化脚本", "#C8F7DC") { showScriptPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(3) })
        workspace.addView(row2)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        footer.addView(TextView(this).apply {
            text = "打开主界面"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E6E0E9"))
            textSize = 10f
            background = aggMenuDrawable(Color.parseColor("#302D35"), 9, Color.parseColor("#49454F"))
            setOnClickListener {
                try {
                    startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(3) })
        footer.addView(TextView(this).apply {
            text = "退出 GG-AI"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFB4AB"))
            textSize = 10f
            background = aggMenuDrawable(Color.parseColor("#35232A"), 9, Color.parseColor("#68404A"))
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginStart = dp(3) })
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
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenW - panelW) / 2).coerceAtLeast(0)
            y = ((screenH - panelH) / 2).coerceAtLeast(0)
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
            enableCompactPanelDrag(header, params, panelW, panelH)
            panel?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
            oldPanel?.let { old -> handler.postDelayed({ try { wm?.removeView(old) } catch (_: Exception) {} }, 24L) }
        } catch (_: Exception) {
            oldPanel?.let { old -> try { wm?.removeView(old) } catch (_: Exception) {} }
        }
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
        navigation.addView(aggNavButton("02", "搜索", isLandscape, Color.parseColor("#45C8FF")) { showSearchPanel() }, copyLayoutParams(navParams))
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
        firstRow.addView(aggToolCard("内存搜索", "精确 / 模糊 / 范围", "SCAN", Color.parseColor("#45C8FF")) { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(6) })
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
        panelH: Int
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
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 进程面板 ====================

    private fun showProcessPanel() {
        saveLastPanel("process")
        makeDraggablePanel("选择进程", { content ->
            val attachedPid = MemoryEngine.getAttachedPid()
            val status = TextView(this).apply {
                text = if (attachedPid != null) "当前进程  PID $attachedPid" else "正在扫描运行中的应用…"
                setTextColor(if (attachedPid != null) Color.parseColor("#C8F7DC") else Color.parseColor("#CAC4D0"))
                textSize = 11f
                setPadding(dp(8), dp(4), dp(8), dp(6))
            }
            content.addView(status)

            val searchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), dp(5))
            }
            val queryInput = EditText(this).apply {
                hint = "搜索应用名或包名"
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 12f
                setSingleLine(true)
                setPadding(dp(12), 0, dp(10), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            }
            searchRow.addView(queryInput)

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            val searchButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_agg_search)
                setColorFilter(Color.parseColor("#E8DEF8"))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = aggMenuDrawable(Color.parseColor("#4A4458"), 10, Color.parseColor("#675F72"))
                setOnClickListener { loadProcs(list, status, queryInput.text.toString()) }
            }
            searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
            val refreshButton = ImageView(this).apply {
                setImageResource(R.drawable.ic_agg_refresh)
                setColorFilter(Color.parseColor("#E8DEF8"))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = aggMenuDrawable(Color.parseColor("#34313A"), 10, Color.parseColor("#49454F"))
                setOnClickListener { loadProcs(list, status, queryInput.text.toString()) }
            }
            searchRow.addView(refreshButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(5) })
            content.addView(searchRow)

            val scroll = ScrollView(this).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            scroll.addView(list)
            content.addView(scroll)

            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(5), dp(4), dp(1))
            }
            footer.addView(TextView(this).apply {
                text = "分离当前进程"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FFB4AB"))
                textSize = 11f
                background = aggMenuDrawable(Color.parseColor("#35232A"), 9, Color.parseColor("#68404A"))
                setOnClickListener {
                    MemoryEngine.detachProcess()
                    clearAttachedProcessInfo()
                    searchResults = emptyList()
                    selectedIndices.clear()
                    status.text = "已分离进程"
                    loadProcs(list, status, queryInput.text.toString())
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4) })
            footer.addView(TextView(this).apply {
                text = "进入搜索"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#231A2E"))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = aggMenuDrawable(Color.parseColor("#D0BCFF"), 9, Color.parseColor("#E8DEF8"))
                setOnClickListener {
                    if (MemoryEngine.getAttachedPid() != null) showSearchPanel()
                    else status.text = "请先选择一个运行中的应用"
                }
            }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) })
            content.addView(footer)

            loadProcs(list, status)
        }, 360, 520, titleIcon = R.drawable.ic_agg_apps)
    }

    private fun loadProcs(list: LinearLayout, status: TextView, query: String = "") {
        status.text = "正在扫描运行中的应用…"
        list.removeAllViews()
        Thread {
            val keyword = query.trim().lowercase()
            val procs = ProcessManager.getProcessList(this@OverlayService).filter {
                val pkg = it["packageName"] as String
                val name = it["processName"] as String
                pkg.isNotEmpty() && pkg.contains(".") &&
                        !pkg.startsWith("com.android.") && !pkg.startsWith("android.") &&
                        pkg != "system" && pkg != "zygote" && pkg != "zygote64" &&
                        (keyword.isEmpty() || pkg.lowercase().contains(keyword) || name.lowercase().contains(keyword))
            }
            handler.post {
                val currentPid = MemoryEngine.getAttachedPid()
                status.text = if (currentPid != null) {
                    "${procs.size} 个应用  ·  当前 PID $currentPid"
                } else {
                    "找到 ${procs.size} 个运行中的应用"
                }
                status.setTextColor(if (currentPid != null) Color.parseColor("#C8F7DC") else Color.parseColor("#CAC4D0"))

                if (procs.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "没有找到匹配的运行进程"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 12f
                        setPadding(dp(8), dp(34), dp(8), dp(34))
                    })
                    return@post
                }

                for (proc in procs) {
                    val name = proc["processName"] as String
                    val pkg = proc["packageName"] as String
                    val pid = proc["pid"] as Int
                    val isAttached = currentPid == pid

                    val item = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(5), dp(7), dp(5))
                        background = aggMenuDrawable(
                            if (isAttached) Color.parseColor("#3B3346") else Color.parseColor("#242128"),
                            8,
                            if (isAttached) Color.parseColor("#B69DF8") else Color.parseColor("#343039")
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(54)
                        ).apply { bottomMargin = dp(4) }
                    }

                    val icon = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = aggMenuDrawable(Color.parseColor("#34313A"), 9, Color.parseColor("#49454F"))
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        try {
                            setImageDrawable(packageManager.getApplicationIcon(pkg))
                        } catch (_: Exception) {
                            setImageResource(R.drawable.ic_agg_apps)
                            setColorFilter(Color.parseColor("#D0BCFF"))
                        }
                    }
                    item.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(9) })

                    item.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        addView(TextView(this@OverlayService).apply {
                            text = name
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(Color.parseColor("#F3EDF7"))
                            textSize = 12.5f
                            setTypeface(null, if (isAttached) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        })
                        addView(TextView(this@OverlayService).apply {
                            text = pkg
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                            setTextColor(Color.parseColor("#CAC4D0"))
                            textSize = 9.5f
                            setPadding(0, dp(2), 0, 0)
                        })
                    })

                    item.addView(TextView(this).apply {
                        text = if (isAttached) "已附加\n$pid" else "PID\n$pid"
                        gravity = Gravity.CENTER
                        setTextColor(if (isAttached) Color.parseColor("#C8F7DC") else Color.parseColor("#CAC4D0"))
                        textSize = 9.5f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        background = aggMenuDrawable(
                            if (isAttached) Color.parseColor("#214B39") else Color.parseColor("#302D35"),
                            8,
                            if (isAttached) Color.parseColor("#3B7258") else Color.parseColor("#49454F")
                        )
                    }, LinearLayout.LayoutParams(dp(56), dp(38)).apply { marginStart = dp(7) })

                    item.setOnClickListener {
                        if (MemoryEngine.getAttachedPid() == pid) {
                            showSearchPanel()
                            return@setOnClickListener
                        }
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
                                    status.text = "已附加 $name  ·  PID $pid"
                                    status.setTextColor(Color.parseColor("#C8F7DC"))
                                    showSearchPanel()
                                } else {
                                    clearAttachedProcessInfo()
                                    status.text = "附加失败，请检查 Root 权限和进程状态"
                                    status.setTextColor(Color.parseColor("#FFB4AB"))
                                }
                            }
                        }.start()
                    }
                    list.addView(item)
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
            })
        }
        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
            .putString("saved_memory_items", array.toString())
            .apply()
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
                            if (canOperate) showWriteDialog(addressText, liveValue, dataType = item.type)
                            else Toast.makeText(this@OverlayService, "请先附加对应进程", Toast.LENGTH_SHORT).show()
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
                                text = "$addressText  ·  ${item.type.uppercase()}"
                                setTextColor(Color.parseColor("#938F99"))
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
                    operations.addView(itemAction(R.drawable.ic_agg_lock, if (isFrozen) "解冻" else "冻结", if (isFrozen) "#FFB4AB" else "#C8F7DC") {
                        if (!canOperate) {
                            Toast.makeText(this@OverlayService, "请先附加对应进程", Toast.LENGTH_SHORT).show()
                            return@itemAction
                        }
                        Thread {
                            val success = if (isFrozen) {
                                MemoryFreezer.unfreeze(item.address)
                            } else {
                                val value = MemoryEngine.readMemory(item.address, item.type)
                                    ?: parseMemoryValue(item.lastValue, item.type)
                                value != null && MemoryFreezer.freeze(item.address, value, item.type)
                            }
                            if (success) replaceItem(item, item.copy(freeze = !isFrozen, lastValue = liveValue))
                            handler.post {
                                Toast.makeText(
                                    this@OverlayService,
                                    if (success) (if (isFrozen) "已解冻" else "已冻结") else "操作失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
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
                        if (value != null && MemoryFreezer.freeze(item.address, value, item.type)) count++
                    }
                    handler.post {
                        Toast.makeText(this@OverlayService, "已恢复冻结 $count/${items.size} 条", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(savedButton("返回搜索", true) { showSearchPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(actions)

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

    private fun showSearchPanel() {
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
                        showSearchPanel()
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
                updateSearchResults(resultList, searchResults, actionBarContainer)
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
                showSearchPanel()
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
        MemoryEngine.resetSearchState()
    }

    private fun showRegionPanel() {
        saveLastPanel("search")
        makeDraggablePanel("内存范围", { content ->
            val selected = MemoryEngine.getSelectedRegionCategories().toMutableSet()
            var summaryCache: List<Map<String, Any>> = emptyList()

            content.addView(TextView(this).apply {
                text = "选择参与扫描的内存区域。减少区域可以显著提升首次搜索速度，并降低无关结果数量。"
                setTextColor(Color.parseColor("#CAC4D0"))
                textSize = 10f
                setPadding(dp(6), dp(2), dp(6), dp(7))
            })

            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(list)
            }
            content.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

            fun readableSize(bytes: Long): String {
                return when {
                    bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1073741824.0)
                    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1048576.0)
                    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
                    else -> "$bytes B"
                }
            }

            fun renderSummary() {
                list.removeAllViews()
                if (summaryCache.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "正在读取 /proc 内存映射…"
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor("#938F99"))
                        textSize = 11f
                        setPadding(dp(8), dp(36), dp(8), dp(36))
                    })
                    return
                }

                for (item in summaryCache) {
                    val id = item["id"] as String
                    val label = item["label"] as String
                    val count = (item["count"] as Number).toInt()
                    val size = (item["size"] as Number).toLong()
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(7), dp(6), dp(7), dp(6))
                        background = aggMenuDrawable(
                            if (id in selected) Color.parseColor("#332B3D") else Color.parseColor("#25222B"),
                            9,
                            if (id in selected) Color.parseColor("#B69DF8") else Color.parseColor("#3A3641"),
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(54),
                        ).apply { bottomMargin = dp(4) }
                    }
                    val check = android.widget.CheckBox(this).apply {
                        isChecked = id in selected
                        buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B69DF8"))
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(id) else selected.remove(id)
                        }
                    }
                    row.addView(check, LinearLayout.LayoutParams(dp(42), dp(42)))
                    row.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(this@OverlayService).apply {
                            text = label
                            setTextColor(Color.parseColor("#F3EDF7"))
                            textSize = 11.5f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        })
                        addView(TextView(this@OverlayService).apply {
                            text = "$count 个区域  ·  ${readableSize(size)}"
                            setTextColor(Color.parseColor("#938F99"))
                            textSize = 9.5f
                            setPadding(0, dp(2), 0, 0)
                        })
                    })
                    row.setOnClickListener { check.isChecked = !check.isChecked }
                    list.addView(row)
                }
            }

            renderSummary()
            Thread {
                val summary = MemoryEngine.getRegionCategorySummary()
                handler.post {
                    summaryCache = summary
                    renderSummary()
                }
            }.start()

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            fun regionButton(label: String, accent: Boolean = false, action: () -> Unit): TextView {
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
            actions.addView(regionButton("常用范围") {
                selected.clear()
                selected.addAll(listOf("anonymous", "heap", "java", "app"))
                renderSummary()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            actions.addView(regionButton("全部") {
                selected.clear()
                selected.addAll(summaryCache.map { it["id"] as String })
                renderSummary()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(regionButton("应用", true) {
                if (selected.isEmpty()) {
                    Toast.makeText(this@OverlayService, "至少选择一个内存范围", Toast.LENGTH_SHORT).show()
                    return@regionButton
                }
                Thread {
                    val ok = MemoryEngine.setRegionCategories(selected)
                    if (ok) {
                        getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
                            .putStringSet("memory_region_categories", selected.toSet())
                            .apply()
                        resetSearchSession()
                    }
                    handler.post {
                        Toast.makeText(
                            this@OverlayService,
                            if (ok) "已应用 ${selected.size} 类内存范围" else "范围应用失败",
                            Toast.LENGTH_SHORT,
                        ).show()
                        if (ok) showSearchPanel()
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 370, 520, onBack = { showSearchPanel() }, titleIcon = R.drawable.ic_agg_memory)
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
                        setOnClickListener { currentSearchMode = mode; showSearchPanel() }
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
                        setOnClickListener { currentSearchMode = mode; showSearchPanel() }
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
            updateSearchResults(rl, searchResults, abc)
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
    ) {
        val address = addr.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        if (address == null) {
            Toast.makeText(this, "地址格式不正确", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedType = dataType.takeIf { MemoryEngine.isSupportedType(it) } ?: "dword"
        val wasFrozen = MemoryFreezer.isFrozen(address)

        makeDraggablePanel("编辑内存", { content ->
            val summary = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(8), dp(9), dp(8))
                background = aggMenuDrawable(Color.parseColor("#25222B"), 10, Color.parseColor("#49454F"))
            }
            summary.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@OverlayService).apply {
                    text = addr
                    setTextColor(Color.parseColor("#F3EDF7"))
                    textSize = 12f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@OverlayService).apply {
                    text = normalizedType.uppercase()
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#D0BCFF"))
                    textSize = 8.5f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = aggMenuDrawable(Color.parseColor("#3A3345"), 7, Color.parseColor("#5C526A"))
                }, LinearLayout.LayoutParams(dp(58), dp(26)))
            })
            summary.addView(TextView(this).apply {
                text = "当前值  $curVal${if (wasFrozen) "  ·  已冻结" else ""}"
                setTextColor(if (wasFrozen) Color.parseColor("#C8F7DC") else Color.parseColor("#CAC4D0"))
                textSize = 10f
                setPadding(0, dp(4), 0, 0)
            })
            if (machineCode.isNotBlank()) {
                summary.addView(TextView(this).apply {
                    text = "机器码  ${machineCode.take(96)}"
                    setTextColor(Color.parseColor("#938F99"))
                    textSize = 9f
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 2
                    setPadding(0, dp(3), 0, 0)
                })
            }
            content.addView(summary)

            val input = EditText(this).apply {
                hint = "输入新值"
                setText(curVal?.toString() ?: "")
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 13f
                setSingleLine(true)
                setSelectAllOnFocus(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setPadding(dp(12), 0, dp(12), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(7) })

            val state = TextView(this).apply {
                text = if (wasFrozen) "写入后会同步更新冻结值" else "修改后将重新读取内存确认结果"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 9.5f
                setPadding(dp(3), dp(5), dp(3), dp(3))
            }
            content.addView(state)

            fun updateResult(value: Any, frozen: Boolean) {
                searchResults = searchResults.map { item ->
                    val itemAddress = (item["addressInt"] as? Number)?.toLong()
                    if (itemAddress == address) {
                        item.toMutableMap().apply {
                            this["value"] = value
                            this["type"] = normalizedType
                            this["isFrozen"] = frozen
                        }
                    } else item
                }
            }

            fun writeValue(freezeAfter: Boolean) {
                val parsed = parseMemoryValue(input.text.toString(), normalizedType)
                if (parsed == null) {
                    state.text = "数值格式不正确"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return
                }
                state.text = if (freezeAfter) "正在写入并冻结…" else "正在写入…"
                state.setTextColor(Color.parseColor("#D0BCFF"))
                Thread {
                    var success = MemoryEngine.writeMemory(address, parsed, normalizedType)
                    val keepFrozen = freezeAfter || wasFrozen
                    if (success && keepFrozen) success = MemoryFreezer.freeze(address, parsed, normalizedType)
                    val readBack = if (success) MemoryEngine.readMemory(address, normalizedType) ?: parsed else parsed
                    if (success) updateResult(readBack, MemoryFreezer.isFrozen(address))
                    handler.post {
                        if (success) {
                            Toast.makeText(this@OverlayService, "已写入 $addr = $readBack", Toast.LENGTH_SHORT).show()
                            showSearchPanel()
                        } else {
                            state.text = "写入失败，请检查进程和地址状态"
                            state.setTextColor(Color.parseColor("#FFB4AB"))
                        }
                    }
                }.start()
            }

            fun dialogButton(label: String, accent: Boolean = false, danger: Boolean = false, action: () -> Unit): TextView {
                return TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 10f
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
                        }
                    )
                    setOnClickListener { action() }
                }
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            actions.addView(dialogButton("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            actions.addView(dialogButton("写入", true) { writeValue(false) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(
                if (wasFrozen) {
                    dialogButton("解除冻结", danger = true) {
                        if (MemoryFreezer.unfreeze(address)) {
                            updateResult(curVal ?: 0, false)
                            Toast.makeText(this@OverlayService, "已解除冻结", Toast.LENGTH_SHORT).show()
                            showSearchPanel()
                        }
                    }
                } else {
                    dialogButton("写入并冻结") { writeValue(true) }
                },
                LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) }
            )
            content.addView(actions)
        }, 360, 320, onBack = { showSearchPanel() }, titleIcon = R.drawable.ic_agg_edit)
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
            bar.addView(smallBtn("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
                            showSearchPanel()
                        } else {
                            Toast.makeText(this@OverlayService, "❌ 修改失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(bar)
        }, 280, 280, onBack = { showSearchPanel() })
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
                hint = "输入统一的新值"
                setTextColor(Color.parseColor("#F3EDF7"))
                setHintTextColor(Color.parseColor("#938F99"))
                textSize = 13f
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                setPadding(dp(12), 0, dp(12), 0)
                background = aggMenuDrawable(Color.parseColor("#25222B"), 9, Color.parseColor("#49454F"))
            }
            content.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(7) })

            val state = TextView(this).apply {
                text = "每条结果会按自己的数据类型解析并写入"
                setTextColor(Color.parseColor("#938F99"))
                textSize = 9.5f
                setPadding(dp(3), dp(5), dp(3), dp(3))
            }
            content.addView(state)

            fun applyBatch(freezeAfter: Boolean) {
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) {
                    state.text = "请输入新值"
                    state.setTextColor(Color.parseColor("#FFB4AB"))
                    return
                }
                state.text = if (freezeAfter) "正在批量写入并冻结…" else "正在批量写入…"
                state.setTextColor(Color.parseColor("#D0BCFF"))

                Thread {
                    var successCount = 0
                    val updated = mutableMapOf<Long, Pair<Any, String>>()
                    for (item in selectedResults) {
                        val address = (item["addressInt"] as? Number)?.toLong()
                            ?: (item["address"] as? String)
                                ?.removePrefix("0x")
                                ?.removePrefix("0X")
                                ?.toLongOrNull(16)
                            ?: continue
                        val type = (item["type"] as? String)
                            ?.takeIf { MemoryEngine.isSupportedType(it) }
                            ?: searchDataType
                        val parsed = parseMemoryValue(raw, type) ?: continue
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
                        showSearchPanel()
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
            actions.addView(batchButton("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(3) })
            actions.addView(batchButton("批量写入", true) { applyBatch(false) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            actions.addView(batchButton("写入并冻结") { applyBatch(true) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3) })
            content.addView(actions)
        }, 380, 390, onBack = { showSearchPanel() }, titleIcon = R.drawable.ic_agg_edit)
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
            bar.addView(smallBtn("取消") { showSearchPanel() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
                        showSearchPanel()
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
