package com.walkman.tv.di

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 内置诊断记录（检查器）：抓未捕获的 Kotlin 层异常，连同当时的页面（哪个浮层开着）、
 * 焦点落在哪、最近按的几个键、正在播哪首歌，一起写进本机文件；设置页“调试”里能看到
 * 最近一次的记录，下次再闪退就有据可查。
 *
 * 老实说清楚：这只能抓 Kotlin/Java 层的崩溃。原生层 SIGSEGV（比如脏音频把电视的
 * 原生解码器撞死）是系统直接把进程杀掉，不走这里的 handler，抓不住——真机上如果
 * 再退，优先还是拿电视 logcat，看 Fatal signal 那一行定性。
 *
 * 默认只记不弹框，记录只保存在本机（filesDir/crash-diagnostics/last-crash.txt），
 * 不联网上传。记完仍按默认方式死掉，绝不吞掉崩溃。
 */
object CrashDiagnostics {
    private const val DIR_NAME = "crash-diagnostics"
    private const val FILE_NAME = "last-crash.txt"
    private const val FAILURE_FILE_NAME = "playback-failures.txt"
    private const val MAX_KEYS = 12
    private const val MAX_STACK_LINES = 80
    private const val MAX_FAILURE_ENTRIES = 20

    /** 记上次启动时的包安装时间（新包第一次启动清旧记录用）。 */
    private const val PREFS_NAME = "crash-diagnostics"
    private const val KEY_LAST_UPDATE_TIME = "lastUpdateTime"

    /** 当前在哪个页面（RootScreen 的 section / 播放页 / 退出框）。 */
    @Volatile
    var currentScreen: String = "启动中"

    /** 当前开着哪个浮层（AppEvents 登记的栈顶入口 key，没有就是 null）。 */
    @Volatile
    var currentOverlay: String? = null

    /** 焦点最后落在哪（卡片 key / 歌曲行）。 */
    @Volatile
    var lastFocus: String? = null

    private val recentKeys = ArrayDeque<String>()
    private val keysLock = Any()

    /** 正在播哪首歌（MainActivity 安装时传入；读 playbackController 的 StateFlow，快照是线程安全的）。 */
    var nowPlayingProvider: (() -> String?)? = null

    @Volatile
    private var installed = false

    /** 记一次按键（只记按下那一下，长按重复不记，免得同一个键刷屏）。 */
    fun noteKey(label: String) {
        synchronized(keysLock) {
            recentKeys.addLast(label)
            while (recentKeys.size > MAX_KEYS) recentKeys.removeFirst()
        }
    }

    /** 记焦点落点。 */
    fun noteFocus(where: String) {
        lastFocus = where
    }

    /** 装上未捕获异常 handler（只装一次）。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        // 新包第一次启动：清掉上一版的旧诊断记录（见 maybeClearStaleRecords）。
        maybeClearStaleRecords(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, thread, throwable) }
            // 记完仍按默认方式死掉：之前的 handler 负责弹系统崩溃框、杀进程。
            // 没有上一个 handler 时（理论上不会，Android 运行时自带一个），自己杀进程，
            // 绝不能让崩溃被吞掉、App 带着坏状态继续跑。
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** 读最近一次的诊断记录（没有就是 null）。崩溃报告在前，播放失败记录附在后面，
     *  同一个对话框里一起看，不另起查看入口。 */
    fun readLastReport(context: Context): String? {
        val dir = File(context.filesDir, DIR_NAME)
        val crash = runCatching {
            val file = File(dir, FILE_NAME)
            if (file.exists()) file.readText() else null
        }.getOrNull()
        val failures = readFailures(context)
        return when {
            crash != null && failures != null -> crash + "\n播放失败记录：\n" + failures
            crash != null -> crash
            failures != null -> "播放失败记录：\n" + failures
            else -> null
        }
    }

    /** 清掉诊断记录（崩溃报告 + 播放失败记录一起清）。 */
    fun clearReport(context: Context) {
        runCatching { File(File(context.filesDir, DIR_NAME), FILE_NAME).delete() }
        runCatching { File(File(context.filesDir, DIR_NAME), FAILURE_FILE_NAME).delete() }
    }

