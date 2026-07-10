package com.yl.aigg.ai_gg666

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
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
    private var lastSnapshotType: String? = null
    private val aobDatabase = mutableMapOf<Long, AobSignature>()
    private var appContext: Context? = null
    private var lastLivenessCheckAt = 0L
    private var lastLivenessResult = false
    private val regionCategoryLabels = linkedMapOf(
        "anonymous" to "匿名内存",
        "heap" to "原生堆",
        "java" to "Java / Ashmem",
        "stack" to "线程栈",
        "app" to "应用代码与数据",
        "system" to "系统代码与数据",
        "other" to "其他可写区域",
    )
    private var selectedRegionCategories: Set<String> = regionCategoryLabels.keys.toSet()

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

    @Synchronized
    fun attachProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        if (attachedPid == pid && activeRegions.isNotEmpty() && isAttachedProcessAlive(force = true)) {
            return true
        }

        return try {
            if (!RootManager.checkRootAccess()) {
                Log.e(TAG, "❌ No root access")
                return false
            }
            if (!isPidAlive(pid)) {
                Log.e(TAG, "❌ Process $pid is not running")
                return false
            }

            // AGG 在切换进程时会清空旧搜索/保存状态；这里同样避免旧地址污染新进程。
            MemoryFreezer.clearAll()
            RootScanner.shutdown()
            attachedPid = null
            activeRegions = emptyList()
            lastSnapshot = emptyMap()
            lastSnapshotType = null
            aobDatabase.clear()

            val ctx = appContext ?: return false
            val scannerReady = runBlocking { RootScanner.initialize(ctx) }
            if (!scannerReady) {
                Log.e(TAG, "❌ Root scanner initialization failed")
                return false
            }

            val regions = getRegions(pid)
            if (regions.isEmpty()) {
                RootScanner.shutdown()
                Log.e(TAG, "Process $pid has no accessible regions")
                return false
            }

            activeRegions = regions
            attachedPid = pid
            lastLivenessCheckAt = System.currentTimeMillis()
            lastLivenessResult = true

            val totalMB = activeRegions.sumOf { it.endAddr - it.startAddr } / 1024 / 1024
            Log.i(TAG, "✅ Attached to process $pid (${activeRegions.size} regions, ${totalMB}MB)")
            true
        } catch (e: Exception) {
            RootScanner.shutdown()
            attachedPid = null
            activeRegions = emptyList()
            Log.e(TAG, "❌ Failed to attach: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun detachProcess() {
        MemoryFreezer.clearAll()
        attachedPid = null
        activeRegions = emptyList()
        lastSnapshot = emptyMap()
        lastSnapshotType = null
        aobDatabase.clear()
        lastLivenessResult = false
        lastLivenessCheckAt = 0L
        RootScanner.shutdown()
    }

    fun getAttachedPid(): Int? = attachedPid

    @Synchronized
    fun resetSearchState() {
        lastSnapshot = emptyMap()
        lastSnapshotType = null
        aobDatabase.clear()
    }

    fun isAttachedProcessAlive(force: Boolean = false): Boolean {
        val pid = attachedPid ?: return false
        val now = System.currentTimeMillis()
        if (!force && now - lastLivenessCheckAt < 1000L) return lastLivenessResult
        lastLivenessCheckAt = now
        lastLivenessResult = isPidAlive(pid)
        return lastLivenessResult
    }

    fun isSupportedType(type: String): Boolean = type in setOf(
        "byte", "word", "dword", "qword", "float", "double"
    )

    private fun isPidAlive(pid: Int): Boolean {
        if (File("/proc/$pid").exists()) return true
        return RootManager.executeRootCommand(
            "if [ -d /proc/$pid ]; then echo alive; else echo dead; fi"
        )?.trim() == "alive"
    }

    // ==================== 内存段解析（通过 Root Shell 一次性读取） ====================

    data class MemRegion(
        val startAddr: Long,
        val endAddr: Long,
        val priority: Int,
        val permissions: String,
        val name: String,
        val category: String,
    )

    private fun classifyRegion(name: String): String {
        val normalized = name.lowercase()
        return when {
            normalized.contains("[heap]") -> "heap"
            normalized.contains("[stack") -> "stack"
            normalized.contains("/dev/ashmem") || normalized.contains("dalvik") || normalized.contains("/memfd:") -> "java"
            normalized.isBlank() || normalized.contains("[anon:") -> "anonymous"
            normalized.contains("/data/") || normalized.contains(".apk") || normalized.contains(".dex") || normalized.contains(".odex") -> "app"
            normalized.contains("/system/") || normalized.contains("/vendor/") || normalized.contains("/apex/") -> "system"
            else -> "other"
        }
    }

    private fun readRegions(pid: Int, applySelection: Boolean): List<MemRegion> {
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
            if (name.contains("[anon:vulkan]")) continue
            if (regionSize > 100 * 1024 * 1024) continue

            val category = classifyRegion(name)
            if (applySelection && category !in selectedRegionCategories) continue

            var priority = 30
            when (category) {
                "heap" -> priority += 70
                "anonymous" -> priority += 55
                "java" -> priority += 50
                "stack" -> priority += 45
                "app" -> priority += 40
                "system" -> priority += 20
            }
            if (permissions.contains('x')) priority += 5

            regions.add(MemRegion(startAddr, endAddr, priority, permissions, name, category))
        }

        return regions.sortedWith(compareByDescending<MemRegion> { it.priority }.thenBy { it.startAddr })
    }

    private fun getRegions(pid: Int): List<MemRegion> = readRegions(pid, applySelection = true)

    fun getSelectedRegionCategories(): Set<String> = selectedRegionCategories.toSet()

    fun getRegionCategorySummary(): List<Map<String, Any>> {
        val pid = attachedPid ?: return regionCategoryLabels.map { (id, label) ->
            mapOf("id" to id, "label" to label, "count" to 0, "size" to 0L, "selected" to (id in selectedRegionCategories))
        }
        val allRegions = readRegions(pid, applySelection = false)
        return regionCategoryLabels.map { (id, label) ->
            val categoryRegions = allRegions.filter { it.category == id }
            mapOf(
                "id" to id,
                "label" to label,
                "count" to categoryRegions.size,
                "size" to categoryRegions.sumOf { it.endAddr - it.startAddr },
                "selected" to (id in selectedRegionCategories),
            )
        }
    }

    @Synchronized
    fun setRegionCategories(categories: Set<String>): Boolean {
        val valid = categories.filterTo(linkedSetOf()) { it in regionCategoryLabels }
        if (valid.isEmpty()) return false
        val previous = selectedRegionCategories
        selectedRegionCategories = valid
        val pid = attachedPid
        if (pid != null) {
            val filtered = getRegions(pid)
            if (filtered.isEmpty()) {
                selectedRegionCategories = previous
                activeRegions = getRegions(pid)
                return false
            }
            activeRegions = filtered
            resetSearchState()
        }
        return true
    }

    fun getMemoryRegions(): List<Map<String, Any>> {
        return activeRegions.map { r ->
            mapOf(
                "startAddress" to r.startAddr,
                "endAddress" to r.endAddr,
                "size" to (r.endAddr - r.startAddr),
                "priority" to r.priority,
                "permissions" to r.permissions,
                "name" to r.name,
                "category" to r.category,
            )
        }
    }

    // ==================== 辅助：获取地址和大小数组（已废弃，保留用于兼容） ====================

    // ==================== 搜索 ====================

    fun searchExact(value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (!isSupportedType(type) || activeRegions.isEmpty() || !isAttachedProcessAlive()) return emptyList()

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
                RootScanner.searchExact(pid, activeRegions, type, typeSize, targetBytes)
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

    fun searchByRange(minValue: Number, maxValue: Number, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (!isSupportedType(type) || activeRegions.isEmpty() || !isAttachedProcessAlive()) return emptyList()

        return try {
            val typeSize = getTypeSize(type)
            val low: Number
            val high: Number
            if (type == "float" || type == "double") {
                low = minValue.toDouble()
                high = maxValue.toDouble()
            } else {
                low = minValue.toLong()
                high = maxValue.toLong()
            }
            if (low.toDouble() > high.toDouble()) return emptyList()

            val addresses = runBlocking {
                RootScanner.searchRange(pid, activeRegions, type, typeSize, low, high)
            }

            val results = addresses.map { addr ->
                val current = readMemory(addr, type) ?: low
                createResultMap(addr, current, type)
            }
            enrichWithMachineCode(pid, results)
            saveSnapshot(results, type)
            results
        } catch (e: Exception) {
            Log.e(TAG, "searchByRange failed: ${e.message}", e)
            emptyList()
        }
    }

    fun filterResults(previousAddresses: List<Long>, value: Any, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (!isSupportedType(type) || !isAttachedProcessAlive()) return emptyList()

        return try {
            val typeSize = getTypeSize(type)
            val targetBytes = valueToBytes(value, type) ?: return emptyList()

            // 使用模糊搜索的逻辑来过滤
            val results = mutableListOf<MutableMap<String, Any>>()
            for (addr in previousAddresses.asSequence().distinct().take(MAX_RESULTS)) {
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
        if (!isSupportedType(type) || activeRegions.isEmpty() || !isAttachedProcessAlive()) return emptyList()

        if (lastSnapshot.isEmpty() || lastSnapshotType != type) {
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
                RootScanner.searchFuzzy(pid, addresses, oldValues, mode, type, typeSize)
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
        if (activeRegions.isEmpty() || !isAttachedProcessAlive()) return emptyList()

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
                    RootScanner.readMemory(pid, (addr - 16).coerceAtLeast(0), patternBytes.size + 32)
                }
                if (ctx != null) aobDatabase[addr] = AobSignature(addr, pattern, ctx, 16)
                val mc = runBlocking { RootScanner.readMemory(pid, addr, 8) }
                val mcStr = mc?.joinToString(" ") { String.format("%02X", it) } ?: ""
                // 读取该地址的实际值（dword）
                val valBytes = runBlocking { RootScanner.readMemory(pid, addr, 4) }
                val actualValue: Any = if (valBytes != null) bytesToValue(valBytes, "dword") ?: 0 else 0
                mapOf("address" to "0x${addr.toString(16).uppercase()}", "addressInt" to addr,
                    "value" to actualValue, "type" to "dword", "isFavorite" to false, "isFrozen" to false,
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
        if (address <= 0L || !isSupportedType(type) || !isAttachedProcessAlive()) return null
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
        if (address <= 0L || !isSupportedType(type) || !isAttachedProcessAlive()) return false
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

    fun readBytes(address: Long, size: Int): ByteArray? {
        val pid = attachedPid ?: return null
        if (address <= 0L || size <= 0 || size > 16 * 1024 * 1024 || !isAttachedProcessAlive()) return null
        return try {
            runBlocking { RootScanner.readMemory(pid, address, size) }
        } catch (e: Exception) {
            Log.e(TAG, "readBytes failed: ${e.message}")
            null
        }
    }

    fun writeBytes(address: Long, data: ByteArray): Boolean {
        val pid = attachedPid ?: return false
        if (address <= 0L || data.isEmpty() || data.size > 16 * 1024 * 1024 || !isAttachedProcessAlive()) return false
        return try {
            runBlocking { RootScanner.writeMemory(pid, address, data) }
        } catch (e: Exception) {
            Log.e(TAG, "writeBytes failed: ${e.message}")
            false
        }
    }

    fun copyMemory(from: Long, to: Long, bytes: Int): Boolean {
        if (from <= 0L || to <= 0L || bytes <= 0 || bytes > 16 * 1024 * 1024) return false
        val data = readBytes(from, bytes) ?: return false
        if (data.size != bytes) return false
        return writeBytes(to, data)
    }

    fun dumpMemory(from: Long, to: Long, outputFile: File): Long {
        val pid = attachedPid ?: return -1L
        if (from < 0L || to <= from || !isAttachedProcessAlive()) return -1L
        val total = to - from
        if (total > 256L * 1024L * 1024L) return -1L
        return try {
            outputFile.parentFile?.mkdirs()
            var cursor = from
            var written = 0L
            outputFile.outputStream().buffered().use { output ->
                while (cursor < to) {
                    val size = minOf(256 * 1024L, to - cursor).toInt()
                    val data = runBlocking { RootScanner.readMemory(pid, cursor, size) } ?: return -1L
                    if (data.isEmpty()) return -1L
                    output.write(data)
                    cursor += data.size
                    written += data.size
                    if (data.size < size) break
                }
            }
            written
        } catch (e: Exception) {
            Log.e(TAG, "dumpMemory failed: ${e.message}")
            -1L
        }
    }

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

    fun readMemoryWindow(startAddress: Long, count: Int, type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (startAddress <= 0L || !isSupportedType(type) || !isAttachedProcessAlive()) return emptyList()
        val itemSize = getTypeSize(type)
        val safeCount = count.coerceIn(1, 128)
        val alignedStart = startAddress - (startAddress % itemSize.toLong())
        val byteCount = safeCount * itemSize

        return try {
            val data = runBlocking { RootScanner.readMemory(pid, alignedStart, byteCount) } ?: return emptyList()
            val actualCount = data.size / itemSize
            List(actualCount) { index ->
                val offset = index * itemSize
                val bytes = data.copyOfRange(offset, offset + itemSize)
                val address = alignedStart + offset
                mapOf(
                    "address" to "0x${address.toString(16).uppercase()}",
                    "addressInt" to address,
                    "value" to (bytesToValue(bytes, type) ?: 0),
                    "type" to type,
                    "machineCode" to bytes.joinToString(" ") { String.format("%02X", it) },
                    "isFrozen" to MemoryFreezer.isFrozen(address),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "readMemoryWindow failed: ${e.message}")
            emptyList()
        }
    }

    fun searchPointers(
        targetAddresses: List<Long>,
        maxOffset: Long,
        memoryFrom: Long = 0L,
        memoryTo: Long = -1L,
        limit: Int = 0,
    ): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (!isAttachedProcessAlive() || maxOffset < 0L) return emptyList()
        val targets = targetAddresses.filter { it > 0L }.distinct().sorted().take(128)
        if (targets.isEmpty()) return emptyList()
        val safeLimit = if (limit <= 0) MAX_RESULTS else limit.coerceIn(1, 5000)
        val upperBound = if (memoryTo <= 0L) Long.MAX_VALUE else memoryTo
        if (upperBound <= memoryFrom) return emptyList()

        val regions = activeRegions.mapNotNull { region ->
            val start = maxOf(region.startAddr, memoryFrom)
            val end = minOf(region.endAddr, upperBound)
            if (end <= start) null else region.copy(startAddr = start, endAddr = end)
        }
        if (regions.isEmpty()) return emptyList()

        val intervals = mutableListOf<Pair<Long, Long>>()
        for (target in targets) {
            val low = (target - maxOffset).coerceAtLeast(0L)
            val high = target
            val last = intervals.lastOrNull()
            if (last != null && low <= last.second + 1L) {
                intervals[intervals.lastIndex] = last.first to maxOf(last.second, high)
            } else {
                intervals.add(low to high)
            }
        }

        val pointerTypes = if (targets.any { it > 0xFFFF_FFFFL }) listOf("qword") else listOf("dword", "qword")
        val candidates = linkedMapOf<String, Pair<Long, String>>()
        try {
            runBlocking {
                loop@ for ((low, high) in intervals) {
                    for (type in pointerTypes) {
                        val typeLow: Number
                        val typeHigh: Number
                        if (type == "dword") {
                            if (low > 0xFFFF_FFFFL) continue
                            typeLow = low.coerceAtMost(0xFFFF_FFFFL)
                            typeHigh = high.coerceAtMost(0xFFFF_FFFFL)
                        } else {
                            typeLow = low
                            typeHigh = high
                        }
                        val addresses = RootScanner.searchRange(
                            pid = pid,
                            regions = regions,
                            type = type,
                            typeSize = getTypeSize(type),
                            lowBound = typeLow,
                            highBound = typeHigh,
                        )
                        for (address in addresses) {
                            candidates.putIfAbsent("$type:$address", address to type)
                            if (candidates.size >= safeLimit * 4) break@loop
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchPointers scan failed: ${e.message}")
            return emptyList()
        }

        val output = mutableListOf<Map<String, Any>>()
        for ((candidateAddress, type) in candidates.values) {
            val raw = readMemory(candidateAddress, type) as? Number ?: continue
            val pointerValue = if (type == "dword") raw.toLong() and 0xFFFF_FFFFL else raw.toLong()
            val target = targets.firstOrNull { it >= pointerValue && it - pointerValue <= maxOffset } ?: continue
            val offset = target - pointerValue
            val machineCode = readBytes(candidateAddress, getTypeSize(type))
                ?.joinToString(" ") { String.format("%02X", it) }
                .orEmpty()
            output.add(
                mapOf(
                    "address" to "0x${candidateAddress.toString(16).uppercase()}",
                    "addressInt" to candidateAddress,
                    "value" to pointerValue,
                    "type" to type,
                    "pointerTarget" to target,
                    "pointerTargetText" to "0x${target.toString(16).uppercase()}",
                    "pointerOffset" to offset,
                    "pointerExpression" to "0x${pointerValue.toString(16).uppercase()} + 0x${offset.toString(16).uppercase()}",
                    "machineCode" to machineCode,
                    "isFavorite" to false,
                    "isFrozen" to MemoryFreezer.isFrozen(candidateAddress),
                )
            )
            if (output.size >= safeLimit) break
        }
        return output.sortedWith(compareBy<Map<String, Any>> {
            (it["pointerOffset"] as? Number)?.toLong() ?: Long.MAX_VALUE
        }.thenBy { (it["addressInt"] as? Number)?.toLong() ?: Long.MAX_VALUE })
    }

    private fun searchAllValues(type: String): List<Map<String, Any>> {
        val pid = attachedPid ?: return emptyList()
        if (activeRegions.isEmpty()) return emptyList()

        return try {
            val typeSize = getTypeSize(type)

            val bounds: Pair<Number, Number> = when (type) {
                "float", "double" -> -Double.MAX_VALUE to Double.MAX_VALUE
                "byte" -> Byte.MIN_VALUE.toLong() to Byte.MAX_VALUE.toLong()
                "word" -> Short.MIN_VALUE.toLong() to Short.MAX_VALUE.toLong()
                "dword" -> Int.MIN_VALUE.toLong() to Int.MAX_VALUE.toLong()
                else -> Long.MIN_VALUE to Long.MAX_VALUE
            }
            val addresses = runBlocking {
                RootScanner.searchRange(pid, activeRegions, type, typeSize, bounds.first, bounds.second)
            }

            addresses.map { addr ->
                createResultMap(addr, readMemory(addr, type) ?: 0, type)
            }
        } catch (e: Exception) { emptyList() }
    }

    // ==================== 快照 ====================

    private fun saveSnapshot(results: List<Map<String, Any>>, type: String) {
        val pid = attachedPid ?: return
        val typeSize = getTypeSize(type)
        val addresses = results.mapNotNull { (it["addressInt"] as? Number)?.toLong() }
        if (addresses.isEmpty()) {
            lastSnapshot = emptyMap()
            lastSnapshotType = type
            return
        }

        val snapshot = mutableMapOf<Long, ByteArray>()
        for (addr in addresses) {
            val b = runBlocking {
                RootScanner.readMemory(pid, addr, typeSize)
            }
            if (b != null) snapshot[addr] = b
        }
        lastSnapshot = snapshot
        lastSnapshotType = type
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
