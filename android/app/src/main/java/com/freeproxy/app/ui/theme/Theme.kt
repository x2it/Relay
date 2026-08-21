package com.freeproxy.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// —— 暗色主题配色：深色底 + 低饱和蓝 + 细腻语义色 ——
val Seed       = Color(0xFF6B8AFF)   // 主色：暗色下调亮的蓝紫
val SeedPress  = Color(0xFF5A7AFF)   // 按下态
val SeedDim    = Color(0xFF263258)   // 主色淡底（chip/cell 用）
val SeedText   = Color(0xFFA7B8FF)
val Surface    = Color(0xFF0F1216)   // 页面底：深灰黑
val Surface2   = Color(0xFF171B21)   // 卡片底：深灰
val Surface3   = Color(0xFF1F242B)   // 次级底
val OnSurface  = Color(0xFFE7E9EC)   // 主文字：接近白
val OnSurfaceDim  = Color(0xFF9AA2AC) // 次文字：中性灰
val OnSurfaceMute = Color(0xFF6B7280) // 更弱的辅助文字
val Outline    = Color(0xFF2C323B)   // 描边：深灰
val Divider    = Color(0xFF1F242B)

// 语义色：同样低饱和、克制
val Good = Color(0xFF2BA47A)   // Emerald 降饱和
val Warn = Color(0xFFD9822B)   // Orange 降饱和
val Bad  = Color(0xFFD64545)   // Red 降饱和

private val LightScheme = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = SeedDim,
    onPrimaryContainer = SeedText,
    secondary = OnSurfaceDim,
    onSecondary = Color.White,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface2,
    onSurface = OnSurface,
    surfaceVariant = Surface3,
    onSurfaceVariant = OnSurfaceDim,
    outline = Outline,
    outlineVariant = Divider,
    error = Bad,
    onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA7B8FF),
    onPrimary = Color(0xFF0D1428),
    primaryContainer = Color(0xFF263258),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE7E9EC),
    surface = Color(0xFF171B21),
    onSurface = Color(0xFFE7E9EC),
    surfaceVariant = Color(0xFF1F242B),
    onSurfaceVariant = Color(0xFF9AA2AC),
    outline = Color(0xFF2C323B),
    error = Color(0xFFE06969),
)

val AppTypography = Typography(
    titleLarge   = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium  = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge    = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium   = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge   = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium  = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FreeProxyTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
