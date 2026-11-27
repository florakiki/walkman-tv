package com.walkman.tv.playback

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 播放进程死亡防死循环（2026-11-27 第十五轮）：
 *
 * 光把播放器隔离进 :playback 还不够——主进程重启播放进程后，如果重试同一首歌，
 * 解码器会再次 SIGSEGV，无限死循环。所以 setMediaItem 之前先把“正在尝试播哪首歌”
 * 写进磁盘（[PlaybackAttempt]，MPD 那个 dirty flag 的思路），死了之后靠它知道
 * 该跳过哪首；同一首歌死两次就写进 [CrashedTrack]，24 小时内启动自动恢复时跳过它。
 *
 * 两份文件都放在 filesDir（跟设置、快照同一处，主进程和 :playback 共用一个 filesDir，
 * 但只有主进程读写这两份——:playback 只管播，不管记）。读不到 / 写失败一律返回
 * 默认值 / 吞掉，绝不拦播放。
 */
object PlaybackCrashGuard {
    private const val ATTEMPT_FILE = "playback-attempt.json"
    private const val CRASHED_FILE = "crashed-tracks.json"

    /** 崩过的歌拉黑多久：24 小时。过期后自动放出来（用户也可以手动点它再试）。 */
    private const val CRASH_TTL_MS = 24 * 60 * 60 * 1000L

    /** 正在尝试播的歌（setMediaItem 之前写，播停 / 进程释放时清）。 */
    @Serializable
    data class PlaybackAttempt(
        val trackId: String = "",
        val song: String = "",
        val url: String = "",
        val originLabel: String = "",
        val quality: String = "",
        val startedAtMs: Long = 0L,
    )

    /** 死过两次、被跳过的歌（启动自动恢复时跳过它）。 */
    @Serializable
    data class CrashedTrack(
        val trackId: String,
        val song: String,
        val crashedAtMs: Long,
    )

    @Serializable
    private data class CrashedTracks(val tracks: List<CrashedTrack> = emptyList())

    private fun attemptStore(context: Context) = JsonStore(
        File(context.filesDir, ATTEMPT_FILE),
        PlaybackAttempt.serializer(),
        PlaybackAttempt(),
    )

    private fun crashedStore(context: Context) = JsonStore(
        File(context.filesDir, CRASHED_FILE),
        CrashedTracks.serializer(),
        CrashedTracks(),
    )

    /** setMediaItem 之前写：正在尝试播哪首歌、哪个地址。 */
    fun writeAttempt(context: Context, trackId: String, song: String, url: String, originLabel: String, quality: String) {
        runCatching {
            attemptStore(context).save(
                PlaybackAttempt(trackId, song, url, originLabel, quality, System.currentTimeMillis()),
            )
        }
    }

    /** 播停 / 播放器释放时清掉（写回空记录）。 */
    fun clearAttempt(context: Context) {
        runCatching { attemptStore(context).save(PlaybackAttempt()) }
    }

    /** 读正在尝试播的歌；没有（空记录 / 读不到）返回 null。 */
    fun readAttempt(context: Context): PlaybackAttempt? {
        return runCatching { attemptStore(context).load() }.getOrNull()
            ?.takeIf { it.trackId.isNotBlank() }
    }

    /** 这首歌死透了（两次都死 / 换源也死），拉黑 24 小时。过期条目顺手清掉。 */
    fun markCrashed(context: Context, trackId: String, song: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val store = crashedStore(context)
            val kept = store.load().tracks
                .filter { now - it.crashedAtMs < CRASH_TTL_MS && it.trackId != trackId }
            store.save(CrashedTracks(kept + CrashedTrack(trackId, song, now)))
        }
    }

    /** 这首歌是不是在 24 小时拉黑期内（启动自动恢复时问）。 */
    fun isCrashed(context: Context, trackId: String): Boolean {
        val now = System.currentTimeMillis()
        return runCatching {
            crashedStore(context).load().tracks.any {
                it.trackId == trackId && now - it.crashedAtMs < CRASH_TTL_MS
            }
        }.getOrDefault(false)
    }
}
