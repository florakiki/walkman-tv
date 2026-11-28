package com.walkman.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.walkman.tv.ui.components.FolderBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.walkman.tv.data.sync.LanSyncController
import com.walkman.tv.data.sync.NsdSyncDiscovery
import com.walkman.tv.data.sync.SyncDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.walkman.tv.data.model.Quality
import com.walkman.tv.di.CrashDiagnostics
import com.walkman.tv.di.getLanIp
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.components.QrDialog
import com.walkman.tv.ui.components.TvFocusable
import com.walkman.tv.ui.components.TvPill
import com.walkman.tv.ui.components.TvTextFieldHost
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.playback.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importFocus = remember { FocusRequester() }
    val settings by appContainer.settingsStore.settings.collectAsState()
    val scripts by appContainer.scriptStore.scripts.collectAsState()
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.walkman.tv.data.model.UserScript?>(null) }

    // System file picker (SAF) for .js scripts.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                status = "正在读取文件…"
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (raw.isNullOrEmpty()) {
                    status = "读取文件失败"
                } else {
                    status = "正在导入文件…"
                    val r = appContainer.scriptStore.import(raw)
                    status = r.fold({ it.message }, { "导入失败：${it.message}" })
                }
            }
        }
    }

    // In-app File browser for the download folder (TVs often lack a SAF picker). Writing needs
    // WRITE_EXTERNAL_STORAGE on API ≤ 29, or All-files access on 30+.
    var showDownloadBrowser by remember { mutableStateOf(false) }
    var dlPermHint by remember { mutableStateOf(false) }

    // System file picker (SAF) for a playlist-backup JSON produced by 「备份歌单」.
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                status = "正在读取备份…"
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                }
                if (raw.isNullOrEmpty()) {
                    status = "读取备份失败"
                } else {
                    status = "正在恢复歌单…"
                    val r = appContainer.libraryStore.importBackupJson(raw)
                    status = r.fold(
                        { "已恢复 $it 个歌单（我喜欢的、播放历史、自建列表）" },
                        { "恢复失败：${it.message}" },
                    )
                }
            }
        }
    }
    val dlPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) showDownloadBrowser = true else dlPermHint = true }

    fun openDownloadPicker() {
        if (hasStorageWriteAccess(context)) {
            showDownloadBrowser = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dlPermHint = true
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        } else {
            dlPermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // Receive script payloads from the phone-to-TV QR flow.
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptUrl.collect { u ->
            showQr = false
            importFromUrl(scope, u) { status = it }
        }
    }
    LaunchedEffect(Unit) {
        appContainer.events.qrScriptText.collect { raw ->
            showQr = false
            status = "正在导入上传的脚本…"
            val r = appContainer.scriptStore.import(raw)
            status = r.fold({ it.message }, { "导入失败：${it.message}" })
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(top = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("播放音质") {
            // 8 tiers including extended (hires/atmos/master) — wrap so they fit on any TV width.
            // Each pill carries a small icon to make the tier intent scannable in one glance.
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Quality.orderedHighToLow.reversed().forEach { q ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(preferredQuality = q) } } },
                        selected = settings.preferredQuality == q,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.tv.material3.Icon(
                                imageVector = qualityIcon(q),
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(q.displayName, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Section("发现页音源") {
            // Toggle each source in/out. The discover (home) page subtitle and section
            // contents track this list in real time — see HomeStore.loadIfNeeded.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.model.SourceID.homePageOrder.forEach { src ->
                    val active = src in settings.homeSources
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update { s ->
                                    val next = if (active) s.homeSources - src else s.homeSources + src
                                    // Spec §8: never let the set collapse to empty — keep at least
                                    // one source so the discover page stays usable.
                                    s.copy(homeSources = if (next.isEmpty()) s.homeSources else next)
                                }
                            }
                        },
                        selected = active,
                    ) {
                        Text(src.displayName, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("内置直连兜底") {
            ToggleRow("脚本失败时尝试内置直连（kw/wy）", settings.fallbackEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(fallbackEnabled = !it.fallbackEnabled) } }
            }
        }

        Section("音频解码") {
            ToggleRow("Hi-Res 硬件直通（播放无损闪退时请关闭）", settings.audioOffloadEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(audioOffloadEnabled = !it.audioOffloadEnabled) } }
            }
            ToggleRow("强制软件解码（播放闪退或没声音时请开启，下一首起生效）", settings.forceSoftwareDecoding) {
                scope.launch { appContainer.settingsStore.update { it.copy(forceSoftwareDecoding = !it.forceSoftwareDecoding) } }
            }
        }

        Section("内置音量") {
            // 第二十五轮：增益固定开（约 +6dB），开关撤掉。直通开着时增益还是被绕过，
            // 在这里直接说出来。
            Text(
                "内置增益固定开启（约 +6dB，带防破音软限幅），不改电视系统音量；与均衡器互不冲突。硬件直通开启时不生效。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            if (settings.audioOffloadEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "硬件直通已开启：音频走硬件直通，内置增益（固定 +6dB）不生效。关闭直通后增益自动生效。",
                    color = AppColors.Warning,
                    fontSize = 11.sp,
                )
            }
        }

        Section("歌词翻译") {
            ToggleRow("显示歌词翻译（外语歌有翻译时在每句下方显示）", settings.showLyricTranslation) {
                scope.launch { appContainer.settingsStore.update { it.copy(showLyricTranslation = !it.showLyricTranslation) } }
            }
        }

        Section("歌词大小") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.walkman.tv.data.store.LyricSize.entries.forEach { size ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(lyricSize = size) } } },
                        selected = settings.lyricSize == size,
                    ) {
                        Text(size.label, fontSize = 13.sp)
                    }
                }
            }
        }

        Section("播放界面") {
            ToggleRow("播放控件自动隐藏", settings.autoHidePlayerControls) {
                scope.launch { appContainer.settingsStore.update { it.copy(autoHidePlayerControls = !it.autoHidePlayerControls) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开启后，播放页 5 秒无操作自动隐藏进度条和播放按钮，按方向键或确认键重新显示。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("下载目录") {
            val roots = remember { appContainer.downloadStore.availableRoots() }
            val treeUriStr = settings.customDownloadTreeUri
            val usingTree = treeUriStr != null
            // A folder picked via the in-app browser is a customDownloadDir that isn't one of the
            // app-scoped volume roots.
            val usingCustomFolder = settings.customDownloadDir != null &&
                roots.none { it.dir.absolutePath == settings.customDownloadDir }
            // Current volume = configured path, or the first volume (default) when unset. Only
            // highlighted when a SAF folder is NOT in use.
            val currentPath = settings.customDownloadDir
                ?: roots.firstOrNull()?.dir?.absolutePath
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                roots.forEachIndexed { index, opt ->
                    val path = opt.dir.absolutePath
                    TvPill(
                        onClick = {
                            scope.launch {
                                appContainer.settingsStore.update {
                                    // Picking a volume clears any SAF folder. Store null for the
                                    // default (first) volume so it keeps following the system default.
                                    it.copy(
                                        customDownloadDir = if (index == 0) null else path,
                                        customDownloadTreeUri = null,
                                    )
                                }
                            }
                        },
                        selected = !usingTree && path == currentPath,
                    ) {
                        Text(opt.label, fontSize = 13.sp)
                    }
                }
                // Pick any folder via the in-app browser (incl. USB/SD) — no SAF needed.
                TvPill(
                    onClick = { openDownloadPicker() },
                    selected = usingTree || usingCustomFolder,
                ) {
                    Text("选择其他文件夹…", fontSize = 13.sp)
                }
            }
            if (dlPermHint) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要存储写入权限：请在系统设置里为「KITV音乐」开启文件访问（或允许存储权限），返回后再点选择。",
                    color = AppColors.Warning,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    usingTree -> "已选文件夹：" + (
                        treeUriStr?.let { runCatching { appContainer.downloadStore.treeDisplayName(Uri.parse(it)) }.getOrNull() }
                            ?: "自定义文件夹"
                        )
                    usingCustomFolder -> "已选文件夹：" + (settings.customDownloadDir ?: "")
                    else -> currentPath ?: "（应用默认音乐目录）"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            Text(
                if (usingTree || usingCustomFolder) {
                    "下载会保存到你选的文件夹（可被文件管理器/其它应用浏览）。切换目录只影响之后的下载。"
                } else {
                    "存储卷目录在应用专属空间（卸载会清除、不易在文件管理器看到）；想存到可见位置请点「选择其他文件夹」。切换只影响之后的下载，已下载的仍可正常播放。"
                },
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("下载并发数") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..6).forEach { n ->
                    TvPill(
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(maxConcurrentDownloads = n) } } },
                        selected = settings.maxConcurrentDownloads == n,
                    ) {
                        Text("$n", fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "同时下载的最大歌曲数（批量下载时排队，越大越快但越占带宽）。默认 3。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("批量下载") {
            ToggleRow("已下载的也按新音质重下", settings.redownloadOnQualityChange) {
                scope.launch { appContainer.settingsStore.update { it.copy(redownloadOnQualityChange = !it.redownloadOnQualityChange) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开启后，对歌单「下载全部」时，已下载但音质与所选不同的歌会按新音质重新下载（如 128k 升级到 FLAC）；关闭则一律跳过已下载。默认开启。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        Section("歌单备份") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvPill(
                    onClick = {
                        scope.launch {
                            status = "正在导出…"
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val json = appContainer.libraryStore.exportBackupJson()
                                    val stamp = java.text.SimpleDateFormat(
                                        "yyyyMMdd-HHmmss", java.util.Locale.US,
                                    ).format(java.util.Date())
                                    val file = java.io.File(
                                        appContainer.downloadStore.downloadRoot,
                                        "kitv-music-backup-$stamp.json",
                                    )
                                    file.writeText(json)
                                    file.absolutePath
                                }
                            }
                            status = result.fold(
                                { "备份已导出：$it" },
                                { "导出失败：${it.message}" },
                            )
                        }
                    },
                    selected = true,
                ) { Text("备份歌单", fontSize = 14.sp) }
                TvPill(
                    onClick = {
                        runCatching {
                            backupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }.onFailure { status = "未找到可用的文件选择器" }
                    },
                    selected = true,
                ) { Text("恢复歌单", fontSize = 14.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "备份：把「我喜欢的」、播放历史和自建歌单导出成一个 JSON 文件，存在下载目录；换电视、重装后用「恢复歌单」选那个文件导回来。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            status?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        SyncSection()

        Section("自定义音源") {
            Text(
                "粘贴 URL 自动远程拉取；直接粘贴脚本则按 JS 代码导入；或上传 .js 文件；扫码可在手机/电脑操作。",
                color = AppColors.TextMuted,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvTextFieldHost(modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp)) { fieldModifier ->
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(fieldModifier),
                        placeholder = { Text("URL（http://...）或直接粘贴 JS 代码", color = AppColors.TextMuted) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { importInput(scope, url) { status = it } }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppColors.TextPrimary,
                            unfocusedTextColor = AppColors.TextPrimary,
                            focusedBorderColor = AppColors.AccentGreen,
                            unfocusedBorderColor = AppColors.Card,
                            cursorColor = AppColors.AccentGreen,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { importInput(scope, url) { status = it } },
                    selected = true,
                    focusRequester = importFocus,
                ) {
                    Text("导入", fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = {
                        runCatching {
                            filePicker.launch(arrayOf("application/javascript", "text/javascript", "application/x-javascript", "text/plain", "*/*"))
                        }.onFailure { status = "未找到可用的文件选择器" }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上传文件", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(8.dp))
                TvPill(
                    onClick = { showQr = true },
                    shape = CircleShape,
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = "扫码导入", modifier = Modifier.size(22.dp))
                }
            }
            status?.let { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

            Spacer(Modifier.padding(top = 6.dp))
            if (scripts.isEmpty()) {
                Text("尚未导入任何脚本", color = AppColors.TextMuted, fontSize = 13.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scripts.forEach { s ->
                        // Two side-by-side focusables (toggle area + delete pill). The delete
                        // path used to live on the UI's rememberCoroutineScope, which gets
                        // cancelled when the user navigates away mid-click; route through
                        // appContainer.appScope so the unload + filter + persist sequence
                        // can't be interrupted between steps.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvFocusable(
                                onClick = { scope.launch { appContainer.scriptStore.setEnabled(s.id, !s.enabled) } },
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                        Text("v${s.version}  ${s.author}", color = AppColors.TextMuted, fontSize = 12.sp)
                                    }
                                    Text(
                                        if (s.enabled) "已启用" else "已停用",
                                        color = if (s.enabled) AppColors.AccentGreen else AppColors.TextMuted,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            TvPill(
                                onClick = { pendingDelete = s },
                                accent = AppColors.Danger,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                            ) { Text("删除", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }

        Section("调试") {
            ToggleRow("显示调试信息", settings.showDebugInfo) {
                scope.launch { appContainer.settingsStore.update { it.copy(showDebugInfo = !it.showDebugInfo) } }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "排查音源等问题时开启，平时不需要。目前包括：播放页歌手名下方显示获取播放地址、播放失败自动跳过、换音源等状态。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            TvPill(
                onClick = { showDiagnostics = true },
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("查看诊断记录", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "App 闪退时会自动记下当时的页面、焦点、按键和报错，只保存在本机，不上传。下次再闪退，进来看这里就知道是哪一步出的问题。注意：只能记 Kotlin 层的崩溃，原生层闪退记不到。",
                color = AppColors.TextMuted,
                fontSize = 11.sp,
            )
        }

        UpdateSection()
    }

    pendingDelete?.let { target ->
        DeleteScriptConfirmDialog(
            scriptName = target.name,
            onCancel = { pendingDelete = null },
            onConfirm = {
                // Use the appScope (process-lived) so the unload + filter + persist sequence
                // can't be cancelled by leaving Settings mid-delete.
                pendingDelete = null
                appContainer.appScope.launch {
                    appContainer.scriptStore.remove(target.id)
                    status = "已删除：${target.name}"
                }
            },
        )
    }

    if (showQr) {
        val ip = remember { getLanIp() }
        val port = appContainer.localServer?.boundPort
        if (ip != null && port != null) {
            QrDialog(
                url = "http://$ip:$port/script",
                title = "扫码导入音源",
                subtitle = "手机扫码、电脑打开地址都可以，可粘贴脚本 URL 或上传 .js 文件",
                onDismiss = { showQr = false },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showQr = false },
                title = { Text("无法启动扫码") },
                text = { Text("请确认电视已连接 Wi-Fi 且本地服务已启动后再试。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showQr = false }) {
                        Text("好", color = AppColors.AccentGreen)
                    }
                },
                containerColor = AppColors.BgPanel,
                titleContentColor = AppColors.TextPrimary,
                textContentColor = AppColors.TextSecondary,
            )
        }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }

    if (showDownloadBrowser) {
        FolderBrowser(
            onPick = { dir ->
                dlPermHint = false
                showDownloadBrowser = false
                scope.launch {
                    appContainer.settingsStore.update {
                        it.copy(customDownloadDir = dir.absolutePath, customDownloadTreeUri = null)
                    }
                }
            },
            onCancel = { showDownloadBrowser = false },
        )
    }
}

/**
 * 诊断记录弹窗：显示最近一次崩溃的记录（时间、页面、浮层、焦点、按键、正在播的歌、
 * 报错堆栈），没有记录就说没有。记录只存在本机文件里，清除按钮删掉它。
 */
@Composable
private fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashDiagnostics.readLastReport(context)) }
    // 2026-11-27（Yang 报“只显示一部分、下面的看不到”）：日志区之前虽然挂了
    // verticalScroll，但遥控器上下键走不到它上面——焦点直接落在下面两个按钮上，滚不动，
    // 下面的记录看不到。改成日志区自己可获焦、开弹窗就先落到它上面，上下键按一下滚一段；
    // 滚到底 / 顶时放行，焦点才能走到清除记录 / 关闭按钮上。只修展示，不动写入逻辑。
    val scrollState = rememberScrollState()
    val scrollFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { runCatching { scrollFocus.requestFocus() } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断记录", color = AppColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .focusRequester(scrollFocus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val stepPx = with(density) { 200.dp.toPx() }
                        when (e.key) {
                            Key.DirectionDown -> {
                                // 到底了就放行，焦点走到下面的按钮上。
                                if (!scrollState.canScrollForward) return@onPreviewKeyEvent false
                                scope.launch { scrollState.scrollTo(scrollState.value + stepPx.toInt()) }
                                true
                            }
                            Key.DirectionUp -> {
                                // 到顶了就放行。
                                if (!scrollState.canScrollBackward) return@onPreviewKeyEvent false
                                scope.launch { scrollState.scrollTo((scrollState.value - stepPx).toInt().coerceAtLeast(0)) }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                val current = report
                if (current == null) {
                    Text(
                        "暂无诊断记录（没有抓到过崩溃）。",
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                } else {
                    // 堆栈放最前面、不用滚动就能看到：上次 Yang 只拍到对话框顶部，堆栈藏在
                    // 下面翻不到。只露前 15 行（文件 + 行号都在里面），完整堆栈还在下面。
                    val stackLines = current.substringAfter("堆栈：\n", "")
                        .lineSequence().take(15).joinToString("\n")
                    Text(
                        "堆栈（前 15 行）",
                        color = AppColors.Danger,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stackLines.ifEmpty { "（无堆栈）" },
                        color = AppColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "完整记录",
                        color = AppColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current,
                        color = AppColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭", color = AppColors.AccentGreen)
            }
        },
        dismissButton = {
            if (report != null) {
                androidx.compose.material3.TextButton(onClick = {
                    CrashDiagnostics.clearReport(context)
                    report = null
                }) {
                    Text("清除记录", color = AppColors.Danger)
                }
            }
        },
        containerColor = AppColors.BgPanel,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

/** Whether we can write to arbitrary folders with java.io.File right now. */
private fun hasStorageWriteAccess(ctx: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

/**
 * Auto-detects the input: if it looks like an http(s) URL, fetches the URL and imports the
 * body; otherwise treats the input as raw JS code and imports it directly. Used by both the
 * "导入" button and the IME "完成" action.
 */
private fun importInput(scope: kotlinx.coroutines.CoroutineScope, input: String, onStatus: (String) -> Unit) {
    if (input.isBlank()) return
    val trimmed = input.trim()
    val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    scope.launch {
        onStatus(if (isUrl) "正在下载脚本…" else "正在导入脚本…")
        val result = runCatching {
            val raw = if (isUrl) appContainer.fetchText(trimmed) else trimmed
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ it.message }, { "导入失败：${it.message}" }))
    }
}

// Kept for backwards-compat callers that already exist; just delegates.
private fun importFromUrl(scope: kotlinx.coroutines.CoroutineScope, url: String, onStatus: (String) -> Unit) {
    if (url.isBlank()) return
    scope.launch {
        onStatus("正在导入…")
        val result = runCatching {
            val raw = appContainer.fetchText(url.trim())
            appContainer.scriptStore.import(raw).getOrThrow()
        }
        onStatus(result.fold({ it.message }, { "导入失败：${it.message}" }))
    }
}

/**
 * 检查更新: auto-checks GitHub once on open, then drives the whole download → install flow
 * inline. State lives in [com.walkman.tv.playback.update.UpdateManager] so leaving and
 * re-entering Settings resumes wherever it was (e.g. keeps a "有新版本" result).
 */
@Composable
private fun UpdateSection() {
    val ctx = LocalContext.current
    val mgr = appContainer.updateManager
    val state by mgr.state.collectAsState()
    var permHint by remember { mutableStateOf(false) }

    // Check + download run on the process-lived appScope (not a composable scope) so leaving
    // Settings mid-flight doesn't cancel them — the StateFlow keeps progressing and the UI
    // re-attaches to wherever it got to when the user comes back.
    // Auto-check once — only from a clean Idle so re-entering Settings doesn't re-hit GitHub.
    LaunchedEffect(Unit) {
        if (mgr.state.value is UpdateState.Idle) appContainer.appScope.launch { mgr.check() }
    }
    // Note: auto-launching the installer on download-complete is handled globally in RootScreen.

    Section("检查更新") {
        Text("当前版本 v${mgr.currentVersion}", color = AppColors.TextSecondary, fontSize = 13.sp)

        // Status line — plain text, no focusable, so it can freely swap per state.
        when (val s = state) {
            is UpdateState.Checking ->
                Text("正在检查更新…", color = AppColors.TextMuted, fontSize = 13.sp)
            is UpdateState.UpToDate ->
                Text("已是最新版本", color = AppColors.AccentGreen, fontSize = 13.sp)
            is UpdateState.Failed ->
                Text("检查失败：${s.message}", color = AppColors.Danger, fontSize = 12.sp)
            is UpdateState.Available -> {
                Text("发现新版本 v${s.release.versionName}", color = AppColors.AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (s.release.notes.isNotBlank()) {
                    Text(s.release.notes, color = AppColors.TextMuted, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
            is UpdateState.Downloaded -> {
                Text("下载完成", color = AppColors.AccentGreen, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (permHint) {
                    Text(
                        "请在系统弹出的设置里允许「KITV音乐」安装未知应用，返回后再点「立即安装」。",
                        color = AppColors.Warning, fontSize = 12.sp,
                    )
                }
            }
            else -> {} // Idle / Downloading — the button label carries the state
        }

        // ONE stable button: its label + action follow the state, but the composable node never
        // leaves the tree — so remote focus stays put across 检查→检查中→有更新 transitions
        // instead of drifting to some other control on the page.
        val actionLabel = when (val s = state) {
            is UpdateState.Checking -> "检查中…"
            is UpdateState.UpToDate -> "重新检查"
            is UpdateState.Failed -> "重试"
            is UpdateState.Available -> "下载并安装"
            is UpdateState.Downloading -> "正在下载 ${(s.progress * 100).toInt()}%…"
            is UpdateState.Downloaded -> "立即安装"
            is UpdateState.Idle -> "检查更新"
        }
        TvPill(
            onClick = {
                when (val s = state) {
                    is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed ->
                        appContainer.appScope.launch { mgr.check() }
                    is UpdateState.Available ->
                        appContainer.appScope.launch { mgr.download(s.release) }
                    is UpdateState.Downloaded ->
                        permHint = !mgr.install(ctx, s.file)
                    else -> {} // Checking / Downloading — busy, no-op
                }
            },
            selected = true,
        ) { Text(actionLabel, fontSize = 14.sp) }
    }
}

/**
 * 局域网同步歌单（电视版入口；手机版在 MobileSyncScreen 里用同一套 LanSyncController）。
 * 电视亮二维码，手机扫码配对；同一 WiFi 下的手机也会自动出现在列表里，点一下填 6 位同步码
 * 就能配对、勾选，再点「开始同步」。只同步歌单，不同步已下载的音频文件。
 */
@Composable
private fun SyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { LanSyncController(appContainer, context) }
    val discovered by controller.discovered.collectAsState()
    val saved by controller.savedPeers.collectAsState()
    val selected by controller.selectedIds.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    var pairingTarget by remember { mutableStateOf<NsdSyncDiscovery.DiscoveredPeer?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var pairingError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    Section("局域网同步歌单") {
        Text(
            "同一 WiFi 下，和手机互相同步歌单（收藏 / 历史 / 自建列表），不同步已下载的音频。",
            color = AppColors.TextMuted,
            fontSize = 11.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvPill(onClick = { showQr = true }, selected = true) { Text("显示二维码", fontSize = 14.sp) }
        }
        Text(
            "本机同步码：${controller.myCode.chunked(2).joinToString(" ")}" +
                (controller.myAddress()?.let { "（$it）" } ?: ""),
            color = AppColors.TextSecondary,
            fontSize = 13.sp,
        )
        if (discovered.isEmpty() && saved.isEmpty()) {
            Text("还没发现设备：确认手机也在同步页、连着同一个 WiFi；或让手机扫上面的二维码。", color = AppColors.TextMuted, fontSize = 12.sp)
        }
        discovered.forEach { d ->
            val savedPeer = controller.savedPeerFor(d.host ?: "", d.port)
            ToggleRow(
                "${d.displayName}（${d.host}:${d.port}）" + if (savedPeer == null) " — 未配对，点一下填同步码" else "",
                savedPeer?.let { it.id in selected } ?: false,
            ) {
                if (savedPeer != null) {
                    controller.toggleSelect(savedPeer.id)
                } else {
                    pairingTarget = d
                    pairingCode = ""
                    pairingError = null
                }
            }
        }
        saved.forEach { peer ->
            ToggleRow("${peer.name}（${peer.host}:${peer.port}）", peer.id in selected) {
                controller.toggleSelect(peer.id)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncDirection.entries.forEach { dir ->
                TvPill(
                    onClick = { controller.direction = dir },
                    selected = controller.direction == dir,
                ) {
                    Text(dir.label, fontSize = 13.sp)
                }
            }
        }
        TvPill(
            onClick = { scope.launch { controller.syncSelected() } },
            selected = true,
        ) {
            Text(if (controller.busy) "同步中…" else "开始同步", fontSize = 14.sp)
        }
        controller.status?.let {
            Text(it, color = AppColors.TextSecondary, fontSize = 12.sp)
        }
    }

    if (showQr) {
        val payload = remember { controller.myQrPayload() }
        if (payload != null) {
            QrDialog(
                url = payload,
                title = "扫码同步歌单",
                subtitle = "在手机 KITV音乐 的同步页点「扫码添加设备」扫这个码",
                onDismiss = { showQr = false },
            )
        } else {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showQr = false },
                title = { Text("无法启动扫码") },
                text = { Text("请确认电视已连接 Wi-Fi 且本地服务已启动后再试。") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showQr = false }) {
                        Text("好", color = AppColors.AccentGreen)
                    }
                },
                containerColor = AppColors.BgPanel,
                titleContentColor = AppColors.TextPrimary,
                textContentColor = AppColors.TextSecondary,
            )
        }
    }

    pairingTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pairingTarget = null },
            title = { Text("配对 ${target.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("在手机的同步页看 6 位同步码，填进来：", color = AppColors.TextSecondary, fontSize = 13.sp)
                    TvTextFieldHost(shape = RoundedCornerShape(4.dp)) { fieldModifier ->
                        OutlinedTextField(
                            value = pairingCode,
                            onValueChange = { if (it.length <= 6) pairingCode = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().then(fieldModifier),
                            placeholder = { Text("6 位同步码", color = AppColors.TextMuted) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AppColors.TextPrimary,
                                unfocusedTextColor = AppColors.TextPrimary,
                                focusedBorderColor = AppColors.AccentGreen,
                                unfocusedBorderColor = AppColors.Card,
                                cursorColor = AppColors.AccentGreen,
                            ),
                        )
                    }
                    pairingError?.let {
                        Text(it, color = AppColors.Danger, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch {
                        val r = controller.addManual("${target.host}:${target.port}", pairingCode)
                        r.fold(
                            { pairingTarget = null },
                            { pairingError = "配对失败：${it.message}" },
                        )
                    }
                }) { Text("配对", color = AppColors.AccentGreen) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pairingTarget = null }) { Text("取消") }
            },
            containerColor = AppColors.BgPanel,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AppColors.AccentGreen, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    TvFocusable(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppColors.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (on) "开" else "关", color = if (on) AppColors.AccentGreen else AppColors.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Per-tier Material icon — small visual cue alongside the displayName in the picker.
 *  Matches iOS's per-tier symbol convention (spec §4) using Material's closest analogues. */
private fun qualityIcon(q: Quality): androidx.compose.ui.graphics.vector.ImageVector = when (q) {
    Quality.K128 -> Icons.Filled.MusicNote
    Quality.K320 -> Icons.Filled.GraphicEq
    Quality.FLAC -> Icons.Filled.HighQuality
    Quality.FLAC24, Quality.HIRES -> Icons.Filled.AutoAwesome
    Quality.ATMOS, Quality.ATMOS_PLUS -> Icons.Filled.SurroundSound
    Quality.MASTER -> Icons.Filled.Diamond
}

@Composable
private fun DeleteScriptConfirmDialog(
    scriptName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.BgPanel)
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text("删除自定义音源", color = AppColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                "确定要删除「$scriptName」吗？删除后需要重新导入才能使用。",
                color = AppColors.TextSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TvPill(
                    onClick = onCancel,
                    focusRequester = cancelFocus,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                TvPill(
                    onClick = onConfirm,
                    selected = true,
                    accent = AppColors.Danger,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text("确定", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
