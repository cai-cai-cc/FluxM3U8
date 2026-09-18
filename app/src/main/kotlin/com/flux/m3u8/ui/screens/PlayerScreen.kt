package com.flux.m3u8.ui.screens

import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.SeekBar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.playback.PlaybackManager
import com.flux.m3u8.util.Camouflage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放位置记忆 SharedPreferences 名称和 key 前缀。 */
private const val POSITION_PREFS = "player_positions"
private const val KEY_POSITION = "pos_"

/** 顶部/底部 UI 栏的高度，用于把手势覆盖层里的点击与栏上按钮区分开。 */
private val TOP_BAR_DP = 60.dp
private val BOTTOM_BAR_DP = 88.dp

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

/**
 * 边下边播播放页。
 *
 * 播放源：
 *  · **已完成任务**：直接播放输出文件的 file:// 或 content:// 地址
 *  · **下载中 / 暂停 / 失败 / 取消**：走本地代理服务器
 *
 * UI 全部用 Compose 自绘（顶部标题栏 + 底部进度栏 + 手势覆盖层），
 * 不依赖 PlayerView 自带的控制器，避免其生命周期与进度条被底层实现的改动影响。
 *
 * 手势：
 *  · 单击视频区 → 切换顶部/底部栏的显示
 *  · 双击左 1/3 → 倒退 10 秒
 *  · 双击中间 1/3 → 暂停 / 播放
 *  · 双击右 1/3 → 快进 10 秒
 *  · 长按 → 3x 快速播放；手指松开立即恢复
 *
 * 播放位置记忆：按任务 id 存 SharedPreferences，再次打开自动恢复（仅本地播放）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    taskId: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val tasks by DownloadManager.tasks.collectAsState()
    val task = tasks.firstOrNull { it.id == taskId }
    val activity = context as? Activity

    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }

    // 音频轨兜底：转封装 MP4 的 AAC 头损坏会导致永久缓冲，丢弃音频轨只播视频
    var audioDisabledFallback by remember { mutableStateOf(false) }
    // 已到 READY 一次（用于区分"从头缓冲"与"READY 后掉回缓冲"）
    var seenReady by remember(taskId, audioDisabledFallback) { mutableStateOf(false) }
    // 是否真正开播过（isPlaying 变 true 即置位）。
    // 音频兜底只在"从未开播"的启动阶段触发：用户拖动进度条产生的缓冲是正常 seek，不算音频损坏。
    var everStarted by remember { mutableStateOf(false) }

    // UI + 播放状态
    var chromeVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }

    // 倍速
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    val speedOptions = remember {
        listOf(
            0.5f to "0.5x", 0.75f to "0.75x", 1f to "1.0x", 1.25f to "1.25x",
            1.5f to "1.5x", 2f to "2.0x", 3f to "3.0x"
        )
    }
    val speedLabel = speedOptions.firstOrNull { it.first == speed }?.second ?: "${speed}x"

    // 长按 3x 提示
    var showSpeedBoostHint by remember { mutableStateOf(false) }

    // 横向滑动的目标时间预览（视频最底部显示）
    var seekPreviewMs by remember { mutableLongStateOf(-1L) }
    var showSeekPreview by remember { mutableStateOf(false) }

    // 本地播放（已完成任务）才支持画面预览
    var isLocalPlayback by remember { mutableStateOf(false) }
    var playbackUri by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 单击/双击识别状态
    var firstTapPos by remember { mutableStateOf<Offset?>(null) }
    var firstTapTime by remember { mutableStateOf(0L) }
    val gestureScope = remember { kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate) }

    // SharedPreferences 用于记忆播放位置
    val posPrefs: SharedPreferences = remember {
        context.applicationContext.getSharedPreferences(POSITION_PREFS, 0)
    }

    // 倍速变化时应用给播放器
    LaunchedEffect(speed, exoPlayer) {
        exoPlayer?.setPlaybackSpeed(speed)
    }

    // 轮询获取进度 / 时长 / 缓冲位置
    LaunchedEffect(exoPlayer) {
        while (true) {
            val p = exoPlayer ?: break
            if (!isSeeking) positionMs = p.currentPosition
            durationMs = p.duration.coerceAtLeast(0L)
            delay(300)
        }
    }

    // 本地播放时才抽帧做画面预览（滑动抗抖）
    LaunchedEffect(seekPreviewMs, isLocalPlayback) {
        previewBitmap = null
        if (!isLocalPlayback || seekPreviewMs < 0) return@LaunchedEffect
        val targetMs = seekPreviewMs
        val uri = playbackUri ?: return@LaunchedEffect
        delay(120)
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, Uri.parse(uri))
                    mmr.getFrameAtTime(targetMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    mmr.release()
                }
            }.getOrNull()
        }
        // 关键帧后时间变了就丢弃过期结果
        if (seekPreviewMs == targetMs) previewBitmap = bmp
    }

    // 栏显示 3 秒后自动隐藏（仅播放中）
    LaunchedEffect(chromeVisible, isPlaying) {
        if (chromeVisible && isPlaying) {
            delay(3000)
            if (isPlaying) chromeVisible = false
        }
    }

    // 本地播放失败兜底：READY 后掉回 BUFFERING 且一直没在播（说明转封装 MP4 的音频轨坏），
    // 等待 2.2s 确认后丢弃音频轨只播视频，重建播放器。
    // 在 Compose 的 LaunchedEffect 协程里定时，避免依赖播放器内部 Handler 协程卡死。
    // 条件里必须有 !everStarted：一旦真正开播过，音频就证明是好的，
    // 之后 seek 拉进度条产生的缓冲是正常现象，不能误触发兜底。
    LaunchedEffect(buffering, seenReady, audioDisabledFallback, exoPlayer, everStarted) {
        if (isLocalPlayback && seenReady && buffering && !audioDisabledFallback && exoPlayer != null && !isPlaying && !everStarted) {
            delay(2200)
            if (buffering && !isPlaying && seenReady && !audioDisabledFallback && exoPlayer != null && !everStarted) {
                audioDisabledFallback = true
                retryKey++
            }
        }
    }

    // 进入播放页记录原方向；退出时恢复
    DisposableEffect(Unit) {
        val window = activity?.window
        val prevOrientation = activity?.requestedOrientation
        onDispose {
            gestureScope.cancel()
            if (activity != null && window != null) {
                activity.requestedOrientation =
                    if (prevOrientation != null && prevOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                        prevOrientation
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 进入播放页：横屏 + 沉浸式全屏
    LaunchedEffect(Unit) {
        val act = activity ?: return@LaunchedEffect
        val window = act.window
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 任务被取消 / 移除 → 退出
    val taskExists = tasks.any { it.id == taskId }
    LaunchedEffect(taskExists) {
        if (!taskExists) onClose()
    }

    DisposableEffect(taskId, retryKey) {
        var player: ExoPlayer? = null
        var disposed = false
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)

        scope.launch {
            error = null
            buffering = false
            try {
                val current = DownloadManager.get(taskId)
                    ?: throw IllegalStateException("任务不存在或已被移除")

                // 只有已完成的任务才直接播本地文件；其他状态一律走代理
                val url = if (current.status == TaskStatus.Completed) {
                    PlaybackManager.localFileUri(current) ?: run {
                        PlaybackManager.close(taskId)
                        withContext(Dispatchers.IO) { PlaybackManager.open(current) }
                    }
                } else {
                    PlaybackManager.close(taskId)
                    withContext(Dispatchers.IO) { PlaybackManager.open(current) }
                }
                if (disposed) return@launch

                // 已完成任务：验证输出文件确实存在
                if (current.status == TaskStatus.Completed) {
                    val uri = Uri.parse(url)
                    val exists = runCatching {
                        when (uri.scheme) {
                            "file" -> java.io.File(uri.path ?: "").exists()
                            "content" -> context.contentResolver.query(uri, null, null, null, null)
                                ?.use { it.count > 0 }
                                ?: false
                            else -> true
                        }
                    }.getOrDefault(false)
                    if (!exists) {
                        throw IllegalStateException("输出文件不存在，可能已被删除")
                    }
                }

                val savedPosition = posPrefs.getLong(KEY_POSITION + taskId, -1L)

                // 记录是否为本地播放，用于决定是否显示画面预览
                isLocalPlayback = current.status == TaskStatus.Completed
                playbackUri = url

                // 防相册伪装过的文件后缀不可识别（如 xxx.mp4.flux），
                // 显式指定 MIME 让播放器按内容正确解析，照常播放
                val mime = if (current.status == TaskStatus.Completed) {
                    val n = current.outputName.orEmpty()
                    when {
                        n.endsWith(".mp4" + Camouflage.EXT) -> "video/mp4"
                        n.endsWith(".ts" + Camouflage.EXT) -> "video/mp2t"
                        else -> null
                    }
                } else null
                val mediaItem = if (mime != null) {
                    MediaItem.Builder().setUri(Uri.parse(url)).setMimeType(mime).build()
                } else {
                    MediaItem.fromUri(Uri.parse(url))
                }

                var positionRestored = false
                // 监听器里需要用到的播放器引用（回调发生在 build 之后异步触发）
                var livePlayer: ExoPlayer? = null
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                        // 一旦真正开播过，音频就证明是好的，此后 seek 缓冲不再触发音频兜底
                        if (playing) everStarted = true
                        // 一旦真正在播，立即清除缓冲状态，避免本地播放一直显示缓冲中
                        if (playing) buffering = false
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        buffering = (state == Player.STATE_BUFFERING)
                        durationMs = (livePlayer?.duration ?: 0L).coerceAtLeast(0L)
                        if (state == Player.STATE_READY) seenReady = true
                        // 仅首次 READY 时恢复到上次位置，避免 seek 重新触发 READY 导致循环。
                        // 本地（已完成任务）播放同样恢复记忆位置：
                        // 早期转封装 MP4 音频轨损坏导致本地 seek 卡死（READY↔BUFFERING 不前进）才禁掉，
                        // 现在 ffmpeg 转出的 MP4 音频正常，本地恢复不再有问题。
                        if (state == Player.STATE_READY && !positionRestored && savedPosition > 0) {
                            val d = livePlayer?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
                            if (savedPosition < d) {
                                positionRestored = true
                                val safePos = savedPosition.coerceAtLeast(0L)
                                livePlayer?.seekTo(safePos)
                                positionMs = safePos
                            }
                        }
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        error = e.message ?: "播放失败"
                        buffering = false
                    }
                }

                // 音频轨兜底：转封装 MP4 的 AAC 头损坏会致永久缓冲，丢弃音频轨只播视频
                val p = if (audioDisabledFallback) {
                    val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
                        setParameters(buildUponParameters().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true))
                    }
                    ExoPlayer.Builder(context).setTrackSelector(trackSelector).build()
                } else {
                    ExoPlayer.Builder(context).build()
                }.apply {
                    livePlayer = this
                    setMediaItem(mediaItem)
                    addListener(listener)
                    playWhenReady = true
                    prepare()
                }
                player = p
                exoPlayer = p
            } catch (e: Exception) {
                if (!disposed) {
                    error = e.message ?: "无法开始播放"
                    buffering = false
                }
            }
        }

        onDispose {
            disposed = true
            // 关闭时保存播放位置，供下次恢复
            runCatching {
                val pos = player?.currentPosition ?: 0L
                if (pos > 3000) {
                    posPrefs.edit().putLong(KEY_POSITION + taskId, pos).apply()
                }
            }
            scope.cancel()
            runCatching { player?.release() }
            exoPlayer = null
            PlaybackManager.close(taskId)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 视频画面（关闭内置控制器，全部 UI 由 Compose 自绘）
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        keepScreenOn = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view -> view.player = exoPlayer }
            )

            // ─── 手势覆盖层 ───
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)

                            // 顶部/底部栏区域的触摸交给栏上的按钮，跳过
                            val inBarZone =
                                down.position.y < TOP_BAR_DP.toPx() ||
                                down.position.y > size.height - BOTTOM_BAR_DP.toPx()
                            if (inBarZone) return@awaitEachGesture

                            // 长按定时器：500ms 后触发 3x
                            var longPressTriggered = false
                            val longPressJob = gestureScope.launch {
                                delay(500)
                                longPressTriggered = true
                                exoPlayer?.let { player ->
                                    player.setPlaybackSpeed(3f)
                                    showSpeedBoostHint = true
                                }
                            }

                            // ── 横向滑动（左→右快进 / 右→左快退） ──
                            val maxDurMs = exoPlayer?.duration?.takeIf { it > 0 } ?: durationMs
                            val startX = down.position.x
                            var dragged = false
                            var wasPlaying = exoPlayer?.isPlaying ?: false
                            var seekOrigin = exoPlayer?.currentPosition ?: positionMs
                            var lastUp: Offset? = null

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                if (change.pressed) {
                                    val dx = change.position.x - startX
                                    if (!dragged && kotlin.math.abs(dx) > 48f) {
                                        // 判定为横滑：取消长按，记录起点并暂停
                                        longPressJob.cancel()
                                        dragged = true
                                        wasPlaying = exoPlayer?.isPlaying ?: false
                                        seekOrigin = exoPlayer?.currentPosition ?: positionMs
                                        exoPlayer?.pause()
                                    }
                                    if (dragged) {
                                        // 约 1 px ≈ 1 秒，左→右为正（快进）；比例已调小便于精细控制
                                        val target = (seekOrigin + (dx * 1000f).toLong())
                                            .coerceIn(0L, if (maxDurMs > 0) maxDurMs else Long.MAX_VALUE)
                                        seekPreviewMs = target
                                        showSeekPreview = true
                                        exoPlayer?.seekTo(target)
                                    }
                                } else {
                                    lastUp = change.position
                                    if (dragged) {
                                        val dx = change.position.x - startX
                                        val target = (seekOrigin + (dx * 1000f).toLong())
                                            .coerceIn(0L, if (maxDurMs > 0) maxDurMs else Long.MAX_VALUE)
                                        seekPreviewMs = target
                                        exoPlayer?.seekTo(target)
                                        showSeekPreview = false
                                        if (wasPlaying) exoPlayer?.play()
                                        return@awaitEachGesture
                                    }
                                    break
                                }
                            }
                            showSeekPreview = false

                            longPressJob.cancel()
                            // 长按已触发 → 松开时立即恢复倍速
                            if (longPressTriggered) {
                                exoPlayer?.setPlaybackSpeed(speed)
                                showSpeedBoostHint = false
                                return@awaitEachGesture
                            }

                            val upPos = lastUp ?: return@awaitEachGesture
                            val now = System.currentTimeMillis()
                            val widthPx = size.width.toFloat()

                            val first = firstTapPos
                            val isDoubleTap = first != null &&
                                (now - firstTapTime) < 250L &&
                                (first - upPos).let { it.x * it.x + it.y * it.y } < (widthPx * widthPx * 0.33f * 0.33f)

                            if (isDoubleTap) {
                                when {
                                    upPos.x < widthPx * 0.33f -> {
                                        exoPlayer?.let { player ->
                                            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                                        }
                                    }
                                    upPos.x > widthPx * 0.67f -> {
                                        exoPlayer?.let { player ->
                                            val max = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(max))
                                        }
                                    }
                                    else -> {
                                        exoPlayer?.let { player ->
                                            if (player.isPlaying) player.pause() else player.play()
                                        }
                                    }
                                }
                                firstTapPos = null
                                firstTapTime = 0L
                            } else {
                                // 可能是第一次点击；等 250ms 无第二次才算单击
                                firstTapPos = upPos
                                firstTapTime = now
                                gestureScope.launch {
                                    delay(250)
                                    if (firstTapPos == upPos && firstTapTime == now) {
                                        chromeVisible = !chromeVisible
                                        firstTapPos = null
                                        firstTapTime = 0L
                                    }
                                }
                            }
                        }
                    }
            )

            // ─── 顶部标题栏 ───
            if (chromeVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            task?.name ?: "播放",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (task != null && task.status.isActive) {
                            Text(
                                "边下边播 · 已缓冲 ${task.doneSegments}/${task.totalSegments} 片",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        } else {
                            Text("本地播放", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenuOpen = true }) {
                            Text(
                                speedLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                            speedOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(if (value == speed) "✓ $label" else label) },
                                    onClick = { speed = value; speedMenuOpen = false }
                                )
                            }
                        }
                    }
                }
                }
                }

            // ─── 底部进度栏 ───
            if (chromeVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "播放/暂停",
                            tint = Color.White
                        )
                    }
                    Text(
                        formatTime(positionMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        factory = { ctx ->
                            SeekBar(ctx).apply {
                                max = 1000
                                progressTintList =
                                    android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
                                thumbTintList =
                                    android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
                            }
                        },
                        update = { bar ->
                            val d = durationMs.takeIf { it > 0 }
                                ?: (exoPlayer?.duration?.takeIf { it > 0 } ?: 0L)
                            if (d > 0) {
                                if (!isSeeking) {
                                    bar.progress = ((positionMs.toFloat() / d) * 1000f).toInt().coerceIn(0, 1000)
                                }
                                bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                                        if (fromUser && d > 0) {
                                            positionMs = (progress.toFloat() / 1000f * d).toLong()
                                        }
                                    }
                                    override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
                                    override fun onStopTrackingTouch(sb: SeekBar) {
                                        exoPlayer?.seekTo(positionMs)
                                        isSeeking = false
                                    }
                                })
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTime(exoPlayer?.duration ?: durationMs),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
                }
                }

            // 缓冲中提示（仅未在播放时才显示）
            if (buffering && !isPlaying && error == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("缓冲中…", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            // 长按 3x 提示（顶部中间；背景透明不遮挡画面；栏隐藏时上移到顶部）
            if (showSpeedBoostHint) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (chromeVisible) 72.dp else 12.dp)
                ) {
                    Text(
                        "3x 快速播放中",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }

            // 横向滑动时的目标时间预览（本地播放可附加画面缩略图）
            if (showSeekPreview && seekPreviewMs >= 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (chromeVisible) (BOTTOM_BAR_DP + 8.dp) else 6.dp)
                ) {
                    if (isLocalPlayback && previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "画面预览",
                            modifier = Modifier
                                .width(180.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Text(
                            formatTime(seekPreviewMs),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // 错误提示
            if (error != null) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "无法播放",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { retryKey++ },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}