@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.walkman.tv.ui.mobile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.store.LyricSize
import com.walkman.tv.data.store.VinylColor
import com.walkman.tv.playback.update.UpdateState
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手机设置：音质 / 内置增益 / 直通 / 强制软解 / 歌词 / 黑胶颜色 / 歌单备份 / 检查更新 / 关于。
 * 开关和电视版同一份 SettingsStore，改一处两边都生效。
 */
@Composable
fun MobileSettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by appContainer.settingsStore.settings.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(appContainer.libraryStore.exportBackupJson().toByteArray(Charsets.UTF_8))
                    } ?: error("打不开文件")
                }
            }
            status = ok.fold({ "备份已导出" }, { "导出失败：${it.message}" })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (raw.isNullOrEmpty()) {
                status = "读取备份失败"
            } else {
                val r = appContainer.libraryStore.importBackupJson(raw)
                status = r.fold({ "已恢复 $it 个列表" }, { "恢复失败：${it.message}" })
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
        Spacer(Modifier.height(12.dp))

        SettingSection("播放音质") {
            FlowRow {
                Quality.orderedHighToLow.reversed().forEach { q ->
                    FilterChip(
                        selected = settings.preferredQuality == q,
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(preferredQuality = q) } } },
                        label = { Text(q.displayName, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.BrandPrimary,
                            selectedLabelColor = Color.White,
                        ),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }
            }
            Text("默认最高音质（FLAC 24bit），播不了会自动往下降。", fontSize = 12.sp, color = AppColors.TextMuted)
        }

        SettingSection("音频") {
            SwitchRow("内置增益（声音偏小时开启，约 +6dB）", settings.builtinGainEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(builtinGainEnabled = !it.builtinGainEnabled) } }
            }
            SwitchRow("Hi-Res 硬件直通（播放无损闪退时请关闭）", settings.audioOffloadEnabled) {
                scope.launch { appContainer.settingsStore.update { it.copy(audioOffloadEnabled = !it.audioOffloadEnabled) } }
            }
            SwitchRow("强制软件解码（播放闪退或没声音时请开启）", settings.forceSoftwareDecoding) {
                scope.launch { appContainer.settingsStore.update { it.copy(forceSoftwareDecoding = !it.forceSoftwareDecoding) } }
            }
            Text("硬件直通默认关闭；直通开着时内置增益不生效。", fontSize = 12.sp, color = AppColors.TextMuted)
        }

        SettingSection("歌词") {
            SwitchRow("显示歌词翻译", settings.showLyricTranslation) {
                scope.launch { appContainer.settingsStore.update { it.copy(showLyricTranslation = !it.showLyricTranslation) } }
            }
            FlowRow {
                LyricSize.entries.forEach { size ->
                    FilterChip(
                        selected = settings.lyricSize == size,
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(lyricSize = size) } } },
                        label = { Text(size.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.BrandPrimary,
                            selectedLabelColor = Color.White,
                        ),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }

        SettingSection("黑胶颜色") {
            FlowRow {
                VinylColor.entries.forEach { color ->
                    FilterChip(
                        selected = settings.vinylColor == color,
                        onClick = { scope.launch { appContainer.settingsStore.update { it.copy(vinylColor = color) } } },
                        label = { Text(color.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.BrandPrimary,
                            selectedLabelColor = Color.White,
                        ),
                        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }

        SettingSection("歌单备份") {
            Row {
                OutlinedButton(onClick = { exportLauncher.launch("kitv-music-backup.json") }) {
                    Text("备份歌单", fontSize = 14.sp, color = AppColors.BrandPrimary)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("恢复歌单", fontSize = 14.sp, color = AppColors.BrandPrimary)
                }
            }
            Text("把「我喜欢的」、播放历史和自建歌单导出成一个 JSON 文件；换手机、重装后用「恢复歌单」导回来。", fontSize = 12.sp, color = AppColors.TextMuted)
            status?.let {
                Text(it, fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }

        SettingSection("检查更新") {
            MobileUpdateSection()
        }

        SettingSection("关于") {
            Text("KITV音乐 v${appContainer.updateManager.currentVersion}", fontSize = 14.sp, color = AppColors.TextPrimary)
            Text("更新地址：github.com/florakiki/walkman-tv", fontSize = 12.sp, color = AppColors.TextMuted)
            Text("音源：Ki.js 内置（测试版），正式版不带源、需手动导入。", fontSize = 12.sp, color = AppColors.TextMuted)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.BrandPrimary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppColors.BrandPrimary),
        )
    }
}

@Composable
private fun MobileUpdateSection() {
    val ctx = LocalContext.current
    val mgr = appContainer.updateManager
    val state by mgr.state.collectAsState()
    var permHint by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (mgr.state.value is UpdateState.Idle) appContainer.appScope.launch { mgr.check() }
    }

    Text("当前版本 v${mgr.currentVersion}", color = AppColors.TextSecondary, fontSize = 13.sp)
    when (val s = state) {
        is UpdateState.Checking -> Text("正在检查更新…", color = AppColors.TextMuted, fontSize = 13.sp)
        is UpdateState.UpToDate -> Text("已是最新版本", color = AppColors.BrandPrimary, fontSize = 13.sp)
        is UpdateState.Failed -> Text("检查失败：${s.message}", color = AppColors.Danger, fontSize = 12.sp)
        is UpdateState.Available -> {
            Text("发现新版本 v${s.release.versionName}", color = AppColors.BrandPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (s.release.notes.isNotBlank()) Text(s.release.notes, color = AppColors.TextMuted, fontSize = 12.sp)
        }
        is UpdateState.Downloaded -> {
            Text("下载完成", color = AppColors.BrandPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (permHint) Text("请在系统弹出的设置里允许「KITV音乐」安装未知应用，返回后再点「立即安装」。", color = AppColors.Warning, fontSize = 12.sp)
        }
        else -> {}
    }
    Spacer(Modifier.height(8.dp))
    val actionLabel = when (val s = state) {
        is UpdateState.Checking -> "检查中…"
        is UpdateState.UpToDate -> "重新检查"
        is UpdateState.Failed -> "重试"
        is UpdateState.Available -> "下载并安装"
        is UpdateState.Downloading -> "正在下载 ${(s.progress * 100).toInt()}%…"
        is UpdateState.Downloaded -> "立即安装"
        is UpdateState.Idle -> "检查更新"
    }
    Button(
        onClick = {
            when (val s = state) {
                is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed ->
                    appContainer.appScope.launch { mgr.check() }
                is UpdateState.Available ->
                    appContainer.appScope.launch { mgr.download(s.release) }
                is UpdateState.Downloaded ->
                    permHint = !mgr.install(ctx, s.file)
                else -> {}
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
    ) {
        Text(actionLabel, fontSize = 14.sp)
    }
}
