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
    /** 最近一次落上焦点的卡片 key（没有就是 null）。左边面板按右回网格时用它当目标。 */
    var lastFocusedKey: String? = null
        private set
    /** 右边内容最顶上那张卡片的 key（没有就是 null）。左边面板按右落到它上面（Yang 定：落最顶上，不是落上次那张）。由 RecommendGrid 在数据到齐时更新。 */
    var topCardKey: String? = null

    /** 拿一张卡片的 FocusRequester（同一张卡片每次拿的是同一个）。 */
    fun focuserFor(key: String): FocusRequester = focusers.getOrPut(key) { FocusRequester() }

    /**
     * 卡片用的 Modifier：把焦点请求器挂上去，焦点落上/离开时上报一声，
     * 让 [restoreTo] 知道已经落上、可以停了。
     */
    fun modifierFor(key: String): Modifier = Modifier
        .focusRequester(focuserFor(key))
        .onFocusChanged {
            if (it.isFocused) { focusedKeys.add(key); lastFocusedKey = key } else focusedKeys.remove(key)
        }

    /** 把焦点送到指定的那张卡片上。key 为空就什么都不做。卡片可能还没组合好（数据刚到、网格刚滚回顶部），请求器先建出来再重试：卡片组合时会挂上同一个请求器，之后某次重试就能落上；12 次都落不上就放弃，不抛异常。 */
    suspend fun restoreTo(key: String?) {
        key ?: return
        val focuser = focuserFor(key)
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
