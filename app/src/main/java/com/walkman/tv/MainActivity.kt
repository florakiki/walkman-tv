package com.walkman.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import com.walkman.tv.ui.RootScreen
import com.walkman.tv.ui.mobile.MobileRootScreen
import com.walkman.tv.ui.mobile.MobileTheme
import com.walkman.tv.ui.theme.AppColors
import com.walkman.tv.ui.theme.WalkmanTvTheme
import com.walkman.tv.di.CrashDiagnostics

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 内置诊断记录：抓未捕获的 Kotlin 层异常，连同页面/焦点/按键/播歌写进本机文件
        // （设置页“调试”里能看到）。只抓 Kotlin 层崩溃，原生 SIGSEGV 抓不住；
        // 记完仍按默认方式死掉，绝不吞掉崩溃。
        CrashDiagnostics.install(applicationContext)
        CrashDiagnostics.nowPlayingProvider = {
            runCatching {
                App.container.playbackController.state.value.currentTrack
                    ?.let { "${it.name} - ${it.singer}" }
            }.getOrNull()
        }
        // 手机 flavor（src/mobile/res/values/bools.xml）为 true → 触屏竖屏界面；
        // 电视版为 false → 原来的遥控界面。包名不同，两版可以共存。
        val isMobile = resources.getBoolean(R.bool.is_mobile)
        setContent {
            // Keep the screen awake while audio/video is playing.
            val playing = App.container.playbackController.state.collectAsState().value.isPlaying
            LaunchedEffect(playing) {
                if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // 第二十四轮：等首帧画完再连 :playback 播放进程——冷启动时两个进程同时抢
            // 电视弱 CPU 会拖慢首帧。播歌前 ensureConnected() 也会主动发起连接，
            // 所以晚几百毫秒连不影响播放。
            // 第二十六轮：量首帧耗时（App.onCreate → 第一帧画出来），写进诊断记录
            // 供 Yang 在设置页“查看诊断记录”里看秒数。withFrameNanos 的帧时间是
            // uptime 纳秒，和 App.onCreateStartMs（uptime 毫秒）同源可减。
            // 只记一次（首帧）；超过 60 秒就是热启动（Application 早建好了），
            // 数字没意义，不记。写盘放 IO 线程，不占主线程。
            LaunchedEffect(Unit) {
                withFrameNanos { frameTimeNanos ->
                    val startMs = App.onCreateStartMs
                    if (startMs > 0L) {
                        val elapsedMs = frameTimeNanos / 1_000_000 - startMs
                        if (elapsedMs in 0..60_000L) {
                            launch(Dispatchers.IO) {
                                runCatching { CrashDiagnostics.recordFirstFrame(applicationContext, elapsedMs) }
                            }
                        }
                    }
                }
                App.container.playbackController.startConnection()
            }
            if (isMobile) {
                MobileTheme {
                    MobileRootScreen(modifier = Modifier.fillMaxSize().background(AppColors.BgDeep))
                }
            } else {
                WalkmanTvTheme {
                    RootScreen(modifier = Modifier.fillMaxSize().background(AppColors.BgDeep))
                }
            }
        }
    }

    /**
     * Forward the remote's "Menu / Options / 三条横线" button into the Compose tree via
     * [App.container.events]. Needed because the AndroidView-hosted [androidx.media3.ui.PlayerView]
     * in MV mode owns focus and Compose's onPreviewKeyEvent never sees the event.
     *
     * Several keycodes are accepted because OEM remotes are inconsistent — Sony BRAVIA Z9K maps
     * its three-line button to KEYCODE_MENU, but other vendors send KEYCODE_TV_CONTENTS_MENU
     * or KEYCODE_BUTTON_MODE for the same physical key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 诊断记录：记最近按的键（只记按下那一下，长按重复不记，免得同一个键刷屏）。
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            CrashDiagnostics.noteKey(KeyEvent.keyCodeToString(event.keyCode))
        }
        if (App.container.events.keyInterceptor?.invoke(event) == true) return true
        return try {
            super.dispatchKeyEvent(event)
        } catch (e: IllegalStateException) {
            // 1.0.1 兜底：Compose 1.6.8 的焦点搜索（TwoDimensionalFocusSearch.activeNode）
            // 在焦点节点已脱离组合时抛 "Searching for active node in inactive hierarchy"，
            // 按方向键移焦点就闪退。只认这一条：把这次按键吃掉、记一条诊断，App 不闪退；
            // 其他 IllegalStateException 原样抛出去，不吞。
            if (e.message?.contains("inactive hierarchy") == true) {
                android.util.Log.w(
                    "FocusGuard",
                    "swallowed focus-search crash on ${KeyEvent.keyCodeToString(event.keyCode)}",
                    e,
                )
                runCatching {
                    CrashDiagnostics.recordFocusGuard(
                        applicationContext,
                        "按键 ${KeyEvent.keyCodeToString(event.keyCode)} 移焦点时节点已脱离，已拦住未闪退",
                    )
                }
                true
            } else throw e
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_TV_CONTENTS_MENU,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_GUIDE -> {
                App.container.events.postMenuKey()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
