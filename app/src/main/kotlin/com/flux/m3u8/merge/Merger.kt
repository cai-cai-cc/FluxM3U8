package com.flux.m3u8.merge

import com.flux.m3u8.model.Segment
import java.io.File
import java.io.OutputStream

/**
 * 分片合并。
 *
 * MPEG-TS 的分片可以直接按字节顺序拼接，不需要重编码，也不需要 ffmpeg。
 * 拼出来的 .ts 文件用 VLC / MX Player / 系统播放器都能播。
 */
object Merger {

    private const val BUFFER = 256 * 1024

    /**
     * 按顺序把分片写进输出流。
     *
     * @param onProgress 已合并分片数 / 总数
     */
    fun merge(
        tmpDir: File,
        segments: List<Segment>,
        out: OutputStream,
        initData: ByteArray? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        val buffer = ByteArray(BUFFER)
        initData?.let { out.write(it) }

        segments.forEachIndexed { i, seg ->
            val part = File(tmpDir, seg.fileName)
            if (!part.exists()) {
                throw IllegalStateException("分片缺失：${part.name}")
            }
            part.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            onProgress(i + 1, segments.size)
        }
        out.flush()
    }

    /** 检查分片是否齐全（断点续传前用于判断能否直接合并）。 */
    fun allSegmentsReady(tmpDir: File, segments: List<Segment>): Boolean =
        segments.all { File(tmpDir, it.fileName).exists() }

    /** 统计已下载分片的总字节数。 */
    fun downloadedBytes(tmpDir: File, segments: List<Segment>): Long =
        segments.sumOf { File(tmpDir, it.fileName).let { f -> if (f.exists()) f.length() else 0L } }
}
