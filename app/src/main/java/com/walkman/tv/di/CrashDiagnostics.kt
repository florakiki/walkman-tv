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
    private const val MAX_KEYS = 12
    private const val MAX_STACK_LINES = 80

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

    /** 读最近一次的诊断记录（没有就是 null）。 */
    fun readLastReport(context: Context): String? {
        val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        return runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    }

    /** 清掉诊断记录。 */
    fun clearReport(context: Context) {
        runCatching { File(File(context.filesDir, DIR_NAME), FILE_NAME).delete() }
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
