package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object PerfBreakpointManager {
    private const val TAG = "PerfBreakpointManager"
    private const val HELPER_NAME = "perf_bp_root"

    data class PerfResult(
        val status: String,
        val tid: Int = 0,
        val threads: Int = 0,
        val count: Long = 0L,
        val message: String = "",
        val errno: Int = 0,
    ) {
        val hit: Boolean get() = status == "hit"
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    fun ensureHelper(context: Context): String? {
        val dest = File("/data/local/tmp", HELPER_NAME)
        val temp = File(context.cacheDir, HELPER_NAME)
        return try {
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val libDir = context.applicationInfo.nativeLibraryDir
            val direct = File(libDir, HELPER_NAME)
            val renamed = File(libDir, "lib${HELPER_NAME}.so")
            val source = when {
                direct.exists() -> direct
                renamed.exists() -> renamed
                else -> null
            }
            if (source != null) {
                RootManager.executeRootCommand("cp ${quote(source.absolutePath)} ${quote(dest.absolutePath)} && chmod 755 ${quote(dest.absolutePath)}")
                return dest.absolutePath
            }

            val assetPath = "native/$abi/$HELPER_NAME"
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                val apkAssetPath = "assets/$assetPath"
                java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                    val entry = zip.getEntry(apkAssetPath) ?: return null
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                }
            }
            RootManager.executeRootCommand("cp ${quote(temp.absolutePath)} ${quote(dest.absolutePath)} && chmod 755 ${quote(dest.absolutePath)}")
            temp.delete()
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "ensureHelper failed: ${e.message}", e)
            null
        }
    }

    fun probe(context: Context): PerfResult {
        val helper = ensureHelper(context) ?: return PerfResult("error", message = "helper not found")
        val raw = RootManager.executeRootCommand("${quote(helper)} probe") ?: return PerfResult("error", message = "no root output")
        return parse(raw)
    }

    fun waitForHit(
        context: Context,
        pid: Int,
        address: Long,
        length: Int,
        mode: String,
        timeoutMs: Int,
    ): PerfResult {
        val helper = ensureHelper(context) ?: return PerfResult("error", message = "helper not found")
        val safeLen = when (length) { 1, 2, 4, 8 -> length else -> 4 }
        val safeMode = when (mode.lowercase()) { "r", "w", "rw", "wr", "x", "exec" -> mode.lowercase() else -> "w" }
        val cmd = "${quote(helper)} wait $pid 0x${address.toString(16)} $safeLen $safeMode ${timeoutMs.coerceIn(1, 60000)}"
        val raw = RootManager.executeRootCommand(cmd) ?: return PerfResult("error", message = "no root output")
        return parse(raw)
    }

    private fun parse(raw: String): PerfResult {
        val line = raw.lineSequence().lastOrNull { it.trim().startsWith("{") }?.trim().orEmpty()
        if (line.isEmpty()) return PerfResult("error", message = raw.take(180))
        return try {
            val json = JSONObject(line)
            PerfResult(
                status = json.optString("status", "error"),
                tid = json.optInt("tid", 0),
                threads = json.optInt("threads", 0),
                count = json.optLong("count", 0L),
                message = json.optString("message", json.optString("stage", "")),
                errno = json.optInt("errno", 0),
            )
        } catch (e: Exception) {
            PerfResult("error", message = line.take(180))
        }
    }
}
