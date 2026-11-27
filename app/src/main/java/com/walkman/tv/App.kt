package com.walkman.tv

import android.app.Application
import android.os.Build
import com.walkman.tv.di.AppContainer
import com.whl.quickjs.android.QuickJSLoader
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // :playback 进程只跑播放器（第十五轮）：不初始化 QuickJS、容器、设置和 UI 状态。
        // Application.onCreate 每个进程都会跑，不分流的话 :playback 会白白建一套容器、
        // 起一遍 bootstrap，还多占一份内存。播放器由 PlaybackService 自己建。
        if (isPlaybackProcess()) return
        // Load the QuickJS native lib once for the whole process (custom-source engine).
        QuickJSLoader.init()
        container = AppContainer(this)
        // ExoPlayer must be created on the main thread.
        container.initPlayback()
        container.bootstrap()
    }

    /** 当前是不是 :playback 播放进程（Application.getProcessName()，API 28+；
     *  低版本读 /proc/self/cmdline）。 */
    private fun isPlaybackProcess(): Boolean {
        val name = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readBytes().decodeToString().trimEnd('\u0000')
            }
        }.getOrNull()
        return name?.endsWith(":playback") == true
    }

    companion object {
        lateinit var instance: App
            private set
        lateinit var container: AppContainer
            private set
    }
}
