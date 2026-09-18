package com.flux.m3u8.download

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * 滑动窗口测速：只统计最近 3 秒的数据量。
 *
 * 用滑动窗口而不是瞬时差值，速度显示才不会剧烈跳动；
 * 暂停后窗口自然清空，速度归零，不需要额外处理。
 */
class SpeedMeter(private val windowMs: Long = 3000) {

    private data class Sample(val timeMs: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private val lock = Any()

    /**
     * 待入窗的字节数。
     *
     * 下载循环每读满 64KB 就调一次 [add]——50MB/s 时是每秒约 800 次，
     * 每次都 new 一个 Sample，3 秒窗口里堆两千多个对象，白白制造 GC 压力。
     * 这里先攒着，每 [FLUSH_MS] 毫秒才真正入窗一次。
     */
    private var pending = 0L
    private var lastFlushMs = 0L

    fun add(bytes: Long) {
        if (bytes <= 0) return
        synchronized(lock) {
            pending += bytes
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= FLUSH_MS) flushLocked(now)
        }
    }

    fun rate(): Long = synchronized(lock) {
        flushLocked(System.currentTimeMillis())
        trimLocked()
        if (samples.isEmpty()) return 0L
        val span = max(1L, System.currentTimeMillis() - samples.first().timeMs)
        val total = samples.sumOf { it.bytes }
        total * 1000L / span
    }

    fun reset() = synchronized(lock) {
        samples.clear()
        pending = 0L
        lastFlushMs = 0L
    }

    private fun flushLocked(now: Long) {
        if (pending > 0) {
            samples.addLast(Sample(now, pending))
            pending = 0L
        }
        lastFlushMs = now
        trimLocked()
    }

    private fun trimLocked() {
        val cutoff = System.currentTimeMillis() - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            samples.removeFirst()
        }
    }

    companion object {
        private const val FLUSH_MS = 100L
    }
}

/**
 * 全局限速（令牌桶）。rate <= 0 表示不限速。
 *
 * 用 AtomicLong 记录令牌余量，多协程并发消费时无需额外加锁。
 */
class TokenBucket {

    @Volatile
    var rateBytesPerSec: Long = 0
        private set

    private val tokens = AtomicLong(0)
    private val lastNanos = AtomicLong(System.nanoTime())

    fun configure(kiloBytesPerSec: Int) {
        rateBytesPerSec = if (kiloBytesPerSec <= 0) 0 else kiloBytesPerSec * 1024L
        tokens.set(rateBytesPerSec)
    }

    /** 消费 n 字节；若令牌不足则阻塞等待。 */
    suspend fun consume(n: Int) {
        val rate = rateBytesPerSec
        if (rate <= 0 || n <= 0) return

        while (true) {
            val now = System.nanoTime()
            val last = lastNanos.get()
            val elapsedNanos = now - last
            if (elapsedNanos > 0 && lastNanos.compareAndSet(last, now)) {
                val refill = elapsedNanos * rate / 1_000_000_000L
                // 令牌上限为 rate，避免长时间不限速后突然爆发
                tokens.updateAndGet { cur -> minOf(rate, cur + refill) }
            }

            val cur = tokens.get()
            if (cur >= n) {
                if (tokens.compareAndSet(cur, cur - n)) return
            } else {
                val deficit = n - cur
                val waitMs = (deficit * 1000L / rate).coerceIn(1, 200)
                kotlinx.coroutines.delay(waitMs)
            }
        }
    }
}

/**
 * 进程内**共享**的限速令牌桶。
 *
 * 之前每个任务各持有一个令牌桶，结果是"全局限速 1MB/s + 3 个任务"实际跑到 3MB/s，
 * 设置形同虚设。改为全局共用一个桶后，界面上的限速才是真正的总带宽上限，
 * 并且设置改动后立刻对所有正在下载的任务生效（无需重启任务）。
 */
object GlobalLimiter {
    val bucket = TokenBucket()

    fun configure(kiloBytesPerSec: Int) = bucket.configure(kiloBytesPerSec)
}
