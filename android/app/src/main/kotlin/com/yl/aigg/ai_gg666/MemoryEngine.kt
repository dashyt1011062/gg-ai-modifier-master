package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 内存引擎 v20.0 - Root Scanner 版
 *
 * - 使用独立的 root 可执行文件进行内存扫描
 * - C++ process_vm_readv/writev 系统调用（有 CAP_SYS_PTRACE）
 * - 2MB 高速缓冲区滑动窗口
 * - 异步执行，不阻塞 UI 线程
 */
object MemoryEngine {

    private const val TAG = "MemoryEngine"
    private const val MAX_RESULTS = 500

    private var attachedPid: Int? = null
    private var activeRegions: List<MemRegion> = emptyList()
    private var lastSnapshot: Map<Long, ByteArray> = emptyMap()
    private val aobDatabase = mutableMapOf<Long, AobSignature>()
    private var appContext: Context? = null

    init {
        try {
            System.loadLibrary("aigg_scanner")
            Log.i(TAG, "✅ Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not loaded (using Root Scanner instead)")
        }
    }

    fun isNativeAvailable(): Boolean = true
    
    /**
     * 设置 Application Context（用于初始化 RootScanner）
     */
    fun setContext(context: Context) {
        appContext = context
    }

    // ==================== 进程管理 ====================

    fun attachProcess(pid: Int): Boolean {
        return try {
            // 检查 Root 权限
            if (!RootManager.checkRootAccess()) {
                Log.e(TAG, "❌ No root access")
                return false
            }
            
            // 初始化 RootScanner
            val ctx = appContext
            if (ctx != null) {
                runBlocking {
                    RootScanner.initialize(ctx)
                }
            }
            
            // 解析 maps
            activeRegions = getRegions(pid)
            if (activeRegions.isEmpty()) {
                Log.e(TAG, "Process $pid has no accessible regions")
                return false
            }

            attachedPid = pid
            lastSnapshot = emptyMap()
            aobDatabase.clear()

            val totalMB = activeRegions.sumOf { it.endAddr - it.startAddr } / 1024 / 1024
            Log.i(TAG, "✅ Attached to process $pid (${activeRegions.size} regions, ${totalMB}MB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to attach: ${e.message}", e)
            false
        }
    }

    fun detachProcess() {
        attachedPid = null
        activeRegions = emptyList()
        lastSnapshot = emptyMap()
        aobDatabase.clear()
        RootScanner.shutdown()
    }

    fun getAttachedPid(): Int? = attachedPid

    // ==================== 内存段解析（通过 Root Shell 一次性读取） ====================

    data class MemRegion(val startAddr: Long, val endAddr: Long, val priority: Int)

    private fun getRegions(pid: Int): List<MemRegion> {
        val mapsResult = RootManager.executeRootCommand("cat /proc/$pid/maps 2>/dev/null") ?: return emptyList()

        val regions = mutableListOf<MemRegion>()
        for (line in mapsResult.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val addrRange = parts[0].split("-")
            if (addrRange.size != 2) continue

            val startAddr = addrRange[0].toLongOrNull(16) ?: continue
            val endAddr = addrRange[1].toLongOrNull(16) ?: continue
            val permissions = parts[1]
            val name = if (parts.size > 5) parts.subList(5, parts.size).joinToString(" ") else ""
            val regionSize = endAddr - startAddr

            if (regionSize <= 0) continue
            if (!permissions.contains('r') || !permissions.contains('w')) continue
            if (name.contains("/dev/ashmem") || name.contains("[anon:vulkan]")) continue
            if (regionSize > 100 * 1024 * 1024) continue

            var priority = 0
            if (permissions.contains('r')) priority += 10
            if (permissions.contains('w')) priority += 20
            when {
                name.contains("[heap]") -> priority += 70
                name.contains("[anon:") -> priority += 50
                name.isEmpty() -> priority += 40
            }

            regions.add(MemRegion(startAddr, endAddr, priority))
        }

        return regions.sortedByDescending { it.priority }
    }

