package com.flux.m3u8.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flux.m3u8.BuildConfig
import com.flux.m3u8.data.DirCheck
import com.flux.m3u8.data.Storage
import com.flux.m3u8.download.DownloadManager
import com.flux.m3u8.util.formatBytes
import com.flux.m3u8.util.formatSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onThemeChanged: (Boolean) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val settings = remember { DownloadManager.settings() }
    val storage = remember { DownloadManager.storage() }

    var dir by remember { mutableStateOf(settings.saveDir) }
    var connections by remember { mutableIntStateOf(settings.connections) }
    var maxRunning by remember { mutableIntStateOf(settings.maxRunning) }
    var chunkThreads by remember { mutableIntStateOf(settings.chunkThreads) }
    var chunkThreshold by remember { mutableStateOf(settings.chunkThresholdKb.toString()) }
    var autoConcurrency by remember { mutableStateOf(settings.autoConcurrency) }
    var speedLimit by remember { mutableStateOf(settings.speedLimitKbps.toString()) }
    var retry by remember { mutableStateOf(settings.retryTimes.toString()) }
    var retryDelay by remember { mutableStateOf(settings.retryDelaySeconds.toString()) }
    var timeout by remember { mutableStateOf(settings.timeoutSeconds.toString()) }
    var toMp4 by remember { mutableStateOf(settings.toMp4) }
    var camouflage by remember { mutableStateOf(settings.camouflage) }
    var keepSegments by remember { mutableStateOf(settings.keepSegments) }
    var wakeLock by remember { mutableStateOf(settings.wakeLock) }
    var onlyWifi by remember { mutableStateOf(settings.onlyWifi) }
    var autoResume by remember { mutableStateOf(settings.autoResume) }
    var proxyHost by remember { mutableStateOf(settings.proxyHost) }
    var proxyPort by remember { mutableStateOf(settings.proxyPort.toString().takeIf { settings.proxyPort > 0 } ?: "") }
    var userAgent by remember { mutableStateOf(settings.userAgent) }
    var referer by remember { mutableStateOf(settings.referer) }
    var insecureTls by remember { mutableStateOf(settings.insecureTls) }
    var themeLight by remember { mutableStateOf(settings.themeLight) }
    var dynamicColor by remember { mutableStateOf(settings.dynamicColor) }

    var batteryOptimized by remember { mutableStateOf(!isIgnoringBatteryOptimizations(context)) }

    /** 每次修改立即落盘并通知下载引擎，无需重启。 */
    fun apply() {
        settings.saveDir = dir
        settings.connections = connections
        settings.maxRunning = maxRunning
        settings.chunkThreads = chunkThreads
        settings.chunkThresholdKb = chunkThreshold.toIntOrNull()?.coerceIn(256, 102400) ?: 2048
        settings.autoConcurrency = autoConcurrency
        settings.speedLimitKbps = speedLimit.toIntOrNull() ?: 0
        settings.retryTimes = retry.toIntOrNull() ?: 3
        settings.retryDelaySeconds = retryDelay.toIntOrNull() ?: 1
        settings.timeoutSeconds = timeout.toIntOrNull() ?: 30
        settings.toMp4 = toMp4
        settings.camouflage = camouflage
        settings.keepSegments = keepSegments
        settings.wakeLock = wakeLock
        settings.onlyWifi = onlyWifi
        settings.autoResume = autoResume
        settings.proxyHost = proxyHost
        settings.proxyPort = proxyPort.toIntOrNull() ?: 0
        settings.userAgent = userAgent
        settings.referer = referer
        settings.insecureTls = insecureTls
        settings.themeLight = themeLight
        settings.dynamicColor = dynamicColor
        DownloadManager.onSettingsChanged()
    }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 系统文件选择器返回后必须拿到「可持久化」的授权才算真正选好目录：
            // 若授权过程被中断（Activity 在选目录途中被重建、进程被回收等），
            // takePersistableUriPermission 会静默失败——以前直接吞掉异常照常保存，
            // 设置里记下的就是一串"看得见但用不了"的 URI，下次打开就报"目录授权已失效"。
            // 这里在保存前再校验一次，拿不到授权就不切换目录。
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val authorized = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
                storage.hasTreePermission(uri.toString())
            }.getOrDefault(false)
            if (!authorized) {
                Toast.makeText(context, "目录授权失败，请重新选择", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            storage.releaseTree(dir)
            dir = uri.toString()
            apply()
        }
    }

    /**
     * 打开系统目录选择器。
     * @param preset 非空时把选择器直接定位到对应公共目录（仅主流 ROM 支持，
     *               不受支持时 URI 会被忽略，退回根目录）
     */
    fun pick(preset: Storage.Preset?) {
        val hint = preset?.let { Storage.presetTreeUri(it) }
        runCatching { dirPicker.launch(hint) }
            .onFailure { dirPicker.launch(null) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("设置", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── 下载目录 ──
            SettingsCard(title = "下载目录", subtitle = "视频保存到哪里") {
                // 目录检查涉及文件 IO，放到 IO 线程；输入子目录时做一次防抖
                var dirCheck by remember { mutableStateOf<DirCheck?>(null) }
                LaunchedEffect(dir) {
                    delay(300)
                    dirCheck = withContext(Dispatchers.IO) { storage.check(dir) }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (Storage.isInternal(dir)) Icons.Default.PhoneAndroid else Icons.Default.FolderOpen,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (Storage.isInternal(dir)) "应用内部目录" else "外部目录（SAF）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            storage.describe(dir),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                when (val st = dirCheck) {
                    null -> Text("正在检查目录…", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is DirCheck.Ok -> Text(
                        "✓ 目录可写" + if (st.freeBytes > 0) " · 可用 ${formatBytes(st.freeBytes)}" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is DirCheck.Failed -> Text(
                        "⚠ ${st.reason}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(10.dp))

                // 常用位置
                Text("常用位置", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            storage.releaseTree(dir)
                            dir = Storage.INTERNAL
                            apply()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("内部 Movies", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            storage.releaseTree(dir)
                            dir = Storage.encodeInternal("Downloads")
                            apply()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("内部 Downloads", fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pick(null) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自定义目录…", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            storage.releaseTree(dir)
                            dir = Storage.INTERNAL
                            apply()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认", fontSize = 12.sp)
                    }
                }

                // 内部目录下的自定义子目录
                if (Storage.isInternal(dir)) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                    var sub by remember(dir) { mutableStateOf(storage.subDirOf(dir)) }
                    OutlinedTextField(
                        value = sub,
                        onValueChange = {
                            sub = it
                            dir = Storage.encodeInternal(it)
                            apply()
                        },
                        label = { Text("子目录（可分级，如 剧集/第一季）") },
                        placeholder = { Text(Storage.DEFAULT_SUBDIR) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Text(
                        "完整路径：${storage.internalDir(dir).absolutePath}\n"
                                + "留空则回到 ${Storage.DEFAULT_SUBDIR}；不合法的字符与 .. 会被自动过滤。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "内部目录免权限即可写入，卸载 App 时会被一起删除；\n"
                            + "外部目录（公共下载/影片、SD 卡）卸载后文件仍在，但需要一次授权，"
                            + "授权被系统回收时会**自动回退到内部目录**，不会让下载白跑。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            // ── 临时文件 ──
            SettingsCard(title = "临时文件", subtitle = "下载过程中产生的分片缓存") {
                var usage by remember { mutableLongStateOf(DownloadManager.tempUsage()) }
                var freedMessage by remember { mutableStateOf<String?>(null) }
                var clearing by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前占用", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (usage > 0) formatBytes(usage) else "无残留",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (usage > 0) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clearing = true
                            val freed = DownloadManager.cleanTempFiles()
                            usage = DownloadManager.tempUsage()
                            clearing = false
                            freedMessage = if (freed > 0) "已清理 ${formatBytes(freed)}" else "没有需要清理的文件"
                        },
                        enabled = !clearing
                    ) {
                        if (clearing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("清理残留")
                    }
                }

                freedMessage?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(6.dp))

                SwitchRow(
                    "完成后保留分片",
                    "开启后合并完不删除 .ts 分片，仅调试用，会持续占用空间",
                    keepSegments
                ) { keepSegments = it; apply() }

                Text(
                    "正常情况：下载完成或取消后会自动删除临时分片，无需手动处理。\n"
                            + "漏网场景（应用崩溃、被系统强杀）会在下次启动时自动扫描清理，"
                            + "超过 7 天没变动的残留目录也会被清掉。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            // ── 下载 ──
            SettingsCard(title = "并发与速度", subtitle = "决定能不能跑满带宽") {
                SwitchRow(
                    "自动并发",
                    "根据实测速度自动加减连接数，找到最快又不浪费的那个点",
                    autoConcurrency
                ) { autoConcurrency = it; apply() }

                AnimatedVisibility(visible = !autoConcurrency) {
                    SliderRow(
                        "每任务并发连接数",
                        connections, 1..64,
                        hint = "同一时间同时下多少个分片。分片多时调大提速明显",
                        onValueChange = { connections = it },
                        onCommit = { apply() }
                    )
                }
                AnimatedVisibility(visible = autoConcurrency) {
                    SliderRow(
                        "并发上限（自动模式下）",
                        connections, 1..64,
                        hint = "自动并发时最多允许开到多少条连接",
                        onValueChange = { connections = it },
                        onCommit = { apply() }
                    )
                }

                SliderRow(
                    "同时下载任务数",
                    maxRunning, 1..10,
                    hint = "超过此数量的任务会排队等待；总连接数 = 本值 × 每任务并发",
                    onValueChange = { maxRunning = it },
                    onCommit = { apply() }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))

                SliderRow(
                    "单分片分块线程数",
                    chunkThreads, 1..16,
                    hint = "把一个大分片切成几段并行拉取。分片少而大时（如 10 片 × 50MB）必须开这个才能压满带宽",
                    onValueChange = { chunkThreads = it },
                    onCommit = { apply() }
                )

                AnimatedVisibility(visible = chunkThreads > 1) {
                    OutlinedNumberField(
                        "分块阈值（KB）",
                        chunkThreshold,
                        hint = "分片大于此值才分块，默认 2048（2MB）。小分片分块反而更慢"
                    ) { chunkThreshold = it; apply() }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))

                OutlinedNumberField("全局限速（KB/s，0 不限）", speedLimit) {
                    speedLimit = it; apply()
                }
                Text(
                    "限速对所有正在下载的任务合计生效，修改后立即生效。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                // 失败重试：次数 × 间隔共同决定"多快放弃、多久再试"
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedNumberField("失败重试次数", retry) { retry = it; apply() }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedNumberField("重试间隔（秒）", retryDelay) {
                            retryDelay = it; apply()
                        }
                    }
                }
                Text(
                    "分片下载失败后，等待「间隔 × 第几次失败」再重试"
                            + "（例如间隔 2 秒：第 1 次等 2 秒、第 2 次等 4 秒）。\n"
                            + "源站已经在限流时把间隔调大；设为 0 表示立即重试，仅在确认是偶发抖动时用。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                OutlinedNumberField("超时时间（秒）", timeout) { timeout = it; apply() }

                Spacer(Modifier.height(4.dp))
                Text(
                    "实际并发 = 每任务并发 × 同时下载任务数 × 分块线程数。"
                            + "开太大可能被源站限流甚至封 IP，一般 16 × 2 已经能跑满大多数宽带。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            // ── 后台保活 ──
            SettingsCard(title = "后台与省电", subtitle = "决定锁屏后是否还能继续下") {
                SwitchRow("下载时保持 CPU 唤醒", "熄屏后继续满速下载（推荐开启）", wakeLock) {
                    wakeLock = it; apply()
                }
                SwitchRow("启动后自动继续未完成任务", "重启手机或强杀进程后自动接着下", autoResume) {
                    autoResume = it; apply()
                }
                SwitchRow("仅在 WiFi 下下载", "移动网络下自动暂停", onlyWifi) {
                    onlyWifi = it; apply()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("电池优化", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (batteryOptimized) "未加入白名单，长时间后台可能被系统限制"
                            else "已加入白名单，后台下载不受限制",
                            fontSize = 11.sp,
                            color = if (batteryOptimized) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedButton(onClick = {
                        openBatterySettings(context)
                        batteryOptimized = !isIgnoringBatteryOptimizations(context)
                    }) {
                        Text(if (batteryOptimized) "去设置" else "已允许")
                    }
                }
                Text(
                    "国内 ROM（小米/华为/OPPO/vivo 等）还需在「手机管家 → 应用管理」里"
                            + "把本应用设为自启动 + 后台运行无限制，否则锁屏后仍可能被冻结。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 输出 ──
            SettingsCard(title = "输出", subtitle = "保存格式") {
                SwitchRow(
                    "默认转 MP4",
                    "关闭时把分片合并成一个 TS 文件（下载目录里只有一个文件）；" +
                            "开启后依次尝试多种 MP4 封装方式，全部失败则回退为单个 TS",
                    toMp4
                ) {
                    toMp4 = it; apply()
                }
                SwitchRow(
                    "防相册识别",
                    "给视频文件换上特殊后缀（如 xxx.mp4.flux），系统相册不再收录下载的视频；" +
                            "开启后已完成的任务下方会出现「转换/还原」按钮，可随时切换，播放器照常播放",
                    camouflage
                ) {
                    camouflage = it; apply()
                }
            }

            // ── 网络 ──
            SettingsCard(title = "网络", subtitle = "代理与请求头") {
                DeferredTextField(
                    label = "代理地址", value = proxyHost, placeholder = "如 127.0.0.1"
                ) { proxyHost = it.trim(); apply() }
                Spacer(Modifier.height(8.dp))
                DeferredTextField(
                    label = "代理端口", value = proxyPort, placeholder = "如 7890"
                ) { proxyPort = it.filter { c -> c.isDigit() }; apply() }
                Spacer(Modifier.height(8.dp))
                DeferredTextField(
                    label = "User-Agent", value = userAgent
                ) { userAgent = it; apply() }
                Spacer(Modifier.height(8.dp))
                DeferredTextField(
                    label = "全局 Referer", value = referer,
                    placeholder = "留空自动使用站点域名"
                ) { referer = it.trim(); apply() }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    "跳过 HTTPS 证书校验",
                    "源站证书链不完整导致 SSL 握手失败时才临时开启",
                    insecureTls
                ) { insecureTls = it; apply() }
                Text(
                    "⚠️ 开启后不再校验证书与主机名，同一网络下的攻击者可以窃听或篡改下载内容。\n"
                            + "建议只在确认是源站证书问题后临时打开，用完关掉。",
                    fontSize = 11.sp,
                    color = if (insecureTls) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }

            // ── 外观 ──
            SettingsCard(title = "外观", subtitle = "主题") {
                SwitchRow("浅色主题", "默认深色", themeLight) {
                    themeLight = it; apply(); onThemeChanged(it)
                }
                SwitchRow("动态取色", "跟随系统壁纸（Android 12+）", dynamicColor) {
                    dynamicColor = it; apply(); onDynamicColorChanged(it)
                }
            }

            // ── 关于 ──
            // 版本号取自 app/build.gradle.kts 的 versionName，不再硬编码
            SettingsCard(title = "关于", subtitle = "FluxM3U8 v${BuildConfig.VERSION_NAME}") {
                Text(
                    "使用 Kotlin + Jetpack Compose 原生开发，下载引擎以前台服务常驻，"
                            + "锁屏、切后台、划掉应用都不会中断。\n"
                            + "支持多清晰度、AES-128 加密、直播流、断点续传。当前速度：${formatSpeed(DownloadManager.totalSpeed())}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "请仅下载你拥有合法权利的内容。\n"
                        +"本程序全程由deepseek-v4.1开发。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 滑块。
 *
 * **拖动过程中只更新本地状态，松手（[onCommit]）才落盘**。
 * 之前每拖一帧就写一次 SharedPreferences 并通知下载引擎刷新设置，
 * 把并发从 1 拖到 64 会触发 ~63 次 OkHttp 客户端重建，白白卡顿。
 *
 * 拖动中的连续值必须由 [Slider] 自己持有（[local]），
 * 不能用外部的 Int 值反灌：拖动中外部值每次都是「取整后」的旧值，
 * 回灌会让 `SliderState` 被反复写回整点，手指拖到底也只停在按下位置。
 */
@Composable
private fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    hint: String = "",
    onValueChange: (Int) -> Unit,
    onCommit: () -> Unit
) {
    // 拖动中的值只保留在本地。外部的 Int 值仅在「非拖动」时回灌。
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) local = value.toFloat() }

    // 固化回调身份，避免每帧新建 lambda 实例导致下游状态重建。
    val currentChange by rememberUpdatedState(onValueChange)
    val currentCommit by rememberUpdatedState(onCommit)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                local.roundToInt().toString(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (hint.isNotBlank()) {
            Text(hint, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        Slider(
            value = local,
            onValueChange = {
                dragging = true
                local = it
                currentChange(it.roundToInt())
            },
            onValueChangeFinished = {
                dragging = false
                currentCommit()
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = (range.count() - 2).coerceAtLeast(0)
        )
    }
}

/**
 * 数字输入框。
 *
 * 只在**失焦或按了键盘 Done** 时才回调——以前是每敲一个字符就写一遍
 * SharedPreferences（主线程同步 IO），还会顺带触发下载引擎的设置刷新。
 */
@Composable
private fun OutlinedNumberField(
    label: String,
    value: String,
    hint: String = "",
    onChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() } },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (hadFocus && !state.isFocused) onChange(text)
                    hadFocus = state.isFocused
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onChange(text) }),
            shape = RoundedCornerShape(12.dp)
        )
        if (hint.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(hint, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
    }
}

/**
 * 文本输入框，同样只在失焦 / Done 时提交。
 * 用于代理地址、UA、Referer 这类一改动就要重建 HTTP 客户端的字段。
 */
@Composable
private fun DeferredTextField(
    label: String,
    value: String,
    placeholder: String = "",
    onCommit: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (hadFocus && !state.isFocused) onCommit(text)
                hadFocus = state.isFocused
            },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        shape = RoundedCornerShape(12.dp)
    )
}