    /**
     * 静默记一条播放失败（换源失败写日志那处用）：真播不了时屏幕上什么都不显示，
     * 只记在这里。记哪首歌、当时在哪个页面、换了哪几个源、最后是什么原因。
     * 不弹框、不 toast、不碰状态行。只留最近 [MAX_FAILURE_ENTRIES] 条。
     */
    fun recordPlaybackFailure(context: Context, song: String, sources: List<String>, reason: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[播放失败] $time")
            appendLine("歌曲：$song")
            appendLine("页面：$currentScreen")
            appendLine("浮层：${currentOverlay ?: "无"}")
            appendLine("换了这些源：${if (sources.isEmpty()) "未知" else sources.joinToString("、")}")
            appendLine("原因：$reason")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记一条音质核对（2026-11-26）：探头实测档位低于请求档位，或探头不认这个容器时，
     * 记哪首歌、请求的是什么档位、实测到的是什么。跟播放失败写同一个文件、同一个查看
     * 入口（设置 → 查看诊断记录），只留最近 [MAX_FAILURE_ENTRIES] 条。只在有落差时
     * 记（实测与请求一致不记），不逐首刷屏。
     */
    fun recordQualityNote(context: Context, song: String, requested: String, measured: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[音质] $time")
            appendLine("歌曲：$song")
            appendLine("请求档位：$requested")
            appendLine("实测：$measured")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记一次按音质换源（第二十七轮）：主源给的地址实测音质低于请求档位，换其它源
     * 找到更好的就播更好的。记哪首歌、从哪个源的什么实测音质、换到哪个源的
     * 什么实测音质。跟 [音质] 写同一个文件、同一个查看入口，只在真换了源时记。
     */
    fun recordQualitySwitch(
        context: Context,
        song: String,
        fromLabel: String,
        fromMeasured: String,
        toLabel: String,
        toMeasured: String,
    ) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[按音质换源] $time")
            appendLine("歌曲：$song")
            appendLine("从：$fromLabel（实测 $fromMeasured）")
            appendLine("到：$toLabel（实测 $toMeasured）")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 留痕（2026-11-27 第十五轮）：解析出地址后、setMediaItem 之前记一条“正在播”，
     * 把赢家地址（URL + 来源平台 + 歌名 + 档位）写进播放失败记录同一个文件、
     * 同一个 20 条上限，设置页“查看诊断记录”本来就读它。播放进程原生闪退死后，
     * 主进程靠这条知道它当时在播哪条地址，也能验证隔离到底生效没有（死了之后
     * 这条还在、主进程还在）。调用方用 runCatching 包住，写盘失败绝不拦播放。
     */
    fun recordNowPlaying(context: Context, song: String, originLabel: String, quality: String, url: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[正在播] $time")
            appendLine("歌曲：$song")
            appendLine("来源：$originLabel")
            appendLine("档位：$quality")
            appendLine("地址：$url")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 新包第一次启动时清掉旧诊断记录（第十八轮）：重装同包名 APK 不清应用数据，
     * 上一版的 [播放进程死亡] / [播放失败] 一直留着，跟新版的混在一起没法一眼
     * 分清是哪一版的。versionName 每轮都是 1.0.0-debug，分不出轮次，用包的
     * lastUpdateTime（装新包就变）判断：跟上次记的不一样就是刚装了新包，清掉。
     * 只清一次（清完把新的 lastUpdateTime 记下来，下次启动就不清了）。
     */
    fun maybeClearStaleRecords(context: Context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val lastUpdate = info.lastUpdateTime
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getLong(KEY_LAST_UPDATE_TIME, -1L)
            if (stored != -1L && stored != lastUpdate) {
                clearReport(context)
            }
            if (stored != lastUpdate) {
                prefs.edit().putLong(KEY_LAST_UPDATE_TIME, lastUpdate).apply()
            }
        }
    }

    /**
     * 记一条播放进程死亡（第十五轮）：原生解码器把 :playback 进程杀掉后，主进程
     * 感知到、重启并换源 / 跳下一首时记在这里：哪首歌、哪个地址、几点、是原生崩溃
     * 还是内存杀死。跟播放失败写同一个文件、同一个查看入口。
     * [decoder]（第十八轮）：当时在用的解码器（"ffmpeg" / "c2.android.*" 等），
     * 有就多写一行，下次诊断直接看到是哪条解码器在崩。
     */
    fun recordPlaybackProcessDeath(
        context: Context,
        song: String,
        url: String,
        originLabel: String,
        reason: String,
        decoder: String? = null,
    ) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[播放进程死亡] $time")
            appendLine("歌曲：$song")
            appendLine("来源：$originLabel")
            appendLine("地址：$url")
            if (decoder != null) appendLine("解码器：$decoder")
            appendLine("原因：$reason")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记一次启动（第十六轮）：写进程名和分流结果。下次启动就闪退时，诊断里能
     * 看到是主进程还是 :playback 进程、App.onCreate 有没有把主进程误判成播放进程。
     */
    fun recordStartup(context: Context, processName: String, isPlaybackProcess: Boolean) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[启动] $time")
            appendLine("进程：$processName")
            appendLine("分流：${if (isPlaybackProcess) ":playback（只跑播放器）" else "主进程（完整初始化）"}")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记播放进程启动（第十六轮）：PlaybackService.onCreate 里写，建播放器成功写用
     * 的是哪条解码路（FFmpeg 优先 / 平台解码），失败写异常。启动就闪退时靠这条
     * 判断是不是播放器建不起来。
     */
    fun recordPlaybackServiceStart(context: Context, detail: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[播放进程启动] $time")
            appendLine("详情：$detail")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记主进程的 MediaController 连上了 :playback 的 MediaSession（第十七轮）。
     * 诊断里没有这条就说明主进程根本没连上播放器服务——第十六轮后
     * “全部不能听”排查时缺的就是这条证据。
     */
    fun recordPlaybackConnected(context: Context) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[播放器已连接] $time")
            appendLine("详情：主进程已连上 :playback 的 MediaSession")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记启动时音源脚本加载情况（第十七轮）：已加载几个、叫什么。
     * 解析卡住时靠这条判断是不是根本没脚本可用（0 个就是脚本没加载上）。
     */
    fun recordScriptStatus(context: Context, names: List<String>) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[音源] $time")
            appendLine("已加载：${names.size} 个")
            appendLine("名称：${if (names.isEmpty()) "无" else names.joinToString("、")}")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记音频设置实况（第二十轮）：主进程发了什么、:playback 收到什么、实际在
     * 用哪个解码器、增益处理器到底处理了多少采样。增益没效果时靠这条定性——
     * 处理链被直通绕过、还是增益本身没生效，一看就知道（第二十五轮：增益固定开，
     * 不再有“服务没收到命令”这一项）。
     */
    fun recordAudioState(context: Context, where: String, detail: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[音频设置] $time")
            appendLine("位置：$where")
            appendLine(detail.trimEnd())
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /**
     * 记首帧耗时（第二十六轮）：App.onCreate → 第一帧画出来，单位毫秒。启动慢
     * 的排查就看这条——目标 3–5 秒。写在 MainActivity 第一帧的 withFrameNanos
     * 回调里；只记一次（首帧），不重复写。
     */
    fun recordFirstFrame(context: Context, elapsedMs: Long) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = buildString {
            appendLine("[首帧] $time")
            appendLine("耗时：${elapsedMs}ms（App.onCreate → 第一帧画出来）")
            appendLine("目标：3000–5000ms")
        }.trimEnd()
        appendFailureEntry(context, entry)
    }

    /** 往共享诊断文件里追加一条，保留最新的 [MAX_FAILURE_ENTRIES] 条。 */
    private fun appendFailureEntry(context: Context, entry: String) {
        runCatching {
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val file = File(dir, FAILURE_FILE_NAME)
            val existing = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
            val entries = existing.split("\n---\n").filter { it.isNotBlank() }.takeLast(MAX_FAILURE_ENTRIES - 1)
            val all = (entries + entry).joinToString("\n---\n")
            file.writeText(all + "\n")
        }
    }

    private fun readFailures(context: Context): String? {
        val file = File(File(context.filesDir, DIR_NAME), FAILURE_FILE_NAME)
        return runCatching {
            if (!file.exists()) return null
            file.readText().ifBlank { null }
        }.getOrNull()
    }

    /** 版本号从包管理器读（本项目没开 BuildConfig 生成，和 UpdateManager 同一套读法）。 */
    private fun appVersion(context: Context): String {
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("未知")
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val keys = synchronized(keysLock) { recentKeys.toList() }
        val nowPlaying = runCatching { nowPlayingProvider?.invoke() }.getOrNull()
        val stack = throwable.stackTraceToString()
            .lineSequence()
            .take(MAX_STACK_LINES)
            .joinToString("\n")
        val report = buildString {
            appendLine("时间：$time")
            appendLine("版本：${appVersion(context)}")
            appendLine("页面：$currentScreen")
            appendLine("浮层：${currentOverlay ?: "无"}")
            appendLine("焦点：${lastFocus ?: "未知"}")
            appendLine("正在播：${nowPlaying ?: "无"}")
            appendLine("最近按键：${if (keys.isEmpty()) "无" else keys.joinToString(" → ")}")
            appendLine("崩溃线程：${thread.name}")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine("堆栈：")
            appendLine(stack)
        }
        File(dir, FILE_NAME).writeText(report)
    }
}
