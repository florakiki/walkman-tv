package com.walkman.tv.di

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 开着的浮层登记的“顶上内容”焦点入口：左边面板按右、顶栏按下时，先看栈顶——
 * 有浮层开着就进浮层里的内容（歌手页第一张专辑卡片 / 热门歌曲第 0 行 / 歌单第 0 行 …），
 * 不去碰被盖住的首页网格（那张卡片在浮层下面，焦点落上去用户也看不见，
 * 看着就是“按右没反应”——2026-11-26 陈奕迅歌手页开着时，焦点在暂停键上按右就是这么卡住的）。
 *
 * 主线程用；浮层用 DisposableEffect 登记/注销，嵌套浮层后登记者在栈顶
 * （专辑详情盖在歌手页上面时，栈顶是专辑详情；它关掉注销后，栈顶回到歌手页）。
 */
class OverlayEntry(
    val key: String,
    /** 顶上内容的焦点请求器（浮层在屏幕上，节点一般已组合好，同步请求一次就能落上）。 */
    val focuser: FocusRequester,
    /** 是不是已经落上焦点（浮层自己用 onFocusChanged 维护）。 */
    val isFocused: () -> Boolean,
    /** 同步没落上时的异步重试（顶上内容还没组合好时先滚回顶部再试），返回是不是落上了。 */
    val retry: (suspend () -> Boolean)? = null,
)

/**
 * Process-wide event bus. [MainActivity] forwards hardware-key presses that need to cross the
 * AndroidView boundary (e.g. KEYCODE_MENU while a PlayerView has focus). [LocalServer] forwards
 * payloads received over the phone-to-TV QR flow (search keyword, custom-source script URL or
 * raw script content).
 */
class AppEvents {
    private val _menuKey = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val menuKey: SharedFlow<Unit> = _menuKey.asSharedFlow()

    /** Screen-level hook run from [MainActivity.dispatchKeyEvent] before any view sees the key —
     *  works even when nothing in Compose holds focus (e.g. player controls auto-hidden). Return
     *  true to consume. Main thread only; the owner clears it when it leaves. */
    var keyInterceptor: ((android.view.KeyEvent) -> Boolean)? = null

    private val _qrSearchKeyword = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val qrSearchKeyword: SharedFlow<String> = _qrSearchKeyword.asSharedFlow()

    private val _qrScriptUrl = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val qrScriptUrl: SharedFlow<String> = _qrScriptUrl.asSharedFlow()

    private val _qrScriptText = MutableSharedFlow<String>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val qrScriptText: SharedFlow<String> = _qrScriptText.asSharedFlow()

    private val _qrPlaylistName = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val qrPlaylistName: SharedFlow<String> = _qrPlaylistName.asSharedFlow()

    private val _qrSonglistUrl = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val qrSonglistUrl: SharedFlow<String> = _qrSonglistUrl.asSharedFlow()

    /** Emitted when the full-screen player overlay closes — TrackLists collect this to restore
     *  focus on the row that was last clicked. */
    private val _playerClosed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val playerClosed: SharedFlow<Unit> = _playerClosed.asSharedFlow()

    private val overlayEntries = mutableListOf<OverlayEntry>()
    fun registerOverlayEntry(entry: OverlayEntry) {
        overlayEntries.removeAll { it.key == entry.key }
        overlayEntries.add(entry)
        CrashDiagnostics.currentOverlay = entry.key
    }
    fun unregisterOverlayEntry(key: String) {
        overlayEntries.removeAll { it.key == key }
        CrashDiagnostics.currentOverlay = overlayEntries.lastOrNull()?.key
    }
    /** 最上层开着的浮层的入口（没有浮层开着就是 null）。 */
    val topOverlayEntry: OverlayEntry? get() = overlayEntries.lastOrNull()

    fun postMenuKey() { _menuKey.tryEmit(Unit) }
    fun postQrSearchKeyword(q: String) { _qrSearchKeyword.tryEmit(q) }
    fun postQrScriptUrl(url: String) { _qrScriptUrl.tryEmit(url) }
    fun postQrScriptText(text: String) { _qrScriptText.tryEmit(text) }
    fun postQrPlaylistName(name: String) { _qrPlaylistName.tryEmit(name) }
    fun postQrSonglistUrl(url: String) { _qrSonglistUrl.tryEmit(url) }
    fun postPlayerClosed() { _playerClosed.tryEmit(Unit) }
}
