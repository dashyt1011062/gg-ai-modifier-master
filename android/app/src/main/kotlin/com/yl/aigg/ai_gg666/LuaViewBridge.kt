package com.yl.aigg.ai_gg666

import android.webkit.JavascriptInterface
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * AGG Lua 自定义视图模型。
 * LuaEngine 负责解析脚本参数，OverlayService 负责把这些模型绘制为真实悬浮窗控件。
 */
object LuaViewBridge {

    sealed class ViewSpec

    data class TextSpec(val text: String) : ViewSpec()

    data class ListItemSpec(
        val title: String,
        val subtitle: String,
        val callback: LuaValue,
    )

    data class ListSpec(
        val items: List<ListItemSpec>,
        val refreshCallback: LuaValue? = null,
    ) : ViewSpec()

    data class SwitchItemSpec(
        val title: String,
        val openCallback: LuaValue,
        val closeCallback: LuaValue,
        var checked: Boolean,
    )

    data class SwitchSpec(val items: List<SwitchItemSpec>) : ViewSpec()

    data class MultiChoiceSpec(
        val items: List<String>,
        val callback: LuaValue,
        val selected: MutableSet<Int> = mutableSetOf(),
    ) : ViewSpec()

    data class PromptFieldSpec(
        val label: String,
        val type: String,
        val defaultValue: LuaValue,
        val options: List<String> = emptyList(),
    )

    data class PromptSpec(
        val fields: List<PromptFieldSpec>,
        val callback: LuaValue,
    ) : ViewSpec()

    data class WebSpec(
        val source: String,
        val callbacks: Map<String, LuaValue> = emptyMap(),
    ) : ViewSpec()

    data class TabSpec(
        val title: String,
        val view: ViewSpec,
        val locked: Boolean,
    )

    data class WindowSpec(
        val tabs: MutableList<TabSpec> = mutableListOf(),
        var activeIndex: Int = 0,
    )

    fun viewFromUserdata(value: LuaValue): ViewSpec? {
        return if (value.isuserdata(ViewSpec::class.java)) value.touserdata(ViewSpec::class.java) as? ViewSpec else null
    }

    fun windowFromUserdata(value: LuaValue): WindowSpec? {
        return if (value.isuserdata(WindowSpec::class.java)) value.touserdata(WindowSpec::class.java) as? WindowSpec else null
    }

    fun invokeAsync(callback: LuaValue?, vararg args: LuaValue) {
        if (callback == null || !callback.isfunction()) return
        Thread({
            try {
                when (args.size) {
                    0 -> callback.call()
                    1 -> callback.call(args[0])
                    2 -> callback.call(args[0], args[1])
                    else -> callback.invoke(LuaValue.varargsOf(args))
                }
            } catch (_: Throwable) {
                // 脚本回调异常不应关闭悬浮窗服务。
            }
        }, "agg-lua-callback").apply {
            isDaemon = true
            start()
        }
    }

    fun selectedItemsTable(items: List<String>, selected: Set<Int>): LuaTable {
        return LuaTable().apply {
            var outputIndex = 1
            selected.sorted().forEach { index ->
                items.getOrNull(index)?.let { set(outputIndex++, LuaValue.valueOf(it)) }
            }
        }
    }

    class JavascriptBridge(private val callback: LuaValue) {
        @JavascriptInterface
        fun onClick() = invokeAsync(callback)

        @JavascriptInterface
        fun number(value: Double) = invokeAsync(callback, LuaValue.valueOf(value))

        @JavascriptInterface
        fun string(value: String) = invokeAsync(callback, LuaValue.valueOf(value))

        @JavascriptInterface
        fun bool(value: Boolean) = invokeAsync(callback, LuaValue.valueOf(value))
    }
}
