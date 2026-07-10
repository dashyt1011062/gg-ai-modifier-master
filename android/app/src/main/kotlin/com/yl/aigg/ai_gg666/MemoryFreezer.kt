package com.yl.aigg.ai_gg666

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AGG / GameGuardian 风格内存冻结器。
 *
 * 支持固定值、只允许增大、只允许减小和区间限制四种模式。冻结项与当前进程绑定，
 * 切换或退出进程时自动清理，避免把旧地址写入新进程。
 */
object MemoryFreezer {

    const val FREEZE_NORMAL = 0
    const val FREEZE_MAY_INCREASE = 1
    const val FREEZE_MAY_DECREASE = 2
    const val FREEZE_IN_RANGE = 3

    private const val TAG = "MemoryFreezer"
    private const val FREEZE_INTERVAL_MS = 120L
    private const val MAX_CONSECUTIVE_FAILURES = 8

    private data class FrozenItem(
        val pid: Int,
        val address: Long,
        @Volatile var value: Any,
        @Volatile var type: String,
        @Volatile var freezeType: Int = FREEZE_NORMAL,
        @Volatile var freezeFrom: Any? = null,
        @Volatile var freezeTo: Any? = null,
        @Volatile var failures: Int = 0,
    )

    private val frozenAddresses = ConcurrentHashMap<Long, FrozenItem>()
    private val running = AtomicBoolean(false)
    @Volatile private var freezeThread: Thread? = null

    fun freeze(
        address: Long,
        value: Any,
        type: String,
        freezeType: Int = FREEZE_NORMAL,
        freezeFrom: Any? = null,
        freezeTo: Any? = null,
    ): Boolean {
        val pid = MemoryEngine.getAttachedPid() ?: return false
        if (!MemoryEngine.isAttachedProcessAlive()) return false
        if (!MemoryEngine.isSupportedType(type)) return false
        if (freezeType !in FREEZE_NORMAL..FREEZE_IN_RANGE) return false

        val normalizedRange = normalizeRange(value, freezeFrom, freezeTo, freezeType) ?: return false

        // 与原版保存列表一致：启用冻结时先写入一次目标值，再开始约束后续变化。
        if (!MemoryEngine.writeMemory(address, value, type)) return false

        frozenAddresses[address] = FrozenItem(
            pid = pid,
            address = address,
            value = value,
            type = type,
            freezeType = freezeType,
            freezeFrom = normalizedRange.first,
            freezeTo = normalizedRange.second,
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

    fun getFreezeType(address: Long): Int? = frozenAddresses[address]?.freezeType

    fun getFrozenAddresses(): List<Map<String, Any?>> {
        return frozenAddresses.values
            .sortedBy { it.address }
            .map {
                mapOf(
                    "pid" to it.pid,
                    "address" to it.address,
                    "addressText" to "0x${it.address.toString(16).uppercase()}",
                    "value" to it.value,
                    "type" to it.type,
                    "freezeType" to it.freezeType,
                    "freezeFrom" to it.freezeFrom,
                    "freezeTo" to it.freezeTo,
                    "failures" to it.failures,
                )
            }
    }

    fun clearAll() {
        frozenAddresses.clear()
        stopFreezing()
    }

    private fun normalizeRange(
        value: Any,
        freezeFrom: Any?,
        freezeTo: Any?,
        freezeType: Int,
    ): Pair<Any?, Any?>? {
        if (freezeType != FREEZE_IN_RANGE) return freezeFrom to freezeTo
        val from = freezeFrom ?: value
        val to = freezeTo ?: value
        val fromNumber = numberValue(from) ?: return null
        val toNumber = numberValue(to) ?: return null
        return if (fromNumber <= toNumber) Pair(from, to) else Pair(to, from)
    }

    private fun numberValue(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble()
            null -> null
            else -> value.toString().trim().toDoubleOrNull()
        }
    }

    private fun enforce(item: FrozenItem): Boolean {
        if (item.freezeType == FREEZE_NORMAL) {
            return MemoryEngine.writeMemory(item.address, item.value, item.type)
        }

        val current = MemoryEngine.readMemory(item.address, item.type) ?: return false
        val currentNumber = numberValue(current) ?: return false
        val targetNumber = numberValue(item.value) ?: return false

        return when (item.freezeType) {
            FREEZE_MAY_INCREASE -> {
                when {
                    currentNumber < targetNumber -> MemoryEngine.writeMemory(item.address, item.value, item.type)
                    currentNumber > targetNumber -> {
                        item.value = current
                        true
                    }
                    else -> true
                }
            }
            FREEZE_MAY_DECREASE -> {
                when {
                    currentNumber > targetNumber -> MemoryEngine.writeMemory(item.address, item.value, item.type)
                    currentNumber < targetNumber -> {
                        item.value = current
                        true
                    }
                    else -> true
                }
            }
            FREEZE_IN_RANGE -> {
                val from = item.freezeFrom ?: item.value
                val to = item.freezeTo ?: item.value
                val fromNumber = numberValue(from) ?: return false
                val toNumber = numberValue(to) ?: return false
                when {
                    currentNumber < fromNumber -> MemoryEngine.writeMemory(item.address, from, item.type)
                    currentNumber > toNumber -> MemoryEngine.writeMemory(item.address, to, item.type)
                    else -> true
                }
            }
            else -> false
        }
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
                            enforce(item)
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
