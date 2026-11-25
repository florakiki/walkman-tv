package com.walkman.tv.data.sync

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 局域网同步的客户端 + 二维码载荷编解码。服务端在 LocalServer 里（同步接口 /api/sync 下）。
 *
 * 二维码内容：kitvmusic://sync?host=192.168.3.5&port=8765&token=<完整token>&name=<设备名>
 * 对方扫码后直接用完整 token 配对；手动配对则输 6 位同步码（token 前 6 位）。
 */
object LanSync {
    private const val TAG = "LanSync"

    fun qrPayload(host: String, port: Int, token: String, name: String): String =
        "kitvmusic://sync?host=$host&port=$port&token=$token&name=" +
            URLEncoder.encode(name, Charsets.UTF_8.name())

    /** 解析扫码 / 粘贴得到的 kitvmusic://sync 链接；格式不对返回 null。 */
    fun parseQrPayload(text: String): SyncPeer? = runCatching {
        val raw = text.trim()
        require(raw.startsWith("kitvmusic://sync?")) { "不是同步二维码" }
        val query = raw.substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to URLDecoder.decode(kv[1], Charsets.UTF_8.name()) else null
        }.toMap()
        val host = params["host"]?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val port = params["port"]?.toIntOrNull() ?: return@runCatching null
        val token = params["token"]?.takeIf { it.isNotBlank() }
        val name = params["name"]?.takeIf { it.isNotBlank() } ?: host
        val id = params["device"]?.takeIf { it.isNotBlank() } ?: "$host:$port"
        SyncPeer(id = id, name = name, host = host, port = port, token = token)
    }.getOrNull()
}

/** 对方同步服务的客户端（OkHttp，走 AppContainer.httpClient）。 */
class SyncClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchInfo(host: String, port: Int): Result<SyncInfo> = runCatching {
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url("http://$host:$port/api/sync/info").build()
            http.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                val body = resp.body?.string() ?: error("空响应")
                json.decodeFromString(SyncInfo.serializer(), body)
            }
        }
    }

    /** 拉取对方的歌单备份 JSON（与 LibraryStore.exportBackupJson() 同格式）。 */
    suspend fun fetchPlaylists(host: String, port: Int, token: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("http://$host:$port/api/sync/playlists?token=$token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) error("对方拒绝：同步码不对，请核对后再试")
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                resp.body?.string() ?: error("空响应")
            }
        }
    }

    /** 把备份 JSON 推给对方，对方合并后返回它的合并报告。 */
    suspend fun pushPlaylists(host: String, port: Int, token: String, backupJson: String): Result<MergeReport> =
        runCatching {
            withContext(Dispatchers.IO) {
                val body = backupJson.toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("http://$host:$port/api/sync/playlists?token=$token")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (resp.code == 401) error("对方拒绝：同步码不对，请核对后再试")
                    require(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val raw = resp.body?.string() ?: error("空响应")
                    json.decodeFromString(MergeReport.serializer(), raw)
                }
            }
        }

    companion object {
        private const val TAG = "SyncClient"
    }
}

internal fun logSync(msg: String) = Log.i("LanSync", msg)
