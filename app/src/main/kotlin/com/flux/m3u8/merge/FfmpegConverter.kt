package com.flux.m3u8.merge

import android.media.MediaExtractor
import android.media.MediaFormat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用 ffmpeg（ffmpeg-kit）把合并好的 TS 转成 MP4。
 *
 * 策略：流拷贝优先（秒级、无损），拷贝失败再降级重编码——
 *  · 流拷贝 `-c copy`：只改容器不改码流，ADTS 头由 ffmpeg 正确处理
 *  · 硬件重编码 `h264_mediacodec` + `aac`：源编码不被 MP4 支持时兜底
 *  · 软件重编码 `mpeg4` + `aac`：硬件编码器不可用时最后兜底
 * 全部失败才向上抛错，由上层回退保存 .ts。
 */
object FfmpegConverter {

    /** 转换结果：成功的文件与使用到的方式名。 */
    data class Result(val file: File, val method: String)

    private data class Attempt(val name: String, val codecArgs: List<String>)

    private val attempts = listOf(
        Attempt("ffmpeg 流拷贝", listOf("-c", "copy")),
        Attempt("ffmpeg 硬件重编码", listOf("-c:v", "h264_mediacodec", "-c:a", "aac", "-b:a", "128k")),
        Attempt("ffmpeg 软件重编码", listOf("-c:v", "mpeg4", "-c:a", "aac", "-b:a", "128k"))
    )

    /**
     * @param isCancelled 任务被取消时返回 true，会中断正在进行的 ffmpeg 进程
     * @param onProgress 0..1，基于已处理时长占输入总时长的比例
     */
    suspend fun convert(
        input: File,
        output: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {}
    ): Result {
        if (!input.exists() || input.length() <= 0) {
            throw IllegalStateException("待转换文件为空，无法转 MP4")
        }
        val durationMs = readDurationMs(input)
        val base = listOf(
            "-hide_banner", "-y", "-i", input.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?",
            "-stats", "-stats_period", "0.5",
            "-movflags", "+faststart"
        )

        val failures = StringBuilder()
        for (attempt in attempts) {
            runCatching { if (output.exists()) output.delete() }
            try {
                runFfmpeg(base + attempt.codecArgs + output.absolutePath, isCancelled, durationMs, onProgress)
                if (output.exists() && output.length() > 0) {
                    return Result(output, attempt.name)
                }
                failures.append(attempt.name).append("：输出为空\n")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                failures.append(attempt.name).append("：")
                    .append(e.message ?: e.javaClass.simpleName).append('\n')
            }
        }
        throw IllegalStateException("ffmpeg 转 MP4 失败：\n$failures")
    }

    /** 用系统解析器读输入时长（微秒→毫秒），读不到返回 0（此时进度仅展示阶段文案）。 */
    private fun readDurationMs(input: File): Long = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            var us = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    us = maxOf(us, f.getLong(MediaFormat.KEY_DURATION))
                }
            }
            us / 1000
        } finally {
            extractor.release()
        }
    }.getOrDefault(0L)

    /** 挂起直到 ffmpeg 完成；进度与取消都从统计回调里驱动。 */
    private suspend fun runFfmpeg(
        args: List<String>,
        isCancelled: () -> Boolean,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val cmd = args.joinToString(" ") { if (it.contains(' ') || it.contains('\\')) "\"$it\"" else it }
        var cancelled = false
        var session: FFmpegSession? = null
        session = FFmpegKit.executeAsync(
            cmd,
            { s ->
                if (!cont.isActive) return@executeAsync
                when {
                    ReturnCode.isSuccess(s.returnCode) -> cont.resume(Unit)
                    cancelled || ReturnCode.isCancel(s.returnCode) ->
                        cont.resumeWithException(CancellationException("转换已取消"))
                    else ->
                        cont.resumeWithException(IllegalStateException("ffmpeg 退出码 ${s.returnCode}"))
                }
            },
            null,
            { stat ->
                if (durationMs > 0 && stat.time > 0) {
                    onProgress((stat.time.toFloat() / durationMs).coerceIn(0f, 1f))
                }
                if (isCancelled()) {
                    cancelled = true
                    session?.cancel()
                }
            }
        )
        cont.invokeOnCancellation { session?.cancel() }
    }
}
