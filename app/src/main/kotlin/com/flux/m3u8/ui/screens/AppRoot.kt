package com.flux.m3u8.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.playback.PlaybackManager
import com.flux.m3u8.ui.theme.FluxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { Tasks, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    sharedUrl: String?,
    sharedTitle: String?,
    onSharedHandled: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { DownloadManager.settings() }

    val tasks by DownloadManager.tasks.collectAsState()

    var screen by rememberSaveable { mutableStateOf(Screen.Tasks) }
    var showNewTask by remember { mutableStateOf(false) }
    var newTaskUrl by remember { mutableStateOf("") }
    var newTaskName by remember { mutableStateOf("") }
    var themeLight by remember { mutableStateOf(settings.themeLight) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }

    // 边下边播：当前正在播放的任务
    var playingTaskId by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()

    // 返回键统一处理（优先级从高到低）：
    //  1. 播放页 → 关闭播放页，回到主页
    //  2. 新建弹窗 → 关闭
    //  3. 设置页 → 回到主页
    //  4. 主页   → 双击退出；第二次才真正退出，并清掉无任务时残留的前台服务与通知
    var backArmed by remember { mutableStateOf(false) }
    LaunchedEffect(backArmed) {
        if (backArmed) {
            delay(2000)
            backArmed = false
        }
    }
    BackHandler {
        when {
            playingTaskId != null -> playingTaskId = null
            showNewTask -> showNewTask = false
            screen == Screen.Settings -> screen = Screen.Tasks
            else -> {
                if (backArmed) {
                    DownloadManager.shutdownIfIdle()
                    (context as? Activity)?.finishAndRemoveTask()
                } else {
                    backArmed = true
                    uiScope.launch { snackbar.showSnackbar("再按一次退出") }
                }
            }
        }
    }

    RequestRuntimePermissions()

    // 深色/浅色切换时同步状态栏图标颜色，否则浅色主题下是白底白字
    val view = LocalView.current
    LaunchedEffect(themeLight) {
        val window = (context as? Activity)?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = themeLight
        }
    }

    // 从浏览器/其他应用进来的链接：直接带着地址弹出新建窗口
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            newTaskUrl = sharedUrl
            newTaskName = sharedTitle ?: ""
            showNewTask = true
            onSharedHandled()
        }
    }

    FluxTheme(lightPreference = themeLight, dynamicColor = dynamicColor) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (screen) {
                Screen.Tasks -> TasksScreen(
                    tasks = tasks,
                    onOpenSettings = { screen = Screen.Settings },
                    onNewTask = { newTaskUrl = ""; newTaskName = ""; showNewTask = true },
                    onPlay = { task ->
                        val reason = PlaybackManager.blockReason(task)
                        if (reason != null) {
                            uiScope.launch { snackbar.showSnackbar(reason) }
                        } else {
                            playingTaskId = task.id
                        }
                    }
                )

                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Tasks },
                    onThemeChanged = { themeLight = it },
                    onDynamicColorChanged = { dynamicColor = it }
                )
            }

            if (showNewTask) {
                NewTaskDialog(
                    initialUrl = newTaskUrl,
                    initialName = newTaskName,
                    onDismiss = { showNewTask = false },
                    onConfirm = { task ->
                        showNewTask = false
                        DownloadManager.add(
                            url = task.url,
                            name = task.name,
                            saveDir = settings.saveDir,
                            connections = task.connections,
                            toMp4 = task.toMp4,
                            referer = task.referer,
                            userAgent = "",
                            cookie = task.cookie,
                            variantIndex = task.variantIndex
                        )
                    }
                )
            }

            // 播放页盖在最上层
            if (playingTaskId != null) {
                PlayerScreen(
                    taskId = playingTaskId!!,
                    onClose = { playingTaskId = null }
                )
            }

            // 轻量提示（目前用于"还不能播"这类即时反馈）
            Box(modifier = Modifier.fillMaxSize()) {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp)
                )
            }
        }
    }
}

/** 一次性申请必要权限。 */
@Composable
private fun RequestRuntimePermissions() {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
    }
}

/** 打开已下载的文件：内部目录走 FileProvider，SAF 目录直接用它的 content URI。 */
fun openDownloadedFile(context: Context, task: DownloadTask) {
    val raw = task.outputUri ?: return
    try {
        val uri = if (raw.startsWith("file://")) {
            val file = File(Uri.parse(raw).path ?: return)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.parse(raw)
        }
        // 按扩展名给准确的 MIME：单文件产物是 .ts / .mp4（.m3u8 是旧版本产物），
        // 一律写成 "video/*" 有些播放器会拒绝接收
        val name = task.outputName.orEmpty().lowercase()
        val mime = when {
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".ts") -> "video/mp2t"
            name.endsWith(".m3u8") -> "application/x-mpegURL"
            else -> "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // 没有可打开的应用：忽略
    }
}

/** 是否已被用户加入电池优化白名单。 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

/** 跳转到系统的电池优化设置页。 */
fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
