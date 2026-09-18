package com.flux.m3u8.download

import android.content.Context
import android.os.SystemClock
import com.flux.m3u8.data.Settings
import com.flux.m3u8.data.Storage
import com.flux.m3u8.data.TaskDb
import com.flux.m3u8.m3u8.M3u8Parser
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.ProbeInfo
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.playback.PlaybackManager
import com.flux.m3u8.util.TempCleaner
import com.flux.m3u8.util.Camouflage
import com.flux.m3u8.util.safeFileName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载调度中心（进程内单例）。
 *
 * 这里是解决"挂后台就没速度"的关键：
 *  · 所有下载协程跑在 [scope] 里，通过 [DownloadService] 的前台服务保活，
 *    与 Activity / 界面完全无关——退出界面、锁屏、切到别的应用都不受影响；
 *  · 界面只是观察 [tasks] 这个 StateFlow 的旁观者，
 *    就算 Activity 被系统回收，下载也照常进行。
 */
object DownloadManager {

    private lateinit var app: Context
    private lateinit var settings: Settings
    private lateinit var storage: Storage
    private lateinit var db: TaskDb

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val controls = ConcurrentHashMap<String, TaskControl>()
    private val meters = ConcurrentHashMap<String, SpeedMeter>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 「仅 Wi-Fi」自动暂停的任务 id：Wi-Fi 恢复后只恢复这些，不动用户手动暂停的。 */
    private val wifiPausedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 用户是否已"完全退出"应用。
     * 退出后即使还有暂停任务，也不再自动拉起前台服务/通知，直到应用重新回到前台。
     */
    @Volatile
    private var userExited = false

