package com.walkman.tv.ui.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.walkman.tv.ui.theme.AppColors

/**
 * 手机版主题：普通 material3（不是 tv-material3，手机上要触屏组件），
 * 颜色 token 和电视版同一套 QQ 风：浅白底 #F4F6F4、白卡片、QQ绿 #00C168、深绿 #00A656。
 */
private val MobileColorScheme = lightColorScheme(
    primary = AppColors.BrandPrimary,
    onPrimary = Color.White,
    secondary = AppColors.BrandPrimary,
    background = AppColors.BgDeep,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.BgPanel,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.CardElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    error = AppColors.Danger,
)

@Composable
fun MobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MobileColorScheme, content = content)
}
