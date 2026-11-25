package com.walkman.tv.ui.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkman.tv.data.sync.LanSyncController
import com.walkman.tv.data.sync.NsdSyncDiscovery
import com.walkman.tv.data.sync.SyncDirection
import com.walkman.tv.data.sync.SyncPeer
import com.walkman.tv.di.encodeQrToBitmap
import com.walkman.tv.ui.appContainer
import com.walkman.tv.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 局域网同步歌单（手机版；电视版在设置页里用同一套 LanSyncController）。
 *
 * 用法：两台设备连同一个 WiFi，打开这一页。
 * ① 一台设备当“主机”亮出二维码，另一台点「扫码添加设备」扫一下就配对了；
 *    没法扫码就输对方的 6 位同步码 + 地址。
 * ② 同一 WiFi 下的设备会自动出现在「发现的设备」里，点一下配对、勾上。
 * ③ 选好方向（默认双向），点「开始同步」。只同步歌单（收藏 / 历史 / 自建列表），
 *    不同步已下载的音频文件。
 */
@Composable
fun MobileSyncScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { LanSyncController(appContainer, context) }
    val discovered by controller.discovered.collectAsState()
    val saved by controller.savedPeers.collectAsState()
    val selected by controller.selectedIds.collectAsState()
    var manualHost by remember { mutableStateOf("") }
    var manualCode by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var pairingTarget by remember { mutableStateOf<NsdSyncDiscovery.DiscoveredPeer?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var pairingError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { controller.start() }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    val myPayload = remember { controller.myQrPayload() }
    val qrBitmap by produceState<android.graphics.Bitmap?>(null, myPayload) {
        value = withContext(Dispatchers.Default) {
            myPayload?.let { encodeQrToBitmap(it, 512) }
        }
    }

    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("同步歌单", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "同一 WiFi 下，电视和手机互相同步歌单（收藏 / 历史 / 自建列表）。不同步已下载的音频。",
                fontSize = 13.sp, color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
        }

        // 本机二维码 + 同步码
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本机同步码", fontSize = 14.sp, color = AppColors.TextSecondary)
                Text(
                    controller.myCode.chunked(2).joinToString(" "),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary,
                )
                controller.myAddress()?.let {
                    Text("本机地址 $it", fontSize = 12.sp, color = AppColors.TextMuted)
                }
                Spacer(Modifier.height(10.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "同步二维码",
                        modifier = Modifier.size(200.dp).background(Color.White),
                    )
                } else {
                    Text("二维码生成中…（需连上 WiFi）", fontSize = 13.sp, color = AppColors.TextMuted)
                }
                Spacer(Modifier.height(6.dp))
                Text("另一台设备扫这个码就能配对", fontSize = 12.sp, color = AppColors.TextMuted)
            }
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(onClick = { showScanner = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("扫码添加设备", fontSize = 14.sp, color = AppColors.BrandPrimary)
                }
            }
            scanError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = AppColors.Danger)
            }
            Spacer(Modifier.height(12.dp))
        }

        // 手动添加
        item {
            Text("手动添加设备", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = manualHost,
                onValueChange = { manualHost = it },
                placeholder = { Text("对方地址，如 192.168.3.5:8765") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualCode,
                onValueChange = { if (it.length <= 6) manualCode = it },
                placeholder = { Text("对方 6 位同步码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val r = controller.addManual(manualHost, manualCode)
                        manualError = r.fold({ null }, { "添加失败：${it.message}" })
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
            ) {
                Text("添加并勾选", fontSize = 14.sp)
            }
            manualError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = 12.sp, color = AppColors.Danger)
            }
            Spacer(Modifier.height(16.dp))
        }

        // 发现的设备
        item {
            Text("发现的设备（同一 WiFi）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            if (discovered.isEmpty()) {
                Text("还没发现设备：确认另一台也在同步页、连着同一个 WiFi；或用扫码 / 手动添加。", fontSize = 13.sp, color = AppColors.TextMuted, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        items(discovered.size) { i ->
            val d = discovered[i]
            val savedPeer = controller.savedPeerFor(d.host ?: "", d.port)
            DiscoveredRow(
                name = d.displayName,
                address = "${d.host ?: "?"}:${d.port}",
                paired = savedPeer != null,
                selected = savedPeer?.let { it.id in selected } ?: false,
                onClick = {
                    if (savedPeer != null) {
                        controller.toggleSelect(savedPeer.id)
                    } else {
                        pairingTarget = d
                        pairingCode = ""
                        pairingError = null
                    }
                },
            )
        }

        // 已保存的设备
        item {
            Spacer(Modifier.height(12.dp))
            Text("已保存的设备", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            if (saved.isEmpty()) {
                Text("还没有配对过的设备", fontSize = 13.sp, color = AppColors.TextMuted, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        items(saved.size) { i ->
            val peer = saved[i]
            SavedPeerRow(
                peer = peer,
                selected = peer.id in selected,
                onToggle = { controller.toggleSelect(peer.id) },
                onDelete = {
                    scope.launch {
                        appContainer.syncPeerStore.removePeer(peer.id)
                        controller.refreshSaved()
                    }
                },
            )
        }

        // 方向 + 开始同步
        item {
            Spacer(Modifier.height(16.dp))
            Text("同步方向", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row {
                SyncDirection.entries.forEach { dir ->
                    FilterChip(
                        selected = controller.direction == dir,
                        onClick = { controller.direction = dir },
                        label = { Text(dir.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.BrandPrimary,
                            selectedLabelColor = Color.White,
                        ),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { scope.launch { controller.syncSelected() } },
                enabled = !controller.busy,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.BrandPrimary),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (controller.busy) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (controller.busy) "同步中…" else "开始同步", fontSize = 15.sp)
            }
            controller.status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = AppColors.TextSecondary)
            }
            Spacer(Modifier.height(96.dp))
        }
    }

    // 给发现的设备填同步码配对
    pairingTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pairingTarget = null },
            title = { Text("配对 ${target.displayName}") },
            text = {
                Column {
                    Text("在对方设备的同步页看 6 位同步码，填进来：", fontSize = 14.sp, color = AppColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { if (it.length <= 6) pairingCode = it },
                        placeholder = { Text("6 位同步码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    pairingError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, fontSize = 12.sp, color = AppColors.Danger)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = controller.addManual("${target.host}:${target.port}", pairingCode)
                        r.fold(
                            { pairingTarget = null },
                            { pairingError = "配对失败：${it.message}" },
                        )
                    }
                }) { Text("配对", color = AppColors.BrandPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { pairingTarget = null }) { Text("取消") }
            },
        )
    }

    // 扫码添加设备（盖在同步页上面）
    if (showScanner) {
        QrScannerScreen(
            onScanned = { payload ->
                scope.launch {
                    val r = controller.addScanned(payload)
                    r.fold(
                        { showScanner = false; scanError = null },
                        { scanError = "扫码失败：${it.message}" },
                    )
                }
            },
            onClose = { showScanner = false },
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
    }
    } // Box
}

@Composable
private fun DiscoveredRow(
    name: String,
    address: String,
    paired: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) AppColors.BrandPrimary else AppColors.TextMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text(address, fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        Text(if (paired) "已配对" else "点一下配对", fontSize = 12.sp, color = if (paired) AppColors.BrandPrimary else AppColors.TextMuted)
    }
}

@Composable
private fun SavedPeerRow(
    peer: SyncPeer,
    selected: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
        )
        Column(
            Modifier.weight(1f).clickable(onClick = onToggle),
        ) {
            Text(peer.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
            Text("${peer.host}:${peer.port}", fontSize = 12.sp, color = AppColors.TextSecondary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = AppColors.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
