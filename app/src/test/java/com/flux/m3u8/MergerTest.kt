package com.flux.m3u8

import com.flux.m3u8.merge.Merger
import com.flux.m3u8.model.Segment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 分片合并单测。Merger 只依赖 java.io，不碰 Android 框架，所以能跑在纯 JVM 上。
 */
class MergerTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun prepare(contents: List<String>): Pair<File, List<Segment>> {
        val dir = tmp.newFolder("seg")
        val segs = contents.mapIndexed { i, s ->
            File(dir, "%07d.ts".format(i)).writeText(s)
            Segment(index = i, uri = "https://a.com/$i.ts")
        }
        return dir to segs
    }

    @Test
    fun segmentsAreConcatenatedInIndexOrder() {
        val (dir, segs) = prepare(listOf("AAA", "BBB", "CCC"))
        val out = ByteArrayOutputStream()
        Merger.merge(dir, segs, out)
        assertEquals("AAABBBCCC", out.toString("UTF-8"))
    }

    @Test
    fun initSegmentIsWrittenFirst() {
        val (dir, segs) = prepare(listOf("AAA", "BBB"))
        val out = ByteArrayOutputStream()
        Merger.merge(dir, segs, out, initData = "INIT".toByteArray())
        assertEquals("INITAAABBB", out.toString("UTF-8"))
    }

    @Test
    fun missingSegmentThrows() {
        val (dir, segs) = prepare(listOf("AAA", "BBB"))
        File(dir, "0000001.ts").delete()
        try {
            Merger.merge(dir, segs, ByteArrayOutputStream())
            fail("分片缺失却没有抛异常，会静默产出损坏文件")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("分片缺失"))
        }
    }

    @Test
    fun readinessCheck() {
        val (dir, segs) = prepare(listOf("A", "B"))
        assertTrue(Merger.allSegmentsReady(dir, segs))
        File(dir, "0000000.ts").delete()
        assertFalse(Merger.allSegmentsReady(dir, segs))
    }

    @Test
    fun downloadedBytesCounting() {
        val (dir, segs) = prepare(listOf("AAA", "BBB"))
        assertEquals(6L, Merger.downloadedBytes(dir, segs))
        File(dir, "0000001.ts").delete()
        assertEquals(3L, Merger.downloadedBytes(dir, segs))
    }

    @Test
    fun progressCallbackMatchesSegmentCount() {
        val (dir, segs) = prepare(listOf("A", "B", "C"))
        val seen = mutableListOf<Pair<Int, Int>>()
        Merger.merge(dir, segs, ByteArrayOutputStream()) { done, total -> seen += done to total }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun binaryContentIsPreserved() {
        // TS 流里有大量 0x47/0xFF 之类的字节，必须原样搬运
        val dir = tmp.newFolder("bin")
        val payload = byteArrayOf(0x47, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x47)
        File(dir, "0000000.ts").writeBytes(payload)
        val seg = Segment(0, "https://a.com/0.ts")

        val out = ByteArrayOutputStream()
        Merger.merge(dir, listOf(seg), out)
        assertArrayEquals(payload, out.toByteArray())
    }
}
