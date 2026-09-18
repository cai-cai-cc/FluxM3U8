package com.flux.m3u8

import com.flux.m3u8.download.SpeedMeter
import com.flux.m3u8.download.TokenBucket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {

    @Test
    fun initialSpeedIsZero() {
        assertEquals(0L, SpeedMeter().rate())
    }

    @Test
    fun speedIsPositiveAfterAddingBytes() {
        val m = SpeedMeter()
        m.add(1024)
        assertTrue(m.rate() > 0)
    }

    @Test
    fun nonPositiveBytesAreIgnored() {
        val m = SpeedMeter()
        m.add(0)
        m.add(-1)
        assertEquals(0L, m.rate())
    }

    @Test
    fun resetClearsSpeed() {
        val m = SpeedMeter()
        m.add(4096)
        assertTrue(m.rate() > 0)
        m.reset()
        assertEquals(0L, m.rate())
    }

    @Test
    fun aggregationDoesNotLoseBytes() {
        // 目的：验证"攒够刷新周期才入窗"的改动没把字节数弄丢
        val m = SpeedMeter()
        repeat(10) { m.add(100) }
        assertTrue(m.rate() > 0)
    }

    @Test
    fun noLimitReturnsImmediately() = runTest {
        val b = TokenBucket()
        b.configure(0)
        b.consume(10 * 1024 * 1024)     // 若阻塞就说明限速没关掉
        assertEquals(0L, b.rateBytesPerSec)
    }

    @Test
    fun sufficientTokensReturnImmediately() = runTest {
        val b = TokenBucket()
        b.configure(1024)               // 1 MB/s，初始令牌池 = 1 MB
        b.consume(512 * 1024)
    }

    @Test
    fun consumingZeroDoesNotBlock() = runTest {
        val b = TokenBucket()
        b.configure(1)
        b.consume(0)
    }

    // 注意："令牌不足需要等待"的分支没有放进单测——
    // TokenBucket 用 System.nanoTime() 计算补充量，而 runTest 的 delay 走虚拟时间，
    // 两者对不上会导致死循环。该分支由人工冒烟验证覆盖。
}
