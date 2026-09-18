package com.flux.m3u8.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flux.m3u8.model.DownloadTask
import kotlin.math.roundToInt

/**
 * 长按非活跃任务（暂停 / 取消 / 失败）后弹出的编辑框。
 *
 * 修改链接会让已下载进度作废，这里会明确提示；只改名称/请求头/并发则保留进度。
 */
@Composable
fun EditTaskDialog(
    task: DownloadTask,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, referer: String, cookie: String, connections: Int, toMp4: Boolean) -> Unit
) {
    var name by remember(task.id) { mutableStateOf(task.name) }
    var url by remember(task.id) { mutableStateOf(task.url) }
    var referer by remember(task.id) { mutableStateOf(task.referer) }
    var cookie by remember(task.id) { mutableStateOf(task.cookie) }
    var connections by remember(task.id) { mutableStateOf(task.connections.toFloat()) }
    var toMp4 by remember(task.id) { mutableStateOf(task.toMp4) }

    val urlChanged = url.trim() != task.url

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑任务", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("链接") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                if (urlChanged) {
                    Text(
                        "修改链接会清空已下载进度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedTextField(
                    value = referer,
                    onValueChange = { referer = it },
                    label = { Text("Referer（可选）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text("Cookie（可选）") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Column {
                    Text(
                        "并发连接：${connections.roundToInt()}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Slider(
                        value = connections,
                        onValueChange = { connections = it },
                        valueRange = 1f..32f,
                        steps = 30
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("转换为 MP4", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = toMp4, onCheckedChange = { toMp4 = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        url.trim(),
                        referer.trim(),
                        cookie.trim(),
                        connections.roundToInt(),
                        toMp4
                    )
                },
                enabled = url.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
