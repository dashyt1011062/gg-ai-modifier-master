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
        gg.set("getResults", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue {
                val count = arg.toint()
                val table = LuaTable()
                val takeCount = minOf(count, searchResults.size)
                for (i in 0 until takeCount) {
                    val result = searchResults[i]
                    val item = LuaTable()
                    item.set("address", LuaValue.valueOf(result["address"] as String))
                    item.set("value", LuaValue.valueOf((result["value"] as? Number)?.toDouble() ?: 0.0))
                    item.set("flags", LuaValue.valueOf(dataTypeToLuaType(result["type"] as String)))
                    table.set(i + 1, item)
                }
                return table
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
