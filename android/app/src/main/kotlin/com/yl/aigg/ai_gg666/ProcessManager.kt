package com.yl.aigg.ai_gg666

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * AGG 风格运行进程枚举。
 *
 * 优先读取 ARGS/cmdline，避免部分 Android 设备的 NAME 字段截断包名，
 * 导致应用被误判成 Linux 进程并被默认过滤。
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

        // ARGS 一般能返回完整 argv[0]，NAME 在部分 toybox 版本上只保留 comm，可能被截断。
        val argsProcesses = parsePsOutput(
            context = context,
            output = RootManager.executeRootCommand("ps -A -o PID,UID,ARGS 2>/dev/null"),
            hasIdentityColumn = true,
        )
        val nameProcesses = parsePsOutput(
            context = context,
            output = RootManager.executeRootCommand("ps -A -o PID,UID,NAME 2>/dev/null"),
            hasIdentityColumn = true,
        )

        val merged = linkedMapOf<Int, Map<String, Any>>()
        // NAME 先放，ARGS 后放，使完整 cmdline 覆盖截断名称。
        nameProcesses.forEach { merged[it["pid"] as Int] = it }
        argsProcesses.forEach { merged[it["pid"] as Int] = it }

        val processes = if (merged.isNotEmpty()) merged.values.toList() else getProcessListFallback(context)
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
                    // 同一应用优先显示主进程，降低误附加到 :remote/:service 的概率。
                    when {
                        it["isMainProcess"] == true -> 0
                        it["isChildProcess"] == true -> 1
                        else -> 2
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

    private fun resolveApp(rawProcessName: String): Pair<String, AppMeta>? {
        val command = rawProcessName.substringBefore(' ').trim().trimEnd('\u0000')
        val base = command.substringBefore(':')
        appCache[base]?.let { return base to it }

        // 兼容完整包名后带进程后缀，或部分 ps 输出附带额外 argv。
        val packageName = appCache.keys.firstOrNull { candidate ->
            command == candidate || command.startsWith("$candidate:")
        } ?: return null
        return packageName to (appCache[packageName] ?: return null)
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

            val resolved = resolveApp(rawProcessName)
            val packageName = resolved?.first ?: rawProcessName.substringBefore(':')
            if (packageName == context.packageName || rawProcessName == context.packageName) return@forEach

            val meta = resolved?.second
            val isLinux = meta == null
            val suffix = if (meta != null && rawProcessName.startsWith(meta.info.packageName)) {
                rawProcessName.substringAfter(':', "")
            } else ""
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
                "packageName" to packageName,
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

    /** 备用方案：直接遍历 /proc，同时读取 UID 和 cmdline。 */
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
