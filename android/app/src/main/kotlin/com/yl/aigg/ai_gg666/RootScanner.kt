package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*

/**
 * Root Scanner - 通过独立可执行文件进行内存扫描
 * 
 * 设计：
 * - scanner_root 可执行文件通过 su 以 root 权限运行
 * - 使用 process_vm_readv/writev 系统调用（有 CAP_SYS_PTRACE）
 * - 通过 stdin/stdout 进行 JSON 通信
 * - 异步执行，不阻塞 UI 线程
 */
object RootScanner {
    
    private const val TAG = "RootScanner"
    private const val SCANNER_NAME = "scanner_root"
    
    private var scannerProcess: Process? = null
    private var scannerWriter: BufferedWriter? = null
    private var scannerReader: BufferedReader? = null
    
    /**
     * 初始化 Root Scanner（部署可执行文件并启动）
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 从 assets 或 native lib 目录复制可执行文件到 /data/local/tmp
            val scannerPath = extractScanner(context)
            if (scannerPath == null) {
                Log.e(TAG, "Failed to extract scanner executable")
                return@withContext false
            }
            
            // 2. 通过 su 启动 scanner_root
            val suProcess = Runtime.getRuntime().exec("su")
            val suWriter = BufferedWriter(OutputStreamWriter(suProcess.outputStream))
            
            // 设置权限并启动
            suWriter.write("chmod 755 $scannerPath\n")
            suWriter.write("$scannerPath\n")
            suWriter.flush()
            
            scannerProcess = suProcess
            scannerWriter = suWriter
            scannerReader = BufferedReader(InputStreamReader(suProcess.inputStream))
            
            Log.i(TAG, "✅ Root Scanner initialized at $scannerPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Root Scanner: ${e.message}", e)
            false
        }
    }
    
    /**
     * 提取 scanner_root 可执行文件
     */
    private fun extractScanner(context: Context): String? {
        try {
            val abi = android.os.Build.SUPPORTED_ABIS[0]
            val libPath = context.applicationInfo.nativeLibraryDir
            val scannerDest = File("/data/local/tmp", SCANNER_NAME)
            // 先写到应用私有目录，再用 su 复制到 /data/local/tmp
            val tempFile = File(context.cacheDir, SCANNER_NAME)

            // 1. 从 native lib 目录查找（直接名）
            var scannerSrc = File(libPath, SCANNER_NAME)
            Log.d(TAG, "Looking for scanner in nativeLibraryDir: $libPath")

            // 2. 如果不存在，查找 libscanner_root.so（jniLibs 会被 Android 重命名）
            if (!scannerSrc.exists()) {
                scannerSrc = File(libPath, "lib${SCANNER_NAME}.so")
            }

            // 如果源文件存在，通过 su 复制到 /data/local/tmp
            if (scannerSrc.exists()) {
                val result = RootManager.executeRootCommand("cp ${scannerSrc.absolutePath} ${scannerDest.absolutePath} && chmod 755 ${scannerDest.absolutePath}")
                Log.i(TAG, "Extracted scanner from nativeLibraryDir: ${scannerSrc.name}")
                return scannerDest.absolutePath
            }

            // 3. 从 assets 复制到临时目录，再用 su 复制到 /data/local/tmp
            val assetPath = "native/$abi/$SCANNER_NAME"
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "Read scanner from assets to temp: ${tempFile.absolutePath} (${tempFile.length()} bytes)")

                // 用 su 复制到 /data/local/tmp 并设置权限
                val result = RootManager.executeRootCommand("cp ${tempFile.absolutePath} ${scannerDest.absolutePath} && chmod 755 ${scannerDest.absolutePath}")
                tempFile.delete()

                if (scannerDest.exists() || result != null) {
                    Log.i(TAG, "Extracted scanner to ${scannerDest.absolutePath}")
                    return scannerDest.absolutePath
                } else {
                    Log.w(TAG, "su copy may have failed, trying direct su exec")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Assets extraction failed: $assetPath - ${e.javaClass.simpleName}: ${e.message}")
            }

            // 4. 尝试从 APK 中直接读取（绕过 AssetManager）
            try {
                val apkPath = context.applicationInfo.sourceDir
                val apkAssetPath = "assets/$assetPath" // APK 中的实际路径带 assets/ 前缀
                java.util.zip.ZipFile(apkPath).use { zip ->
                    val entry = zip.getEntry(apkAssetPath)
                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(tempFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "Read scanner from APK zip to temp (${tempFile.length()} bytes)")

                        val result = RootManager.executeRootCommand("cp ${tempFile.absolutePath} ${scannerDest.absolutePath} && chmod 755 ${scannerDest.absolutePath}")
                        tempFile.delete()
                        return scannerDest.absolutePath
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK zip extraction failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            Log.e(TAG, "Scanner not found in any location")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "extractScanner failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 发送命令并接收响应
     */
    private suspend fun sendCommand(json: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val writer = scannerWriter ?: return@withContext null
            val reader = scannerReader ?: return@withContext null
            
            writer.write(json)
            writer.write("\n")
            writer.flush()
            
            val response = reader.readLine() ?: return@withContext null
            JSONObject(response)
        } catch (e: Exception) {
            Log.e(TAG, "sendCommand failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * 精确搜索
     */
    suspend fun searchExact(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        typeSize: Int,
        targetBytes: ByteArray
    ): List<Long> = withContext(Dispatchers.IO) {
        try {
            val targetHex = targetBytes.joinToString("") { "%02x".format(it) }
            val regionsJson = regions.joinToString(",") { 
                "{\"start\":${it.startAddr},\"size\":${it.endAddr - it.startAddr}}" 
            }
            
            val json = """{"cmd":"search_exact","pid":$pid,"regions":[$regionsJson],"type_size":$typeSize,"target":"$targetHex"}"""
            
            val response = sendCommand(json) ?: return@withContext emptyList()
            
            if (response.getString("status") != "ok") {
                return@withContext emptyList()
            }
            
            val addrsStr = response.getString("addrs")
            if (addrsStr.isEmpty()) return@withContext emptyList()
            
            addrsStr.split(",").mapNotNull { it.toLongOrNull(16) }
        } catch (e: Exception) {
            Log.e(TAG, "searchExact failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 范围搜索
     */
    suspend fun searchRange(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        typeSize: Int,
        lowBound: Long,
        highBound: Long
    ): List<Long> = withContext(Dispatchers.IO) {
        try {
            val regionsJson = regions.joinToString(",") { 
                "{\"start\":${it.startAddr},\"size\":${it.endAddr - it.startAddr}}" 
            }
            
            val json = """{"cmd":"search_range","pid":$pid,"regions":[$regionsJson],"type_size":$typeSize,"low":$lowBound,"high":$highBound}"""
            
            val response = sendCommand(json) ?: return@withContext emptyList()
            
            if (response.optString("status", "") != "ok") {
                return@withContext emptyList()
            }

            val addrsStr = response.optString("addrs", "")
            if (addrsStr.isEmpty()) return@withContext emptyList()

            addrsStr.split(",").mapNotNull { it.toLongOrNull(16) }
        } catch (e: Exception) {
            Log.e(TAG, "searchRange failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * AOB 特征码搜索
     */
    suspend fun searchAob(
        pid: Int,
        regions: List<MemoryEngine.MemRegion>,
        pattern: ByteArray,
        mask: ByteArray
    ): List<Long> = withContext(Dispatchers.IO) {
        try {
            val patternHex = pattern.joinToString("") { "%02x".format(it) }
            val maskHex = mask.joinToString("") { "%02x".format(it) }
            val regionsJson = regions.joinToString(",") { 
                "{\"start\":${it.startAddr},\"size\":${it.endAddr - it.startAddr}}" 
            }
            
            val json = """{"cmd":"search_aob","pid":$pid,"regions":[$regionsJson],"pattern":"$patternHex","mask":"$maskHex"}"""
            
            val response = sendCommand(json) ?: return@withContext emptyList()
            
            if (response.getString("status") != "ok") {
                return@withContext emptyList()
            }
            
            val addrsStr = response.getString("addrs")
            if (addrsStr.isEmpty()) return@withContext emptyList()
            
            addrsStr.split(",").mapNotNull { it.toLongOrNull(16) }
        } catch (e: Exception) {
            Log.e(TAG, "searchAob failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 模糊搜索
     */
    suspend fun searchFuzzy(
        pid: Int,
        addresses: List<Long>,
        oldValues: ByteArray,
        mode: Int,
        typeSize: Int
    ): List<Long> = withContext(Dispatchers.IO) {
        try {
            val addrsStr = addresses.joinToString(",") { it.toString(16) }
            val valsHex = oldValues.joinToString("") { "%02x".format(it) }
            
            val json = """{"cmd":"search_fuzzy","pid":$pid,"addrs":[$addrsStr],"old_vals":"$valsHex","mode":$mode,"type_size":$typeSize}"""
            
            val response = sendCommand(json) ?: return@withContext emptyList()
            
            if (response.optString("status", "") != "ok") {
                return@withContext emptyList()
            }

            val resultAddrsStr = response.optString("addrs", "")
            if (resultAddrsStr.isEmpty()) return@withContext emptyList()

            resultAddrsStr.split(",").mapNotNull { it.toLongOrNull(16) }
        } catch (e: Exception) {
            Log.e(TAG, "searchFuzzy failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 读取内存
     */
    suspend fun readMemory(pid: Int, address: Long, size: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val json = """{"cmd":"read","pid":$pid,"addr":$address,"size":$size}"""
            
            val response = sendCommand(json) ?: return@withContext null
            
            if (response.optString("status", "") != "ok") {
                return@withContext null
            }

            val dataHex = response.optString("data", "")
            if (dataHex.isEmpty()) return@withContext null
            val bytes = ByteArray(dataHex.length / 2)
            for (i in bytes.indices) {
                val hex = dataHex.substring(i * 2, i * 2 + 2)
                bytes[i] = hex.toInt(16).toByte()
            }
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed: ${e.message}", e)
            null
        }
    }
    
    /**
     * 写入内存
     */
    suspend fun writeMemory(pid: Int, address: Long, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val dataHex = data.joinToString("") { "%02x".format(it) }
            val json = """{"cmd":"write","pid":$pid,"addr":$address,"data":"$dataHex"}"""
            
            val response = sendCommand(json) ?: return@withContext false
            
            response.getString("status") == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "writeMemory failed: ${e.message}", e)
            false
        }
    }
    
    /**
     * 关闭 Scanner
     */
    fun shutdown() {
        try {
            scannerWriter?.close()
            scannerReader?.close()
            scannerProcess?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown failed: ${e.message}")
        }
        scannerProcess = null
        scannerWriter = null
        scannerReader = null
    }
}
