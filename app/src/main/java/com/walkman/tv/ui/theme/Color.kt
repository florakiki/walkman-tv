package com.walkman.tv.ui.theme

import androidx.compose.ui.graphics.Color

/** QQ 音乐风浅色主题（florakiki fork 改版，见 README“Fork 说明”）。
 *  Tokens 取自 2026-11-15 定稿的 KI 音乐设计图方案 B：
 *  浅白底 #F4F6F4、白卡片、QQ 绿 #00C168、深绿 #00A656、明黄 #FFE45C。
 *  Token 名字与原版保持一致，只换取值，其余界面不用改代码。
 *
 *  Token taxonomy:
 *   - Bg* / Card*       → surfaces
 *   - BrandPrimary      → the brand hue itself; do not bind to it directly in UI, use semantics
 *   - Selected / Focus  → interaction states (Focus is a *deeper* green so the
 *                          focus ring reads above the static selected pill on white)
 *   - Progress / Waveform → playback graphics
 *   - LyricActive       → current line; deep green so it pops against gray idle lines
 *   - Danger / Warning / Info → functional accents reserved for non-brand semantics
 *   - Text*             → typography ramp
 *   - Source* / Quality* → per-platform identity tints (used in chips + focused-row tint)
 */
object AppColors {
    val BgDeep = Color(0xFFF4F6F4)
    val BgPanel = Color(0xFFFFFFFF)
    val Card = Color(0xFFFFFFFF)
    val CardElevated = Color(0xFFF7F9F7)

    // ── Brand (single hue, two intensities). UI binds to semantic aliases below. ────────
    val BrandPrimary = Color(0xFF00C168)          // QQ 绿
    val BrandBright  = Color(0xFF00A656)          // 深绿 — 焦点环 / 当前歌词

    // Kept as physical aliases for incremental migration. New code should prefer the
    // semantic tokens below.
    val AccentGreen = BrandPrimary
    val AccentGreenDim = Color(0x5900C168)
    val NeonGreen = BrandPrimary

    // ── Interaction state (semantic) ──────────────────────────────────────────────────────
    val Selected      = BrandPrimary                       // pill 选中 / 当前 tab / now-playing
    val Focus         = BrandBright                        // 焦点环 — 比 Selected 略深一档
    val Progress      = BrandPrimary.copy(alpha = 0.92f)   // 进度条 / 波形主线
    val WaveformGhost = BrandPrimary.copy(alpha = 0.40f)   // 波形副线

    // ── Functional (non-brand) ───────────────────────────────────────────────────────────
    val Danger  = Color(0xFFEF5A6F)   // 删除 / 退出确定 / 清空 — 红
    val Warning = Color(0xFFE8B341)   // 「已切换音源」「降级到 320k」 — 琥珀
    val Info    = Color(0xFF61AFEF)   // MV badge / 通知 — 蓝

    val TextPrimary = Color(0xFF1A1D1B)
    val TextSecondary = Color(0xFF5A635D)
    val TextMuted = Color(0xFF8A938C)

    val NavActiveBg = Color(0xFFE6F8EE)
    val NavInactiveBg = Color(0xFFECEFEC)

    val FocusBorder = Focus
    val FocusFill = Color(0x1400C168)

    val LyricActive = BrandBright            // 深绿，区分「当前演唱行」vs「选中」
    val LyricIdle = Color(0xFF8A938C)

    // QQ 风新增 token
    val GreenSoft = Color(0xFFE6F8EE)
    val GreenMid = Color(0xFFBFEEDA)
    val Line = Color(0xFFE6EAE6)
    val Yellow = Color(0xFFFFE45C)

    // Source tints (酷我/酷狗/QQ/网易/咪咕/本地) — from DesignSystem.swift
    // 未改：平台标识色，在浅底上一样可读。
    val SourceKw = Color(0xFFF26D4D)
    val SourceKg = Color(0xFF1AB0F0)
    val SourceTx = Color(0xFF33CC8C)
    val SourceWy = Color(0xFFF24552)
    val SourceMg = Color(0xFFF28A1A)
    val SourceLocal = Color(0xFF8E8E93)

    // Quality badge tints — 未改
    val QualityHires = Color(0xFFD99E21)
    val QualityLossless = Color(0xFF664DD9)
    val QualityHq = Color(0xFF1A8C6B)
    val QualitySq = Color(0xFF8E8E93)
}
