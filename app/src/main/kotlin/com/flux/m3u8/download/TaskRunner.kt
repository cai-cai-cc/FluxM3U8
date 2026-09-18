package com.flux.m3u8.download

import android.content.Context
import com.flux.m3u8.crypto.HlsCrypto
import com.flux.m3u8.data.FileConflictException
import com.flux.m3u8.data.Settings
import com.flux.m3u8.data.Storage
import com.flux.m3u8.data.TaskDb
import com.flux.m3u8.m3u8.HlsSource
import com.flux.m3u8.m3u8.M3u8Parser
import com.flux.m3u8.merge.HlsOutput
import com.flux.m3u8.merge.Merger
import com.flux.m3u8.merge.FfmpegConverter
import com.flux.m3u8.merge.copyWithProgress
import com.flux.m3u8.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 进度 ticker 专用调度器。
 *
 * 之前 ticker 跑在 Dispatchers.IO 上：下载 worker（每个任务 connections×chunkThreads 个
 * 阻塞式网络协程）会把 64 线程的 IO 池占满，ticker 和新建任务的解析协程排队等线程，
 * 表现就是"进度条冻结、只有总速度在跑、新任务解析卡住"。ticker 必须独占一条线程，
 * 永不与阻塞型下载工作争抢。
 */
private val tickerDispatcher =
    Executors.newSingleThreadExecutor { r -> Thread(r, "flux-ticker").apply { isDaemon = true } }
        .asCoroutineDispatcher()

/**
 * 全任务共享的分片下载工作池（上限 32 并发线程）。
 *
 * 单个任务默认 connections=16、分块 chunkThreads=4，理论上可同时占 64 个 IO 线程，
 * 两个任务就把 Dispatchers.IO（64 线程）吃干榨净。用 limitedParallelism 把下载用的
 * 阻塞线程总数全局封顶，给解析 / 合并 / 存储等留出余量；排队的分片协程不占线程，
 * 网络受限时下载速度基本不受影响。
 */
private val workerPool = Dispatchers.IO.limitedParallelism(32)

/**
 * 单个任务的控制开关。
 *
 * 暂停不是取消：协程继续存活但阻塞在 [check] 上，已建立的连接不释放，
 * 恢复时立刻满速，不会有"重新握手"的迟滞。
 */
class TaskControl {
    private val paused = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)

    fun pause() = paused.set(true)
    fun resume() = paused.set(false)
    fun cancel() { canceled.set(true); paused.set(false) }

    val isPaused: Boolean get() = paused.get()
    val isCanceled: Boolean get() = canceled.get()

    /** 暂停时挂起，取消时抛出，正常时立即返回。 */
    suspend fun check() {
        if (canceled.get()) throw CancellationException("任务已取消")
        while (paused.get() && !canceled.get()) delay(250)
        if (canceled.get()) throw CancellationException("任务已取消")
    }
}

/**
 * 任务执行器：解析 → 并发下载分片 → 解密 → 合并 → 输出。
 *
 * 关键行为：
 *  · 每个分片先写 .part，写完整才改名，进程被杀也不会留下"假完成"的分片
 *  · 启动前扫描临时目录，已存在的分片直接跳过，实现分片级断点续传
 *  · 直播流（无 EXT-X-ENDLIST）持续刷新播放列表追加新分片
 */
