package com.walkman.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * 进浮层之前记住点的那张卡片，出来时焦点回到那张卡片上。
 *
 * 为什么需要它：浮层关掉那一下，焦点不会自己回到点下去的那张卡片上——
 * 焦点是跟着浮层里的列表一起被 dispose 掉的，Compose 1.6.8 里被移除的焦点节点
 * 只会直接 clearFocus(force = true)，焦点整个被清空，不会自己送回父级；而下层
 * 页面上的 focusRestorer 只在“焦点重新进入”时才恢复，浮层关掉那一下并不会触发
 * 它。所以关掉浮层后焦点就丢了（页面上没有高亮、方向键也没反应），用户再按一下
 * 返回，上一层浮层/页面也被关掉，看着就像“按一下返回直接回了首页”。
 *
 * 用法（在浮层状态所在的那一层）：
 * ```
 * val restore = rememberOverlayFocusRestore()
 * var entryKey by remember { mutableStateOf<String?>(null) }
 * // 卡片上：modifier = restore.modifierFor(key)
 * // 点卡片进浮层时：entryKey = key; openOverlay = ...
 * // 浮层关掉时：LaunchedEffect(overlay) { if (overlay == null) restore.restoreTo(entryKey) }
 * ```
 * 卡片可能刚重新组合好（比如歌手页关掉专辑详情时专辑区重新展开），多试几次；
 * 试完还落不上就放弃，不抛异常，焦点保持现状，不比原来更差。
 *
 * 注意：本项目用的 Compose 1.6.8 里 FocusRequester.requestFocus() 不返回值
 * （Unit），没法直接知道落上没有，所以每张卡片用 modifierFor 挂一个
 * onFocusChanged 上报落位；焦点一落上循环就停，不会把用户随后移走的焦点抢回来。
 */
class OverlayFocusRestore {
    private val focusers = mutableMapOf<String, FocusRequester>()
    private val focusedKeys = mutableSetOf<String>()

    /** 拿一张卡片的 FocusRequester（同一张卡片每次拿的是同一个）。 */
    fun focuserFor(key: String): FocusRequester = focusers.getOrPut(key) { FocusRequester() }

    /**
     * 卡片用的 Modifier：把焦点请求器挂上去，焦点落上/离开时上报一声，
     * 让 [restoreTo] 知道已经落上、可以停了。
     */
    fun modifierFor(key: String): Modifier = Modifier
        .focusRequester(focuserFor(key))
        .onFocusChanged {
            if (it.isFocused) focusedKeys.add(key) else focusedKeys.remove(key)
        }

    /** 把焦点送回指定的那张卡片上。key 为空、或卡片已经不在了，就什么都不做。 */
    suspend fun restoreTo(key: String?) {
        val focuser = key?.let { focusers[it] } ?: return
        repeat(12) {
            // 已经落上了就停：不然循环剩下的几次会把用户随后按方向键移走的焦点抢回来。
            if (key in focusedKeys) return
            runCatching { focuser.requestFocus() }
            delay(70)
        }
    }
}

@Composable
fun rememberOverlayFocusRestore(): OverlayFocusRestore = remember { OverlayFocusRestore() }