    fun getMemoryRegions(): List<Map<String, Any>> {
        return activeRegions.map { r ->
            mapOf("startAddress" to r.startAddr, "endAddress" to r.endAddr,
                "size" to (r.endAddr - r.startAddr), "priority" to r.priority)
        }
    }

    // ==================== 辅助：获取地址和大小数组（已废弃，保留用于兼容） ====================

    // ==================== 搜索 ====================

    fun searchExact(value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (activeRegions.isEmpty()) return emptyList()

        return try {
            val targetBytes = valueToBytes(value, type) ?: return emptyList()
            val typeSize = getTypeSize(type)

            val targetHex = targetBytes.joinToString(" ") { String.format("%02X", it) }
            val totalMB = activeRegions.sumOf { it.endAddr - it.startAddr } / 1024 / 1024
            Log.d(TAG, "🎯 搜索值: $value, 类型: $type, Hex: [$targetHex]")
            Log.d(TAG, "📊 段数: ${activeRegions.size}, 总体积: ${totalMB}MB")

            val startTime = System.currentTimeMillis()

            // 使用 RootScanner（异步执行）
            val addresses = runBlocking {
                RootScanner.searchExact(pid, activeRegions, typeSize, targetBytes)
            }

            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            Log.d(TAG, "⚡ searchExact: ${addresses.size} results in ${String.format("%.2f", elapsed)}s")

            val results = addresses.map { addr -> createResultMap(addr, value, type) }
            enrichWithMachineCode(pid, results)
            saveSnapshot(results, type)
            results
        } catch (e: Exception) {
            Log.e(TAG, "searchExact failed: ${e.message}", e)
            emptyList()
        }
    }

    fun searchByRange(minValue: Long, maxValue: Long, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (activeRegions.isEmpty()) return emptyList()

        return try {
            val typeSize = getTypeSize(type)

            val addresses = runBlocking {
                RootScanner.searchRange(pid, activeRegions, typeSize, minValue, maxValue)
            }
            
            val placeholder: Any = if (type == "float") minValue.toFloat() else minValue
            val results = addresses.map { addr -> createResultMap(addr, placeholder, type) }
            enrichWithMachineCode(pid, results)
            saveSnapshot(results, type)
            results
        } catch (e: Exception) { emptyList() }
    }

    fun filterResults(previousAddresses: List<Long>, value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        return try {
            val typeSize = getTypeSize(type)
            val targetBytes = valueToBytes(value, type) ?: return emptyList()

            // 使用模糊搜索的逻辑来过滤
            val results = mutableListOf<MutableMap<String, Any>>()
            for (addr in previousAddresses) {
                val bytes = runBlocking {
                    RootScanner.readMemory(pid, addr, typeSize)
                }
                if (bytes != null && bytes.contentEquals(targetBytes)) {
                    results.add(createResultMap(addr, value, type))
                }
            }

            if (results.isNotEmpty()) {
                enrichWithMachineCode(pid, results)
                saveSnapshot(results, type)
            }
            results
        } catch (e: Exception) { emptyList() }
    }

    fun searchFuzzy(comparison: String, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()

        if (lastSnapshot.isEmpty()) {
            val initialResults = searchAllValues(type)
            saveSnapshot(initialResults, type)
            return initialResults
        }

        return try {
            val typeSize = getTypeSize(type)
            val addresses = lastSnapshot.keys.toList()
            val oldValues = ByteArray(addresses.size * typeSize)
            
            // 构建旧值数组
            addresses.forEachIndexed { index, addr ->
                val bytes = lastSnapshot[addr] ?: return@forEachIndexed
                System.arraycopy(bytes, 0, oldValues, index * typeSize, typeSize)
            }
            
            val mode = when (comparison) {
                "changed" -> 0
                "unchanged" -> 1
                "increased" -> 2
                "decreased" -> 3
                else -> 0
            }
            
            val resultAddrs = runBlocking {
                RootScanner.searchFuzzy(pid, addresses, oldValues, mode, typeSize)
            }
            
            val results = resultAddrs.map { addr ->
                val bytes = runBlocking {
                    RootScanner.readMemory(pid, addr, typeSize)
                }
                val value = if (bytes != null) bytesToValue(bytes, type) else 0
                createResultMap(addr, value ?: 0, type)
            }
            enrichWithMachineCode(pid, results)
            saveSnapshot(results, type)
            results
        } catch (e: Exception) { emptyList() }
    }

