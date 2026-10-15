package local.wangchangdian.videograbber

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 两套浅色主题：浅橙（默认）/ 浅绿 */
object AppThemeColors {
    val Orange = lightColorScheme(
        primary = Color(0xFFFF8A3D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE7D6),
        onPrimaryContainer = Color(0xFF7A3A00),
        secondary = Color(0xFFFFB27A),
        background = Color(0xFFFFF8F3),
        onBackground = Color(0xFF2B2118),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF2B2118),
        surfaceVariant = Color(0xFFFFEFE3),
        onSurfaceVariant = Color(0xFF7A6A5C),
    )

    val Green = lightColorScheme(
        primary = Color(0xFF4CAF7D),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD9F2E3),
        onPrimaryContainer = Color(0xFF12432B),
        secondary = Color(0xFF86C9A6),
        background = Color(0xFFF4FBF6),
        onBackground = Color(0xFF1B2A21),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B2A21),
        surfaceVariant = Color(0xFFE6F4EB),
        onSurfaceVariant = Color(0xFF5C6F63),
    )

    fun scheme(key: String) = if (key == "green") Green else Orange

    /** 主题渐变色（用于标题栏与主按钮） */
    fun gradient(key: String): List<Color> = if (key == "green") {
        listOf(Color(0xFF7CD8A6), Color(0xFF3FA46D))
    } else {
        listOf(Color(0xFFFFA96B), Color(0xFFF97316))
    }

    /** 页面背景渐变（顶部微染色，底部回到白） */
    fun backgroundGradient(key: String): List<Color> = if (key == "green") {
        listOf(Color(0xFFE9F7EF), Color(0xFFF7FBF8))
    } else {
        listOf(Color(0xFFFFEDE0), Color(0xFFFFF9F5))
    }
}

@Composable
fun VideoGrabberTheme(themeKey: String, content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        // 保持浅色风格（用户明确要求浅色主题）
        AppThemeColors.scheme(themeKey)
    } else {
        AppThemeColors.scheme(themeKey)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
