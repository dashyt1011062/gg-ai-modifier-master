package com.yl.aigg.ai_gg666

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * LuaJ GG API 桥接层
 * 在 JVM 中执行 Lua 脚本，提供与 GG 修改器兼容的交互式 API
 */
object LuaEngine {

    private var context: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var searchResults = mutableListOf<Map<String, Any>>()
    private val savedList = mutableListOf<MutableMap<String, Any?>>()
    private val outputLog = StringBuilder()

    fun setContext(ctx: Context?) {
        context = ctx
    }

    fun setActivity(act: android.app.Activity?) {
        context = act
    }

    fun executeScript(scriptContent: String): String {
        outputLog.clear()
        searchResults.clear()

        try {
            val globals = JsePlatform.standardGlobals()
            val gg = LuaTable()
            registerGgApi(gg)
            globals.set("gg", gg)
            val agg = LuaTable()
            registerAggApi(agg)
            globals.set("agg", agg)
            globals.set("AGG", agg)
            val chunk = globals.load(scriptContent)
            chunk.call()
            return outputLog.toString()
        } catch (e: Exception) {
            val errorMsg = "Lua 执行错误: ${e.message}"
            outputLog.appendLine(errorMsg)
            return outputLog.toString()
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun showDialog(dialog: AlertDialog) {
        val ctx = context
        if (ctx != null && ctx !is android.app.Activity) {
            dialog.window?.setType(getOverlayType())
        }
        dialog.show()
    }

    private fun showChoiceDialog(title: String, items: List<String>): Int {
        val latch = CountDownLatch(1)
        val selectedIndex = AtomicInteger(-1)
        val ctx = context ?: return -1

        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        selectedIndex.set(which + 1)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setNegativeButton("取消") { _, _ ->
                        selectedIndex.set(-1)
                        latch.countDown()
                    }
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                outputLog.appendLine("⚠️ 对话框显示失败: ${e.message}")
                selectedIndex.set(-1)
                latch.countDown()
            }
        }

        latch.await()
        return selectedIndex.get()
    }

