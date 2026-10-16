package com.danxing.spatialscene.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    secondary = Color(0xFFB8C4FF),
    tertiary = Color(0xFFFFB68D),
    background = Color(0xFF0B1026),
    surface = Color(0xFF121831)
)

private val FallbackLight = darkColorScheme(
    primary = Color(0xFF3B6EA5),
    secondary = Color(0xFF5A6B9A),
    tertiary = Color(0xFF8C5A34)
)

@Composable
fun SpatialSceneTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
