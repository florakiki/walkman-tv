package com.walkman.tv

import android.app.Application
import android.os.Build
import com.walkman.tv.di.AppContainer
import com.walkman.tv.di.CrashDiagnostics
import com.whl.quickjs.android.QuickJSLoader
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 诊断（第十六轮）：写进程名和分流结果进诊断文件，下次启动闪退时能看到
        // 是主进程还是 :playback 进程、这里有没有把主进程误判成播放进程。
        val playback = isPlaybackProcess()
        runCatching { CrashDiagnostics.recordStartup(this, currentProcessName(), playback) }
        // :playback 进程只跑播放器（第十五轮）：不初始化 QuickJS、容器、设置和 UI 状态。
        // Application.onCreate 每个进程都会跑，不分流的话 :playback 会白白建一套容器、
        // 起一遍 bootstrap，还多占一份内存。播放器由 PlaybackService 自己建。
        if (playback) return
        container = AppContainer(this)
        // 第二十四轮：QuickJS native 库加载挪后台线程，别占主线程的首帧时间。
        // 脚本加载在 bootstrap 里等 quickJsReady 完成才跑，不会撞上未加载的库；
        // 加载失败也照常 complete（脚本加载自己有 runCatching 兜底），不卡住启动。
        Thread({
            runCatching { QuickJSLoader.init() }
            container.quickJsReady.complete(Unit)
        }, "quickjs-init").start()
        // ExoPlayer must be created on the main thread.
        container.initPlayback()
        container.bootstrap()
    }

    /** 当前进程名（API 28+ 用 getProcessName，低版本读 /proc/self/cmdline；读不到返回“未知”）。 */
    private fun currentProcessName(): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readBytes().decodeToString().trimEnd('\u0000')
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "未知"
    }

    /** 当前是不是 :playback 播放进程。 */
    private fun isPlaybackProcess(): Boolean = currentProcessName().endsWith(":playback")

    companion object {
        lateinit var instance: App
            private set
        lateinit var container: AppContainer
            private set
    }
}
