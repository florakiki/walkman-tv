package com.walkman.tv.data.sync

import android.content.Context
import android.os.Build
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 同步身份 + 配对设备，存在 filesDir/sync.json。
 * 本机 token 是启动时生成一次的随机串（之后一直用），二维码里带完整 token；
 * 6 位同步码是 token 的前 6 位（小写 hex），给手动配对用 —— 局域网内、用户当面确认才有效。
 */
class SyncPeerStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "sync.json"),
        SyncPeerData.serializer(),
        SyncPeerData(
            deviceId = UUID.randomUUID().toString(),
            deviceToken = UUID.randomUUID().toString().replace("-", ""),
            deviceName = Build.MODEL ?: "KITV音乐",
        ),
    )

    // 先给一个临时身份；AppContainer.bootstrap() 里的 loadAll() 会马上用磁盘上的覆盖
    // （文件不存在才是第一次装，临时身份就转正、第一次 persist 时写盘）。
    private val _data = MutableStateFlow(
        SyncPeerData(
            deviceId = UUID.randomUUID().toString(),
            deviceToken = UUID.randomUUID().toString().replace("-", ""),
            deviceName = Build.MODEL ?: "KITV音乐",
        ),
    )
    val data: StateFlow<SyncPeerData> = _data.asStateFlow()

    suspend fun loadAll() {
        _data.value = withContext(Dispatchers.IO) { store.load() }
    }

    /** 本机 6 位同步码（token 前 6 位）。 */
    fun shortCode(): String = _data.value.deviceToken.take(6)

    /** 服务端鉴权：完整 token 或 6 位同步码都认（局域网 + 当面确认的前提下）。 */
    fun checkToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val t = token.trim()
        val full = _data.value.deviceToken
        return t == full || t.equals(shortCode(), ignoreCase = true)
    }

    /** 配对成功后记下来（按 host:port 去重，token 以最新一次为准）。 */
    suspend fun savePeer(peer: SyncPeer) {
        val cur = _data.value
        val deduped = cur.knownPeers.filterNot { it.host == peer.host && it.port == peer.port }
        _data.value = cur.copy(knownPeers = deduped + peer)
        persist()
    }

    suspend fun removePeer(id: String) {
        _data.value = _data.value.copy(knownPeers = _data.value.knownPeers.filter { it.id != id })
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { store.save(_data.value) }
}
