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
        return super.dispatchKeyEvent(event)
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