    /** 全局速度（最近 3 秒滑动窗口）。 */
    fun totalSpeed(): Long = meters.values.sumOf { it.rate() }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = Settings(app)
        storage = Storage(app)
        db = TaskDb(app)
        GlobalLimiter.configure(settings.speedLimitKbps)
        PlaybackManager.init(app)
        refresh()
        sweepTempAsync()
        // 进程被杀后重新打开 App 也要把未完成任务捡起来。
        // 之前只有开机广播会触发，导致"划掉应用再打开"后任务一直卡在"下载中"不动。
        // 延迟一点执行，先让首屏渲染完，避免和数据库读取抢资源。
        scope.launch(Dispatchers.IO) {
            delay(1500)
            runCatching { restoreUnfinished() }
        }
    }

    /**
     * 启动时异步清理残留临时文件。
     *
     * 主要清理上次崩溃 / 被强杀后留下的孤儿目录。
     * 异步执行且延迟 2 秒，避免和启动时的数据库读取抢主线程。
     */
    private fun sweepTempAsync() {
        scope.launch(Dispatchers.IO) {
            delay(2000)
            runCatching { cleanTempFiles() }
        }
    }

    /**
     * 清理残留临时分片。
     *
     * 保留规则：正在下载、暂停待续传的任务 -> 分片必须留着，否则断点续传会失效；
     * 已完成的任务 -> 只有开启「保留分片」时才留，否则删。
     *
     * @return 释放的字节数
     */
    fun cleanTempFiles(): Long {
        val all = db.all()
        val knownIds = all.map { it.id }.toSet()
        // 已完成/已取消的任务：若没开「保留分片」，其分片就是纯粹的垃圾
        val finishedIds = if (settings.keepSegments) emptySet() else all.filter {
            it.status == TaskStatus.Completed || it.status == TaskStatus.Canceled
        }.map { it.id }.toSet()

        val result = TempCleaner.sweep(app, knownIds, finishedIds)
        return result.freedBytes
    }

    /** 清空所有临时文件（设置页「立即清理」用，谨慎调用）。 */
    fun clearAllTemp() {
        TempCleaner.clearAll(app)
    }

    /** 临时文件占用字节数。 */
    fun tempUsage(): Long = TempCleaner.sizeOf(app)

    fun settings(): Settings = settings
    fun storage(): Storage = storage
    fun db(): TaskDb = db

    private fun refresh() {
        val list = db.all()
        _tasks.value = list
        syncControls(list)
    }

    /** 让控件与数据库状态保持一致（重启后恢复）。 */
    private fun syncControls(list: List<DownloadTask>) {
        val alive = list.map { it.id }.toSet()
        controls.keys.retainAll(alive)
        meters.keys.retainAll(alive)
        // 数据库里是暂停/出错的任务，补一个已暂停的控制器，避免恢复时被判为"未取消"
        list.forEach { t ->
            if (!controls.containsKey(t.id)) {
                val c = TaskControl()
                if (t.status == TaskStatus.Paused || t.status == TaskStatus.Error || t.status == TaskStatus.Canceled) {
                    c.pause()
                }
                controls[t.id] = c
            }
        }
    }

    /**
     * UI 推送节流：**每个任务独立**，最多约 3 次/秒。
     *
     * 不能用全局一个时间戳：多任务 tick（各 400ms）相位错开时，
     * 后到的任务更新会因"距上次全局推送不足 300ms"被反复丢弃，
     * 表现就是进度条长时间不动、任务结束后猛地跳到真实进度。
     * 每任务独立计时后，各任务的进度互不干扰、持续刷新。
     */
    private val lastUiPushByTask = ConcurrentHashMap<String, Long>()

    private fun update(task: DownloadTask, persistStatus: Boolean = true) {
        // 控制器若已处于暂停态，任何“活跃”状态的进度回写都不能把状态顶回去。
        // 否则暂停后每 400ms 一次的进度回调会立刻把状态改回“下载中”，
        // 表现为：列表状态不变、点“全部开始”找不到暂停任务、只能强杀重开。
        val control = controls[task.id]
        val effective =
            if (control != null && control.isPaused && !control.isCanceled && task.status.isActive) {
                task.copy(status = TaskStatus.Paused, speedBps = 0)
            } else {
                task
            }

        val list = _tasks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == effective.id }
        if (idx >= 0) list[idx] = effective else list.add(0, effective)
        val prevStatus = if (idx >= 0) _tasks.value[idx].status else null
        val statusChanged = prevStatus != effective.status
        val now = SystemClock.elapsedRealtime()
        if (statusChanged || now - (lastUiPushByTask[effective.id] ?: 0L) >= 300) {
            _tasks.value = list.sortedByDescending { it.createdAt }
            lastUiPushByTask[effective.id] = now
        }
        // 状态落库异步化：update() 可能被 UI 线程直接调用（如新建任务），
        // 阻塞式 SQLite 写在锁竞争时会把界面拖死。
        if (persistStatus) {
            val snap = effective
            scope.launch(Dispatchers.IO) { runCatching { db.updateStatus(snap.id, snap.status, snap.error) } }
        }
        ensureService()
    }

    // ──────────────── 对外操作 ────────────────

    /** 解析预览：新建任务前先看时长、清晰度、分片数。 */
    suspend fun probe(url: String, referer: String = "", ua: String = "", cookie: String = ""): ProbeInfo =
        withContext(Dispatchers.IO) {
            val headers = HttpFactory.buildHeaders(url, settings, referer, ua, cookie)
            val req = okhttp3.Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            HttpFactory.get(settings).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val body = resp.body?.string() ?: throw java.io.IOException("响应为空")
                if (!M3u8Parser.isValid(body)) throw java.io.IOException("不是有效的 m3u8 内容")
                when (val r = M3u8Parser.parse(body, url)) {
                    is M3u8Parser.Result.Master -> ProbeInfo(
                        isMaster = true,
                        variants = r.playlist.sorted(),
                        suggestedName = M3u8Parser.guessName(url)
                    )
                    is M3u8Parser.Result.Media -> ProbeInfo(
                        isMaster = false,
                        durationSeconds = r.playlist.duration.toInt(),
                        segmentCount = r.playlist.segments.size,
                        encrypted = r.playlist.encrypted,
                        isLive = r.playlist.isLive,
                        isFmp4 = r.playlist.isFmp4,
                        suggestedName = M3u8Parser.guessName(url)
                    )
                }
            }
        }

    /**
     * 保存目录里是否已有同名产物（`.mp4` / `.m3u8` / `.ts`）。
     *
     * 供"新建任务"弹窗在开下之前提示用户：有重名就不让开始，避免下完才发现写不进去。
     * 涉及文件 IO，调用方务必放到 IO 线程。
     *
     * @return 冲突的文件名；没有冲突返回 null
     */
    fun outputConflict(dirUri: String, name: String, toMp4: Boolean): String? =
        runCatching { storage.conflictOutput(dirUri, safeFileName(name), toMp4) }.getOrNull()

    /** 新建任务并立即开始。 */
    fun add(
        url: String,
        name: String,
        saveDir: String = settings.saveDir,
        connections: Int = settings.connections,
        toMp4: Boolean = settings.toMp4,
        referer: String = "",
        userAgent: String = "",
        cookie: String = "",
        variantIndex: Int? = null
    ): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        // 轻量权限校验：SAF 授权若已失效，直接回退内部目录，
        // 免得用户"选了目录、文件却存到别处"还浑然不觉。
        // 真正落盘的探针检查由 TaskRunner 在下载前（IO 线程）完成。
        val resolvedDir =
            if (Storage.isInternal(saveDir) || storage.hasTreePermission(saveDir)) saveDir
            else Storage.INTERNAL
        val task = DownloadTask(
            id = id,
            url = url.trim(),
            name = safeFileName(name.ifBlank { M3u8Parser.guessName(url) }),
            saveDirUri = resolvedDir,
            status = TaskStatus.Queued,
            connections = connections.coerceIn(1, 64),
            toMp4 = toMp4,
            referer = referer,
            userAgent = userAgent,
            cookie = cookie,
            variantIndex = variantIndex
        )
        // 落库放到后台：SQLite 写即使有 TaskDb 内部锁串行，也不该在 UI 线程上等锁。
        // 之前 add() 直接在 UI 线程阻塞写库，下载 ticker 抢锁时界面会整屏卡死。
        val persist = task
        scope.launch(Dispatchers.IO) { runCatching { db.insert(persist) } }
        controls[id] = TaskControl()
        meters[id] = SpeedMeter()
        val list = (_tasks.value + task).sortedByDescending { it.createdAt }
        _tasks.value = list
        userExited = false
        start(task)
        return id
    }

    /** 启动（或恢复）任务。 */
    @Synchronized
    fun start(task: DownloadTask) {
        val control = controls.getOrPut(task.id) { TaskControl() }
        val meter = meters.getOrPut(task.id) { SpeedMeter() }
        meter.reset()
        control.resume()
        userExited = false

        if (jobs[task.id]?.isActive == true) return

        // 并发上限：超出的排队等待。
        // 两个坑都要避开：
        //  ① 用 isRunning 而不是 isActive——排队中的任务不能占槽位，否则队头永远轮不到；
        //  ② 把"要启动的这一个"排除在计数外——恢复/重试时它在列表里可能已经是
        //     "下载中"，算进去会让上限为 N 的任务实际只能跑 N-1 个，
        //     上限设成 1 时甚至一个都起不来（"设置 1 个并发却完全不下"就是这个）。
        val running = _tasks.value.count { it.status.isRunning && it.id != task.id }
        if (running >= settings.maxRunning) {
            update(task.copy(status = TaskStatus.Queued))
            return
        }

        jobs[task.id] = scope.launch {
            try {
                val runner = TaskRunner(app, settings, storage, db)
                val result = runner.run(task, control, meter) { updated ->
                    // 子协程里的回调，用 launch 保证线程安全
                    scope.launch { update(updated, persistStatus = false) }
                }
                update(result)
            } finally {
                // 无论正常结束还是异常逃逸，都必须让出任务槽位并补位队头，
                // 否则任务会"看起来还在跑"实际协程已死（状态永久停在下载中/解析中）。
                jobs.remove(task.id)
                pumpQueue()
            }
        }
        ensureService()
    }

    /**
     * 有任务结束后，把排队中的任务按并发上限逐个拉起来。
     *
     * 「全部开始」也是走 [start]，因此同样受这个上限约束：多出来的会自动排队，
     * 前面的下完再依次补位，不会一次性全开。
     */
    private fun pumpQueue() {
        var guard = _tasks.value.size + 1
        while (guard-- > 0) {
            // 只数真正在跑的任务，排队中的不占槽位
            val running = _tasks.value.count { it.status.isRunning }
            if (running >= settings.maxRunning) return
            val next = _tasks.value.firstOrNull { it.status == TaskStatus.Queued } ?: return
            start(next)
            // start() 没接住（例如并发又被占满）就别再转圈了
            if (_tasks.value.firstOrNull { it.id == next.id }?.status == TaskStatus.Queued) return
        }
    }

    fun pause(id: String) {
        controls[id]?.pause()
        _tasks.value.firstOrNull { it.id == id }?.let {
            update(it.copy(status = TaskStatus.Paused, speedBps = 0))
        }
        ensureService()
    }

    fun resume(id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        if (t.status == TaskStatus.Completed) return
        userExited = false
        controls[id]?.resume()
        if (jobs[id]?.isActive == true) {
            update(t.copy(status = TaskStatus.Downloading))
        } else {
            start(t.copy(status = TaskStatus.Queued))
        }
    }

    /**
     * 编辑非活跃任务（暂停 / 取消 / 失败）的参数。
     * 改动 URL 会让已下内容失效，因此会清空进度与临时分片。
     */
    fun editTask(
        id: String,
        name: String,
        url: String,
        referer: String,
        cookie: String,
        connections: Int,
        toMp4: Boolean
    ) {
        val old = _tasks.value.firstOrNull { it.id == id } ?: return
        if (old.status.isActive) return   // 活跃任务不能编辑，先暂停/取消

        val newUrl = url.trim()
        val urlChanged = newUrl != old.url
        val newName = safeFileName(name.ifBlank { old.name })
        val nameChanged = newName != old.name
        val updated = old.copy(
            name = newName,
            url = newUrl,
            referer = referer,
            cookie = cookie,
            connections = connections.coerceIn(1, 64),
            toMp4 = toMp4,
            // URL 变了：旧的进度/输出全部作废，让任务回到可重新开始的状态
            status = if (urlChanged) TaskStatus.Paused else old.status,
            // 改名/换链接都可能解开"同名文件"冲突，清掉旧的报错让用户能重新判断
            error = if (urlChanged || nameChanged) null else old.error,
            phaseLabel = "",
            mergeProgress = 0f,
            doneSegments = if (urlChanged) 0 else old.doneSegments,
            doneBytes = if (urlChanged) 0 else old.doneBytes,
            totalSegments = if (urlChanged) 0 else old.totalSegments,
            totalBytes = if (urlChanged) 0 else old.totalBytes,
            estimated = if (urlChanged) false else old.estimated,
            outputUri = if (urlChanged) null else old.outputUri,
            outputName = if (urlChanged) null else old.outputName,
            speedBps = 0
        )
        if (urlChanged) {
            storage.clearTemp(id)
            controls[id] = TaskControl().apply { pause() }
            meters[id] = SpeedMeter()
        }
        val persist = updated
        scope.launch(Dispatchers.IO) { runCatching { db.insert(persist) } }
        val list = _tasks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = updated else list.add(0, updated)
        _tasks.value = list.sortedByDescending { it.createdAt }
        ensureService()
    }

    // ──────────────── 网络策略（仅 Wi-Fi） ────────────────

    /** 应用回到前台：解除"已退出"标记，按需重新拉起服务。 */
    fun onAppForeground() {
        userExited = false
        ensureService()
    }

    /**
     * 网络变化时由前台服务回调。
     *
     * 开启「仅 Wi-Fi」时：
     *  · 掉到蜂窝网络 → 自动暂停正在下载的任务（记住 id，不干扰用户手动暂停的）
     *  · 回到 Wi-Fi → 只恢复被自动暂停的那些
     */
    fun onNetworkPolicy(onWifi: Boolean) {
        if (!settings.onlyWifi) {
            if (wifiPausedIds.isNotEmpty()) {
                val ids = wifiPausedIds.toList()
                wifiPausedIds.clear()
                ids.forEach { resume(it) }
            }
            return
        }
        if (!onWifi) {
            _tasks.value.filter { it.status.isActive }.forEach {
                wifiPausedIds.add(it.id)
                pause(it.id)
            }
        } else if (wifiPausedIds.isNotEmpty()) {
            val ids = wifiPausedIds.toList()
            wifiPausedIds.clear()
            ids.forEach { resume(it) }
        }
    }

    fun cancel(id: String) {
        controls[id]?.cancel()
        jobs[id]?.cancel()
        _tasks.value.firstOrNull { it.id == id }?.let {
            update(it.copy(status = TaskStatus.Canceled, speedBps = 0))
        }
        storage.clearTemp(id)
        ensureService()
    }

    fun retry(id: String) {
        val old = _tasks.value.firstOrNull { it.id == id } ?: return
        cancel(id)
        storage.clearTemp(id)
        val fresh = old.copy(
            status = TaskStatus.Queued,
            error = null,
            doneSegments = 0,
            doneBytes = 0,
            speedBps = 0
        )
        controls[id] = TaskControl()
        meters[id] = SpeedMeter()
        update(fresh)
        start(fresh)
    }

    /**
     * 移除任务。
     *
     * @param deleteFile 是否连本地已下载的文件一起删。默认**只删任务记录、保留文件**——
     *   用户常常只是想清列表，文件还要留着看。界面会先问清楚（见 `RemoveTaskDialog`）。
     */
    fun remove(id: String, deleteFile: Boolean = false) {
        val target = _tasks.value.firstOrNull { it.id == id }
        cancel(id)
        if (deleteFile && target != null) {
            val snap = target
            scope.launch(Dispatchers.IO) {
                runCatching { deleteOutputs(snap) }
                runCatching { db.delete(id) }
            }
        } else {
            scope.launch(Dispatchers.IO) { runCatching { db.delete(id) } }
        }
        controls.remove(id)
        meters.remove(id)
        PlaybackManager.close(id)
        _tasks.value = _tasks.value.filterNot { it.id == id }
        ensureService()
    }

    /**
     * 删除任务产出的本地文件。
     *
     * 产物名随输出方式而变（单个 TS / MP4；`.m3u8` 与逐片文件是旧版本产物），
     * 所以按「任务名 + 常见扩展名」逐个清，避免留下一堆不认识来源的孤儿文件。
     * 删不掉的（文件已不存在、目录授权失效）直接忽略：这是收尾动作，不该再报错。
     */
    private fun deleteOutputs(task: DownloadTask) {
        val names = linkedSetOf<String>()
        task.outputName?.takeIf { it.isNotBlank() }?.let { names += it }
        names += "${task.name}.ts"
        names += "${task.name}.m3u8"
        names += "${task.name}.mp4"
        names.forEach { runCatching { storage.delete(task.saveDirUri, it) } }
    }

    /**
     * 「防相册识别」转换/还原：把已完成任务的输出文件在
     * 可识别后缀（`.mp4` / `.ts`）与隐藏后缀（`.flux`）之间切换。
     *
     * 只改文件名、不改内容；成功后同步更新任务记录与数据库，
     * 播放器按文件名里保留的原始扩展名显式指定 MIME 播放。
     *
     * @return null 表示成功；非 null 为失败原因（供 UI 直接提示）
     */
    suspend fun toggleCamouflage(id: String): String? = withContext(Dispatchers.IO) {
        val task = get(id) ?: return@withContext "任务不存在"
        if (task.status != TaskStatus.Completed) return@withContext "仅已完成的任务可以转换/还原"
        val curName = task.outputName?.takeIf { it.isNotBlank() }
            ?: return@withContext "没有可转换的输出文件"
        val newName = if (Camouflage.isHidden(curName)) Camouflage.restoreName(curName)
        else Camouflage.hideName(curName)
        if (storage.exists(task.saveDirUri, newName)) {
            return@withContext "目录中已存在同名文件「$newName」，请先处理它再试"
        }
        val newUri = storage.rename(task.saveDirUri, curName, newName)
            ?: return@withContext "文件重命名失败，请检查保存目录权限"
        val updated = task.copy(outputName = newName, outputUri = newUri)
        db.updateOutput(id, newUri, newName)
        val list = _tasks.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = updated
        _tasks.value = list
        null
    }

    fun pauseAll() = _tasks.value.filter { it.status.isActive }.forEach { pause(it.id) }

    fun resumeAll() {
        _tasks.value.filter {
            it.status == TaskStatus.Paused ||
                it.status == TaskStatus.Error ||
                // 状态是“活跃”但实际没有运行中的协程（例如被强行停止后残留的“下载中”），
                // 也要能被“全部开始”重新拉起，否则点了没反应。
                (it.status.isActive && jobs[it.id]?.isActive != true)
        }.forEach { resume(it.id) }
        // 收尾再补一次：上面的循环里若因并发上限排了队，这里保证队头能被拉起
        pumpQueue()
    }

    fun cancelAll() = _tasks.value.filter { it.status.isActive }.forEach { cancel(it.id) }

    fun clearFinished() {
        val finished = _tasks.value.filter { !it.status.isActive && it.status != TaskStatus.Paused }
        val ids = finished.map { it.id }
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            ids.forEach { runCatching { storage.clearTemp(it) } }
            runCatching { db.clearFinished() }
        }
        controls.keys.removeAll(ids)
        meters.keys.removeAll(ids)
        _tasks.value = _tasks.value.filterNot { it.id in ids }
        ensureService()
    }

    private val restored = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 重启后恢复未完成的任务（由 BootReceiver 或 App 启动时调用）。 */
    @Synchronized
    fun restoreUnfinished() {
        if (!settings.autoResume) return
        // 开机广播与 App 启动可能都触发，去重避免同一个任务被拉起两遍
        if (!restored.compareAndSet(false, true)) return
        db.resumable().forEach { t ->
            controls[t.id] = TaskControl()
            meters[t.id] = SpeedMeter()
            start(t)
        }
        // 超出并发上限的已排队，等前面的任务结束会自动补位；这里再兜一次底
        pumpQueue()
    }

    /**
     * 异步恢复（BootReceiver 用）。
     *
     * 走 DownloadManager 自己的作用域，BroadcastReceiver 只负责在结束时
     * 调 [onDone] 收尾——以前直接在 Receiver 里开 GlobalScope，
     * 生命周期完全不受控。
     */
    fun restoreUnfinishedAsync(delayMs: Long = 3000, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            try {
                delay(delayMs)
                runCatching { restoreUnfinished() }
            } finally {
                onDone()
            }
        }
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    /** 影响网络栈的设置指纹，只有它变了才需要重建 OkHttp 客户端。 */
    private var networkFingerprint: String? = null

    private fun currentFingerprint(): String = listOf(
        settings.proxyHost, settings.proxyPort, settings.timeoutSeconds, settings.insecureTls,
        settings.connections, settings.maxRunning, settings.chunkThreads
    ).joinToString("|")

    /** 设置变更后调用：按需重建 HTTP 客户端并按新的并发/限速上限调度。 */
    fun onSettingsChanged() {
        // 设置页里每敲一个字符都会走到这里。若无脑 invalidate()，
        // 正在下载的连接池会被反复销毁重建，速度直接塌方。
        val fp = currentFingerprint()
        if (fp != networkFingerprint) {
            networkFingerprint = fp
            HttpFactory.invalidate()
        }
        // 限速改动立即对正在下载的任务生效（共用一个全局令牌桶）
        GlobalLimiter.configure(settings.speedLimitKbps)
        // 「仅 Wi-Fi」开关改动后立刻按当前网络重新裁决
        runCatching { onNetworkPolicy(com.flux.m3u8.util.NetworkUtil.isOnWifi(app)) }
        pumpQueue()
        ensureService()
    }

    // 服务启停状态缓存：update() 每 400ms 就会调一次 ensureService，
    // 不加这层判断等于每秒多次 startForegroundService，纯属浪费还可能引发 ANR。
    private val serviceLock = Any()
    private var serviceRunning: Boolean? = null

    /** 有活跃任务或暂停中的任务（都需要保活/通知入口）。 */
    fun hasWork(): Boolean = _tasks.value.any {
        it.status.isActive || it.status == TaskStatus.Paused
    }

    /** 有活跃任务（或暂停中的任务）就拉起前台服务，没有就撤掉（省电）。 */
    private fun ensureService() {
        // 用户已完全退出：不要在后台偷偷把服务和通知拉起来
        if (userExited) return
        val list = _tasks.value
        val hasActive = list.any { it.status.isActive }
        // 暂停中的任务也要保留服务：服务一旦停掉，系统会连同通知一起移除，
        // 通知栏上的“全部开始”恢复入口就没了。
        val hasPaused = list.any { it.status == TaskStatus.Paused }
        val shouldRun = hasActive || hasPaused
        val changed = synchronized(serviceLock) {
            if (serviceRunning == shouldRun) false else {
                serviceRunning = shouldRun
                true
            }
        }
        if (!changed) return
        if (shouldRun) {
            runCatching { DownloadService.start(app) }
        } else {
            runCatching { DownloadService.stop(app) }
        }
    }

    /** 当前是否有真正在跑（活跃）的任务。 */
    fun hasActive(): Boolean = _tasks.value.any { it.status.isActive }

    /**
     * 用户退出应用时调用：没有活跃任务就彻底停掉前台服务并清空所有通知。
     *
     * 关键是同时置上 [userExited]：否则退出后残留的进度回调仍会触发
     * [ensureService]，把服务与通知又拉回来——这正是"有暂停任务时通知栏
     * 一直不消失"的根因。
     */
    fun shutdownIfIdle() {
        if (hasActive()) return
        userExited = true
        synchronized(serviceLock) { serviceRunning = false }
        runCatching { DownloadService.stop(app) }
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancelAll()
        }
    }
}