class TaskRunner(
    private val context: Context,
    private val settings: Settings,
    private val storage: Storage,
    private val db: TaskDb
) {

    /**
     * 限速令牌桶：所有任务共用同一个，界面上设的"限速"才是真正的**总**带宽上限。
     * （每个任务各持一个桶的话，N 个任务会跑出 N 倍限速，设置等于失效。）
     */
    private val bucket: TokenBucket get() = GlobalLimiter.bucket

    private val client get() = HttpFactory.get(settings)

    /** 播放列表 / 密钥 / 初始化段的取流，与 PlaybackManager 共用一套实现。 */
    private val source by lazy { HlsSource(settings) }

    /**
     * @param initial 任务初始状态
     * @param control 暂停/取消开关
     * @param meter 该任务的测速器
     * @param onUpdate 状态变化回调（调用方负责刷新 UI 与通知）
     * @return 最终状态的任务
     */
    suspend fun run(
        initial: DownloadTask,
        control: TaskControl,
        meter: SpeedMeter,
        onUpdate: (DownloadTask) -> Unit
    ): DownloadTask = withContext(Dispatchers.IO) {
        // 任务快照放在 AtomicReference 里。
        // ticker 协程每 400ms 更新一次，主流程同时也在读；
        // 用普通 var 会读到"半新半旧"的撕裂值（估算总大小时尤其明显）。
        val taskRef = AtomicReference(initial)
        val tmpDir = storage.taskTempDir(initial.id)
        var ticker: Job? = null

        try {
            bucket.configure(settings.speedLimitKbps)

            // ── 1. 解析 ──
            val parsing = taskRef.get().copy(status = TaskStatus.Parsing)
            taskRef.set(parsing)
            onUpdate(parsing)

            val headers = HttpFactory.buildHeaders(
                parsing.url, settings, parsing.referer, parsing.userAgent, parsing.cookie
            )
            val (mediaUrl, media, variantLabel) = resolvePlaylist(parsing, headers, control)
            if (media.segments.isEmpty()) throw IllegalStateException("播放列表里没有任何分片")

            val segments = media.segments.toMutableList()
            val exactTotal = segments.mapNotNull { it.byteRange?.first }.sum()
            val existing = segments.count { File(tmpDir, it.fileName).exists() }

            val ready = parsing.copy(
                variantLabel = variantLabel,
                totalSegments = segments.size,
                totalBytes = exactTotal,
                estimated = exactTotal == 0L,
                isLive = media.isLive,
                encrypted = media.encrypted,
                doneSegments = existing,
                doneBytes = Merger.downloadedBytes(tmpDir, segments),
                status = TaskStatus.Downloading
            )
            taskRef.set(ready)
            runCatching { db.insert(ready) }
            onUpdate(ready)

            // ── 1.5 输出目录体检 ──
            // 必须在开下之前做：SAF 目录授权可能已被系统回收，
            // 等到合并阶段才发现，整个视频都下完了，失败代价极高。
            val effectiveDir = storage.ensureUsable(ready.saveDirUri)
            if (effectiveDir != ready.saveDirUri) {
                val relocated = ready.copy(saveDirUri = effectiveDir)
                taskRef.set(relocated)
                runCatching { db.insert(relocated) }
            }

            // ── 1.6 同名文件拦截 ──
            // 目录里已经有同名产物（.mp4 / .m3u8 / .ts）时立刻报错退出：
            // 不覆盖、不自动改名，也不白白下完整个视频再到最后一步才发现。
            // 用户可以在任务卡上长按改名，或到设置里换保存目录后重试。
            val target = taskRef.get()
            storage.conflictOutput(target.saveDirUri, target.name, target.toMp4)?.let { dup ->
                throw FileConflictException(dup)
            }

            // ── 2. 密钥与初始化段 ──
            val keys = fetchKeys(segments, headers)
            val initData = fetchInitMap(segments, headers)

            // ── 3. 并发下载 ──
            // 用原子计数而不是直接改 task 字段：多个 worker 并发回调，
            // 直接 copy 会因竞态丢计数。
            val base = taskRef.get()
            val bytesRef = AtomicLong(base.doneBytes)
            val segsRef = AtomicInteger(base.doneSegments)
            val totalSegsRef = AtomicInteger(segments.size)
            val failedRef = AtomicInteger(0)

            ticker = launch(tickerDispatcher) {
                while (isActive) {
                    delay(400)
                    val cur = taskRef.get()
                    val done = segsRef.get()
                    val bytes = bytesRef.get()
                    val total = totalSegsRef.get()
                    val totalBytes = if (cur.estimated && done > 0) {
                        (bytes.toDouble() / done * total).toLong()
                    } else cur.totalBytes
                    val next = cur.copy(
                        doneSegments = done,
                        doneBytes = bytes,
                        totalSegments = total,
                        totalBytes = totalBytes,
                        speedBps = meter.rate()
                    )
                    taskRef.set(next)
                    // 先刷新 UI/Flow，落库放后面并单独保护：
                    // updateProgress 是裸 SQLite 写，多任务并发时偶发写锁异常，
                    // 一旦抛出会杀死整条 ticker 协程——表现就是"界面可点但状态和进度条不动"。
                    onUpdate(next)
                    runCatching {
                        db.updateProgress(next.id, done, bytes, total, totalBytes, next.estimated)
                    }
                }
            }

            val channel = Channel<Segment>(Channel.UNLIMITED)
            val maxWorkers = base.connections.coerceIn(1, 64)
            val seen = segments.map { it.uri }.toMutableSet()

            // 并发闸门：worker 必须先拿到许可才能取分片。
            // 自动并发模式下靠增减许可数来实时调节并发度，无需重建协程。
            val startWorkers = if (settings.autoConcurrency) {
                minOf(4, maxWorkers, segments.size).coerceAtLeast(1)
            } else maxWorkers
            val gate = Semaphore(startWorkers)

            // 用 supervisorScope：某个分片失败不该连坐其他 worker。
            //
            // 注意 worker 内部必须自己吃掉分片级异常——以前没 catch，
            // 一个分片重试耗尽后抛异常会**直接杀死整条 worker 协程**，
            // 并发度被悄悄打掉，越下越慢却不报错。
            supervisorScope {
                val jobs = (0 until maxWorkers).map {
                    launch(workerPool) {
                        for (seg in channel) {
                            gate.acquire()
                            try {
                                control.check()
                                downloadSegment(
                                    seg, headers, keys, tmpDir, control, meter
                                ) { delta, doneOne ->
                                    if (delta > 0) bytesRef.addAndGet(delta)
                                    if (doneOne > 0) segsRef.addAndGet(doneOne)
                                }
                            } catch (ce: CancellationException) {
                                failedRef.incrementAndGet()
                                throw ce
                            } catch (e: Exception) {
                                failedRef.incrementAndGet()
                            } finally {
                                gate.release()
                            }
                        }
                    }
                }

                // 自动并发：每 2.5 秒看一次速度，涨了就加连接，连续两次没涨就收一点。
                // 目的是找到"再加也没用"的那个拐点，避免盲目开满把源站打爆。
                val tuner = if (settings.autoConcurrency) launch {
                    var current = startWorkers
                    var lastSpeed = 0L
                    var stall = 0
                    while (isActive) {
                        delay(2500)
                        val speed = meter.rate()
                        if (speed > (lastSpeed * 1.10)) {
                            if (current < maxWorkers) {
                                gate.release()
                                current++
                            }
                            stall = 0
                        } else {
                            stall++
                            if (stall >= 2 && current > 1) {
                                if (gate.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                                    current--
                                }
                                stall = 0
                            }
                        }
                        lastSpeed = speed
                    }
                } else null

                try {
                    segments.forEach { channel.send(it) }
                    if (media.isLive) {
                        pumpLive(mediaUrl, headers, media, segments, seen, channel, control, totalSegsRef)
                    }
                } finally {
                    tuner?.cancel()
                    runCatching { channel.close() }
                }
                jobs.joinAll()
            }

            ticker?.cancel()
            control.check()

            // ── 3.5 完整性自检 ──
            // 以前缺分片要等到 Merger 报"分片缺失"才发现，
            // 那时任务已经跑完 99%、合并也失败，用户白等一场。
            // 直播流不套用这条：直播本来就可能丢片，产出部分内容比整体失败更有意义。
            if (!media.isLive) {
                val missing = totalSegsRef.get() - segsRef.get()
                if (missing > 0) {
                    throw IOException(
                        "$missing 个分片未下载完成（累计失败 ${failedRef.get()} 次，" +
                            "已自动重试 ${settings.retryTimes} 轮）"
                    )
                }
            }

            // ── 4. 合并输出 ──
            // 进度立刻切到"合并中"，不要等下一帧，否则下完 100% 后会卡在"下载中"一会儿。
            val merging = taskRef.get().copy(
                status = TaskStatus.Merging,
                doneSegments = segsRef.get(),
                doneBytes = bytesRef.get(),
                mergeProgress = 0f,
                phaseLabel = "准备合并…",
                speedBps = 0
            )
            taskRef.set(merging)
            onUpdate(merging)

            // 合并/转换过程持续回报进度（只写回内存任务，不落库）。
            // 大文件可能有上千个分片，逐片回调会疯狂刷新界面，这里做 100ms 节流，
            // 界面才跟得上、不会显得"卡住不动"。
            var lastProgressAt = 0L
            val reportProgress: (Float) -> Unit = { p ->
                val now = System.currentTimeMillis()
                val cur = taskRef.get()
                if (cur.status == TaskStatus.Merging &&
                    (now - lastProgressAt >= 100 || p >= 1f || p <= 0f)
                ) {
                    lastProgressAt = now
                    val next = cur.copy(mergeProgress = p.coerceIn(0f, 1f))
                    taskRef.set(next)
                    onUpdate(next)
                }
            }
            // 阶段文案（合并 / 转换 / 写入）分开显示
            val reportPhase: (String) -> Unit = { label ->
                val cur = taskRef.get()
                if (cur.status == TaskStatus.Merging && cur.phaseLabel != label) {
                    val next = cur.copy(phaseLabel = label)
                    taskRef.set(next)
                    onUpdate(next)
                }
            }

            // 输出前再确认一次目录可写：SAF 授权可能在中途被系统回收
            val currentDir = taskRef.get().saveDirUri
            val usableDir = storage.ensureUsable(currentDir)
            if (usableDir != currentDir) {
                val moved = taskRef.get().copy(saveDirUri = usableDir)
                taskRef.set(moved)
                runCatching { db.insert(moved) }
            }

            val out = mergeToOutput(taskRef.get(), segments, initData, tmpDir, control, reportPhase, reportProgress)

            if (out.dirUri != taskRef.get().saveDirUri) {
                val moved = taskRef.get().copy(saveDirUri = out.dirUri)
                taskRef.set(moved)
                runCatching { db.insert(moved) }
            }

            val finished = taskRef.get().copy(
                status = TaskStatus.Completed,
                finishedAt = System.currentTimeMillis(),
                outputUri = out.uri,
                outputName = out.name,
                mergeProgress = 1f,
                phaseLabel = "",
                error = null,
                speedBps = 0
            )
            taskRef.set(finished)
            runCatching { db.updateOutput(finished.id, out.uri, out.name) }
            // 转换完成后立即清理本任务的全部临时文件（分片目录 + 合并/转换的中间产物），
            // 不留下"下完了还占一份空间"的垃圾；设置里勾了"保留分片"才留着分片。
            if (!settings.keepSegments) storage.clearTemp(finished.id)
            finished
        } catch (ce: CancellationException) {
            val cur = taskRef.get()
            val finalStatus = if (control.isCanceled) TaskStatus.Canceled else TaskStatus.Paused
            cur.copy(status = finalStatus, phaseLabel = "", speedBps = 0).also {
                // 落库失败也不能让异常逃逸：否则协程死在 catch 里，任务永远停在"解析中/下载中"
                runCatching { db.updateStatus(it.id, finalStatus) }
                if (finalStatus == TaskStatus.Canceled) storage.clearTemp(it.id)
            }
        } catch (e: Exception) {
            val cur = taskRef.get()
            val msg = describeError(e)
            cur.copy(status = TaskStatus.Error, error = msg, phaseLabel = "", speedBps = 0).also {
                runCatching { db.updateStatus(it.id, TaskStatus.Error, msg) }
            }
        } finally {
            ticker?.cancel()
        }
    }

    /** 把底层异常翻译成用户能看懂的中文原因。 */
    private fun describeError(e: Throwable): String {
        val raw = e.message?.trim().orEmpty()
        return when {
            e is FileConflictException ->
                "保存目录已存在同名文件「${e.fileName}」，请改名或更换保存目录后重试"
            e is java.net.UnknownHostException -> "无法解析域名，请检查网络或链接是否正确"
            e is java.net.SocketTimeoutException -> "连接超时，可在设置里调大超时时间"
            e is java.net.ConnectException -> "无法连接服务器，请检查网络或代理设置"
            e is javax.net.ssl.SSLException -> "HTTPS 证书校验失败，可在设置里临时跳过证书校验"
            e is java.io.FileNotFoundException -> "文件创建失败：${raw.take(80)}"
            e is java.io.IOException && raw.contains("HTTP 403") ->
                "服务器拒绝访问（403），通常需要填写 Referer/Cookie"
            e is java.io.IOException && raw.contains("HTTP 404") ->
                "资源不存在（404），链接可能已失效"
            e is java.io.IOException && raw.contains("HTTP 5") ->
                "服务器错误（$raw），请稍后重试"
            raw.contains("No space left") || raw.contains("ENOSPC") ->
                "存储空间不足，请清理后重试"
            raw.isNotBlank() -> raw.take(200)
            else -> e.javaClass.simpleName
        }
    }

    // ──────────────── 解析 ────────────────

    private suspend fun resolvePlaylist(
        task: DownloadTask,
        headers: Map<String, String>,
        control: TaskControl
    ): Triple<String, MediaPlaylist, String> = withContext(Dispatchers.IO) {
        control.check()
        source.resolveMediaPlaylist(task.url, headers, task.variantIndex)
    }

    private suspend fun fetchKeys(
        segments: List<Segment>,
        headers: Map<String, String>
    ): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        source.fetchKeys(segments, headers)
    }

    private suspend fun fetchInitMap(
        segments: List<Segment>,
        headers: Map<String, String>
    ): ByteArray? = withContext(Dispatchers.IO) {
        source.fetchInitMap(segments, headers)
    }

    // ──────────────── 下载 ────────────────

    private suspend fun downloadSegment(
        seg: Segment,
        headers: Map<String, String>,
        keys: Map<String, ByteArray>,
        tmpDir: File,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long, Int) -> Unit
    ) {
        val final = File(tmpDir, seg.fileName)
        if (final.exists() && final.length() > 0) {
            // 断点续传：已下载分片直接跳过，字节数仍要计入总量。
            // 注意不要喂给 meter——那是"过去下载的字节"，算进去会让恢复瞬间
            // 速度虚高到几十 MB/s，进度条 ETA 直接失真。
            onDelta(final.length(), 0)
            return
        }

        val part = File(tmpDir, seg.fileName + ".part")
        val attempts = settings.retryTimes + 1
        var lastError: Exception? = null

        for (attempt in 0 until attempts) {
            try {
                control.check()
                val size = fetchSegmentBody(seg, headers, part, control, meter) { onDelta(it, 0) }
                if (size == 0L) throw IOException("分片内容为空")

                // 解密用流式：整段 readBytes() 会让峰值内存达到分片的 3 倍，
                // 大分片（几十 MB）+ 多并发时足以把进程拖垮。
                val key = seg.key
                if (key != null && key.encrypted) {
                    val raw = keys[key.uri] ?: throw IOException("缺少解密密钥")
                    val iv = if (!key.ivHex.isNullOrBlank()) HlsCrypto.ivFromHex(key.ivHex!!)
                    else HlsCrypto.ivFromSequence(seg.mediaSequence)
                    val plain = File(tmpDir, seg.fileName + ".dec")
                    HlsCrypto.decryptFile(part, plain, raw, iv)
                    part.delete()
                    if (!plain.renameTo(part)) {
                        plain.copyTo(part, overwrite = true)
                        plain.delete()
                    }
                }

                // 写完整才改名：保证半截文件不会被误判为已完成
                if (!part.renameTo(final)) {
                    part.copyTo(final, overwrite = true)
                    part.delete()
                }
                onDelta(0, 1)
                return
            } catch (ce: CancellationException) {
                part.delete()
                throw ce
            } catch (e: Exception) {
                lastError = e
                part.delete()
                // 线性退避：第 n 次失败等 n × 间隔，给源站留恢复时间。
                // 间隔设为 0 时立即重试。
                if (attempt < attempts - 1) {
                    val wait = settings.retryDelaySeconds * 1000L * (attempt + 1)
                    if (wait > 0) delay(wait)
                }
            }
        }
        throw lastError ?: IOException("分片下载失败")
    }

    // ──────────────── 分片下载：单连接 / 分块并发 ────────────────

    /**
     * 下载分片主体，返回写入的字节数。
     *
     * 两条路径：
     *  1. 分片够大且服务器支持 Range → 切成多段并发拉取（[downloadChunked]）
   *  2. 否则单连接流式下载
     *
     * 分块失败（部分 CDN 不认 Range）会自动降级为单连接，不会导致任务失败。
     */
    private suspend fun fetchSegmentBody(
        seg: Segment,
        headers: Map<String, String>,
        part: File,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long) -> Unit
    ): Long {
        val baseOffset = seg.byteRange?.second ?: 0L
        val knownLen = seg.byteRange?.first
        val chunks = settings.chunkThreads
        val threshold = settings.chunkThresholdKb * 1024L

        // 播放列表已经用 EXT-X-BYTERANGE 声明了长度：
        // 既知道大小，又能确定服务端按 Range 取流（否则这个列表本身就没法播），
        // 探测请求纯属浪费——一个 500 分片的任务会因此多发 500 个请求。
        if (knownLen != null && knownLen > 0) {
            return if (chunks > 1 && knownLen >= threshold) {
                runCatching {
                    downloadChunked(seg, headers, part, knownLen, chunks, baseOffset,
                        control, meter, onDelta)
                }.getOrElse {
                    fetchPlain(seg, headers, part, knownLen, baseOffset, control, meter, onDelta)
                }
            } else {
                fetchPlain(seg, headers, part, knownLen, baseOffset, control, meter, onDelta)
            }
        }

        // 先发一次请求，从响应头拿到分片真实大小（顺便判断服务器是否支持 Range）
        val probeHeaders = headers.toMutableMap().apply {
            knownLen?.let { put("Range", "bytes=$baseOffset-${baseOffset + it - 1}") }
        }
        val probeReq = Request.Builder().url(seg.uri).apply {
            probeHeaders.forEach { (k, v) -> header(k, v) }
        }.build()

        // 探测结果必须先存下来：use 块结束后 Response 已关闭，取不到头信息
        var probedTotal: Long? = null

        client.newCall(probeReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")

            val total = parseTotalLength(resp, knownLen)
            val ranged = resp.code == 206 || resp.header("Content-Range") != null

            val canChunk = chunks > 1 && ranged && total != null && total >= threshold
            if (!canChunk) {
                // 复用已建立的连接直接流式下载，零额外开销
                return streamTo(part, body, control, meter, onDelta)
            }
            probedTotal = total
        }

        val total = probedTotal ?: return fetchPlain(
            seg, headers, part, knownLen, baseOffset, control, meter, onDelta
        )

        return runCatching {
            downloadChunked(seg, headers, part, total, chunks, baseOffset, control, meter, onDelta)
        }.getOrElse {
            // 服务器不配合 Range：退回单连接再来一次
            fetchPlain(seg, headers, part, knownLen, baseOffset, control, meter, onDelta)
        }
    }

    /** 把单个分片切成 chunks 段并发拉取，各段用 RandomAccessFile 写到各自偏移。 */
    private suspend fun downloadChunked(
        seg: Segment,
        headers: Map<String, String>,
        part: File,
        total: Long,
        chunks: Int,
        baseOffset: Long,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long) -> Unit
    ): Long = coroutineScope {
        // 预分配文件空间，避免并发写时文件反复扩容
        RandomAccessFile(part, "rw").use { it.setLength(total) }

        val per = total / chunks
        val jobs = (0 until chunks).map { i ->
            val start = i * per
            val end = if (i == chunks - 1) total - 1 else (i + 1) * per - 1
            async(Dispatchers.IO) {
                downloadRange(seg, headers, part, start, end, baseOffset, control, meter, onDelta)
            }
        }
        val written = jobs.awaitAll().sum()
        if (written != total) throw IOException("分块下载不完整：$written/$total")
        written
    }

    /** 拉取分片的一个字节区间，写入文件的指定偏移。 */
    private suspend fun downloadRange(
        seg: Segment,
        headers: Map<String, String>,
        part: File,
        start: Long,
        end: Long,
        baseOffset: Long,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long) -> Unit
    ): Long {
        val h = headers.toMutableMap().apply {
            put("Range", "bytes=${baseOffset + start}-${baseOffset + end}")
        }
        val req = Request.Builder().url(seg.uri).apply {
            h.forEach { (k, v) -> header(k, v) }
        }.build()

        client.newCall(req).execute().use { resp ->
            // 返回 200 说明服务器忽略了 Range 头，直接给了完整内容——必须降级
            if (resp.code == 200) throw IOException("服务器不支持分段请求")
            if (resp.code != 206) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")

            val buf = ByteArray(64 * 1024)
            var written = 0L
            RandomAccessFile(part, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    while (true) {
                        control.check()
                        val n = input.read(buf)
                        if (n <= 0) break
                        bucket.consume(n)
                        raf.write(buf, 0, n)
                        written += n
                        meter.add(n.toLong())
                        onDelta(n.toLong())
                    }
                }
            }
            return written
        }
    }

    /** 单连接流式下载并写入文件。 */
    private suspend fun streamTo(
        part: File,
        body: okhttp3.ResponseBody,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long) -> Unit
    ): Long {
        var downloaded = 0L
        val buf = ByteArray(64 * 1024)
        part.outputStream().use { out ->
            body.byteStream().use { input ->
                while (true) {
                    control.check()
                    val n = input.read(buf)
                    if (n <= 0) break
                    bucket.consume(n)
                    out.write(buf, 0, n)
                    downloaded += n
                    meter.add(n.toLong())
                    onDelta(n.toLong())
                }
            }
        }
        return downloaded
    }

    /** 降级路径：不带分块的单连接下载。 */
    private suspend fun fetchPlain(
        seg: Segment,
        headers: Map<String, String>,
        part: File,
        knownLen: Long?,
        baseOffset: Long,
        control: TaskControl,
        meter: SpeedMeter,
        onDelta: (Long) -> Unit
    ): Long {
        val h = headers.toMutableMap().apply {
            knownLen?.let { put("Range", "bytes=$baseOffset-${baseOffset + it - 1}") }
        }
        val req = Request.Builder().url(seg.uri).apply {
            h.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("响应为空")
            return streamTo(part, body, control, meter, onDelta)
        }
    }

    /** 从响应头解析分片总长度。 */
    private fun parseTotalLength(resp: okhttp3.Response?, knownLen: Long?): Long? {
        knownLen?.let { return it }
        resp?.header("Content-Range")?.let { cr ->
            // 形如 bytes 0-1023/45678
            cr.substringAfterLast('/').toLongOrNull()?.let { if (it > 0) return it }
        }
        return resp?.body?.contentLength()?.takeIf { it > 0 }
    }

    /** 直播：按 targetDuration 轮询播放列表，把新分片投进队列。 */
    private suspend fun pumpLive(
        mediaUrl: String,
        headers: Map<String, String>,
        original: MediaPlaylist,
        segments: MutableList<Segment>,
        seen: MutableSet<String>,
        channel: Channel<Segment>,
        control: TaskControl,
        totalSegsRef: AtomicInteger
    ) {
        val waitMs = (original.targetDuration.takeIf { it > 0 } ?: 4) * 1000L
        var idle = 0
        while (!control.isCanceled) {
            delay(waitMs)
            control.check()
            try {
                val (text, _) = source.fetchText(mediaUrl, headers)
                val media = when (val parsed = M3u8Parser.parse(text, mediaUrl)) {
                    is M3u8Parser.Result.Media -> parsed.playlist
                    is M3u8Parser.Result.Master -> return   // 结构变了，按点播收尾
                }
                val fresh = media.segments.filter { it.uri !in seen }
                if (fresh.isNotEmpty()) {
                    idle = 0
                    val base = segments.size
                    fresh.forEachIndexed { i, s ->
                        seen.add(s.uri)
                        val reindexed = s.copy(index = base + i)
                        segments.add(reindexed)
                        totalSegsRef.set(segments.size)
                        channel.send(reindexed)
                    }
                } else {
                    idle++
                    if (media.endList || idle > 30) return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                idle++
                if (idle > 5) return
            }
        }
    }

    // ──────────────── 输出 ────────────────

    /** 非 MP4 产物的落盘：单文件拼接 + 播放列表索引，方式自动择优。 */
    private val hls by lazy { HlsOutput(storage) }

    /**
     * 合并输出，按需选择路径：
     *  · 开启「转 MP4」：合并成 TS → 空间紧就先删分片 → 依次尝试多种 MP4 封装方式，
     *    全部失败则把已合并好的 TS 直接落盘（不再拼一遍分片）。
     *  · 未开启：交给 [HlsOutput] 合并成**一个** TS 文件（细节见该类）。
     *
     * 两条路径都保证给出明确结局：成功 → Completed，失败 → Error。
     * 之前输出保存失败时任务会一直停在「合并中」，就是因为兜底分支自己又抛了异常
     * 却没人收尾；现在异常一律抛给 [run] 的兜底分支统一处理。
     */
    private suspend fun mergeToOutput(
        task: DownloadTask,
        segments: List<Segment>,
        initData: ByteArray?,
        tmpDir: File,
        control: TaskControl,
        onPhase: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): HlsOutput.Output = withContext(Dispatchers.IO) {
        if (task.toMp4) {
            convertToMp4(task, segments, initData, tmpDir, control, onPhase, onProgress)
        } else {
            saveSingleFile(task, segments, initData, tmpDir, onPhase, onProgress)
        }
    }

    /**
     * 未开启转 MP4：把分片合并成**一个** TS 文件（下载目录里只有这一个文件）。
     *
     * 不再把每个分片逐个复制到下载目录（上千次建文件 + 上千次全量拷贝，在 SAF 目录上
     * 慢得离谱，开播还要逐个打开），也不再额外写一份 .m3u8 索引——细节见 [HlsOutput]。
     * 转换完成后临时分片由 [run] 统一清理。
     */
    private fun saveSingleFile(
        task: DownloadTask,
        segments: List<Segment>,
        initData: ByteArray?,
        tmpDir: File,
        onPhase: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): HlsOutput.Output = hls.write(
        dirUri = task.saveDirUri,
        baseName = task.name,
        segments = segments,
        initData = initData,
        tmpDir = tmpDir,
        onPhase = onPhase,
        onProgress = onProgress
    )

    /** 开启转 MP4：合并 → 删分片 → ffmpeg 转 MP4（拷贝优先）→ 全失败回退 TS。 */
    private suspend fun convertToMp4(
        task: DownloadTask,
        segments: List<Segment>,
        initData: ByteArray?,
        tmpDir: File,
        control: TaskControl,
        onPhase: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): HlsOutput.Output = withContext(Dispatchers.IO) {
        // 先合并成 .ts 到私有缓存（合并占进度 0~50%）
        val tmpTs = File(context.cacheDir, "${task.id}_merge.ts")
        val outMp4 = File(context.cacheDir, "${task.id}_out.mp4")
        try {
            onPhase("合并分片中…")
            tmpTs.outputStream().use { out ->
                Merger.merge(tmpDir, segments, out, initData) { done, total ->
                    if (total > 0) onProgress(done.toFloat() / total * 0.5f)
                }
            }
            onProgress(0.5f)

            // 空间吃紧时先删分片再转换：否则"分片 + 合并 TS + 成品"三份同时存在会写不下。
            // 空间充足则保留分片，好在转换失败时直接把合并好的 TS 落盘（省一次二次合并）。
            val lowSpace = storage.cacheFreeBytes() < tmpTs.length() * 2
            if (!settings.keepSegments && lowSpace) {
                onPhase("空间不足，清理分片…")
                storage.clearTemp(task.id)
            }

            try {
                onPhase("转换为 MP4…")
                val result = FfmpegConverter.convert(
                    tmpTs, outMp4,
                    isCancelled = { control.isCanceled },
                    onProgress = { p -> onProgress(0.5f + p * 0.35f) }
                )
                onPhase("写入文件（${result.method}）…")
                val out = writeFile(
                    task, result.file, ext = "mp4", mime = "video/mp4",
                    onProgress = { onProgress(0.85f + it * 0.15f) }
                )
                onProgress(1f)
                return@withContext out
            } catch (e: Exception) {
                // 所有 MP4 方式都失败：把已经合并好的 TS 直接落盘（不再重复拼分片），
                // 顺带写一份播放列表索引。合并文件/分片都没了才会重新拼。
                onPhase("MP4 转换失败，回退保存 TS…")
                onProgress(0.5f)
                val merged = tmpTs.takeIf { it.exists() && it.length() > 0 }
                val out = hls.write(
                    dirUri = task.saveDirUri,
                    baseName = task.name,
                    segments = segments,
                    initData = initData,
                    tmpDir = tmpDir,
                    source = merged,
                    onPhase = onPhase,
                    onProgress = { onProgress(0.5f + it * 0.5f) }
                )
                onProgress(1f)
                return@withContext out
            }
        } finally {
            runCatching { tmpTs.delete() }
            runCatching { outMp4.delete() }
        }
    }

    /**
     * 把已经准备好的文件拷贝到下载目录（MP4 成品路径）。
     *
     * 失败时必须把半成品删掉：否则用户目录里会留下一个打不开的 "xxx.mp4"，
     * 任务状态是失败，文件却在那儿，非常容易误以为下好了。
     * 目标目录若被系统拒绝写入，会自动回退到内部目录（见 [Storage.createFileRelocating]）。
     */
    private fun writeFile(
        task: DownloadTask,
        source: File,
        ext: String,
        mime: String,
        onProgress: (Float) -> Unit = {}
    ): HlsOutput.Output {
        var created: Storage.CreatedFile? = null
        var usedDir = task.saveDirUri
        return try {
            val res = storage.createFileRelocating(task.saveDirUri, "${task.name}.$ext", mime)
            created = res.file
            usedDir = res.dirUri
            res.file.stream.use { out -> copyWithProgress(source, out, onProgress) }
            HlsOutput.Output(res.file.uri, res.file.name, usedDir)
        } catch (e: Exception) {
            created?.let {
                runCatching { storage.deleteUri(it.uri) }
                runCatching { storage.delete(usedDir, it.name) }
            }
            throw e
        }
    }
}
