package com.flux.m3u8.merge

import com.flux.m3u8.data.Storage
import com.flux.m3u8.model.Segment
import java.io.File
import java.io.OutputStream

/**
 * 成品落盘（非 MP4 路径）：把分片合并成**一个**文件。
 *
 * ## 为什么是「一个文件」
 *
 * 早期实现把每个分片都复制成 `<名字>.0000001.ts` 再配一份播放列表：
 *  · 一个 1000 片的任务 = 1000 次建文件 + 1000 次全量拷贝，在 SAF 目录上
 *    每次 `createFile` 还是一次跨进程调用，慢到用户以为卡死；
 *  · 播放器开播要逐个打开上千个文件；
 *  · 下载目录里多出一堆不认识来源的分片。
 *
 * 现在只落一个 `<名字>.ts`：建 1 个文件、顺序写一遍、开播只打开一个文件。
 *
 * ## 为什么不写进 .m3u8
 *
 * `.m3u8` 是**纯文本索引**（每行一条分目地址），视频数据物理上装不进去：
 *  · 把媒体数据追加在播放列表之后 → media3 1.3.1 的 `HlsPlaylistParser` 只在读到
 *    **文件末尾（EOF）** 才结束解析（`#EXT-X-ENDLIST` 只置一个标志位，见其
 *    `parseMediaPlaylist`），追加的二进制会被当文本逐行解析出成百上千条假分目，
 *    播放必然失败；
 *  · 用 `data:` URI 把媒体 base64 塞进播放列表 → 体积 +33%，且播放器要把整条 URI
 *    读进内存，几百 MB 的文件直接 OOM。
 *
 * `.ts`（MPEG-TS）本身就是「整段视频装在一个文件里」的容器，还免去一次二次转换，
 * 所以单文件产物就用 `.ts`；想要更好的兼容性可以在任务里勾「转换为 MP4」。
 */
class HlsOutput(private val storage: Storage) {

    /** 产物：URI、文件名，以及**实际**落盘目录（写入失败时可能已回退到内部目录）。 */
    data class Output(val uri: String, val name: String, val dirUri: String)

    /**
     * 把分片合并成单个 `<baseName>.ts` 写进下载目录。
     *
     * @param source 非空则直接拷贝该文件（MP4 转换失败时已合并好的 TS，避免二次合并）
     * @param onPhase 阶段文案回调（会显示在任务卡上）
     *
     * 目标目录写不进去时 [Storage.createFileRelocating] 会自动回退到内部目录；
     * 只有「同名文件已存在」会原样抛出（是否覆盖 / 改名由用户决定）。
     * 任何失败都会删掉半成品——目录里留一个打不开的"成品"比报错更糟。
     */
    fun write(
        dirUri: String,
        baseName: String,
        segments: List<Segment>,
        initData: ByteArray?,
        tmpDir: File,
        source: File? = null,
        onPhase: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): Output {
        onPhase("合并为单个 TS 文件…")
        val res = storage.createFileRelocating(dirUri, "$baseName.ts", "video/mp2t")
        val created = res.file
        try {
            created.stream.use { out ->
                if (source != null) {
                    copyWithProgress(source, out, onProgress)
                } else {
                    // fMP4 的初始化段由 Merger 写在最前
                    Merger.merge(tmpDir, segments, out, initData) { done, total ->
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { storage.deleteUri(created.uri) }
            runCatching { storage.delete(res.dirUri, created.name) }
            throw e
        }
        onProgress(1f)
        return Output(created.uri, created.name, res.dirUri)
    }
}

/** 拷贝整个文件到输出流并回报进度（0~1）。 */
internal fun copyWithProgress(source: File, out: OutputStream, onProgress: (Float) -> Unit) {
    val total = source.length().coerceAtLeast(1)
    source.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        var lastAt = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastAt >= 80) {
                lastAt = now
                onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
            }
        }
    }
    onProgress(1f)
}
