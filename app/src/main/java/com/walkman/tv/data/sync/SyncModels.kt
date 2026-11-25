package com.walkman.tv.data.sync

import kotlinx.serialization.Serializable

/**
 * 局域网同步歌单：同一 WiFi 下，电视和手机互相传歌单（收藏 / 播放历史 / 自建列表）。
 * 不同步已下载的音频文件本身。没有账号、没有云、不搭服务器 —— 走本机 HTTP + mDNS 发现。
 */

/** 一台已配对过的设备。token 为空表示只发现、还没配对（配对时填对方的 6 位同步码或扫码）。 */
@Serializable
data class SyncPeer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String? = null,
)

/** sync.json 的内容：本机身份 + 配对过的设备。 */
@Serializable
data class SyncPeerData(
    val deviceId: String,
    val deviceToken: String,
    val deviceName: String,
    val knownPeers: List<SyncPeer> = emptyList(),
)

/** 同步方向：默认双向（两边合起来一样）。 */
enum class SyncDirection(val label: String) {
    BOTH("双向同步"),
    PULL("仅从对方拉取"),
    PUSH("仅推送到对方"),
}

/** 一次合并的结果（本机视角）。 */
@Serializable
data class MergeReport(
    val addedLove: Int = 0,
    val addedHistory: Int = 0,
    val addedLists: Int = 0,
    val addedTracks: Int = 0,
    val totalLists: Int = 0,
)

/** 对方 /api/sync/info 返回的信息（不用鉴权，只报名字和设备号）。 */
@Serializable
data class SyncInfo(
    val name: String,
    val deviceId: String,
    val version: String,
)
