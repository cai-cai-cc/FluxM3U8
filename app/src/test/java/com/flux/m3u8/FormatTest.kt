package com.flux.m3u8

import com.flux.m3u8.util.formatBytes
import com.flux.m3u8.util.formatDuration
import com.flux.m3u8.util.formatDurationCn
import com.flux.m3u8.util.formatSpeed
import com.flux.m3u8.util.hostOf
import com.flux.m3u8.util.isSafeRelative
import com.flux.m3u8.util.safeFileName
import com.flux.m3u8.util.safeRelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun formatBytesWorks() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun formatSpeedWorks() {
        assertEquals("1.0 KB/s", formatSpeed(1024))
        assertEquals("0 B/s", formatSpeed(0))
    }

    @Test
    fun formatDurationWorks() {
        assertEquals("--", formatDuration(0))
        assertEquals("--", formatDuration(-5))
        assertEquals("0:05", formatDuration(5))
        assertEquals("1:05", formatDuration(65))
        assertEquals("59:59", formatDuration(3599))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:01:01", formatDuration(3661))
    }

    @Test
    fun formatDurationCnWorks() {
        assertEquals("未知", formatDurationCn(0))
        assertEquals("45 秒", formatDurationCn(45))
        assertEquals("3 分 20 秒", formatDurationCn(200))
        assertEquals("2 小时 1 分", formatDurationCn(7265))
    }

    @Test
    fun hostOfWorks() {
        assertEquals("example.com", hostOf("https://example.com/a/b.m3u8"))
        assertEquals("example.com", hostOf("http://example.com:8080/a.m3u8"))
        // 非法 URL 不能抛异常，退化成截断字符串
        assertTrue(hostOf("not a url").isNotEmpty())
    }

    @Test
    fun safeFileNameWorks() {
        assertEquals("a_b_c", safeFileName("a/b:c"))
        assertEquals("video", safeFileName("   "))
        assertEquals("video", safeFileName(""))
        // 首尾的点在部分系统上会出问题，必须去掉
        assertEquals("name", safeFileName("name."))
    }

    @Test
    fun safeRelativePathWorks() {
        assertEquals("Movies", safeRelativePath("Movies"))
        assertEquals("a/b", safeRelativePath("a\\b"))
        assertEquals("a_b", safeRelativePath("a:b"))
        assertEquals("abs/path", safeRelativePath("/abs/path"))
        assertEquals("", safeRelativePath(""))
    }

    @Test
    fun pathTraversalIsBlocked() {
        // 安全红线：../ 一旦漏过去，用户就能把文件写到任意位置
        assertEquals("etc", safeRelativePath("../../etc"))
        assertEquals("", safeRelativePath(".."))
        assertEquals("a/b/c", safeRelativePath("a/../b/../../c"))
        // 幂等
        assertEquals("a/b/c", safeRelativePath(safeRelativePath("a/../b/../../c")))
        assertFalse(isSafeRelative("../../etc/passwd"))
        assertTrue(isSafeRelative("Movies/2024"))
    }
}
