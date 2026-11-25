package com.walkman.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.lightColorScheme

// QQ 音乐风浅色主题（florakiki fork 改版，见 README“Fork 说明”）。
private val WalkmanColorScheme: ColorScheme = lightColorScheme(
    primary = AppColors.AccentGreen,
    onPrimary = Color.White,
    secondary = AppColors.AccentGreen,
    background = AppColors.BgDeep,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.BgPanel,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.CardElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    border = AppColors.FocusBorder,
)

/** Spacing / radius tokens (mirrors DS.Spacing / DS.Radius). */
object Dimens {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val radiusSmall = 8.dp
    val radiusMedium = 12.dp
    val radiusLarge = 18.dp
    val radiusXLarge = 28.dp
}

@Composable
fun WalkmanTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WalkmanColorScheme,
        typography = WalkmanTypography,
        content = content,
    )
}

private val WalkmanTypography = Typography().let { base ->
    base.copy(
        displayMedium = base.displayMedium.copy(fontSize = 32.sp),
        titleLarge = base.titleLarge.copy(fontSize = 22.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp),
    )
}
