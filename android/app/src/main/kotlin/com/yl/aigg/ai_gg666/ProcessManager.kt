package com.yl.aigg.ai_gg666

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * AGG 风格运行进程枚举。
 *
 * 分类规则与 AGG 的“进程过滤”含义保持一致：
 * - 系统应用进程：能映射到已安装 APK，且 ApplicationInfo 带系统应用标志。
 * - Linux 进程：无法映射到已安装 APK 的原生/守护进程。
 * - 应用子进程（package:remote）仍归属对应 APK，不视为 Linux 进程。
 */
object ProcessManager {

    private data class AppMeta(
        val info: ApplicationInfo,
        val label: String,
        val isSystem: Boolean,
    )

    @Volatile
    private var appCache: Map<String, AppMeta> = emptyMap()

    fun getProcessList(context: Context): List<Map<String, Any>> {
        val pm = context.packageManager
        refreshAppCache(pm)

        val primary = parsePsOutput(
            context = context,
            output = RootManager.executeRootCommand("ps -A -o PID,UID,NAME 2>/dev/null"),
            hasIdentityColumn = true,
        )
        val processes = if (primary.isNotEmpty()) primary else getProcessListFallback(context)

        return processes
            .distinctBy { it["pid"] as Int }
            .sortedWith(
                compareBy<Map<String, Any>> {
                    when {
                        it["isLinux"] == true -> 2
                        it["isSystem"] == true -> 1
                        else -> 0
                    }
                }.thenBy {
                    val name = it["processName"]?.toString().orEmpty()
                    if (name.firstOrNull()?.code?.let { code -> code > 127 } == true) 0 else 1
                }.thenBy { it["processName"]?.toString().orEmpty().lowercase() }
                    .thenBy { it["pid"] as Int }
            )
    }

    private fun refreshAppCache(pm: PackageManager) {
        appCache = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).associate { info ->
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                info.packageName to AppMeta(
                    info = info,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    isSystem = isSystem,
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parsePsOutput(
        context: Context,
        output: String?,
        hasIdentityColumn: Boolean,
    ): MutableList<Map<String, Any>> {
        val processes = mutableListOf<Map<String, Any>>()
        if (output.isNullOrBlank()) return processes

        output.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            if (line.isBlank() || line.startsWith("PID", ignoreCase = true)) return@forEach

            val parts = line.split(Regex("\\s+"), limit = if (hasIdentityColumn) 3 else 2)
            val required = if (hasIdentityColumn) 3 else 2
            if (parts.size < required) return@forEach

            val pid = parts[0].toIntOrNull() ?: return@forEach
            val identity = if (hasIdentityColumn) parts[1] else ""
            val rawProcessName = parts[if (hasIdentityColumn) 2 else 1]
                .trim()
                .substringBefore(' ')
                .trimEnd('\u0000')
            if (rawProcessName.isBlank()) return@forEach

            val baseName = rawProcessName.substringBefore(':')
            if (baseName == context.packageName || rawProcessName == context.packageName) return@forEach

            val meta = appCache[baseName]
            val isLinux = meta == null
            val suffix = rawProcessName.substringAfter(':', "")
            val displayName = when {
                meta == null -> rawProcessName
                suffix.isBlank() -> meta.label
                else -> "${meta.label} · $suffix"
            }
            val uid = identity.toIntOrNull() ?: -1

            processes += mapOf(
                "pid" to pid,
                "uid" to uid,
                "user" to identity,
                "packageName" to if (meta == null) baseName else meta.info.packageName,
                "rawProcessName" to rawProcessName,
                "processName" to displayName,
                "appLabel" to (meta?.label ?: rawProcessName),
                "isSystem" to (meta?.isSystem == true),
                "isLinux" to isLinux,
                "isAppProcess" to !isLinux,
                "isMainProcess" to (meta != null && rawProcessName == meta.info.packageName),
                "isChildProcess" to (meta != null && rawProcessName.startsWith("${meta.info.packageName}:")),
            )
        }
        return processes
    }

    /**
     * 备用方案：直接遍历 /proc，同时读取 UID 和 cmdline。
     */
    private fun getProcessListFallback(context: Context): List<Map<String, Any>> {
        val shellCommand = """
            for d in /proc/[0-9]*; do
              p=${'$'}{d##*/}
              c=${'$'}(tr '\0' ' ' < "${'$'}d/cmdline" 2>/dev/null | sed 's/ *${'$'}//')
              [ -z "${'$'}c" ] && c=${'$'}(cat "${'$'}d/comm" 2>/dev/null)
              u=${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' "${'$'}d/status" 2>/dev/null)
              [ -n "${'$'}c" ] && printf '%s %s %s\n' "${'$'}p" "${'$'}u" "${'$'}c"
            done
        """.trimIndent()
        val output = RootManager.executeRootCommand(shellCommand)
        return parsePsOutput(context, output, hasIdentityColumn = true)
    }
}
