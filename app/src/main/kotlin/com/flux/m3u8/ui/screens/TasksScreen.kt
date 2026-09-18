package com.flux.m3u8.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.ui.components.EmptyState
import com.flux.m3u8.ui.components.SpeedSparkline
import com.flux.m3u8.ui.components.StatCard
import com.flux.m3u8.ui.components.TaskCard
import com.flux.m3u8.ui.theme.Danger
import com.flux.m3u8.util.formatSpeed
import kotlinx.coroutines.delay

enum class TaskFilter(val label: String) {
    All("全部"), Active("进行中"), Paused("暂停中"), Done("已完成"), Failed("失败")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    tasks: List<DownloadTask>,
    onOpenSettings: () -> Unit,
    onNewTask: () -> Unit,
    onPlay: (DownloadTask) -> Unit
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(TaskFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 长按编辑的任务（仅非活跃任务可编辑）
    var editing by remember { mutableStateOf<DownloadTask?>(null) }
    // 待确认移除的任务（要问清楚"是否连本地文件一起删"）
    var removing by remember { mutableStateOf<DownloadTask?>(null) }

    // 顶栏随列表滚动变色（M3 标准行为），之前是写死背景色，滚起来没有层次
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // 速度采样：每秒记一次，供曲线使用
    val samples = remember { mutableStateListOf<Long>() }
    LaunchedEffect(Unit) {
        while (true) {
            samples.add(DownloadManager.totalSpeed())
            if (samples.size > 40) samples.removeAt(0)
            delay(1000)
        }
    }

    val visible = remember(tasks, filter, query) {
        tasks.filter { t ->
            val matchFilter = when (filter) {
                TaskFilter.All -> true
                TaskFilter.Active -> t.status.isActive
                TaskFilter.Paused -> t.status == TaskStatus.Paused
                TaskFilter.Done -> t.status == TaskStatus.Completed
                TaskFilter.Failed -> t.status == TaskStatus.Error || t.status == TaskStatus.Canceled
            }
            val matchQuery = query.isBlank() ||
                    t.name.contains(query, true) || t.url.contains(query, true)
            matchFilter && matchQuery
        }
    }

    val activeCount = tasks.count { it.status.isActive }
    val doneCount = tasks.count { it.status == TaskStatus.Completed }
    val currentSpeed = samples.lastOrNull() ?: 0L

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Column {
                        Text(
                            "FluxM3U8",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            if (activeCount > 0) "正在下载 $activeCount 个 · 后台持续运行"
                            else "流媒体下载器 · 支持后台下载",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(if (searchOpen) Icons.Default.Close else Icons.Default.Search, "搜索")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("全部开始") },
                                onClick = { menuOpen = false; DownloadManager.resumeAll() },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("全部暂停") },
                                onClick = { menuOpen = false; DownloadManager.pauseAll() },
                                leadingIcon = { Icon(Icons.Default.Pause, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空已完成") },
                                onClick = { menuOpen = false; DownloadManager.clearFinished() },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = { menuOpen = false; onOpenSettings() },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Default.Add, "新建") },
                text = { Text("新建任务") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            AnimatedVisibility(visible = searchOpen, enter = fadeIn(), exit = fadeOut()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("搜索任务名称或链接") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                        StatCard(
                            label = "总速度",
                            value = formatSpeed(currentSpeed),
                            hint = if (activeCount > 0) "$activeCount 个任务进行中" else "空闲",
                            modifier = Modifier.weight(1.4f),
                            trailing = {
                                SpeedSparkline(
                                    samples = samples,
                                    modifier = Modifier
                                        .width(110.dp)
                                        .height(34.dp)
                                        .offset(y = 4.dp)
                                )
                            }
                        )
                        Spacer(Modifier.width(10.dp))
                        StatCard(
                            label = "已完成",
                            value = doneCount.toString(),
                            hint = "共 ${tasks.size} 个",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    // 筛选条改为可左右滑动：5 个筛选 + 计数在窄屏上会被裁掉，
                    // 之前"失败/已完成"显示不全就是被挤没了。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TaskFilter.values().forEach { f ->
                            val count = when (f) {
                                TaskFilter.All -> tasks.size
                                TaskFilter.Active -> tasks.count { it.status.isActive }
                                TaskFilter.Paused -> tasks.count { it.status == TaskStatus.Paused }
                                TaskFilter.Done -> doneCount
                                TaskFilter.Failed -> tasks.count { it.status == TaskStatus.Error || it.status == TaskStatus.Canceled }
                            }
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(if (count > 0) "${f.label} $count" else f.label) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                            EmptyState(
                                title = if (query.isNotBlank()) "没有匹配的任务" else "还没有下载任务",
                                subtitle = if (query.isNotBlank()) "换个关键词试试"
                                else "粘贴一个 m3u8 链接，或从浏览器分享到本应用"
                            )
                        }
                    }
                } else {
                    items(visible, key = { it.id }) { task ->
                        // 只有非活跃任务（暂停/取消/失败）允许长按编辑
                        val editable = task.status == TaskStatus.Paused ||
                                task.status == TaskStatus.Canceled ||
                                task.status == TaskStatus.Error
                        TaskCard(
                            task = task,
                            canPlay = com.flux.m3u8.playback.PlaybackManager.isPlayable(task),
                            onPlay = { onPlay(task) },
                            onPause = { DownloadManager.pause(task.id) },
                            onResume = { DownloadManager.resume(task.id) },
                            onCancel = { DownloadManager.cancel(task.id) },
                            onRetry = { DownloadManager.retry(task.id) },
                            // 先弹窗问清楚：只移除任务，还是连本地文件一起删
                            onRemove = { removing = task },
                            onOpen = { openDownloadedFile(context, task) },
                            onEdit = if (editable) ({ editing = task }) else null
                        )
                    }
                }

                item { Spacer(Modifier.height(84.dp)) }
            }
        }
    }

    editing?.let { target ->
        EditTaskDialog(
            task = target,
            onDismiss = { editing = null },
            onSave = { name, url, referer, cookie, connections, toMp4 ->
                DownloadManager.editTask(target.id, name, url, referer, cookie, connections, toMp4)
                editing = null
            }
        )
    }

    removing?.let { target ->
        RemoveTaskDialog(
            task = target,
            onDismiss = { removing = null },
            onConfirm = { deleteFile ->
                DownloadManager.remove(target.id, deleteFile)
                removing = null
            }
        )
    }
}

/**
 * 移除任务的确认框。
 *
 * 关键点：**本地文件不能跟着任务记录一起悄悄消失，也不能默认留着占空间**——
 * 用户可能只是想清列表（文件还要留着看），也可能想把空间收回来。
 * 所以有产物时给两个明确选项：「仅移除任务」/「同时删除文件」，
 * 没产物时只提示一下临时分片会被清理。
 */
@Composable
private fun RemoveTaskDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onConfirm: (deleteFile: Boolean) -> Unit
) {
    val outputName = task.outputName?.takeIf { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移除任务", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "确定要移除「${task.name}」吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (outputName != null)
                        "下载好的文件（$outputName）默认保留；选择「同时删除文件」会把它一并删掉，无法恢复。"
                    else
                        "该任务还没有产出文件，移除后它的临时分片也会被清理。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            if (outputName != null) {
                TextButton(
                    onClick = { onConfirm(true) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Danger)
                ) { Text("同时删除文件") }
            } else {
                TextButton(onClick = { onConfirm(false) }) { Text("移除") }
            }
        },
        dismissButton = {
            Row {
                if (outputName != null) {
                    TextButton(onClick = { onConfirm(false) }) { Text("仅移除任务") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