    private fun showMultiChoiceDialog(title: String, items: List<String>, initial: BooleanArray): BooleanArray? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<BooleanArray?>(null)
        val ctx = context ?: return null
        val checked = BooleanArray(items.size) { index -> initial.getOrElse(index) { false } }
        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, value ->
                        if (which in checked.indices) checked[which] = value
                    }
                    .setPositiveButton("确定") { _, _ ->
                        result.set(checked.copyOf())
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        result.set(null)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                outputLog.appendLine("⚠️ 多选框显示失败: ${e.message}")
                result.set(null)
                latch.countDown()
            }
        }
        latch.await()
        return result.get()
    }

    private fun showInputDialog(title: String, defaultValue: String): String {
        val latch = CountDownLatch(1)
        val inputResult = AtomicReference(defaultValue)
        val ctx = context ?: return defaultValue

        mainHandler.post {
            try {
                val editText = android.widget.EditText(ctx).apply {
                    setText(defaultValue)
                    setPadding(50, 30, 50, 30)
                }
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setView(editText)
                    .setPositiveButton("确定") { _, _ ->
                        inputResult.set(editText.text.toString())
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        inputResult.set(defaultValue)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                outputLog.appendLine("⚠️ 输入框显示失败: ${e.message}")
                inputResult.set(defaultValue)
                latch.countDown()
            }
        }

        latch.await()
        return inputResult.get()
    }

    private fun showConfirmDialog(title: String, message: String): Boolean {
        val latch = CountDownLatch(1)
        val confirmed = AtomicInteger(0)
        val ctx = context ?: return false

        mainHandler.post {
            try {
                val dialog = AlertDialog.Builder(ctx)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ ->
                        confirmed.set(1)
                        latch.countDown()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        confirmed.set(0)
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .create()
                showDialog(dialog)
            } catch (e: Exception) {
                confirmed.set(0)
                latch.countDown()
            }
        }

        latch.await()
        return confirmed.get() == 1
    }

    private fun luaTypeToDataType(type: Int): String {
        return when (type) {
            1 -> "byte"
            2 -> "word"
            4 -> "dword"
            8 -> "qword"
            16 -> "float"
            32 -> "double"
            else -> "dword"
        }
    }

    private fun dataTypeToLuaType(type: String): Int {
        return when (type) {
            "byte" -> 1
            "word" -> 2
            "dword" -> 4
            "qword" -> 8
            "float" -> 16
            "double" -> 32
            else -> 4
        }
    }

    private fun parseLuaAddress(value: LuaValue): Long? {
        if (value.isnumber()) return value.tolong()
        val raw = value.tojstring().trim()
        return when {
            raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLongOrNull(16)
            raw.matches(Regex("[0-9A-Fa-f]{6,}")) -> raw.toLongOrNull(16)
            else -> raw.toLongOrNull()
        }
    }

    private fun luaValueOf(value: Any?): LuaValue {
        return when (value) {
            null -> LuaValue.NIL
            is Float, is Double -> LuaValue.valueOf((value as Number).toDouble())
            is Number -> LuaValue.valueOf(value.toDouble())
            is Boolean -> LuaValue.valueOf(value)
            else -> LuaValue.valueOf(value.toString())
        }
    }

    private fun resultToLuaTable(source: Map<String, Any?>): LuaTable {
        val item = LuaTable()
        val address = (source["addressInt"] as? Number)?.toLong()
            ?: (source["address"] as? Number)?.toLong()
            ?: source["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
            ?: 0L
        val type = source["type"]?.toString() ?: "dword"
        item.set("address", luaValueOf(address))
        item.set("value", luaValueOf(source["value"]))
        item.set("flags", LuaValue.valueOf(dataTypeToLuaType(type)))
        source["name"]?.let { item.set("name", luaValueOf(it)) }
        source["freeze"]?.let { item.set("freeze", luaValueOf(it)) }
        source["freezeType"]?.let { item.set("freezeType", luaValueOf(it)) }
        source["freezeFrom"]?.let { item.set("freezeFrom", luaValueOf(it)) }
        source["freezeTo"]?.let { item.set("freezeTo", luaValueOf(it)) }
        source["pointerOffset"]?.let { item.set("offset", luaValueOf(it)) }
        source["pointerTarget"]?.let { item.set("target", luaValueOf(it)) }
        return item
    }

    private fun parseLuaMemoryValue(value: LuaValue, type: String): Any? {
        if (value.isnil()) return null
        if (value.isnumber()) {
            return if (type == "float" || type == "double") value.todouble() else value.tolong()
        }
        val raw = value.tojstring().trim()
        if (raw.isEmpty()) return null
        return if (type == "float" || type == "double") {
            raw.toDoubleOrNull()
        } else {
            when {
                raw.startsWith("0x", ignoreCase = true) -> raw.substring(2).toLongOrNull(16)
                raw.endsWith("h", ignoreCase = true) -> raw.dropLast(1).toLongOrNull(16)
                else -> raw.toLongOrNull()
            }
        }
    }

    private fun freezeModeName(mode: Int): String {
        return when (mode) {
            MemoryFreezer.FREEZE_MAY_INCREASE -> "mayIncrease"
            MemoryFreezer.FREEZE_MAY_DECREASE -> "mayDecrease"
            MemoryFreezer.FREEZE_IN_RANGE -> "inRange"
            else -> "normal"
        }
    }

    private fun targetPackageKey(): String {
        val prefs = context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
        return prefs?.getString("attached_package", "")
            ?.takeIf { it.isNotBlank() }
            ?: "pid:${MemoryEngine.getAttachedPid() ?: 0}"
    }

    private fun loadPersistentSavedList(): MutableList<MutableMap<String, Any?>> {
        val prefs = context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE) ?: return savedList
        val raw = prefs.getString("saved_memory_items", "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                mutableMapOf<String, Any?>(
                    "address" to (item.optString("address").toLongOrNull() ?: item.optLong("address", 0L)),
                    "type" to item.optString("type", "dword"),
                    "packageName" to item.optString("packageName", ""),
                    "name" to item.optString("label", "保存项 ${index + 1}"),
                    "value" to item.optString("lastValue", "0"),
                    "freeze" to item.optBoolean("freeze", false),
                    "freezeType" to item.optInt("freezeType", MemoryFreezer.FREEZE_NORMAL),
                    "freezeFrom" to item.optString("freezeFrom", ""),
                    "freezeTo" to item.optString("freezeTo", ""),
                )
            }.filterTo(mutableListOf()) { (it["address"] as? Number)?.toLong()?.let { address -> address > 0L } == true }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persistSavedList(items: List<Map<String, Any?>>) {
        val prefs = context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE) ?: return
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("address", ((item["address"] as? Number)?.toLong() ?: 0L).toString())
                put("type", item["type"]?.toString() ?: "dword")
                put("packageName", item["packageName"]?.toString() ?: targetPackageKey())
                put("label", item["name"]?.toString() ?: "保存项")
                put("lastValue", item["value"]?.toString() ?: "0")
                put("freeze", item["freeze"] as? Boolean ?: false)
                put("freezeType", (item["freezeType"] as? Number)?.toInt() ?: MemoryFreezer.FREEZE_NORMAL)
                put("freezeFrom", item["freezeFrom"]?.toString() ?: "")
                put("freezeTo", item["freezeTo"]?.toString() ?: "")
            })
        }
        prefs.edit().putString("saved_memory_items", array.toString()).apply()
        savedList.clear()
        savedList.addAll(items.map { it.toMutableMap() })
    }

    private fun savedListFileItems(file: java.io.File): List<MutableMap<String, Any?>> {
        if (!file.isFile || file.length() <= 0L || file.length() > 16L * 1024L * 1024L) return emptyList()
        return try {
            val text = file.readText()
            if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                val trimmed = text.trim()
                val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
                MutableList(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val rawAddress = item.optString("address", "0").removePrefix("0x").removePrefix("0X")
                    val address = rawAddress.toLongOrNull() ?: rawAddress.toLongOrNull(16) ?: item.optLong("address", 0L)
                    mutableMapOf<String, Any?>(
                        "address" to address,
                        "type" to item.optString("type", "dword").lowercase(),
                        "packageName" to item.optString("packageName", ""),
                        "name" to item.optString("label", item.optString("name", "导入项 ${index + 1}")),
                        "value" to item.optString("lastValue", item.optString("value", "0")),
                        "freeze" to item.optBoolean("freeze", false),
                        "freezeType" to item.optInt("freezeType", MemoryFreezer.FREEZE_NORMAL),
                        "freezeFrom" to item.optString("freezeFrom", ""),
                        "freezeTo" to item.optString("freezeTo", ""),
                    )
                }.filter { (it["address"] as? Number)?.toLong()?.let { address -> address > 0L } == true && MemoryEngine.isSupportedType(it["type"]?.toString() ?: "") }
            } else {
                text.lineSequence().mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
                    val parts = line.split('\t')
                    if (parts.size < 3) return@mapNotNull null
                    val rawAddress = parts[0].trim().removePrefix("0x").removePrefix("0X")
                    val address = rawAddress.toLongOrNull(16) ?: rawAddress.toLongOrNull() ?: return@mapNotNull null
                    val type = parts[1].trim().lowercase()
                    if (!MemoryEngine.isSupportedType(type)) return@mapNotNull null
                    mutableMapOf<String, Any?>(
                        "address" to address,
                        "type" to type,
                        "packageName" to parts.getOrNull(8)?.trim().orEmpty(),
                        "name" to (parts.getOrNull(7)?.trim()?.takeIf { it.isNotEmpty() } ?: "地址 0x${address.toString(16).uppercase()}"),
                        "value" to parts.getOrNull(2)?.trim().orEmpty().ifBlank { "0" },
                        "freeze" to (parts.getOrNull(3)?.trim()?.equals("true", ignoreCase = true) == true),
                        "freezeType" to (parts.getOrNull(4)?.trim()?.toIntOrNull() ?: MemoryFreezer.FREEZE_NORMAL),
                        "freezeFrom" to parts.getOrNull(5)?.trim().orEmpty(),
                        "freezeTo" to parts.getOrNull(6)?.trim().orEmpty(),
                    )
                }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeSavedListFile(file: java.io.File, items: List<Map<String, Any?>>, asText: Boolean): Boolean {
        return try {
            file.parentFile?.mkdirs()
            if (asText) {
                file.bufferedWriter().use { writer ->
                    writer.appendLine("# GG-AI SAVED LIST v1")
                    writer.appendLine("# address\ttype\tvalue\tfreeze\tfreezeType\tfreezeFrom\tfreezeTo\tlabel\tpackageName")
                    for (item in items) {
                        fun clean(value: Any?): String = value?.toString()?.replace('\t', ' ')?.replace('\r', ' ')?.replace('\n', ' ') ?: ""
                        val address = (item["address"] as? Number)?.toLong() ?: continue
                        writer.append("0x${address.toString(16).uppercase()}").append('\t')
                            .append(clean(item["type"] ?: "dword")).append('\t')
                            .append(clean(item["value"] ?: "0")).append('\t')
                            .append(clean(item["freeze"] ?: false)).append('\t')
                            .append(clean(item["freezeType"] ?: MemoryFreezer.FREEZE_NORMAL)).append('\t')
                            .append(clean(item["freezeFrom"])).append('\t')
                            .append(clean(item["freezeTo"])).append('\t')
                            .append(clean(item["name"])).append('\t')
                            .appendLine(clean(item["packageName"]))
                    }
                }
            } else {
                val array = JSONArray()
                for (item in items) {
                    array.put(JSONObject().apply {
                        put("address", ((item["address"] as? Number)?.toLong() ?: 0L).toString())
                        put("type", item["type"]?.toString() ?: "dword")
                        put("packageName", item["packageName"]?.toString() ?: targetPackageKey())
                        put("label", item["name"]?.toString() ?: "保存项")
                        put("lastValue", item["value"]?.toString() ?: "0")
                        put("freeze", item["freeze"] as? Boolean ?: false)
                        put("freezeType", (item["freezeType"] as? Number)?.toInt() ?: MemoryFreezer.FREEZE_NORMAL)
                        put("freezeFrom", item["freezeFrom"]?.toString() ?: "")
                        put("freezeTo", item["freezeTo"]?.toString() ?: "")
                    })
                }
                file.writeText(JSONObject().apply {
                    put("format", "GG-AI-SAVED-LIST")
                    put("version", 1)
                    put("createdAt", System.currentTimeMillis())
                    put("items", array)
                }.toString(2))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun compareVersionStrings(current: String, required: String): Int {
        val a = current.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val b = required.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private fun aggViewValue(spec: LuaViewBridge.ViewSpec): LuaTable {
        val userdata = org.luaj.vm2.LuaUserdata(spec)
        return LuaTable().apply {
            set("__aggView", userdata)
            set("getView", object : org.luaj.vm2.lib.ZeroArgFunction() {
                override fun call(): LuaValue = userdata
            })
        }
    }

    private fun aggViewSpec(value: LuaValue): LuaViewBridge.ViewSpec? {
        if (value.istable()) {
            val hidden = value.checktable().get("__aggView")
            return LuaViewBridge.viewFromUserdata(hidden)
        }
        return LuaViewBridge.viewFromUserdata(value)
    }

    private fun aggWindowValue(window: LuaViewBridge.WindowSpec): LuaTable {
        return LuaTable().apply {
            set("__aggWindow", org.luaj.vm2.LuaUserdata(window))
        }
    }

    private fun aggWindowSpec(value: LuaValue): LuaViewBridge.WindowSpec? {
        if (value.istable()) {
            val hidden = value.checktable().get("__aggWindow")
            return LuaViewBridge.windowFromUserdata(hidden)
        }
        return LuaViewBridge.windowFromUserdata(value)
    }

    private fun registerAggApi(agg: LuaTable) {
        agg.set("viewText", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = aggViewValue(LuaViewBridge.TextSpec(arg.tojstring()))
        })

        agg.set("viewList", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (!args.arg(1).istable()) return LuaValue.valueOf("items must be a table")
                val source = args.arg(1).checktable()
                val items = mutableListOf<LuaViewBridge.ListItemSpec>()
                for (index in 1..source.length()) {
                    val item = source.get(index)
                    if (!item.istable()) continue
                    val table = item.checktable()
                    val callback = table.get("main")
                    if (!callback.isfunction()) continue
                    items.add(
                        LuaViewBridge.ListItemSpec(
                            title = table.get("title").optjstring("菜单 $index"),
                            subtitle = table.get("subTitle").optjstring(""),
                            callback = callback,
                        )
                    )
                }
                val refresh = args.arg(2).takeIf { it.isfunction() }
                return aggViewValue(LuaViewBridge.ListSpec(items, refresh))
            }
        })

        agg.set("viewSwitch", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("items must be a table")
                val source = arg.checktable()
                val items = mutableListOf<LuaViewBridge.SwitchItemSpec>()
                for (index in 1..source.length()) {
                    val item = source.get(index)
                    if (!item.istable()) continue
                    val table = item.checktable()
                    val open = table.get("open")
                    val close = table.get("close")
                    if (!open.isfunction() || !close.isfunction()) continue
                    items.add(
                        LuaViewBridge.SwitchItemSpec(
                            title = table.get("title").optjstring("开关 $index"),
                            openCallback = open,
                            closeCallback = close,
                            checked = table.get("isCheck").toboolean(),
                        )
                    )
                }
                return aggViewValue(LuaViewBridge.SwitchSpec(items))
            }
        })

        agg.set("viewMultiChoice", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                if (!arg1.istable() || !arg2.isfunction()) return LuaValue.valueOf("invalid arguments")
                val source = arg1.checktable()
                val items = (1..source.length()).map { source.get(it).tojstring() }
                return aggViewValue(LuaViewBridge.MultiChoiceSpec(items, arg2))
            }
        })

        agg.set("viewPrompt", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (!args.arg(1).istable()) return LuaValue.valueOf("prompts must be a table")
                val prompts = args.arg(1).checktable()
                val defaults = if (args.narg() >= 2 && args.arg(2).istable()) args.arg(2).checktable() else LuaTable()
                val types = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else LuaTable()
                val callback = args.arg(4)
                if (!callback.isfunction()) return LuaValue.valueOf("onclick must be a function")
                val fields = mutableListOf<LuaViewBridge.PromptFieldSpec>()
                for (index in 1..prompts.length()) {
                    val prompt = prompts.get(index)
                    val options = if (prompt.istable()) {
                        val table = prompt.checktable()
                        (1..table.length()).map { table.get(it).tojstring() }
                    } else emptyList()
                    val label = if (prompt.istable()) "选择项 $index" else prompt.tojstring()
                    fields.add(
                        LuaViewBridge.PromptFieldSpec(
                            label = label,
                            type = types.get(index).optjstring(if (options.isEmpty()) "text" else "number"),
                            defaultValue = defaults.get(index),
                            options = options,
                        )
                    )
                }
                return aggViewValue(LuaViewBridge.PromptSpec(fields, callback))
            }
        })

        agg.set("viewWeb", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val callbacks = linkedMapOf<String, LuaValue>()
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    val table = args.arg(2).checktable()
                    var key = LuaValue.NIL
                    while (true) {
                        val next = table.next(key)
                        key = next.arg1()
                        if (key.isnil()) break
                        val value = next.arg(2)
                        if (value.isfunction()) callbacks[key.tojstring()] = value
                    }
                }
                return aggViewValue(LuaViewBridge.WebSpec(args.arg(1).tojstring(), callbacks))
            }
        })

        agg.set("mainTabs", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val title = args.arg(1).tojstring()
                val view = aggViewSpec(args.arg(2)) ?: return LuaValue.valueOf("invalid AGG view")
                val locked = args.narg() >= 3 && args.arg(3).toboolean()
                val window = if (args.narg() >= 4) aggWindowSpec(args.arg(4)) else null
                    ?: LuaViewBridge.WindowSpec()
                window.tabs.add(LuaViewBridge.TabSpec(title, view, locked))
                window.activeIndex = window.tabs.lastIndex
                OverlayService.showLuaWindow(window)
                return aggWindowValue(window)
            }
        })

        agg.set("notification", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val ctx = context ?: return LuaValue.FALSE
                return try {
                    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "agg_script_notifications"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        manager.createNotificationChannel(
                            android.app.NotificationChannel(channelId, "AGG 脚本通知", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                        )
                    }
                    @Suppress("DEPRECATION")
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.app.Notification.Builder(ctx, channelId)
                    } else {
                        android.app.Notification.Builder(ctx)
                    }
                    val notification = builder
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(arg1.tojstring())
                        .setContentText(arg2.tojstring())
                        .setAutoCancel(true)
                        .build()
                    manager.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), notification)
                    LuaValue.TRUE
                } catch (e: Exception) {
                    LuaValue.valueOf("notification failed: ${e.message}")
                }
            }
        })

        agg.set("isVPN", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val ctx = context ?: return LuaValue.FALSE
                val active = try {
                    val manager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val network = manager.activeNetwork ?: return LuaValue.FALSE
                    manager.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                } catch (_: Exception) {
                    false
                }
                return LuaValue.valueOf(active)
            }
        })

        agg.set("getProcessInfo", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val ctx = context ?: return LuaTable()
                val includeSystem = arg.toboolean()
                val processes = ProcessManager.getProcessList(ctx).filter { includeSystem || it["isSystem"] != true }
                return LuaTable().apply {
                    processes.forEachIndexed { index, process ->
                        val item = LuaTable()
                        item.set("pid", luaValueOf(process["pid"]))
                        item.set("packageName", luaValueOf(process["packageName"]))
                        item.set("processName", luaValueOf(process["processName"]))
                        item.set("rawProcessName", luaValueOf(process["rawProcessName"]))
                        item.set("uid", luaValueOf(process["uid"]))
                        item.set("isSystem", luaValueOf(process["isSystem"]))
                        set(index + 1, item)
                    }
                }
            }
        })

        agg.set("setProcessInfo", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val ctx = context ?: return LuaValue.FALSE
                val query = arg.tojstring().trim()
                val process = ProcessManager.getProcessList(ctx).firstOrNull { item ->
                    item["packageName"]?.toString() == query ||
                        item["rawProcessName"]?.toString() == query ||
                        item["processName"]?.toString() == query
                } ?: return LuaValue.valueOf("process not found: $query")
                val pid = (process["pid"] as? Number)?.toInt() ?: return LuaValue.FALSE
                val success = MemoryEngine.attachProcess(pid)
                if (success) {
                    ctx.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE).edit()
                        .putInt("attached_pid", pid)
                        .putString("attached_package", process["packageName"]?.toString() ?: query)
                        .putString("attached_name", process["processName"]?.toString() ?: query)
                        .putLong("attached_time", System.currentTimeMillis())
                        .apply()
                }
                return LuaValue.valueOf(success)
            }
        })

        agg.set("getHot", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val raw = RootManager.executeRootCommand(
                    "for f in /sys/class/thermal/thermal_zone*/temp; do [ -r \"\$f\" ] && echo \"\$f|\$(cat \"\$f\" 2>/dev/null)\"; done"
                ).orEmpty()
                val output = LuaTable()
                var index = 1
                for (line in raw.lines()) {
                    val parts = line.split('|', limit = 2)
                    if (parts.size != 2) continue
                    val value = parts[1].trim().toDoubleOrNull() ?: continue
                    val celsius = if (value > 1000.0) value / 1000.0 else value
                    val item = LuaTable()
                    item.set("path", LuaValue.valueOf(parts[0]))
                    item.set("value", LuaValue.valueOf(celsius))
                    output.set(index++, item)
                }
                return output
            }
        })

        agg.set("getWM", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val ctx = context ?: return LuaTable()
                val metrics = ctx.resources.displayMetrics
                val config = ctx.resources.configuration
                return LuaTable().apply {
                    set("width", LuaValue.valueOf(metrics.widthPixels))
                    set("height", LuaValue.valueOf(metrics.heightPixels))
                    set("density", LuaValue.valueOf(metrics.density.toDouble()))
                    set("densityDpi", LuaValue.valueOf(metrics.densityDpi))
                    set("orientation", LuaValue.valueOf(config.orientation))
                }
            }
        })

        agg.set("getClassMethods", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                return try {
                    val methods = Class.forName(arg.tojstring()).declaredMethods
                    LuaTable().apply {
                        methods.forEachIndexed { index, method -> set(index + 1, LuaValue.valueOf(method.toGenericString())) }
                    }
                } catch (e: Exception) {
                    LuaValue.valueOf("class lookup failed: ${e.message}")
                }
            }
        })

        agg.set("isTabVisible", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(OverlayService.isLuaPanelVisible())
        })
        agg.set("setTabVisible", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                OverlayService.setLuaPanelVisible(arg.toboolean())
                return LuaValue.TRUE
            }
        })
    }

    private fun registerGgApi(gg: LuaTable) {
        // gg.toast
        gg.set("toast", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                outputLog.appendLine("📢 $msg")
                mainHandler.post {
                    try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                }
                return LuaValue.NIL
            }
        })

        // gg.alert
        gg.set("alert", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val msg = arg.tojstring()
                outputLog.appendLine("⚠️ $msg")
                showConfirmDialog("提示", msg)
                return LuaValue.NIL
            }
        })

        // gg.prompt
        gg.set("prompt", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val msg = args.arg(1).tojstring()
                var defaultValue = ""
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    val table = args.arg(2).checktable()
                    if (table.length() > 0) defaultValue = table.get(1).tojstring()
                }
                val result = showInputDialog(msg, defaultValue)
                outputLog.appendLine("📝 $msg → $result")
                val resultTable = LuaTable()
                resultTable.set(1, LuaValue.valueOf(result))
                return resultTable
            }
        })

        // gg.choice
        gg.set("choice", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val items = mutableListOf<String>()
                var title = "选择"
                if (args.arg(1).istable()) {
                    val table = args.arg(1).checktable()
                    for (i in 1..table.length()) items.add(table.get(i).tojstring())
                }
                if (args.narg() >= 3 && !args.arg(3).isnil()) {
                    title = args.arg(3).tojstring()
                } else if (args.narg() >= 2 && args.arg(2).isstring()) {
                    val second = args.arg(2)
                    if (second.isstring() && !second.isnumber()) title = second.tojstring()
                }
                if (items.isEmpty()) return LuaValue.NIL

                outputLog.appendLine("📋 $title")
                for (i in items.indices) outputLog.appendLine("  ${i + 1}. ${items[i]}")

                val selected = showChoiceDialog(title, items)
                if (selected > 0) {
                    outputLog.appendLine("  → 选择了: ${items[selected - 1]}")
                } else {
                    outputLog.appendLine("  → 已取消")
                }
                return if (selected > 0) LuaValue.valueOf(selected) else LuaValue.NIL
            }
        })

        // gg.multiChoice
        gg.set("multiChoice", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (!args.arg(1).istable()) return LuaValue.NIL
                val source = args.arg(1).checktable()
                val items = (1..source.length()).map { source.get(it).tojstring() }
                if (items.isEmpty()) return LuaValue.NIL
                val initial = BooleanArray(items.size)
                if (args.narg() >= 2 && args.arg(2).istable()) {
                    val selected = args.arg(2).checktable()
                    for (index in initial.indices) initial[index] = selected.get(index + 1).toboolean()
                }
                val title = if (args.narg() >= 3 && !args.arg(3).isnil()) args.arg(3).tojstring() else "选择"
                val checked = showMultiChoiceDialog(title, items, initial) ?: return LuaValue.NIL
                val output = LuaTable()
                for (index in checked.indices) if (checked[index]) output.set(index + 1, LuaValue.TRUE)
                return output
            }
        })

        // gg.bytes / gg.copyText
        gg.set("bytes", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.arg(1).tojstring()
                val encoding = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).tojstring() else "UTF-8"
                return try {
                    val data = text.toByteArray(java.nio.charset.Charset.forName(encoding))
                    LuaTable().apply {
                        data.forEachIndexed { index, byte -> set(index + 1, LuaValue.valueOf(byte.toInt() and 0xFF)) }
                    }
                } catch (_: Exception) {
                    LuaValue.valueOf("unsupported encoding: $encoding")
                }
            }
        })
        gg.set("copyText", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.arg(1).tojstring()
                val ctx = context ?: return LuaValue.NIL
                mainHandler.post {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("GG-AI", text))
                }
                return LuaValue.NIL
            }
        })

        // gg.makeRequest
        gg.set("makeRequest", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val rawUrl = args.arg(1).tojstring().trim()
                if (!rawUrl.startsWith("https://") && !rawUrl.startsWith("http://")) {
                    return LuaValue.valueOf("only http and https URLs are supported")
                }
                return try {
                    val connection = java.net.URL(rawUrl).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "GG-AI-Modifier/1.0")
                    if (args.narg() >= 2 && args.arg(2).istable()) {
                        val headers = args.arg(2).checktable()
                        var key = LuaValue.NIL
                        while (true) {
                            val next = headers.next(key)
                            key = next.arg1()
                            if (key.isnil()) break
                            connection.setRequestProperty(key.tojstring(), next.arg(2).tojstring())
                        }
                    }
                    if (args.narg() >= 3 && !args.arg(3).isnil()) {
                        val data = args.arg(3).tojstring().toByteArray(Charsets.UTF_8)
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Length", data.size.toString())
                        connection.outputStream.use { it.write(data) }
                    }
                    val code = connection.responseCode
                    val source = if (code in 200..399) connection.inputStream else connection.errorStream
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    if (source != null) source.use { input ->
                        while (output.size() < 4 * 1024 * 1024) {
                            val read = input.read(buffer, 0, minOf(buffer.size, 4 * 1024 * 1024 - output.size()))
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    val response = LuaTable()
                    response.set("code", LuaValue.valueOf(code))
                    response.set("content", LuaValue.valueOf(output.toString(Charsets.UTF_8.name())))
                    response.set("url", LuaValue.valueOf(connection.url.toString()))
                    val responseHeaders = LuaTable()
                    connection.headerFields.filterKeys { it != null }.forEach { (name, values) ->
                        responseHeaders.set(name, LuaValue.valueOf(values.joinToString(", ")))
                    }
                    response.set("headers", responseHeaders)
                    connection.disconnect()
                    response
                } catch (e: Exception) {
                    LuaValue.valueOf("request failed: ${e.message}")
                }
            }
        })

        // gg.searchNumber
        gg.set("searchNumber", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val value = arg1.tojstring()
                val type = luaTypeToDataType(arg2.toint())
                val numValue: Any = when (type) {
                    "float", "double" -> value.toDoubleOrNull() ?: 0.0
                    else -> value.toLongOrNull() ?: 0
                }
                val results = MemoryEngine.searchExact(numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                outputLog.appendLine("🔍 搜索 $value ($type): 找到 ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.refineNumber
        gg.set("refineNumber", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val value = arg.tojstring()
                val prevAddresses = searchResults.map { (it["addressInt"] as Number).toLong() }
                val numValue: Any = value.toDoubleOrNull() ?: value.toLongOrNull() ?: 0
                val type = searchResults.firstOrNull()?.get("type") as? String ?: "dword"
                val results = MemoryEngine.filterResults(prevAddresses, numValue, type)
                searchResults.clear()
                searchResults.addAll(results)
                outputLog.appendLine("🔍 过滤后: ${results.size} 个结果")
                return LuaValue.valueOf(results.size)
            }
        })

        // gg.getResultsCount / gg.getResultCount
        val getResultsCountFunc = object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(searchResults.size)
        }
        gg.set("getResultsCount", getResultsCountFunc)
        gg.set("getResultCount", getResultsCountFunc)

        // gg.getResults
        gg.set("getResults", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val maxCount = args.arg(1).toint().coerceAtLeast(0)
                val skip = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).toint().coerceAtLeast(0) else 0
                val addressMin = if (args.narg() >= 3 && !args.arg(3).isnil()) args.arg(3).tolong() else Long.MIN_VALUE
                val addressMax = if (args.narg() >= 4 && !args.arg(4).isnil()) args.arg(4).tolong() else Long.MAX_VALUE
                val valueMin = if (args.narg() >= 5 && !args.arg(5).isnil()) args.arg(5).tojstring().toDoubleOrNull() else null
                val valueMax = if (args.narg() >= 6 && !args.arg(6).isnil()) args.arg(6).tojstring().toDoubleOrNull() else null
                val typeFlag = if (args.narg() >= 7 && !args.arg(7).isnil()) args.arg(7).toint() else 0
                val pointerFilter = if (args.narg() >= 9 && !args.arg(9).isnil()) args.arg(9).toint() else 0
                val filtered = searchResults.asSequence().filter { result ->
                    val address = (result["addressInt"] as? Number)?.toLong()
                        ?: result["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
                        ?: return@filter false
                    if (address !in addressMin..addressMax) return@filter false
                    if (typeFlag != 0 && dataTypeToLuaType(result["type"]?.toString() ?: "dword") != typeFlag) return@filter false
                    val number = (result["value"] as? Number)?.toDouble() ?: result["value"]?.toString()?.toDoubleOrNull()
                    if (valueMin != null && (number == null || number < valueMin)) return@filter false
                    if (valueMax != null && (number == null || number > valueMax)) return@filter false
                    if (pointerFilter != 0 && result["pointerOffset"] == null) return@filter false
                    true
                }.drop(skip).let { sequence -> if (maxCount == 0) sequence else sequence.take(maxCount) }.toList()
                return LuaTable().apply {
                    filtered.forEachIndexed { index, result -> set(index + 1, resultToLuaTable(result)) }
                }
            }
        })

        // gg.removeResults
        gg.set("removeResults", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("results must be a table")
                val input = arg.checktable()
                val addresses = mutableSetOf<Long>()
                for (index in 1..input.length()) {
                    val source = input.get(index)
                    val value = if (source.istable()) source.checktable().get("address") else source
                    parseLuaAddress(value)?.let { addresses.add(it) }
                }
                searchResults.removeAll { result ->
                    val address = (result["addressInt"] as? Number)?.toLong()
                        ?: result["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
                    address != null && address in addresses
                }
                return LuaValue.TRUE
            }
        })

        // No independent selection model exists inside a headless Lua run, so expose the active set.
        gg.set("getSelectedResults", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaTable().apply {
                searchResults.forEachIndexed { index, result -> set(index + 1, resultToLuaTable(result)) }
            }
        })
        gg.set("getSelectedElements", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = gg.get("getSelectedResults").call()
        })
        gg.set("getSelectedListItems", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val targetKey = targetPackageKey()
                val items = loadPersistentSavedList().filter { it["packageName"] == targetKey }
                return LuaTable().apply {
                    items.forEachIndexed { index, item -> set(index + 1, resultToLuaTable(item)) }
                }
            }
        })

        // gg.loadResults
        gg.set("loadResults", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("results must be a table")
                val input = arg.checktable()
                val loaded = mutableListOf<Map<String, Any>>()
                for (i in 1..input.length()) {
                    val source = input.get(i)
                    if (!source.istable()) continue
                    val item = source.checktable()
                    val address = parseLuaAddress(item.get("address")) ?: continue
                    val flagsValue = item.get("flags")
                    val type = luaTypeToDataType(if (flagsValue.isnil()) 4 else flagsValue.toint())
                    val value = MemoryEngine.readMemory(address, type) ?: continue
                    loaded.add(
                        mapOf(
                            "address" to "0x${address.toString(16).uppercase()}",
                            "addressInt" to address,
                            "value" to value,
                            "type" to type,
                        )
                    )
                }
                searchResults.clear()
                searchResults.addAll(loaded)
                outputLog.appendLine("📥 已加载 ${loaded.size} 条搜索结果")
                return LuaValue.TRUE
            }
        })

        // gg.editAll
        gg.set("editAll", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val type = luaTypeToDataType(arg2.toint())
                val rawValues = arg1.tojstring().split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (rawValues.isEmpty()) return LuaValue.valueOf("value is empty")
                var count = 0
                val updated = searchResults.toMutableList()
                for ((index, result) in searchResults.withIndex()) {
                    val resultType = result["type"] as? String ?: type
                    if (resultType != type) continue
                    val address = (result["addressInt"] as? Number)?.toLong()
                        ?: result["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
                        ?: continue
                    val raw = rawValues[count % rawValues.size]
                    val value = parseLuaMemoryValue(LuaValue.valueOf(raw), type) ?: continue
                    var success = MemoryEngine.writeMemory(address, value, type)
                    if (success && MemoryFreezer.isFrozen(address)) {
                        val freezeType = MemoryFreezer.getFreezeType(address) ?: MemoryFreezer.FREEZE_NORMAL
                        success = MemoryFreezer.freeze(address, value, type, freezeType)
                    }
                    if (success) {
                        updated[index] = result.toMutableMap().apply { this["value"] = value }
                        count++
                    }
                }
                searchResults.clear()
                searchResults.addAll(updated)
                outputLog.appendLine("✏️ editAll 已修改 $count 条结果")
                return LuaValue.valueOf(count)
            }
        })

        // gg.setValues
        gg.set("setValues", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("values must be a table")
                val table = arg.checktable()
                var count = 0
                for (i in 1..table.length()) {
                    val item = table.get(i)
                    if (!item.istable()) continue
                    val itemTable = item.checktable()
                    val address = parseLuaAddress(itemTable.get("address")) ?: continue
                    val flags = itemTable.get("flags")
                    val type = luaTypeToDataType(if (flags.isnil()) 4 else flags.toint())
                    val numValue = parseLuaMemoryValue(itemTable.get("value"), type) ?: continue
                    var success = MemoryEngine.writeMemory(address, numValue, type)
                    if (success && MemoryFreezer.isFrozen(address)) {
                        val currentMode = MemoryFreezer.getFreezeType(address) ?: MemoryFreezer.FREEZE_NORMAL
                        success = MemoryFreezer.freeze(address, numValue, type, currentMode)
                    }
                    if (success) count++
                }
                outputLog.appendLine("✏️ 已修改 $count 个地址")
                return if (count > 0 || table.length() == 0) LuaValue.TRUE else LuaValue.valueOf("no values were written")
            }
        })

        // gg.writeMemory
        gg.set("writeMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = parseLuaAddress(args.arg(1)) ?: return LuaValue.valueOf(false)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue = parseLuaMemoryValue(args.arg(2), type) ?: return LuaValue.valueOf(false)
                val success = MemoryEngine.writeMemory(address, numValue, type)
                if (success) outputLog.appendLine("✏️ 写入 0x${address.toString(16).uppercase()} = $numValue")
                return LuaValue.valueOf(success)
            }
        })

        // gg.freeze
        gg.set("freeze", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val address = parseLuaAddress(args.arg(1)) ?: return LuaValue.valueOf(false)
                val type = luaTypeToDataType(args.arg(3).toint())
                val numValue = parseLuaMemoryValue(args.arg(2), type) ?: return LuaValue.valueOf(false)
                val freezeType = if (args.narg() >= 4) args.arg(4).toint() else MemoryFreezer.FREEZE_NORMAL
                val freezeFrom = if (args.narg() >= 5) parseLuaMemoryValue(args.arg(5), type) else null
                val freezeTo = if (args.narg() >= 6) parseLuaMemoryValue(args.arg(6), type) else null
                val success = MemoryFreezer.freeze(address, numValue, type, freezeType, freezeFrom, freezeTo)
                if (success) outputLog.appendLine("🔒 冻结 0x${address.toString(16).uppercase()} = $numValue (${freezeModeName(freezeType)})")
                return LuaValue.valueOf(success)
            }
        })

        // gg.addListItems
        gg.set("addListItems", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("items must be a table")
                val table = arg.checktable()
                savedList.clear()
                savedList.addAll(loadPersistentSavedList())
                var count = 0
                for (i in 1..table.length()) {
                    val source = table.get(i)
                    if (!source.istable()) continue
                    val item = source.checktable()
                    val address = parseLuaAddress(item.get("address")) ?: continue
                    val flagsValue = item.get("flags")
                    val type = luaTypeToDataType(if (flagsValue.isnil()) 4 else flagsValue.toint())
                    val value = parseLuaMemoryValue(item.get("value"), type)
                        ?: MemoryEngine.readMemory(address, type)
                        ?: continue
                    val freeze = item.get("freeze").toboolean()
                    val freezeTypeValue = item.get("freezeType")
                    val freezeType = if (freezeTypeValue.isnil()) MemoryFreezer.FREEZE_NORMAL else freezeTypeValue.toint()
                    val freezeFrom = parseLuaMemoryValue(item.get("freezeFrom"), type)
                    val freezeTo = parseLuaMemoryValue(item.get("freezeTo"), type)
                    val nameValue = item.get("name")
                    val name = if (nameValue.isnil()) "" else nameValue.tojstring()

                    val saved = mutableMapOf<String, Any?>(
                        "address" to address,
                        "value" to value,
                        "type" to type,
                        "packageName" to targetPackageKey(),
                        "name" to name.ifBlank { "地址 0x${address.toString(16).uppercase()}" },
                        "freeze" to freeze,
                        "freezeType" to freezeType,
                        "freezeFrom" to freezeFrom,
                        "freezeTo" to freezeTo,
                    )
                    val existing = savedList.indexOfFirst {
                        (it["address"] as? Number)?.toLong() == address && it["type"] == type
                    }
                    if (existing >= 0) savedList[existing] = saved else savedList.add(saved)

                    if (!freeze || MemoryFreezer.freeze(address, value, type, freezeType, freezeFrom, freezeTo)) {
                        count++
                    }
                }
                persistSavedList(savedList)
                outputLog.appendLine("💾 已加入保存列表 $count 条")
                return if (count > 0 || table.length() == 0) LuaValue.TRUE else LuaValue.valueOf("no items were added")
            }
        })

        // gg.getValues
        gg.set("getValues", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaTable()
                val input = arg.checktable()
                val output = LuaTable()
                var outputIndex = 1
                for (i in 1..input.length()) {
                    val source = input.get(i)
                    if (!source.istable()) continue
                    val sourceTable = source.checktable()
                    val address = parseLuaAddress(sourceTable.get("address")) ?: continue
                    val flagsValue = sourceTable.get("flags")
                    val type = luaTypeToDataType(if (flagsValue.isnil()) 4 else flagsValue.toint())
                    val value = MemoryEngine.readMemory(address, type) ?: continue
                    val item = LuaTable()
                    item.set("address", LuaValue.valueOf(address.toDouble()))
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType(type)))
                    item.set("value", luaValueOf(value))
                    item.set("freeze", LuaValue.valueOf(MemoryFreezer.isFrozen(address)))
                    output.set(outputIndex++, item)
                }
                return output
            }
        })

        // gg.getValuesRange
        gg.set("getValuesRange", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("values must be a table")
                val input = arg.checktable()
                val regions = MemoryEngine.getMemoryRegions()
                val output = LuaTable()
                fun shortCode(category: String): String = when (category) {
                    "heap" -> "Ch"
                    "java" -> "Jh"
                    "stack" -> "S"
                    "app" -> "Xa"
                    "system" -> "Xs"
                    "anonymous" -> "A"
                    else -> "O"
                }
                for (index in 1..input.length()) {
                    val source = input.get(index)
                    val addressValue = if (source.istable()) source.checktable().get("address") else source
                    val address = parseLuaAddress(addressValue) ?: continue
                    val region = regions.firstOrNull { item ->
                        val start = (item["startAddress"] as? Number)?.toLong() ?: return@firstOrNull false
                        val end = (item["endAddress"] as? Number)?.toLong() ?: return@firstOrNull false
                        address in start until end
                    }
                    output.set(index, LuaValue.valueOf(shortCode(region?.get("category")?.toString() ?: "other")))
                }
                return output
            }
        })

        // gg.getListItems
        gg.set("getListItems", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val output = LuaTable()
                savedList.clear()
                savedList.addAll(loadPersistentSavedList())
                val targetKey = targetPackageKey()
                val visible = savedList.filter { it["packageName"] == targetKey }
                for ((index, source) in visible.withIndex()) {
                    val address = (source["address"] as? Number)?.toLong() ?: continue
                    val type = source["type"] as? String ?: "dword"
                    val currentValue = MemoryEngine.readMemory(address, type) ?: source["value"]
                    if (currentValue != null) source["value"] = currentValue
                    val item = LuaTable()
                    item.set("address", luaValueOf(address))
                    item.set("value", luaValueOf(currentValue))
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType(type)))
                    item.set("name", luaValueOf(source["name"] ?: ""))
                    item.set("freeze", LuaValue.valueOf(MemoryFreezer.isFrozen(address)))
                    item.set("freezeType", luaValueOf(source["freezeType"] ?: MemoryFreezer.FREEZE_NORMAL))
                    item.set("freezeFrom", luaValueOf(source["freezeFrom"]))
                    item.set("freezeTo", luaValueOf(source["freezeTo"]))
                    output.set(index + 1, item)
                }
                return output
            }
        })

        // gg.removeListItems
        gg.set("removeListItems", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                if (!arg.istable()) return LuaValue.valueOf("items must be a table")
                val table = arg.checktable()
                savedList.clear()
                savedList.addAll(loadPersistentSavedList())
                var count = 0
                for (i in 1..table.length()) {
                    val source = table.get(i)
                    val addressValue = if (source.istable()) source.checktable().get("address") else source
                    val address = parseLuaAddress(addressValue) ?: continue
                    val before = savedList.size
                    savedList.removeAll { (it["address"] as? Number)?.toLong() == address }
                    MemoryFreezer.unfreeze(address)
                    if (savedList.size < before) count++
                }
                persistSavedList(savedList)
                outputLog.appendLine("🗑️ 已从保存列表移除 $count 个地址")
                return LuaValue.TRUE
            }
        })

        // gg.saveList / gg.loadList
        gg.set("saveList", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val path = args.arg(1).tojstring().trim()
                if (path.isEmpty()) return LuaValue.valueOf("file path is empty")
                val flags = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).toint() else 0
                savedList.clear()
                savedList.addAll(loadPersistentSavedList())
                val targetKey = targetPackageKey()
                val items = savedList.filter { it["packageName"] == targetKey }
                val success = writeSavedListFile(java.io.File(path), items, flags and 1 != 0)
                if (success) outputLog.appendLine("💾 已保存 ${items.size} 条列表：$path")
                return if (success) LuaValue.TRUE else LuaValue.valueOf("save list failed")
            }
        })
        gg.set("loadList", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val path = args.arg(1).tojstring().trim()
                if (path.isEmpty()) return LuaValue.valueOf("file path is empty")
                val flags = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).toint() else 0
                val imported = savedListFileItems(java.io.File(path))
                if (imported.isEmpty()) return LuaValue.valueOf("list file is empty or invalid")
                val append = flags and 1 != 0
                val applyValues = flags and 2 != 0 || flags and 4 != 0
                val freezeValues = flags and 4 != 0
                val targetKey = targetPackageKey()
                savedList.clear()
                savedList.addAll(loadPersistentSavedList())
                if (!append) savedList.removeAll { it["packageName"] == targetKey }
                var loaded = 0
                for (source in imported) {
                    val address = (source["address"] as? Number)?.toLong() ?: continue
                    val type = source["type"]?.toString()?.takeIf { MemoryEngine.isSupportedType(it) } ?: continue
                    val value = source["value"]?.toString()?.let { raw ->
                        parseLuaMemoryValue(LuaValue.valueOf(raw), type)
                    } ?: MemoryEngine.readMemory(address, type)
                    val item = source.toMutableMap().apply {
                        this["packageName"] = targetKey
                        this["type"] = type
                        this["freeze"] = if (freezeValues) true else (this["freeze"] as? Boolean ?: false)
                    }
                    val index = savedList.indexOfFirst {
                        (it["address"] as? Number)?.toLong() == address && it["type"] == type && it["packageName"] == targetKey
                    }
                    if (index >= 0) savedList[index] = item else savedList.add(item)
                    var success = true
                    if (applyValues && value != null) success = MemoryEngine.writeMemory(address, value, type)
                    if (success && (freezeValues || item["freeze"] == true) && value != null) {
                        val freezeType = (item["freezeType"] as? Number)?.toInt() ?: MemoryFreezer.FREEZE_NORMAL
                        val freezeFrom = item["freezeFrom"]?.toString()?.takeIf { it.isNotBlank() }
                            ?.let { parseLuaMemoryValue(LuaValue.valueOf(it), type) }
                        val freezeTo = item["freezeTo"]?.toString()?.takeIf { it.isNotBlank() }
                            ?.let { parseLuaMemoryValue(LuaValue.valueOf(it), type) }
                        success = MemoryFreezer.freeze(address, value, type, freezeType, freezeFrom, freezeTo)
                    }
                    if (success) loaded++
                }
                persistSavedList(savedList)
                outputLog.appendLine("📥 已加载 $loaded/${imported.size} 条列表：$path")
                return LuaValue.TRUE
            }
        })

        // gg.searchPointer
        gg.set("searchPointer", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (searchResults.isEmpty()) return LuaValue.valueOf("no search results")
                val maxOffset = args.arg(1).tolong().coerceAtLeast(0L)
                val memoryFrom = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).tolong() else 0L
                val memoryTo = if (args.narg() >= 3 && !args.arg(3).isnil()) args.arg(3).tolong() else -1L
                val limit = if (args.narg() >= 4 && !args.arg(4).isnil()) args.arg(4).toint() else 0
                val targets = searchResults.mapNotNull { source ->
                    (source["addressInt"] as? Number)?.toLong()
                        ?: source["address"]?.toString()?.removePrefix("0x")?.removePrefix("0X")?.toLongOrNull(16)
                }
                val pointers = MemoryEngine.searchPointers(targets, maxOffset, memoryFrom, memoryTo, limit)
                searchResults.clear()
                searchResults.addAll(pointers)
                outputLog.appendLine("🧷 指针搜索完成：${pointers.size} 条，最大偏移 0x${maxOffset.toString(16).uppercase()}")
                return LuaValue.TRUE
            }
        })

        // gg.processPause / gg.processResume / gg.isProcessPaused
        gg.set("processPause", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = MemoryEngine.getAttachedPid() ?: return LuaValue.FALSE
                val success = RootManager.executeRootCommand("kill -STOP $pid") != null
                if (success) outputLog.appendLine("⏸️ 已暂停进程 $pid")
                return LuaValue.valueOf(success)
            }
        })
        gg.set("processResume", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = MemoryEngine.getAttachedPid() ?: return LuaValue.FALSE
                val success = RootManager.executeRootCommand("kill -CONT $pid") != null
                if (success) outputLog.appendLine("▶️ 已恢复进程 $pid")
                return LuaValue.valueOf(success)
            }
        })
        gg.set("processToggle", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = MemoryEngine.getAttachedPid() ?: return LuaValue.FALSE
                val state = RootManager.executeRootCommand("grep '^State:' /proc/$pid/status | cut -c 8")
                val paused = state?.trim()?.startsWith("T") == true
                val success = RootManager.executeRootCommand("kill -${if (paused) "CONT" else "STOP"} $pid") != null
                if (success) outputLog.appendLine(if (paused) "▶️ 已恢复进程 $pid" else "⏸️ 已暂停进程 $pid")
                return LuaValue.valueOf(success)
            }
        })
        gg.set("processKill", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = MemoryEngine.getAttachedPid() ?: return LuaValue.FALSE
                val success = RootManager.executeRootCommand("kill -KILL $pid") != null
                if (success) {
                    outputLog.appendLine("⏹️ 已结束进程 $pid")
                    MemoryEngine.detachProcess()
                    context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)?.edit()
                        ?.remove("attached_pid")
                        ?.remove("attached_package")
                        ?.remove("attached_name")
                        ?.remove("attached_time")
                        ?.apply()
                }
                return LuaValue.valueOf(success)
            }
        })
        gg.set("isProcessPaused", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = MemoryEngine.getAttachedPid() ?: return LuaValue.FALSE
                val state = RootManager.executeRootCommand("grep '^State:' /proc/$pid/status | cut -c 8")
                return LuaValue.valueOf(state?.trim()?.startsWith("T") == true)
            }
        })

        // gg.copyMemory / gg.dumpMemory
        gg.set("copyMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val from = parseLuaAddress(args.arg(1)) ?: return LuaValue.valueOf("invalid source address")
                val to = parseLuaAddress(args.arg(2)) ?: return LuaValue.valueOf("invalid target address")
                val bytes = args.arg(3).toint()
                val success = MemoryEngine.copyMemory(from, to, bytes)
                if (success) outputLog.appendLine("📋 已复制 $bytes 字节：0x${from.toString(16).uppercase()} → 0x${to.toString(16).uppercase()}")
                return if (success) LuaValue.TRUE else LuaValue.valueOf("copy memory failed")
            }
        })
        gg.set("dumpMemory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val from = parseLuaAddress(args.arg(1)) ?: return LuaValue.valueOf("invalid start address")
                val to = parseLuaAddress(args.arg(2)) ?: return LuaValue.valueOf("invalid end address")
                val directoryPath = args.arg(3).tojstring().trim()
                if (directoryPath.isEmpty()) return LuaValue.valueOf("dump directory is empty")
                val directory = java.io.File(directoryPath)
                val outputFile = java.io.File(directory, "dump_${from.toString(16)}_${to.toString(16)}.bin")
                val written = MemoryEngine.dumpMemory(from, to, outputFile)
                if (written < 0L) return LuaValue.valueOf("dump memory failed")
                outputLog.appendLine("💾 已转储 $written 字节：${outputFile.absolutePath}")
                return LuaValue.TRUE
            }
        })

        // Target, locale and UI compatibility helpers
        gg.set("getTargetPackage", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val pkg = context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
                    ?.getString("attached_package", "")
                    ?.takeIf { it.isNotBlank() }
                return pkg?.let { LuaValue.valueOf(it) } ?: LuaValue.NIL
            }
        })
        gg.set("isPackageInstalled", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val ctx = context ?: return LuaValue.FALSE
                val installed = try {
                    ctx.packageManager.getPackageInfo(arg.tojstring(), 0)
                    true
                } catch (_: Exception) {
                    false
                }
                return LuaValue.valueOf(installed)
            }
        })
        gg.set("getLocale", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(java.util.Locale.getDefault().toString())
        })
        gg.set("numberFromLocale", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val symbols = java.text.DecimalFormatSymbols.getInstance()
                val fixed = arg.tojstring()
                    .replace(symbols.groupingSeparator.toString(), "")
                    .replace(symbols.decimalSeparator, '.')
                return LuaValue.valueOf(fixed)
            }
        })
        gg.set("numberToLocale", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val symbols = java.text.DecimalFormatSymbols.getInstance()
                val fixed = arg.tojstring().replace(",", "").replace('.', symbols.decimalSeparator)
                return LuaValue.valueOf(fixed)
            }
        })
        gg.set("setVisible", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                OverlayService.setLuaPanelVisible(arg.toboolean())
                return LuaValue.NIL
            }
        })
        gg.set("isVisible", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(OverlayService.isLuaPanelVisible())
        })
        gg.set("showUiButton", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                OverlayService.setLuaButtonVisible(true)
                return LuaValue.NIL
            }
        })
        gg.set("hideUiButton", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                OverlayService.setLuaButtonVisible(false)
                return LuaValue.NIL
            }
        })
        gg.set("isClickedUiButton", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                if (!OverlayService.isLuaButtonVisible()) return LuaValue.NIL
                return LuaValue.valueOf(OverlayService.consumeLuaButtonClick())
            }
        })
        gg.set("gotoAddress", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                parseLuaAddress(arg)?.let { OverlayService.luaGotoAddress(it) }
                return LuaValue.NIL
            }
        })
        gg.set("require", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val ctx = context ?: return LuaValue.NIL
                val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                val currentVersion = info.versionName ?: "0"
                @Suppress("DEPRECATION")
                val currentBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
                val requiredVersion = if (!args.arg(1).isnil()) args.arg(1).tojstring() else ""
                val requiredBuild = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).tolong() else 0L
                if ((requiredVersion.isNotEmpty() && compareVersionStrings(currentVersion, requiredVersion) < 0) || currentBuild < requiredBuild) {
                    throw org.luaj.vm2.LuaError("GG-AI version $requiredVersion ($requiredBuild) or newer is required")
                }
                return LuaValue.NIL
            }
        })
        gg.set("skipRestoreState", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.NIL
        })

        // gg.getTargetInfo
        gg.set("getTargetInfo", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val result = LuaTable()
                val pid = MemoryEngine.getAttachedPid() ?: 0
                val prefs = context?.getSharedPreferences("gg_overlay", Context.MODE_PRIVATE)
                result.set("pid", LuaValue.valueOf(pid))
                result.set("packageName", LuaValue.valueOf(prefs?.getString("attached_package", "") ?: ""))
                result.set("processName", LuaValue.valueOf(prefs?.getString("attached_name", "") ?: ""))
                result.set("x64", LuaValue.valueOf(Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()))
                result.set("cmdLine", LuaValue.valueOf(prefs?.getString("attached_package", "") ?: ""))
                return result
            }
        })

        // gg.getRangesList
        gg.set("getRangesList", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val output = LuaTable()
                val regions = MemoryEngine.getMemoryRegions()
                for ((index, region) in regions.withIndex()) {
                    val item = LuaTable()
                    item.set("start", luaValueOf(region["startAddress"]))
                    item.set("end", luaValueOf(region["endAddress"]))
                    item.set("state", luaValueOf(region["permissions"]))
                    item.set("name", luaValueOf(region["name"]))
                    item.set("type", luaValueOf(region["category"]))
                    output.set(index + 1, item)
                }
                return output
            }
        })

        val regionBits = linkedMapOf(
            "anonymous" to 1,
            "heap" to 2,
            "java" to 4,
            "stack" to 8,
            "app" to 16,
            "system" to 32,
            "other" to 64,
        )

        // gg.getRanges / gg.setRanges
        gg.set("getRanges", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val selected = MemoryEngine.getSelectedRegionCategories()
                val mask = regionBits.entries.fold(0) { acc, entry ->
                    if (entry.key in selected) acc or entry.value else acc
                }
                return LuaValue.valueOf(mask)
            }
        })
        gg.set("setRanges", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val mask = arg.toint()
                val selected = regionBits.filterValues { bit -> mask and bit != 0 }.keys
                val success = selected.isNotEmpty() && MemoryEngine.setRegionCategories(selected)
                if (success) outputLog.appendLine("🧭 已切换内存范围: ${selected.joinToString()}")
                return LuaValue.valueOf(success)
            }
        })

        // gg.clearResults
        gg.set("clearResults", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue { searchResults.clear(); return LuaValue.NIL }
        })

        // gg.clearList
        gg.set("clearList", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                savedList.clear()
                persistSavedList(emptyList())
                MemoryFreezer.clearAll()
                outputLog.appendLine("🔓 已清空保存与冻结列表")
                return LuaValue.NIL
            }
        })

        // gg.sleep
        gg.set("sleep", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                try { Thread.sleep(arg.tolong()) } catch (_: Exception) {}
                return LuaValue.NIL
            }
        })

        // Constants
        gg.set("TYPE_BYTE", LuaValue.valueOf(1))
        gg.set("TYPE_WORD", LuaValue.valueOf(2))
        gg.set("TYPE_DWORD", LuaValue.valueOf(4))
        gg.set("TYPE_QWORD", LuaValue.valueOf(8))
        gg.set("TYPE_FLOAT", LuaValue.valueOf(16))
        gg.set("TYPE_DOUBLE", LuaValue.valueOf(32))
        gg.set("FREEZE_NORMAL", LuaValue.valueOf(MemoryFreezer.FREEZE_NORMAL))
        gg.set("FREEZE_MAY_INCREASE", LuaValue.valueOf(MemoryFreezer.FREEZE_MAY_INCREASE))
        gg.set("FREEZE_MAY_DECREASE", LuaValue.valueOf(MemoryFreezer.FREEZE_MAY_DECREASE))
        gg.set("FREEZE_IN_RANGE", LuaValue.valueOf(MemoryFreezer.FREEZE_IN_RANGE))
        gg.set("DUMP_SKIP_SYSTEM_LIBS", LuaValue.valueOf(1))
        gg.set("SAVE_AS_TEXT", LuaValue.valueOf(1))
        gg.set("LOAD_APPEND", LuaValue.valueOf(1))
        gg.set("LOAD_VALUES", LuaValue.valueOf(2))
        gg.set("LOAD_VALUES_FREEZE", LuaValue.valueOf(4))
        gg.set("POINTER_NO", LuaValue.valueOf(0))
        gg.set("POINTER_READ_ONLY", LuaValue.valueOf(1))
        gg.set("POINTER_WRITABLE", LuaValue.valueOf(2))
        gg.set("POINTER_EXECUTABLE", LuaValue.valueOf(4))
        gg.set("POINTER_EXECUTABLE_WRITABLE", LuaValue.valueOf(6))
        gg.set("REGION_ANONYMOUS", LuaValue.valueOf(1))
        gg.set("REGION_C_HEAP", LuaValue.valueOf(2))
        gg.set("REGION_JAVA_HEAP", LuaValue.valueOf(4))
        gg.set("REGION_JAVA", LuaValue.valueOf(4))
        gg.set("REGION_STACK", LuaValue.valueOf(8))
        gg.set("REGION_CODE_APP", LuaValue.valueOf(16))
        gg.set("REGION_CODE_SYS", LuaValue.valueOf(32))
        gg.set("REGION_OTHER", LuaValue.valueOf(64))
        gg.set("REGION_C_ALLOC", LuaValue.valueOf(1))
        gg.set("REGION_C_DATA", LuaValue.valueOf(16))
        gg.set("REGION_C_BSS", LuaValue.valueOf(1))
    }
}
