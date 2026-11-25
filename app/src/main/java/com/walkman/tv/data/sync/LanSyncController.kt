package com.walkman.tv.data.sync

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.walkman.tv.di.AppContainer
import com.walkman.tv.di.getLanIp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 同步页的状态机，电视设置页和手机同步页共用。
 * 界面只负责显示 + 收集用户点选，连接 / 合并都在这里。
 *
 * 选设备：发现列表（mDNS）和已保存列表里的设备都可以勾选；没配对过的设备点一下会先让填
 * 对方 6 位同步码（或扫码），配对成功后自动勾上。同步时按选中的设备一个一个来。
 */
class LanSyncController(
    private val container: AppContainer,
    context: Context,
) {
    private val discovery = NsdSyncDiscovery(context.applicationContext)
    private val client = SyncClient(container.httpClient)

    val discovered: StateFlow<List<NsdSyncDiscovery.DiscoveredPeer>> = discovery.discovered

    private val _savedPeers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val savedPeers: StateFlow<List<SyncPeer>> = _savedPeers.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    var direction by mutableStateOf(SyncDirection.BOTH)
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** 本机 6 位同步码（显示用）。 */
    val myCode: String get() = container.syncPeerStore.shortCode()

    /** 本机二维码内容（扫码配对用）；没拿到局域网 IP 或本地服务没起来时为 null。 */
    fun myQrPayload(): String? {
        val ip = getLanIp() ?: return null
        val port = container.localServer?.boundPort ?: return null
        val data = container.syncPeerStore.data.value
        return LanSync.qrPayload(ip, port, data.deviceToken, data.deviceName)
    }

    /** 本机地址（显示用，如 192.168.3.5:8765）。 */
    fun myAddress(): String? {
        val ip = getLanIp() ?: return null
        val port = container.localServer?.boundPort ?: return null
        return "$ip:$port"
    }

    /** 进同步页时调：刷新已保存列表、开始 mDNS 发现。 */
    fun start() {
        refreshSaved()
        discovery.startDiscovery()
    }

    /** 离开同步页时调：停掉发现（广播继续留着，别的设备还能找到本机）。 */
    fun stop() {
        discovery.stopDiscovery()
    }

    fun refreshSaved() {
        _savedPeers.value = container.syncPeerStore.data.value.knownPeers
    }

    /** 已保存的设备里有没有这台（按 host:port 认）。 */
    fun savedPeerFor(host: String, port: Int): SyncPeer? =
        _savedPeers.value.firstOrNull { it.host == host && it.port == port }

    fun isSelected(id: String): Boolean = id in _selectedIds.value

    fun toggleSelect(id: String) {
        _selectedIds.value =
            if (id in _selectedIds.value) _selectedIds.value - id else _selectedIds.value + id
    }

    fun selectPeer(peer: SyncPeer) {
        _selectedIds.value = _selectedIds.value + peer.id
    }

    /** 手动添加：输 IP（或 host:port）+ 对方 6 位同步码，先连一下确认对方真是 KITV音乐。 */
    suspend fun addManual(hostInput: String, code: String): Result<SyncPeer> = runCatching {
        val (host, port) = parseHostPort(hostInput.trim())
        val token = code.trim()
        require(token.length >= 6) { "同步码是 6 位" }
        val info = client.fetchInfo(host, port).getOrThrow()
        val peer = SyncPeer(
            id = info.deviceId,
            name = info.name,
            host = host,
            port = port,
            token = token,
        )
        container.syncPeerStore.savePeer(peer)
        refreshSaved()
        selectPeer(peer)
        peer
    }

    /** 扫码添加：二维码里自带完整 token，直接配对。 */
    suspend fun addScanned(payload: String): Result<SyncPeer> = runCatching {
        val peer = LanSync.parseQrPayload(payload) ?: error("这个二维码不是 KITV音乐 的同步码")
        require(!peer.token.isNullOrBlank()) { "二维码里没有配对信息" }
        // 扫码前先确认对方在线，避免把过期地址存下来。
        val info = client.fetchInfo(peer.host, peer.port).getOrThrow()
        val fixed = peer.copy(id = info.deviceId, name = info.name)
        container.syncPeerStore.savePeer(fixed)
        refreshSaved()
        selectPeer(fixed)
        fixed
    }

    /** 对已选中的设备逐个同步；结果写进 status（界面直接显示）。 */
    suspend fun syncSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) {
            status = "先勾选要同步的设备（电视 / 手机列在上面）"
            return
        }
        val peers = _savedPeers.value.filter { it.id in ids }
        if (peers.isEmpty()) {
            status = "选中的设备还没配对：点它一下，填对方的 6 位同步码"
            return
        }
        busy = true
        val lines = mutableListOf<String>()
        try {
            for (peer in peers) {
                lines += runCatching { syncPeer(peer) }.getOrElse { "与 ${peer.name} 同步失败：${it.message}" }
            }
        } finally {
            busy = false
        }
        status = lines.joinToString("\n")
    }

    private suspend fun syncPeer(peer: SyncPeer): String {
        val token = peer.token ?: error("没配对，先填对方的 6 位同步码")
        val store = container.libraryStore
        return when (direction) {
            SyncDirection.PULL -> {
                val remote = client.fetchPlaylists(peer.host, peer.port, token).getOrThrow()
                val r = store.mergeBackupJson(remote).getOrThrow()
                "从 ${peer.name} 拉取：本机新增 ${r.addedLove + r.addedHistory + r.addedTracks} 首、${r.addedLists} 个歌单"
            }
            SyncDirection.PUSH -> {
                val r = client.pushPlaylists(peer.host, peer.port, token, store.exportBackupJson()).getOrThrow()
                "已推送到 ${peer.name}：对方新增 ${r.addedLove + r.addedHistory + r.addedTracks} 首、${r.addedLists} 个歌单"
            }
            SyncDirection.BOTH -> {
                val remote = client.fetchPlaylists(peer.host, peer.port, token).getOrThrow()
                val mine = store.mergeBackupJson(remote).getOrThrow()
                val theirs = client.pushPlaylists(peer.host, peer.port, token, store.exportBackupJson()).getOrThrow()
                "与 ${peer.name} 双向同步：本机新增 ${mine.addedLove + mine.addedHistory + mine.addedTracks} 首、${mine.addedLists} 个歌单；对方新增 ${theirs.addedLove + theirs.addedHistory + theirs.addedTracks} 首、${theirs.addedLists} 个歌单"
            }
        }
    }

    private fun parseHostPort(input: String): Pair<String, Int> {
        val s = input.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val parts = s.split(":")
        return if (parts.size == 2) {
            parts[0] to (parts[1].toIntOrNull() ?: error("端口不对"))
        } else {
            s to (container.localServer?.boundPort ?: 8765)
        }
    }
}
