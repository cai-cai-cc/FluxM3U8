package com.flux.m3u8

import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.KeyInfo
import com.flux.m3u8.model.MasterPlaylist
import com.flux.m3u8.model.MediaPlaylist
import com.flux.m3u8.model.Segment
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.model.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelsTest {

    @Test
    fun activeStatusFlags() {
        assertTrue(TaskStatus.Downloading.isActive)
        assertTrue(TaskStatus.Queued.isActive)
        assertTrue(TaskStatus.Parsing.isActive)
        assertTrue(TaskStatus.Merging.isActive)
        assertFalse(TaskStatus.Paused.isActive)
        assertFalse(TaskStatus.Completed.isActive)
        assertFalse(TaskStatus.Error.isActive)
        assertFalse(TaskStatus.Canceled.isActive)
    }

    @Test
    fun unknownStatusFallsBackToQueued() {
        assertEquals(TaskStatus.Downloading, TaskStatus.from("Downloading"))
        assertEquals(TaskStatus.Queued, TaskStatus.from("NotAStatus"))
    }

    @Test
    fun queuedDoesNotHoldDownloadSlot() {
        // 排队中的任务只是在等前面的让位，不能计入并发上限：
        // 否则并发上限为 N 时会排满 N 个"活跃"任务，前面的下完了后面也永远起不来
        assertFalse(TaskStatus.Queued.isRunning)
        assertTrue(TaskStatus.Parsing.isRunning)
        assertTrue(TaskStatus.Downloading.isRunning)
        assertTrue(TaskStatus.Merging.isRunning)
        assertFalse(TaskStatus.Paused.isRunning)
        assertFalse(TaskStatus.Completed.isRunning)
    }

    @Test
    fun segmentFileNameIsZeroPadded() {
        // 补零是合并顺序正确的前提：不补零会出现 10.ts 排在 2.ts 前面
        assertEquals("0000000.ts", Segment(0, "u").fileName)
        assertEquals("0000009.ts", Segment(9, "u").fileName)
        assertEquals("0000123.ts", Segment(123, "u").fileName)
    }

    @Test
    fun encryptionIsDetected() {
        assertTrue(KeyInfo("AES-128", "u", null).encrypted)
        assertTrue(KeyInfo("SAMPLE-AES", "u", null).encrypted)
        assertFalse(KeyInfo("NONE", null, null).encrypted)
    }

    @Test
    fun variantLabels() {
        assertEquals("1080P", Variant("u", 1, "1920x1080").label)
        assertEquals("1500kbps", Variant("u", 1_500_000, "").label)
        assertEquals("默认", Variant("u", 0, "").label)
        assertEquals(1080, Variant("u", 1, "1920x1080").height)
        assertEquals(0, Variant("u", 1, "abc").height)
    }

    @Test
    fun variantsAreSortedDescending() {
        val list = listOf(
            Variant("a", 500, "640x360"),
            Variant("b", 3000, "1920x1080"),
            Variant("c", 1000, "1280x720")
        )
        assertEquals(listOf("b", "c", "a"), MasterPlaylist(list).sorted().map { it.url })
    }

    @Test
    fun playlistDerivedFlags() {
        val segs = listOf(Segment(0, "a", 10.0), Segment(1, "b", 5.5))
        val vod = MediaPlaylist(segs, targetDuration = 10, endList = true)
        assertEquals(15.5, vod.duration, 0.001)
        assertFalse(vod.isLive)
        assertFalse(vod.isFmp4)
        assertFalse(vod.encrypted)

        assertTrue(MediaPlaylist(segs, endList = false).isLive)
    }

    private fun task(
        totalBytes: Long = 0,
        doneBytes: Long = 0,
        totalSegments: Int = 0,
        doneSegments: Int = 0
    ) = DownloadTask(
        id = "1", url = "https://a.com/x.m3u8", name = "n",
        saveDirUri = "internal", totalBytes = totalBytes, doneBytes = doneBytes,
        totalSegments = totalSegments, doneSegments = doneSegments
    )

    @Test
    fun progressPrefersBytes() {
        assertEquals(0.25f, task(totalBytes = 100, doneBytes = 25).progress, 0.001f)
    }

    @Test
    fun progressFallsBackToSegments() {
        assertEquals(0.5f, task(totalSegments = 4, doneSegments = 2).progress, 0.001f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(1f, task(totalBytes = 100, doneBytes = 500).progress, 0.001f)
        assertEquals(0f, task().progress, 0.001f)
    }

    @Test
    fun completedIsAlwaysFullProgress() {
        // totalBytes 是估算值时（总长度未知，按平均分片大小推算）doneBytes/totalBytes
        // 常常算出 0.99，列表就会一直停在 99%，看着像没下完
        val done = task(totalBytes = 1000, doneBytes = 990).copy(status = TaskStatus.Completed)
        assertEquals(1f, done.progress, 0.001f)
    }

    @Test
    fun etaIsSafe() {
        assertEquals(10L, task(totalBytes = 100, doneBytes = 0).copy(speedBps = 10).etaSeconds)
        // 速度为 0 时不能除零，也不能返回负数
        assertEquals(0L, task(totalBytes = 100, doneBytes = 0).etaSeconds)
        assertEquals(0L, task(totalBytes = 100, doneBytes = 200).copy(speedBps = 10).etaSeconds)
    }
}
