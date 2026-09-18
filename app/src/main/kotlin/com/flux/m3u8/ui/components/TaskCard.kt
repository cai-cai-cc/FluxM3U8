package com.flux.m3u8.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.m3u8.model.DownloadTask
import com.flux.m3u8.model.TaskStatus
import com.flux.m3u8.ui.theme.Danger
import com.flux.m3u8.ui.theme.Success
import com.flux.m3u8.ui.theme.Warning
import com.flux.m3u8.util.Camouflage
import com.flux.m3u8.util.formatBytes
import com.flux.m3u8.util.formatDuration
import com.flux.m3u8.util.formatSpeed
import com.flux.m3u8.util.hostOf

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TaskCard(
    task: DownloadTask,
    canPlay: Boolean = false,
    onPlay: () -> Unit = {},
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)? = null,
    /** 非空且任务已完成时显示「转换/还原」按钮（防相册识别）。 */
    onToggleCamouflage: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 用 colorScheme.primary 而不是写死的品牌色：
    // 开启动态取色后写死色会跟系统配色打架，按钮文字的对比度也会失控。
    val accent = MaterialTheme.colorScheme.primary
    val tone = when (task.status) {
        TaskStatus.Completed -> Success
        TaskStatus.Error -> Danger
        TaskStatus.Paused -> Warning
        else -> accent
    }
    // 合并/转换阶段展示合并进度，其余阶段展示下载进度
    val progress = if (task.status == TaskStatus.Merging) task.mergeProgress else task.progress
    val editable = onEdit != null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (editable) {
                    Modifier.combinedClickable(onClick = {}, onLongClick = onEdit)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Row {
                // 状态图标
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(tone.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = statusIcon(task.status),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 标题行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        StatusChip(task.status)
                    }

                    Spacer(Modifier.height(2.dp))

                    // 元信息
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            hostOf(task.url),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (task.totalSegments > 0) {
                            MetaDot()
                            Text("${task.doneSegments}/${task.totalSegments} 片",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.variantLabel.isNotBlank()) {
                            MetaDot()
                            Text(task.variantLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.encrypted) {
                            MetaDot()
                            Icon(Icons.Default.Lock, null, tint = Warning,
                                modifier = Modifier.size(11.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("加密", style = MaterialTheme.typography.labelSmall, color = Warning)
                        }
                    }

                    Spacer(Modifier.height(9.dp))

                    GradientProgress(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        // 合并/转换阶段不做插值动画：进度由合并线程细粒度回报，
                        // 加 300ms 过渡反而显得"反应迟钝"。
                        animate = task.status != TaskStatus.Merging,
                        tone = when (task.status) {
                            TaskStatus.Completed -> ProgressTone.Success
                            TaskStatus.Error -> ProgressTone.Danger
                            TaskStatus.Paused -> ProgressTone.Warning
                            else -> ProgressTone.Accent
                        }
                    )

                    Spacer(Modifier.height(6.dp))

                    // 数值行：改用 FlowRow，信息多 / 窄屏时自动折成多行（此前是单行 Row，容易被裁断）
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (task.totalBytes > 0) {
                            Text(
                                "${formatBytes(task.doneBytes)} / ${if (task.estimated) "≈" else ""}${formatBytes(task.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (task.doneBytes > 0) {
                            Text(formatBytes(task.doneBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (task.status.isActive && task.speedBps > 0) {
                            Text(formatSpeed(task.speedBps),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = accent)
                        }
                        if (task.status.isActive && task.etaSeconds > 0) {
                            Text("剩 ${formatDuration(task.etaSeconds)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // 合并 / 转换阶段的文案单独占一行并允许换行：文案偏长
                    // （如「合并为单个 TS 文件…」「MP4 转换失败，回退保存 TS…」），
                    // 挤在数值行里会被折得七零八落，看不全当前卡在哪一步。
                    if (task.status == TaskStatus.Merging) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            task.phaseLabel.ifBlank { "合并/转换中…" },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = accent,
                            softWrap = true,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (task.status == TaskStatus.Error && !task.error.isNullOrBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            task.error.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Danger,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            // 操作区：横排放在卡片底部（原先竖排一列会把卡片撑得很高）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (editable) {
                        Text(
                            "长按编辑",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canPlay) {
                        // 已完成的任务点这里是直接播**本地成品文件**，不再是边下边播
                        SmallAction(
                            Icons.Default.PlayCircle,
                            if (task.status == TaskStatus.Completed) "播放本地文件" else "边下边播",
                            onPlay,
                            tint = accent
                        )
                    }
                    if (onToggleCamouflage != null && task.status == TaskStatus.Completed) {
                        val hidden = Camouflage.isHidden(task.outputName)
                        SmallAction(
                            if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            if (hidden) "还原" else "转换",
                            onToggleCamouflage
                        )
                    }
                    when {
                        task.status.isActive -> {
                            SmallAction(Icons.Default.Pause, "暂停", onPause)
                            SmallAction(Icons.Default.Close, "取消", onCancel)
                        }
                        task.status == TaskStatus.Paused -> {
                            SmallAction(Icons.Default.PlayArrow, "继续", onResume)
                            SmallAction(Icons.Default.Close, "取消", onCancel)
                        }
                        task.status == TaskStatus.Error || task.status == TaskStatus.Canceled ->
                            SmallAction(Icons.Default.Refresh, "重试", onRetry)
                        task.status == TaskStatus.Completed ->
                            SmallAction(Icons.Default.FolderOpen, "打开", onOpen)
                    }
                    SmallAction(Icons.Default.Delete, "移除", onRemove, tint = Danger)
                }
            }
        }
    }
}

@Composable
private fun MetaDot() {
    Spacer(Modifier.width(6.dp))
    Box(
        modifier = Modifier
            .size(3.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    )
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun StatusChip(status: TaskStatus) {
    val color = when (status) {
        TaskStatus.Completed -> Success
        TaskStatus.Error -> Danger
        TaskStatus.Paused -> Warning
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            status.cn,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

private fun statusIcon(status: TaskStatus) = when (status) {
    TaskStatus.Queued -> Icons.Default.Schedule
    TaskStatus.Parsing -> Icons.Default.Layers
    TaskStatus.Downloading -> Icons.Default.Downloading
    TaskStatus.Merging -> Icons.AutoMirrored.Filled.MergeType
    TaskStatus.Paused -> Icons.Default.Pause
    TaskStatus.Completed -> Icons.Default.CheckCircle
    TaskStatus.Error -> Icons.Default.ErrorOutline
    TaskStatus.Canceled -> Icons.Default.Cancel
}