    fun searchAob(pattern: String, mask: String? = null): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (activeRegions.isEmpty()) return emptyList()

        // 检测是否为地址格式（0x开头的单个十六进制数）
        val addrLong = parseAddress(pattern)
        if (addrLong != null) {
            return readAddressValues(pid, addrLong)
        }

        return try {
            val (patternBytes, maskBytes) = parseAobPattern(pattern)
            if (patternBytes.isEmpty()) return emptyList()

            // 如果外部传了 mask，覆盖内部生成的
            val finalMask = if (mask != null) {
                ByteArray(patternBytes.size) { i ->
                    if (i < mask.length && mask[i] == '?') 0.toByte() else maskBytes[i]
                }
            } else maskBytes

            val addresses = runBlocking {
                RootScanner.searchAob(pid, activeRegions, patternBytes, finalMask)
            }

            addresses.map { addr ->
                val ctx = runBlocking {
                    RootScanner.readMemory(pid, addr - 16, patternBytes.size + 32)
                }
                if (ctx != null) aobDatabase[addr] = AobSignature(addr, pattern, ctx, 16)
                val mc = runBlocking { RootScanner.readMemory(pid, addr, 8) }
                val mcStr = mc?.joinToString(" ") { String.format("%02X", it) } ?: ""
                // 读取该地址的实际值（dword）
                val valBytes = runBlocking { RootScanner.readMemory(pid, addr, 4) }
                val actualValue: Any = if (valBytes != null) bytesToValue(valBytes, "dword") ?: 0 else 0
                mapOf("address" to "0x${addr.toString(16).uppercase()}", "addressInt" to addr,
                    "value" to actualValue, "type" to "aob", "isFavorite" to false, "isFrozen" to false,
                    "machineCode" to mcStr)
            }
        } catch (e: Exception) { emptyList() }
    }

    // 解析地址格式，返回 Long 或 null
    private fun parseAddress(input: String): Long? {
        val s = input.trim()
        return when {
            s.startsWith("0x", ignoreCase = true) -> s.substring(2).toLongOrNull(16)
            // 纯十六进制且长度>=6（至少3字节地址）也视为地址
            s.length >= 6 && s.all { it.isDigit() || it in "abcdefABCDEF" } -> s.toLongOrNull(16)
            else -> null
        }
    }

    // 读取指定地址处的各种类型值
    private fun readAddressValues(pid: Int, address: Long): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        // 读取机器码
        val mc = runBlocking { RootScanner.readMemory(pid, address, 8) }
        val mcStr = mc?.joinToString(" ") { String.format("%02X", it) } ?: ""
        // 读取多种类型的值
        val types = listOf("dword" to 4, "float" to 4, "double" to 8, "word" to 2, "byte" to 1)
        for ((type, size) in types) {
            try {
                val bytes = runBlocking { RootScanner.readMemory(pid, address, size) }
                if (bytes != null && bytes.size == size) {
                    val value = bytesToValue(bytes, type)
                    if (value != null) {
                        results.add(mapOf(
                            "address" to "0x${address.toString(16).uppercase()}",
                            "addressInt" to address,
                            "value" to value,
                            "type" to type,
                            "isFavorite" to false,
                            "isFrozen" to false,
                            "machineCode" to mcStr
                        ))
                    }
                }
            } catch (_: Exception) {}
        }
        return results
    }

    fun relocateAobSignatures(): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (aobDatabase.isEmpty() || activeRegions.isEmpty()) return emptyList()

        return try {
            val results = mutableListOf<Map<String, Any>>()

            for ((address, sig) in aobDatabase) {
                val mask = ByteArray(sig.contextBytes.size) { 1.toByte() }
                val found = runBlocking {
                    RootScanner.searchAob(pid, activeRegions, sig.contextBytes, mask)
                }
                if (found.isNotEmpty()) {
                    val newAddr = found[0] + sig.contextOffset
                    val mc = runBlocking { RootScanner.readMemory(pid, newAddr, 8) }
                    val mcStr = mc?.joinToString(" ") { String.format("%02X", it) } ?: ""
                    val valBytes = runBlocking { RootScanner.readMemory(pid, newAddr, 4) }
                    val actualValue: Any = if (valBytes != null) bytesToValue(valBytes, "dword") ?: 0 else 0
                    results.add(mapOf("address" to "0x${newAddr.toString(16).uppercase()}", "addressInt" to newAddr,
                        "value" to actualValue, "type" to "aob", "isFavorite" to false, "isFrozen" to false,
                        "relocated" to true, "oldAddress" to "0x${address.toString(16).uppercase()}",
                        "machineCode" to mcStr))
                }
            }
            results
        } catch (e: Exception) { emptyList() }
    }

    // ==================== 内存读写 ====================

    fun readMemory(address: Long, type: String): Any? {
        val pid = attachedPid ?: return null
        return try {
            val typeSize = getTypeSize(type)
            val bytes = runBlocking {
                RootScanner.readMemory(pid, address, typeSize)
            } ?: return null
            bytesToValue(bytes, type)
        } catch (e: Exception) {
            Log.e(TAG, "readMemory failed: ${e.message}")
            null
        }
    }

    // 兼容旧调用
    fun readMemory(address: Int, type: String): Any? = readMemory(address.toLong(), type)

    fun writeMemory(address: Long, value: Any, type: String): Boolean {
        val pid = attachedPid ?: return false
        return try {
            val bytes = valueToBytes(value, type) ?: return false
            runBlocking {
                RootScanner.writeMemory(pid, address, bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeMemory failed: ${e.message}")
            false
        }
    }

    // 兼容旧调用
    fun writeMemory(address: Int, value: Any, type: String): Boolean = writeMemory(address.toLong(), value, type)

    fun writeBatch(requests: List<Map<String, Any>>): Boolean {
        var ok = true
        for (req in requests) {
            val addr = (req["address"] as? Number)?.toLong() ?: continue
            val v = req["value"] ?: continue
            val t = req["type"] as? String ?: "dword"
            if (!writeMemory(addr, v, t)) ok = false
        }
        return ok
    }

    fun analyzeMemoryRegion(address: Long, range: Int): Map<String, Any> {
        val pid = attachedPid ?: return emptyMap()
        return try {
            val data = runBlocking {
                RootScanner.readMemory(pid, (address - range).coerceAtLeast(0), range * 2)
            } ?: return emptyMap()
            mapOf("address" to address, "range" to range, "data" to data.joinToString("") { "%02x".format(it) }, "size" to data.size)
        } catch (e: Exception) { emptyMap() }
    }

    // 兼容旧调用
    fun analyzeMemoryRegion(address: Int, range: Int): Map<String, Any> = analyzeMemoryRegion(address.toLong(), range)

    private fun searchAllValues(type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (activeRegions.isEmpty()) return emptyList()

        return try {
            val typeSize = getTypeSize(type)

            val addresses = runBlocking {
                RootScanner.searchRange(pid, activeRegions, typeSize, Long.MIN_VALUE, Long.MAX_VALUE)
            }
            
            val placeholder: Any = if (type == "float") 0.0f else if (type == "double") 0.0 else 0
            addresses.map { addr -> createResultMap(addr, placeholder, type) }
        } catch (e: Exception) { emptyList() }
    }

    // ==================== 快照 ====================

    private fun saveSnapshot(results: List<Map<String, Any>>, type: String) {
        val pid = attachedPid ?: return
        val typeSize = getTypeSize(type)
        val addresses = results.mapNotNull { (it["addressInt"] as? Number)?.toLong() }
        if (addresses.isEmpty()) return

        val snapshot = mutableMapOf<Long, ByteArray>()
        for (addr in addresses) {
            val b = runBlocking {
                RootScanner.readMemory(pid, addr, typeSize)
            }
            if (b != null) snapshot[addr] = b
        }
        lastSnapshot = snapshot
    }

    // ==================== 工具函数 ====================

    // 解析 AOB 特征码，返回 Pair(patternBytes, maskBytes)，mask=1精确匹配，0=通配
    private fun parseAobPattern(input: String): Pair<ByteArray, ByteArray> {
        var raw = input.trim()
        // 去除 0x/0X 前缀
        if (raw.startsWith("0x", ignoreCase = true)) raw = raw.substring(2)

        // 按空格分割，如果没有空格则每2字符分割（但 ?? 需特殊处理）
        val tokens: List<String> = if (raw.contains(" ")) {
            raw.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        } else {
            // 连续格式：先把 ?? 提出来，再每2字符分割
            val result = mutableListOf<String>()
            var j = 0
            while (j < raw.length) {
                if (j + 1 < raw.length && raw[j] == '?' && raw[j + 1] == '?') {
                    result.add("??")
                    j += 2
                } else if (j + 1 < raw.length) {
                    result.add(raw.substring(j, j + 2))
                    j += 2
                } else {
                    j++ // 跳过奇数末尾
                }
            }
            result
        }

        val patternBytes = mutableListOf<Byte>()
        val maskBytes = mutableListOf<Byte>()
        for (token in tokens) {
            if (token == "??") {
                patternBytes.add(0)
                maskBytes.add(0) // 通配
            } else {
                patternBytes.add(token.toInt(16).toByte())
                maskBytes.add(1) // 精确匹配
            }
        }
        return Pair(patternBytes.toByteArray(), maskBytes.toByteArray())
    }

    fun getTypeSize(type: String): Int = when (type) {
        "byte" -> 1; "word" -> 2; "dword" -> 4; "qword" -> 8; "float" -> 4; "double" -> 8; else -> 4
    }

    private fun valueToBytes(value: Any, type: String): ByteArray? {
        return try {
            val num = value as? Number ?: return null
            when (type) {
                "byte" -> byteArrayOf(num.toInt().toByte())
                "word" -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(num.toShort()).array()
                "dword" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num.toInt()).array()
                "qword" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(num.toLong()).array()
                "float" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(num.toFloat()).array()
                "double" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(num.toDouble()).array()
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun bytesToValue(bytes: ByteArray, type: String): Any? {
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            when (type) {
                "byte" -> bytes[0].toInt() and 0xFF
                "word" -> buf.short.toInt() and 0xFFFF
                "dword" -> buf.int; "qword" -> buf.long
                "float" -> buf.float; "double" -> buf.double
                else -> null
            }
        } catch (e: Exception) { null }
    }

    private fun valuesEqual(a: Any, b: Any, type: String): Boolean = try {
        when (type) {
            "float" -> Math.abs((a as Number).toFloat() - (b as Number).toFloat()) < 0.001
            "double" -> Math.abs((a as Number).toDouble() - (b as Number).toDouble()) < 0.0001
            else -> (a as? Number)?.toLong() == (b as? Number)?.toLong()
        }
    } catch (e: Exception) { false }

    private fun compareValues(a: Any, b: Any, type: String): Int = try {
        when (type) {
            "float" -> (a as Number).toFloat().compareTo((b as Number).toFloat())
            "double" -> (a as Number).toDouble().compareTo((b as Number).toDouble())
            else -> (a as Number).toLong().compareTo((b as Number).toLong())
        }
    } catch (e: Exception) { 0 }

    private fun createResultMap(address: Long, value: Any, type: String): MutableMap<String, Any> = mutableMapOf(
        "address" to "0x${address.toString(16).uppercase()}", "addressInt" to address,
        "value" to value, "type" to type, "isFavorite" to false, "isFrozen" to false
    )

    // 为搜索结果批量读取机器码（地址处的原始字节）
    private fun enrichWithMachineCode(pid: Int, results: List<MutableMap<String, Any>>) {
        for (r in results) {
            val addr = r["addressInt"] as? Long ?: continue
            try {
                val bytes = runBlocking { RootScanner.readMemory(pid, addr, 8) }
                if (bytes != null) {
                    r["machineCode"] = bytes.joinToString(" ") { String.format("%02X", it) }
                }
            } catch (_: Exception) {}
        }
    }

    data class AobSignature(val address: Long, val pattern: String, val contextBytes: ByteArray, val contextOffset: Int)
}
