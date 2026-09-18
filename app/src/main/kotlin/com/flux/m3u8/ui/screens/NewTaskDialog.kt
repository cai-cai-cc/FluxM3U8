package com.flux.m3u8.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.model.ProbeInfo
import com.flux.m3u8.ui.theme.AccentGradient
import com.flux.m3u8.ui.theme.Danger
import com.flux.m3u8.ui.theme.Success
import com.flux.m3u8.ui.theme.Warning
import com.flux.m3u8.util.formatDurationCn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 新建任务时收集到的表单数据。 */
data class NewTaskForm(
    val url: String,
    val name: String,
    val referer: String,
    val cookie: String,
    val toMp4: Boolean,
    val variantIndex: Int?,
    val connections: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskDialog(
    initialUrl: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (NewTaskForm) -> Unit
) {
    val settings = remember { DownloadManager.settings() }

    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf(initialName) }
    var referer by remember { mutableStateOf(settings.referer) }
    var cookie by remember { mutableStateOf("") }
    var toMp4 by remember { mutableStateOf(settings.toMp4) }
    var variantIndex by remember { mutableStateOf<Int?>(null) }
    var conn by remember { mutableFloatStateOf(settings.connections.toFloat()) }

    var probe by remember { mutableStateOf<ProbeInfo?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }

    /**
     * 链接变化后延迟自动解析，避免边输入边请求。
     * LaunchedEffect 的 key 是 url，输入变化时上一个协程会自动取消，
     * 因此不会残留过期的解析请求。
     */
    LaunchedEffect(url) {
        if (!url.startsWith("http", true)) {
            probe = null
            probeError = null
            return@LaunchedEffect
        }
        probing = true
        probeError = null
        delay(650)
        try {
            probe = DownloadManager.probe(url, referer, "", cookie)
            if (name.isBlank()) name = probe?.suggestedName ?: ""
        } catch (e: Exception) {
            probeError = e.message ?: "解析失败"
            probe = null
        } finally {
            probing = false
        }
    }

    /**
     * 同名文件检查。
     *
     * 保存目录里已经有同名产物时：不但要提示，还要**禁止开始下载**——
     * 与其等下完整个视频再在最后一步失败（或悄悄写成 "xxx (1).mp4"），
     * 不如在点按钮之前就说清楚。
     * 涉及文件 IO，切到 IO 线程并加小段防抖，避免边输入边查目录。
     */
    val saveDir = settings.saveDir
    var conflict by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name, toMp4, saveDir) {
        if (name.isBlank()) {
            conflict = null
            return@LaunchedEffect
        }
        delay(300)
        conflict = withContext(Dispatchers.IO) {
            DownloadManager.outputConflict(saveDir, name, toMp4)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column {
                // 标题
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        "新建下载任务",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "关闭")
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp)
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("m3u8 地址") },
                        placeholder = { Text("https://example.com/index.m3u8") },
                        minLines = 2,
                        maxLines = 3,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = { Icon(Icons.Default.Link, null) }
                    )

                    Spacer(Modifier.height(10.dp))

                    // 解析结果
                    ProbePanel(probing = probing, probe = probe, error = probeError)

                    if (probe?.isMaster == true && probe!!.variants.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("清晰度", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            probe!!.variants.take(5).forEachIndexed { i, v ->
                                FilterChip(
                                    selected = (variantIndex ?: 0) == i,
                                    onClick = { variantIndex = i },
                                    label = { Text(v.label) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("文件名") },
                        singleLine = true,
                        isError = conflict != null,
                        shape = RoundedCornerShape(14.dp)
                    )

                    // 保存目录已有同名文件：提示并拦住，不允许开始下载
                    AnimatedVisibility(visible = conflict != null) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline, null, tint = Danger,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "保存目录已存在同名文件「${conflict.orEmpty()}」，"
                                        + "请修改文件名或到设置里更换保存目录后再下载。",
                                fontSize = 11.sp,
                                color = Danger,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 并发连接数：最常用的提速旋钮，直接放在主区域
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("同时下载分片数", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            conn.toInt().toString(),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = conn,
                        onValueChange = { conn = it },
                        valueRange = 1f..64f,
                        steps = 62
                    )
                    Text(
                        "同一时间并行下载多少个分片。想跑满带宽就往大调，"
                                + "源站较慢或返回失败时适当调小。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    TextButton(onClick = { advanced = !advanced }) {
                        Icon(
                            if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (advanced) "收起高级选项" else "展开高级选项（防盗链）")
                    }

                    AnimatedVisibility(visible = advanced) {
                        Column {
                            OutlinedTextField(
                                value = referer,
                                onValueChange = { referer = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Referer") },
                                placeholder = { Text("视频播放页地址，防盗链必备") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = cookie,
                                onValueChange = { cookie = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cookie") },
                                placeholder = { Text("需要登录时填写") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("完成后转 MP4（实验功能）", modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = toMp4, onCheckedChange = { toMp4 = it })
                            }
                            Text(
                                "关闭时把分片合并成一个 TS 文件，可直接播放；"
                                        + "开启转 MP4 会依次尝试多种系统封装方式，全部失败则回退保存为单个 TS。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }

                // 底部按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("取消") }

                    Button(
                        onClick = {
                            onConfirm(
                                NewTaskForm(
                                    url = url,
                                    name = name,
                                    referer = referer,
                                    cookie = cookie,
                                    toMp4 = toMp4,
                                    variantIndex = if (probe?.isMaster == true) (variantIndex ?: 0) else null,
                                    connections = conn.toInt()
                                )
                            )
                        },
                        enabled = url.startsWith("http", true) && conflict == null,
                        modifier = Modifier.weight(1.4f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGradient.first(),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (conflict != null) "文件名重复" else "开始下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbePanel(probing: Boolean, probe: ProbeInfo?, error: String?) {
    when {
        probing -> Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("正在解析播放列表…", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        error != null -> AssistChip(
            onClick = { },
            label = { Text(error, fontSize = 11.sp) },
            leadingIcon = {
                Icon(Icons.Default.ErrorOutline, null, tint = Danger,
                    modifier = Modifier.size(14.dp))
            },
            colors = AssistChipDefaults.assistChipColors(
                labelColor = Danger
            )
        )

        probe != null -> Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (probe.isMaster) {
                    InfoChip("多清晰度 · ${probe.variants.size} 档", Success)
                } else {
                    if (probe.durationSeconds > 0)
                        InfoChip("时长 ${formatDurationCn(probe.durationSeconds.toLong())}", Success)
                    InfoChip("${probe.segmentCount} 个分片", Success)
                    InfoChip(if (probe.encrypted) "已加密" else "未加密",
                        if (probe.encrypted) Warning else Success)
                    InfoChip(if (probe.isLive) "直播" else "点播", Success)
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = androidx.compose.ui.Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}
