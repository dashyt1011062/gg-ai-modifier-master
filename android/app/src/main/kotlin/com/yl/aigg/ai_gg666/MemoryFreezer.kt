package com.yl.aigg.ai_gg666

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存冻结器。
 *
 * 行为参考 AGG 的保存列表：冻结项与当前进程绑定，后台按固定周期重写；
 * 切换/退出进程时清空旧项，避免把旧地址写进新进程。
 */
object MemoryFreezer {

    private const val TAG = "MemoryFreezer"
    private const val FREEZE_INTERVAL_MS = 120L
    private const val MAX_CONSECUTIVE_FAILURES = 8

    private data class FrozenItem(
        val pid: Int,
        val address: Long,
        @Volatile var value: Any,
        @Volatile var type: String,
        @Volatile var failures: Int = 0,
    )

    private val frozenAddresses = ConcurrentHashMap<Long, FrozenItem>()
    private val running = AtomicBoolean(false)
    @Volatile private var freezeThread: Thread? = null

    fun freeze(address: Long, value: Any, type: String): Boolean {
        val pid = MemoryEngine.getAttachedPid() ?: return false
        if (!MemoryEngine.isAttachedProcessAlive()) return false
        if (!MemoryEngine.isSupportedType(type)) return false

        // 先写一次，确认地址和类型有效，再加入持续冻结列表。
        if (!MemoryEngine.writeMemory(address, value, type)) return false

        frozenAddresses[address] = FrozenItem(
            pid = pid,
            address = address,
            value = value,
            type = type,
        )
        startFreezingIfNeeded()
        return true
    }

    fun unfreeze(address: Long): Boolean {
        val removed = frozenAddresses.remove(address) != null
        if (frozenAddresses.isEmpty()) stopFreezing()
        return removed
    }

    fun isFrozen(address: Long): Boolean = frozenAddresses.containsKey(address)

    fun getFrozenAddresses(): List<Map<String, Any>> {
        return frozenAddresses.values
            .sortedBy { it.address }
            .map {
                mapOf(
                    "pid" to it.pid,
                    "address" to it.address,
                    "addressText" to "0x${it.address.toString(16).uppercase()}",
                    "value" to it.value,
                    "type" to it.type,
                    "failures" to it.failures,
                )
            }
    }

    fun clearAll() {
        frozenAddresses.clear()
        stopFreezing()
    }

    private fun startFreezingIfNeeded() {
        if (!running.compareAndSet(false, true)) return

        freezeThread = Thread({
            try {
                while (running.get()) {
                    val currentPid = MemoryEngine.getAttachedPid()
                    if (currentPid == null || !MemoryEngine.isAttachedProcessAlive()) {
                        frozenAddresses.clear()
                        break
                    }

                    for ((address, item) in frozenAddresses.entries.toList()) {
                        if (item.pid != currentPid) {
                            frozenAddresses.remove(address)
                            continue
                        }

                        val success = try {
                            MemoryEngine.writeMemory(item.address, item.value, item.type)
                        } catch (t: Throwable) {
                            Log.w(TAG, "freeze write failed at 0x${item.address.toString(16)}", t)
                            false
                        }

                        if (success) {
                            item.failures = 0
                        } else {
                            item.failures += 1
                            if (item.failures >= MAX_CONSECUTIVE_FAILURES) {
                                frozenAddresses.remove(address)
                            }
                        }
                    }

                    if (frozenAddresses.isEmpty()) break
                    Thread.sleep(FREEZE_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                running.set(false)
                freezeThread = null
            }
        }, "memory-freezer").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopFreezing() {
        running.set(false)
        freezeThread?.interrupt()
        freezeThread = null
    }
}
